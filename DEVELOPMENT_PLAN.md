# Development Plan

Each phase goes through: analyze → design → implement → compile → test → fix → update docs → mark complete.
A phase is not marked complete while any test is failing.

| Phase | Scope | Exit criteria | Status |
|---|---|---|---|
| 1 | Repo inspection, architecture, design docs, decisions | Docs written; D1–D4 approved | **Complete** |
| 2 | Gradle multi-module skeleton, Spring Boot app, Docker build wrapper, Compose (Postgres, Redis), Flyway baseline, `shared` (tenant ctx, errors, outbox, job queue, idempotency), `auth` (API keys, JWT, RBAC), OpenAPI, ArchUnit | `./gradlew build` green in Docker; job queue tests (lease, retry, DLQ, restart) pass | **Complete** |
| 3 | Audience CRUD, versioning, activation, rule compiler and evaluator, `/evaluate` | API and evaluator tests | **Complete** |
| 4 | Customer profile (minimal) and Event ingestion: schemas, validation, dedup, async processing | dedup and idempotency tests | Pending |
| 5 | Journey management: definition schema, graph validator, drafts, publish, versions, node-type registry API | validator test matrix (cycles, orphans, branches) | Pending |
| 6 | Journey engine: workers, handlers for all 11 node types, waits, retries, pause/resume/cancel, re-entry, event triggers | restart-survival and duplicate-request tests | Pending |
| 7 | Frontend: app shell, auth, Journey Builder (React Flow), Journeys/Executions pages | Vitest and builder E2E | Pending |
| 8 | Channel Dispatcher (D13–D18): NM trigger API, templates, evaluation pipeline, consent, caps; SMS → NM webhook, Push → FCM, App Inbox → MS Store Messages; device token lifecycle; SDK events; real adapters plus mocks | pipeline tests per suppression reason; contract tests per adapter against mocks | Pending (needs NM, Firebase and MS answers before real adapters are enabled) |
| 9 | Customer 360, preferences, devices, activity timeline; remaining UI pages | API and UI tests | Pending |
| 10 | Metrics, Prometheus/Grafana dashboards, analytics read models, Analytics UI | dashboards load; metric assertions | Pending |
| 11 | Full acceptance-scenario E2E (Playwright + API), tenant-isolation and security tests | all acceptance criteria automated | Pending |
| 12 | Production Compose stack, hardening, README runbook | `docker compose up` → demo scenario works from clean | Pending |

## Phase 2 report

**Delivered:** Gradle 9.9 multi-module build (`shared`, `auth`, `app`) on Spring Boot 4.1.2 / Java 21, built entirely in Docker (`backend/gw`); Flyway migrations; durable job queue (SKIP LOCKED leases, lease-token fencing, exponential backoff, DLQ + replay API); transactional outbox + relay; idempotency service; advisory locks; audit log; RFC 7807 errors; request-id propagation; API-key + JWT auth, RBAC with role hierarchy, integration scopes, tenant context, Redis rate limiting; OpenAPI/Swagger; Prometheus endpoint; JSON structured logs; Docker image + Compose stack (`infra/compose`).

**Verification:** `./gw build` passes 40 automated tests (Testcontainers Postgres 16 + Redis 7):

| Suite | Proves |
|---|---|
| `JobQueueIT` (8) | processing + tenant binding; dedup; delayed jobs not run early (durable WAIT basis); handler writes roll back on failure; backoff 30s→2m; DLQ after max attempts + tenant-scoped replay; permanent failure; **crash recovery via lease expiry, stale worker fenced**; 4 concurrent claimers never share a job |
| `OutboxIT` (4) | rolled-back tx never delivers; fan-out to all subscribers exactly once; relay crash/re-run doesn't duplicate; publish outside tx rejected |
| `IdempotencyIT` (5) | replay returns original; key reuse with different body → 409; tenant-scoped keys; failed action releases key; 5 concurrent duplicates run the action once |
| `SecurityIT` (10) | 401/403 problem+json; login without user enumeration; tampered JWT rejected; API key create→use→revoke + audit; ambiguous/malformed credentials; VIEWER denied admin; role hierarchy; per-field validation; **tenant isolation**; OpenAPI/health/request-id |
| `RateLimitIT` (1) | per-application limit → 429 + Retry-After, resets next window |
| `ModuleBoundariesTest` (7) | no module touches another module's `internal` package |
| unit (5) | backoff schedule, UUIDv7, API key format/hash |

Also smoke-tested against the running Compose stack (login, key issue, RBAC, DLQ, OpenAPI, Swagger UI, Prometheus, persistence across container rebuild).

**Bug found and fixed by tests:** `@PreAuthorize` denials were swallowed by the catch-all handler as 500s; now mapped to 403 by `SecurityExceptionAdvice`.

**Deferred (tracked):** refresh tokens → Phase 7 (needed by UI); housekeeping of DONE jobs / published outbox / old idempotency keys → Phase 12; API-key lookup cache → Phase 12; tenant-provisioning API (platform super-admin) → Phase 12; Prometheus/Grafana containers → Phase 10.

## Phase 3 report

**Delivered:** new `audience` module (API, internal rules, store, service, web). Tables `audiences`, `audience_versions` and `audience_key_counters`. Generated IDs per tenant (`AUD10001`, …) or client-chosen IDs. Immutable versions with `If-Match` / `ETag`. Lifecycle DRAFT → ACTIVE ⇄ INACTIVE → ARCHIVED. Rule compiler (shorthand plus full tree, normalized form, path-level errors) and interpreter (12 operators, typed comparisons, regex step budget). `/validate` and `/evaluate` endpoints. `Idempotency-Key` on create. Audit entries for every change. New API-key scope `audiences:evaluate`.

**Facade for later phases (`com.cep.audience.api`):** `AudienceFacade` (find and evaluate, used by the journey AUDIENCE node), `AudienceSubjectResolver` (customer module, Phase 4, enables `{customerId}` on `/evaluate`) and `AudienceUsageChecker` (journey management, Phase 5, blocks deactivate and archive while an ACTIVE journey uses the audience).

**Verification:** `./gw build` passes 110 tests (56 new rule-engine unit tests, 12 new `AudienceIT` integration tests, plus the 42 existing ones):

| Suite | Proves |
|---|---|
| `RuleCompilerTest` (26) | shorthand and full-tree compile; normalized form recompiles to itself; every invalid input reported with its path; depth and condition limits |
| `RuleEvaluatorTest` (30) | each operator, number / date / string typing, missing and null fields, groups and `not`, matched / failed paths, catastrophic regex cut off |
| `AudienceIT` (12) | sequential IDs; client IDs and duplicates; 422 with field paths; idempotent create; versions, stale `If-Match` → 412, no-op updates, history; lifecycle and archive (ADMIN only); roles; evaluate by context, by old version and through the facade; API-key scope and ACTIVE check; validate saves nothing; **tenant isolation** with per-tenant numbering |
| `ModuleBoundariesTest` (9) | `audience.internal` is private; `shared` does not depend on `audience` |

Also smoke-tested on the running Compose stack: the migration applied cleanly to the existing Phase 2 database (schema 20261112.0200 → 20261113.0300), and create, activate, evaluate, the 422 error and the OpenAPI paths all work.

**Bug found and fixed:** `backend/Dockerfile` copied modules by name, so the image build failed without `audience`. It now copies `audience` too. Every new module must be added there.

**Deferred:** `{customerId}` on `/evaluate` → Phase 4. "In use by an ACTIVE journey" checks → Phase 5. Bulk segment counts (SQL predicates) → later, when needed.

## Acceptance criteria traceability (filled in as tests land)
| Criterion | Test |
|---|---|
| Create audience (ID + JSON content) | `AudienceIT.createGeneratesSequentialIdsAndStartsAsDraftVersionOne`, `clientChosenIdIsKeptAndDuplicatesAreRejected` |
| Build journey visually (START, AUDIENCE, SMS, WAIT, PUSH, APP_INBOX, END) | — |
| Publish | — |
| Submit API request; resolve customer and audience; start correct version | — |
| Dynamic node execution; mock notifications | — |
| Persisted progress; WAIT resumes after restart | — |
| Notification status tracked | — |
| Execution history in UI | — |
| Duplicate request → no duplicate execution | — |

## Approval-gated actions (will stop and ask)
Destructive DB changes · removing functionality · major architecture changes (for example, introducing Kafka) · any deployment beyond local Docker · real customer data · real-provider adapters or real sends.
