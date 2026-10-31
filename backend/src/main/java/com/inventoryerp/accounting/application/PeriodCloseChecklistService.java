package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Period-close checklist (FIN-3): the blocking gates of {@link PeriodCloseService#close}, advisory checks on unfinished
 * documents, and manual sign-offs. It reports and records; {@code close} keeps its own gates and ignores sign-offs.
 */
@Service
public class PeriodCloseChecklistService {

    /** Manual steps, in the order a controller works through them. */
    private static final Map<String, String> SIGN_OFFS = new LinkedHashMap<>();

    static {
        SIGN_OFFS.put("bank_reconciled", "Bank accounts reconciled");
        SIGN_OFFS.put("accruals_booked", "Accruals and prepayments booked");
        SIGN_OFFS.put("tax_return_reviewed", "Tax return reviewed");
        SIGN_OFFS.put("management_review", "Management accounts reviewed");
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PeriodCloseChecklistService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
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

    /** The checklist of one period, computed now. */
    @Transactional(readOnly = true)
    public Map<String, Object> checklist(String periodId) {
        String org = TenantContext.organizationId();
        Map<String, Object> p = period(periodId, org);
        Timestamp start = SqlRows.ts((Instant) p.get("startDate"));
        Timestamp end = SqlRows.ts((Instant) p.get("endDate"));

        String pending = " WHERE \"organizationId\" = ? AND status::text IN ('draft', 'pending', 'approved')";
        long mutations = count("SELECT count(*) FROM \"StockOut\"" + pending, org)
                + count("SELECT count(*) FROM \"WasteRecord\"" + pending, org)
                + count("SELECT count(*) FROM \"StockAdjustment\"" + pending, org)
                + count("SELECT count(*) FROM \"StockTransfer\"" + pending, org);
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("register_shifts", "Register shifts reconciled", true,
                count("SELECT count(*) FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"openedAt\" <= ?"
                        + " AND status::text <> 'reconciled'", org, end)));
        checks.add(check("stock_posting", "Stock postings finished", true,
                count("SELECT count(*) FROM \"StockPostingJob\" WHERE \"organizationId\" = ? AND status::text IN"
                        + " ('pending', 'processing', 'failed') AND \"createdAt\" >= ? AND \"createdAt\" <= ?", org,
                        start, end)));
        checks.add(check("inventory_mutations", "No pending stock documents", true, mutations));
        checks.add(check("inventory_exceptions", "No open inventory exceptions", true,
                count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND status::text = "
                        + "'open'"
                        + " AND \"createdAt\" >= ? AND \"createdAt\" <= ?", org, start, end)));
        checks.add(check("draft_journals", "No draft journal entries", false,
                count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND status::text = 'draft'"
                        + " AND \"postingDate\" >= ? AND \"postingDate\" <= ?", org, start, end)));
        checks.add(check("draft_invoices", "No draft sales invoices", false, draftDocuments("sales_invoice", org, start,
                end)));
        checks.add(check("draft_bills", "No draft vendor bills", false, draftDocuments("vendor_bill", org, start,
                end)));
        checks.add(check("draft_receipts", "No draft goods receipts", false,
                count("SELECT count(*) FROM \"GoodsReceiptNote\" WHERE \"organizationId\" = ? AND status::text = "
                        + "'draft'"
                        + " AND \"deletedAt\" IS NULL AND \"receivedAt\" >= ? AND \"receivedAt\" <= ?", org, start,
                        end)));
        checks.add(check("unmatched_bank_lines", "No unmatched bank statement lines", false,
                count("SELECT count(*) FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND status = 'unmatched'"
                        + " AND \"postedAt\" >= ? AND \"postedAt\" <= ?", org, start, end)));

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
