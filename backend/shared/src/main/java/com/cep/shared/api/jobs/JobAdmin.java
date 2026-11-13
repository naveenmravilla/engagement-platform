package com.cep.shared.api.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operational access to the dead-letter queue. All operations are tenant-scoped. */
public interface JobAdmin {
    List<DeadJob> deadJobs(UUID tenantId, String queue, int limit);

    /** Re-queues a dead job with a fresh attempt budget. @return false if not found or not dead. */
    boolean replay(UUID tenantId, UUID jobId);

    record DeadJob(UUID id, String queue, int attempts, String lastError, Instant createdAt, Instant deadAt) {}
}
