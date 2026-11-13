package com.cep.auth.api;

import java.util.Set;

/** Dashboard roles (hierarchy ADMIN > MARKETER > VIEWER) and integration scopes. */
public final class Roles {
    public static final String ADMIN = "ADMIN";
    public static final String MARKETER = "MARKETER";
    public static final String VIEWER = "VIEWER";
    /** Granted to every authenticated API-key caller; finer access comes from scopes. */
    public static final String INTEGRATION = "INTEGRATION";

    public static final Set<String> USER_ROLES = Set.of(ADMIN, MARKETER, VIEWER);

    public static final String SCOPE_JOURNEYS_EXECUTE = "journeys:execute";
    public static final String SCOPE_EVENTS_INGEST = "events:ingest";
    public static final String SCOPE_NOTIFICATIONS_SEND = "notifications:send";
    public static final String SCOPE_CUSTOMERS_WRITE = "customers:write";
    public static final String SCOPE_CUSTOMERS_READ = "customers:read";
    public static final String SCOPE_AUDIENCES_EVALUATE = "audiences:evaluate";

    public static final Set<String> SCOPES = Set.of(SCOPE_JOURNEYS_EXECUTE, SCOPE_EVENTS_INGEST,
            SCOPE_NOTIFICATIONS_SEND, SCOPE_CUSTOMERS_WRITE, SCOPE_CUSTOMERS_READ, SCOPE_AUDIENCES_EVALUATE);

    private Roles() {}
}
