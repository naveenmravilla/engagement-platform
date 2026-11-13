package com.cep.auth.internal.domain;

import com.cep.shared.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {
    @Column(name = "tenant_key", nullable = false, updatable = false)
    private String tenantKey;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status;

    protected Tenant() {}

    public Tenant(UUID id, String tenantKey, String name, Instant now) {
        super(id, now);
        this.tenantKey = tenantKey;
        this.name = name;
        this.status = "ACTIVE";
    }

    public String getTenantKey() { return tenantKey; }
    public String getName() { return name; }
    public boolean isActive() { return "ACTIVE".equals(status); }
}
