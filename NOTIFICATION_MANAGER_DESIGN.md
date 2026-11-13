# Channel Dispatcher Design

> Renamed from "Notification Manager" (D13) so it is not confused with the external **NM**. Channel flows, ownership and contracts are in [NOTIFICATION_CHANNEL_ARCHITECTURE.md](NOTIFICATION_CHANNEL_ARCHITECTURE.md) (D13–D18, approved 2026-11-13).

The Channel Dispatcher is the only component that talks to delivery channels. The NM trigger (`POST /api/v1/alerts/trigger`), journey notification nodes and `POST /api/v1/notifications/send` all go through the same evaluation pipeline. After evaluation, each channel has its own adapter, contract and status model (D14).

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
  9. route by channel to its ChannelAdapter (SMS → NmSmsWebhookChannel, PUSH → FcmPushChannel, APP_INBOX → MsInboxChannel); circuit-breaker per adapter
 10. send → SMS: SUBMITTED_TO_NM (final, NM only acks) · PUSH: SENT per token · APP_INBOX: SENT once MS stores it
     retryable failure → notification.retry / webhook.call with backoff | permanent → FAILED
 11. PUSH: SDK impression / open events → OPENED. SMS and APP_INBOX have no later status in M1AIAlert
 every transition → notification_delivery_logs + audit; journey step gets the outcome via outbox
```

## Channel adapter SPI
```java
public interface ChannelAdapter {
  String adapterKey();                  // "nm-sms", "mock-nm-sms", "fcm", "ms-inbox"
  Channel channel();
  SendResult send(RenderedMessage msg, Recipient to, ProviderConfig cfg);   // never throws for business errors
  default Optional<DeliveryReceipt> parseCallback(Map<String,String> headers, String body) { return Optional.empty(); }
}
record SendResult(Outcome outcome /*ACCEPTED|RETRYABLE_ERROR|PERMANENT_ERROR*/, String providerMessageId, String detail) {}
```
Adapters (D16):

| Channel | Real adapter | Mock (local / test) |
|---|---|---|
| SMS | `NmSmsWebhookChannel`: POSTs to NM per the draft contract (§6.2a of the channel doc). M1AIAlert never talks to the SMS Gateway | `MockNmSmsChannel`: records the webhook payload and returns a 2xx ack |
| PUSH | `FcmPushChannel`: FCM HTTP v1 with a service-account OAuth2 token | `MockPushChannel`: records messages and simulates `UNREGISTERED` / 5xx for test tokens |
| APP_INBOX | `MsInboxChannel`: POSTs to MS Store Messages, idempotent on `notificationId` | `MockMsInboxChannel`: records messages and returns `201 {msMessageId}` |

Mocks can be configured to fail at a set rate or for specific test recipients (`+6500000000` → permanent error, etc.).
A startup guard refuses to start in any profile other than `prod` if a real adapter is enabled. Real adapters also need credentials that are only provided in `prod`.

## App Inbox API

Removed (D15). App Inbox content and read state live in the MS APIs, and MyM1+ calls Fetch All Messages, Read Messages and Fetch Unread Count there directly. M1AIAlert only calls MS Store Messages.

## Templates
`{{customerName}}` syntax using Mustache (logic-less, HTML-escaped where relevant). `variables_schema` declares which variables are required and their types, and is validated both when a template is saved and at render time. Channel limits are enforced: an SMS warns at more than 160 GSM-7 characters per segment, and a Push title and body have maximum lengths.
