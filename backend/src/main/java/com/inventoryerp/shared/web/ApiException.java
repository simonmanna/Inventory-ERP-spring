package com.inventoryerp.shared.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Business error rendered like a Nest {@code HttpException}. Messages must be copied verbatim from
 * the Nest source: the web client shows them to users.
 *
 * <ul>
 *   <li>{@code new XxxException('msg')} → {@link #ApiException(HttpStatus, String)}: body
 *       {@code {message, error, statusCode}}.
 *   <li>{@code new XxxException()} → {@link #bare(HttpStatus)}: body {@code {message: <reason>, statusCode}}.
 *   <li>{@code new XxxException({...})} / {@code new HttpException(body, status)} →
 *       {@link #withBody(HttpStatus, Object)}: the given object is the whole body.
 * </ul>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> extra;
    private final Object body;
    private final boolean hasBody;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(HttpStatus status, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.extra = extra;
        this.body = null;
        this.hasBody = false;
    }

    private ApiException(HttpStatus status, String message, Object body) {
        super(message);
        this.status = status;
        this.extra = Map.of();
        this.body = body;
        this.hasBody = true;
    }

    /** The given value is rendered as the complete response body (Nest object-argument exceptions). */
    public static ApiException withBody(HttpStatus status, Object body) {
        String message = body instanceof Map<?, ?> m && m.get("message") instanceof String s ? s : String.valueOf(body);
        return new ApiException(status, message, body);
    }

    /** Nest exception constructed without arguments: {@code {message: "Unauthorized", statusCode: 401}}. */
    public static ApiException bare(HttpStatus status) {
        var b = new java.util.LinkedHashMap<String, Object>();
        b.put("message", status.getReasonPhrase());
        b.put("statusCode", status.value());
        return new ApiException(status, status.getReasonPhrase(), b);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    /** Complete body when constructed via {@link #withBody}; otherwise {@code null}. */
    public Object body() {
        return body;
    }

    public boolean hasBody() {
        return hasBody;
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
