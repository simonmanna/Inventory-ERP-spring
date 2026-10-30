package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.hr.application.HrPayrollInputService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales commissions and targets (ADR-S011 phase S9).
 *
 * <p>Commission rules match a revenue line by salesperson, team (of the sales order) and product category; the
 * matching rule with the highest priority — then the most specific — wins, and pays on its own basis: {@code invoiced}
 * (documents issued in the month) or {@code paid} (invoice lines pro rata to the payments allocated in the month).
 * Methods: percent of revenue, percent of margin (revenue − product cost) or an amount per unit. Credit notes count
 * negative, so returns claw commission back in the month they are issued.
 *
 * <p>A monthly statement per salesperson is a draft until approved; approval queues the total as a taxable payroll
 * earning of the salesperson's employee record (HR payroll input, debit Sales Commissions expense). A statement whose
 * total is not positive is approved without a payroll input (the clawback stays on record).
 *
 * <p>Targets are monthly amounts (optionally quantities) per salesperson / team / branch / product category, compared
 * with the net revenue lines of the month.
 */
@Service
public class SalesCommissionService {

    private final SalesDb db;
    private final SalesRevenueQuery revenue;
    private final HrPayrollInputService payroll;
    private final AccountResolverService accounts;
    private final SequenceService sequence;
    private final AuditService audit;

    public SalesCommissionService(SalesDb db, SalesRevenueQuery revenue, HrPayrollInputService payroll,
            AccountResolverService accounts, SequenceService sequence, AuditService audit) {
        this.db = db;
        this.revenue = revenue;
        this.payroll = payroll;
        this.accounts = accounts;
        this.sequence = sequence;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules() {
        return db.rows("SELECT r.*, trim(u.\"firstName\" || ' ' || COALESCE(u.\"lastName\", '')) AS \"salespersonName\", t.name AS \"teamName\", c.name AS"
                + " \"productCategoryName\" FROM \"CommissionRule\" r LEFT JOIN \"User\" u ON u.id = r.\"salespersonId\""
                + " LEFT JOIN \"CrmTeam\" t ON t.id = r.\"teamId\" LEFT JOIN \"ProductCategory\" c ON c.id ="
                + " r.\"productCategoryId\" WHERE r.\"organizationId\" = ? ORDER BY r.priority DESC, r.name", org());
    }

    @Transactional
    public Map<String, Object> saveRule(String id, Map<String, Object> in) {
        if (id != null) {
            db.require("CommissionRule", id, "Commission rule not found");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("name")) {
            if (blank(str(in.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            d.put("name", str(in.get("name")));
        }
        for (String k : List.of("salespersonId", "teamId", "productCategoryId")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        oneOf(in, d, "basis", Set.of("invoiced", "paid"), id == null ? "invoiced" : null);
        oneOf(in, d, "method", Set.of("percent_revenue", "percent_margin", "per_unit"), id == null ? "percent_revenue"
                : null);
        if (id == null || in.containsKey("rate")) {
            BigDecimal rate = dec(in.get("rate"));
            if (in.get("rate") == null || rate.signum() < 0) {
                throw ApiException.badRequest("rate must not be less than 0");
            }
            d.put("rate", rate);
        }
        if (in.containsKey("priority")) {
            d.put("priority", in.get("priority") instanceof Number n ? n.intValue() : 0);
        }
        for (String k : List.of("validFrom", "validTo")) {
            if (in.containsKey(k)) {
                d.put(k, SalesDb.date(str(in.get(k))));
            }
        }
        if (in.containsKey("isActive")) {
            d.put("isActive", !Boolean.FALSE.equals(in.get("isActive")));
        }
        Map<String, Object> row = id == null ? db.insert("CommissionRule", d) : db.patch("CommissionRule", id, d, true);
        audit.record(AuditInput.of("CommissionRule", str(row.get("id")), id == null ? "create" : "update", null,
                Obj.of("fields", d.keySet())));
        return row;
    }

    private static void oneOf(Map<String, Object> in, Map<String, Object> d, String key, Set<String> allowed,
            String fallback) {
        if (in.containsKey(key) || fallback != null) {
            String v = in.get(key) == null ? fallback : str(in.get(key));
            if (!allowed.contains(v)) {
                throw ApiException.badRequest(key + " must be one of: " + String.join(", ", new java.util.TreeSet<>(
                        allowed)));
            }
            d.put(key, v);
        }
    }

    // ------------------------------------------------------------------ computation

    /** Commission lines of {@code salespersonId} (null = everyone) for {@code [from, to]}. */
    List<Map<String, Object>> compute(String salespersonId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rules = db.rows("SELECT * FROM \"CommissionRule\" WHERE \"organizationId\" = ? AND"
                + " \"isActive\" AND (\"validFrom\" IS NULL OR \"validFrom\" <= ?) AND (\"validTo\" IS NULL OR \"validTo\""
                + " >= ?) ORDER BY priority DESC", org(), to, from);
        List<Map<String, Object>> source = new ArrayList<>(revenue.invoiced(from, to));
        source.addAll(revenue.paid(from, to));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : source) {
            String sp = str(l.get("salespersonId"));
            if (sp == null || salespersonId != null && !salespersonId.equals(sp)) {
                continue;
            }
            Map<String, Object> rule = best(rules, l);
            if (rule == null || !rule.get("basis").equals(l.get("basis"))) {
                continue;
            }
            BigDecimal rev = dec(l.get("revenue"));
            BigDecimal qty = dec(l.get("quantity"));
            BigDecimal margin = rev.subtract(dec(l.get("cost")));
            BigDecimal rate = dec(rule.get("rate"));
            BigDecimal amount = switch (str(rule.get("method"))) {
                case "per_unit" -> qty.multiply(rate);
                case "percent_margin" -> margin.multiply(rate).divide(SalesPricingEngine.HUNDRED, 10, RoundingMode.HALF_UP);
                default -> rev.multiply(rate).divide(SalesPricingEngine.HUNDRED, 10, RoundingMode.HALF_UP);
            };
            Map<String, Object> m = Obj.of("salespersonId", sp, "documentId", l.get("documentId"), "number",
                    l.get("number"), "type", l.get("type"), "date", l.get("date"), "partnerId", l.get("partnerId"),
                    "productId", l.get("productId"), "description", l.get("description"));
            m.put("basis", l.get("basis"));
            m.put("quantity", JsNumbers.js(qty.setScale(6, RoundingMode.HALF_UP)));
            m.put("revenue", JsNumbers.js(rev.setScale(2, RoundingMode.HALF_UP)));
            m.put("margin", JsNumbers.js(margin.setScale(2, RoundingMode.HALF_UP)));
            m.put("ruleId", rule.get("id"));
            m.put("ruleName", rule.get("name"));
            m.put("method", rule.get("method"));
            m.put("rate", rule.get("rate"));
            m.put("amount", JsNumbers.js(amount.setScale(2, RoundingMode.HALF_UP)));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> best(List<Map<String, Object>> rules, Map<String, Object> l) {
        Map<String, Object> best = null;
        int bestPriority = Integer.MIN_VALUE;
        int bestSpecific = -1;
        for (Map<String, Object> r : rules) {
            if (!eqOrNull(r.get("salespersonId"), l.get("salespersonId")) || !eqOrNull(r.get("teamId"), l.get("teamId"))
                    || !eqOrNull(r.get("productCategoryId"), l.get("productCategoryId"))) {
                continue;
            }
            int priority = ((Number) r.get("priority")).intValue();
            int specific = (r.get("salespersonId") == null ? 0 : 1) + (r.get("teamId") == null ? 0 : 1)
                    + (r.get("productCategoryId") == null ? 0 : 1);
            if (priority > bestPriority || priority == bestPriority && specific > bestSpecific) {
                best = r;
                bestPriority = priority;
                bestSpecific = specific;
            }
        }
        return best;
    }

    private static boolean eqOrNull(Object want, Object have) {
        return want == null || want.equals(have);
    }

    /** {@code GET /sales/commissions/preview?month=YYYY-MM[&salespersonId]}: lines and totals, nothing saved. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String month, String salespersonId) {
        LocalDate from = month(month);
        List<Map<String, Object>> lines = compute(salespersonId, from, from.plusMonths(1).minusDays(1));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            totals.merge(str(l.get("salespersonId")), dec(l.get("amount")), BigDecimal::add);
        }
        List<Map<String, Object>> bySp = new ArrayList<>();
        totals.forEach((k, v) -> bySp.add(Obj.of("salespersonId", k, "total", JsNumbers.js(v))));
        return Obj.of("month", from, "salespeople", bySp, "lines", lines);
    }

    static LocalDate month(String month) {
        if (blank(month)) {
            return LocalDate.now().withDayOfMonth(1);
        }
        try {
            return LocalDate.parse(month.length() == 7 ? month + "-01" : month.substring(0, 10)).withDayOfMonth(1);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid month: " + month);
        }
    }

    // ------------------------------------------------------------------ statements

    @Transactional(readOnly = true)
    public List<Map<String, Object>> statements(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder sql = new StringBuilder("SELECT s.id, s.number, s.\"salespersonId\", trim(u.\"firstName\" || ' ' || COALESCE(u.\"lastName\", '')) AS"
                + " \"salespersonName\", s.\"periodStart\", s.\"periodEnd\", s.status, s.total, s.\"payrollInputId\","
                + " s.\"approvedAt\", s.\"createdAt\" FROM \"CommissionStatement\" s LEFT JOIN \"User\" u ON u.id ="
                + " s.\"salespersonId\" WHERE s.\"organizationId\" = ?");
        if (!TenantContext.has(Permissions.SALES__COMMISSION_MANAGE)) {
            sql.append(" AND s.\"salespersonId\" = ?");
            params.add(user());
        } else if (!blank(q.get("salespersonId"))) {
            sql.append(" AND s.\"salespersonId\" = ?");
            params.add(q.get("salespersonId"));
        }
        if (!blank(q.get("status"))) {
            sql.append(" AND s.status = ?");
            params.add(q.get("status"));
        }
        sql.append(" ORDER BY s.\"periodStart\" DESC, s.number DESC");
        return db.rows(sql.toString(), params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> statement(String id) {
        Map<String, Object> s = db.row("SELECT s.*, trim(u.\"firstName\" || ' ' || COALESCE(u.\"lastName\", '')) AS \"salespersonName\" FROM \"CommissionStatement\" s"
                + " LEFT JOIN \"User\" u ON u.id = s.\"salespersonId\" WHERE s.id = ? AND s.\"organizationId\" = ?", id,
                org());
        if (s == null || !TenantContext.has(Permissions.SALES__COMMISSION_MANAGE) && !str(s.get("salespersonId"))
                .equals(user())) {
            throw ApiException.notFound("Commission statement not found");
        }
        return s;
    }

    /**
     * Draft statements of {@code month} for {@code salespersonId} (or every salesperson with commission lines). An
     * existing draft of the month is recomputed; an approved one is left alone.
     */
    @Transactional
    public List<Map<String, Object>> generate(String month, String salespersonId) {
        LocalDate from = month(month);
        LocalDate to = from.plusMonths(1).minusDays(1);
        List<Map<String, Object>> lines = compute(salespersonId, from, to);
        Set<String> people = new LinkedHashSet<>();
        if (salespersonId != null) {
            people.add(salespersonId);
        }
        lines.forEach(l -> people.add(str(l.get("salespersonId"))));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sp : people) {
            List<Map<String, Object>> mine = lines.stream().filter(l -> sp.equals(l.get("salespersonId"))).toList();
            BigDecimal total = mine.stream().map(l -> dec(l.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> existing = db.row("SELECT * FROM \"CommissionStatement\" WHERE \"organizationId\" = ? AND"
                    + " \"salespersonId\" = ? AND \"periodStart\" = ? AND status <> 'cancelled' FOR UPDATE", org(), sp, from);
            String id;
            if (existing == null) {
                int year = from.getYear();
                id = str(db.insert("CommissionStatement", Obj.of("number", sequence.next("commission_statement:" + year,
                        "COM-" + year + "-", 5), "salespersonId", sp, "periodStart", from, "periodEnd", to, "total", total,
                        "createdById", user())).get("id"));
            } else if ("draft".equals(existing.get("status"))) {
                id = str(existing.get("id"));
                db.patch("CommissionStatement", id, Obj.of("total", total), false);
            } else {
                out.add(existing);
                continue;
            }
            db.update("UPDATE \"CommissionStatement\" SET lines = ?::jsonb WHERE id = ?", SalesDb.toJson(mine), id);
            out.add(db.row("SELECT * FROM \"CommissionStatement\" WHERE id = ?", id));
        }
        return out;
    }

    /** Approve a draft statement; a positive total becomes a payroll earning of the salesperson's employee. */
    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> s = db.row("SELECT * FROM \"CommissionStatement\" WHERE id = ? AND \"organizationId\" = ? FOR"
                + " UPDATE", id, org());
        if (s == null) {
            throw ApiException.notFound("Commission statement not found");
        }
        if (!"draft".equals(s.get("status"))) {
            throw ApiException.badRequest("Only draft statements can be approved");
        }
        if (str(s.get("salespersonId")).equals(user())) {
            throw ApiException.forbidden("You cannot approve your own commission statement");
        }
        BigDecimal total = dec(s.get("total"));
        String inputId = null;
        if (total.signum() > 0) {
            Map<String, Object> emp = db.row("SELECT id FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"userId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND \"isActive\" ORDER BY \"createdAt\" LIMIT 1", org(),
                    s.get("salespersonId"));
            if (emp == null) {
                throw ApiException.badRequest("The salesperson has no active employee record; link the user to an"
                        + " employee before approving");
            }
            String expense = accounts.ensureByCode("6150", "Sales Commissions", "operating_expense",
                    "sales_commission_expense");
            Map<String, Object> input = payroll.addEarning(str(emp.get("id")), "Sales commission " + s.get("number"),
                    total, (LocalDate) s.get("periodEnd"), true, expense, "sales_commission", id);
            inputId = str(input.get("id"));
        }
        db.patch("CommissionStatement", id, Obj.of("status", "approved", "approvedById", user(), "approvedAt",
                Instant.now(), "payrollInputId", inputId), false);
        audit.record(AuditInput.of("CommissionStatement", id, "approve", null, Obj.of("total", total, "payrollInputId",
                inputId)));
        return statement(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> s = db.require("CommissionStatement", id, "Commission statement not found");
        if (!"draft".equals(s.get("status"))) {
            throw ApiException.badRequest("Only draft statements can be cancelled");
        }
        db.patch("CommissionStatement", id, Obj.of("status", "cancelled"), false);
        audit.record(AuditInput.of("CommissionStatement", id, "update", null, Obj.of("status", "cancelled")));
        return statement(id);
    }

    // ------------------------------------------------------------------ targets

    @Transactional(readOnly = true)
    public List<Map<String, Object>> targets(String month) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String filter = "";
        if (!blank(month)) {
            filter = " AND t.month = ?";
            params.add(month(month));
        }
        return db.rows("SELECT t.*, trim(u.\"firstName\" || ' ' || COALESCE(u.\"lastName\", '')) AS \"salespersonName\", tm.name AS \"teamName\", b.name AS"
                + " \"branchName\", c.name AS \"productCategoryName\" FROM \"SalesTarget\" t LEFT JOIN \"User\" u ON u.id ="
                + " t.\"salespersonId\" LEFT JOIN \"CrmTeam\" tm ON tm.id = t.\"teamId\" LEFT JOIN \"Branch\" b ON b.id ="
                + " t.\"branchId\" LEFT JOIN \"ProductCategory\" c ON c.id = t.\"productCategoryId\" WHERE"
                + " t.\"organizationId\" = ?" + filter + " ORDER BY t.month DESC, trim(u.\"firstName\" || ' ' || COALESCE(u.\"lastName\", '')), tm.name", params);
    }

    @Transactional
    public Map<String, Object> saveTarget(String id, Map<String, Object> in) {
        if (id != null) {
            db.require("SalesTarget", id, "Sales target not found");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("month")) {
            d.put("month", month(str(in.get("month"))));
        }
        for (String k : List.of("salespersonId", "teamId", "branchId", "productCategoryId")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (id == null || in.containsKey("amount")) {
            BigDecimal amount = dec(in.get("amount"));
            if (in.get("amount") == null || amount.signum() < 0) {
                throw ApiException.badRequest("amount must not be less than 0");
            }
            d.put("amount", amount);
        }
        if (in.containsKey("quantity")) {
            d.put("quantity", SalesDb.decOrNull(in.get("quantity")));
        }
        Map<String, Object> row = id == null ? db.insert("SalesTarget", d) : db.patch("SalesTarget", id, d, false);
        audit.record(AuditInput.of("SalesTarget", str(row.get("id")), id == null ? "create" : "update", null,
                Obj.of("fields", d.keySet())));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteTarget(String id) {
        db.require("SalesTarget", id, "Sales target not found");
        db.update("DELETE FROM \"SalesTarget\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("SalesTarget", id, "delete"));
        return Obj.of("ok", true);
    }

    /** Targets of {@code month} with actual net revenue / quantity and achievement %. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> achievement(String month) {
        LocalDate from = month(month);
        List<Map<String, Object>> lines = revenue.invoiced(from, from.plusMonths(1).minusDays(1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : targets(from.toString())) {
            BigDecimal amount = BigDecimal.ZERO;
            BigDecimal qty = BigDecimal.ZERO;
            for (Map<String, Object> l : lines) {
                if (eqOrNull(t.get("salespersonId"), l.get("salespersonId")) && eqOrNull(t.get("teamId"), l.get("teamId"))
                        && eqOrNull(t.get("branchId"), l.get("branchId"))
                        && eqOrNull(t.get("productCategoryId"), l.get("productCategoryId"))) {
                    amount = amount.add(dec(l.get("revenue")));
                    qty = qty.add(dec(l.get("quantity")));
                }
            }
            BigDecimal target = dec(t.get("amount"));
            t.put("actualAmount", JsNumbers.js(amount.setScale(2, RoundingMode.HALF_UP)));
            t.put("actualQuantity", JsNumbers.js(qty));
            t.put("achievementPercent", target.signum() == 0 ? null : JsNumbers.js(amount.multiply(
                    SalesPricingEngine.HUNDRED).divide(target, 1, RoundingMode.HALF_UP)));
            out.add(t);
        }
        return out;
    }
}
