package com.cep.auth.api;

import java.util.UUID;

/** The authenticated caller: a dashboard user or an integration application, always bound to one tenant. */
public record CepPrincipal(UUID tenantId, ActorType actorType, UUID actorId, String name, int rateLimitPerMinute) {
    public enum ActorType { USER, APPLICATION }
}
