package com.cep.shared.internal.jobs;

import static com.cep.shared.internal.persistence.Sql.instant;
import static com.cep.shared.internal.persistence.Sql.ts;

import com.cep.shared.api.jobs.Job;
import com.cep.shared.api.jobs.JobAdmin;
import com.cep.shared.api.jobs.JobRequest;
import com.cep.shared.api.jobs.JobScheduler;
import com.cep.shared.api.util.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Postgres-backed durable queue. Workers claim with {@code FOR UPDATE SKIP LOCKED} and hold a lease
 * identified by a random token; completion and failure updates are fenced on that token so a worker whose
 * lease expired (e.g. a paused JVM) cannot overwrite the outcome of the worker that reclaimed the job.
 */
@Repository
public class JdbcJobQueue implements JobScheduler, JobAdmin {

    static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcJobQueue(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<UUID> enqueue(JobRequest r) {
        Instant now = clock.instant();
        UUID id = Ids.newId(clock);
        int inserted = jdbc.sql("""
                INSERT INTO jobs (id, tenant_id, queue, dedup_key, payload, status, run_at, attempts, max_attempts,
                                  created_at, updated_at)
                VALUES (:id, :tenant, :queue, :dedup, CAST(:payload AS jsonb), 'READY', :runAt, 0, :max, :now, :now)
                ON CONFLICT (dedup_key) DO NOTHING
                """)
                .param("id", id)
                .param("tenant", r.tenantId())
                .param("queue", r.queue())
                .param("dedup", r.dedupKey())
                .param("payload", mapper.writeValueAsString(r.payload()))
                .param("runAt", ts(r.runAt() == null ? now : r.runAt()))
                .param("max", r.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : r.maxAttempts())
                .param("now", ts(now))
                .update();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    /** Claims due jobs (READY and due, or LEASED with an expired lease) and leases them to {@code token}. */
    @Transactional
    public List<Job> claim(Collection<String> queues, int limit, Duration lease, UUID token) {
        if (queues.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM jobs
                    WHERE queue IN (:queues)
                      AND ((status = 'READY' AND run_at <= :now) OR (status = 'LEASED' AND lease_until < :now))
                    ORDER BY run_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                UPDATE jobs j
                   SET status = 'LEASED', lease_token = :token, lease_until = :leaseUntil,
                       attempts = j.attempts + 1, updated_at = :now
                  FROM due
                 WHERE j.id = due.id
                RETURNING j.id, j.queue, j.tenant_id, j.payload::text AS payload, j.attempts, j.max_attempts,
                          j.run_at, j.created_at
                """)
                .param("queues", queues)
                .param("now", ts(now))
                .param("limit", limit)
                .param("token", token)
                .param("leaseUntil", ts(now.plus(lease)))
                .query(this::mapJob)
                .list();
    }

    /** Marks the job DONE iff this worker still holds the lease. Must run in the handler's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(UUID jobId, UUID token) {
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE jobs SET status = 'DONE', lease_token = NULL, lease_until = NULL, last_error = NULL,
                                completed_at = :now, updated_at = :now
                 WHERE id = :id AND lease_token = :token AND status = 'LEASED'
                """).param("id", jobId).param("token", token).param("now", ts(now)).update() == 1;
    }

    @Transactional
    public boolean retryLater(UUID jobId, UUID token, Instant runAt, String error) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'READY', lease_token = NULL, lease_until = NULL, run_at = :runAt,
                                last_error = :error, updated_at = :now
                 WHERE id = :id AND lease_token = :token AND status = 'LEASED'
                """).param("id", jobId).param("token", token).param("runAt", ts(runAt))
                .param("error", truncate(error)).param("now", ts(clock.instant())).update() == 1;
    }

    @Transactional
    public boolean deadLetter(UUID jobId, UUID token, String error) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'DEAD', lease_token = NULL, lease_until = NULL, last_error = :error,
                                updated_at = :now
                 WHERE id = :id AND lease_token = :token AND status = 'LEASED'
                """).param("id", jobId).param("token", token).param("error", truncate(error))
                .param("now", ts(clock.instant())).update() == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeadJob> deadJobs(UUID tenantId, String queue, int limit) {
        return jdbc.sql("""
                SELECT id, queue, attempts, last_error, created_at, updated_at FROM jobs
                 WHERE status = 'DEAD' AND tenant_id = :tenant AND (CAST(:queue AS varchar) IS NULL OR queue = :queue)
                 ORDER BY updated_at DESC LIMIT :limit
                """).param("tenant", tenantId).param("queue", queue).param("limit", Math.min(limit, 500))
                .query((rs, n) -> new DeadJob(rs.getObject("id", UUID.class), rs.getString("queue"),
                        rs.getInt("attempts"), rs.getString("last_error"), instant(rs, "created_at"),
                        instant(rs, "updated_at")))
                .list();
    }

    @Override
    @Transactional
    public boolean replay(UUID tenantId, UUID jobId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE jobs SET status = 'READY', attempts = 0, run_at = :now, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenant AND status = 'DEAD'
                """).param("id", jobId).param("tenant", tenantId).param("now", ts(now)).update() == 1;
    }

    private Job mapJob(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getString("queue"), rs.getObject("tenant_id", UUID.class),
                mapper.readTree(rs.getString("payload")), rs.getInt("attempts"), rs.getInt("max_attempts"),
                instant(rs, "run_at"), instant(rs, "created_at"));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 4000 ? s : s.substring(0, 4000);
    }
}
