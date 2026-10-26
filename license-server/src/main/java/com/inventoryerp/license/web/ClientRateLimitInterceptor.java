package com.inventoryerp.license.web;

import com.inventoryerp.license.application.LicenseProperties;
import com.inventoryerp.license.shared.ApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP throttle on the routes an install may call without an account: activation, heartbeat,
 * deactivation and admin sign-in.
 *
 * <p>Those routes take a licence key or a password, so without a limit they are an offline guessing
 * oracle. The buckets are held in memory, which is the right trade for this service: a single
 * instance is the normal deployment, and behind several the effective limit simply multiplies by the
 * instance count rather than failing open.
 */
@Component
public class ClientRateLimitInterceptor implements HandlerInterceptor {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int perMinute;

    public ClientRateLimitInterceptor(LicenseProperties properties) {
        this.perMinute = Math.max(1, properties.getActivation().getRateLimitPerMinute());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (AdminGuard.find(method, RateLimited.class) == null) {
            return true;
        }
        String bucketKey = clientIp(request) + " " + method.getBeanType().getSimpleName();
        Bucket bucket = buckets.computeIfAbsent(bucketKey, key -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perMinute)
                        .refillGreedy(perMinute, Duration.ofMinutes(1))
                        .build())
                .build());
        if (!bucket.tryConsume(1)) {
            response.setHeader("Retry-After", "60");
            throw ApiException.tooManyRequests("Too many attempts. Try again in a minute.");
        }
        return true;
    }

    /** First hop of {@code X-Forwarded-For} when behind a proxy, else the socket address. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
