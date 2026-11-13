package com.cep.auth.internal.security;

import com.cep.shared.api.error.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Method-security denials (@PreAuthorize) surface inside the MVC layer, before the security filter chain
 * can translate them. Without this advice the catch-all handler would turn them into 500s.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityExceptionAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problems.problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action", null));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Problems.problem(HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "Authentication required", null));
    }
}
