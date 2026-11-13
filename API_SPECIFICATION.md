# API Specification

Base path is `/api/v1`. All APIs use JSON. Live OpenAPI is served at `/v3/api-docs` and Swagger UI at `/swagger-ui.html` (from Phase 2).

## Conventions
- **Auth:** `X-API-Key` for integration endpoints, `Authorization: Bearer <JWT>` for dashboard endpoints. The tenant is always derived from the credential and never taken from the body.
- **Idempotency:** `POST`s that create things accept an `Idempotency-Key` header. `/journeys/execute` and `/notifications/send` also use the body's `requestId`, and `/events/ingest` uses `eventId`. A replay with the same key returns the original response. The same key with a different body returns `409 IDEMPOTENCY_KEY_REUSED`.
- **Errors:** RFC 7807 `application/problem+json`: `{type: "urn:cep:problem:<code>", title, status, detail, code, errors[]?, requestId}`. `X-Request-Id` is echoed or generated on every response.
- **Integration scopes** (API keys): `journeys:execute`, `events:ingest`, `notifications:send`, `customers:read`, `customers:write`. Responses carry `X-RateLimit-Limit/Remaining`; over-limit → `429` + `Retry-After`.
- **Pagination:** `?page=&size=&sort=` → `{items, page, size, total}`.
- **Concurrency:** `ETag`/`If-Match` on PUT for audiences, journeys and templates.

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | /audiences | JWT MARKETER | `{name?, content}` → `{audienceId:"AUD10001", version:1, status}` |
| GET | /audiences, /audiences/{id} | JWT VIEWER | `?version=` for history |
| PUT | /audiences/{id} | JWT MARKETER | new version |
| POST | /audiences/{id}/activate · /deactivate | JWT MARKETER | |
| DELETE | /audiences/{id} | JWT ADMIN | soft-deactivate; refused if referenced by an ACTIVE journey |
| POST | /audiences/{id}/evaluate | JWT/API key | `{customerId}` → `{member:bool, matched:[...], failed:[...]}` |
| POST | /journeys | JWT MARKETER | create draft `{name, definition}` |
| GET | /journeys, /journeys/{id} | JWT VIEWER | draft and published summary |
| PUT | /journeys/{id} | JWT MARKETER | save draft (returns validation warnings) |
| POST | /journeys/{id}/validate | JWT MARKETER | full publish-level validation, no side effects |
| POST | /journeys/{id}/publish | JWT MARKETER | → new immutable version |
| POST | /journeys/{id}/pause · /resume | JWT MARKETER | |
| GET | /journeys/{id}/versions[/{v}] | JWT VIEWER | |
| GET | /journeys/node-types | JWT VIEWER | registry: config schemas and branches for the Builder |
| POST | /journeys/execute | API key | spec request → `202 {executionId, status, duplicate:bool}` |
| GET | /executions?journeyId=&customerId=&status= | JWT VIEWER | |
| GET | /executions/{id} | JWT/API key | includes steps timeline |
| POST | /executions/{id}/cancel · /pause · /resume | JWT MARKETER / API key | |
| POST | /events/ingest | API key | single event or `{events:[…]}` batch (≤500) → `202` with per-event status |
| GET/POST | /event-schemas | JWT ADMIN | |
| POST | /notifications/send | API key / JWT | spec request → `202 {notificationId, status}` |
| GET | /notifications/{id} | JWT/API key | lifecycle and delivery logs |
| POST | /templates · GET /templates/{id} · PUT /templates/{id} | JWT MARKETER | PUT creates a new version |
| POST | /templates/{id}/preview | JWT MARKETER | render with sample variables |
| POST | /customers · GET /customers/{id} | API key / JWT | upsert by `customerId` |
| GET | /customers/{id}/activity | JWT VIEWER | merged timeline: events, executions, notifications |
| GET/PUT | /customers/{id}/preferences | API key / JWT | consent per channel |
| POST | /customers/{id}/devices | API key | register push token |
| POST | /alerts/trigger | API key (NM) | NM trigger: `{nmRequestId, channel, campaignId, customer, variables}` → `202 {notificationId, correlationId}`. Idempotent on `nmRequestId` (D13) |
| PUT/DELETE | /customers/{id}/devices/{deviceId} | API key | push token refresh / deactivate on logout |
| POST | /sdk/events | API key (MyM1+ SDK) | `notification_impression`, `notification_opened` |
| — | ~~/customers/{id}/inbox…~~ | — | **Removed (D15).** MyM1+ uses the MS APIs |
| GET | /analytics/journeys/{id} · /analytics/notifications · /analytics/overview | JWT VIEWER | |
| POST | /auth/login | — | `{tenant, email, password}` → `{accessToken, tokenType, expiresIn}` · **implemented** |
| GET | /auth/me | any | caller identity, tenant, authorities · **implemented** |
| POST | /auth/refresh | — | deferred to Phase 7 |
| POST/GET | /admin/applications · POST /admin/applications/{id}/revoke | JWT ADMIN | `{name, scopes?, rateLimitPerMinute?}`; API key returned **once** · **implemented** |
| POST/GET | /admin/users | JWT ADMIN | `{email, password(≥12), displayName?, roles⊆{ADMIN,MARKETER,VIEWER}}` · **implemented** |
| GET | /admin/jobs/dead · POST /admin/jobs/{id}/replay | JWT ADMIN | dead-letter queue · **implemented** |
| CRUD | /admin/providers | JWT ADMIN | Phase 8 |

## Execute request (from spec)
```json
{ "requestId": "REQ10001", "customerId": "CUST1001", "action": "START_JOURNEY",
  "event": "ORDER_PLACED", "audienceId": "AUD10001", "journeyId": "JRN10001",
  "payload": { "orderId": "ORD1001", "amount": 2500, "currency": "SGD" } }
```
`action` ∈ `START_JOURNEY | TRIGGER_EVENT | CANCEL_JOURNEY`. `journeyId` is required for START and CANCEL. For `TRIGGER_EVENT` the journey is resolved by trigger matching.
Possible responses: `202` new or duplicate · `404 JOURNEY_NOT_FOUND` · `409 JOURNEY_NOT_PUBLISHED` · `422 CUSTOMER_NOT_ELIGIBLE` / `TRIGGER_MISMATCH` / `REENTRY_BLOCKED`.
