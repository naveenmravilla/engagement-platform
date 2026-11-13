package com.cep.auth.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** First-run bootstrap of a tenant and its admin. Credentials must come from the environment. */
@ConfigurationProperties("cep.bootstrap")
public record BootstrapProperties(
        @DefaultValue("default") String tenantKey,
        @DefaultValue("Default Tenant") String tenantName,
        String adminEmail,
        String adminPassword) {
}
