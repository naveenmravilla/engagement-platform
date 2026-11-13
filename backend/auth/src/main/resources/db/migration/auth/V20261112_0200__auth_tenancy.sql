-- Tenancy, integration applications (API keys) and dashboard users.

CREATE TABLE tenants (
    id          uuid PRIMARY KEY,
    tenant_key  varchar(64)  NOT NULL UNIQUE CHECK (tenant_key ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name        varchar(200) NOT NULL,
    status      varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    settings    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE TABLE applications (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    name                varchar(200) NOT NULL,
    api_key_hash        varchar(64)  NOT NULL UNIQUE,
    api_key_prefix      varchar(16)  NOT NULL,
    scopes              text[]       NOT NULL,
    rate_limit_per_min  int          NOT NULL CHECK (rate_limit_per_min > 0),
    status              varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    last_used_at        timestamptz,
    CONSTRAINT applications_tenant_name_uk UNIQUE (tenant_id, name)
);
CREATE INDEX applications_tenant_idx ON applications (tenant_id);

CREATE TABLE users (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenants (id),
    email          varchar(320) NOT NULL,
    password_hash  varchar(200) NOT NULL,
    display_name   varchar(200),
    roles          text[]       NOT NULL,
    status         varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    last_login_at  timestamptz,
    CONSTRAINT users_tenant_email_uk UNIQUE (tenant_id, email)
);
