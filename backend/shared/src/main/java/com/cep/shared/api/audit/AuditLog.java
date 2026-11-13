package com.cep.shared.api.audit;

import java.util.UUID;

/** Append-only audit trail. Joins the caller's transaction so audit and change commit together. */
public interface AuditLog {
    void record(AuditEntry entry);

    record AuditEntry(UUID tenantId, String actorType, String actorId, String action, String entityType,
                      String entityId, Object before, Object after) {}
}
