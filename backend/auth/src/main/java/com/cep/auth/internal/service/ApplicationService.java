package com.cep.auth.internal.service;

import com.cep.auth.api.CepPrincipal;
import com.cep.auth.api.Roles;
import com.cep.auth.internal.domain.Application;
import com.cep.auth.internal.domain.ApplicationRepository;
import com.cep.auth.internal.domain.TenantRepository;
import com.cep.shared.api.audit.AuditLog;
import com.cep.shared.api.audit.AuditLog.AuditEntry;
import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.util.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final ApplicationRepository apps;
    private final TenantRepository tenants;
    private final AuditLog audit;
    private final Clock clock;

    public ApplicationService(ApplicationRepository apps, TenantRepository tenants, AuditLog audit, Clock clock) {
        this.apps = apps;
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
    }

    public record Created(Application application, String rawApiKey) {}

    @Transactional
    public Created create(CepPrincipal actor, String name, Set<String> scopes, Integer rateLimitPerMin) {
        UUID tenantId = actor.tenantId();
        Set<String> effectiveScopes = scopes == null || scopes.isEmpty() ? Roles.SCOPES : scopes;
        Set<String> unknown = new java.util.TreeSet<>(effectiveScopes);
        unknown.removeAll(Roles.SCOPES);
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("UNKNOWN_SCOPE", "Unknown scopes: " + unknown,
                    List.of(new ApiException.FieldIssue("scopes", "allowed: " + new java.util.TreeSet<>(Roles.SCOPES))));
        }
        if (apps.existsByTenantIdAndName(tenantId, name)) {
            throw ApiException.conflict("APPLICATION_EXISTS", "An application named '" + name + "' already exists");
        }
        String raw = ApiKeys.generate();
        Application app = apps.save(new Application(Ids.newId(clock), tenantId, name, ApiKeys.hash(raw),
                ApiKeys.displayPrefix(raw), effectiveScopes, rateLimitPerMin == null ? 600 : rateLimitPerMin, clock.instant()));
        audit.record(new AuditEntry(tenantId, actor.actorType().name(), actor.actorId().toString(), "APPLICATION_CREATED",
                "application", app.getId().toString(), null,
                Map.of("name", name, "scopes", app.getScopes(), "keyPrefix", app.getApiKeyPrefix())));
        return new Created(app, raw);
    }

    @Transactional(readOnly = true)
    public List<Application> list(UUID tenantId) {
        return apps.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public Application revoke(CepPrincipal actor, UUID id) {
        Application app = apps.findByIdAndTenantId(id, actor.tenantId())
                .orElseThrow(() -> ApiException.notFound("APPLICATION_NOT_FOUND", "Application not found"));
        if (app.isActive()) {
            app.revoke(clock.instant());
            audit.record(new AuditEntry(actor.tenantId(), actor.actorType().name(), actor.actorId().toString(),
                    "APPLICATION_REVOKED", "application", id.toString(), null, null));
        }
        return app;
    }

    /** Resolves an API key to an active application of an active tenant. */
    @Transactional
    public Optional<Application> authenticate(String rawKey) {
        if (!ApiKeys.looksValid(rawKey)) {
            return Optional.empty();
        }
        Optional<Application> app = apps.findByApiKeyHash(ApiKeys.hash(rawKey))
                .filter(Application::isActive)
                .filter(a -> tenants.findById(a.getTenantId()).map(t -> t.isActive()).orElse(false));
        app.ifPresent(a -> {
            Instant now = clock.instant();
            apps.touchLastUsed(a.getId(), now, now.minus(LAST_USED_RESOLUTION));
        });
        return app;
    }
}
