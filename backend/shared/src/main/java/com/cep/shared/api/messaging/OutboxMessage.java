package com.cep.shared.api.messaging;

import java.util.Objects;
import java.util.UUID;

/** A domain event to publish reliably. {@code payload} is any Jackson-serializable object. */
public record OutboxMessage(UUID tenantId, String topic, String aggregateType, String aggregateId, Object payload) {
    public OutboxMessage {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
    }
}
