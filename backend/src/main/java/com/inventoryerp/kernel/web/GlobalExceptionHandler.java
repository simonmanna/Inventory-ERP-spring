package com.inventoryerp.kernel.web;

import com.inventoryerp.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error in the Nest wire shape {@code {statusCode, message, error}}. Port of
 * apps/api/src/kernel/filters/global-exception.filter.ts. See docs/CONTRACT.md section 3.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final boolean exposeDetail;

    public GlobalExceptionHandler(@Value("${app.errors.expose-detail:false}") boolean exposeDetail) {
        this.exposeDetail = exposeDetail;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException ex) {
        Map<String, Object> body = nestBody(ex.status(), ex.getMessage());
        body.putAll(ex.extra());
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        List<String> messages = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(nestBody(HttpStatus.BAD_REQUEST, messages));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> invalidParams(HandlerMethodValidationException ex) {
        List<String> messages = ex.getAllErrors().stream()
                .map(e -> String.valueOf(e.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(nestBody(HttpStatus.BAD_REQUEST, messages));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(nestBody(HttpStatus.BAD_REQUEST, "Invalid request body"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> denied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(nestBody(HttpStatus.FORBIDDEN, "Forbidden resource"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(nestBody(HttpStatus.NOT_FOUND, "Cannot " + req.getMethod() + " " + req.getRequestURI()));
    }

    /** Unique violation escaping a service means two clients raced and the first won (Prisma P2002 parity). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        if (!isUniqueViolation(ex)) {
            return unhandled(ex, req);
        }
        log.warn("Unique conflict on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "This operation conflicts with one that was just completed. Refresh and try again.");
        body.put("statusCode", 409);
        body.put("requestId", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unhandled(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Internal Server Error");
        body.put("statusCode", 500);
        body.put("requestId", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        if (exposeDetail) {
            body.put("error", ex.getMessage());
            body.put("name", ex.getClass().getSimpleName());
        }
        return ResponseEntity.internalServerError().body(body);
    }

    public static Map<String, Object> nestBody(HttpStatus status, Object message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statusCode", status.value());
        body.put("message", message);
        body.put("error", status.getReasonPhrase());
        return body;
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
        Throwable t = ex.getMostSpecificCause();
        return t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState());
    }
}
