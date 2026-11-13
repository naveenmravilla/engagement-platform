# Customer Engagement Platform

A multi-tenant, event-driven platform with three parts: audience management, a hybrid visual/API journey engine, and a Channel Dispatcher with separate SMS (via NM), Push (via Firebase to MyM1+) and App Inbox (via MS APIs) flows. See NOTIFICATION_CHANNEL_ARCHITECTURE.md.

**Status:** Phase 2 (backend foundation: build, database, job queue, outbox, auth, RBAC) is complete. Business modules start in Phase 3. See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md).

## Documents
- [ARCHITECTURE.md](ARCHITECTURE.md): modules, async model, security, decision log
- [ARCHITECTURE_DIAGRAM.md](ARCHITECTURE_DIAGRAM.md): end-to-end system, request-lifecycle and deployment diagrams (SVGs in `diagrams/`)
- [DATABASE_DESIGN.md](DATABASE_DESIGN.md)
- [API_SPECIFICATION.md](API_SPECIFICATION.md)
- [JOURNEY_ENGINE_DESIGN.md](JOURNEY_ENGINE_DESIGN.md)
- [NOTIFICATION_MANAGER_DESIGN.md](NOTIFICATION_MANAGER_DESIGN.md)
- [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

## Layout
```
backend/            Gradle multi-module Spring Boot 4.1 app (Java 21)
  buildSrc/         shared build conventions
  shared/           kernel: tenant context, errors, job queue, outbox, idempotency, locks, audit
  auth/             tenants, API keys, users, JWT, RBAC, rate limiting
  app/              Spring Boot entry point, config, ops APIs, integration tests
  gw                runs ./gradlew inside a JDK 21 container
frontend/           React + TypeScript + MUI + React Flow (Phase 7)
infra/              docker-compose.yml, compose wrapper, .env.example
```
Each module exposes only its `api` package. `internal` packages are private, and `ModuleBoundariesTest` enforces this.

## Prerequisites
Docker Engine only. Java, Gradle and docker-compose all run in containers (decisions D3 and D9).

## Build and test
```bash
cd backend
./gw build                         # compile + all tests (Testcontainers starts Postgres and Redis)
./gw :app:test --tests '*JobQueueIT'
```
Test reports are written to `backend/app/build/reports/tests/test/index.html`.

## Run locally
```bash
cp infra/.env.example infra/.env    # then set real secrets (openssl rand -base64 48)
infra/compose up -d --build         # postgres, redis, app
curl localhost:${CEP_HTTP_PORT:-8080}/actuator/health
```
- Swagger UI: `http://localhost:8080/swagger-ui.html`. Prometheus metrics: `/actuator/prometheus`.
- On first start, the app creates tenant `CEP_BOOTSTRAP_TENANT` and admin `CEP_BOOTSTRAP_ADMIN_EMAIL`.

```bash
# Dashboard login → JWT
curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"tenant":"default","email":"admin@example.com","password":"…"}'
# Register an integration app → API key (shown once)
curl -s localhost:8080/api/v1/admin/applications -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"orders-service","scopes":["journeys:execute"]}'
# Call as the app
curl -s localhost:8080/api/v1/auth/me -H "X-API-Key: $KEY"
```
Stop the stack with `infra/compose down`. Add `-v` to also delete the database volume.

## Safety
Development and tests use **mock channel adapters only**. No real SMS, push or inbox messages are sent. Real adapters (D16) run only in the `prod` profile with real credentials.
