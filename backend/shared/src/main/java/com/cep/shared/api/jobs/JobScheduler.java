package com.cep.shared.api.jobs;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for enqueuing durable jobs. Enqueue participates in the caller's transaction, so a job is only
 * visible to workers if the business change that produced it commits (transactional enqueue).
 */
public interface JobScheduler {
    /** @return the new job id, or empty if a job with the same dedup key already exists. */
    Optional<UUID> enqueue(JobRequest request);
}
