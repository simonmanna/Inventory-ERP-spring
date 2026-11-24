package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * General-ledger integrity checks (finance readiness audit 2026-11-23, step A0; {@code GET /reports/accounting/integrity}
 * and the nightly {@link LedgerIntegrityMonitor}). Each check states one invariant of the ledger and lists the rows that
 * break it; nothing here writes or corrects anything. The report reads like the inventory integrity report.
 *
 * <p>Checks, numbered as in {@code docs/audits/2026-11-23-finance-accounting-readiness.md}:
 * <ul>
 * <li>I1 {@value #ENTRY_BALANCED}: a posted entry balances exactly in base, has two or more lines, no all-zero line and
 * no line breaking the amount rules of V499 (never validated against older rows);
 * <li>I2 {@value #ENTRY_CURRENCY_BALANCED}: a foreign-currency entry balances in its own currency;
 * <li>I3 {@value #FX_LINES}: a foreign line with an amount has base amount = amount × rate (base-only FX adjustment
 *     lines are exempt);
 * <li>I4 {@value #TRIAL_BALANCE} and {@value #ACCOUNT_CLASSIFICATION}: the trial balance balances, and every posted
 * account belongs to the organisation and has a report section (so assets = liabilities + equity in the statements);
 * <li>I5 {@value #PERIOD_COVERAGE}: periods run from 00:00 to 23:59:59.999 org-local and do not overlap, and every posted
 * entry lies in one;
 * <li>I6 {@value #CLOSED_PERIODS}: a closed period's profit and loss nets to zero over its whole days, and its closing
 * entry was not reversed while it stayed closed;
 * <li>I7 {@value #REVERSAL_LINKS}: every reversal is the single, exact mirror of its original;
 * <li>I11 {@value #TAX_LOCK}: every VAT settlement's offset entry is posted, and the settled figures are unchanged;
 * <li>I9 {@value #INVENTORY_GL}: inventory valuation ties to the GL (the inventory GL tie-out).
 * </ul>
 * I8 (AR/AP), I10 (fixed assets), I12 (cash drawers) and I13 (posting-key reuse) come with later steps of the plan.
 */
@Service
public class LedgerIntegrityService {

    /** Most rows one check returns; {@code truncated} says when there were more. */
    public static final int ROW_LIMIT = 5000;

    /** Amount tolerance of the transaction-currency, FX-line, closed-period and VAT checks. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /**
     * Extra FX-line slack per unit of foreign amount: a line rate is stored at 8 decimals, so amount × stored rate can
     * differ from the base amount (computed with the full rate) by up to half a unit of the 8th decimal per unit.
     */
    static final BigDecimal RATE_SLACK = new BigDecimal("0.000000005");

    public static final String ENTRY_BALANCED = "entry_balanced";
    public static final String ENTRY_CURRENCY_BALANCED = "entry_currency_balanced";
    public static final String FX_LINES = "fx_lines";
    public static final String TRIAL_BALANCE = "trial_balance";
    public static final String ACCOUNT_CLASSIFICATION = "account_classification";
    public static final String PERIOD_COVERAGE = "period_coverage";
    public static final String CLOSED_PERIODS = "closed_periods";
    public static final String REVERSAL_LINKS = "reversal_links";
    public static final String TAX_LOCK = "tax_lock";
    public static final String INVENTORY_GL = "inventory_gl";

    /** Every check, in report order. */
    public static final List<String> CHECKS = List.of(ENTRY_BALANCED, ENTRY_CURRENCY_BALANCED, FX_LINES, TRIAL_BALANCE,
            ACCOUNT_CLASSIFICATION, PERIOD_COVERAGE, CLOSED_PERIODS, REVERSAL_LINKS, TAX_LOCK, INVENTORY_GL);

    /** Report sections of profit-and-loss accounts (the ones a period close carries to retained earnings). */
    static final String PNL_SECTIONS = "('revenue', 'contra_revenue', 'cogs', 'operating_expense', 'other_income',"
            + " 'other_expense')";

    private static final String POSTED = "e.status::text IN ('posted', 'reversed')";
    private static final String SECTION = "coalesce(a.\"reportSection\"::text, cat.\"reportSection\"::text)";

    private static final Logger log = LoggerFactory.getLogger(LedgerIntegrityService.class);

    /**
     * One check: {@code ok} when nothing breaks the invariant. {@code failures} counts what breaks it (entries, lines,
     * periods, accounts; for an uncovered day, its entries); at most {@value #ROW_LIMIT} rows come back and
     * {@code truncated} says when there were more. {@code tolerance} null = exact. A skipped check is ok and says why in
     * {@code note}; a note on a run check is information only.
     */
    public record IntegrityCheck(String key, boolean ok, boolean skipped, String invariant, BigDecimal tolerance,
            long failures, boolean truncated, List<Map<String, Object>> rows, String note) {}

    /** The whole report: {@code ok} when every check is. */
    public record IntegrityReport(Instant checkedAt, boolean ok, int rowLimit, List<IntegrityCheck> checks) {

        /** The failing checks. */
        public List<IntegrityCheck> failing() {
            return checks.stream().filter(c -> !c.ok()).toList();
        }
    }

    private record Found(long failures, boolean truncated, List<Map<String, Object>> rows) {}

    private final JdbcClient jdbc;
    private final InventoryValuationService valuation;
    private final SettingResolverService settings;
    private final TransactionTemplate readOnly;

    public LedgerIntegrityService(JdbcClient jdbc, InventoryValuationService valuation,
            SettingResolverService settings, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.valuation = valuation;
        this.settings = settings;
        this.readOnly = new TransactionTemplate(txm);
        this.readOnly.setReadOnly(true);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * Run every check for the current organisation. The ledger checks share one read-only transaction; the inventory
     * tie-out runs in its own, so a failure to compute it cannot spoil the others.
     */
    public IntegrityReport run() {
        List<IntegrityCheck> checks = new ArrayList<>(readOnly.execute(s -> ledgerChecks()));
        checks.add(inventoryGl());
        return new IntegrityReport(Instant.now(), checks.stream().allMatch(IntegrityCheck::ok), ROW_LIMIT,
                List.copyOf(checks));
    }

    private List<IntegrityCheck> ledgerChecks() {
        String base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse(null);
        ZoneId zone = AccountingDay.zone(jdbc, org());
        return List.of(entryBalanced(), entryCurrencyBalanced(base), fxLines(base), trialBalance(),
                accountClassification(), periodCoverage(zone), closedPeriods(zone), reversalLinks(), taxLock(zone));
    }

    // ------------------------------------------------------------------ I1–I4: entries and the trial balance

    private IntegrityCheck entryBalanced() {
        String zero = "l.debit = 0 AND l.credit = 0 AND l.\"baseDebit\" = 0 AND l.\"baseCredit\" = 0";
        String invalid = "l.debit < 0 OR l.credit < 0 OR l.\"baseDebit\" < 0 OR l.\"baseCredit\" < 0"
                + " OR (l.debit > 0 AND l.credit > 0) OR (l.\"baseDebit\" > 0 AND l.\"baseCredit\" > 0)";
        Found f = find("""
                SELECT e.id AS "journalEntryId", e."entryNumber", e."sourceType", e.status::text AS status,
                       e."postingDate", count(l.id) AS lines, coalesce(sum(l."baseDebit"), 0) AS "baseDebit",
                       coalesce(sum(l."baseCredit"), 0) AS "baseCredit", count(l.id) FILTER (WHERE %1$s) AS "zeroLines",
                       count(l.id) FILTER (WHERE %2$s) AS "invalidLines", count(*) OVER () AS rows_total
                  FROM "JournalEntry" e
                  LEFT JOIN "JournalLine" l ON l."journalEntryId" = e.id AND l."organizationId" = e."organizationId"
                 WHERE e."organizationId" = :org AND %3$s
                 GROUP BY e.id
                HAVING coalesce(sum(l."baseDebit"), 0) <> coalesce(sum(l."baseCredit"), 0) OR count(l.id) < 2
                    OR count(l.id) FILTER (WHERE %1$s) > 0 OR count(l.id) FILTER (WHERE %2$s) > 0
                 ORDER BY e."postingDate", e."entryNumber\"""".formatted(zero, invalid, POSTED), params(null));
        return check(ENTRY_BALANCED, "posted entry: Σ baseDebit = Σ baseCredit, at least 2 lines, no all-zero line,"
                + " no negative amount, no line with both a debit and a credit", null, f, null);
    }

    private IntegrityCheck entryCurrencyBalanced(String base) {
        Map<String, Object> p = params(TOLERANCE);
        p.put("base", base == null ? "" : base);
        String own = "upper(l.\"currencyId\") = upper(e.\"currencyId\")";
        Found f = find("""
                SELECT e.id AS "journalEntryId", e."entryNumber", e."sourceType", e."postingDate",
                       e."currencyId" AS currency, coalesce(sum(l.debit) FILTER (WHERE %1$s), 0) AS debit,
                       coalesce(sum(l.credit) FILTER (WHERE %1$s), 0) AS credit, count(*) OVER () AS rows_total
                  FROM "JournalEntry" e
                  JOIN "JournalLine" l ON l."journalEntryId" = e.id AND l."organizationId" = e."organizationId"
                 WHERE e."organizationId" = :org AND %2$s AND coalesce(e."currencyId", '') <> ''
                   AND upper(e."currencyId") <> upper(:base)
                 GROUP BY e.id
                HAVING count(*) FILTER (WHERE coalesce(l."currencyId", '') <> '' AND NOT (%1$s)) = 0
                   AND abs(coalesce(sum(l.debit) FILTER (WHERE %1$s), 0)
                           - coalesce(sum(l.credit) FILTER (WHERE %1$s), 0)) > :tol
                 ORDER BY e."postingDate", e."entryNumber\"""".formatted(own, POSTED), p);
        return check(ENTRY_CURRENCY_BALANCED, "entry in a foreign currency (no lines in a third currency): Σ debit ="
                + " Σ credit of its lines in that currency", TOLERANCE, f, null);
    }

    private IntegrityCheck fxLines(String base) {
        String invariant = "foreign-currency line with an amount: |amount × rate − base amount| ≤ tolerance (+ 8-decimal"
                + " rate rounding); base-only FX adjustment lines (amount 0) are exempt";
        if (base == null) {
            return skipped(FX_LINES, invariant, TOLERANCE, "the organisation has no functional currency");
        }
        Map<String, Object> p = params(TOLERANCE);
        p.put("base", base);
        p.put("slack", RATE_SLACK);
        Found drift = find("""
                SELECT e.id AS "journalEntryId", e."entryNumber", e."sourceType", e."postingDate", l."lineNumber",
                       l."currencyId" AS currency, l.debit, l.credit, l."exchangeRate", l."baseDebit", l."baseCredit",
                       count(*) OVER () AS rows_total
                  FROM "JournalEntry" e
                  JOIN "JournalLine" l ON l."journalEntryId" = e.id AND l."organizationId" = e."organizationId"
                 WHERE e."organizationId" = :org AND %s AND coalesce(l."currencyId", '') <> ''
                   AND upper(l."currencyId") <> upper(:base)
                   AND (l.debit <> 0 OR l.credit <> 0)
                   AND (abs(l.debit * l."exchangeRate" - l."baseDebit") > :tol + l.debit * :slack
                     OR abs(l.credit * l."exchangeRate" - l."baseCredit") > :tol + l.credit * :slack)
                 ORDER BY e."postingDate", e."entryNumber", l."lineNumber\"""".formatted(POSTED), p);
        // Information only: approved drafts and reversals never store a rate snapshot (audit P3), so a missing one is
        // not a ledger defect.
        long unsnapshotted = jdbc.sql("""
                SELECT count(*) FROM "JournalEntry" e
                 WHERE e."organizationId" = :org AND %s AND e."postingType" <> 'reversal'
                   AND coalesce(e."currencyId", '') <> '' AND upper(e."currencyId") <> upper(:base)
                   AND NOT EXISTS (SELECT 1 FROM "JournalEntryFxRate" r WHERE r."journalEntryId" = e.id)"""
                .formatted(POSTED)).params(p).query(Long.class).single();
        return check(FX_LINES, invariant, TOLERANCE, drift, unsnapshotted == 0 ? null : unsnapshotted
                + " foreign-currency entr" + (unsnapshotted == 1 ? "y has" : "ies have") + " no rate snapshot"
                + " (approved drafts never store one)");
    }

    private IntegrityCheck trialBalance() {
        Map<String, Object> totals = SqlRows.one(jdbc.sql("""
                SELECT coalesce(sum(l."baseDebit"), 0) AS debit, coalesce(sum(l."baseCredit"), 0) AS credit
                  FROM "JournalLine" l
                  JOIN "JournalEntry" e ON e.id = l."journalEntryId" AND e."organizationId" = l."organizationId"
                 WHERE l."organizationId" = :org AND %s""".formatted(POSTED)).params(params(null)));
        BigDecimal debit = SqlRows.dec(totals, "debit");
        BigDecimal credit = SqlRows.dec(totals, "credit");
        boolean balanced = debit.compareTo(credit) == 0;
        List<Map<String, Object>> rows = balanced ? List.of()
                : List.of(render(row("debit", debit, "credit", credit, "difference", debit.subtract(credit))));
        return check(TRIAL_BALANCE, "Σ baseDebit = Σ baseCredit over every posted line of the organisation", null,
                new Found(balanced ? 0 : 1, false, rows), null);
    }

    private IntegrityCheck accountClassification() {
        Found unclassified = find("""
                SELECT 'unclassified' AS kind, a.id AS "accountId", a.code, a.name, count(l.id) AS lines,
                       sum(l."baseDebit" - l."baseCredit") AS balance, count(*) OVER () AS rows_total
                  FROM "JournalLine" l
                  JOIN "JournalEntry" e ON e.id = l."journalEntryId" AND e."organizationId" = l."organizationId"
                  JOIN "Account" a ON a.id = l."accountId" AND a."organizationId" = l."organizationId"
                  LEFT JOIN "AccountCategory" cat ON cat.id = a."categoryId"
                 WHERE l."organizationId" = :org AND %s AND %s IS NULL
                 GROUP BY a.id, a.code, a.name
                 ORDER BY a.code""".formatted(POSTED, SECTION), params(null));
        Found foreign = find("""
                SELECT 'not_an_account_of_the_organisation' AS kind, l."accountId", count(l.id) AS lines,
                       sum(l."baseDebit" - l."baseCredit") AS balance, count(*) OVER () AS rows_total
                  FROM "JournalLine" l
                  JOIN "JournalEntry" e ON e.id = l."journalEntryId" AND e."organizationId" = l."organizationId"
                 WHERE l."organizationId" = :org AND %s
                   AND NOT EXISTS (SELECT 1 FROM "Account" a WHERE a.id = l."accountId" AND a."organizationId" = :org)
                 GROUP BY l."accountId"
                 ORDER BY l."accountId\"""".formatted(POSTED), params(null));
        return check(ACCOUNT_CLASSIFICATION, "every account with posted lines belongs to the organisation and has a"
                + " report section (its own or its category's), so the statements and the period close include it",
                null, merge(unclassified, foreign), null);
    }

    // ------------------------------------------------------------------ I5–I7: periods and reversals

    private IntegrityCheck periodCoverage(ZoneId zone) {
        String invariant = "live periods run from 00:00 to 23:59:59.999 (org-local) and do not overlap; every posted"
                + " entry lies in a period (a deleted period still counts when closed or locked, as in period control)";
        Found bounds = find("""
                SELECT 'bounds' AS kind, p.id AS "periodId", p.name AS "periodName", p.status::text AS status,
                       p."startDate", p."endDate", count(*) OVER () AS rows_total
                  FROM "FiscalPeriod" p
                 WHERE p."organizationId" = :org AND p."deletedAt" IS NULL
                   AND (p."startDate"::time <> time '00:00:00' OR p."endDate"::time <> time '23:59:59.999')
                 ORDER BY p."startDate\"""", params(null));
        Found overlaps = find("""
                SELECT 'overlap' AS kind, a.id AS "periodId", a.name AS "periodName", b.id AS "otherPeriodId",
                       b.name AS "otherPeriodName", count(*) OVER () AS rows_total
                  FROM "FiscalPeriod" a
                  JOIN "FiscalPeriod" b ON b."organizationId" = a."organizationId" AND a.id < b.id
                       AND a."startDate" <= b."endDate" AND b."startDate" <= a."endDate" AND b."deletedAt" IS NULL
                 WHERE a."organizationId" = :org AND a."deletedAt" IS NULL
                 ORDER BY a."startDate\"""", params(null));
        long periods = jdbc.sql("SELECT count(*) FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND (\"deletedAt\""
                + " IS NULL OR status::text <> 'open')").param(org()).query(Long.class).single();
        if (periods == 0) {
            return check(PERIOD_COVERAGE, invariant, null, merge(bounds, overlaps),
                    "the organisation has no fiscal periods; posting coverage not checked");
        }
        Found uncovered = find("""
                SELECT 'uncovered_day' AS kind, (%s)::date AS "localDate", count(*) AS entries,
                       min(e."entryNumber") AS "firstEntry", max(e."entryNumber") AS "lastEntry",
                       sum(count(*)) OVER () AS failures_total, count(*) OVER () AS rows_total
                  FROM "JournalEntry" e
                 WHERE e."organizationId" = :org AND %s
                   AND NOT EXISTS (SELECT 1 FROM "FiscalPeriod" p WHERE p."organizationId" = :org
                                     AND (p."deletedAt" IS NULL OR p.status::text <> 'open')
                                     AND e."postingDate" BETWEEN %s AND %s)
                 GROUP BY 2
                 ORDER BY 2""".formatted(utcToWall("e.\"postingDate\"", zone), POSTED,
                wallToUtc("p.\"startDate\"", zone), wallToUtc("p.\"endDate\"", zone)), params(null));
        return check(PERIOD_COVERAGE, invariant, null, merge(bounds, overlaps, uncovered), null);
    }

    private IntegrityCheck closedPeriods(ZoneId zone) {
        String periods = """
                WITH per AS (
                  SELECT p.id, p.name, p.status::text AS status, %s AS s, %s AS f
                    FROM "FiscalPeriod" p
                   WHERE p."organizationId" = :org AND p."deletedAt" IS NULL AND p.status::text IN ('closed', 'locked'))
                """.formatted(wallToUtc("date_trunc('day', p.\"startDate\")", zone),
                wallToUtc("(date_trunc('day', p.\"endDate\") + interval '1 day' - interval '1 millisecond')", zone));
        Found pnl = find(periods + """
                SELECT 'pnl_not_zero' AS kind, per.id AS "periodId", per.name AS "periodName", per.status,
                       a.id AS "accountId", a.code, a.name AS "accountName", sum(l."baseDebit" - l."baseCredit") AS net,
                       count(*) OVER () AS rows_total
                  FROM per
                  JOIN "JournalEntry" e ON e."organizationId" = :org AND %s AND e."postingDate" BETWEEN per.s AND per.f
                  JOIN "JournalLine" l ON l."journalEntryId" = e.id AND l."organizationId" = :org
                  JOIN "Account" a ON a.id = l."accountId"
                  LEFT JOIN "AccountCategory" cat ON cat.id = a."categoryId"
                 WHERE %s IN %s
                 GROUP BY per.id, per.name, per.status, per.s, a.id, a.code, a.name
                HAVING abs(sum(l."baseDebit" - l."baseCredit")) > :tol
                 ORDER BY per.s, a.code""".formatted(POSTED, SECTION, PNL_SECTIONS), params(TOLERANCE));
        // Closing entries written before the period id was kept as their source id are not matched (false negative).
        Found reversed = find(periods + """
                SELECT 'closing_entry_reversed' AS kind, per.id AS "periodId", per.name AS "periodName", per.status,
                       count(ce.id) AS "reversedClosingEntries", max(ce."entryNumber") AS "closingEntry",
                       count(*) OVER () AS rows_total
                  FROM per
                  JOIN "JournalEntry" ce ON ce."organizationId" = :org AND ce."sourceType" = 'period_close'
                       AND ce."sourceId" = per.id AND ce.status::text = 'reversed'
                 WHERE NOT EXISTS (SELECT 1 FROM "JournalEntry" c WHERE c."organizationId" = :org
                                     AND c."sourceType" = 'period_close' AND c."sourceId" = per.id
                                     AND c.status::text = 'posted')
                 GROUP BY per.id, per.name, per.status, per.s
                 ORDER BY per.s""", params(null));
        return check(CLOSED_PERIODS, "closed or locked period: each profit-and-loss account nets to 0 over the"
                + " period's whole days (first 00:00 to last 23:59:59.999, org-local), and its closing entry is not"
                + " reversed while the period stays closed", TOLERANCE, merge(pnl, reversed), null);
    }

    private IntegrityCheck reversalLinks() {
        Found unlinked = find("""
                SELECT 'reversed_without_mirror' AS kind, e.id AS "journalEntryId", e."entryNumber", e."sourceType",
                       e."reversedEntryId", count(*) OVER () AS rows_total
                  FROM "JournalEntry" e
                 WHERE e."organizationId" = :org AND e.status::text = 'reversed'
                   AND NOT EXISTS (SELECT 1 FROM "JournalEntry" r WHERE r.id = e."reversedEntryId"
                                     AND r."organizationId" = :org AND r."reversalOfId" = e.id
                                     AND r.status::text IN ('posted', 'reversed'))
                 ORDER BY e."postingDate", e."entryNumber\"""", params(null));
        Found orphan = find("""
                SELECT 'orphan_reversal' AS kind, r.id AS "reversalEntryId", r."entryNumber",
                       r."reversalOfId" AS "journalEntryId", o.status::text AS "originalStatus",
                       o."reversedEntryId" AS "originalReversedBy", count(*) OVER () AS rows_total
                  FROM "JournalEntry" r
                  LEFT JOIN "JournalEntry" o ON o.id = r."reversalOfId" AND o."organizationId" = :org
                 WHERE r."organizationId" = :org AND r."reversalOfId" IS NOT NULL
                   AND r.status::text IN ('posted', 'reversed')
                   AND (o.id IS NULL OR o.status::text <> 'reversed' OR o."reversedEntryId" IS DISTINCT FROM r.id)
                 ORDER BY r."postingDate", r."entryNumber\"""", params(null));
        Found duplicate = find("""
                SELECT 'duplicate_reversal' AS kind, r."reversalOfId" AS "journalEntryId", count(*) AS reversals,
                       count(*) OVER () AS rows_total
                  FROM "JournalEntry" r
                 WHERE r."organizationId" = :org AND r."reversalOfId" IS NOT NULL
                   AND r.status::text IN ('posted', 'reversed')
                 GROUP BY r."reversalOfId"
                HAVING count(*) > 1
                 ORDER BY r."reversalOfId\"""", params(null));
        Found mismatch = find("""
                WITH pairs AS (
                  SELECT r."reversalOfId" AS oid, r.id AS rid
                    FROM "JournalEntry" r
                   WHERE r."organizationId" = :org AND r."reversalOfId" IS NOT NULL
                     AND r.status::text IN ('posted', 'reversed'))
                SELECT 'mirror_mismatch' AS kind, p.oid AS "journalEntryId", p.rid AS "reversalEntryId",
                       l."accountId", sum(l."baseDebit" - l."baseCredit") AS net, count(*) OVER () AS rows_total
                  FROM pairs p
                  JOIN "JournalLine" l ON l."journalEntryId" IN (p.oid, p.rid) AND l."organizationId" = :org
                 GROUP BY p.oid, p.rid, l."accountId"
                HAVING sum(l."baseDebit" - l."baseCredit") <> 0
                 ORDER BY p.oid, l."accountId\"""", params(null));
        return check(REVERSAL_LINKS, "a reversed entry has exactly one posted reversal, linked both ways, that"
                + " mirrors it account by account", null, merge(unlinked, orphan, duplicate, mismatch), null);
    }

    // ------------------------------------------------------------------ I11, I9: VAT and inventory

    /**
     * The offset entry of each settlement in force is posted, and output / input VAT of its period, measured as
     * {@code TaxSettlementService} measures it, still equal the settled figures. A difference means something reached
     * the settled period afterwards (a posting racing the settlement, a write that bypassed the VAT lock, a reversed
     * settlement entry) or the tax accounts / organisation time zone changed since. A settlement undone through
     * {@code POST /tax-settlements/{id}/reverse} (finance audit A3) locks nothing, so its figures are not compared; its
     * offset entry must be reversed by a posted reversal entry instead.
     */
    private IntegrityCheck taxLock(ZoneId zone) {
        Found entries = find("""
                SELECT CASE WHEN s."reversedAt" IS NULL THEN 'settlement_entry_not_posted'
                            ELSE 'settlement_reversal_not_posted' END AS kind,
                       s.id AS "settlementId", s."periodFrom", s."periodTo", s."inputTax",
                       CASE WHEN s."reversedAt" IS NULL THEN s."journalEntryId" ELSE s."reversalJournalEntryId" END
                           AS "journalEntryId",
                       e.status::text AS "entryStatus", count(*) OVER () AS rows_total
                  FROM "TaxSettlement" s
                  LEFT JOIN "JournalEntry" e ON e."organizationId" = :org
                       AND e.id = CASE WHEN s."reversedAt" IS NULL THEN s."journalEntryId"
                                       ELSE s."reversalJournalEntryId" END
                 WHERE s."organizationId" = :org
                   AND ((s."journalEntryId" IS NOT NULL AND (e.id IS NULL OR e.status::text <> 'posted'))
                     OR (s."reversedAt" IS NULL AND s."journalEntryId" IS NULL AND s."inputTax" > 0))
                 ORDER BY s."periodTo\"""", params(null));
        // Mappings are read as AccountResolverService.byMapping reads them: one row per key, used even when soft-deleted.
        Found figures = find("""
                WITH recv AS (
                  SELECT "accountId" FROM "AccountMapping"
                   WHERE "organizationId" = :org AND key = 'tax_receivable'),
                outp AS (
                  (SELECT "accountId" FROM "AccountMapping"
                    WHERE "organizationId" = :org AND key = 'tax_payable'
                   UNION SELECT "accountId" FROM "Tax" WHERE "organizationId" = :org AND "accountId" IS NOT NULL)
                  EXCEPT SELECT "accountId" FROM recv),
                mv AS (
                  SELECT s.id,
                         coalesce(-sum(l."baseDebit" - l."baseCredit")
                                  FILTER (WHERE l."accountId" IN (SELECT "accountId" FROM outp)), 0) AS output,
                         coalesce(sum(l."baseDebit" - l."baseCredit")
                                  FILTER (WHERE l."accountId" IN (SELECT "accountId" FROM recv)), 0) AS input
                    FROM "TaxSettlement" s
                    LEFT JOIN ("JournalLine" l
                               JOIN "JournalEntry" e ON e.id = l."journalEntryId" AND e."organizationId" = :org
                                    AND %s AND coalesce(e."sourceType", '') <> 'tax_settlement'
                                    AND coalesce(e.dimensions ->> 'treasuryOperation', '') <> 'tax_payment'
                                    AND NOT EXISTS (SELECT 1 FROM "JournalEntry" o WHERE o.id = e."reversalOfId"
                                          AND (coalesce(o.dimensions ->> 'treasuryOperation', '') = 'tax_payment'
                                            OR coalesce(o."sourceType", '') = 'tax_settlement')))
                           ON l."organizationId" = :org
                          AND l."accountId" IN (SELECT "accountId" FROM outp UNION SELECT "accountId" FROM recv)
                          AND e."postingDate" >= %s AND e."postingDate" <= %s
                   WHERE s."organizationId" = :org AND s."reversedAt" IS NULL
                   GROUP BY s.id)
                SELECT 'settled_figures_changed' AS kind, s.id AS "settlementId", s."periodFrom", s."periodTo",
                       s."outputTax" AS "settledOutputTax", mv.output AS "outputTaxNow",
                       s."inputTax" AS "settledInputTax", mv.input AS "inputTaxNow", count(*) OVER () AS rows_total
                  FROM "TaxSettlement" s
                  JOIN mv ON mv.id = s.id
                 WHERE abs(mv.output - s."outputTax") > :tol OR abs(mv.input - s."inputTax") > :tol
                 ORDER BY s."periodTo\"""".formatted(POSTED, wallToUtc("s.\"periodFrom\"", zone),
                wallToUtc("s.\"periodTo\"", zone)), params(TOLERANCE));
        return check(TAX_LOCK, "every VAT settlement in force has its offset entry posted (an undone one, its reversal),"
                + " and output / input VAT of each settled period still equal the settled figures", TOLERANCE,
                merge(entries, figures), null);
    }

    private IntegrityCheck inventoryGl() {
        String invariant = "inventory valuation = inventory GL accounts (the inventory GL tie-out)";
        try {
            if (!settings.resolveBool("inventory.glTieOut.enabled", SettingContext.EMPTY)) {
                return skipped(INVENTORY_GL, invariant, null, "inventory.glTieOut.enabled is off");
            }
            Boolean stock = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"StockItem\" WHERE \"organizationId\" = ?)")
                    .param(org()).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(stock)) {
                return skipped(INVENTORY_GL, invariant, null, "the organisation holds no stock");
            }
            Number configured = settings.resolveNumber("inventory.glTieOut.tolerance", SettingContext.EMPTY);
            double tolerance = Math.max(0, configured == null ? 1 : configured.doubleValue());
            Map<String, Object> t = valuation.glTieOut(null, tolerance);
            boolean within = Boolean.TRUE.equals(t.get("withinTolerance"));
            List<Map<String, Object>> rows = within ? List.of()
                    : List.of(row("asOf", String.valueOf(t.get("asOf")), "subledgerValue", t.get("subledgerValue"),
                            "glBalance", t.get("glBalance"), "variance", t.get("variance")));
            return check(INVENTORY_GL, invariant, new BigDecimal(JsNumbers.toJsString(tolerance)),
                    new Found(within ? 0 : 1, false, rows), null);
        } catch (RuntimeException e) {
            log.warn("[ledger-integrity] inventory GL tie-out failed for {}: {}", org(), e.toString());
            String why = e instanceof ApiException ? e.getMessage() : "see the server log";
            return new IntegrityCheck(INVENTORY_GL, false, false, invariant, null, 0, false, List.of(),
                    "the tie-out could not be computed: " + why);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * SQL for a stored org-local wall-clock column as the UTC timestamp it denotes. A fixed offset goes through interval
     * arithmetic: Postgres reads a text offset such as {@code '+03:00'} in {@code AT TIME ZONE} as a POSIX zone, with
     * the sign inverted.
     */
    public static String wallToUtc(String column, ZoneId zone) {
        if (AccountingDay.isUtc(zone)) {
            return column;
        }
        if (zone.normalized() instanceof ZoneOffset offset) {
            return "(" + column + " - interval '" + offset.getTotalSeconds() + " seconds')";
        }
        return "(" + column + " AT TIME ZONE '" + zoneId(zone) + "' AT TIME ZONE 'UTC')";
    }

    /** SQL for a UTC timestamp column as the org-local wall clock (the inverse of {@link #wallToUtc}). */
    public static String utcToWall(String column, ZoneId zone) {
        if (AccountingDay.isUtc(zone)) {
            return column;
        }
        if (zone.normalized() instanceof ZoneOffset offset) {
            return "(" + column + " + interval '" + offset.getTotalSeconds() + " seconds')";
        }
        return "(" + column + " AT TIME ZONE 'UTC' AT TIME ZONE '" + zoneId(zone) + "')";
    }

    /** A region id, checked before it is inlined (letters, digits, {@code / _ + -}). */
    private static String zoneId(ZoneId zone) {
        String id = zone.getId();
        if (!id.matches("[A-Za-z0-9/_+\\-]+")) {
            throw new IllegalArgumentException("Unexpected zone id: " + id);
        }
        return id;
    }

    private static Map<String, Object> params(BigDecimal tolerance) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", org());
        if (tolerance != null) {
            p.put("tol", tolerance);
        }
        return p;
    }

    /**
     * Rows of {@code sql}, at most {@value #ROW_LIMIT}. The query selects {@code rows_total} (all its rows) and, when a
     * row stands for several failures, {@code failures_total}.
     */
    private Found find(String sql, Map<String, Object> params) {
        List<Map<String, Object>> found = SqlRows.list(jdbc.sql(sql + " LIMIT " + ROW_LIMIT).params(params));
        if (found.isEmpty()) {
            return new Found(0, false, List.of());
        }
        Map<String, Object> first = found.getFirst();
        long rowsTotal = ((Number) first.get("rows_total")).longValue();
        Object failures = first.get("failures_total");
        List<Map<String, Object>> rows = new ArrayList<>(found.size());
        for (Map<String, Object> r : found) {
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.remove("rows_total");
            out.remove("failures_total");
            rows.add(render(out));
        }
        return new Found(failures == null ? rowsTotal : ((Number) failures).longValue(), rowsTotal > rows.size(), rows);
    }

    private static Found merge(Found... parts) {
        long failures = 0;
        boolean truncated = false;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Found f : parts) {
            failures += f.failures();
            truncated |= f.truncated();
            for (Map<String, Object> r : f.rows()) {
                if (rows.size() < ROW_LIMIT) {
                    rows.add(r);
                } else {
                    truncated = true;
                }
            }
        }
        return new Found(failures, truncated, rows);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    /** Decimals as exact decimal strings, like the other accounting reports. */
    private static Map<String, Object> render(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof BigDecimal b ? JsNumbers.dec(b) : v));
        return out;
    }

    private static IntegrityCheck check(String key, String invariant, BigDecimal tolerance, Found f, String note) {
        return new IntegrityCheck(key, f.failures() == 0, false, invariant, tolerance, f.failures(), f.truncated(),
                f.rows(), note);
    }

    private static IntegrityCheck skipped(String key, String invariant, BigDecimal tolerance, String note) {
        return new IntegrityCheck(key, true, true, invariant, tolerance, 0, false, List.of(), note);
    }
}
