package com.cep.shared.api.messaging;

/**
 * Transactional outbox port. {@link #publish} must be called inside the transaction that makes the
 * corresponding business change; the message is delivered (at least once) only if that transaction commits.
 * The v1 relay fans messages out to job queues; a Kafka relay can replace it without touching callers.
 */
public interface MessageBus {
    void publish(OutboxMessage message);
}
