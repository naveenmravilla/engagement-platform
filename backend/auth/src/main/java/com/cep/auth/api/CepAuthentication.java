package com.cep.auth.api;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/** Uniform authentication token for both API-key and JWT callers. */
public final class CepAuthentication extends AbstractAuthenticationToken {
    private final CepPrincipal principal;

    public CepAuthentication(CepPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() { return null; }

    @Override
    public CepPrincipal getPrincipal() { return principal; }

    @Override
    public String getName() { return principal.name(); }
}
