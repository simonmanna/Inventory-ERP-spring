package com.inventoryerp.kernel.web;

import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Express {@code json()} parity: a request without a body reaches Nest's ValidationPipe as {@code {}}, so a DTO
 * with required fields answers the usual field messages instead of a generic "body missing" error. Here an empty
 * body is read as the empty JSON object, then validated like any other.
 */
@ControllerAdvice
public class EmptyBodyAdvice extends RequestBodyAdviceAdapter {

    /** Request attribute with the {@link BodyTarget} of the {@code @RequestBody} being read. */
    public static final String BODY_TARGET = "erp.bodyTarget";

    /** The body parameter and its generic type, for re-reading the body when Jackson rejects it. */
    public record BodyTarget(MethodParameter parameter, Type type) {}

    private final JsonMapper mapper;

    public EmptyBodyAdvice(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(BODY_TARGET, new BodyTarget(parameter, targetType),
                    RequestAttributes.SCOPE_REQUEST);
        }
        return inputMessage;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        if (body != null) {
            return body;
        }
        try {
            return mapper.readValue("{}", mapper.constructType(targetType));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
