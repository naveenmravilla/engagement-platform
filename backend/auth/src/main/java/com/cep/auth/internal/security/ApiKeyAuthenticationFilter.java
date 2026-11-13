package com.cep.auth.internal.security;

import com.cep.auth.api.CepAuthentication;
import com.cep.auth.api.CepPrincipal;
import com.cep.auth.api.Roles;
import com.cep.auth.internal.domain.Application;
import com.cep.auth.internal.service.ApplicationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates integration callers via {@code X-API-Key}. Invalid keys are rejected outright (no fallthrough). */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    static final String HEADER = "X-API-Key";

    private final ApplicationService applications;
    private final ProblemSecurityHandlers problems;

    ApiKeyAuthenticationFilter(ApplicationService applications, ProblemSecurityHandlers problems) {
        this.applications = applications;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            problems.write(response, HttpStatus.UNAUTHORIZED, "AMBIGUOUS_CREDENTIALS",
                    "Send either X-API-Key or Authorization, not both");
            return;
        }
        Optional<Application> app = applications.authenticate(key.trim());
        if (app.isEmpty()) {
            problems.write(response, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "API key is invalid or revoked");
            return;
        }
        Application a = app.get();
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + Roles.INTEGRATION));
        a.getScopes().forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        var principal = new CepPrincipal(a.getTenantId(), CepPrincipal.ActorType.APPLICATION, a.getId(), a.getName(),
                a.getRateLimitPerMin());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new CepAuthentication(principal, authorities));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
