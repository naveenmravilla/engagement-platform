package com.cep.shared.api.jobs;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A unit of durable asynchronous work.
 *
 * @param queue       handler queue name, e.g. {@code journey.step}
 * @param tenantId    owning tenant (null only for platform-level jobs)
 * @param payload     any Jackson-serializable object
 * @param runAt       earliest execution time (null = now); this is how durable delays work
 * @param dedupKey    globally unique key; a second enqueue with the same key is a no-op (null = none)
 * @param maxAttempts attempts before the job is dead-lettered (null = platform default of 5)
 */
public record JobRequest(String queue, UUID tenantId, Object payload, Instant runAt, String dedupKey, Integer maxAttempts) {
    public JobRequest {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(payload, "payload");
        if (maxAttempts != null && maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    public static JobRequest of(String queue, UUID tenantId, Object payload) {
        return new JobRequest(queue, tenantId, payload, null, null, null);
    }

    public JobRequest runAt(Instant at) { return new JobRequest(queue, tenantId, payload, at, dedupKey, maxAttempts); }
    public JobRequest dedupKey(String key) { return new JobRequest(queue, tenantId, payload, runAt, key, maxAttempts); }
    public JobRequest maxAttempts(int n) { return new JobRequest(queue, tenantId, payload, runAt, dedupKey, n); }
}
