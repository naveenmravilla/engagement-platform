package com.cep.shared.api.jobs;

/**
 * Processes jobs of one queue. Implementations are Spring beans and are discovered automatically.
 *
 * <p>{@link #handle} runs inside a database transaction that also marks the job DONE, so a handler's
 * own database writes commit atomically with job completion. Throwing any exception rolls the handler's
 * writes back and schedules a retry with exponential backoff; throw {@link PermanentJobFailure} to
 * dead-letter immediately. Handlers must be idempotent for side effects outside the database.
 */
public interface JobHandler {
    String queue();

    void handle(Job job);
}
