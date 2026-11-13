package com.cep.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cep.app.support.ApiClient;
import com.cep.app.support.IntegrationTest;
import com.cep.shared.api.util.Ids;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class SecurityIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RoleHierarchy roleHierarchy;

    ApiClient api;
    String admin;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, mapper);
        admin = api.adminToken();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void unauthenticatedRequestGetsProblemJson401() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongPasswordAndUnknownTenantAreIndistinguishable() throws Exception {
        for (var creds : new String[][] {{"default", "wrong-password-123"}, {"no-such-tenant", ApiClient.ADMIN_PASSWORD}}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(api.json(Map.of("tenant", creds[0], "email", ApiClient.ADMIN_EMAIL, "password", creds[1]))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    @Test
    void jwtIdentifiesUserTenantAndRoles() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorType").value("USER"))
                .andExpect(jsonPath("$.name").value(ApiClient.ADMIN_EMAIL))
                .andExpect(jsonPath("$.authorities").value(hasItem("ROLE_ADMIN")));
    }

    @Test
    void tamperedJwtIsRejected() throws Exception {
        String[] parts = admin.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiKeyLifecycle_createUseRevoke() throws Exception {
        var created = api.createApplication(admin, unique("orders-service"), Set.of("journeys:execute"), null);
        String key = created.get("apiKey").asString();
        String appId = created.get("application").get("id").asString();
        assertThat(key).startsWith("cep_");

        // Listed without the secret.
        mvc.perform(get("/api/v1/admin/applications").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(key))));

        mvc.perform(get("/api/v1/auth/me").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorType").value("APPLICATION"))
                .andExpect(jsonPath("$.authorities").value(hasItem("SCOPE_journeys:execute")))
                .andExpect(jsonPath("$.authorities").value(hasItem("ROLE_INTEGRATION")))
                .andExpect(header().string("X-RateLimit-Limit", "600"));

        // Integration credentials can't administer the tenant.
        mvc.perform(get("/api/v1/admin/applications").header("X-API-Key", key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(post("/api/v1/admin/applications/" + appId + "/revoke").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(get("/api/v1/auth/me").header("X-API-Key", key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));

        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE entity_id = :id").param("id", appId)
                .query(Integer.class).single()).as("create + revoke audited").isEqualTo(2);
    }

    @Test
    void malformedOrAmbiguousCredentialsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("X-API-Key", "cep_not-a-real-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
        mvc.perform(get("/api/v1/auth/me").header("X-API-Key", "x").header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_CREDENTIALS"));
    }

    @Test
    void viewerCannotAdministerAndRoleHierarchyHolds() throws Exception {
        String email = unique("viewer") + "@test.local";
        mvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(api.json(Map.of("email", email, "password", "Viewer-Passw0rd!", "roles", Set.of("VIEWER")))))
                .andExpect(status().isCreated());
        String viewer = api.login("default", email, "Viewer-Passw0rd!");

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());

        var reachable = AuthorityUtils.authorityListToSet(
                roleHierarchy.getReachableGrantedAuthorities(AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        assertThat(reachable).contains("ROLE_ADMIN", "ROLE_MARKETER", "ROLE_VIEWER").doesNotContain("ROLE_INTEGRATION");
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        mvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(api.json(Map.of("email", "not-an-email", "password", "x", "roles", Set.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("roles")));

        mvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(api.json(Map.of("email", unique("weak") + "@test.local", "password", "short", "roles", Set.of("VIEWER")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));

        mvc.perform(post("/api/v1/admin/applications").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(api.json(Map.of("name", unique("bad-scope"), "scopes", Set.of("root:all")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SCOPE"));
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        var mine = api.createApplication(admin, unique("tenant-a-app"), null, null);
        String myAppId = mine.get("application").get("id").asString();

        // Second tenant with its own admin.
        UUID tenantB = Ids.newId();
        String keyB = unique("tenant-b");
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("INSERT INTO tenants (id, tenant_key, name, status, created_at, updated_at) VALUES (:id, :k, 'B', 'ACTIVE', :n, :n)")
                .param("id", tenantB).param("k", keyB).param("n", now).update();
        jdbc.sql("""
                INSERT INTO users (id, tenant_id, email, password_hash, roles, status, created_at, updated_at)
                VALUES (:id, :t, 'admin@b.local', :pw, ARRAY['ADMIN'], 'ACTIVE', :n, :n)
                """).param("id", Ids.newId()).param("t", tenantB).param("pw", encoder.encode("Tenant-B-Passw0rd"))
                .param("n", now).update();
        String adminB = api.login(keyB, "admin@b.local", "Tenant-B-Passw0rd");

        mvc.perform(get("/api/v1/admin/applications").header("Authorization", "Bearer " + adminB))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(myAppId))));
        mvc.perform(post("/api/v1/admin/applications/" + myAppId + "/revoke").header("Authorization", "Bearer " + adminB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        // Same email in another tenant is a different identity: tenant A's admin password does not work in B.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(api.json(Map.of("tenant", keyB, "email", ApiClient.ADMIN_EMAIL, "password", ApiClient.ADMIN_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicOperationalEndpointsAndRequestIdPropagation() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/v1/auth/login")))
                .andExpect(content().string(containsString("/api/v1/admin/jobs/dead")));
        mvc.perform(get("/api/v1/auth/me").header("X-Request-Id", "trace-abc-123"))
                .andExpect(header().string("X-Request-Id", "trace-abc-123"))
                .andExpect(jsonPath("$.requestId").value("trace-abc-123"));
    }
}
