package com.inventoryerp.sales.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Fiscal-document authority (e.g. URA EFRIS) for branch cash sales (ADR-S056 §8, cash-sales.md §7). Adapters are
 * called by {@link CashSaleFiscalService} outside the business transaction; every request carries a stable
 * {@code requestRef} so a retry after a timeout first asks the provider whether it already accepted the request.
 *
 * <p>ADR-S094 C3b (§5.5): an adapter declares what recovery may rely on: whether {@link #lookup} can tell an accepted
 * request from one never received, whether a repeated {@code requestRef} returns the first result instead of a second
 * document, and how long after a send a lookup is authoritative. An outcome is never re-sent blindly to a provider that
 * can do neither: the operation waits for a person to reconcile it.
 */
public interface FiscalDocumentPort {

    /** Provider key stored on the branch profile ({@code none}, {@code mock}, later {@code efris}). */
    String provider();

    /** Submit one operation ({@code issue}, {@code cancel}, {@code correct}); throws {@link Timeout} when unknown. */
    Result submit(Request request);

    /** What the provider knows about {@code requestRef} (empty: never received). */
    Optional<Result> lookup(String requestRef);

    /** True when the adapter makes no external call: an operation completes in the caller's transaction. */
    default boolean local() {
        return false;
    }

    /** True when {@link #lookup} is authoritative (after {@link #settlement}): empty means the provider never got it. */
    default boolean supportsLookup() {
        return true;
    }

    /** True when submitting an accepted {@code requestRef} again returns its first result, never a second document. */
    default boolean dedupes() {
        return false;
    }

    /** How long after a send a request may still appear at the provider (a lookup before then proves nothing). */
    default Duration settlement() {
        return Duration.ZERO;
    }

    /** One request: stable reference, operation and the document payload. */
    record Request(String requestRef, String operation, Map<String, Object> payload) {}

    /** Outcome: accepted (with the provider's references) or rejected with an error. */
    record Result(boolean accepted, String externalRef, String fiscalNumber, String error,
            Map<String, Object> response) {

        public static Result ok(String externalRef, String fiscalNumber) {
            return new Result(true, externalRef, fiscalNumber, null, Map.of());
        }

        public static Result rejected(String error) {
            return new Result(false, null, null, error, Map.of());
        }
    }

    /** The outcome of a request is unknown (timeout, connection reset): recovery looks it up by reference. */
    class Timeout extends RuntimeException {
        public Timeout(String message) {
            super(message);
        }
    }
}
