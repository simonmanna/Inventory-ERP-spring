package com.inventoryerp.license.web;

import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Turns {@link RequireAdmin} into a 401 or 403.
 *
 * <p>It runs after routing, so an unknown path is still a 404 rather than an authentication error —
 * the same ordering the ERP's {@code AccessGuardInterceptor} uses, for the same reason: a probe
 * should not be able to map the API by watching which paths answer 401.
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    /** Request attribute holding the verified admin. */
    public static final String PRINCIPAL = "license.admin";

    private final AdminTokens tokens;

    public AdminGuard(AdminTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        // Make the identity available to every route that has one, so audit rows name the actor
        // even on routes that do not require an admin.
        bearer(request).flatMap(tokens::verify)
                .ifPresent(principal -> request.setAttribute(PRINCIPAL, principal));

        RequireAdmin required = find(method, RequireAdmin.class);
        if (required == null) {
            return true;
        }
        Object principal = request.getAttribute(PRINCIPAL);
        if (!(principal instanceof AdminTokens.Principal admin)) {
            throw ApiException.unauthorized("Sign in to continue.");
        }
        if (required.value().length > 0 && !Arrays.asList(required.value()).contains(admin.role())) {
            throw ApiException.forbidden("Your role does not allow this action. Required: "
                    + Arrays.stream(required.value()).map(AdminRole::value).reduce((a, b) -> a + " or " + b)
                            .orElse(""));
        }
        return true;
    }

    private static java.util.Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(header.substring(7).trim());
    }

    /** Method annotation wins over the class one. */
    static <A extends Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }
}
