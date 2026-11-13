-- Audience management: audiences, immutable content versions and the per-tenant key counter.

CREATE TABLE audiences (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    audience_key     varchar(64)  NOT NULL CHECK (audience_key ~ '^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$'),
    name             varchar(200) NOT NULL,
    status           varchar(16)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    current_version  int          NOT NULL CHECK (current_version >= 1),
    created_by       uuid         NOT NULL,
    updated_by       uuid         NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT audiences_tenant_key_uk UNIQUE (tenant_id, audience_key)
);
CREATE INDEX audiences_tenant_updated_idx ON audiences (tenant_id, updated_at DESC);
CREATE INDEX audiences_tenant_status_idx ON audiences (tenant_id, status);

CREATE TABLE audience_versions (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    audience_id        uuid        NOT NULL REFERENCES audiences (id),
    version            int         NOT NULL CHECK (version >= 1),
    content            jsonb       NOT NULL,
    compiled_criteria  jsonb       NOT NULL,
    created_by         uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT audience_versions_uk UNIQUE (audience_id, version)
);
CREATE INDEX audience_versions_content_gin ON audience_versions USING gin (content jsonb_path_ops);

-- Generates AUD10001, AUD10002, ... per tenant without gaps under concurrency (row lock on upsert).
CREATE TABLE audience_key_counters (
    tenant_id   uuid   PRIMARY KEY REFERENCES tenants (id),
    next_value  bigint NOT NULL
);
