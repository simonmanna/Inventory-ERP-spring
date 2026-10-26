package com.inventoryerp.license.web;

import com.inventoryerp.license.domain.AdminRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a signed-in admin, optionally of a particular role.
 *
 * <p>Put it on the controller class and override on a method where a stronger role is needed, the
 * way the ERP's {@code @RequirePermissions} behaves. Absence of this annotation means the route is
 * client-facing (an install, authenticated by its licence key or token) or public.
 *
 * @see AdminGuard
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {

    /** Roles that may call this route. Empty means any signed-in admin. */
    AdminRole[] value() default {};
}
