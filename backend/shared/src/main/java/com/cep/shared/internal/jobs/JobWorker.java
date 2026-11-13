package com.cep.shared.internal.jobs;

import com.cep.shared.api.jobs.Job;
import com.cep.shared.api.jobs.JobHandler;
import com.cep.shared.api.jobs.PermanentJobFailure;
import com.cep.shared.api.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the job table and dispatches to {@link JobHandler}s. Stateless: all progress lives in Postgres,
 * so any number of app instances can run workers and a crash loses nothing (leases simply expire).
 */
@Component
public class JobWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcJobQueue queue;
    private final Map<String, JobHandler> handlers;
    private final JobsProperties props;
    private final Backoff backoff;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;
    private final UUID workerToken = UUID.randomUUID();
    private volatile ScheduledExecutorService executor;

    public JobWorker(JdbcJobQueue queue, List<JobHandler> handlers, JobsProperties props, TransactionTemplate tx,
                     Clock clock, MeterRegistry meters) {
        this.queue = queue;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::queue, Function.identity()));
        this.props = props;
        this.backoff = new Backoff(props.backoffInitial(), props.backoffMultiplier(), props.backoffMax());
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    /** Claims and processes one batch synchronously. @return number of jobs claimed. Used by pollers and tests. */
    public int runOnce() {
        // A fresh token per claim batch: lease fencing is per batch, not per JVM.
        UUID token = UUID.randomUUID();
        List<Job> jobs = queue.claim(handlers.keySet(), props.batchSize(), props.leaseDuration(), token);
        jobs.forEach(job -> process(job, token));
        return jobs.size();
    }

    /** Runs batches until no due job remains (bounded). Test and ops helper. */
    public int drain(int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int n = runOnce();
            if (n == 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    void process(Job job, UUID token) {
        MDC.put("jobId", job.id().toString());
        MDC.put("queue", job.queue());
        Timer.Sample sample = Timer.start(meters);
        String outcome = "done";
        try {
            if (job.attempt() > job.maxAttempts()) {
                // Only reachable when earlier leases expired without an outcome (worker crashed mid-job).
                queue.deadLetter(job.id(), token, "Lease expired on final attempt (worker crash or timeout)");
                outcome = "dead";
                return;
            }
            JobHandler handler = handlers.get(job.queue());
            Boolean completed = tx.execute(status -> {
                TenantContext.runAs(job.tenantId(), () -> handler.handle(job));
                if (!queue.complete(job.id(), token)) {
                    status.setRollbackOnly(); // lease lost: another worker owns this job now; discard our effects
                    return false;
                }
                return true;
            });
            if (!Boolean.TRUE.equals(completed)) {
                outcome = "lease_lost";
                log.warn("Lease lost for job {}; effects rolled back", job.id());
            }
        } catch (PermanentJobFailure e) {
            outcome = "dead";
            log.error("Job {} failed permanently: {}", job.id(), e.getMessage());
            queue.deadLetter(job.id(), token, describe(e));
        } catch (RuntimeException e) {
            if (job.isLastAttempt()) {
                outcome = "dead";
                log.error("Job {} exhausted {} attempts", job.id(), job.maxAttempts(), e);
                queue.deadLetter(job.id(), token, describe(e));
            } else {
                outcome = "retry";
                var delay = backoff.delayAfter(job.attempt());
                log.warn("Job {} attempt {}/{} failed, retrying in {}: {}", job.id(), job.attempt(),
                        job.maxAttempts(), delay, e.toString());
                queue.retryLater(job.id(), token, clock.instant().plus(delay), describe(e));
            }
        } finally {
            sample.stop(meters.timer("cep.jobs.processed", "queue", job.queue(), "outcome", outcome));
            MDC.remove("jobId");
            MDC.remove("queue");
        }
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    // ---- lifecycle --------------------------------------------------------------------------

    @Override
    public void start() {
        if (!props.workerEnabled() || handlers.isEmpty()) {
            log.info("Job worker disabled (enabled={}, handlers={})", props.workerEnabled(), handlers.keySet());
            return;
        }
        executor = Executors.newScheduledThreadPool(props.workerThreads(),
                Thread.ofPlatform().name("cep-job-worker-", 0).daemon().factory());
        long interval = props.pollInterval().toMillis();
        for (int i = 0; i < props.workerThreads(); i++) {
            executor.scheduleWithFixedDelay(this::pollSafely, interval, interval, TimeUnit.MILLISECONDS);
        }
        log.info("Job worker {} started: threads={}, queues={}", workerToken, props.workerThreads(), handlers.keySet());
    }

    private void pollSafely() {
        try {
            // Keep draining while there is work, then back off to the fixed poll interval.
            while (executor != null && !executor.isShutdown() && runOnce() == props.batchSize()) {
                // full batch: more is likely waiting
            }
        } catch (RuntimeException e) {
            log.error("Job poll failed", e);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService ex = executor;
        executor = null;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(30, TimeUnit.SECONDS)) {
                    ex.shutdownNow(); // in-flight jobs roll back; leases expire and they are reclaimed
                }
            } catch (InterruptedException e) {
                ex.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100; // start last, stop first
    }
}
