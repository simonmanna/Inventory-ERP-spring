package com.inventoryerp.accounting.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import com.inventoryerp.accounting.application.LedgerIntegrityService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finance readiness audit B6: after every accounting integration test class, the ledger invariants run over each
 * journal entry the class wrote, in every organization, so a test that forgets to call {@link LedgerInvariants} still
 * cannot leave a broken ledger behind. Registered on {@code ApiTestSupport}; it acts on classes under
 * {@code com.inventoryerp.accounting} only. A class that corrupts the ledger on purpose (to prove a detector finds it)
 * opts out with {@link Skip}.
 *
 * <p>Checked for posted and reversed entries created since the class started (database clock):
 * <ul>
 *   <li>balanced in base currency, and in the transaction currency when all lines carry one currency;</li>
 *   <li>at least two lines, every line in the entry's organization and on an account of that organization;</li>
 *   <li>a reversal points at a reversed original that points back at it, and mirrors it account by account.</li>
 * </ul>
 */
public class LedgerInvariantsCheck implements BeforeAllCallback, AfterAllCallback, TestWatcher {

    /** Opt a class out of the automatic check; give the reason. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Skip {
        String value();
    }

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(LedgerInvariantsCheck.class);

    private static boolean applies(ExtensionContext context) {
        Class<?> type = context.getRequiredTestClass();
        return type.getPackageName().startsWith("com.inventoryerp.accounting") && !type.isAnnotationPresent(Skip.class);
    }

    private static JdbcClient jdbc(ExtensionContext context) {
        return SpringExtension.getApplicationContext(context).getBean(JdbcClient.class);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (applies(context)) {
            context.getStore(NS).put("since", jdbc(context).sql("SELECT clock_timestamp()::timestamp(3)")
                    .query(Timestamp.class).single());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Timestamp since = context.getStore(NS).get("since", Timestamp.class);
        if (since == null || !applies(context)) {
            return;
        }
        try {
            check(jdbc(context), since);
            registersTieOut(context, since);
        } catch (AssertionError e) {
            echo("[ledger-invariants] " + context.getRequiredTestClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    /** To stderr, and appended to {@code $TEST_FAILURE_LOG} when set (fastTest does not show test output). */
    private static void echo(String message) {
        System.err.println(message);
        String file = System.getenv("TEST_FAILURE_LOG");
        if (file != null && !file.isBlank()) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(file), message + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ignored) {
                // best effort
            }
        }
    }

    /** Gradle prints only where an assertion failed; echo what failed, for local runs and CI logs alike. */
    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        if (context.getRequiredTestClass().getPackageName().startsWith("com.inventoryerp.accounting")) {
            echo("[test-failed] " + context.getRequiredTestClass().getSimpleName() + " " + context.getDisplayName()
                    + ": " + cause);
        }
    }

    /**
     * I10 and I12 (finance audit): in each organization the class created, the fixed-asset register ties to the GL and
     * each closed cash session's journals moved its drawer by its counted movement ({@link LedgerIntegrityService}).
     * Organizations created by the class only: the shared DEMO history is not the class's to answer for.
     */
    private static void registersTieOut(ExtensionContext context, Timestamp since) {
        var spring = SpringExtension.getApplicationContext(context);
        LedgerIntegrityService integrity = spring.getBean(LedgerIntegrityService.class);
        PlatformTransactionManager txm = spring.getBean(PlatformTransactionManager.class);
        List<String> orgs = jdbc(context).sql("SELECT o.id FROM \"Organization\" o WHERE o.\"createdAt\" >= ? AND"
                + " EXISTS (SELECT 1 FROM \"JournalEntry\" e WHERE e.\"organizationId\" = o.id)").param(since)
                .query(String.class).list();
        SoftAssertions soft = new SoftAssertions();
        for (String org : orgs) {
            TenantContext.run(TenantStore.organization(org), () -> new TransactionTemplate(txm).executeWithoutResult(s -> {
                // a register is tied only where the class used it: a test may post to an asset or drawer account as
                // a plain account in an organization with no asset or shift behind it
                List<String> used = new java.util.ArrayList<>();
                if (exists(context, "\"Asset\"", org)) {
                    used.add(LedgerIntegrityService.FIXED_ASSETS);
                }
                if (exists(context, "\"CashSession\"", org)) {
                    used.add(LedgerIntegrityService.CASH_DRAWERS);
                }
                for (LedgerIntegrityService.IntegrityCheck c : used.isEmpty() ? List.<LedgerIntegrityService.IntegrityCheck>of()
                        : integrity.run().checks()) {
                    if (used.contains(c.key())) {
                        soft.assertThat(c.ok() || c.skipped()).as(org + " " + c.key() + ": " + c.rows()).isTrue();
                    }
                }
            }));
        }
        soft.assertAll();
    }

    private static boolean exists(ExtensionContext context, String table, String org) {
        return jdbc(context).sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE \"organizationId\" = ?)").param(org)
                .query(Boolean.class).single();
    }

    /** The checks themselves, over entries created at or after {@code since}. */
    public static void check(JdbcClient jdbc, Timestamp since) {
        String scope = " FROM \"JournalEntry\" e WHERE e.\"createdAt\" >= ? AND e.status::text IN ('posted', 'reversed')";
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(list(jdbc, "SELECT e.\"entryNumber\" || ': ' || sum(l.\"baseDebit\") || ' <> ' || sum(l.\"baseCredit\")"
                + " FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id WHERE e.\"createdAt\" >= ?"
                + " AND e.status::text IN ('posted', 'reversed') GROUP BY e.id, e.\"entryNumber\""
                + " HAVING sum(l.\"baseDebit\") <> sum(l.\"baseCredit\")", since)).as("unbalanced in base currency")
                .isEmpty();
        soft.assertThat(list(jdbc, "SELECT e.\"entryNumber\" FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id WHERE e.\"createdAt\" >= ? AND e.status::text IN ('posted', 'reversed')"
                + " GROUP BY e.id, e.\"entryNumber\" HAVING count(DISTINCT coalesce(l.\"currencyId\", '-')) = 1"
                + " AND sum(l.debit) <> sum(l.credit)", since)).as("single-currency entries unbalanced in that currency")
                .isEmpty();
        soft.assertThat(list(jdbc, "SELECT e.\"entryNumber\"" + scope + " AND (SELECT count(*) FROM \"JournalLine\" l"
                + " WHERE l.\"journalEntryId\" = e.id) < 2", since)).as("posted entries with fewer than two lines")
                .isEmpty();
        soft.assertThat(list(jdbc, "SELECT e.\"entryNumber\" FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE e.\"createdAt\" >= ?"
                + " AND (l.\"organizationId\" <> e.\"organizationId\" OR a.\"organizationId\" <> e.\"organizationId\")",
                since)).as("lines or accounts of another organization").isEmpty();
        soft.assertThat(list(jdbc, "SELECT r.\"entryNumber\" FROM \"JournalEntry\" r JOIN \"JournalEntry\" o ON o.id ="
                + " r.\"reversalOfId\" WHERE r.\"createdAt\" >= ? AND r.status::text IN ('posted', 'reversed')"
                + " AND (o.status::text <> 'reversed' OR o.\"reversedEntryId\" IS DISTINCT FROM r.id"
                + " OR o.\"organizationId\" <> r.\"organizationId\")", since))
                .as("reversals whose original is not reversed by them").isEmpty();
        soft.assertThat(list(jdbc, "SELECT DISTINCT r.\"entryNumber\" FROM \"JournalEntry\" r JOIN LATERAL ("
                + " SELECT l.\"accountId\", sum(l.\"baseDebit\" - l.\"baseCredit\") AS net FROM \"JournalLine\" l"
                + " WHERE l.\"journalEntryId\" IN (r.id, r.\"reversalOfId\") GROUP BY l.\"accountId\") x ON true"
                + " WHERE r.\"createdAt\" >= ? AND r.\"reversalOfId\" IS NOT NULL AND r.status::text IN ('posted',"
                + " 'reversed') AND x.net <> 0", since)).as("reversals that do not mirror their original").isEmpty();
        soft.assertAll();
    }

    private static List<String> list(JdbcClient jdbc, String sql, Timestamp since) {
        return jdbc.sql(sql).param(since).query(String.class).list();
    }
}
