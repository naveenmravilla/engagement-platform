# Journey Engine Design

## 1. Principles
- Journeys are **data**: a versioned JSON graph. The engine contains no journey-specific code.
- A published version is **immutable**. Every execution is pinned to one `journey_version_id`, which gives version isolation.
- Each node step runs in its own **transaction with a checkpoint**. Between steps, all state is in Postgres.
- No in-memory timers. Waits and retries are jobs with a `run_at` time.

## 2. Definition format
This is the format from the spec, with two additions: `branch` on connections, and `reentry` and `settings` blocks.
```json
{
  "journeyId": "JRN_ORDER", "name": "Order Engagement Journey", "version": 1,
  "trigger": { "type": "EVENT", "eventName": "ORDER_PLACED", "filter": { "payload.amount": { "gte": 100 } } },
  "reentry": { "policy": "AFTER_COOLDOWN", "cooldown": "P1D" },
  "settings": { "maxDuration": "P30D", "timezone": "Asia/Singapore" },
  "nodes": [ { "id": "START", "type": "START", "config": {}, "position": {"x":0,"y":0} } ],
  "connections": [ { "source": "CHECK", "target": "SEND_SMS", "branch": "true" } ]
}
```
Trigger types are `EVENT` (start on an ingested event) and `API` (start only on `/journeys/execute`).

## 3. Node framework
```java
public interface NodeHandler {
  String type();                                   // "SMS"
  JsonNode configSchema();                         // JSON Schema used by the validator and the UI form
  List<ValidationError> validate(NodeDefinition n, GraphView g);   // semantic checks
  Set<String> outputBranches(NodeDefinition n);    // e.g. {"true","false"} or {"default"}
  NodeResult execute(NodeExecutionContext ctx);    // must be idempotent per (executionId,nodeKey,attempt)
}
sealed interface NodeResult {
  record Continue(Set<String> branches, JsonNode output) {}   // follow edges with these labels
  record Wait(Instant until, JsonNode output) {}              // durable timer
  record WaitForEvent(String eventName, Duration timeout, String timeoutBranch) {}
  record Fail(String code, String message, boolean retryable) {}
  record End(JsonNode output) {}
}
```
Handlers are Spring beans that `NodeRegistry` collects automatically. To add a node type, you add one class and nothing else changes. The registry also serves `GET /api/v1/journeys/node-types`, which returns `type`, `configSchema` and `outputBranches`, so the Builder palette and config forms are generated from the backend.

| Type | Behaviour | Branches |
|---|---|---|
| START | no-op entry point; exactly one per graph | default |
| AUDIENCE | evaluates membership of the audience's active version against the customer | `match`, `no_match` (if `no_match` has no edge, the execution ends as `EXITED_NOT_ELIGIBLE`) |
| EVENT_TRIGGER | waits for a named event from the same customer, with a timeout | `received`, `timeout` |
| CONDITION | boolean rule on context (customer, event payload, prior step outputs) | `true`, `false` |
| DECISION | ordered multi-way rules with a default | one label per rule + `default` |
| WAIT | fixed duration (`MINUTES/HOURS/DAYS`) or until a time/attribute; optional quiet-hours alignment | default |
| SMS / PUSH / APP_INBOX | builds a notification request → Notification Manager (outbox) | `sent`, `failed` (falls back to default) |
| WEBHOOK | outbound HTTP via `webhook.call` queue, with allowlisted hosts, timeout and retries; response goes into context | `success`, `failure` |
| END | terminates the execution as COMPLETED | — |

## 4. Rule language (CONDITION, DECISION, AUDIENCE)
JSON rule tree, with JSONPath-like field references into the execution context:
```json
{ "all": [ { "field": "customer.attributes.membershipType", "op": "eq", "value": "GOLD" },
           { "any": [ { "field": "event.payload.amount", "op": "gte", "value": 1000 },
                      { "field": "customer.attributes.city", "op": "in", "value": ["Singapore"] } ] } ] }
```
Operators: `eq, neq, gt, gte, lt, lte, in, nin, contains, exists, regex, between`. The rule tree is evaluated by an interpreter, never compiled into code or SpEL, so a stored definition cannot inject code.

Audience `content` may use the shorthand from the spec (`{"customerStatus":"ACTIVE"}`), which is compiled to `all` + `eq` on `customer.attributes.*`. The full rule tree is accepted too. Future criteria types (`eventHistory`, `transactions`) are added as new `CriterionEvaluator` beans, and each one can supply a SQL predicate for bulk segment counts.

## 5. Graph validation (at save-draft = warnings, at publish = errors)
1. JSON Schema of the definition document.
2. Node IDs are unique. Every `type` is registered. Each node `config` passes its handler's `configSchema`.
3. Exactly one START with no incoming edges, and at least one END.
4. Every edge references existing nodes. Branch labels are in the source's `outputBranches`. No duplicate edges.
5. Every node is reachable from START, and every node reaches some END (no dead ends).
6. **No cycles** (DAG, decision D6). This is checked with a DFS that reports the cycle path.
7. Handler-specific checks, e.g. a template exists for the channel, the audience exists and is ACTIVE, and a DECISION has a default edge.

## 6. Execution model
```
execute request ─► create execution (PENDING) + job journey.step{exec, node=START}  [1 tx]
worker: lease job ─► lock execution row (SELECT … FOR UPDATE) ─► check status
        RUNNING? ─► insert step(attempt) RUNNING ─► handler.execute(ctx)
          Continue ─► step SUCCEEDED; enqueue journey.step for each target   [same tx]
          Wait     ─► step WAITING; exec WAITING; enqueue journey.wake run_at=until
          Fail(retryable && attempts<max) ─► reschedule with backoff
          Fail     ─► step FAILED; exec FAILED (or follow `failed` branch if wired)
          End      ─► exec COMPLETED (when no other active branches remain)
```
- **Parallel paths:** when one node has several outgoing edges, each target gets its own job. `current_node_keys` tracks the active tokens, and the execution completes when the token set is empty. v1 has no join node, since the graph is a DAG where branches end independently.
- **Idempotency:** the job `dedup_key = exec:node:attempt` combined with the step unique constraint means a job delivered twice does not run the step twice. Notification nodes pass `requestId = executionId:nodeKey` to the Notification Manager, which is idempotent on that key.
- **Restart safety:** state is only ever written in Postgres. A crashed worker's lease expires and another worker reclaims the job. Integration test: start → WAIT → stop the app → advance the clock → start the app → resumes.
- **Pause / resume:** a journey-level pause stops new entries, and in-flight executions park at their next step boundary in `PAUSED`, with their jobs held. On resume those jobs are re-enqueued. Execution-level pause works the same way for a single instance.
- **Cancel:** sets status `CANCELLED`. Pending jobs become no-ops when their status check runs.
- **Re-entry:** `ONCE` (never re-enter), `ALWAYS`, `AFTER_COOLDOWN` (ISO-8601 duration since the last completion), `NOT_WHILE_ACTIVE` (default). This is checked under a per-(customer, journey) advisory lock.
- **Retry policy:** per node `retry: {maxAttempts, backoff: EXPONENTIAL, initialDelay}`, with a platform default of 3 attempts at 30 s, 2 min and 8 min. Exhausted jobs go to `DEAD`, where they can be inspected and replayed from the UI.
- **Context:** `{customer, event, journey, steps: {<nodeKey>: output}}`. It is persisted on the execution and is available to conditions and template variables.

## 7. Event-triggered starts
`/events/ingest` stores the event (deduplicated) and enqueues `event.process`. The processor:
(a) wakes executions waiting on that event in an `EVENT_TRIGGER` node for that customer, and
(b) finds ACTIVE journeys whose published version has `trigger_event_name = event` and whose filter matches, then starts executions with `requestId = eventId:journeyId`, which deduplicates them.
