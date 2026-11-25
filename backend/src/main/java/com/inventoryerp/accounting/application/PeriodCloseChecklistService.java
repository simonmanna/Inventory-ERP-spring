package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Period-close checklist (FIN-3): the blocking gates of {@link PeriodCloseService#close}, advisory checks on unfinished
 * documents, and manual sign-offs. It reports and records; {@code close} keeps its own gates and ignores sign-offs.
 */
@Service
public class PeriodCloseChecklistService {

    /** Manual steps, in the order a controller works through them. */
    private static final Map<String, String> SIGN_OFFS = new LinkedHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(PeriodCloseChecklistService.class);

    static {
        SIGN_OFFS.put("bank_reconciled", "Bank accounts reconciled");
        SIGN_OFFS.put("accruals_booked", "Accruals and prepayments booked");
        SIGN_OFFS.put("tax_return_reviewed", "Tax return reviewed");
        SIGN_OFFS.put("management_review", "Management accounts reviewed");
    }

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ObjectProvider<SubledgerTieOut> tieOuts;
    private final TransactionTemplate readOnly;

    public PeriodCloseChecklistService(JdbcClient jdbc, AuditService audit, ObjectProvider<SubledgerTieOut> tieOuts,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tieOuts = tieOuts;
        this.readOnly = new TransactionTemplate(txm);
        this.readOnly.setReadOnly(true);
    }

    private Map<String, Object> period(String periodId, String org) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT id, name, status::text AS status, \"startDate\", "
                + "\"endDate\""
                + " FROM \"FiscalPeriod\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(periodId, org));
        if (p == null) {
            throw ApiException.notFound("Fiscal period not found");
        }
        return p;
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static Map<String, Object> check(String key, String label, boolean blocking, long found) {
        return Obj.of("key", key, "label", label, "blocking", blocking, "count", found, "ok", found == 0);
    }

    /**
     * The checklist of one period, computed now. The AR / AP tie-out (finance audit A5) is measured first, in its own
     * read-only transaction, so a failure to compute it cannot spoil the checklist; the rest shares one.
     */
    public Map<String, Object> checklist(String periodId) {
        String org = TenantContext.organizationId();
        Map<String, Object> period = readOnly.execute(s -> period(periodId, org));
        Map<String, Object> tieOut = arApTieOut(((Instant) period.get("endDate")).atOffset(ZoneOffset.UTC)
                .toLocalDate());
        return readOnly.execute(s -> checklist(periodId, org, tieOut));
    }

    /**
     * Finance audit A5 (P2-5), advisory: the customer and supplier sub-ledgers tie to their control accounts at the
     * period's last day ({@link SubledgerTieOut}, the measure of the AR / AP reconciliation reports). Found = partners
     * that differ, plus a side whose totals differ though no single partner does.
     */
    private Map<String, Object> arApTieOut(LocalDate lastDay) {
        String label = "Customer and supplier balances tie to the control accounts (AR / AP tie-out)";
        SubledgerTieOut tieOut = tieOuts.getIfAvailable();
        if (tieOut == null) {
            return check("ar_ap_tieout", label, false, 0);
        }
        try {
            long found = 0;
            for (String side : List.of("AR", "AP")) {
                SubledgerTieOut.Measure m = tieOut.measure(side, lastDay);
                found += m.differences().isEmpty() ? (m.balanced() ? 0 : 1) : m.differences().size();
            }
            return check("ar_ap_tieout", label, false, found);
        } catch (RuntimeException e) {
            log.warn("[close-checklist] AR/AP tie-out failed: {}", e.toString());
            Map<String, Object> c = new LinkedHashMap<>(check("ar_ap_tieout", label, false, 0));
            c.put("ok", false);
            c.put("note", "The tie-out could not be computed");
            return c;
        }
    }

    private Map<String, Object> checklist(String periodId, String org, Map<String, Object> arApTieOut) {
        Map<String, Object> p = period(periodId, org);
        // Period bounds are org-local wall clock (ADR-S070): real timestamps are compared with the converted bounds
        // (startAt / endAt); date-like columns stored as UTC-midnight dates keep the wall-clock ones.
        Timestamp start = SqlRows.ts((Instant) p.get("startDate"));
        Timestamp end = SqlRows.ts((Instant) p.get("endDate"));
        java.time.ZoneId zone = AccountingDay.zone(jdbc, org);
        Timestamp startAt = AccountingDay.fromWallClock(start, zone);
        Timestamp endAt = AccountingDay.fromWallClock(end, zone);

        // As the close gate (review 2026-11-17, ACR-17): only stock documents raised up to the period end.
        String pending = " WHERE \"organizationId\" = ? AND status::text IN ('draft', 'pending', 'approved')"
                + " AND \"createdAt\" <= ?";
        long mutations = count("SELECT count(*) FROM \"StockOut\"" + pending, org, endAt)
                + count("SELECT count(*) FROM \"WasteRecord\"" + pending, org, endAt)
                + count("SELECT count(*) FROM \"StockAdjustment\"" + pending, org, endAt)
                + count("SELECT count(*) FROM \"StockTransfer\"" + pending, org, endAt);
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("register_shifts", "Register shifts reconciled", true,
                count("SELECT count(*) FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"openedAt\" <= ?"
                        + " AND status::text <> 'reconciled'", org, endAt)));
        // As the close gate (finance audit A3, P2-13): a job counts in the period of its sale, and when queued in it.
        checks.add(check("stock_posting", "Stock postings finished", true,
                count(PeriodCloseService.UNPOSTED_STOCK_JOBS, org, startAt, endAt, startAt, endAt)));
        checks.add(check("inventory_mutations", "No pending stock documents", true, mutations));
        checks.add(check("inventory_exceptions", "No open inventory exceptions", true,
                count(PeriodCloseService.OPEN_SALE_LINE_EXCEPTIONS, org, InventoryTieOutMonitor.KIND,
                        InventoryTieOutMonitor.INTEGRITY_KIND_PREFIX, startAt, endAt, startAt, endAt)));
        // Hardening R0: the daily inventory integrity check parks a failing value check (valuation vs stock ledger or
        // GL) as an open exception of its own kind; like the close gate, it blocks while open (raised since the
        // period started). Quantity findings stay in the Posting Monitor and are not a close item.
        checks.add(check("inventory_integrity_value", "Stock value integrity checks pass (no open valuation finding)",
                true, count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND status::text"
                        + " = 'open' AND kind = ANY(?::text[]) AND \"createdAt\" >= ?", org,
                        InventoryTieOutMonitor.INTEGRITY_VALUE_KINDS.toArray(String[]::new), startAt)));
        // The daily inventory / GL tie-out check (deviation 20 of docs/spec/inventory.md) parks a variance as an open
        // InventoryException of its own kind. A reconciliation finding, so advisory like the bank and landed-cost
        // ones: raised since the period started (the run after the last day lands in the next period) and still open.
        checks.add(check("inventory_gl_tieout", "Inventory ties to the GL (no open tie-out variance)", false,
                count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind = ? AND"
                        + " status::text = 'open' AND \"createdAt\" >= ?", org, InventoryTieOutMonitor.KIND, startAt)));
        checks.add(arApTieOut);
        // FX-7 (ADR-S082): foreign-currency monetary balances open at period end should be revalued for the period
        // (advisory: the run is a period-end step, not a lock). Found = open (account, currency) balances when no
        // revaluation was recorded for this period.
        long revalued = count("SELECT count(*) FROM \"FxRevaluation\" WHERE \"organizationId\" = ? AND \"fiscalPeriodId\" = ?",
                org, periodId);
        long foreignOpen = revalued > 0 ? 0 : count("SELECT count(*) FROM (SELECT 1 FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\" JOIN"
                + " \"AccountCategory\" c ON c.id = a.\"categoryId\" JOIN \"Organization\" o ON o.id = l.\"organizationId\""
                + " WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" <= ?"
                + " AND l.\"currencyId\" IS NOT NULL AND upper(l.\"currencyId\") <> upper(o.\"currencyCode\") AND "
                + RevaluationService.MONETARY + " GROUP BY l.\"accountId\", upper(l.\"currencyId\") HAVING"
                + " sum(l.debit - l.credit) <> 0) x", org, endAt);
        checks.add(check("fx_revaluation", "Foreign-currency balances revalued for the period", false, foreignOpen));
        checks.add(check("draft_journals", "No draft journal entries", false,
                count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND status::text = 'draft'"
                        + " AND \"postingDate\" >= ? AND \"postingDate\" <= ?", org, startAt, endAt)));
        checks.add(check("draft_invoices", "No draft sales invoices", false, draftDocuments("sales_invoice", org, start,
                end)));
        checks.add(check("draft_bills", "No draft vendor bills", false, draftDocuments("vendor_bill", org, start,
                end)));
        checks.add(check("draft_receipts", "No draft goods receipts", false,
                count("SELECT count(*) FROM \"GoodsReceiptNote\" WHERE \"organizationId\" = ? AND status::text = "
                        + "'draft'"
                        + " AND \"deletedAt\" IS NULL AND \"receivedAt\" >= ? AND \"receivedAt\" <= ?", org, startAt,
                        endAt)));
        checks.add(check("unmatched_bank_lines", "No unmatched bank statement lines", false,
                count("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND status = 'unmatched'"
                        + " AND \"postedAt\" >= ? AND \"postedAt\" <= ?", org, start, end)));
        // CM-10 (ADR-S041): costing activity in the period should sit in a locked costing period (cost close
        // cockpit gates passed). Advisory here; the costing lock itself refuses later costing postings.
        long costingUnlocked = count("SELECT count(*) FROM \"JournalEntry\" e WHERE e.\"organizationId\" = ? AND"
                + " e.status::text <> 'draft' AND e.\"postingDate\" >= ? AND e.\"postingDate\" <= ? AND e.\"sourceType\" ="
                + " ANY (?) AND NOT EXISTS (SELECT 1 FROM \"CostingPeriodLock\" l WHERE l.\"organizationId\" ="
                + " e.\"organizationId\" AND l.status = 'locked' AND l.\"periodFrom\" <= e.\"postingDate\"::date AND"
                + " l.\"periodTo\" >= e.\"postingDate\"::date)", org, startAt, endAt,
                CostingDimensionGuard.COSTING_SOURCES.toArray(String[]::new));
        checks.add(check("costing_close", "Costing period closed (cost close cockpit)", false, costingUnlocked));
        // Finance readiness audit B5 (P3): advisory reminders for the other sub-ledgers that feed the GL.
        long taxActivity = count("SELECT count(*) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                + " AND e.\"postingDate\" >= ? AND e.\"postingDate\" <= ? AND l.\"accountId\" IN (SELECT \"accountId\""
                + " FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key IN ('tax_payable', 'tax_receivable')"
                + " AND \"deletedAt\" IS NULL UNION SELECT \"accountId\" FROM \"Tax\" WHERE \"organizationId\" = ? AND"
                + " \"accountId\" IS NOT NULL)", org, startAt, endAt, org, org);
        long vatSettled = taxActivity == 0 ? 1 : count("SELECT count(*) FROM \"TaxSettlement\" WHERE"
                + " \"organizationId\" = ? AND \"reversedAt\" IS NULL AND \"periodTo\"::date >= ?::date", org, end);
        checks.add(check("vat_settled", "VAT settled through the period end", false, vatSettled == 0 ? 1 : 0));
        checks.add(check("depreciation_posted", "Depreciation for the period posted", false,
                count("SELECT count(*) FROM \"AssetDepreciation\" WHERE \"organizationId\" = ? AND NOT \"isPosted\""
                        + " AND period >= to_char(?::timestamp, 'YYYY-MM') AND period <= to_char(?::timestamp, 'YYYY-MM')",
                        org, start, end)));
        checks.add(check("payroll_posted", "Payroll runs of the period posted to the GL", false,
                count("SELECT count(*) FROM \"HrPayrollRun\" r JOIN \"HrPayrollPeriod\" p ON p.id = r.\"periodId\""
                        + " WHERE r.\"organizationId\" = ? AND r.status::text <> 'REVERSED' AND NOT r.\"glPosted\""
                        + " AND p.\"deletedAt\" IS NULL AND p.\"endDate\" >= ? AND p.\"endDate\" <= ?", org, start, end)));
        checks.add(check("recurring_failed", "No failed recurring journal runs", false,
                count("SELECT count(*) FROM \"RecurringJournalRun\" WHERE \"organizationId\" = ? AND status = 'failed'"
                        + " AND \"runDate\" >= ?::date AND \"runDate\" <= ?::date", org, start, end)));
        checks.add(check("deferrals_released", "Deferral releases due in the period posted", false,
                count("SELECT count(*) FROM \"DeferralRelease\" WHERE \"organizationId\" = ? AND status = 'planned'"
                        + " AND \"periodEnd\" <= ?::date", org, end)));
        // LC-3 (ADR-S053): landed-cost shares parked because their goods could not be followed must be resolved.
        // The full landed-cost reconciliation (six balances, classification, location) is on its own page.
        checks.add(check("landed_cost_lineage", "Landed cost lineage exceptions resolved", false,
                count("SELECT count(*) FROM \"LineageException\" WHERE \"organizationId\" = ? AND status = 'open'", org)));

        Map<String, Map<String, Object>> signed = new HashMap<>();
        for (Map<String, Object> s : SqlRows.list(jdbc.sql("SELECT s.key, s.note, s.\"signedBy\", s.\"signedAt\","
                + " u.\"firstName\" AS \"signedByName\" FROM \"PeriodCloseSignOff\" s LEFT JOIN \"User\" u ON u.id = "
                + "s.\"signedBy\""
                + " WHERE s.\"organizationId\" = ? AND s.\"periodId\" = ?").params(org, periodId))) {
            signed.put((String) s.get("key"), s);
        }
        List<Map<String, Object>> signOffs = new ArrayList<>();
        SIGN_OFFS.forEach((key, label) -> {
            Map<String, Object> s = signed.get(key);
            signOffs.add(Obj.of("key", key, "label", label, "signed", s != null, "note",
                    s == null ? null : s.get("note"),
                    "signedBy", s == null ? null : s.get("signedBy"), "signedByName",
                            s == null ? null : s.get("signedByName"),
                    "signedAt", s == null ? null : s.get("signedAt")));
        });
        boolean gatesPass = checks.stream().allMatch(c -> !Boolean.TRUE.equals(c.get("blocking"))
                || Boolean.TRUE.equals(c.get("ok")));
        boolean complete = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("ok")))
                && signOffs.stream().allMatch(s -> Boolean.TRUE.equals(s.get("signed")));
        return Obj.of("periodId", p.get("id"), "name", p.get("name"), "status", p.get("status"), "startDate",
                p.get("startDate"), "endDate", p.get("endDate"), "checks", checks, "signOffs", signOffs, "readyToClose",
                gatesPass, "complete", complete);
    }

    private long draftDocuments(String type, String org, Timestamp start, Timestamp end) {
        return count("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ? AND \"documentType\"::text = ?"
                + " AND status::text = 'draft' AND \"issueDate\" >= ? AND \"issueDate\" <= ?", org, type, start, end);
    }

    /** Sign a manual step, or take the signature back, while the period is open. */
    @Transactional
    public Map<String, Object> signOff(String periodId, String key, boolean signed, String note) {
        String org = TenantContext.organizationId();
        Map<String, Object> p = period(periodId, org);
        if (!SIGN_OFFS.containsKey(key)) {
            throw ApiException.badRequest("Unknown checklist step: " + key);
        }
        if (!"open".equals(p.get("status"))) {
            throw ApiException.badRequest("Only an open period's checklist can be changed");
        }
        if (signed) {
            jdbc.sql("INSERT INTO \"PeriodCloseSignOff\" (id, \"organizationId\", \"periodId\", key, note, "
                    + "\"signedBy\")"
                            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"periodId\", key) DO "
                            + "UPDATE SET"
                            + " note = EXCLUDED.note, \"signedBy\" = EXCLUDED.\"signedBy\", \"signedAt\" = "
                            + "CURRENT_TIMESTAMP")
                    .params(UUID.randomUUID().toString(), org, periodId, key, note, TenantContext.userId()).update();
        } else {
            jdbc.sql("DELETE FROM \"PeriodCloseSignOff\" WHERE \"organizationId\" = ? AND \"periodId\" = ? AND key = ?")
                    .params(org, periodId, key).update();
        }
        audit.recordInTx(AuditInput.of("FiscalPeriod", periodId, "update", null, Obj.of("closeChecklist", key, "signed",
                signed, "note", note)));
        return checklist(periodId);
    }
}
