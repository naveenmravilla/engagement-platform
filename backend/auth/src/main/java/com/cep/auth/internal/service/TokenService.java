package com.cep.auth.internal.service;

import com.cep.auth.internal.domain.User;
import com.cep.auth.internal.security.SecurityProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_ROLES = "roles";

    private final JwtEncoder encoder;
    private final SecurityProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, SecurityProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwtIssuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(exp)
                .claim(CLAIM_TENANT, user.getTenantId().toString())
                .claim(CLAIM_ROLES, user.getRoles().stream().sorted().toList())
                .claim("email", user.getEmail())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, exp);
    }
}
