package com.cep.shared.internal.jobs;

import java.time.Duration;

/** Exponential backoff: initial * multiplier^(attempt-1), capped. Defaults give 30s, 2m, 8m, 32m, 1h. */
public record Backoff(Duration initial, double multiplier, Duration max) {

    public Duration delayAfter(int failedAttempt) {
        double millis = initial.toMillis() * Math.pow(multiplier, Math.max(0, failedAttempt - 1));
        return millis >= max.toMillis() ? max : Duration.ofMillis((long) millis);
    }
}
