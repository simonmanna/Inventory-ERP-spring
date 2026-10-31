package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.invoicing.application.CreditNoteService;
import com.inventoryerp.invoicing.application.CreditNoteService.CreditNoteInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer rebates (ADR-S011 phase S9, sales-postings.md P15). A programme pays back a per-unit amount or a percent of
 * value once a customer's qualifying purchases in a period (month / quarter / year) reach a tier; the highest tier
 * reached applies to the whole period.
 *
 * <p>An accrual run recomputes each customer's rebate for the period and posts only the difference to the last
 * accrual: Dr Sales Rebates (contra revenue, mapping {@code sales_rebates}) / Cr Rebate Accrual (liability, mapping
 * {@code rebate_accrual}); a drop reverses the direction. Settlement issues a credit note whose line is booked to the
 * accrual account (Dr Rebate Accrual / Cr AR), so the rebate is never expensed twice; it offsets the customer's oldest
 * open invoice (any excess stays an unallocated customer credit). A settled accrual is final.
 */
@Service
public class SalesRebateService {

    private final SalesDb db;
    private final SalesRevenueQuery revenue;
    private final AccountResolverService accounts;
    private final PostingService posting;
    private final CreditNoteService creditNotes;
    private final AuditService audit;

    public SalesRebateService(SalesDb db, SalesRevenueQuery revenue, AccountResolverService accounts,
            PostingService posting, CreditNoteService creditNotes, AuditService audit) {
        this.db = db;
        this.revenue = revenue;
        this.accounts = accounts;
        this.posting = posting;
        this.creditNotes = creditNotes;
        this.audit = audit;
    }

    String rebateAccount() {
        return accounts.ensureByCode("4190", "Sales Rebates", "revenue", "sales_rebates");
    }

    String accrualAccount() {
        return accounts.ensureByCode("2360", "Rebate Accrual", "current_liability", "rebate_accrual");
    }

    // ------------------------------------------------------------------ programmes

    @Transactional(readOnly = true)
    public List<Map<String, Object>> programs() {
        return db.rows("SELECT r.*, p.name AS \"partnerName\", pc.name AS \"partnerCategoryName\", pr.name AS"
                + " \"productName\", c.name AS \"productCategoryName\", (SELECT COALESCE(sum(a.amount), 0) FROM"
                + " \"RebateAccrual\" a WHERE a.\"programId\" = r.id AND a.status = 'accrued') AS accrued FROM"
                + " \"RebateProgram\" r LEFT JOIN \"Partner\" p ON p.id = r.\"partnerId\" LEFT JOIN \"PartnerCategory\" pc"
                + " ON pc.id = r.\"partnerCategoryId\" LEFT JOIN \"Product\" pr ON pr.id = r.\"productId\" LEFT JOIN"
                + " \"ProductCategory\" c ON c.id = r.\"productCategoryId\" WHERE r.\"organizationId\" = ? ORDER BY"
                + " r.status, r.name", org());
    }

    @Transactional
    public Map<String, Object> saveProgram(String id, Map<String, Object> in) {
        Map<String, Object> current = id == null ? null : db.require("RebateProgram", id, "Rebate programme not found");
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("name")) {
            if (blank(str(in.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            d.put("name", str(in.get("name")));
        }
        for (String k : List.of("partnerId", "partnerCategoryId", "productId", "productCategoryId")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (d.get("partnerId") != null && d.get("partnerCategoryId") != null) {
            throw ApiException.badRequest("Choose a customer or a customer category, not both");
        }
        if (d.get("productId") != null && d.get("productCategoryId") != null) {
            throw ApiException.badRequest("Choose a product or a product category, not both");
        }
        if (in.containsKey("periodType") || id == null) {
            String v = in.get("periodType") == null ? "quarter" : str(in.get("periodType"));
            if (!Set.of("month", "quarter", "year").contains(v)) {
                throw ApiException.badRequest("periodType must be one of: month, quarter, year");
            }
            d.put("periodType", v);
        }
        if (in.containsKey("basis") || id == null) {
            String v = in.get("basis") == null ? "quantity" : str(in.get("basis"));
            if (!Set.of("quantity", "value").contains(v)) {
                throw ApiException.badRequest("basis must be one of: quantity, value");
            }
            d.put("basis", v);
        }
        if (in.containsKey("startDate") || id == null) {
            LocalDate start = SalesDb.date(str(in.get("startDate")));
            if (start == null) {
                throw ApiException.badRequest("startDate should not be empty");
            }
            d.put("startDate", start);
        }
        if (in.containsKey("endDate")) {
            d.put("endDate", SalesDb.date(str(in.get("endDate"))));
        }
        if (in.containsKey("status")) {
            if (!Set.of("active", "closed").contains(str(in.get("status")))) {
                throw ApiException.badRequest("status must be one of: active, closed");
            }
            d.put("status", str(in.get("status")));
        }
        if (in.containsKey("tiers") || id == null) {
            d.put("tiers", SalesDb.jsonb(tiers(in.get("tiers"))));
        }
        if (id == null) {
            id = str(db.insert("RebateProgram", d).get("id"));
        } else {
            db.patch("RebateProgram", id, d, true);
        }
        audit.record(AuditInput.of("RebateProgram", id, current == null ? "create" : "update", null, Obj.of("fields",
                in.keySet())));
        return db.row("SELECT * FROM \"RebateProgram\" WHERE id = ?", id);
    }

    /** Validated tiers sorted by threshold. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> tiers(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw ApiException.badRequest("tiers should not be empty");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> t = (Map<String, Object>) o;
            BigDecimal from = dec(t.get("from"));
            BigDecimal rate = dec(t.get("rate"));
            String type = t.get("rateType") == null ? "per_unit" : str(t.get("rateType"));
            if (from.signum() < 0 || rate.signum() < 0) {
                throw ApiException.badRequest("Tier thresholds and rates must not be negative");
            }
            if (!Set.of("per_unit", "percent").contains(type)) {
                throw ApiException.badRequest("rateType must be one of: per_unit, percent");
            }
            out.add(Obj.of("from", JsNumbers.js(from), "rate", JsNumbers.js(rate), "rateType", type));
        }
        out.sort(Comparator.comparing(t -> dec(t.get("from"))));
        return out;
    }

    // ------------------------------------------------------------------ accrual

    /** First day of the period of {@code periodType} containing {@code d}. */
    static LocalDate periodStart(String periodType, LocalDate d) {
        return switch (periodType) {
            case "month" -> d.withDayOfMonth(1);
            case "year" -> d.withDayOfYear(1);
            default -> d.withMonth((d.getMonthValue() - 1) / 3 * 3 + 1).withDayOfMonth(1);
        };
    }

    static LocalDate periodEnd(String periodType, LocalDate start) {
        return switch (periodType) {
            case "month" -> start.plusMonths(1).minusDays(1);
            case "year" -> start.plusYears(1).minusDays(1);
            default -> start.plusMonths(3).minusDays(1);
        };
    }

    /**
     * Accrue every active programme for the period containing {@code asOf}: recompute each customer's rebate and
     * post the change since the last run. Returns the accruals touched.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> accrue(LocalDate asOf) {
        LocalDate on = asOf == null ? LocalDate.now() : asOf;
        List<Map<String, Object>> touched = new ArrayList<>();
        BigDecimal posted = BigDecimal.ZERO;
        for (Map<String, Object> p : db.rows("SELECT * FROM \"RebateProgram\" WHERE \"organizationId\" = ? AND status ="
                + " 'active' AND \"startDate\" <= ? AND (\"endDate\" IS NULL OR \"endDate\" >= ?)", org(), on, on)) {
            String type = str(p.get("periodType"));
            LocalDate start = periodStart(type, on);
            LocalDate programStart = (LocalDate) p.get("startDate");
            LocalDate from = start.isBefore(programStart) ? programStart : start;
            LocalDate end = periodEnd(type, start);
            LocalDate to = p.get("endDate") != null && ((LocalDate) p.get("endDate")).isBefore(end)
                    ? (LocalDate) p.get("endDate") : end;
            Map<String, BigDecimal[]> byPartner = new LinkedHashMap<>();
            for (Map<String, Object> l : revenue.invoiced(from, to)) {
                if (!matches(p, l)) {
                    continue;
                }
                BigDecimal[] acc = byPartner.computeIfAbsent(str(l.get("partnerId")), k -> new BigDecimal[] {
                        BigDecimal.ZERO, BigDecimal.ZERO});
                acc[0] = acc[0].add(dec(l.get("quantity")));
                acc[1] = acc[1].add(dec(l.get("revenue")));
            }
            // customers accrued earlier in the period whose purchases dropped out (credit notes) recompute to zero
            for (Map<String, Object> a : db.rows("SELECT \"partnerId\" FROM \"RebateAccrual\" WHERE \"programId\" = ? AND"
                    + " \"periodStart\" = ?", p.get("id"), start)) {
                byPartner.putIfAbsent(str(a.get("partnerId")), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            }
            List<Map<String, Object>> tiers = (List<Map<String, Object>>) (Object) parseTiers(p.get("tiers"));
            for (Map.Entry<String, BigDecimal[]> e : byPartner.entrySet()) {
                Map<String, Object> r = accrueOne(p, tiers, e.getKey(), start, end, e.getValue()[0], e.getValue()[1]);
                if (r != null) {
                    touched.add(r);
                    posted = posted.add(dec(r.get("delta")));
                }
            }
        }
        return Obj.of("asOf", on, "accruals", touched, "posted", JsNumbers.js(posted));
    }

    private static List<?> parseTiers(Object v) {
        if (v instanceof List<?> l) {
            return l;
        }
        return tools.jackson.databind.json.JsonMapper.builder().build().readValue(String.valueOf(v), List.class);
    }

    private static boolean matches(Map<String, Object> p, Map<String, Object> l) {
        return eqOrNull(p.get("partnerId"), l.get("partnerId"))
                && eqOrNull(p.get("partnerCategoryId"), l.get("partnerCategoryId"))
                && eqOrNull(p.get("productId"), l.get("productId"))
                && eqOrNull(p.get("productCategoryId"), l.get("productCategoryId"));
    }

    private static boolean eqOrNull(Object want, Object have) {
        return want == null || want.equals(have);
    }

    private Map<String, Object> accrueOne(Map<String, Object> p, List<Map<String, Object>> tiers, String partnerId,
            LocalDate start, LocalDate end, BigDecimal qty, BigDecimal value) {
        Map<String, Object> existing = db.row("SELECT * FROM \"RebateAccrual\" WHERE \"programId\" = ? AND \"partnerId\""
                + " = ? AND \"periodStart\" = ? FOR UPDATE", p.get("id"), partnerId, start);
        if (existing != null && "settled".equals(existing.get("status"))) {
            return null;
        }
        BigDecimal metric = "value".equals(p.get("basis")) ? value : qty;
        Map<String, Object> tier = null;
        for (Map<String, Object> t : tiers) {
            if (metric.compareTo(dec(t.get("from"))) >= 0) {
                tier = t;
            }
        }
        BigDecimal rate = tier == null ? BigDecimal.ZERO : dec(tier.get("rate"));
        BigDecimal amount = tier == null ? BigDecimal.ZERO : "percent".equals(tier.get("rateType"))
                ? value.max(BigDecimal.ZERO).multiply(rate).divide(SalesPricingEngine.HUNDRED, 2, RoundingMode.HALF_UP)
                : qty.max(BigDecimal.ZERO).multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal before = existing == null ? BigDecimal.ZERO : dec(existing.get("amount"));
        BigDecimal delta = amount.subtract(before);
        String id;
        if (existing == null) {
            if (amount.signum() == 0 && qty.signum() == 0 && value.signum() == 0) {
                return null;
            }
            id = str(db.insert("RebateAccrual", Obj.of("programId", p.get("id"), "partnerId", partnerId, "periodStart",
                    start, "periodEnd", end, "qualifyingQty", qty, "qualifyingValue", value, "amount", amount, "rate",
                    rate)).get("id"));
        } else {
            id = str(existing.get("id"));
            db.patch("RebateAccrual", id, Obj.of("qualifyingQty", qty, "qualifyingValue", value, "amount", amount, "rate",
                    rate), true);
        }
        if (delta.signum() != 0) {
            String rebates = rebateAccount();
            String accrual = accrualAccount();
            BigDecimal abs = delta.abs();
            String desc = "Rebate accrual " + p.get("name") + " " + start;
            List<Line> lines = delta.signum() > 0
                    ? List.of(new Line(rebates, abs, null, partnerId, desc, null, null, null),
                            new Line(accrual, null, abs, partnerId, desc, null, null, null))
                    : List.of(new Line(accrual, abs, null, partnerId, desc, null, null, null),
                            new Line(rebates, null, abs, partnerId, desc, null, null, null));
            posting.post(new PostingRequest("GEN", LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC), desc, null,
                    null, "rebate_accrual", id, null, null, null, null, null, lines));
            audit.record(AuditInput.of("RebateAccrual", id, "update", null, Obj.of("amount", amount, "delta", delta)));
        }
        Map<String, Object> out = db.row("SELECT * FROM \"RebateAccrual\" WHERE id = ?", id);
        out.put("delta", JsNumbers.js(delta));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> accruals(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder sql = new StringBuilder("SELECT a.*, r.name AS \"programName\", p.name AS \"partnerName\", d.\"documentNumber\""
                + " AS \"creditNoteNumber\" FROM \"RebateAccrual\" a JOIN \"RebateProgram\" r ON r.id = a.\"programId\" LEFT JOIN"
                + " \"Partner\" p ON p.id = a.\"partnerId\" LEFT JOIN \"Document\" d ON d.id = a.\"creditNoteId\" WHERE"
                + " a.\"organizationId\" = ?");
        for (String k : List.of("programId", "partnerId", "status")) {
            if (!blank(q.get(k))) {
                sql.append(" AND a.\"").append(k).append("\" = ?");
                params.add(q.get(k));
            }
        }
        sql.append(" ORDER BY a.\"periodStart\" DESC, p.name");
        return db.rows(sql.toString(), params);
    }

    /** Settle an accrual of an ended period by credit note (Dr Rebate Accrual / Cr AR). */
    @Transactional
    public Map<String, Object> settle(String accrualId) {
        Map<String, Object> a = db.row("SELECT a.*, r.name AS \"programName\" FROM \"RebateAccrual\" a JOIN"
                + " \"RebateProgram\" r ON r.id = a.\"programId\" WHERE a.id = ? AND a.\"organizationId\" = ? FOR UPDATE OF a",
                accrualId, org());
        if (a == null) {
            throw ApiException.notFound("Rebate accrual not found");
        }
        if ("settled".equals(a.get("status"))) {
            throw ApiException.badRequest("Rebate accrual is already settled");
        }
        if (!((LocalDate) a.get("periodEnd")).isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Rebate period has not ended yet");
        }
        BigDecimal amount = dec(a.get("amount"));
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Nothing to settle");
        }
        String desc = "Rebate " + a.get("programName") + " " + a.get("periodStart") + " – " + a.get("periodEnd");
        // offset the customer's oldest open invoice (one that can absorb it all, if any) so receivables stay matched
        Map<String, Object> open = db.row("SELECT id FROM \"Document\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?"
                + " AND \"documentType\" = 'sales_invoice' AND status = 'posted' AND \"amountResidual\" > 0"
                + " ORDER BY (\"amountResidual\" >= ?) DESC, \"issueDate\", \"documentNumber\" LIMIT 1", org(),
                a.get("partnerId"), amount);
        Map<String, Object> cn = creditNotes.create(new CreditNoteInput(str(a.get("partnerId")), LocalDate.now()
                .toString(), null, "REBATE", desc, open == null ? null : str(open.get("id")), List.of(LineInput.of(null, accrualAccount(), desc,
                        BigDecimal.ONE, amount, BigDecimal.ZERO, null, null))));
        String cnId = str(cn.get("id"));
        creditNotes.post(cnId, "none");
        db.patch("RebateAccrual", accrualId, Obj.of("status", "settled", "creditNoteId", cnId), true);
        audit.record(AuditInput.of("RebateAccrual", accrualId, "approve", null, Obj.of("creditNoteId", cnId, "amount",
                amount)));
        return db.row("SELECT * FROM \"RebateAccrual\" WHERE id = ?", accrualId);
    }
}
