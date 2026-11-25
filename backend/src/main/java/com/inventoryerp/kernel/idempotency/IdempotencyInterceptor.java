package com.inventoryerp.kernel.idempotency;

import com.inventoryerp.kernel.web.JsonResponses;
import com.inventoryerp.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Port of {@code idempotency.interceptor.ts} for {@link Idempotent} routes. Runs after the guards and
 * before argument resolution/validation (Nest interceptors precede pipes): checks a required key,
 * replays or reserves it, runs the handler inside the {@link BusinessOutcome} operation, then stores
 * the response and answers {@code 200} (the Nest interceptor overwrites the route status).
 */
@Component
@ControllerAdvice
public class IdempotencyInterceptor implements HandlerInterceptor, ResponseBodyAdvice<Object> {

    private static final String STATE = "erp.idempotency";

    private final IdempotencyService service;
    private final JsonMapper mapper;

    public IdempotencyInterceptor(IdempotencyService service, JsonMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** Per-request state. */
    private static final class State {
        final BusinessOutcome.Operation operation;
        boolean finished;
        /** Gaps h-06 F7: a write transaction of the handler committed (not a {@link BusinessOutcome#RETRY_SAFE_TX}). */
        boolean committed;
        /** Gaps h-06 F7: a write transaction of the handler rolled back. */
        boolean rolledBack;

        State(BusinessOutcome.Operation operation) {
            this.operation = operation;
        }
    }

    /**
     * Gaps h-06 F7: record on the current request (if it reserved a key) that one of its write transactions committed
     * or rolled back ({@link IdempotencyTransactionListener}).
     */
    static void noteTransaction(boolean committed) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        if (attributes.getAttribute(STATE, RequestAttributes.SCOPE_REQUEST) instanceof State state
                && state.operation != null && !state.finished) {
            if (committed) {
                state.committed = true;
            } else {
                state.rolledBack = true;
            }
        }
    }

    /** Replacement response chosen after a failure. */
    public record Replacement(int status, Object body) {}

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Idempotent idem = annotation(method);
        if (idem == null) {
            return true;
        }
        if (idem.required()) {
            String raw = request.getHeader("Idempotency-Key");
            if (raw == null || raw.trim().isEmpty()) {
                throw ApiException.badRequest(
                        "An Idempotency-Key header is required for this operation so a retry can never charge twice.");
            }
        }
        String key = IdempotencyService.readKey(request.getHeader("Idempotency-Key"));
        if (key == null) {
            request.setAttribute(STATE, new State(null));
            return true;
        }
        byte[] raw = (byte[]) request.getAttribute(CachedBodyFilter.RAW_BODY);
        String rawBody = raw == null || raw.length == 0 ? "{}" : new String(raw, StandardCharsets.UTF_8);
        String multipart = multipartFingerprint(request);
        if (multipart != null) {
            // B5: a multipart body is not buffered; hash its parts, so a key reused with another file is refused (409)
            rawBody = multipart;
        }
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        JsonNode payload = parse(rawBody);
        var begin = service.begin(new IdempotencyService.Request(key,
                service.hashRequest(request.getMethod(), path, rawBody), request.getMethod(), path, payload));
        if (begin instanceof IdempotencyService.Replay replay) {
            JsonResponses.write(mapper, response, replay.statusCode(), replay.body());
            return false;
        }
        var proceed = (IdempotencyService.Proceed) begin;
        request.setAttribute(STATE, new State(proceed.recorded() ? proceed.operation() : null));
        if (proceed.recorded()) {
            BusinessOutcome.set(proceed.operation());
        }
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
            ModelAndView modelAndView) {
        // Handlers returning nothing never reach beforeBodyWrite.
        State state = (State) request.getAttribute(STATE);
        if (state != null && !state.finished && !response.isCommitted()) {
            state.finished = true;
            response.setStatus(200);
            if (state.operation != null) {
                service.complete(state.operation, 200, null);
            }
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        BusinessOutcome.set(null);
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            return body;
        }
        State state = (State) servlet.getServletRequest().getAttribute(STATE);
        if (state == null || state.finished) {
            return body;
        }
        state.finished = true;
        response.setStatusCode(HttpStatus.OK);
        if (state.operation != null) {
            service.complete(state.operation, 200, body);
        }
        return body;
    }

    /**
     * Called by the global exception handler for every error. On an idempotent route whose key was
     * reserved, applies {@link IdempotencyService#fail}; returns {@code null} to keep the original error.
     */
    public Replacement onFailure(HttpServletRequest request, Exception ex, int status, Object body) {
        State state = (State) request.getAttribute(STATE);
        if (state == null || state.finished) {
            return null;
        }
        state.finished = true;
        if (state.operation == null) {
            return null;
        }
        var replay = service.fail(state.operation, status, body, status < 500);
        if (replay == null && !state.committed && !state.rolledBack && status < 500 && beforeHandler(ex)) {
            // B5: the request was refused while binding or validating its arguments, before the handler ran: nothing
            // can have been written, so release the key (it stayed pending and answered OPERATION_PENDING forever).
            service.release(state.operation);
        } else if (replay == null && state.rolledBack && !state.committed) {
            // Gaps h-06 F7: the handler's work rolled back and nothing of it committed, so there is nothing to
            // recover: release the key and let a retry with it run again (it answered OPERATION_PENDING forever).
            service.release(state.operation);
        }
        return replay == null ? null : new Replacement(replay.statusCode(), replay.body());
    }

    /** Errors Spring raises while reading, binding or validating a handler's arguments: the handler never ran. */
    static boolean beforeHandler(Exception ex) {
        return ex instanceof org.springframework.web.bind.MethodArgumentNotValidException
                || ex instanceof org.springframework.web.method.annotation.HandlerMethodValidationException
                || ex instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || ex instanceof org.springframework.web.bind.MissingServletRequestParameterException
                || ex instanceof org.springframework.web.multipart.support.MissingServletRequestPartException
                || ex instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
                || ex instanceof org.springframework.web.HttpMediaTypeNotSupportedException;
    }

    /**
     * The fingerprint of a multipart request: each form field and each file's SHA-256, in name order; {@code null}
     * for any other request.
     */
    static String multipartFingerprint(HttpServletRequest request) {
        var multipart = org.springframework.web.util.WebUtils.getNativeRequest(request,
                org.springframework.web.multipart.MultipartHttpServletRequest.class);
        if (multipart == null) {
            return null;
        }
        StringBuilder out = new StringBuilder("multipart");
        new java.util.TreeMap<>(multipart.getParameterMap()).forEach((name, values) ->
                out.append('|').append(name).append('=').append(String.join(",", values)));
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (var entry : new java.util.TreeMap<>(multipart.getMultiFileMap()).entrySet()) {
                for (var file : entry.getValue()) {
                    out.append('|').append(entry.getKey()).append(':').append(java.util.HexFormat.of()
                            .formatHex(digest.digest(file.getBytes())));
                }
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint the uploaded file", e);
        }
        return out.toString();
    }

    private JsonNode parse(String rawBody) {
        try {
            return mapper.readTree(rawBody);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Idempotent annotation(HandlerMethod method) {
        Idempotent onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Idempotent.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Idempotent.class);
    }
}
