package com.cep.audience.api;

import java.util.List;
import java.util.UUID;

/**
 * Reports what still depends on an audience, so it cannot be deactivated or archived while in use.
 * Implemented by journey management (Phase 5) for ACTIVE journeys.
 */
public interface AudienceUsageChecker {
    /** @return human-readable references, e.g. {@code "journey JRN10001"}; empty if unused. */
    List<String> activeReferences(UUID tenantId, String audienceId);
}
