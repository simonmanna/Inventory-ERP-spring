package com.inventoryerp.sales.application;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Built-in fiscal adapters (ADR-S056 §8). A real EFRIS adapter is a later increment. */
public final class FiscalAdapters {

    private FiscalAdapters() {}

    /** No fiscal authority: every operation is accepted locally, without an external call. */
    @Component
    public static class None implements FiscalDocumentPort {

        @Override
        public String provider() {
            return "none";
        }

        @Override
        public Result submit(Request request) {
            return Result.ok(null, null);
        }

        @Override
        public Optional<Result> lookup(String requestRef) {
            return Optional.empty();
        }
    }

    /**
     * Simulated authority for demos and tests (like the mock mobile-money gateway). By default it accepts and numbers
     * every request; {@link #script} queues outcomes for the next submissions: {@code ok}, {@code reject} or
     * {@code timeout_accepted} (the provider accepts the request but the caller sees a timeout).
     */
    @Component
    public static class Mock implements FiscalDocumentPort {

        private final Deque<String> script = new ArrayDeque<>();
        private final Map<String, Result> accepted = new ConcurrentHashMap<>();
        private int counter;

        @Override
        public String provider() {
            return "mock";
        }

        /** Queue outcomes for the next submissions. */
        public synchronized void script(String... outcomes) {
            script.clear();
            script.addAll(java.util.List.of(outcomes));
        }

        @Override
        public synchronized Result submit(Request request) {
            Result known = accepted.get(request.requestRef());
            if (known != null) {
                return known;
            }
            String outcome = script.isEmpty() ? "ok" : script.poll();
            if ("reject".equals(outcome)) {
                return Result.rejected("Simulated rejection of " + request.operation());
            }
            Result ok = Result.ok("MOCK-" + request.requestRef(), "FDN-" + (++counter));
            accepted.put(request.requestRef(), ok);
            if ("timeout_accepted".equals(outcome)) {
                throw new Timeout("Simulated timeout after acceptance");
            }
            return ok;
        }

        @Override
        public Optional<Result> lookup(String requestRef) {
            return Optional.ofNullable(accepted.get(requestRef));
        }
    }
}
