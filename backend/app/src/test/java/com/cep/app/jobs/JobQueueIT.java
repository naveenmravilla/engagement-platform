package com.cep.app.jobs;

import static com.cep.app.support.TestSupportConfiguration.QUEUE;
import static org.assertj.core.api.Assertions.assertThat;

import com.cep.app.support.IntegrationTest;
import com.cep.app.support.MutableClock;
import com.cep.app.support.RecordingJobHandler;
import com.cep.shared.api.jobs.JobAdmin;
import com.cep.shared.api.jobs.JobRequest;
import com.cep.shared.api.jobs.JobScheduler;
import com.cep.shared.internal.jobs.JdbcJobQueue;
import com.cep.shared.internal.jobs.JobWorker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class JobQueueIT extends IntegrationTest {
    static final UUID TENANT = UUID.randomUUID();

    @Autowired JobScheduler scheduler;
    @Autowired JdbcJobQueue queue;
    @Autowired JobAdmin admin;
    @Autowired JobWorker worker;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired @Qualifier("recordingJobHandler") RecordingJobHandler handler;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("DELETE FROM test_side_effects").update();
        handler.reset();
    }

    private UUID enqueue(Map<String, Object> payload) {
        return scheduler.enqueue(JobRequest.of(QUEUE, TENANT, payload)).orElseThrow();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM jobs WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int sideEffects(String key) {
        return jdbc.sql("SELECT count(*) FROM test_side_effects WHERE effect_key = :k").param("k", QUEUE + ":" + key)
                .query(Integer.class).single();
    }

    @Test
    @DisplayName("enqueued job is processed once, with the job's tenant bound to TenantContext")
    void processesJob() {
        UUID id = enqueue(Map.of("key", "a"));

        assertThat(worker.drain(10)).isEqualTo(1);

        assertThat(status(id)).isEqualTo("DONE");
        assertThat(handler.handled()).singleElement().satisfies(h -> {
            assertThat(h.key()).isEqualTo("a");
            assertThat(h.attempt()).isEqualTo(1);
            assertThat(h.tenantInContext()).isEqualTo(TENANT);
        });
        assertThat(worker.drain(10)).isZero();
    }

    @Test
    @DisplayName("dedup key makes enqueue idempotent")
    void dedup() {
        var req = JobRequest.of(QUEUE, TENANT, Map.of("key", "d")).dedupKey("exec-1:node-A:1");
        Optional<UUID> first = scheduler.enqueue(req);
        Optional<UUID> second = scheduler.enqueue(req);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        worker.drain(10);
        assertThat(handler.handled()).hasSize(1);
    }

    @Test
    @DisplayName("delayed job is not picked up before run_at, and is after time passes (durable WAIT)")
    void delayedJob() {
        UUID id = scheduler.enqueue(JobRequest.of(QUEUE, TENANT, Map.of("key", "w"))
                .runAt(clock.instant().plus(Duration.ofMinutes(30)))).orElseThrow();

        assertThat(worker.drain(10)).isZero();
        clock.advance(Duration.ofMinutes(29));
        assertThat(worker.drain(10)).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(worker.drain(10)).isEqualTo(1);
        assertThat(status(id)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("failed attempts roll back handler writes and retry with exponential backoff (30s, 2m)")
    void retriesWithBackoff() {
        UUID id = enqueue(Map.of("key", "r", "failTimes", 2));

        worker.drain(10);                       // attempt 1 fails
        assertThat(status(id)).isEqualTo("READY");
        assertThat(sideEffects("r")).isZero(); // rolled back

        clock.advance(Duration.ofSeconds(29));
        assertThat(worker.drain(10)).isZero();  // still backing off
        clock.advance(Duration.ofSeconds(1));
        worker.drain(10);                       // attempt 2 fails
        clock.advance(Duration.ofMinutes(2));
        worker.drain(10);                       // attempt 3 succeeds

        assertThat(status(id)).isEqualTo("DONE");
        assertThat(handler.handled()).extracting(RecordingJobHandler.Handled::attempt).containsExactly(1, 2, 3);
        assertThat(sideEffects("r")).isEqualTo(1); // only the successful attempt's write survived
    }

    @Test
    @DisplayName("job exhausting max attempts is dead-lettered, listed, and can be replayed")
    void deadLetterAndReplay() {
        UUID id = scheduler.enqueue(JobRequest.of(QUEUE, TENANT, Map.of("key", "x", "failTimes", 99)).maxAttempts(2))
                .orElseThrow();
        worker.drain(10);
        clock.advance(Duration.ofMinutes(1));
        worker.drain(10);

        assertThat(status(id)).isEqualTo("DEAD");
        assertThat(admin.deadJobs(TENANT, null, 10)).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(id);
            assertThat(d.attempts()).isEqualTo(2);
            assertThat(d.lastError()).contains("scripted failure on attempt 2");
        });
        assertThat(admin.deadJobs(UUID.randomUUID(), null, 10)).as("tenant-scoped").isEmpty();

        assertThat(admin.replay(TENANT, id)).isTrue();
        assertThat(status(id)).isEqualTo("READY");
        assertThat(admin.replay(TENANT, id)).as("only DEAD jobs can be replayed").isFalse();
    }

    @Test
    @DisplayName("PermanentJobFailure dead-letters immediately without retries")
    void permanentFailure() {
        UUID id = enqueue(Map.of("key", "p", "permanent", true));
        worker.drain(10);
        assertThat(status(id)).isEqualTo("DEAD");
        assertThat(handler.handled()).hasSize(1);
    }

    @Test
    @DisplayName("crash recovery: a job leased by a dead worker is reclaimed after the lease expires")
    void expiredLeaseIsReclaimed() {
        UUID id = enqueue(Map.of("key", "c"));
        UUID crashedWorker = UUID.randomUUID();
        // Simulate a worker that claimed the job and then died (no completion, no failure recorded).
        assertThat(queue.claim(Set.of(QUEUE), 10, Duration.ofMinutes(5), crashedWorker)).hasSize(1);

        assertThat(worker.drain(10)).as("lease still valid").isZero();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(worker.drain(10)).isEqualTo(1);

        assertThat(status(id)).isEqualTo("DONE");
        assertThat(handler.handled()).singleElement().extracting(RecordingJobHandler.Handled::attempt).isEqualTo(2);
        // The crashed worker coming back late cannot overwrite the outcome: its lease token is fenced off.
        Boolean staleCompletion = tx.execute(s -> queue.complete(id, crashedWorker));
        assertThat(staleCompletion).isFalse();
    }

    @Test
    @DisplayName("concurrent workers never claim the same job (SKIP LOCKED)")
    void concurrentClaimsAreDisjoint() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add(enqueue(Map.of("key", "k" + i)));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<List<UUID>>> claims = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            claims.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                List<UUID> mine = new ArrayList<>();
                List<UUID> batch;
                do {
                    batch = queue.claim(Set.of(QUEUE), 7, Duration.ofMinutes(5), UUID.randomUUID())
                            .stream().map(j -> j.id()).toList();
                    mine.addAll(batch);
                } while (!batch.isEmpty());
                return mine;
            }));
        }
        start.countDown();
        List<UUID> all = new ArrayList<>();
        for (var c : claims) {
            all.addAll(c.get());
        }
        assertThat(all).hasSize(60);
        assertThat(new HashSet<>(all)).containsExactlyInAnyOrderElementsOf(ids);
    }
}
