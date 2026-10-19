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
}
