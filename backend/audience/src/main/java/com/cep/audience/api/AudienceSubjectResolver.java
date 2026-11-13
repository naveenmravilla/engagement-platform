package com.cep.audience.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the evaluation context for a customer ID, e.g. {@code {"customer":{"attributes":{...}}}}.
 * Implemented by the customer module (Phase 4). Until a bean exists, {@code /evaluate} accepts only an inline
 * {@code context}.
 */
public interface AudienceSubjectResolver {
    Optional<Map<String, Object>> resolve(UUID tenantId, String customerId);
}
