package com.cep.shared.internal.web;

import com.cep.shared.api.error.ApiException;
import com.cep.shared.api.error.ApiException.FieldIssue;
import com.cep.shared.api.error.Problems;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Problems.problem(e.status(), e.code(), e.getMessage(), e.errors()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException e) {
        List<FieldIssue> issues = e.getConstraintViolations().stream()
                .map(v -> new FieldIssue(v.getPropertyPath().toString(), v.getMessage())).toList();
        return ResponseEntity.badRequest()
                .body(Problems.problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", issues));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimistic(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.problem(HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION", "The resource was modified concurrently; reload and retry", null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.problem(HttpStatus.CONFLICT,
                "CONFLICT", "The request conflicts with existing data", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError().body(Problems.problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "An unexpected error occurred", null));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<FieldIssue> issues = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldIssue(f.getField(), f.getDefaultMessage())).toList();
        return ResponseEntity.badRequest()
                .body(Problems.problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", issues));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        // Spring MVC's own errors (404, 405, unreadable body…) get the same shape with a derived code.
        String code = HttpStatus.resolve(status.value()) == null ? "ERROR" : HttpStatus.resolve(status.value()).name();
        String detail = body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail() : ex.getMessage();
        if (status.value() == 400) {
            code = "MALFORMED_REQUEST";
            detail = "Malformed request body or parameters";
        }
        return ResponseEntity.status(status).headers(headers).body(Problems.problem(status, code, detail, null));
    }
}
