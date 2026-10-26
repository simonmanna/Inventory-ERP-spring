package com.inventoryerp.license.web;

import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.application.LicenseProperties;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring: one API prefix for every controller, the admin guard, the client throttle, and
 * {@link AdminTokens.Principal} as a handler parameter.
 *
 * <p>The prefix is applied here rather than written into every {@code @RequestMapping}, so routes
 * read as they do in the spec and the prefix can be changed in one place to match a reverse proxy.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminGuard adminGuard;
    private final ClientRateLimitInterceptor rateLimit;
    private final LicenseProperties properties;

    public WebConfig(AdminGuard adminGuard, ClientRateLimitInterceptor rateLimit, LicenseProperties properties) {
        this.adminGuard = adminGuard;
        this.rateLimit = rateLimit;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Throttle before authenticating: a guessing attack must cost nothing to refuse.
        registry.addInterceptor(rateLimit).order(0);
        registry.addInterceptor(adminGuard).order(1);
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        String prefix = properties.getApiPrefix();
        if (prefix != null && !prefix.isBlank() && !"/".equals(prefix)) {
            configurer.addPathPrefix(prefix, handlerType ->
                    handlerType.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class));
        }
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {

            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return AdminTokens.Principal.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                    NativeWebRequest request, WebDataBinderFactory binderFactory) {
                // Null on a route that does not require an admin — guarded routes cannot reach here
                // without one, because AdminGuard has already refused.
                return request.getAttribute(AdminGuard.PRINCIPAL, RequestAttributes.SCOPE_REQUEST);
            }
        });
    }
}
