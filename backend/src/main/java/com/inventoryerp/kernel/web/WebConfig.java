package com.inventoryerp.kernel.web;

import com.inventoryerp.kernel.idempotency.IdempotencyInterceptor;
import com.inventoryerp.kernel.security.AccessGuardInterceptor;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.RateLimitInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring in Nest order: guards (auth → permissions → module), throttler, then the idempotency
 * interceptor. {@link AuthUser} handler parameters receive the request identity ({@code @CurrentUser}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccessGuardInterceptor accessGuard;
    private final RateLimitInterceptor rateLimit;
    private final IdempotencyInterceptor idempotency;

    public WebConfig(AccessGuardInterceptor accessGuard, RateLimitInterceptor rateLimit,
            IdempotencyInterceptor idempotency) {
        this.accessGuard = accessGuard;
        this.rateLimit = rateLimit;
        this.idempotency = idempotency;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessGuard).order(0);
        registry.addInterceptor(rateLimit).order(1);
        registry.addInterceptor(idempotency).order(2);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AuthUser.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                    NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return request.getAttribute(AuthUser.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            }
        });
    }
}
