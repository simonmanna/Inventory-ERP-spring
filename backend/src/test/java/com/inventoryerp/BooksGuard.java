package com.inventoryerp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * PC-0 uniform books check (ADR-S054, {@code docs/audits/costing-gl-trace.md}) for the accounting regression suites:
 * around every test method, in the demo organisation,
 * <ul>
 *   <li>every journal entry the test created balances (base debit = base credit), and</li>
 *   <li>the inventory sub-ledger / GL tie-out difference is the same after the test as before it — whatever the
 *       test did to stock, it did to the GL too.</li>
 * </ul>
 * Classification stays asserted by each suite's own GL expectations. Entries are recognised by id (not by clock), so
 * JVM / database clock skew cannot hide one.
 */
public class BooksGuard implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(BooksGuard.class);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    private record Before(String[] entries, BigDecimal tieOut) {}

    @Override
    public void beforeEach(ExtensionContext context) {
        ApiTestSupport t = (ApiTestSupport) context.getRequiredTestInstance();
        context.getStore(NS).put("before", new Before(entries(t), tieOut(t)));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (context.getExecutionException().isPresent()) {
            return; // the test already failed; its own message matters more
        }
        ApiTestSupport t = (ApiTestSupport) context.getRequiredTestInstance();
        Before b = context.getStore(NS).get("before", Before.class);
        List<String> unbalanced = t.jdbc.sql("SELECT e.\"entryNumber\" FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                        + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? AND NOT (e.id = ANY(?::text[]))"
                        + " GROUP BY e.id, e.\"entryNumber\" HAVING sum(l.\"baseDebit\") <> sum(l.\"baseCredit\")")
                .params(t.demoOrg, b.entries()).query(String.class).list();
        assertThat(unbalanced).as("BooksGuard: unbalanced journal entries created by the test").isEmpty();
        assertThat(tieOut(t).subtract(b.tieOut())).as("BooksGuard: the test changed the inventory / GL tie-out")
                .isBetween(TOLERANCE.negate(), TOLERANCE);
    }

    private static String[] entries(ApiTestSupport t) {
        return t.jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(t.demoOrg)
                .query(String.class).list().toArray(String[]::new);
    }

    private static BigDecimal tieOut(ApiTestSupport t) {
        return new BigDecimal(t.ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.05", null)
                .get("valuationVsLedger").asString());
    }
}
