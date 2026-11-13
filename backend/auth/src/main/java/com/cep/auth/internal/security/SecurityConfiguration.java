package com.cep.auth.internal.security;

import com.cep.auth.api.CepAuthentication;
import com.cep.auth.api.CepPrincipal;
import com.cep.auth.internal.service.ApplicationService;
import com.cep.auth.internal.service.BootstrapProperties;
import com.cep.auth.internal.service.TokenService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, BootstrapProperties.class})
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApplicationService applications, RateLimiter limiter,
                                    ObjectMapper mapper, SecurityProperties props) throws Exception {
        ProblemSecurityHandlers problems = new ProblemSecurityHandlers(mapper);
        http
            .csrf(csrf -> csrf.disable()) // stateless token API: no cookies, no ambient credentials
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.jwtAuthenticationConverter(jwtConverter(props)))
                .authenticationEntryPoint(problems.entryPoint())
                .accessDeniedHandler(problems.accessDenied()))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(problems.entryPoint())
                .accessDeniedHandler(problems.accessDenied()))
            .addFilterBefore(new ApiKeyAuthenticationFilter(applications, problems), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new GatewayFilter(limiter, problems), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Maps our JWT claims to a {@link CepAuthentication}: {@code tid} → tenant, {@code roles} → ROLE_*. */
    static Converter<Jwt, AbstractAuthenticationToken> jwtConverter(SecurityProperties props) {
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList(TokenService.CLAIM_ROLES);
            var principal = new CepPrincipal(UUID.fromString(jwt.getClaimAsString(TokenService.CLAIM_TENANT)),
                    CepPrincipal.ActorType.USER, UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"),
                    props.userRateLimitPerMinute());
            var authorities = (roles == null ? List.<String>of() : roles).stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return new CepAuthentication(principal, authorities);
        };
    }

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("MARKETER")
                .role("MARKETER").implies("VIEWER")
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt by default
    }

    @Bean
    JwtEncoder jwtEncoder(SecurityProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey(props)));
    }

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(hmacKey(props)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.jwtIssuer()));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match", "X-Request-Id"));
        cors.setExposedHeaders(List.of("ETag", "X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static SecretKey hmacKey(SecurityProperties props) {
        return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
