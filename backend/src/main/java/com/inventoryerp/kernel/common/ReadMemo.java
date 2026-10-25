package com.inventoryerp.kernel.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-request memo for configuration reads that cannot change while one use case runs (organization currency /
 * time zone, currency decimals, setting rows, account mappings). Outside {@link #run} every lookup goes straight to
 * the loader, so behaviour is unchanged wherever no scope is open. Inside a scope the first load of a key is
 * remembered on the calling thread until the scope ends; {@code null} results are never remembered (a missing row
 * may be created later in the same request). Writers of memoized data call {@link #clear()}.
 *
 * <p>Detached writes and other threads never see a scope (it is thread-confined), and nested {@link #run} calls
 * share the outermost scope.
 */
public final class ReadMemo {

    private static final ThreadLocal<Map<List<Object>, Object>> SCOPE = new ThreadLocal<>();

    private ReadMemo() {}

    /** Run {@code body} with a memo scope (joins the enclosing one when there is one). */
    public static <T> T run(Supplier<T> body) {
        if (SCOPE.get() != null) {
            return body.get();
        }
        SCOPE.set(new HashMap<>());
        try {
            return body.get();
        } finally {
            SCOPE.remove();
        }
    }

    /** The memoized value for {@code key} parts, loading it on first use inside a scope. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Supplier<T> loader, Object... key) {
        Map<List<Object>, Object> scope = SCOPE.get();
        if (scope == null) {
            return loader.get();
        }
        List<Object> k = Arrays.asList(key.clone());
        Object hit = scope.get(k);
        if (hit != null) {
            return (T) hit;
        }
        T loaded = loader.get();
        if (loaded != null) {
            scope.put(k, loaded);
        }
        return loaded;
    }

    /** Forget everything memoized in the current scope (no-op outside one). */
    public static void clear() {
        Map<List<Object>, Object> scope = SCOPE.get();
        if (scope != null) {
            scope.clear();
        }
    }

    /** Whether a scope is open on this thread. */
    public static boolean active() {
        return SCOPE.get() != null;
    }
}
