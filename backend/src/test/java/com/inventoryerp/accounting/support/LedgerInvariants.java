package com.inventoryerp.accounting.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Reusable accounting invariants for ACC-0 and every later ACC phase (docs/accounting/ACCOUNTING-TEST-STRATEGY.md).
 * Each method asserts one control; the scope is an organization, optionally narrowed to the entries / partners /
 * source documents a test created so the shared DEMO org's history cannot mask or fake a result.
 *
 * <p>Two levels are always required in a test: these invariants prove double-entry integrity and reconciliation; the
 * test's own expectations prove the accounting treatment (accounts, amounts, currency, rate, date, dimensions).
 */
public final class LedgerInvariants {

    /** Base-currency rounding tolerance per line (6-dp ledger, 2-dp currencies). */
    public static final BigDecimal LINE_TOLERANCE = new BigDecimal("0.01");

    private LedgerInvariants() {}

    private static String[] arr(List<String> ids) {
        return ids.toArray(String[]::new);
    }

    // ------------------------------------------------------------------ double entry

    /** Journal balance: every posted/reversed entry in scope has Σ baseDebit = Σ baseCredit (mandatory invariant). */
    public static void entriesBalanced(JdbcClient jdbc, String org, List<String> entryIds) {
        String scope = entryIds == null ? "" : " AND e.id = ANY(?::text[])";
        var q = jdbc.sql("SELECT e.\"entryNumber\" || ' ' || sum(l.\"baseDebit\") || '<>' || sum(l.\"baseCredit\") FROM"
                + " \"JournalEntry\" e JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ?"
                + " AND e.status IN ('posted', 'reversed')" + scope + " GROUP BY e.id, e.\"entryNumber\""
                + " HAVING sum(l.\"baseDebit\") <> sum(l.\"baseCredit\")").param(org);
        if (entryIds != null) {
            q = q.param(arr(entryIds));
        }
        assertThat(q.query(String.class).list()).as("unbalanced entries (base currency)").isEmpty();
    }

    /**
     * Transaction-currency balance, only where it is meaningful: entries whose lines all carry ONE currency must balance
     * in that currency too. Mixed-currency entries are skipped (functional-currency balance is authoritative there).
     */
    public static void singleCurrencyEntriesBalanced(JdbcClient jdbc, String org, List<String> entryIds) {
        String scope = entryIds == null ? "" : " AND e.id = ANY(?::text[])";
        var q = jdbc.sql("SELECT e.\"entryNumber\" FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? AND e.status IN ('posted', 'reversed')"
                + scope + " GROUP BY e.id, e.\"entryNumber\" HAVING count(DISTINCT coalesce(l.\"currencyId\", '-')) = 1"
                + " AND sum(l.debit) <> sum(l.credit)").param(org);
        if (entryIds != null) {
            q = q.param(arr(entryIds));
        }
        assertThat(q.query(String.class).list()).as("single-currency entries unbalanced in transaction currency")
                .isEmpty();
    }

    /**
     * FX line validity: a line in a currency other than {@code base} must sit on an entry with a rate snapshot, and its
     * base amount must equal transaction amount × line rate within {@link #LINE_TOLERANCE}.
     */
    public static void fxLinesValid(JdbcClient jdbc, String org, String base, List<String> entryIds) {
        String scope = entryIds == null ? "" : " AND e.id = ANY(?::text[])";
        var missing = jdbc.sql("SELECT DISTINCT e.\"entryNumber\" FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? AND l.\"currencyId\" IS NOT NULL AND"
                + " l.\"currencyId\" <> ? AND NOT EXISTS (SELECT 1 FROM \"JournalEntryFxRate\" r WHERE"
                + " r.\"journalEntryId\" = e.id)" + scope).params(org, base);
        var drift = jdbc.sql("SELECT e.\"entryNumber\" || ' line ' || l.\"lineNumber\" FROM \"JournalEntry\" e JOIN"
                + " \"JournalLine\" l ON l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? AND"
                + " l.\"currencyId\" IS NOT NULL AND l.\"currencyId\" <> ? AND (abs(l.debit * l.\"exchangeRate\" -"
                + " l.\"baseDebit\") > ? OR abs(l.credit * l.\"exchangeRate\" - l.\"baseCredit\") > ?)" + scope)
                .params(org, base, LINE_TOLERANCE, LINE_TOLERANCE);
        if (entryIds != null) {
            missing = missing.param(arr(entryIds));
            drift = drift.param(arr(entryIds));
        }
        assertThat(missing.query(String.class).list()).as("foreign-currency entries without a rate snapshot").isEmpty();
        assertThat(drift.query(String.class).list()).as("foreign lines whose base ≠ amount × rate").isEmpty();
    }

    /** Trial balance report: total debits = total credits. */
    public static void trialBalanceBalanced(JsonNode tb) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        JsonNode rows = tb.has("accounts") ? tb.get("accounts") : tb.has("rows") ? tb.get("rows") : tb.get("data");
        if (tb.has("totals") && tb.get("totals").has("debit")) {
            dr = new BigDecimal(tb.get("totals").get("debit").asString());
            cr = new BigDecimal(tb.get("totals").get("credit").asString());
        } else {
            for (JsonNode r : rows) {
                dr = dr.add(new BigDecimal(r.get("debit").asString()));
                cr = cr.add(new BigDecimal(r.get("credit").asString()));
            }
        }
        assertThat(dr).as("trial balance Σdr vs Σcr").isEqualByComparingTo(cr);
    }

    /** Ledger trial balance from {@code AccountingFixtures#tbAsOf}: Σ net = 0. */
    public static void trialBalanceBalanced(Map<String, BigDecimal> tb) {
        assertThat(tb.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).as("Σ trial balance net")
                .isEqualByComparingTo("0");
    }

    /** Balance sheet report: assets = liabilities + equity (+ current-year earnings). */
    public static void balanceSheetBalanced(JsonNode bs) {
        assertThat(new BigDecimal(bs.get("totalAssets").asString())).as("assets vs liabilities + equity + CYE")
                .isEqualByComparingTo(new BigDecimal(bs.get("totalLiabilitiesAndEquity").asString()));
    }

    // ------------------------------------------------------------------ sub-ledger ↔ GL

    /**
     * AR (or AP) control for one partner: the partner's GL balance on the control account equals open documents
     * (residuals of posted invoices/bills) minus unapplied cash (payment amount not allocated) minus unapplied credits
     * (credit / debit note residuals). Advances held on a separate deposit account are not part of the control balance.
     *
     * @param receivable true for AR (debit-normal), false for AP (credit-normal)
     */
    public static void partnerControlReconciles(JdbcClient jdbc, String org, String partnerId, boolean receivable) {
        String key = receivable ? "accounts_receivable" : "accounts_payable";
        BigDecimal gl = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" ="
                + " l.\"organizationId\" AND m.key = ? AND m.\"deletedAt\" IS NULL WHERE l.\"organizationId\" = ? AND"
                + " l.\"partnerId\" = ?").params(key, org, partnerId).query(BigDecimal.class).single();
        if (!receivable) {
            gl = gl.negate();
        }
        String docs = receivable ? "'sales_invoice'" : "'vendor_bill'";
        String credits = receivable ? "'credit_note'" : "'debit_note'";
        BigDecimal open = jdbc.sql("SELECT coalesce(sum(\"amountResidual\"), 0) FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\"::text IN (" + docs + ") AND"
                + " status::text IN ('posted', 'paid')").params(org, partnerId).query(BigDecimal.class).single();
        BigDecimal openCredits = jdbc.sql("SELECT coalesce(sum(\"amountResidual\"), 0) FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\"::text IN (" + credits + ") AND"
                + " status::text IN ('posted', 'paid')").params(org, partnerId).query(BigDecimal.class).single();
        BigDecimal unapplied = jdbc.sql("SELECT coalesce(sum(p.amount - coalesce((SELECT sum(a.amount) FROM"
                + " \"PaymentAllocation\" a WHERE a.\"paymentId\" = p.id), 0)), 0) FROM \"Payment\" p WHERE"
                + " p.\"organizationId\" = ? AND p.\"partnerId\" = ? AND p.status = 'posted' AND p.direction::text = ?")
                .params(org, partnerId, receivable ? "inbound" : "outbound").query(BigDecimal.class).single();
        assertThat(gl).as((receivable ? "AR" : "AP") + " control GL vs open items for partner " + partnerId
                        + " (open " + open + ", unapplied cash " + unapplied + ", open credits " + openCredits + ")")
                .isEqualByComparingTo(open.subtract(unapplied).subtract(openCredits));
    }

    /** Inventory control: valuation report vs inventory GL, as {@code /reports/inventory/gl-tieout} reports it. */
    public static void inventoryTiesOut(JsonNode tieOut, BigDecimal tolerance) {
        assertThat(new BigDecimal(tieOut.get("valuationVsLedger").asString()).abs()).as("inventory valuation vs GL")
                .isLessThanOrEqualTo(tolerance);
    }

    /**
     * Fixed-asset control for a set of assets: register (cost − accumulated depreciation of active assets) equals the GL
     * net of the fixed-asset and accumulated-depreciation accounts over those assets' source documents.
     */
    public static void fixedAssetsReconcile(BigDecimal registerNetBookValue, BigDecimal glNetBookValue) {
        assertThat(glNetBookValue).as("fixed-asset GL (cost − acc. depreciation) vs asset register NBV")
                .isEqualByComparingTo(registerNetBookValue);
    }

    /** Tax control: tax GL movement over a set of documents equals the tax the documents carry. */
    public static void taxReconciles(BigDecimal documentTax, BigDecimal glTax) {
        assertThat(glTax).as("tax GL vs document tax register").isEqualByComparingTo(documentTax);
    }

    // ------------------------------------------------------------------ completeness and immutability

    /**
     * Posting completeness for one source document: exactly {@code expectedPrimary} posted primary entries; when the
     * document is reversed, every reversed entry has a posted compensating entry linked through {@code reversalOfId};
     * no entry for the source is left without lines.
     */
    public static void postingComplete(JdbcClient jdbc, String org, String sourceType, String sourceId,
            int expectedPrimary, boolean reversed) {
        long primaries = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversalOfId\" IS NULL AND status IN ('posted',"
                + " 'reversed')").params(org, sourceType, sourceId).query(Long.class).single();
        assertThat(primaries).as(sourceType + " " + sourceId + " primary entries").isEqualTo(expectedPrimary);
        long empty = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" e WHERE e.\"organizationId\" = ? AND"
                + " e.\"sourceType\" = ? AND e.\"sourceId\" = ? AND NOT EXISTS (SELECT 1 FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = e.id)").params(org, sourceType, sourceId).query(Long.class).single();
        assertThat(empty).as("entries without lines").isZero();
        long unlinked = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" e WHERE e.\"organizationId\" = ? AND"
                + " e.\"sourceType\" = ? AND e.\"sourceId\" = ? AND e.status = 'reversed' AND NOT EXISTS (SELECT 1 FROM"
                + " \"JournalEntry\" r WHERE r.\"reversalOfId\" = e.id AND r.status = 'posted')")
                .params(org, sourceType, sourceId).query(Long.class).single();
        assertThat(unlinked).as("reversed entries without a linked compensating entry").isZero();
        if (reversed) {
            long stillPosted = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                    + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversalOfId\" IS NULL AND status = 'posted'")
                    .params(org, sourceType, sourceId).query(Long.class).single();
            assertThat(stillPosted).as("primary entries still posted on a reversed document").isZero();
        }
    }

    /** Immutability: the database refuses to edit or delete a posted entry or its lines (attempted, rolled back). */
    public static void postedEntryImmutable(JdbcClient jdbc, PlatformTransactionManager txm, String entryId) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("UPDATE \"JournalLine\" SET debit = debit + 1,"
                + " \"baseDebit\" = \"baseDebit\" + 1 WHERE \"journalEntryId\" = ?").param(entryId).update()))
                .as("editing a posted line").isNotNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("UPDATE \"JournalEntry\" SET description ="
                + " 'tampered' WHERE id = ?").param(entryId).update())).as("editing a posted entry").isNotNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("DELETE FROM \"JournalEntry\" WHERE id = ?")
                .param(entryId).update())).as("deleting a posted entry").isNotNull();
    }

    /** Snapshot of an entry's lines for a before/after immutability compare. */
    public static List<Map<String, Object>> snapshot(JdbcClient jdbc, String entryId) {
        return jdbc.sql("SELECT \"accountId\", debit, credit, \"baseDebit\", \"baseCredit\", \"currencyId\" FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ? ORDER BY \"lineNumber\", id").param(entryId).query()
                .listOfRows();
    }
}
