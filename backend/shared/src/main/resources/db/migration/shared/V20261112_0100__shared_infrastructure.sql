-- Shared platform infrastructure: durable job queue, transactional outbox, idempotency keys, audit log.
-- Infrastructure tables carry tenant_id for scoping but no FK, so the shared module has no dependency on auth.

CREATE TABLE jobs (
    id            uuid PRIMARY KEY,
    tenant_id     uuid,
    queue         varchar(100)  NOT NULL,
    dedup_key     varchar(300)  UNIQUE,
    payload       jsonb         NOT NULL,
    status        varchar(16)   NOT NULL CHECK (status IN ('READY', 'LEASED', 'DONE', 'DEAD')),
    run_at        timestamptz   NOT NULL,
    attempts      int           NOT NULL DEFAULT 0,
    max_attempts  int           NOT NULL CHECK (max_attempts >= 1),
    lease_token   uuid,
    lease_until   timestamptz,
    last_error    text,
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL,
    completed_at  timestamptz,
    CONSTRAINT jobs_lease_consistency CHECK ((status = 'LEASED') = (lease_token IS NOT NULL))
);
CREATE INDEX jobs_ready_idx  ON jobs (queue, run_at)        WHERE status = 'READY';
CREATE INDEX jobs_leased_idx ON jobs (queue, lease_until)   WHERE status = 'LEASED';
CREATE INDEX jobs_dead_idx   ON jobs (tenant_id, updated_at) WHERE status = 'DEAD';

CREATE TABLE outbox_events (
    id              uuid PRIMARY KEY,
    tenant_id       uuid,
    topic           varchar(150) NOT NULL,
    aggregate_type  varchar(100),
    aggregate_id    varchar(200),
    payload         jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL,
    published_at    timestamptz
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE idempotency_keys (
    tenant_id     uuid         NOT NULL,
    scope         varchar(100) NOT NULL,
    idem_key      varchar(200) NOT NULL,
    request_hash  varchar(64)  NOT NULL,
    response      jsonb,
    created_at    timestamptz  NOT NULL,
    PRIMARY KEY (tenant_id, scope, idem_key)
);
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);

CREATE TABLE audit_logs (
    id            uuid PRIMARY KEY,
    tenant_id     uuid,
    actor_type    varchar(32)  NOT NULL,
    actor_id      varchar(100),
    action        varchar(100) NOT NULL,
    entity_type   varchar(100) NOT NULL,
    entity_id     varchar(200),
    before_state  jsonb,
    after_state   jsonb,
    request_id    varchar(64),
    created_at    timestamptz  NOT NULL
);
CREATE INDEX audit_logs_entity_idx ON audit_logs (tenant_id, entity_type, entity_id, created_at DESC);
CREATE INDEX audit_logs_time_idx   ON audit_logs (tenant_id, created_at DESC);
