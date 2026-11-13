package com.cep.app.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cep.app.support.ApiClient;
import com.cep.app.support.IntegrationTest;
import com.cep.app.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class RateLimitIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;

    @Test
    void perApplicationLimitIsEnforcedAndResetsNextWindow() throws Exception {
        ApiClient api = new ApiClient(mvc, mapper);
        String key = api.createApplication(api.adminToken(), "limited-" + UUID.randomUUID(), null, 3).get("apiKey").asString();
        // Align to the start of a window so the test can't straddle a boundary.
        long minute = Duration.ofMinutes(1).toMillis();
        clock.set(Instant.ofEpochMilli((clock.millis() / minute + 1) * minute));

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/auth/me").header("X-API-Key", key)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/auth/me").header("X-API-Key", key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        clock.advance(Duration.ofMinutes(1));
        mvc.perform(get("/api/v1/auth/me").header("X-API-Key", key)).andExpect(status().isOk());
    }
}
