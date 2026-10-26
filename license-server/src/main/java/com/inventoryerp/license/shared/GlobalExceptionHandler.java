package com.inventoryerp.license.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error leaves as {@code {message, error, statusCode}} — the ERP's shape (docs/CONTRACT.md §3).
 *
 * <p>An unexpected failure returns only a request id; the detail goes to the log. This service holds
 * signing material and customer records, so a stack trace never reaches a caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException ex) {
        Map<String, Object> body = body(ex.status(), ex.getMessage());
        body.putAll(ex.extra());
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Object> invalid(MethodArgumentNotValidException ex) {
        List<String> messages = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            messages.add(error instanceof FieldError f
                    ? f.getField() + " " + f.getDefaultMessage()
                    : error.getDefaultMessage());
        }
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST, messages.isEmpty() ? "Validation failed" : messages);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "Malformed request body"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> illegal(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> conflict(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "This record changed while you were editing it. Reload and try again."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> integrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "That value is already taken."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.error("Unhandled error [{}] on {} {}", requestId, request.getMethod(), request.getRequestURI(), ex);
        Map<String, Object> body = body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        body.put("requestId", requestId);
        return ResponseEntity.internalServerError().body(body);
    }

    public static Map<String, Object> body(HttpStatus status, Object message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", message);
        out.put("error", status.getReasonPhrase());
        out.put("statusCode", status.value());
        return out;
    }
}
