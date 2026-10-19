package com.inventoryerp.kernel.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route as Idempotency-Key protected (Nest {@code @Idempotent()} on a controller that also
 * applies {@code @UseInterceptors(IdempotencyInterceptor)} — only put it where Nest has both).
 * Retries with the same key + body replay the cached response instead of re-running the handler.
 * Like Nest, a protected route answers {@code 200} (or the cached status on replay), not 201.
 *
 * <p>{@code required = true} additionally rejects a request without the header; use it wherever running
 * the handler twice would move money.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    boolean required() default false;
}
