package com.cep.auth.api;

import com.cep.shared.api.error.ApiException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the authenticated caller from any module. */
public final class CurrentActor {
    private CurrentActor() {}

    public static Optional<CepPrincipal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof CepAuthentication cep ? Optional.of(cep.getPrincipal()) : Optional.empty();
    }

    public static CepPrincipal require() {
        return find().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"));
    }
}
