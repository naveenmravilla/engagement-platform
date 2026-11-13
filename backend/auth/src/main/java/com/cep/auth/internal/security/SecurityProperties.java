package com.cep.auth.internal.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("cep.security")
public record SecurityProperties(
        String jwtSecret,
        @DefaultValue("cep") String jwtIssuer,
        @DefaultValue("1h") Duration accessTokenTtl,
        @DefaultValue("1200") int userRateLimitPerMinute,
        @DefaultValue("10") int loginAttemptsPerMinute,
        @DefaultValue List<String> corsAllowedOrigins) {

    public SecurityProperties {
        // Fail fast: never fall back to a built-in signing key.
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "cep.security.jwt-secret (env CEP_JWT_SECRET) must be set to at least 32 bytes");
        }
    }
}
