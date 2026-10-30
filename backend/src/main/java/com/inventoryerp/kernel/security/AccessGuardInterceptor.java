package com.inventoryerp.kernel.security;

import com.inventoryerp.kernel.featureflags.FeatureFlagsService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The three Nest global guards in their registration order, run after routing (so an unknown route is
 * still a 404): {@code JwtAuthGuard} (401 unless {@link Public}), {@code PermissionsGuard} (403 unless
 * the DB permission set holds every {@link RequirePermissions} entry) and {@code ModuleEnabledGuard}
 * ({@link RequiresModule}).
 */
@Component
public class AccessGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AccessGuardInterceptor.class);

    /** Modules whose boot-time gate is an env flag (Nest {@code MODULE_ENV_FLAGS} → app.modules.*). */
    private static final Map<String, String> MODULE_ENV_FLAGS = Map.of(
            "task", "app.modules.tasks",
            "manufacturing", "app.modules.manufacturing",
            "rental", "app.modules.rental",
            "repair", "app.modules.repair",
            "hr", "app.modules.hr",
            "beverage", "app.modules.beverage",
            "fixed-asset", "app.modules.assets",
            "communication", "app.modules.communication",
            "fleet", "app.modules.fleet",
            "quarry", "app.modules.quarry");

    private final PermissionService permissions;
    private final FeatureFlagsService flags;
    private final Environment env;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public AccessGuardInterceptor(PermissionService permissions, FeatureFlagsService flags, Environment env) {
        this.permissions = permissions;
        this.flags = flags;
        this.env = env;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        AuthUser auth = (AuthUser) request.getAttribute(AuthUser.ATTRIBUTE);

        // JwtAuthGuard
        if (find(method, Public.class) == null && auth == null) {
            throw ApiException.unauthorized("Authentication required");
        }

        // PermissionsGuard
        RequirePermissions required = find(method, RequirePermissions.class);
        if (required != null && required.value().length > 0) {
            if (auth == null) {
                throw ApiException.forbidden("Authentication required");
            }
            List<String> granted = permissions.permissionsOf(auth.organizationId(), auth.sub());
            TenantContext.setPermissions(granted);
            for (String perm : required.value()) {
                if (!granted.contains(perm)) {
                    throw ApiException.forbidden(
                            "Missing required permission(s): " + String.join(", ", required.value()));
                }
            }
        }

        // ModuleEnabledGuard
        RequiresModule module = find(method, RequiresModule.class);
        if (module != null) {
            String orgId = TenantContext.optionalOrganizationId();
            if (orgId != null && !moduleAllowed(orgId, module.value())) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Module '" + module.value() + "' is not enabled for this organization");
            }
        }
        return true;
    }

    private boolean moduleAllowed(String orgId, String required) {
        if (!flags.hasAnyModuleRow()) {
            if (warned.add(orgId)) {
                log.warn("Organization {} has no OrganizationModule rows; allowing '{}' by default. "
                        + "Backfill organization modules to make module gating explicit for this tenant.", orgId, required);
            }
            return true;
        }
        if (flags.isModuleEnabled(required)) {
            return true;
        }
        String property = MODULE_ENV_FLAGS.get(required);
        return property != null && env.getProperty(property, Boolean.class, false);
    }

    /**
     * Nest {@code reflector.getAllAndOverride([handler, class])}: method annotation wins.
     *
     * <p>Public because the licence gate ({@code kernel.licensing}) resolves its own annotations the
     * same way, and the two must agree on which one wins.
     */
    public static <A extends Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }
}
