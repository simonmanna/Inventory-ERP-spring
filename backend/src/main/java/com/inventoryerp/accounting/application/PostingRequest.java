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

    /** One posting line; exactly one of debit/credit must be positive. */
    public record Line(String accountId, Object debit, Object credit, String partnerId, String description,
            String branchId, String costCenterId, Map<String, Object> dimensions) {

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
