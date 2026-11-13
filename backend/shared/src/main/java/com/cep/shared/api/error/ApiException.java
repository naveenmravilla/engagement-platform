package com.cep.shared.api.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Business error rendered as RFC 7807 problem+json with a stable machine-readable {@code code}.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldIssue> errors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldIssue> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldIssue> errors() { return errors; }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unprocessable(String code, String message, List<FieldIssue> errors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, errors);
    }

    /** A single field- or path-level problem. */
    public record FieldIssue(String field, String message) {}
}
