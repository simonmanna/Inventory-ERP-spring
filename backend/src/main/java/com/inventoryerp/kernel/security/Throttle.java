package com.inventoryerp.kernel.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Route-level override of the {@code default} throttler (Nest {@code @Throttle({ default: { limit, ttl } })}).
 * Overridden limits are never multiplied for signed-in staff.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Throttle {
    int limit();

    long ttlMs();

    /**
     * For anonymous traffic, also key the counter on this path variable (a public form key or link token), so one
     * visitor hammering one form or link never uses up the budget of another form or another tenant. Empty = per
     * client address and route only (the Nest behaviour).
     */
    String perPathVariable() default "";
}
