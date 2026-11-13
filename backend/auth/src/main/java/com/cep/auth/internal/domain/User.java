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

@Entity
@Table(name = "users")
public class User extends BaseEntity {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] roles;

    @Column(nullable = false)
    private String status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {}

    public User(UUID id, UUID tenantId, String email, String passwordHash, String displayName, Set<String> roles,
                Instant now) {
        super(id, now);
        this.tenantId = tenantId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.roles = roles.stream().sorted().toArray(String[]::new);
        this.status = "ACTIVE";
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public UUID getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Set<String> getRoles() { return Set.of(roles); }
    public String getStatus() { return status; }
    public boolean isActive() { return "ACTIVE".equals(status); }
    public Instant getLastLoginAt() { return lastLoginAt; }
}
