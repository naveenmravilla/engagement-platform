package com.cep.auth.internal.security;

import com.cep.auth.api.CepAuthentication;
import com.cep.auth.api.CepPrincipal;
import com.cep.shared.api.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after authentication: binds the caller's tenant to {@link TenantContext} for the request and
 * enforces the per-caller rate limit (per application for API keys, per user for dashboard tokens).
 */
final class GatewayFilter extends OncePerRequestFilter {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final ProblemSecurityHandlers problems;

    GatewayFilter(RateLimiter limiter, ProblemSecurityHandlers problems) {
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof CepAuthentication auth)) {
            chain.doFilter(request, response);
            return;
        }
        CepPrincipal p = auth.getPrincipal();
        RateLimiter.Decision d = limiter.acquire(p.actorType().name().toLowerCase() + ":" + p.actorId(),
                p.rateLimitPerMinute(), WINDOW);
        response.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
        if (!d.allowed()) {
            response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Rate limit exceeded; retry later");
            return;
        }
        TenantContext.set(p.tenantId());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
