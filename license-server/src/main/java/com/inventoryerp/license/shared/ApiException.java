package com.inventoryerp.license.shared;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A business error with an HTTP status and a message meant for a human.
 *
 * <p>Deliberately the same body shape as the ERP's {@code ApiException}
 * ({@code {message, error, statusCode}}) so one client error handler serves both services.
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
        this.extra = extra == null ? Map.of() : extra;
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

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
