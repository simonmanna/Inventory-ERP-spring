package com.inventoryerp.kernel.web;

import com.inventoryerp.kernel.idempotency.CachedBodyFilter;
import com.inventoryerp.kernel.idempotency.IdempotencyInterceptor;
import com.inventoryerp.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
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
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

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
    private final JsonMapper mapper;
    private final Validator validator;

    public GlobalExceptionHandler(@Value("${app.errors.expose-detail:false}") boolean exposeDetail,
            Environment env, IdempotencyInterceptor idempotency, JsonMapper mapper, Validator validator) {
        this.exposeDetail = exposeDetail;
        this.prod = env.acceptsProfiles(Profiles.of("prod"));
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.validator = validator;
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
            List<String> all = whitelistAndFieldMessages(req);
            if (all != null) {
                return badRequest(req, ex, all);
            }
            // class-validator prefixes a nested whitelist error with its parent path: "lines.0.property x ..."
            String parent = parentPath(u.getPath());
            return badRequest(req, ex, List.of((parent.isEmpty() ? "" : parent + ".") + "property "
                    + u.getPropertyName() + " should not exist"));
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

    /**
     * The client went away (closed tab, SSE stream dropped) or an async stream timed out. There is nobody to answer
     * and the response may already be {@code text/event-stream}, so write nothing instead of a JSON error body.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    void clientGone(Exception ex, HttpServletRequest req) {
        log.debug("Async request ended on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.toString());
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
        for (ObjectError e : classValidatorOrder(errors)) {
            String field = e instanceof FieldError fe ? fe.getField() : e.getObjectName();
            String msg = e.getDefaultMessage();
            if (msg != null && msg.startsWith("@")) {
                // object-level constraint on a nested element: class-validator joins "<path>.<message>"
                out.add(field.replaceAll("\\[(\\d+)]", ".$1") + "." + msg.substring(1));
                continue;
            }
            if (field.contains(".")) {
                // nested: class-validator reports the leaf with its parent path
                field = field.replaceAll("\\[(\\d+)]", ".$1");
            }
            out.add(prefixed(field, e.getDefaultMessage()));
        }
        return out;
    }

    /**
     * class-validator runs the whitelist check and the property constraints in one pass: the 400 lists every unknown
     * property ({@code property x should not exist}, nested ones under their parent path) followed by the field
     * messages. Jackson stops at the first unknown property, so the cached body is read again skipping unknown
     * properties and then validated. Null when that is not possible (the single whitelist message stays).
     */
    private List<String> whitelistAndFieldMessages(HttpServletRequest req) {
        if (!(req.getAttribute(EmptyBodyAdvice.BODY_TARGET) instanceof EmptyBodyAdvice.BodyTarget target)
                || !(req.getAttribute(CachedBodyFilter.RAW_BODY) instanceof byte[] raw)) {
            return null;
        }
        List<String> unknown = new ArrayList<>();
        Object body;
        try {
            body = mapper.readerFor(mapper.constructType(target.type()))
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .withHandler(new DeserializationProblemHandler() {
                        @Override
                        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                ValueDeserializer<?> deserializer, Object beanOrClass, String propertyName) {
                            // The parser already sits on the value; for an object/array value that is its own
                            // context, so climb to the object that holds the unknown property.
                            TokenStreamContext holder = p.streamReadContext();
                            while (holder != null
                                    && !(holder.inObject() && propertyName.equals(holder.currentName()))) {
                                holder = holder.getParent();
                            }
                            String parent = holder == null ? "" : contextPath(holder.getParent());
                            unknown.add((parent.isEmpty() ? "" : parent + ".") + "property " + propertyName
                                    + " should not exist");
                            p.skipChildren();
                            return true;
                        }
                    })
                    .readValue(raw);
        } catch (RuntimeException e) {
            return null;
        }
        if (unknown.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>(unknown);
        boolean validated = target.parameter().hasParameterAnnotation(Valid.class)
                || target.parameter().hasParameterAnnotation(Validated.class);
        if (body != null && validated) {
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(body, "body");
            new SpringValidatorAdapter(validator).validate(body, result);
            out.addAll(messages(result.getAllErrors()));
        }
        return out;
    }

    /**
     * class-validator order: properties in declaration order (nested properties and list indexes likewise), and the
     * constraints of one property in reverse decorator order. Hibernate Validator collects violations in a
     * {@code HashSet}, so the order is rebuilt from the DTO's declared components and annotation order (DTO records
     * list their constraints in Nest decorator order).
     */
    static List<ObjectError> classValidatorOrder(List<ObjectError> errors) {
        List<ObjectError> sorted = new ArrayList<>(errors);
        sorted.sort(Comparator.comparing(GlobalExceptionHandler::orderKey, GlobalExceptionHandler::compareKeys));
        return sorted;
    }

    private static List<Integer> orderKey(ObjectError error) {
        List<Integer> key = new ArrayList<>();
        ConstraintViolation<?> violation;
        try {
            violation = error.unwrap(ConstraintViolation.class);
        } catch (IllegalArgumentException e) {
            key.add(Integer.MAX_VALUE);
            return key;
        }
        Class<?> type = violation.getRootBeanClass();
        java.lang.reflect.Field field = null;
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.isInIterable()) {
                key.add(node.getIndex() == null ? 0 : node.getIndex());
            }
            if (node.getKind() != ElementKind.PROPERTY || type == null) {
                continue;
            }
            List<java.lang.reflect.Field> fields = Arrays.stream(type.getDeclaredFields())
                    .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers())).toList();
            field = fields.stream().filter(f -> f.getName().equals(node.getName())).findFirst().orElse(null);
            key.add(field == null ? Integer.MAX_VALUE : fields.indexOf(field));
            type = field == null ? null : elementType(field);
        }
        Annotation constraint = violation.getConstraintDescriptor().getAnnotation();
        int position = field == null ? -1 : Arrays.asList(field.getAnnotations()).indexOf(constraint);
        key.add(-position);
        return key;
    }

    /** The bean type a property leads to: the element type for a collection, else the declared type. */
    private static Class<?> elementType(java.lang.reflect.Field field) {
        if (Collection.class.isAssignableFrom(field.getType())
                && field.getGenericType() instanceof ParameterizedType p
                && p.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return field.getType();
    }

    private static int compareKeys(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /** Dotted path of a parser context ({@code lines.0}); empty at the root. */
    private static String contextPath(TokenStreamContext ctx) {
        List<String> parts = new ArrayList<>();
        for (TokenStreamContext c = ctx; c != null && !c.inRoot(); c = c.getParent()) {
            parts.addFirst(c.inArray() ? String.valueOf(c.getCurrentIndex()) : c.currentName());
        }
        return String.join(".", parts);
    }

    /** Dotted JSON path of everything above the last reference ({@code lines.0.readings.0}); empty at top level. */
    static String parentPath(List<tools.jackson.core.JacksonException.Reference> path) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            tools.jackson.core.JacksonException.Reference ref = path.get(i);
            parts.add(ref.getPropertyName() != null ? ref.getPropertyName() : String.valueOf(ref.getIndex()));
        }
        return String.join(".", parts);
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
