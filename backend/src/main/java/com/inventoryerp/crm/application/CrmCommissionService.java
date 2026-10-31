package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.dec;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.hr.application.HrPayrollInputService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Salesperson commissions (SALES-4). A rule pays a percentage of a line's net value, by salesperson and product. A
 * calculation run turns every posted invoice line with a salesperson into one entry (the unique index keeps a re-run
 * idempotent) and cancels entries of cancelled invoices that have not reached payroll. Approved entries become one
 * taxable earning per employee through {@link HrPayrollInputService}, so payroll posts the expense. An entry is earned
 * on posting, whether or not the customer has paid. A salesperson never approves their own commission.
 */
@Service
public class CrmCommissionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String PAYROLL_SOURCE = "sales_commission";

    private final CrmDb db;
    private final AuditService audit;
    private final HrPayrollInputService payrollInputs;

    public CrmCommissionService(CrmDb db, AuditService audit, HrPayrollInputService payrollInputs) {
        this.db = db;
        this.audit = audit;
        this.payrollInputs = payrollInputs;
    }

    // ------------------------------------------------------------------ rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules() {
        return db.rows("SELECT r.*, e.\"firstName\", e.\"lastName\", p.name AS \"productName\", c.name AS"
                + " \"productCategoryName\" FROM \"CommissionRule\" r LEFT JOIN \"HrEmployee\" e ON e.id = r.\"employeeId\""
                + " LEFT JOIN \"Product\" p ON p.id = r.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id ="
                + " r.\"productCategoryId\" WHERE r.\"organizationId\" = ? ORDER BY r.priority DESC, r.name", org());
    }

    @Transactional
    public Map<String, Object> saveRule(String id, Map<String, Object> in) {
        if (blank(str(in.get("name")))) {
            throw ApiException.badRequest("name should not be empty");
        }
        BigDecimal rate = dec(in.get("ratePercent"));
        if (rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) {
            throw ApiException.badRequest("ratePercent must be between 0 and 100");
        }
        String employee = blank(str(in.get("employeeId"))) ? null : str(in.get("employeeId"));
        String product = blank(str(in.get("productId"))) ? null : str(in.get("productId"));
        String category = blank(str(in.get("productCategoryId"))) ? null : str(in.get("productCategoryId"));
        if (product != null && category != null) {
            throw ApiException.badRequest("Choose a product or a product category, not both");
        }
        if (employee != null && !db.exists("HrEmployee", employee)) {
            throw ApiException.notFound("Employee not found");
        }
        if (product != null && !db.exists("Product", product)) {
            throw ApiException.notFound("Product not found");
        }
        if (category != null && !db.exists("ProductCategory", category)) {
            throw ApiException.notFound("Product category not found");
        }
        LocalDate from = in.get("validFrom") == null ? null : CrmDb.date(str(in.get("validFrom")));
        LocalDate to = in.get("validTo") == null ? null : CrmDb.date(str(in.get("validTo")));
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("validTo must be on or after validFrom");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", str(in.get("name")).trim());
        d.put("employeeId", employee);
        d.put("productId", product);
        d.put("productCategoryId", category);
        d.put("ratePercent", rate);
        d.put("validFrom", from);
        d.put("validTo", to);
        d.put("priority", in.get("priority") instanceof Number n ? n.intValue() : 0);
        d.put("isActive", !Boolean.FALSE.equals(in.get("isActive")));
        Map<String, Object> row;
        if (id == null) {
            d.put("createdBy", user());
            row = db.insert("CommissionRule", d);
        } else {
            db.require("CommissionRule", id, "Commission rule not found");
            row = db.patch("CommissionRule", id, d, true);
        }
        audit.recordInTx(AuditInput.of("CommissionRule", str(row.get("id")), id == null ? "create" : "update", null, d));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteRule(String id) {
        db.require("CommissionRule", id, "Commission rule not found");
        long used = db.row("SELECT count(*) AS n FROM \"CommissionEntry\" WHERE \"organizationId\" = ? AND \"ruleId\" = ?",
                org(), id).get("n") instanceof Number n ? n.longValue() : 0;
        if (used > 0) {
            db.patch("CommissionRule", id, Obj.of("isActive", false), true);
            return Obj.of("ok", true, "deactivated", true);
        }
        db.update("DELETE FROM \"CommissionRule\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.recordInTx(AuditInput.of("CommissionRule", id, "delete"));
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ calculation

    /** Earn entries for invoices posted in {@code [from, to]}; a re-run adds only what is new. */
    @Transactional
    public Map<String, Object> calculate(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now(BusinessZone.get()).withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now(BusinessZone.get());
        if (end.isBefore(start)) {
            throw ApiException.badRequest("to must be on or after from");
        }
        int created = 0;
        int noRule = 0;
        for (Map<String, Object> l : db.rows("""
                SELECT d.id AS "documentId", d."documentNumber", d."salespersonId", d."issueDate", l.id AS "lineId",
                       l."productId", l.subtotal, p."categoryId"
                  FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
                  LEFT JOIN "Product" p ON p.id = l."productId"
                 WHERE d."organizationId" = ? AND d."documentType"::text = 'sales_invoice'
                   AND d.status::text IN ('posted', 'paid') AND d."salespersonId" IS NOT NULL
                   AND d."issueDate"::date >= ? AND d."issueDate"::date <= ? AND l."lineType" = 'product'
                   AND NOT EXISTS (SELECT 1 FROM "CommissionEntry" e WHERE e."organizationId" = d."organizationId"
                                    AND e."lineId" = l.id AND e.status <> 'cancelled')
                 ORDER BY d."issueDate", l."lineNumber"
                """.stripTrailing(), org(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end))) {
            LocalDate on = l.get("issueDate") instanceof java.time.Instant i
                    ? i.atZone(BusinessZone.get()).toLocalDate() : start;
            Map<String, Object> rule = rule(str(l.get("salespersonId")), str(l.get("productId")),
                    str(l.get("categoryId")), on);
            if (rule == null) {
                noRule++;
                continue;
            }
            BigDecimal base = dec(l.get("subtotal"));
            BigDecimal rate = dec(rule.get("ratePercent"));
            BigDecimal amount = commission(base, rate);
            if (amount.signum() <= 0) {
                continue;
            }
            db.insert("CommissionEntry", Obj.of("employeeId", l.get("salespersonId"), "documentId", l.get("documentId"),
                    "documentNumber", l.get("documentNumber"), "lineId", l.get("lineId"), "productId", l.get("productId"),
                    "ruleId", rule.get("id"), "baseAmount", base, "ratePercent", rate, "amount", amount, "earnedOn", on,
                    "createdBy", user()));
            created++;
        }
        int cancelled = db.update("UPDATE \"CommissionEntry\" e SET status = 'cancelled' FROM \"Document\" d WHERE"
                + " d.id = e.\"documentId\" AND e.\"organizationId\" = ? AND e.status IN ('accrued', 'approved') AND"
                + " d.status::text NOT IN ('posted', 'paid')", org());
        audit.recordInTx(AuditInput.of("CommissionRun", start + ".." + end, "create", null, Obj.of("created", created,
                "cancelled", cancelled, "linesWithoutRule", noRule)));
        return Obj.of("from", start, "to", end, "created", created, "cancelled", cancelled, "linesWithoutRule", noRule);
    }

    /** Pure rule: percentage of the net base, rounded half up to cents. */
    public static BigDecimal commission(BigDecimal base, BigDecimal ratePercent) {
        return base.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> rule(String employeeId, String productId, String categoryId, LocalDate on) {
        return db.row("""
                SELECT id, "ratePercent" FROM "CommissionRule"
                 WHERE "organizationId" = ? AND "isActive"
                   AND ("validFrom" IS NULL OR "validFrom" <= ?) AND ("validTo" IS NULL OR "validTo" >= ?)
                   AND ("employeeId" IS NULL OR "employeeId" = ?)
                   AND (("productId" IS NULL AND "productCategoryId" IS NULL) OR "productId" = ? OR "productCategoryId" = ?)
                 ORDER BY priority DESC, CASE WHEN "employeeId" IS NOT NULL THEN 1 ELSE 0 END DESC,
                          CASE WHEN "productId" IS NOT NULL THEN 2 WHEN "productCategoryId" IS NOT NULL THEN 1 ELSE 0 END DESC
                 LIMIT 1""", org(), java.sql.Date.valueOf(on), java.sql.Date.valueOf(on), employeeId,
                productId == null ? "" : productId, categoryId == null ? "" : categoryId);
    }

    // ------------------------------------------------------------------ entries

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> entries(String status, String employeeId, LocalDate from, LocalDate to, int page,
            int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(200, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!blank(status)) {
            where.append(" AND c.status = ?");
            params.add(status);
        }
        if (!blank(employeeId)) {
            where.append(" AND c.\"employeeId\" = ?");
            params.add(employeeId);
        }
        if (from != null) {
            where.append(" AND c.\"earnedOn\" >= ?");
            params.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            where.append(" AND c.\"earnedOn\" <= ?");
            params.add(java.sql.Date.valueOf(to));
        }
        long total = db.count("SELECT count(*) FROM \"CommissionEntry\" c" + where, params);
        List<Map<String, Object>> rows = db.rows("SELECT c.*, e.\"firstName\", e.\"lastName\", p.name AS \"productName\""
                + " FROM \"CommissionEntry\" c LEFT JOIN \"HrEmployee\" e ON e.id = c.\"employeeId\" LEFT JOIN \"Product\" p"
                + " ON p.id = c.\"productId\"" + where + " ORDER BY c.\"earnedOn\" DESC, c.\"createdAt\" DESC LIMIT " + size
                + " OFFSET " + (long) (p - 1) * size, params);
        return PageResult.of(rows, p, size, total);
    }

    /** Totals per salesperson and status. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> summary(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.of(2000, 1, 1);
        LocalDate end = to != null ? to : LocalDate.of(2999, 12, 31);
        return db.rows("""
                SELECT c."employeeId", e."firstName", e."lastName",
                       COALESCE(sum(c."baseAmount") FILTER (WHERE c.status <> 'cancelled'), 0) AS sales,
                       COALESCE(sum(c.amount) FILTER (WHERE c.status = 'accrued'), 0) AS accrued,
                       COALESCE(sum(c.amount) FILTER (WHERE c.status = 'approved'), 0) AS approved,
                       COALESCE(sum(c.amount) FILTER (WHERE c.status = 'exported'), 0) AS exported
                  FROM "CommissionEntry" c LEFT JOIN "HrEmployee" e ON e.id = c."employeeId"
                 WHERE c."organizationId" = ? AND c."earnedOn" >= ? AND c."earnedOn" <= ?
                 GROUP BY 1, 2, 3 ORDER BY 2, 3""", org(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    /** Approve accrued entries. The salesperson's own linked user is refused. */
    @Transactional
    public Map<String, Object> approve(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ApiException.badRequest("ids should not be empty");
        }
        int done = 0;
        for (String id : ids) {
            Map<String, Object> e = db.require("CommissionEntry", id, "Commission entry not found");
            if (!"accrued".equals(e.get("status"))) {
                throw ApiException.conflict("Only accrued entries can be approved");
            }
            Map<String, Object> owner = db.row("SELECT \"userId\" FROM \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ?",
                    e.get("employeeId"), org());
            if (owner != null && owner.get("userId") != null && owner.get("userId").equals(user())) {
                throw ApiException.forbidden("You cannot approve your own commission");
            }
            db.update("UPDATE \"CommissionEntry\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = " + CrmDb.NOW
                    + " WHERE id = ? AND \"organizationId\" = ?", user(), id, org());
            audit.recordInTx(AuditInput.of("CommissionEntry", id, "approve"));
            done++;
        }
        return Obj.of("approved", done);
    }

    /** Send approved entries to payroll as one taxable earning per employee. */
    @Transactional
    public Map<String, Object> exportToPayroll(LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(BusinessZone.get());
        int inputs = 0;
        int entries = 0;
        for (Map<String, Object> g : db.rows("SELECT \"employeeId\", sum(amount) AS total, count(*) AS n FROM"
                + " \"CommissionEntry\" WHERE \"organizationId\" = ? AND status = 'approved' AND \"earnedOn\" <= ?"
                + " GROUP BY 1", org(), java.sql.Date.valueOf(end))) {
            String employee = str(g.get("employeeId"));
            String batch = UUID.randomUUID().toString();
            Map<String, Object> input = payrollInputs.addEarning(employee, "Sales commission to " + end,
                    dec(g.get("total")), end, true, null, PAYROLL_SOURCE, batch);
            entries += db.update("UPDATE \"CommissionEntry\" SET status = 'exported', \"payrollInputId\" = ? WHERE"
                    + " \"organizationId\" = ? AND status = 'approved' AND \"employeeId\" = ? AND \"earnedOn\" <= ?",
                    input.get("id"), org(), employee, java.sql.Date.valueOf(end));
            audit.recordInTx(AuditInput.of("CommissionEntry", batch, "post", null, Obj.of("employeeId", employee,
                    "total", g.get("total"), "payrollInputId", input.get("id"))));
            inputs++;
        }
        return Obj.of("payrollInputs", inputs, "entries", entries);
    }
}
