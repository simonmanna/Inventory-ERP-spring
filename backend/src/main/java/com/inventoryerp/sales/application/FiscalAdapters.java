package com.inventoryerp.sales.application;

import java.time.Duration;
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

        @Override
        public boolean local() {
            return true;
        }
    }

    /**
     * Simulated authority for demos and tests (like the mock mobile-money gateway). By default it accepts and numbers
     * every request, answers lookups and returns the first result for a repeated reference. {@link #script} queues
     * outcomes for the next submissions:
     * <ul>
     *   <li>{@code ok}, {@code reject};
     *   <li>{@code timeout_accepted}: the provider accepts the request, the caller sees a timeout;
     *   <li>{@code timeout_lost}: the request never reaches the provider, the caller sees a timeout;
     *   <li>{@code crash_accepted}: the provider accepts the request and the calling worker dies before it records
     *       anything ({@link SimulatedCrash}).
     * </ul>
     * ADR-S094 C3b: {@link #capabilities} makes it a provider without lookup, without deduplication (a repeated
     * reference becomes a second fiscal document) or with a settlement delay; {@link #submissions} and
     * {@link #documents} count, per reference, what was sent and what the provider issued.
     */
    @Component
    public static class Mock implements FiscalDocumentPort {

        private final Deque<String> script = new ArrayDeque<>();
        private final Map<String, Result> accepted = new ConcurrentHashMap<>();
        private final Map<String, Integer> submissions = new ConcurrentHashMap<>();
        private final Map<String, Integer> documents = new ConcurrentHashMap<>();
        private int counter;
        private boolean lookup = true;
        private boolean dedupe = true;
        private Duration settlement = Duration.ZERO;

        @Override
        public String provider() {
            return "mock";
        }

        /** Queue outcomes for the next submissions. */
        public synchronized void script(String... outcomes) {
            script.clear();
            script.addAll(java.util.List.of(outcomes));
        }

        /** What recovery may rely on (see {@link FiscalDocumentPort}); {@link #reset} restores the defaults. */
        public synchronized void capabilities(boolean supportsLookup, boolean dedupes, Duration settlementDelay) {
            this.lookup = supportsLookup;
            this.dedupe = dedupes;
            this.settlement = settlementDelay;
        }

        /** Default behaviour: no script, lookup, deduplication, no settlement delay. */
        public synchronized void reset() {
            script.clear();
            capabilities(true, true, Duration.ZERO);
        }

        /** How many submissions of {@code requestRef} reached the provider. */
        public int submissions(String requestRef) {
            return submissions.getOrDefault(requestRef, 0);
        }

        /** How many fiscal documents the provider issued for {@code requestRef} (more than one: a duplicate). */
        public int documents(String requestRef) {
            return documents.getOrDefault(requestRef, 0);
        }

        @Override
        public synchronized boolean supportsLookup() {
            return lookup;
        }

        @Override
        public synchronized boolean dedupes() {
            return dedupe;
        }

        @Override
        public synchronized Duration settlement() {
            return settlement;
        }

        @Override
        public synchronized Result submit(Request request) {
            String ref = request.requestRef();
            Result known = accepted.get(ref);
            if (known != null && dedupe) {
                submissions.merge(ref, 1, Integer::sum);
                return known;
            }
            String outcome = script.isEmpty() ? "ok" : script.poll();
            if ("timeout_lost".equals(outcome)) {
                throw new Timeout("Simulated timeout before the provider received the request");
            }
            submissions.merge(ref, 1, Integer::sum);
            if ("reject".equals(outcome)) {
                return Result.rejected("Simulated rejection of " + request.operation());
            }
            Result ok = Result.ok("MOCK-" + ref, "FDN-" + (++counter));
            documents.merge(ref, 1, Integer::sum);
            accepted.put(ref, ok);
            if ("timeout_accepted".equals(outcome)) {
                throw new Timeout("Simulated timeout after acceptance");
            }
            if ("crash_accepted".equals(outcome)) {
                throw new SimulatedCrash();
            }
            return ok;
        }

        @Override
        public synchronized Optional<Result> lookup(String requestRef) {
            if (!lookup) {
                throw new UnsupportedOperationException("This provider cannot look requests up");
            }
            return Optional.ofNullable(accepted.get(requestRef));
        }

        /** The calling worker died after the provider accepted the request (tests catch it). */
        public static class SimulatedCrash extends Error {
            private static final long serialVersionUID = 1L;

            public SimulatedCrash() {
                super("Simulated worker crash after the provider accepted the request");
            }
        }
    }
}
