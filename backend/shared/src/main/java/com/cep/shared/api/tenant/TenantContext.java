package com.cep.shared.api.tenant;

import com.cep.shared.api.error.ApiException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/**
 * Holds the tenant of the current unit of work (HTTP request or job). Set by the security layer
 * from the authenticated credential, and by the job worker from the job row; never from request bodies.
 */
public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private static final String MDC_KEY = "tenantId";

    private TenantContext() {}

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED", "No tenant in context");
        }
        return id;
    }

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            clear();
            return;
        }
        CURRENT.set(tenantId);
        MDC.put(MDC_KEY, tenantId.toString());
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
