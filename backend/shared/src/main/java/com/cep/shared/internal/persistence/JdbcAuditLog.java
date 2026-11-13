package com.cep.shared.internal.persistence;

import static com.cep.shared.internal.persistence.Sql.ts;

import com.cep.shared.api.audit.AuditLog;
import com.cep.shared.api.util.Ids;
import java.time.Clock;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcAuditLog implements AuditLog {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcAuditLog(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AuditEntry e) {
        jdbc.sql("""
                INSERT INTO audit_logs (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id,
                                        before_state, after_state, request_id, created_at)
                VALUES (:id, :tenant, :atype, :aid, :action, :etype, :eid,
                        CAST(:before AS jsonb), CAST(:after AS jsonb), :rid, :now)
                """)
                .param("id", Ids.newId(clock))
                .param("tenant", e.tenantId())
                .param("atype", e.actorType())
                .param("aid", e.actorId())
                .param("action", e.action())
                .param("etype", e.entityType())
                .param("eid", e.entityId())
                .param("before", e.before() == null ? null : mapper.writeValueAsString(e.before()))
                .param("after", e.after() == null ? null : mapper.writeValueAsString(e.after()))
                .param("rid", MDC.get("requestId"))
                .param("now", ts(clock.instant()))
                .update();
    }
}
