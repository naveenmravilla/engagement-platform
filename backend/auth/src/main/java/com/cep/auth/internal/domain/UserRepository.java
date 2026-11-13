package com.cep.auth.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    List<User> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);
}
