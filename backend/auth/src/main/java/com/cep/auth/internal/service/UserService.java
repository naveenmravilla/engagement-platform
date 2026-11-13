package com.cep.auth.internal.service;

import com.cep.auth.api.CepPrincipal;
import com.cep.auth.api.Roles;
import com.cep.auth.internal.domain.Tenant;
import com.cep.auth.internal.domain.TenantRepository;
import com.cep.auth.internal.domain.User;
import com.cep.auth.internal.domain.UserRepository;
import com.cep.shared.api.audit.AuditLog;
import com.cep.shared.api.audit.AuditLog.AuditEntry;
import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.util.Ids;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final Clock clock;
    /** Compared against when the user does not exist, so response time doesn't reveal valid emails. */
    private final String dummyHash;

    public UserService(UserRepository users, TenantRepository tenants, PasswordEncoder encoder, AuditLog audit, Clock clock) {
        this.users = users;
        this.tenants = tenants;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = encoder.encode("timing-equalizer-" + UUID.randomUUID());
    }

    @Transactional
    public User create(CepPrincipal actor, String email, String password, String displayName, Set<String> roles) {
        User user = createInternal(actor.tenantId(), email, password, displayName, roles);
        audit.record(new AuditEntry(actor.tenantId(), actor.actorType().name(), actor.actorId().toString(), "USER_CREATED",
                "user", user.getId().toString(), null, Map.of("email", user.getEmail(), "roles", user.getRoles())));
        return user;
    }

    @Transactional
    User createInternal(UUID tenantId, String email, String password, String displayName, Set<String> roles) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.unprocessable("WEAK_PASSWORD", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters",
                    List.of(new ApiException.FieldIssue("password", "min length " + MIN_PASSWORD_LENGTH)));
        }
        if (roles == null || roles.isEmpty() || !Roles.USER_ROLES.containsAll(roles)) {
            throw ApiException.unprocessable("INVALID_ROLES", "Roles must be a non-empty subset of " + Roles.USER_ROLES, List.of());
        }
        if (users.existsByTenantIdAndEmail(tenantId, normalized)) {
            throw ApiException.conflict("USER_EXISTS", "A user with this email already exists");
        }
        return users.save(new User(Ids.newId(clock), tenantId, normalized, encoder.encode(password), displayName, roles,
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<User> list(UUID tenantId) {
        return users.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** @return the active user if the credentials are valid. Audits failures. */
    @Transactional
    public Optional<User> authenticate(String tenantKey, String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Optional<Tenant> tenant = tenants.findByTenantKey(tenantKey).filter(Tenant::isActive);
        Optional<User> user = tenant.flatMap(t -> users.findByTenantIdAndEmail(t.getId(), normalized)).filter(User::isActive);
        boolean ok = encoder.matches(password, user.map(User::getPasswordHash).orElse(dummyHash)) && user.isPresent();
        if (!ok) {
            tenant.ifPresent(t -> audit.record(new AuditEntry(t.getId(), "ANONYMOUS", null, "LOGIN_FAILED", "user",
                    normalized, null, null)));
            return Optional.empty();
        }
        user.get().recordLogin(clock.instant());
        return user;
    }
}
