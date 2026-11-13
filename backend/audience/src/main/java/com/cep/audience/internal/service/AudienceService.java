package com.cep.audience.internal.service;

import com.cep.audience.api.AudienceFacade;
import com.cep.audience.api.AudienceUsageChecker;
import com.cep.audience.internal.rules.Rule;
import com.cep.audience.internal.rules.RuleCompiler;
import com.cep.audience.internal.rules.RuleCompiler.InvalidRuleException;
import com.cep.audience.internal.rules.RuleEvaluator;
import com.cep.audience.internal.store.AudienceStore;
import com.cep.audience.internal.store.AudienceStore.AudienceRow;
import com.cep.audience.internal.store.AudienceStore.VersionRow;
import com.cep.auth.api.CepPrincipal;
import com.cep.shared.api.audit.AuditLog;
import com.cep.shared.api.audit.AuditLog.AuditEntry;
import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.error.ApiException.FieldIssue;
import com.cep.shared.api.util.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AudienceService implements AudienceFacade {
    public static final int MAX_CONTENT_BYTES = 64 * 1024;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{2,63}");
    private static final int COMPILED_CACHE_SIZE = 500;

    private final AudienceStore store;
    private final AuditLog audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ObjectProvider<AudienceUsageChecker> usageCheckers;
    private final RuleCompiler compiler = RuleCompiler.forAudiences();
    /** Versions are immutable, so a compiled rule can be cached by version row id. */
    private final Map<UUID, Rule> compiledCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Rule> eldest) {
            return size() > COMPILED_CACHE_SIZE;
        }
    });

    public AudienceService(AudienceStore store, AuditLog audit, ObjectMapper mapper, Clock clock,
                           ObjectProvider<AudienceUsageChecker> usageCheckers) {
        this.store = store;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.usageCheckers = usageCheckers;
    }

    /** An audience header with one of its versions. Also the stored idempotent response of create. */
    public record AudienceDetails(String audienceId, String name, AudienceStatus status, int version,
                                  int currentVersion, JsonNode content, JsonNode compiledCriteria,
                                  Instant createdAt, Instant updatedAt, Instant versionCreatedAt) {}

    public record AudienceListItem(String audienceId, String name, AudienceStatus status, int currentVersion,
                                   Instant createdAt, Instant updatedAt) {}

    public record Page<T>(List<T> items, int page, int size, long total) {}

    public record VersionItem(int version, JsonNode content, JsonNode compiledCriteria, UUID createdBy,
                              Instant createdAt) {}

    public record Validation(boolean valid, JsonNode compiledCriteria, List<FieldIssue> errors) {}

    // ---------------------------------------------------------------- commands

    @Transactional
    public AudienceDetails create(CepPrincipal actor, String requestedKey, String name, JsonNode content) {
        UUID tenant = actor.tenantId();
        RuleCompiler.Compiled compiled = compile(content);
        String key;
        if (requestedKey != null) {
            if (!KEY.matcher(requestedKey).matches()) {
                throw ApiException.unprocessable("INVALID_AUDIENCE_ID", "Invalid audienceId",
                        List.of(new FieldIssue("audienceId", "3-64 characters: letters, digits, '_' or '-'")));
            }
            if (store.exists(tenant, requestedKey)) {
                throw ApiException.conflict("AUDIENCE_EXISTS", "Audience '" + requestedKey + "' already exists");
            }
            key = requestedKey;
        } else {
            key = generateKey(tenant);
        }
        Instant now = clock.instant();
        UUID actorId = actor.actorId();
        AudienceRow row = new AudienceRow(Ids.newId(clock), tenant, key, name == null || name.isBlank() ? key : name.strip(),
                AudienceStatus.DRAFT.name(), 1, actorId, actorId, now, now);
        store.insert(row);
        VersionRow version = new VersionRow(Ids.newId(clock), row.id(), 1, content, compiled.normalized(), actorId, now);
        store.insertVersion(tenant, version);
        record(actor, "AUDIENCE_CREATED", key, null, Map.of("version", 1, "name", row.name()));
        return details(row, version);
    }

    /**
     * Saves new content as the next version. Unchanged content creates no version. {@code expectedVersion}
     * (from If-Match) guards against overwriting someone else's change.
     */
    @Transactional
    public AudienceDetails update(CepPrincipal actor, String key, String name, JsonNode content, Integer expectedVersion) {
        UUID tenant = actor.tenantId();
        AudienceRow row = store.lock(tenant, key).orElseThrow(AudienceService::notFound);
        requireNotArchived(row);
        if (expectedVersion != null && expectedVersion != row.currentVersion()) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH",
                    "Audience is at version " + row.currentVersion() + ", not " + expectedVersion + "; reload and retry");
        }
        VersionRow current = currentVersion(row);
        String newName = name == null || name.isBlank() ? row.name() : name.strip();
        JsonNode newContent = content == null ? current.content() : content;
        RuleCompiler.Compiled compiled = compile(newContent);
        boolean contentChanged = !compiled.normalized().equals(current.compiled()) || !newContent.equals(current.content());
        if (!contentChanged && newName.equals(row.name())) {
            return details(row, current);
        }
        Instant now = clock.instant();
        VersionRow version = current;
        int next = row.currentVersion();
        if (contentChanged) {
            next = row.currentVersion() + 1;
            version = new VersionRow(Ids.newId(clock), row.id(), next, newContent, compiled.normalized(), actor.actorId(), now);
            store.insertVersion(tenant, version);
        }
        AudienceRow updated = new AudienceRow(row.id(), tenant, key, newName, row.status(), next, row.createdBy(),
                actor.actorId(), row.createdAt(), now);
        store.updateHeader(updated);
        record(actor, "AUDIENCE_UPDATED", key, Map.of("version", row.currentVersion(), "name", row.name()),
                Map.of("version", next, "name", newName));
        return details(updated, version);
    }

    @Transactional
    public AudienceDetails activate(CepPrincipal actor, String key) {
        return transition(actor, key, AudienceStatus.ACTIVE, "AUDIENCE_ACTIVATED");
    }

    @Transactional
    public AudienceDetails deactivate(CepPrincipal actor, String key) {
        return transition(actor, key, AudienceStatus.INACTIVE, "AUDIENCE_DEACTIVATED");
    }

    /** Soft delete: hidden from the default list, cannot be edited or activated, kept for history. */
    @Transactional
    public AudienceDetails archive(CepPrincipal actor, String key) {
        return transition(actor, key, AudienceStatus.ARCHIVED, "AUDIENCE_ARCHIVED");
    }

    private AudienceDetails transition(CepPrincipal actor, String key, AudienceStatus target, String action) {
        UUID tenant = actor.tenantId();
        AudienceRow row = store.lock(tenant, key).orElseThrow(AudienceService::notFound);
        AudienceStatus from = AudienceStatus.valueOf(row.status());
        if (from == target || (target == AudienceStatus.INACTIVE && from == AudienceStatus.DRAFT)) {
            return details(row, currentVersion(row));
        }
        requireNotArchived(row);
        if (target != AudienceStatus.ACTIVE) {
            List<String> refs = usageCheckers.orderedStream()
                    .flatMap(c -> c.activeReferences(tenant, key).stream()).toList();
            if (!refs.isEmpty()) {
                throw ApiException.conflict("AUDIENCE_IN_USE", "Audience is used by " + String.join(", ", refs));
            }
        }
        Instant now = clock.instant();
        AudienceRow updated = new AudienceRow(row.id(), tenant, key, row.name(), target.name(), row.currentVersion(),
                row.createdBy(), actor.actorId(), row.createdAt(), now);
        store.updateHeader(updated);
        record(actor, action, key, Map.of("status", from.name()), Map.of("status", target.name()));
        return details(updated, currentVersion(updated));
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public AudienceDetails get(UUID tenant, String key, Integer version) {
        AudienceRow row = store.find(tenant, key).orElseThrow(AudienceService::notFound);
        return details(row, version == null ? currentVersion(row) : version(row, version));
    }

    @Transactional(readOnly = true)
    public Page<AudienceListItem> list(UUID tenant, AudienceStatus status, String search, int page, int size) {
        String s = status == null ? null : status.name();
        String q = search == null || search.isBlank() ? null : search.strip();
        List<AudienceListItem> items = store.page(tenant, s, q, page * size, size).stream()
                .map(r -> new AudienceListItem(r.key(), r.name(), AudienceStatus.valueOf(r.status()), r.currentVersion(),
                        r.createdAt(), r.updatedAt())).toList();
        return new Page<>(items, page, size, store.count(tenant, s, q));
    }

    @Transactional(readOnly = true)
    public List<VersionItem> versions(UUID tenant, String key) {
        AudienceRow row = store.find(tenant, key).orElseThrow(AudienceService::notFound);
        return store.versions(tenant, row.id()).stream()
                .map(v -> new VersionItem(v.version(), v.content(), v.compiled(), v.createdBy(), v.createdAt())).toList();
    }

    public Validation validate(JsonNode content) {
        try {
            return new Validation(true, compile(content).normalized(), List.of());
        } catch (ApiException e) {
            return new Validation(false, null, e.errors().isEmpty()
                    ? List.of(new FieldIssue("$", e.getMessage())) : e.errors());
        }
    }

    // ---------------------------------------------------------------- facade

    @Override
    @Transactional(readOnly = true)
    public Optional<AudienceSummary> find(UUID tenantId, String audienceId) {
        return store.find(tenantId, audienceId).map(r -> new AudienceSummary(r.key(), r.name(),
                AudienceStatus.valueOf(r.status()), r.currentVersion()));
    }

    @Override
    @Transactional(readOnly = true)
    public Evaluation evaluate(UUID tenantId, String audienceId, Integer version, Map<String, Object> context) {
        JsonNode node = mapper.valueToTree(context == null ? Map.of() : context);
        return evaluate(tenantId, audienceId, version, node);
    }

    @Transactional(readOnly = true)
    public Evaluation evaluate(UUID tenantId, String audienceId, Integer version, JsonNode context) {
        AudienceRow row = store.find(tenantId, audienceId).orElseThrow(AudienceService::notFound);
        VersionRow v = version == null ? currentVersion(row) : version(row, version);
        Rule rule = compiledCache.computeIfAbsent(v.id(), id -> compiler.compile(v.compiled()).rule());
        RuleEvaluator.Outcome outcome = RuleEvaluator.evaluate(rule, context);
        return new Evaluation(row.key(), v.version(), AudienceStatus.valueOf(row.status()), outcome.member(),
                outcome.matched().stream().map(this::result).toList(),
                outcome.failed().stream().map(this::result).toList());
    }

    // ---------------------------------------------------------------- helpers

    private ConditionResult result(RuleEvaluator.Leaf leaf) {
        return new ConditionResult(leaf.path(), leaf.field(), leaf.op(), mapper.treeToValue(leaf.value(), Object.class));
    }

    private RuleCompiler.Compiled compile(JsonNode content) {
        if (content != null && mapper.writeValueAsString(content).length() > MAX_CONTENT_BYTES) {
            throw ApiException.unprocessable("AUDIENCE_CONTENT_TOO_LARGE",
                    "Audience content is larger than " + MAX_CONTENT_BYTES + " bytes", List.of());
        }
        try {
            return compiler.compile(content);
        } catch (InvalidRuleException e) {
            throw ApiException.unprocessable("INVALID_AUDIENCE_CONTENT", "Audience content is invalid",
                    e.issues().stream().map(i -> new FieldIssue("content" + i.path().substring(1), i.message())).toList());
        }
    }

    private String generateKey(UUID tenant) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String key = "AUD" + store.nextKeyValue(tenant);
            if (!store.exists(tenant, key)) {
                return key;
            }
        }
        throw new IllegalStateException("Could not generate a free audience key");
    }

    private VersionRow currentVersion(AudienceRow row) {
        return version(row, row.currentVersion());
    }

    private VersionRow version(AudienceRow row, int version) {
        return store.findVersion(row.tenantId(), row.id(), version).orElseThrow(() -> ApiException.notFound(
                "AUDIENCE_VERSION_NOT_FOUND", "Audience " + row.key() + " has no version " + version));
    }

    private static void requireNotArchived(AudienceRow row) {
        if (AudienceStatus.ARCHIVED.name().equals(row.status())) {
            throw ApiException.conflict("AUDIENCE_ARCHIVED", "Audience " + row.key() + " is archived");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("AUDIENCE_NOT_FOUND", "Audience not found");
    }

    private static AudienceDetails details(AudienceRow row, VersionRow v) {
        return new AudienceDetails(row.key(), row.name(), AudienceStatus.valueOf(row.status()), v.version(),
                row.currentVersion(), v.content(), v.compiled(), row.createdAt(), row.updatedAt(), v.createdAt());
    }

    private void record(CepPrincipal actor, String action, String key, Object before, Object after) {
        audit.record(new AuditEntry(actor.tenantId(), actor.actorType().name(), actor.actorId().toString(), action,
                "audience", key, before, after));
    }
}
