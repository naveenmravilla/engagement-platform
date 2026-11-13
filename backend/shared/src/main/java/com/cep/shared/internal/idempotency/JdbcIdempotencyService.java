package com.cep.shared.internal.idempotency;

import static com.cep.shared.internal.persistence.Sql.ts;

import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.idempotency.IdempotencyService;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class JdbcIdempotencyService implements IdempotencyService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcIdempotencyService(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    private record Stored(String requestHash, String response) {}

    @Override
    @Transactional
    public <T> Result<T> execute(UUID tenantId, String scope, String key, String requestHash, Class<T> type,
                                 Supplier<T> action) {
        // Claim the key. A concurrent transaction holding the same key makes this INSERT wait for its outcome.
        int claimed = jdbc.sql("""
                INSERT INTO idempotency_keys (tenant_id, scope, idem_key, request_hash, created_at)
                VALUES (:tenant, :scope, :key, :hash, :now)
                ON CONFLICT (tenant_id, scope, idem_key) DO NOTHING
                """).param("tenant", tenantId).param("scope", scope).param("key", key).param("hash", requestHash)
                .param("now", ts(clock.instant())).update();

        if (claimed == 1) {
            T value = action.get();
            jdbc.sql("""
                    UPDATE idempotency_keys SET response = CAST(:response AS jsonb)
                     WHERE tenant_id = :tenant AND scope = :scope AND idem_key = :key
                    """).param("response", mapper.writeValueAsString(value))
                    .param("tenant", tenantId).param("scope", scope).param("key", key).update();
            return new Result<>(value, false);
        }

        Stored stored = jdbc.sql("""
                SELECT request_hash, response::text AS response FROM idempotency_keys
                 WHERE tenant_id = :tenant AND scope = :scope AND idem_key = :key
                """).param("tenant", tenantId).param("scope", scope).param("key", key)
                .query((rs, n) -> new Stored(rs.getString("request_hash"), rs.getString("response")))
                .single();
        if (!stored.requestHash().equals(requestHash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key '" + key + "' was already used with a different request");
        }
        return new Result<>(mapper.readValue(stored.response(), type), true);
    }
}
