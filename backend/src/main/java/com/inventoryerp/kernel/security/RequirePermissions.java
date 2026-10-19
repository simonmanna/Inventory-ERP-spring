package com.inventoryerp.kernel.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires the caller to hold ALL listed {@code resource:action} permissions (Nest
 * {@code @RequirePermissions}). A method-level annotation overrides the controller-level one.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermissions {
    String[] value();
}
