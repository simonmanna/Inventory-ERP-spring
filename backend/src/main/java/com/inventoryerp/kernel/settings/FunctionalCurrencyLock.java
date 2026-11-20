package com.inventoryerp.kernel.settings;

import com.inventoryerp.shared.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The functional currency is fixed once the organisation has posted entries (R-8, ADR-S076, plan P-1; a deviation from
 * Nest, which changed it at any time): changing it would silently re-denominate every functional amount in the books.
 */
public final class FunctionalCurrencyLock {

    private FunctionalCurrencyLock() {}

    /** Refuse a change of {@code Organization.currencyCode} to {@code newCode} once journal entries are posted. */
    public static void assertChangeable(JdbcClient jdbc, String organizationId, Object newCode) {
        if (organizationId == null) {
            return;
        }
        if (newCode == null || String.valueOf(newCode).isBlank()) {
            // FX-2 A: the functional currency is mandatory; it can be changed (before posting) but never cleared.
            throw ApiException.badRequest("The functional currency is required");
        }
        String current = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(organizationId)
                .query(String.class).optional().orElse(null);
        String next = String.valueOf(newCode).trim();
        if (current == null || current.equalsIgnoreCase(next)) {
            return;
        }
        boolean posted = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " status::text IN ('posted', 'reversed'))").param(organizationId).query(Boolean.class).single();
        if (posted) {
            throw ApiException.badRequest("The functional currency cannot change once entries are posted");
        }
    }
}
