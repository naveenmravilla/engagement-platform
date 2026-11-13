package com.cep.auth.internal.domain;

import com.cep.shared.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An external system that calls integration APIs with an API key. Only the key's SHA-256 is stored. */
@Entity
@Table(name = "applications")
public class Application extends BaseEntity {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "api_key_hash", nullable = false, updatable = false)
    private String apiKeyHash;

    @Column(name = "api_key_prefix", nullable = false, updatable = false)
    private String apiKeyPrefix;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] scopes;

    @Column(name = "rate_limit_per_min", nullable = false)
    private int rateLimitPerMin;

    @Column(nullable = false)
    private String status;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected Application() {}

    public Application(UUID id, UUID tenantId, String name, String apiKeyHash, String apiKeyPrefix, Set<String> scopes,
                       int rateLimitPerMin, Instant now) {
        super(id, now);
        this.tenantId = tenantId;
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyPrefix = apiKeyPrefix;
        this.scopes = scopes.stream().sorted().toArray(String[]::new);
        this.rateLimitPerMin = rateLimitPerMin;
        this.status = "ACTIVE";
    }

    public void revoke(Instant now) {
        this.status = "REVOKED";
        touch(now);
    }

    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getApiKeyPrefix() { return apiKeyPrefix; }
    public Set<String> getScopes() { return Set.of(scopes); }
    public int getRateLimitPerMin() { return rateLimitPerMin; }
    public String getStatus() { return status; }
    public boolean isActive() { return "ACTIVE".equals(status); }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
