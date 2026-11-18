package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reversal of a costing-engine journal entry (review 2026-11-17, ACR-6).
 *
 * <p>The engines used to reverse at {@code now()}. That split one period's cost over two periods: an allocation, a
 * settlement or a material-ledger run reversed after its period ended left the period carrying the cost while the
 * reversal landed in the next one, and a re-run of the same period then computed its pools from the wrong base. It also
 * bypassed the costing lock (CM-10): the mirrored entry's source is {@code reversal}, which the posting guard does not
 * check, so a reversal could post into a locked costing period.
 *
 * <p>Here the date follows owner decision D-B6, as {@link PostingService#reverse} does when it is given no date: the
 * original's own date while its fiscal period is open, so the period nets to zero, else today. A natural date inside a
 * locked costing period moves to today as well (the lock means "closed for costing" exactly as a closed fiscal period
 * does), and today being locked refuses the reversal with the costing guard's verbatim message.
 */
@Component
public class CostingReversals {

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final CostingDimensionGuard guard;

    public CostingReversals(JdbcClient jdbc, PostingService posting, CostingDimensionGuard guard) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.guard = guard;
    }

    /** Reverse a posted costing entry; returns the mirrored entry (as {@link PostingService#reverse}). */
    @Transactional
    public Map<String, Object> reverse(String journalEntryId, String description) {
        Instant original = jdbc.sql("SELECT \"postingDate\" FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ?")
                .params(journalEntryId, TenantContext.organizationId()).query(Timestamp.class).optional()
                .map(Timestamp::toInstant).orElse(null);
        if (original == null) {
            // the posting engine refuses with its own verbatim message
            return posting.reverse(journalEntryId, null, description);
        }
        Instant natural = posting.reversalDate(original);
        if (guard.lockedPeriod(natural) == null) {
            guard.assertCostingPeriodOpen(natural);
            // no explicit date: the engine applies D-B6 itself, including its settled-VAT fallback to today
            return posting.reverse(journalEntryId, null, description);
        }
        Instant today = Instant.now();
        guard.assertCostingPeriodOpen(today);
        return posting.reverse(journalEntryId, today, description);
    }

    /**
     * Reverse at a stated date, for a reversal that is one leg of a business event dated now: a production order
     * reversed (its stock legs post now), a landed cost reversed with its consumed-since reclass, a project issue
     * returned to stock. The date stays with the event; the costing lock still applies.
     */
    @Transactional
    public Map<String, Object> reverseAt(String journalEntryId, Instant date, String description) {
        guard.assertCostingPeriodOpen(date);
        return posting.reverse(journalEntryId, date, description);
    }

    /** {@link #reverse} returning only the mirrored entry's id (null when {@code journalEntryId} is null). */
    @Transactional
    public String reverseId(String journalEntryId, String description) {
        if (journalEntryId == null) {
            return null;
        }
        Map<String, Object> rev = reverse(journalEntryId, description);
        return rev == null ? null : (String) rev.get("id");
    }
}
