package com.inventoryerp.accounting.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The contract every module uses to post to the ledger (port of {@code posting.types.ts}). Modules never
 * create JournalEntry rows directly; they build a request and call {@link PostingService#post}.
 *
 * @param journalCode journal code, e.g. SALES, CASH, BANK, GEN
 * @param postingType "primary" (default), "reversal", "partial_refund", ...
 * @param postingKey  GL-layer idempotency token (unique per organization among posted entries)
 */
public record PostingRequest(String journalCode, Instant date, String description, String currencyId,
        Object exchangeRate, String sourceType, String sourceId, String postingType, String postingKey,
        String branchId, String costCenterId, Map<String, Object> dimensions, List<Line> lines) {

    /**
     * One posting line; exactly one of debit/credit must be positive. {@code currencyId} / {@code foreignAmount}
     * optionally state the line's own transaction currency and amount when it differs from the entry's (a base-currency
     * settlement relieving a foreign open item keeps that item's currency, so FX revaluation sees it settled); the
     * debit / credit stays in the entry's currency and the stored rate is base ÷ foreign.
     */
    public record Line(String accountId, Object debit, Object credit, String partnerId, String description,
            String branchId, String costCenterId, Map<String, Object> dimensions, String currencyId,
            Object foreignAmount) {

        public Line(String accountId, Object debit, Object credit, String partnerId, String description,
                String branchId, String costCenterId, Map<String, Object> dimensions) {
            this(accountId, debit, credit, partnerId, description, branchId, costCenterId, dimensions, null, null);
        }

        /** This line carried in {@code currency} for {@code amount} (see the record comment). */
        public Line inCurrency(String currency, Object amount) {
            return new Line(accountId, debit, credit, partnerId, description, branchId, costCenterId, dimensions,
                    currency, amount);
        }

        public static Line debit(String accountId, Object amount, String description) {
            return new Line(accountId, amount, null, null, description, null, null, null);
        }

        public static Line credit(String accountId, Object amount, String description) {
            return new Line(accountId, null, amount, null, description, null, null, null);
        }
    }

    /** Minimal request: journal, date, description, source, key and lines. */
    public static PostingRequest of(String journalCode, Instant date, String description, String sourceType,
            String sourceId, String postingKey, List<Line> lines) {
        return new PostingRequest(journalCode, date, description, null, null, sourceType, sourceId, null, postingKey,
                null, null, null, lines);
    }
}
