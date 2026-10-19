package com.inventoryerp.kernel.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventoryerp.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Port of Nest's {@code ThrottlerModule} ({@code default}: 100 / 60 s, {@code burst}: 600 / 10 min) with
 * {@code StaffAwareThrottlerGuard}: authenticated traffic is tracked per user and the global ceilings
 * are multiplied by {@code THROTTLE_STAFF_MULTIPLIER} (5); anonymous traffic is tracked per IP. Counters
 * are per route, hits expire individually after the TTL (sliding window, like the Nest in-memory
 * storage), and an exceeded limit blocks the tracker for one TTL. Route overrides ({@link Throttle})
 * replace only {@code default} and are never multiplied.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Throttler(String name, int limit, long ttlMs) {}

    private static final Throttler DEFAULT = new Throttler("default", 100, 60_000);
    private static final Throttler BURST = new Throttler("burst", 600, 600_000);
    private static final String MESSAGE = "ThrottlerException: Too Many Requests";

    private static final class Window {
        final Deque<Long> hits = new ArrayDeque<>();
        long expiresAt;
        long blockUntil;
    }

    private final boolean enabled;
    private final int staffMultiplier;
    private final Cache<String, Window> windows =
            Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(30)).maximumSize(200_000).build();

    public RateLimitInterceptor(
            @Value("${app.throttle.enabled:true}") boolean enabled,
            @Value("${THROTTLE_STAFF_MULTIPLIER:5}") int staffMultiplier) {
        this.enabled = enabled;
        this.staffMultiplier = staffMultiplier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        AuthUser auth = (AuthUser) request.getAttribute(AuthUser.ATTRIBUTE);
        String tracker = auth != null
                ? "user:" + (auth.organizationId() == null ? "-" : auth.organizationId()) + ":" + auth.sub()
                : request.getRemoteAddr();
        Throttle override = AccessGuardInterceptor.find(method, Throttle.class);
        String route = method.getBeanType().getName() + "-" + method.getMethod().getName();
        for (Throttler t : new Throttler[] {DEFAULT, BURST}) {
            int limit = t == DEFAULT && override != null ? override.limit() : t.limit();
            long ttl = t == DEFAULT && override != null ? override.ttlMs() : t.ttlMs();
            if (auth != null && limit == t.limit() && staffMultiplier > 1) {
                limit = limit * staffMultiplier;
            }
            hit(route + "-" + t.name() + "-" + tracker, t.name(), limit, ttl, response);
        }
        return true;
    }

    private void hit(String key, String name, int limit, long ttl, HttpServletResponse response) {
        String suffix = "default".equals(name) ? "" : "-" + name;
        Window w = windows.get(key, k -> new Window());
        long now = System.currentTimeMillis();
        long remaining;
        long reset;
        synchronized (w) {
            while (!w.hits.isEmpty() && w.hits.peekFirst() <= now - ttl) {
                w.hits.pollFirst();
            }
            if (w.expiresAt <= now) {
                w.expiresAt = now + ttl;
            }
            if (w.blockUntil > now) {
                response.setHeader("Retry-After" + suffix, String.valueOf(ceilSeconds(w.blockUntil - now)));
                throw ApiException.withBody(HttpStatus.TOO_MANY_REQUESTS, MESSAGE);
            }
            if (w.blockUntil != 0) {
                w.blockUntil = 0;
                w.hits.clear();
            }
            w.hits.addLast(now);
            if (w.hits.size() > limit) {
                w.blockUntil = now + ttl;
                response.setHeader("Retry-After" + suffix, String.valueOf(ceilSeconds(ttl)));
                throw ApiException.withBody(HttpStatus.TOO_MANY_REQUESTS, MESSAGE);
            }
            remaining = Math.max(0, limit - w.hits.size());
            reset = ceilSeconds(w.expiresAt - now);
        }
        response.setHeader("X-RateLimit-Limit" + suffix, String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining" + suffix, String.valueOf(remaining));
        response.setHeader("X-RateLimit-Reset" + suffix, String.valueOf(reset));
    }

    private static long ceilSeconds(long ms) {
        return (long) Math.ceil(ms / 1000.0);
    }

    /** Test hook: forget every counter. */
    public void reset() {
        windows.invalidateAll();
    }
}
