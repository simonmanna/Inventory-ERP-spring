package com.inventoryerp.shared.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Business error rendered as the Nest body {@code {statusCode, message, error}}. Messages must be
 * copied verbatim from the Nest source: the web client shows them to users.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> extra;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(HttpStatus status, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.extra = extra;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }
}
