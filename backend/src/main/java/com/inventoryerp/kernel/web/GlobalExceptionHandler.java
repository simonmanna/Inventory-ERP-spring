package com.inventoryerp.kernel.web;

import com.inventoryerp.kernel.idempotency.IdempotencyInterceptor;
import com.inventoryerp.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Renders every error like the Nest {@code GlobalExceptionFilter}: an HttpException's response object
 * as-is ({@code {message, error, statusCode}} for the built-in exceptions), 409 for an escaped unique
 * violation, 500 with only the request id otherwise. See docs/CONTRACT.md section 3.
 *
 * <p>Errors on {@code @Idempotent} routes pass through {@link IdempotencyInterceptor#onFailure} first,
 * which may substitute a committed outcome or cache a definitive client error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final boolean exposeDetail;
    private final boolean prod;
    private final IdempotencyInterceptor idempotency;

    public GlobalExceptionHandler(@Value("${app.errors.expose-detail:false}") boolean exposeDetail,
            Environment env, IdempotencyInterceptor idempotency) {
        this.exposeDetail = exposeDetail;
        this.prod = env.acceptsProfiles(Profiles.of("prod"));
        this.idempotency = idempotency;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException ex, HttpServletRequest req) {
        Object body;
        if (ex.hasBody()) {
            body = ex.body();
        } else {
            Map<String, Object> b = nestBody(ex.status(), ex.getMessage());
            b.putAll(ex.extra());
            body = b;
        }
        return respond(req, ex, ex.status(), body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Object> invalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        return badRequest(req, ex, messages(ex.getBindingResult().getAllErrors()));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<Object> invalidQuery(BindException ex, HttpServletRequest req) {
        return badRequest(req, ex, messages(ex.getBindingResult().getAllErrors()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Object> invalidParams(HandlerMethodValidationException ex, HttpServletRequest req) {
        List<String> messages = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e -> {
            String name = r.getMethodParameter().getParameterName();
            messages.add(prefixed(name, e.getDefaultMessage()));
        }));
        return badRequest(req, ex, messages);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<String> messages = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return prefixed(field, v.getMessage());
                })
                .toList();
        return badRequest(req, ex, messages);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> unreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof UnrecognizedPropertyException u) {
            return badRequest(req, ex, List.of("property " + u.getPropertyName() + " should not exist"));
        }
        if (cause instanceof tools.jackson.core.exc.StreamReadException) {
            // Express body-parser: malformed JSON is a 400 with the parser message.
            return respond(req, ex, HttpStatus.BAD_REQUEST, nestBody(HttpStatus.BAD_REQUEST, "Invalid JSON body"));
        }
        if (cause instanceof tools.jackson.databind.exc.MismatchedInputException m && !m.getPath().isEmpty()) {
            String field = m.getPath().getLast().getPropertyName();
            return badRequest(req, ex, List.of(field + " has an invalid value"));
        }
        return respond(req, ex, HttpStatus.BAD_REQUEST, nestBody(HttpStatus.BAD_REQUEST, "Invalid request body"));
    }

    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<Object> typeMismatch(TypeMismatchException ex, HttpServletRequest req) {
        return badRequest(req, ex, List.of(ex.getPropertyName() + " has an invalid value"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Object> missingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return badRequest(req, ex, List.of(ex.getParameterName() + " should not be empty"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Object> missingPart(MissingServletRequestPartException ex, HttpServletRequest req) {
        return respond(req, ex, HttpStatus.BAD_REQUEST, nestBody(HttpStatus.BAD_REQUEST, "Missing file"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Object> tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return respond(req, ex, HttpStatus.PAYLOAD_TOO_LARGE, nestBody(HttpStatus.PAYLOAD_TOO_LARGE, "File too large"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Object> mediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return respond(req, ex, HttpStatus.BAD_REQUEST, nestBody(HttpStatus.BAD_REQUEST, "Invalid request body"));
    }

    /** Express has no route for an unsupported verb: Nest answers {@code 404 Cannot <METHOD> <url>}. */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Object> notFound(Exception ex, HttpServletRequest req) {
        String url = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(nestBody(HttpStatus.NOT_FOUND, "Cannot " + req.getMethod() + " " + url));
    }

    /** Unique violation escaping a service means two clients raced and the first won (Prisma P2002 parity). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> conflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        if (!isUniqueViolation(ex)) {
            return unhandled(ex, req);
        }
        log.warn("Unique conflict on {} {}: {}", req.getMethod(), req.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "This operation conflicts with one that was just completed. Refresh and try again.");
        body.put("statusCode", 409);
        body.put("requestId", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        return respond(req, ex, HttpStatus.CONFLICT, body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unhandled(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {} [req: {}]", req.getMethod(), req.getRequestURI(),
                req.getAttribute(RequestIdFilter.ATTRIBUTE), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Internal Server Error");
        body.put("statusCode", 500);
        body.put("requestId", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        if (!prod || exposeDetail) {
            body.put("error", ex.getMessage());
            body.put("name", ex.getClass().getSimpleName());
        }
        return respond(req, ex, HttpStatus.INTERNAL_SERVER_ERROR, body);
    }

    /** Nest built-in exception body: {@code {message, error, statusCode}}. */
    public static Map<String, Object> nestBody(HttpStatus status, Object message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("error", status.getReasonPhrase());
        body.put("statusCode", status.value());
        return body;
    }

    private ResponseEntity<Object> badRequest(HttpServletRequest req, Exception ex, List<String> messages) {
        return respond(req, ex, HttpStatus.BAD_REQUEST, nestBody(HttpStatus.BAD_REQUEST, messages));
    }

    private ResponseEntity<Object> respond(HttpServletRequest req, Exception ex, HttpStatus status, Object body) {
        var replaced = idempotency.onFailure(req, ex, status.value(), body);
        if (replaced != null) {
            return ResponseEntity.status(replaced.status()).body(replaced.body());
        }
        return ResponseEntity.status(status).body(body);
    }

    private static List<String> messages(List<ObjectError> errors) {
        List<String> out = new ArrayList<>();
        for (ObjectError e : errors) {
            String field = e instanceof FieldError fe ? fe.getField() : e.getObjectName();
            if (field.contains(".")) {
                // nested: class-validator reports the leaf with its parent path
                field = field.replaceAll("\\[(\\d+)]", ".$1");
            }
            out.add(prefixed(field, e.getDefaultMessage()));
        }
        return out;
    }

    /** class-validator style "{field} {constraint}"; custom messages already naming the field pass through. */
    static String prefixed(String field, String message) {
        if (message == null) {
            return field + " is invalid";
        }
        String leaf = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
        if (message.startsWith(leaf + " ") || message.startsWith(field + " ") || message.startsWith("property ")
                || !Character.isLowerCase(message.charAt(0))) {
            return message;
        }
        return field + " " + message;
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
        Throwable t = ex.getMostSpecificCause();
        return t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState());
    }
}
