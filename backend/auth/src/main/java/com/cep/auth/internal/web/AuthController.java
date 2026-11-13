package com.cep.auth.internal.web;

import com.cep.auth.api.CepPrincipal;
import com.cep.auth.api.CurrentActor;
import com.cep.auth.internal.security.RateLimiter;
import com.cep.auth.internal.security.SecurityProperties;
import com.cep.auth.internal.service.TokenService;
import com.cep.auth.internal.service.UserService;
import com.cep.shared.api.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Dashboard login and caller identity")
class AuthController {
    private final UserService users;
    private final TokenService tokens;
    private final RateLimiter limiter;
    private final SecurityProperties props;
    private final Clock clock;

    AuthController(UserService users, TokenService tokens, RateLimiter limiter, SecurityProperties props, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.limiter = limiter;
        this.props = props;
        this.clock = clock;
    }

    record LoginRequest(@NotBlank @Size(max = 64) String tenant, @NotBlank @Email String email,
                        @NotBlank @Size(max = 200) String password) {}

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    record MeResponse(UUID tenantId, String actorType, UUID actorId, String name, List<String> authorities) {}

    @PostMapping("/login")
    @Operation(summary = "Exchange tenant + email + password for a JWT access token")
    TokenResponse login(@Valid @RequestBody LoginRequest req) {
        String bucket = "login:" + req.tenant() + ":" + req.email().toLowerCase(Locale.ROOT);
        if (!limiter.acquire(bucket, props.loginAttemptsPerMinute(), Duration.ofMinutes(1)).allowed()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many login attempts; retry later");
        }
        var user = users.authenticate(req.tenant(), req.email(), req.password())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials"));
        var issued = tokens.issue(user);
        return new TokenResponse(issued.token(), "Bearer",
                Duration.between(clock.instant(), issued.expiresAt()).toSeconds());
    }

    @GetMapping("/me")
    @Operation(summary = "Describe the authenticated caller (user or application)")
    MeResponse me() {
        CepPrincipal p = CurrentActor.require();
        List<String> authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).sorted().toList();
        return new MeResponse(p.tenantId(), p.actorType().name(), p.actorId(), p.name(), authorities);
    }
}
