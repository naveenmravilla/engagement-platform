package com.cep.app.audience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cep.app.support.ApiClient;
import com.cep.app.support.IntegrationTest;
import com.cep.audience.api.AudienceFacade;
import com.cep.audience.api.AudienceFacade.AudienceStatus;
import com.cep.shared.api.util.Ids;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AudienceIT extends IntegrationTest {
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AudienceFacade facade;

    ApiClient api;
    String admin;
    String marketer;
    String viewer;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, mapper);
        admin = api.adminToken();
        marketer = TOKENS.computeIfAbsent("MARKETER", r -> user(r));
        viewer = TOKENS.computeIfAbsent("VIEWER", r -> user(r));
    }

    private String user(String role) {
        try {
            String email = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
            mvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(api.json(Map.of("email", email, "password", "Passw0rd-" + role, "roles", Set.of(role)))))
                    .andExpect(status().isCreated());
            return api.login("default", email, "Passw0rd-" + role);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions call(MockHttpServletRequestBuilder req, String bearer, Object body) throws Exception {
        req.header("Authorization", "Bearer " + bearer);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : api.json(body));
        }
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return api.read(r.andReturn().getResponse().getContentAsString());
    }

    private String createAudience(Object content) throws Exception {
        return body(call(post("/api/v1/audiences"), marketer, Map.of("content", content))
                .andExpect(status().isCreated())).get("audienceId").asString();
    }

    @Test
    void createGeneratesSequentialIdsAndStartsAsDraftVersionOne() throws Exception {
        JsonNode first = body(call(post("/api/v1/audiences"), marketer,
                Map.of("name", "Active customers", "content", Map.of("customerStatus", "ACTIVE")))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.content.customerStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.compiledCriteria.all[0].field").value("customer.attributes.customerStatus")));
        String id = first.get("audienceId").asString();
        assertThat(id).matches("AUD1\\d{4,}");
        String second = createAudience(Map.of("tier", "GOLD"));
        assertThat(Long.parseLong(second.substring(3))).isEqualTo(Long.parseLong(id.substring(3)) + 1);
    }

    @Test
    void clientChosenIdIsKeptAndDuplicatesAreRejected() throws Exception {
        String id = unique("VIP");
        call(post("/api/v1/audiences"), marketer, Map.of("audienceId", id, "content", Map.of("vip", true)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/audiences/" + id))
                .andExpect(jsonPath("$.audienceId").value(id))
                .andExpect(jsonPath("$.name").value(id));
        call(post("/api/v1/audiences"), marketer, Map.of("audienceId", id, "content", Map.of("vip", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIENCE_EXISTS"));
        call(post("/api/v1/audiences"), marketer, Map.of("audienceId", "a b", "content", Map.of("vip", true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIENCE_ID"));
    }

    @Test
    void invalidContentIsRejectedWithFieldPaths() throws Exception {
        call(post("/api/v1/audiences"), marketer,
                "{\"content\":{\"all\":[{\"field\":\"customer.attributes.age\",\"op\":\"older\",\"value\":1},{\"tier\":null}]}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIENCE_CONTENT"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("content.all[0].op")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("content.all[1].tier")));
        call(post("/api/v1/audiences"), marketer, "{\"name\":\"no content\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void idempotencyKeyReplaysTheOriginalCreate() throws Exception {
        String key = unique("idem");
        var req = Map.of("content", Map.of("city", "Singapore"));
        String first = body(call(post("/api/v1/audiences").header("Idempotency-Key", key), marketer, req)
                .andExpect(status().isCreated())).get("audienceId").asString();
        call(post("/api/v1/audiences").header("Idempotency-Key", key), marketer, req)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audienceId").value(first));
        call(post("/api/v1/audiences").header("Idempotency-Key", key), marketer, Map.of("content", Map.of("city", "KL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.sql("SELECT count(*) FROM audiences WHERE audience_key = :k").param("k", first)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void updatesCreateVersionsAndHistoryStaysReadable() throws Exception {
        String id = createAudience(Map.of("tier", "GOLD"));
        call(put("/api/v1/audiences/" + id).header("If-Match", "\"1\""), marketer,
                Map.of("content", Map.of("tier", java.util.List.of("GOLD", "SILVER"))))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.compiledCriteria.all[0].op").value("in"));
        // Stale If-Match: someone else saved version 2 meanwhile.
        call(put("/api/v1/audiences/" + id).header("If-Match", "\"1\""), marketer, Map.of("content", Map.of("tier", "X")))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));
        // Same content again: no new version.
        call(put("/api/v1/audiences/" + id), marketer, Map.of("content", Map.of("tier", java.util.List.of("GOLD", "SILVER"))))
                .andExpect(jsonPath("$.version").value(2));
        // Rename only: no new version either.
        call(put("/api/v1/audiences/" + id), marketer, Map.of("name", "Gold and silver"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.name").value("Gold and silver"));

        call(get("/api/v1/audiences/" + id + "?version=1"), viewer, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.content.tier").value("GOLD"));
        call(get("/api/v1/audiences/" + id + "?version=9"), viewer, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIENCE_VERSION_NOT_FOUND"));
        call(get("/api/v1/audiences/" + id + "/versions"), viewer, null)
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
    }

    @Test
    void lifecycleActivateDeactivateArchive() throws Exception {
        String id = createAudience(Map.of("tier", "GOLD"));
        call(post("/api/v1/audiences/" + id + "/activate"), marketer, null).andExpect(jsonPath("$.status").value("ACTIVE"));
        call(post("/api/v1/audiences/" + id + "/activate"), marketer, null).andExpect(status().isOk());
        call(post("/api/v1/audiences/" + id + "/deactivate"), marketer, null).andExpect(jsonPath("$.status").value("INACTIVE"));

        call(delete("/api/v1/audiences/" + id), marketer, null).andExpect(status().isForbidden());
        call(delete("/api/v1/audiences/" + id), admin, null).andExpect(jsonPath("$.status").value("ARCHIVED"));
        call(post("/api/v1/audiences/" + id + "/activate"), marketer, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDIENCE_ARCHIVED"));
        call(put("/api/v1/audiences/" + id), marketer, Map.of("content", Map.of("tier", "X")))
                .andExpect(status().isConflict());

        call(get("/api/v1/audiences?size=100"), viewer, null)
                .andExpect(jsonPath("$.items[*].audienceId").value(not(hasItem(id))));
        call(get("/api/v1/audiences?status=ARCHIVED&q=" + id), viewer, null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].audienceId").value(id));

        assertThat(jdbc.sql("SELECT action FROM audit_logs WHERE entity_type = 'audience' AND entity_id = :id ORDER BY created_at, action")
                .param("id", id).query(String.class).list())
                .contains("AUDIENCE_CREATED", "AUDIENCE_ACTIVATED", "AUDIENCE_DEACTIVATED", "AUDIENCE_ARCHIVED");
    }

    @Test
    void rolesAreEnforced() throws Exception {
        call(post("/api/v1/audiences"), viewer, Map.of("content", Map.of("a", 1))).andExpect(status().isForbidden());
        String id = createAudience(Map.of("a", 1));
        call(get("/api/v1/audiences/" + id), viewer, null).andExpect(status().isOk());
        call(post("/api/v1/audiences/" + id + "/activate"), viewer, null).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audiences")).andExpect(status().isUnauthorized());
    }

    @Test
    void evaluateWithInlineContextReportsMatchedAndFailedConditions() throws Exception {
        String id = createAudience(Map.of("all", java.util.List.of(
                Map.of("field", "customer.attributes.membershipType", "op", "eq", "value", "GOLD"),
                Map.of("any", java.util.List.of(
                        Map.of("field", "event.payload.amount", "op", "gte", "value", 1000),
                        Map.of("field", "customer.attributes.city", "op", "in", "value", java.util.List.of("Singapore")))))));
        var gold = Map.of("customer", Map.of("attributes", Map.of("membershipType", "GOLD", "city", "Singapore")),
                "event", Map.of("payload", Map.of("amount", 200)));
        call(post("/api/v1/audiences/" + id + "/evaluate"), viewer, Map.of("context", gold))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(true))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.matched[*].path").value(hasItem("$.all[1].any[1]")))
                .andExpect(jsonPath("$.failed[0].field").value("event.payload.amount"));
        var silver = Map.of("customer", Map.of("attributes", Map.of("membershipType", "SILVER")));
        call(post("/api/v1/audiences/" + id + "/evaluate"), viewer, Map.of("context", silver))
                .andExpect(jsonPath("$.member").value(false));

        call(post("/api/v1/audiences/" + id + "/evaluate"), viewer, Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EVALUATION_SUBJECT_REQUIRED"));
        call(post("/api/v1/audiences/" + id + "/evaluate"), viewer, Map.of("customerId", "C1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_RESOLUTION_UNAVAILABLE"));
        call(post("/api/v1/audiences/NOPE/evaluate"), viewer, Map.of("context", gold))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluateOldVersionAndThroughTheFacade() throws Exception {
        String id = createAudience(Map.of("tier", "GOLD"));
        call(put("/api/v1/audiences/" + id), marketer, Map.of("content", Map.of("tier", "SILVER")));
        var ctx = Map.<String, Object>of("customer", Map.of("attributes", Map.of("tier", "GOLD")));
        call(post("/api/v1/audiences/" + id + "/evaluate"), viewer, Map.of("context", ctx, "version", 1))
                .andExpect(jsonPath("$.member").value(true)).andExpect(jsonPath("$.version").value(1));

        UUID tenant = jdbc.sql("SELECT id FROM tenants WHERE tenant_key = 'default'").query(UUID.class).single();
        var current = facade.evaluate(tenant, id, null, ctx);
        assertThat(current.member()).isFalse();
        assertThat(current.version()).isEqualTo(2);
        assertThat(facade.find(tenant, id)).get().extracting(AudienceFacade.AudienceSummary::status)
                .isEqualTo(AudienceStatus.DRAFT);
    }

    @Test
    void apiKeysNeedTheScopeAndAnActiveAudience() throws Exception {
        String id = createAudience(Map.of("tier", "GOLD"));
        String withScope = api.createApplication(admin, unique("eval"), Set.of("audiences:evaluate"), null).get("apiKey").asString();
        String without = api.createApplication(admin, unique("noeval"), Set.of("events:ingest"), null).get("apiKey").asString();
        var req = api.json(Map.of("context", Map.of("customer", Map.of("attributes", Map.of("tier", "GOLD")))));

        mvc.perform(post("/api/v1/audiences/" + id + "/evaluate").header("X-API-Key", without)
                .contentType(MediaType.APPLICATION_JSON).content(req)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/audiences/" + id + "/evaluate").header("X-API-Key", withScope)
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDIENCE_NOT_ACTIVE"));
        call(post("/api/v1/audiences/" + id + "/activate"), marketer, null);
        mvc.perform(post("/api/v1/audiences/" + id + "/evaluate").header("X-API-Key", withScope)
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isOk()).andExpect(jsonPath("$.member").value(true));
        // An API key cannot manage audiences.
        mvc.perform(get("/api/v1/audiences/" + id).header("X-API-Key", withScope)).andExpect(status().isForbidden());
    }

    @Test
    void validateShowsCompiledTreeWithoutSaving() throws Exception {
        long before = jdbc.sql("SELECT count(*) FROM audiences").query(Long.class).single();
        call(post("/api/v1/audiences/validate"), viewer, Map.of("content", Map.of("age", Map.of("gte", 18))))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.compiledCriteria.all[0].op").value("gte"));
        call(post("/api/v1/audiences/validate"), viewer, Map.of("content", Map.of("age", Map.of("gte", "old"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("content.age.gte"));
        assertThat(jdbc.sql("SELECT count(*) FROM audiences").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void audiencesAreTenantIsolated() throws Exception {
        String id = createAudience(Map.of("tier", "GOLD"));
        UUID tenantB = Ids.newId();
        String keyB = unique("aud-b").toLowerCase();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("INSERT INTO tenants (id, tenant_key, name, status, created_at, updated_at) VALUES (:id, :k, 'B', 'ACTIVE', :n, :n)")
                .param("id", tenantB).param("k", keyB).param("n", now).update();
        jdbc.sql("""
                INSERT INTO users (id, tenant_id, email, password_hash, roles, status, created_at, updated_at)
                VALUES (:id, :t, 'm@b.local', :pw, ARRAY['MARKETER'], 'ACTIVE', :n, :n)
                """).param("id", Ids.newId()).param("t", tenantB).param("pw", encoder.encode("Tenant-B-Passw0rd"))
                .param("n", now).update();
        String marketerB = api.login(keyB, "m@b.local", "Tenant-B-Passw0rd");

        call(get("/api/v1/audiences/" + id), marketerB, null).andExpect(status().isNotFound());
        call(put("/api/v1/audiences/" + id), marketerB, Map.of("content", Map.of("x", 1))).andExpect(status().isNotFound());
        call(get("/api/v1/audiences"), marketerB, null).andExpect(jsonPath("$.total").value(0));
        // Tenant B's own numbering starts at AUD10001, and the same key can exist in both tenants.
        call(post("/api/v1/audiences"), marketerB, Map.of("content", Map.of("x", 1)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.audienceId").value("AUD10001"));
        call(post("/api/v1/audiences"), marketerB, Map.of("audienceId", id, "content", Map.of("x", 1)))
                .andExpect(status().isCreated());
    }
}
