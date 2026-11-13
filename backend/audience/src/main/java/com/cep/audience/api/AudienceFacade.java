package com.cep.audience.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** What other modules (journey management and engine) may use from the audience module. */
public interface AudienceFacade {

    Optional<AudienceSummary> find(UUID tenantId, String audienceId);

    /**
     * Evaluates an audience against a context such as {@code {"customer":{"attributes":{...}},"event":{...}}}.
     * Uses {@code version} when given, otherwise the current version. Does not check the audience status:
     * the caller decides what an inactive audience means (see {@link Evaluation#status()}).
     *
     * @throws com.cep.shared.api.error.ApiException 404 AUDIENCE_NOT_FOUND or AUDIENCE_VERSION_NOT_FOUND
     */
    Evaluation evaluate(UUID tenantId, String audienceId, Integer version, Map<String, Object> context);

    record AudienceSummary(String audienceId, String name, AudienceStatus status, int currentVersion) {}

    enum AudienceStatus { DRAFT, ACTIVE, INACTIVE, ARCHIVED }

    /** One condition of the audience, identified by its position in the normalized rule tree. */
    record ConditionResult(String path, String field, String op, Object value) {}

    record Evaluation(String audienceId, int version, AudienceStatus status, boolean member,
                      java.util.List<ConditionResult> matched, java.util.List<ConditionResult> failed) {}
}
