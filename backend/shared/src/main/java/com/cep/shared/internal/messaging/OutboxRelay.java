package com.cep.shared.internal.messaging;

import static com.cep.shared.internal.persistence.Sql.ts;

import com.cep.shared.api.jobs.JobRequest;
import com.cep.shared.api.jobs.JobScheduler;
import com.cep.shared.api.messaging.OutboxSubscription;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Moves committed outbox rows to subscriber job queues. Each (message, queue) pair has the dedup key
 * {@code outbox:<id>:<queue>}, so running the relay twice, or concurrently, never duplicates deliveries.
 */
@Component
public class OutboxRelay implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final JobScheduler jobs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final OutboxProperties props;
    private final TransactionTemplate tx;
    private final Map<String, List<String>> queuesByTopic;
    private volatile ScheduledExecutorService executor;

    public OutboxRelay(JdbcClient jdbc, JobScheduler jobs, ObjectMapper mapper, Clock clock, OutboxProperties props,
                       TransactionTemplate tx, List<OutboxSubscription> subscriptions) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
        this.queuesByTopic = subscriptions.stream().collect(Collectors.groupingBy(OutboxSubscription::topic,
                Collectors.mapping(OutboxSubscription::queue, Collectors.toUnmodifiableList())));
    }

    private record Row(UUID id, UUID tenantId, String topic, String payload) {}

    /** Relays one batch in its own transaction. @return rows relayed. */
    public int runOnce() {
        Integer n = tx.execute(status -> relayBatch());
        return n == null ? 0 : n;
    }

    private int relayBatch() {
        List<Row> rows = jdbc.sql("""
                SELECT id, tenant_id, topic, payload::text AS payload FROM outbox_events
                 WHERE published_at IS NULL ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("limit", props.batchSize())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("topic"), rs.getString("payload")))
                .list();
        for (Row row : rows) {
            JsonNode payload = mapper.readTree(row.payload());
            for (String queue : queuesByTopic.getOrDefault(row.topic(), List.of())) {
                jobs.enqueue(JobRequest.of(queue, row.tenantId(), payload).dedupKey("outbox:" + row.id() + ":" + queue));
            }
        }
        if (!rows.isEmpty()) {
            jdbc.sql("UPDATE outbox_events SET published_at = :now WHERE id IN (:ids)")
                    .param("now", ts(clock.instant()))
                    .param("ids", rows.stream().map(Row::id).toList())
                    .update();
        }
        return rows.size();
    }

    @Override
    public void start() {
        if (!props.relayEnabled()) {
            log.info("Outbox relay disabled");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("cep-outbox-relay").daemon().factory());
        long interval = props.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(() -> {
            try {
                while (executor != null && runOnce() == props.batchSize()) {
                    // full batch: keep going
                }
            } catch (RuntimeException e) {
                log.error("Outbox relay failed", e);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        ScheduledExecutorService ex = executor;
        executor = null;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
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
        return Integer.MAX_VALUE - 200;
    }
}
