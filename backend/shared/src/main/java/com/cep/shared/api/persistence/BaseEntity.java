package com.cep.shared.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Base for aggregates with application-assigned UUIDv7 ids. Implements {@link Persistable} so Spring Data
 * issues a plain INSERT for new entities instead of a select-then-merge. Timestamps come from the
 * platform {@code Clock} (passed in by services) rather than JVM time, so tests can control time.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected BaseEntity() {}

    protected BaseEntity(UUID id, Instant now) {
        this.id = id;
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = true;
    }

    @Override
    public UUID getId() { return id; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    protected void touch(Instant now) { this.updatedAt = now; }

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
