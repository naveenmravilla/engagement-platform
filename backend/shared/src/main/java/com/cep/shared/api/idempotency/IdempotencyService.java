package com.cep.shared.api.idempotency;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Exactly-once request handling keyed by (tenant, scope, key).
 *
 * <p>The first call runs {@code action} and stores its result in the same transaction. Concurrent callers
 * with the same key block until the first commits, then receive the stored result ({@code replayed=true}).
 * Reusing a key with a different {@code requestHash} raises 409 IDEMPOTENCY_KEY_REUSED.
 */
public interface IdempotencyService {
    <T> Result<T> execute(UUID tenantId, String scope, String key, String requestHash, Class<T> type, Supplier<T> action);

    record Result<T>(T value, boolean replayed) {}
}
