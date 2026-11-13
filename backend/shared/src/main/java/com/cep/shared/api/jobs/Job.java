package com.cep.shared.api.jobs;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A leased job handed to a {@link JobHandler}. {@code attempt} is 1-based. */
public record Job(UUID id, String queue, UUID tenantId, JsonNode payload, int attempt, int maxAttempts,
                  Instant runAt, Instant createdAt) {

    public boolean isLastAttempt() {
        return attempt >= maxAttempts;
    }
}
