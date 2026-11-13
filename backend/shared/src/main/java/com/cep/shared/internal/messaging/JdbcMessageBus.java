package com.cep.shared.internal.messaging;

import static com.cep.shared.internal.persistence.Sql.ts;

import com.cep.shared.api.messaging.MessageBus;
import com.cep.shared.api.messaging.OutboxMessage;
import com.cep.shared.api.util.Ids;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcMessageBus implements MessageBus {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcMessageBus(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(OutboxMessage m) {
        jdbc.sql("""
                INSERT INTO outbox_events (id, tenant_id, topic, aggregate_type, aggregate_id, payload, created_at)
                VALUES (:id, :tenant, :topic, :atype, :aid, CAST(:payload AS jsonb), :now)
                """)
                .param("id", Ids.newId(clock))
                .param("tenant", m.tenantId())
                .param("topic", m.topic())
                .param("atype", m.aggregateType())
                .param("aid", m.aggregateId())
                .param("payload", mapper.writeValueAsString(m.payload()))
                .param("now", ts(clock.instant()))
                .update();
    }
}
