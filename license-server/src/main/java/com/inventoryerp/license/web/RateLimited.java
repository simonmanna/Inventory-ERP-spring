package com.inventoryerp.license.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Throttles this route per client IP at {@code app.activation.rate-limit-per-minute}.
 *
 * <p>Belongs on anything that accepts a secret from an unauthenticated caller — a licence key or a
 * password — so it cannot be guessed at speed.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {}
