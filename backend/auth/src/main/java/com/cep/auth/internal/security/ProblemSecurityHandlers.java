package com.cep.auth.internal.security;

import com.cep.shared.api.error.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/** Renders 401/403 as the same problem+json shape as every other API error. */
final class ProblemSecurityHandlers {
    private final ObjectMapper mapper;

    ProblemSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Valid credentials are required (X-API-Key or Bearer token)");
    }

    AccessDeniedHandler accessDenied() {
        return (request, response, ex) -> write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action");
    }

    void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), Problems.problem(status, code, detail, null));
    }
}
