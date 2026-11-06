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
    private SalesSettingsService settings;
    private SalesTargetService targetsSvc;

    @org.springframework.beans.factory.annotation.Autowired
    void setCollaborators(SalesSettingsService settings, @org.springframework.context.annotation.Lazy SalesTargetService targetsSvc) {
        this.settings = settings;
        this.targetsSvc = targetsSvc;
    }

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
                + " \"productCategoryName\" FROM \"SalesCommissionRule\" r LEFT JOIN \"User\" u ON u.id = r.\"salespersonId\""
                + " LEFT JOIN \"CrmTeam\" t ON t.id = r.\"teamId\" LEFT JOIN \"ProductCategory\" c ON c.id ="
                + " r.\"productCategoryId\" WHERE r.\"organizationId\" = ? ORDER BY r.priority DESC, r.name", org());
    }

    @Transactional
    public Map<String, Object> saveRule(String id, Map<String, Object> in) {
        if (id != null) {
            db.require("SalesCommissionRule", id, "Commission rule not found");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("name")) {
            if (blank(str(in.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            d.put("name", str(in.get("name")));
        }
        for (String k : List.of("salespersonId", "teamId", "productCategoryId", "productId", "partnerId", "partnerCategoryId")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        oneOf(in, d, "basis", Set.of("invoiced", "paid"), id == null ? str(settings.settings().get("commissionDefaultBasis")) : null);
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
        Map<String, Object> row = id == null ? db.insert("SalesCommissionRule", d) : db.patch("SalesCommissionRule", id, d, true);
        String tiers = in.containsKey("tiers") ? validTiers(in.get("tiers")) : null;
        String splits = in.containsKey("splits") ? validSplits(in.get("splits")) : null;
        if (in.containsKey("tiers")) {
            db.update("UPDATE \"SalesCommissionRule\" SET tiers = ?::jsonb WHERE id = ?", tiers, row.get("id"));
        }
        if (in.containsKey("splits")) {
            db.update("UPDATE \"SalesCommissionRule\" SET splits = ?::jsonb WHERE id = ?", splits, row.get("id"));
        }
        audit.record(AuditInput.of("SalesCommissionRule", str(row.get("id")), id == null ? "create" : "update", null,
                Obj.of("fields", d.keySet())));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static String validTiers(Object raw) {
        List<Map<String, Object>> tiers = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (tiers.isEmpty()) {
            return null;
        }
        BigDecimal last = BigDecimal.valueOf(-1);
        for (Map<String, Object> t : tiers) {
            BigDecimal from = dec(t.get("from"));
            if (from.compareTo(last) <= 0 || dec(t.get("rate")).signum() < 0) {
                throw ApiException.badRequest("Tiers need increasing \"from\" amounts and rates of 0 or more");
            }
            last = from;
        }
        if (dec(tiers.get(0).get("from")).signum() != 0) {
            throw ApiException.badRequest("The first tier must start from 0");
        }
        return SalesDb.toJson(tiers);
    }

    @SuppressWarnings("unchecked")
    private static String validSplits(Object raw) {
        List<Map<String, Object>> splits = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (splits.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> s : splits) {
            if (blank(str(s.get("salespersonId"))) || dec(s.get("percent")).signum() <= 0) {
                throw ApiException.badRequest("Each split needs a salesperson and a percent");
            }
            sum = sum.add(dec(s.get("percent")));
        }
        if (sum.compareTo(SalesPricingEngine.HUNDRED) > 0) {
            throw ApiException.badRequest("Splits cannot add up to more than 100%");
        }
        return SalesDb.toJson(splits);
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
        List<Map<String, Object>> rules = db.rows("SELECT * FROM \"SalesCommissionRule\" WHERE \"organizationId\" = ? AND"
                + " \"isActive\" AND (\"validFrom\" IS NULL OR \"validFrom\" <= ?) AND (\"validTo\" IS NULL OR \"validTo\""
                + " >= ?) ORDER BY priority DESC", org(), to, from);
        List<Map<String, Object>> source = new ArrayList<>(revenue.invoiced(from, to));
        source.addAll(revenue.paid(from, to));
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, BigDecimal> running = new LinkedHashMap<>();
        for (Map<String, Object> l : source) {
            String sp = str(l.get("salespersonId"));
            if (sp == null) {
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
                case "percent_margin" -> tiered(rule, margin, rate, running, sp);
                default -> tiered(rule, rev, rate, running, sp);
            };
            for (Map<String, Object> share : shares(rule, sp, amount)) {
                String earner = str(share.get("salespersonId"));
                if (salespersonId != null && !salespersonId.equals(earner)) {
                    continue;
                }
                out.add(line(l, rule, earner, sp, qty, rev, margin, (BigDecimal) share.get("amount")));
            }
        }
        return out;
    }

    /** SG-11: the amount, at the rate or through the rule's marginal tiers on this salesperson's running total. */
    private static BigDecimal tiered(Map<String, Object> rule, BigDecimal base, BigDecimal rate, Map<String, BigDecimal> running, String sp) {
        List<Map<String, Object>> tiers = tierList(rule.get("tiers"));
        if (tiers.isEmpty()) {
            return base.multiply(rate).divide(SalesPricingEngine.HUNDRED, 10, RoundingMode.HALF_UP);
        }
        String key = sp + "|" + rule.get("id");
        BigDecimal before = running.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal after = before.add(base);
        running.put(key, after);
        return tierTotal(tiers, after).subtract(tierTotal(tiers, before));
    }

    private static BigDecimal tierTotal(List<Map<String, Object>> tiers, BigDecimal total) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal from = dec(tiers.get(i).get("from"));
            BigDecimal to = i + 1 < tiers.size() ? dec(tiers.get(i + 1).get("from")) : null;
            BigDecimal top = to == null ? total : total.min(to);
            if (top.compareTo(from) > 0) {
                sum = sum.add(top.subtract(from).multiply(dec(tiers.get(i).get("rate"))).divide(SalesPricingEngine.HUNDRED, 10, RoundingMode.HALF_UP));
            }
        }
        return sum;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tierList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return SalesDb.jsonList(raw);
    }

    /** SG-11: who earns what. Each split takes its percent; the salesperson on the document keeps the rest. */
    private static List<Map<String, Object>> shares(Map<String, Object> rule, String sp, BigDecimal amount) {
        List<Map<String, Object>> splits = tierList(rule.get("splits"));
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal left = amount;
        for (Map<String, Object> s : splits) {
            BigDecimal part = amount.multiply(dec(s.get("percent"))).divide(SalesPricingEngine.HUNDRED, 10, RoundingMode.HALF_UP);
            left = left.subtract(part);
            out.add(Obj.of("salespersonId", str(s.get("salespersonId")), "amount", part));
        }
        if (left.signum() != 0 || splits.isEmpty()) {
            out.add(Obj.of("salespersonId", sp, "amount", left));
        }
        return out;
    }

    private static Map<String, Object> line(Map<String, Object> l, Map<String, Object> rule, String to, String sp, BigDecimal qty, BigDecimal rev,
            BigDecimal margin, BigDecimal amount) {
            Map<String, Object> m = Obj.of("salespersonId", to, "documentId", l.get("documentId"), "number",
                    l.get("number"), "type", l.get("type"), "date", l.get("date"), "partnerId", l.get("partnerId"),
                    "productId", l.get("productId"), "description", l.get("description"));
            m.put("basis", l.get("basis"));
            m.put("paymentId", l.get("paymentId"));
            m.put("splitFrom", to.equals(sp) ? null : sp);
            m.put("quantity", JsNumbers.js(qty.setScale(6, RoundingMode.HALF_UP)));
            m.put("revenue", JsNumbers.js(rev.setScale(2, RoundingMode.HALF_UP)));
            m.put("margin", JsNumbers.js(margin.setScale(2, RoundingMode.HALF_UP)));
            m.put("ruleId", rule.get("id"));
            m.put("ruleName", rule.get("name"));
            m.put("method", rule.get("method"));
            m.put("rate", rule.get("rate"));
            m.put("amount", JsNumbers.js(amount.setScale(2, RoundingMode.HALF_UP)));
            return m;
    }

    private static Map<String, Object> best(List<Map<String, Object>> rules, Map<String, Object> l) {
        Map<String, Object> best = null;
        int bestPriority = Integer.MIN_VALUE;
        int bestSpecific = -1;
        for (Map<String, Object> r : rules) {
            if (!eqOrNull(r.get("salespersonId"), l.get("salespersonId")) || !eqOrNull(r.get("teamId"), l.get("teamId"))
                    || !eqOrNull(r.get("productCategoryId"), l.get("productCategoryId")) || !eqOrNull(r.get("productId"), l.get("productId"))
                    || !eqOrNull(r.get("partnerId"), l.get("partnerId")) || !eqOrNull(r.get("partnerCategoryId"), l.get("partnerCategoryId"))) {
                continue;
            }
            int priority = ((Number) r.get("priority")).intValue();
            int specific = (r.get("salespersonId") == null ? 0 : 1) + (r.get("teamId") == null ? 0 : 1)
                    + (r.get("productCategoryId") == null ? 0 : 1) + (r.get("productId") == null ? 0 : 2)
                    + (r.get("partnerId") == null ? 0 : 2) + (r.get("partnerCategoryId") == null ? 0 : 1);
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
        s.put("adjustments", db.rows("SELECT * FROM \"CommissionAdjustment\" WHERE \"statementId\" = ? ORDER BY \"createdAt\"", id));
        s.put("disputes", db.rows("SELECT * FROM \"CommissionDispute\" WHERE \"statementId\" = ? ORDER BY \"createdAt\"", id));
        return s;
    }

    private static final String UNCONSUMED_CARRY = """
            SELECT s.id, s."salespersonId", s.total FROM "CommissionStatement" s
             WHERE s."organizationId" = ? AND s.status IN ('approved', 'paid') AND s.total < 0 AND s."periodStart" < ?
               AND NOT EXISTS (SELECT 1 FROM "CommissionStatement" n WHERE n."organizationId" = s."organizationId" AND n.status IN ('approved', 'paid')
                                  AND s.id = ANY(n."carriedFromIds"))""";

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
        // SG-11: whoever ended a period negative carries it into the next statement, even with no sales this month.
        for (Map<String, Object> c : db.rows(UNCONSUMED_CARRY, org(), from)) {
            if (salespersonId == null || salespersonId.equals(c.get("salespersonId"))) {
                people.add(str(c.get("salespersonId")));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sp : people) {
            List<Map<String, Object>> mine = lines.stream().filter(l -> sp.equals(l.get("salespersonId"))).toList();
            BigDecimal linesTotal = mine.stream().map(l -> dec(l.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal carried = BigDecimal.ZERO;
            List<String> carriedFrom = new ArrayList<>();
            for (Map<String, Object> c : db.rows(UNCONSUMED_CARRY, org(), from)) {
                if (sp.equals(c.get("salespersonId"))) {
                    carried = carried.add(dec(c.get("total")));
                    carriedFrom.add(str(c.get("id")));
                }
            }
            BigDecimal total = linesTotal.add(carried);
            Map<String, Object> existing = db.row("SELECT * FROM \"CommissionStatement\" WHERE \"organizationId\" = ? AND"
                    + " \"salespersonId\" = ? AND \"periodStart\" = ? AND status <> 'cancelled' FOR UPDATE", org(), sp, from);
            String id;
            if (existing == null) {
                int year = from.getYear();
                id = str(db.insert("CommissionStatement", Obj.of("number", sequence.next("commission_statement:" + year,
                        "COM-" + year + "-", 5), "salespersonId", sp, "periodStart", from, "periodEnd", to, "total", total,
                        "linesTotal", linesTotal, "carriedIn", carried, "createdById", user())).get("id"));
            } else if ("draft".equals(existing.get("status"))) {
                id = str(existing.get("id"));
                total = total.add(dec(existing.get("adjustmentTotal")));
                db.patch("CommissionStatement", id, Obj.of("total", total, "linesTotal", linesTotal, "carriedIn", carried), false);
            } else {
                out.add(existing);
                continue;
            }
            db.update("UPDATE \"CommissionStatement\" SET lines = ?::jsonb, \"carriedFromIds\" = ?::text[] WHERE id = ?", SalesDb.toJson(mine),
                    carriedFrom.toArray(String[]::new), id);
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
        if (db.count("SELECT count(*) FROM \"CommissionDispute\" WHERE \"statementId\" = ? AND status = 'open'", List.of(id)) > 0) {
            throw ApiException.badRequest("Resolve the open disputes on this statement first");
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

    /** A manual line on a draft statement, with its reason; regenerating keeps it. */
    @Transactional
    public Map<String, Object> addAdjustment(String id, Map<String, Object> in) {
        Map<String, Object> s = db.row("SELECT * FROM \"CommissionStatement\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
        if (s == null) {
            throw ApiException.notFound("Commission statement not found");
        }
        if (!"draft".equals(s.get("status"))) {
            throw ApiException.badRequest("Only draft statements can be adjusted");
        }
        String reason = str(in.get("reason"));
        BigDecimal amount = dec(in.get("amount"));
        if (blank(reason) || amount.signum() == 0) {
            throw ApiException.badRequest("An adjustment needs an amount and a reason");
        }
        db.insert("CommissionAdjustment", Obj.of("statementId", id, "amount", amount, "reason", reason, "createdById", user()));
        db.update("UPDATE \"CommissionStatement\" SET total = total + ?, \"adjustmentTotal\" = \"adjustmentTotal\" + ? WHERE id = ?", amount, amount, id);
        audit.record(AuditInput.of("CommissionStatement", id, "update", null, Obj.of("adjustment", amount, "reason", reason)));
        return statement(id);
    }

    /** The salesperson or a manager disputes a statement; it cannot be approved until resolved. */
    @Transactional
    public Map<String, Object> dispute(String id, Map<String, Object> in) {
        statement(id);
        String reason = str(in.get("reason"));
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        db.insert("CommissionDispute", Obj.of("statementId", id, "reason", reason, "raisedById", user()));
        return statement(id);
    }

    @Transactional
    public Map<String, Object> resolveDispute(String disputeId, Map<String, Object> in) {
        String resolution = str(in.get("resolution"));
        if (blank(resolution)) {
            throw ApiException.badRequest("resolution should not be empty");
        }
        Map<String, Object> d = db.row("SELECT \"statementId\" FROM \"CommissionDispute\" WHERE id = ? AND \"organizationId\" = ?", disputeId, org());
        if (d == null) {
            throw ApiException.notFound("Dispute not found");
        }
        db.update("UPDATE \"CommissionDispute\" SET status = 'resolved', resolution = ?, \"resolvedById\" = ?, \"resolvedAt\" = now() WHERE id = ?", resolution, user(),
                disputeId);
        return statement(str(d.get("statementId")));
    }

    /** Record the payout. Only an approved statement can be paid, so nothing leaves before approval. */
    @Transactional
    public Map<String, Object> markPaid(String id, Map<String, Object> in) {
        Map<String, Object> s = db.row("SELECT * FROM \"CommissionStatement\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
        if (s == null) {
            throw ApiException.notFound("Commission statement not found");
        }
        if (!"approved".equals(s.get("status"))) {
            throw ApiException.badRequest("Only approved statements can be marked paid");
        }
        if (dec(s.get("total")).signum() <= 0) {
            throw ApiException.badRequest("This statement has nothing to pay");
        }
        db.update("UPDATE \"CommissionStatement\" SET status = 'paid', \"paidAt\" = now(), \"paidById\" = ?, \"paymentReference\" = ? WHERE id = ?", user(),
                str(in.get("reference")), id);
        audit.record(AuditInput.of("CommissionStatement", id, "update", null, Obj.of("status", "paid")));
        return statement(id);
    }

    /** The statement as a PDF: its lines, adjustments and what was carried in. */
    @Transactional(readOnly = true)
    public byte[] pdf(String id) {
        Map<String, Object> s = statement(id);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : tierList(s.get("lines"))) {
            lines.add(Obj.of("description", l.get("number") + " " + l.get("description"), "quantity", l.get("quantity"), "unitPrice", l.get("rate"), "discountPercent", null,
                    "total", l.get("amount")));
        }
        for (Map<String, Object> a : tierList(s.get("adjustments"))) {
            lines.add(Obj.of("description", "Adjustment: " + a.get("reason"), "quantity", 1, "unitPrice", a.get("amount"), "discountPercent", null, "total", a.get("amount")));
        }
        String company = str(db.row("SELECT name FROM \"Organization\" WHERE id = ?", org()).get("name"));
        return SalesDocumentPdf.render(company, "COMMISSION STATEMENT", str(s.get("number")), List.of("Salesperson: " + s.get("salespersonName"), "Period: "
                + s.get("periodStart") + " to " + s.get("periodEnd"), "Status: " + s.get("status")), lines, null, List.of(new SalesDocumentPdf.Total("Carried in",
                        dec(s.get("carriedIn"))), new SalesDocumentPdf.Total("Total", dec(s.get("total")))), List.of());
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
            filter = " AND t.month <= ? AND COALESCE(t.\"periodEnd\", (t.month + interval '1 month' - interval '1 day')::date) >= ?";
            params.add(month(month).plusMonths(1).minusDays(1));
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
        if (id == null || in.containsKey("month") || in.containsKey("periodStart")) {
            String period = str(in.get("periodType"));
            d.put("month", in.get("periodStart") != null ? SalesDb.date(str(in.get("periodStart"))) : month(str(in.get("month"))));
            if (period != null) {
                d.put("periodType", period);
            }
            if (in.containsKey("periodEnd")) {
                d.put("periodEnd", SalesDb.date(str(in.get("periodEnd"))));
            }
        }
        if (in.containsKey("metric")) {
            d.put("metric", str(in.get("metric")));
        }
        SalesTargetService.validate(d);
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

    /** Targets of {@code month} with actuals and achievement %: see {@link SalesTargetService}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> achievement(String month) {
        LocalDate from = month(month);
        return targetsSvc.achievement(from, from.plusMonths(1).minusDays(1));
    }
}
