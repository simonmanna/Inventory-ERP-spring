package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reconciliation checks for order-to-cash integration tests (docs/spec/sales-postings.md §7). Scoped to one
 * organization and, where noted, one partner, so concurrent test data of other suites does not interfere.
 */
public final class SalesLedgerAssertions {

    private final JdbcClient jdbc;
    private final String org;

    public SalesLedgerAssertions(JdbcClient jdbc, String org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    /** Every posted / reversed journal entry of the source document balances (debit = credit, both > 0). */
    public void entriesBalanced(String sourceType, String sourceId) {
        List<String> ids = entries(sourceType, sourceId);
        assertThat(ids).as("journal entries of %s %s", sourceType, sourceId).isNotEmpty();
        for (String id : ids) {
            Map<String, Object> s = jdbc.sql("SELECT COALESCE(SUM(debit), 0) AS d, COALESCE(SUM(credit), 0) AS c FROM"
                    + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(id).query().singleRow();
            assertThat((BigDecimal) s.get("d")).as("debit of entry %s", id).isPositive();
            assertThat((BigDecimal) s.get("d")).as("entry %s balanced", id).isEqualByComparingTo((BigDecimal) s.get("c"));
        }
    }

    /** Trial balance of the organization's posted entries sums to zero. */
    public void trialBalanceZero() {
        BigDecimal diff = jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed')").param(org).query(BigDecimal.class).single();
        assertThat(diff).as("trial balance").isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Posted balance (debit − credit) of the account mapped to {@code key}, optionally for one partner. */
    public BigDecimal balance(String key, String partnerId) {
        String account = mapped(key);
        return jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                + " AND l.\"accountId\" = ? AND (?::text IS NULL OR l.\"partnerId\" = ?)")
                .params(org, account, partnerId, partnerId).query(BigDecimal.class).single();
    }

    /** AR subledger (open residual of the partner's posted sales invoices) equals the partner's AR control balance. */
    public void receivableTiesOut(String partnerId) {
        BigDecimal open = jdbc.sql("SELECT COALESCE(SUM(\"amountResidual\"), 0) FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\"::text IN ('sales_invoice',"
                + " 'credit_note') AND status::text NOT IN ('draft', 'cancelled', 'rejected')").params(org, partnerId)
                .query(BigDecimal.class).single();
        assertThat(balance("accounts_receivable", partnerId)).as("AR control of partner %s", partnerId)
                .isEqualByComparingTo(open);
    }

    /** Quantity and value on hand of a product at a location. */
    public Map<String, Object> stock(String productId, String locationId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) AS qty, COALESCE(SUM(quantity * \"runningAverageCost\"), 0)"
                + " AS value FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?")
                .params(org, productId, locationId).query().singleRow();
    }

    public List<String> entries(String sourceType, String sourceId) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND"
                + " \"sourceId\" = ? ORDER BY \"createdAt\"").params(org, sourceType, sourceId).query(String.class).list();
    }

    public String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(org, key).query(String.class).single();
    }
}
