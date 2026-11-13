package com.cep.app.messaging;

import static com.cep.app.support.TestSupportConfiguration.QUEUE;
import static com.cep.app.support.TestSupportConfiguration.SECOND_QUEUE;
import static com.cep.app.support.TestSupportConfiguration.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cep.app.support.IntegrationTest;
import com.cep.app.support.RecordingJobHandler;
import com.cep.shared.api.messaging.MessageBus;
import com.cep.shared.api.messaging.OutboxMessage;
import com.cep.shared.internal.jobs.JobWorker;
import com.cep.shared.internal.messaging.OutboxRelay;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxIT extends IntegrationTest {
    static final UUID TENANT = UUID.randomUUID();

    @Autowired MessageBus bus;
    @Autowired OutboxRelay relay;
    @Autowired JobWorker worker;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;
    @Autowired @Qualifier("recordingJobHandler") RecordingJobHandler first;
    @Autowired @Qualifier("secondJobHandler") RecordingJobHandler second;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outbox_events").update();
        jdbc.sql("DELETE FROM jobs").update();
        first.reset();
        second.reset();
    }

    private int jobs(String queue) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE queue = :q").param("q", queue).query(Integer.class).single();
    }

    @Test
    void messageFromRolledBackTransactionIsNeverDelivered() {
        tx.executeWithoutResult(s -> {
            bus.publish(new OutboxMessage(TENANT, TOPIC, "order", "o-1", Map.of("key", "rolled-back")));
            s.setRollbackOnly();
        });
        assertThat(relay.runOnce()).isZero();
        assertThat(jobs(QUEUE)).isZero();
    }

    @Test
    void committedMessageFansOutToEverySubscriberExactlyOnce() {
        tx.executeWithoutResult(s -> bus.publish(new OutboxMessage(TENANT, TOPIC, "order", "o-2", Map.of("key", "ok"))));

        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(relay.runOnce()).as("already published").isZero();
        assertThat(jobs(QUEUE)).isEqualTo(1);
        assertThat(jobs(SECOND_QUEUE)).isEqualTo(1);

        worker.drain(10);
        assertThat(first.handled()).singleElement().satisfies(h -> {
            assertThat(h.key()).isEqualTo("ok");
            assertThat(h.tenantInContext()).isEqualTo(TENANT);
        });
        assertThat(second.handled()).hasSize(1);
    }

    @Test
    void relayCrashAfterEnqueueDoesNotDuplicateDeliveries() {
        tx.executeWithoutResult(s -> bus.publish(new OutboxMessage(TENANT, TOPIC, "order", "o-3", Map.of("key", "dup"))));
        relay.runOnce();
        // Simulate a relay that enqueued but crashed before marking published: un-publish and relay again.
        jdbc.sql("UPDATE outbox_events SET published_at = NULL").update();
        relay.runOnce();
        assertThat(jobs(QUEUE)).isEqualTo(1);
    }

    @Test
    void publishOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> bus.publish(new OutboxMessage(TENANT, TOPIC, null, null, Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
