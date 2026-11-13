package com.cep.app.support;

import com.cep.shared.api.jobs.Job;
import com.cep.shared.api.jobs.JobHandler;
import com.cep.shared.api.jobs.PermanentJobFailure;
import com.cep.shared.api.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Scriptable handler. Payload fields: {@code key} (string), {@code failTimes} (fail while attempt <= n),
 * {@code permanent} (throw PermanentJobFailure). Every attempt first writes a side-effect row, so tests can
 * prove that failed attempts are rolled back and successful ones commit with the job.
 */
public class RecordingJobHandler implements JobHandler {
    public record Handled(UUID jobId, String key, int attempt, UUID tenantInContext) {}

    private final String queue;
    private final JdbcClient jdbc;
    private final List<Handled> handled = new CopyOnWriteArrayList<>();

    public RecordingJobHandler(String queue, JdbcClient jdbc) {
        this.queue = queue;
        this.jdbc = jdbc;
    }

    @Override
    public String queue() { return queue; }

    @Override
    public void handle(Job job) {
        String key = job.payload().path("key").asString("");
        jdbc.sql("INSERT INTO test_side_effects (effect_key, attempt) VALUES (:k, :a)")
                .param("k", queue + ":" + key).param("a", job.attempt()).update();
        handled.add(new Handled(job.id(), key, job.attempt(), TenantContext.current().orElse(null)));
        if (job.payload().path("permanent").asBoolean(false)) {
            throw new PermanentJobFailure("scripted permanent failure");
        }
        if (job.attempt() <= job.payload().path("failTimes").asInt(0)) {
            throw new IllegalStateException("scripted failure on attempt " + job.attempt());
        }
    }

    public List<Handled> handled() { return List.copyOf(handled); }

    public void reset() { handled.clear(); }
}
