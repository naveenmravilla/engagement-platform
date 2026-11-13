package com.cep.shared.api.jobs;

/** Signals a non-retryable failure: the job is moved to the dead-letter state immediately. */
public class PermanentJobFailure extends RuntimeException {
    public PermanentJobFailure(String message) {
        super(message);
    }

    public PermanentJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
