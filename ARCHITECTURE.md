# Architecture — Customer Engagement Platform (CEP)

Status: Phase 2 complete (decisions in §9). Notification channels: see [NOTIFICATION_CHANNEL_ARCHITECTURE.md](NOTIFICATION_CHANNEL_ARCHITECTURE.md) (D13–D18)

## 1. Goals

An event-driven, multi-tenant platform for:

1. **Audience Management**: flexible JSON audience definitions, versioning, and membership evaluation.
2. **Hybrid Journey Builder and Execution Engine**: business users design journeys visually in React Flow. External apps trigger them over an API. One generic engine interprets the published JSON graph.
3. **Channel Dispatcher** (D13, formerly "Notification Manager"): triggered by the external **NM** over REST. Campaign evaluation is shared, and then each channel has its own path (D14): **SMS** is handed to NM, which sends it through its SMS Gateway; **Push** goes through Firebase (FCM) directly to MyM1+; **App Inbox** is stored in the MS APIs. There is no generic notification webhook.

Core rule: **no journey-specific code.** Journeys are data. The engine interprets them through a pluggable node registry.

See [ARCHITECTURE_DIAGRAM.md](ARCHITECTURE_DIAGRAM.md) for the rendered end-to-end, sequence and deployment diagrams.

## 2. Style: modular monolith

The first release is one deployable Spring Boot application split into Gradle modules, one per bounded context. The boundary rules below keep each module extractable into a microservice later:

- A module exposes only its `..api` package (facades, DTOs, domain events). `..internal` packages are private. This is enforced by ArchUnit tests.
- Modules never read each other's tables. Cross-module calls go through the facades. Asynchronous interactions go through the outbox and job queue.
- Each module owns its own Flyway migration folder (`db/migration/<module>`).

```
                ┌──────────────────────────── API Gateway layer ───────────────────────────┐
 External apps ─►  AuthN (API key / JWT) · Tenant resolution · Rate limit · Idempotency   │
 Dashboard UI  ─►  OpenAPI REST controllers                                               │
                └───────┬─────────────┬──────────────┬──────────────┬──────────────┬──────┘
                        │             │              │              │              │
                 ┌──────▼─────┐ ┌─────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐
                 │ Audience   │ │ Event      │ │ Journey    │ │ Customer   │ │ Template   │
                 │ Mgmt       │ │ Ingestion  │ │ Mgmt       │ │ Profile +  │ │ Mgmt       │
                 │            │ │            │ │ (versions) │ │ Preference │ │            │
                 └──────▲─────┘ └─────┬──────┘ └─────┬──────┘ └─────▲──────┘ └─────▲──────┘
                        │   outbox    │              │ published    │              │
                        │             ▼              ▼ versions     │              │
                        │      ┌────────────────────────────┐       │              │
                        └──────┤   Journey Execution Engine  ├───────┘              │
                               │   NodeRegistry + Workers    │                      │
                               └──────────────┬─────────────┘                      │
                                              │ notification request (outbox)      │
                                              ▼                                    │
                               ┌────────────────────────────┐                      │
                               │   Channel Dispatcher (D13)  ├──────────────────────┘
                               │ consent·suppress·cap·render │◄── NM trigger (REST)
                               └──────┬────────┬────────┬────┘
                                      ▼        ▼        ▼
                                   SMS      Push     App Inbox     (separate ChannelAdapters, D14;
                                  → NM     → FCM    → MS Store       mock impl in local/test, D16)
                                (webhook)  → MyM1+   Messages

         Shared: Postgres (system of record + durable job queue) · Redis (rate limit, caps, locks)
         Observability: Micrometer → Prometheus → Grafana · JSON structured logs with traceId
```

## 3. Modules

| Module | Responsibility | Owns tables |
|---|---|---|
| `shared` | Tenant context, error model, idempotency, outbox, durable job queue, locking, clock | `outbox_events`, `jobs`, `idempotency_keys`, `audit_logs` |
| `auth` | Tenants, applications (API keys), users, RBAC, JWT issuing | `tenants`, `applications`, `users` |
| `audience` | CRUD, versioning, activation, criteria validation, membership evaluation | `audiences`, `audience_versions` |
| `customer` | Profiles, attributes, contacts, device tokens, activity timeline | `customers`, `customer_attributes`, `customer_devices` |
| `preference` | Consent and channel preferences, quiet hours | `customer_preferences` |
| `event-ingestion` | Event schemas, validation, dedup, async dispatch to journey triggers | `event_schemas`, `incoming_events`, `event_processing_logs` |
| `journey-mgmt` | Drafts, graph validation, publish/pause, version history | `journeys`, `journey_versions`, `journey_nodes`, `journey_connections` |
| `journey-engine` | Execution instances, node registry, workers, waits, retries, re-entry | `journey_executions`, `journey_execution_steps` |
| `template` | Channel templates, versioning, variable schema, rendering | `templates` |
| `notification` | Channel Dispatcher: NM trigger API, campaign evaluation pipeline, SMS / Push / App Inbox channel adapters, delivery status. App Inbox storage belongs to the MS APIs (D15) | `notification_requests`, `notification_delivery_logs`, `provider_configurations` |
| `analytics` | Read-model aggregates: journey funnels, delivery stats, dashboards | `analytics_daily_*` (materialized) |
| `app` | Spring Boot entry point, security config, OpenAPI, actuator | — |

## 4. Asynchronous processing

v1 uses **Postgres as the durable queue** instead of a broker, based on the hardware and simplicity requirements.

- **Transactional outbox:** each state change and its follow-up message are written in the same DB transaction. The outbox relay turns outbox rows into `jobs` rows, and later could publish them to Kafka instead.
- **Job queue:** a `jobs` table with `queue`, `run_at`, `status` (`READY → LEASED → DONE | DEAD`), `attempts`, `lease_token`, `lease_until`, and `dedup_key` (unique). Workers claim with `SELECT … FOR UPDATE SKIP LOCKED LIMIT n` and take a lease identified by a random token. The handler's own writes and the `DONE` mark commit in **one transaction**; completion and failure updates are fenced on the lease token, so a worker that stalled past its lease cannot overwrite the outcome of the worker that reclaimed the job. Failures reschedule with exponential backoff (30 s × 4ⁿ, capped at 1 h); after `max_attempts` (default 5), or on `PermanentJobFailure`, a job becomes `DEAD` and can be listed and replayed via `/api/v1/admin/jobs`.
- **Durable delays:** a WAIT node is a job with `run_at = now + duration`. No in-memory timers are used anywhere. If the app crashes, any lease that expires is reclaimed by another worker, so WAITs survive restarts by construction.
- **Abstraction:** domain code depends only on `MessageBus` and `JobScheduler` ports, so moving to Kafka or RabbitMQ later only means writing a new adapter.

Queues: `event.process`, `journey.step`, `journey.wake`, `notification.dispatch`, `notification.retry`, `webhook.call`.

## 5. Request flow: `POST /api/v1/journeys/execute`

1. Gateway: authenticate the API key, resolve the tenant, apply the rate limit, and look up the idempotency key (`requestId`).
2. Validate the request against the JSON schema and dispatch on `action` (`START_JOURNEY`, `TRIGGER_EVENT`, `CANCEL_JOURNEY`).
3. Resolve the customer, auto-creating a profile if the tenant policy allows it.
4. Resolve the journey and load its **currently published version**.
5. Check the trigger (event name, filter conditions) and the re-entry rule.
6. Insert `journey_executions` (unique on `tenant_id, request_id`) and enqueue `journey.step(START)`, both in one transaction.
7. Return `202 Accepted {executionId, status}`. Replaying the same request returns the original execution.

The audience check runs inside the journey as an `AUDIENCE` node, so it is versioned with the graph. If the request contains `audienceId`, it is also checked up front so ineligible requests are rejected early.

## 6. Security

- Integration APIs (`/execute`, `/events/ingest`, `/notifications/send`) use an `X-API-Key` per application. Keys are stored as hashes and scoped to one tenant.
- Dashboard users get a JWT access token. Roles: `ADMIN`, `MARKETER`, `VIEWER`, `INTEGRATION`.
- Tenant isolation: every table has `tenant_id`. A request-scoped `TenantContext` and repository-level filtering apply it, and tests cover cross-tenant access.
- Secrets come from environment variables or Docker secrets, never from the repo. Provider credentials are stored encrypted at rest (AES-GCM, key taken from the environment).
- Rate limiting uses a Redis token bucket per application.

## 7. Observability

- Micrometer metrics: queue depth, job latency, executions by status, node durations, and notifications by channel and status.
- Prometheus scrapes `/actuator/prometheus`. Grafana dashboards are provisioned from `infra/grafana`.
- Logs are structured JSON (logstash encoder) including `tenantId`, `executionId`, `requestId` and `traceId`.

## 8. Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA, Flyway, springdoc-openapi, networknt json-schema-validator |
| Build | Gradle (Kotlin DSL), multi-module, run inside Docker |
| DB | PostgreSQL 16 (JSONB) |
| Cache / locks / limits | Redis 7 |
| Frontend | React 18, TypeScript, Vite, Material UI, React Flow (`@xyflow/react`), TanStack Query |
| Tests | JUnit 5, Testcontainers (Postgres, Redis), ArchUnit, Vitest, Playwright for the E2E acceptance test |
| Ops | Docker Compose, Prometheus, Grafana |

## 9. Decision log

| # | Decision | Status |
|---|---|---|
| D1 | Separate repository (`~/engagement-platform`), independent of the `/workshop` repo | Approved |
| D2 | Postgres durable queue + transactional outbox behind `MessageBus` port; no Kafka/RabbitMQ in v1 | Approved |
| D3 | All Java/Node builds run in Docker (`eclipse-temurin:21`, `node:22`) | Approved |
| D4 | Gradle Kotlin DSL multi-module build | Approved |
| D5 | Modular monolith with ArchUnit-enforced boundaries | Proposed (spec permits) |
| D6 | Journey graphs are DAGs in v1; loops are not supported | Proposed |
| D7 | Only mock provider adapters; real-provider adapters require explicit approval | **Superseded by D16** |
| D8 | Spring Boot **4.1.2** + Gradle 9.9.1 (Boot 3.5 left OSS support mid-2026). Implies Jackson 3 (`tools.jackson.*`), Spring Security 7, Hibernate 7, Testcontainers 2 | Implemented (Phase 2) |
| D9 | `docker compose` runs from the `docker:cli` image via `infra/compose` (host has no compose plugin; keeps D3) | Implemented (Phase 2) |
| D10 | JWT = HS256 with a single env-provided secret while there is one service; move to RS256 + JWKS when a module is extracted | Implemented (Phase 2) |
| D11 | Rate limiter fails **open** if Redis is down (logged + `cep.ratelimit.errors` metric); it protects capacity, it is not an authorization boundary | Implemented (Phase 2) |
| D12 | Flyway versions are timestamps (`V20261112_0100__…`) under `db/migration/<module>/`, so modules can add migrations independently without number collisions | Implemented (Phase 2) |
| D13 | Triggered by the external NM over REST; the internal module is renamed to Channel Dispatcher | Approved 2026-11-13 |
| D14 | Shared up to campaign evaluation, then three separate channel adapters; no generic notification webhook. Replaces "single path" | Approved 2026-11-13 |
| D15 | App Inbox content and read state belong to the MS APIs; `inbox_messages` and `/customers/{id}/inbox` APIs removed from M1AIAlert | Approved 2026-11-13 |
| D16 | Real integrations (NM SMS webhook, FCM, MS Store Messages), each with a mock for local and test profiles. Supersedes D7. Real sends still need credentials and a `prod` profile | Approved 2026-11-13 |
| D17 | SMS is handed to NM, which sends through its SMS Gateway and owns DLRs; NM only acks, so `SUBMITTED_TO_NM` is final | Approved 2026-11-13 |
| D18 | M1AIAlert proposes the NM SMS webhook contract (NOTIFICATION_CHANNEL_ARCHITECTURE.md §6.2a), pending NM confirmation | Approved 2026-11-13 |
