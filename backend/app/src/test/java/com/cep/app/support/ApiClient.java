package com.cep.app.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small helper for authenticating in MockMvc tests. */
public record ApiClient(MockMvc mvc, ObjectMapper mapper) {
    public static final String ADMIN_EMAIL = "admin@test.local";
    public static final String ADMIN_PASSWORD = "Adm1n-Passw0rd-Test";

    public String json(Object o) {
        return mapper.writeValueAsString(o);
    }

    public JsonNode read(String body) {
        return mapper.readTree(body);
    }

    public String login(String tenant, String email, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenant", tenant, "email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return read(body).get("accessToken").asString();
    }

    public String adminToken() throws Exception {
        return login("default", ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    /** Creates an application and returns {id, apiKey}. */
    public JsonNode createApplication(String bearer, String name, Set<String> scopes, Integer rateLimit) throws Exception {
        var req = new java.util.HashMap<String, Object>();
        req.put("name", name);
        if (scopes != null) {
            req.put("scopes", scopes);
        }
        if (rateLimit != null) {
            req.put("rateLimitPerMinute", rateLimit);
        }
        String body = mvc.perform(post("/api/v1/admin/applications").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return read(body);
    }
}
