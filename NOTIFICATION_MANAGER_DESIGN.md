# Notification Manager Design

The Notification Manager is the only component that talks to delivery providers. Journey notification nodes and `POST /api/v1/notifications/send` both go through the same pipeline.

## Pipeline
```
accept(request)                                          [sync, idempotent on (tenant, requestId)]
  1. validate schema, channel ∈ {SMS, PUSH, APP_INBOX}
  2. persist notification_requests (ACCEPTED) + job notification.dispatch   [1 tx]
  → 202 {notificationId, status}

dispatch(job)                                            [async worker]
  3. resolve template (key + latest ACTIVE version, or pinned version) for channel/locale
  4. render: {{var}} substitution from request.variables ∪ customer profile; missing required var → FAILED(TEMPLATE_VARIABLE_MISSING)
  5. resolve recipient: SMS→phone_e164, PUSH→active device tokens, APP_INBOX→customerId
  6. consent: channel preference must be OPT_IN (SMS/PUSH) — else SUPPRESSED(NO_CONSENT)
  7. suppression: global/tenant suppression list, customer status, quiet hours (→ reschedule, not drop)
  8. frequency cap: Redis sliding window per customer/channel (tenant default + per-customer override)
  9. route: pick ProviderAdapter by provider_configurations priority; circuit-breaker per provider
 10. send → SENT (+ provider_message_id) | retryable failure → notification.retry with backoff | permanent → FAILED
 11. provider callbacks / mock simulated receipts → DELIVERED / FAILED; inbox read → READ
 every transition → notification_delivery_logs + audit; journey step gets the outcome via outbox
```

## Provider SPI
```java
public interface ProviderAdapter {
  String providerKey();                 // "mock-sms"
  Channel channel();
  SendResult send(RenderedMessage msg, Recipient to, ProviderConfig cfg);   // never throws for business errors
  default Optional<DeliveryReceipt> parseCallback(Map<String,String> headers, String body) { return Optional.empty(); }
}
record SendResult(Outcome outcome /*ACCEPTED|RETRYABLE_ERROR|PERMANENT_ERROR*/, String providerMessageId, String detail) {}
```
v1 adapters:
- `MockSmsAdapter`, `MockPushAdapter`: record messages to an in-DB sink (`mock_outbox` view of delivery logs) and can be configured to fail at a set rate or for specific test recipients (`+6500000000` → permanent error, etc.).
- `AppInboxAdapter`: a first-party provider that writes `inbox_messages`. It is the real implementation, not a mock.

Future adapters (e.g. Twilio, SNS, FCM, APNs) only need to implement the interface. **No real-provider adapter will be added without explicit approval.**
A startup guard refuses to start in any profile other than `prod` if a non-mock adapter is enabled.

## App Inbox API
```
GET    /api/v1/customers/{id}/inbox?status=UNREAD&page=     list (expired messages filtered out)
POST   /api/v1/customers/{id}/inbox/{msgId}/read            mark read  (→ notification READ)
POST   /api/v1/customers/{id}/inbox/{msgId}/unread
POST   /api/v1/customers/{id}/inbox/{msgId}/archive
GET    /api/v1/customers/{id}/inbox/unread-count
```
A scheduled job marks expired messages `EXPIRED` and archives messages older than the retention period.

## Templates
`{{customerName}}` syntax using Mustache (logic-less, HTML-escaped where relevant). `variables_schema` declares which variables are required and their types, and is validated both when a template is saved and at render time. Channel limits are enforced: an SMS warns at more than 160 GSM-7 characters per segment, and a Push title and body have maximum lengths.
