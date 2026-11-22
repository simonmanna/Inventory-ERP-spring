package com.inventoryerp.kernel.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires the caller to hold ALL listed {@code resource:action} permissions (Nest
 * {@code @RequirePermissions}), or ANY of them when {@link #anyOf()} is set. A method-level annotation overrides the
 * controller-level one.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermissions {
    String[] value();

    /** {@code true}: holding one of the listed permissions is enough (shared lookups used by several screens). */
    boolean anyOf() default false;
}
