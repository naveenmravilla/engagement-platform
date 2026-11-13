package com.cep.auth.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {
    Optional<Application> findByApiKeyHash(String apiKeyHash);

    List<Application> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Application> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    /** Throttled heartbeat: at most one write per application per {@code threshold} window. */
    @Modifying
    @Query("""
            update Application a set a.lastUsedAt = :now
             where a.id = :id and (a.lastUsedAt is null or a.lastUsedAt < :threshold)
            """)
    int touchLastUsed(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
