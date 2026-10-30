package com.inventoryerp.credit.application;

import static com.inventoryerp.credit.application.CreditDb.blank;
import static com.inventoryerp.credit.application.CreditDb.dec;
import static com.inventoryerp.credit.application.CreditDb.org;
import static com.inventoryerp.credit.application.CreditDb.str;
import static com.inventoryerp.credit.application.CreditDb.user;

import com.inventoryerp.credit.application.CreditExposureContributor.Contribution;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit exposure, limits, policy and decisions (docs/spec/credit.md).
 *
 * <p>{@code exposure = undelivered + delivered-not-invoiced + receivables + other − deposits − unapplied credits}
 * (deposits only when the policy says so); {@code headroom = limit − exposure}. The limit is the most specific active
 * {@code CreditLimit} (branch, currency) or else {@code Partner.creditLimit}; 0 means no limit. With group limits the
 * parent customer's limit is checked against the exposure of the parent and all its children.
 *
 * <p>{@link #check} records an immutable {@code CreditDecision}: {@code blocked} for a customer on hard hold (or risk
 * category {@code blocked}, or overdue with the {@code hard} policy), {@code hold} when the limit would be exceeded or
 * overdue receivables exist under the {@code approval} policy, else {@code pass}. A {@code hold} can be released by a
 * holder of {@code credit:override} ({@link #override}).
 */
@Service
public class CreditService {

    public static final Set<String> CHECKPOINTS = Set.of("quote_submit", "order_confirm", "amendment", "trip_dispatch",
            "pos_credit_sale");

    private final CreditDb db;
    private final List<CreditExposureContributor> contributors;
    private final AuditService audit;

    public CreditService(CreditDb db, List<CreditExposureContributor> contributors, AuditService audit) {
        this.db = db;
        this.contributors = contributors;
        this.audit = audit;
    }

    /** Outcome of a credit check. */
    public record Decision(String id, String result, List<String> reasons, Map<String, Object> exposure) {

        public boolean passed() {
            return "pass".equals(result) || "override".equals(result);
        }
    }

    // ------------------------------------------------------------------ exposure

    /** Exposure of one customer (credit.md §2) with limit, headroom, overdue and group figures. */
    @Transactional(readOnly = true)
    public Map<String, Object> exposure(String partnerId) {
        return exposure(partnerId, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exposure(String partnerId, String branchId) {
        Map<String, Object> partner = db.row("SELECT id, name, \"creditLimit\", \"creditHold\", \"creditRiskCategory\","
                + " \"parentId\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?", partnerId, org());
        if (partner == null) {
            throw ApiException.notFound("Partner not found");
        }
        Map<String, Object> policy = currentPolicy();
        Contribution c = contribution(partnerId);
        BigDecimal exposure = total(c, policy);
        Map<String, Object> limit = limit(partnerId, branchId, partner);
        BigDecimal amount = dec(limit.get("amount"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partnerId", partnerId);
        out.put("partnerName", partner.get("name"));
        out.put("currencyCode", orgCurrency());
        out.put("limit", JsNumbers.js(amount));
        out.put("limitSource", limit.get("source"));
        out.put("components", Obj.of("undelivered", JsNumbers.js(c.undelivered()), "deliveredUninvoiced",
                JsNumbers.js(c.deliveredUninvoiced()), "receivables", JsNumbers.js(c.receivables()), "other",
                JsNumbers.js(c.other()), "deposits", JsNumbers.js(c.deposits()), "credits", JsNumbers.js(c.credits())));
        out.put("exposure", JsNumbers.js(exposure));
        out.put("headroom", amount.signum() > 0 ? JsNumbers.js(amount.subtract(exposure)) : null);
        out.put("overdue", Obj.of("amount", JsNumbers.js(c.overdueAmount()), "maxDays", c.overdueDays()));
        out.put("creditHold", Boolean.TRUE.equals(partner.get("creditHold")));
        out.put("riskCategory", limit.get("riskCategory") != null ? limit.get("riskCategory")
                : partner.get("creditRiskCategory"));
        out.put("group", null);
        String parent = str(partner.get("parentId"));
        if (parent != null && Boolean.TRUE.equals(policy.get("groupLimits"))) {
            Map<String, Object> p = db.row("SELECT id, \"creditLimit\", \"creditHold\" FROM \"Partner\" WHERE id = ? AND"
                    + " \"organizationId\" = ?", parent, org());
            if (p != null) {
                BigDecimal groupLimit = dec(limit(parent, branchId, p).get("amount"));
                BigDecimal groupExposure = total(contribution(parent), policy);
                for (Map<String, Object> child : db.rows("SELECT id FROM \"Partner\" WHERE \"parentId\" = ? AND"
                        + " \"organizationId\" = ?", parent, org())) {
                    groupExposure = groupExposure.add(total(contribution(str(child.get("id"))), policy));
                }
                out.put("group", Obj.of("parentId", parent, "limit", JsNumbers.js(groupLimit), "exposure",
                        JsNumbers.js(groupExposure), "creditHold", Boolean.TRUE.equals(p.get("creditHold"))));
            }
        }
        return out;
    }

    private Contribution contribution(String partnerId) {
        Contribution c = Contribution.none();
        for (CreditExposureContributor k : contributors) {
            c = c.plus(k.contribute(partnerId));
        }
        return c;
    }

    private static BigDecimal total(Contribution c, Map<String, Object> policy) {
        BigDecimal t = c.undelivered().add(c.deliveredUninvoiced()).add(c.receivables()).add(c.other())
                .subtract(c.credits());
        if (Boolean.TRUE.equals(policy.get("depositsReduceExposure"))) {
            t = t.subtract(c.deposits());
        }
        return t;
    }

    /** Most specific active limit: branch + currency, branch, currency, organization-wide; else Partner.creditLimit. */
    private Map<String, Object> limit(String partnerId, String branchId, Map<String, Object> partner) {
        Map<String, Object> l = db.row("SELECT amount, \"riskCategory\" FROM \"CreditLimit\" WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ? AND (\"branchId\" IS NULL OR \"branchId\" = ?) AND (\"currencyCode\" IS NULL OR"
                + " \"currencyCode\" = ?) AND (\"validFrom\" IS NULL OR \"validFrom\" <= CURRENT_DATE) AND (\"validTo\" IS"
                + " NULL OR \"validTo\" >= CURRENT_DATE) ORDER BY (\"branchId\" IS NOT NULL) DESC, (\"currencyCode\" IS NOT"
                + " NULL) DESC LIMIT 1", org(), partnerId, branchId == null ? "" : branchId, orgCurrency());
        if (l != null) {
            return Obj.of("amount", dec(l.get("amount")), "source", "credit_limit", "riskCategory", l.get("riskCategory"));
        }
        BigDecimal p = dec(partner.get("creditLimit"));
        return Obj.of("amount", p, "source", p.signum() > 0 ? "partner" : "none", "riskCategory", null);
    }

    // ------------------------------------------------------------------ decisions

    /**
     * Credit check at a check point for a document adding {@code amount} of new exposure. Always records a
     * decision. Check points switched off in the policy pass without evaluation (except {@code order_confirm}).
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Decision check(String checkpoint, String partnerId, String documentType, String documentId,
            String documentNumber, BigDecimal amount, String branchId) {
        if (!CHECKPOINTS.contains(checkpoint)) {
            throw ApiException.badRequest("Unknown credit check point: " + checkpoint);
        }
        Map<String, Object> policy = currentPolicy();
        Map<String, Object> e = exposure(partnerId, branchId);
        List<String> reasons = new ArrayList<>();
        String result = "pass";
        boolean enabled = "order_confirm".equals(checkpoint) || CreditDb.strings(policy.get("checkpoints"))
                .contains(checkpoint);
        if (enabled) {
            BigDecimal add = amount == null ? BigDecimal.ZERO : amount;
            BigDecimal limit = dec(e.get("limit"));
            BigDecimal projected = dec(e.get("exposure")).add(add);
            Map<String, Object> overdue = (Map<String, Object>) e.get("overdue");
            int days = ((Number) overdue.get("maxDays")).intValue();
            if (Boolean.TRUE.equals(e.get("creditHold"))) {
                reasons.add("Customer is on credit hold");
                result = "blocked";
            } else if ("blocked".equals(e.get("riskCategory"))) {
                reasons.add("Customer credit risk is blocked");
                result = "blocked";
            }
            if (!"none".equals(policy.get("overdueAction")) && days > ((Number) policy.get("overdueDays")).intValue()
                    && dec(overdue.get("amount")).signum() > 0) {
                reasons.add("Invoices overdue by " + days + " days");
                result = "hard".equals(policy.get("overdueAction")) ? "blocked" : max(result, "hold");
            }
            if (limit.signum() > 0 && projected.compareTo(limit) > 0) {
                reasons.add("Customer credit limit would be exceeded");
                result = max(result, "hold");
            }
            if (e.get("group") instanceof Map<?, ?> g) {
                BigDecimal gl = dec(g.get("limit"));
                if (Boolean.TRUE.equals(g.get("creditHold"))) {
                    reasons.add("Customer group is on credit hold");
                    result = "blocked";
                } else if (gl.signum() > 0 && dec(g.get("exposure")).add(add).compareTo(gl) > 0) {
                    reasons.add("Customer group credit limit would be exceeded");
                    result = max(result, "hold");
                }
            }
        }
        String id = record(partnerId, checkpoint, documentType, documentId, documentNumber, amount, dec(e.get("limit")),
                result, reasons, e, null);
        return new Decision(id, result, reasons, e);
    }

    private static String max(String a, String b) {
        List<String> order = List.of("pass", "hold", "blocked");
        return order.indexOf(a) >= order.indexOf(b) ? a : b;
    }

    /** Release a held document: records an {@code override} decision (needs {@code credit:override}). */
    @Transactional
    public Decision override(String partnerId, String documentType, String documentId, String documentNumber,
            String reason) {
        if (!TenantContext.has(Permissions.CREDIT__OVERRIDE)) {
            throw ApiException.forbidden("Releasing a credit hold needs the credit override permission");
        }
        Map<String, Object> e = exposure(partnerId);
        Map<String, Object> last = db.row("SELECT id FROM \"CreditDecision\" WHERE \"organizationId\" = ? AND"
                + " \"documentType\" = ? AND \"documentId\" = ? AND result IN ('hold', 'blocked') ORDER BY \"createdAt\""
                + " DESC LIMIT 1", org(), documentType, documentId);
        String id = record(partnerId, "override", documentType, documentId, documentNumber, BigDecimal.ZERO,
                dec(e.get("limit")), "override", List.of(blank(reason) ? "Released by credit override" : reason), e,
                last == null ? null : str(last.get("id")));
        audit.record(AuditInput.of("CreditDecision", id, "approve", null, Obj.of("document", documentNumber, "reason",
                reason)));
        return new Decision(id, "override", List.of(), e);
    }

    private String record(String partnerId, String checkpoint, String documentType, String documentId,
            String documentNumber, BigDecimal amount, BigDecimal limit, String result, List<String> reasons,
            Map<String, Object> exposure, String overrides) {
        String id = CreditDb.newId();
        db.update("INSERT INTO \"CreditDecision\" (id, \"organizationId\", \"partnerId\", checkpoint, \"documentType\","
                + " \"documentId\", \"documentNumber\", amount, \"limitAmount\", result, reasons, exposure, \"overridesId\","
                + " \"decidedById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)", id, org(), partnerId,
                checkpoint, documentType, documentId, documentNumber, amount == null ? BigDecimal.ZERO : amount, limit,
                result, reasons, CreditDb.toJson(exposure), overrides, user());
        return id;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> decisions(Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        for (String k : List.of("partnerId", "documentId", "result", "checkpoint")) {
            if (!blank(p.get(k))) {
                where.append(" AND d.\"").append(k).append("\" = ?");
                params.add(p.get(k));
            }
        }
        return db.rows("SELECT d.*, pa.name AS \"partnerName\", COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\","
                + " u.\"lastName\")), ''), u.email) AS \"decidedByName\" FROM \"CreditDecision\" d LEFT JOIN \"Partner\" pa"
                + " ON pa.id = d.\"partnerId\" LEFT JOIN \"User\" u ON u.id = d.\"decidedById\"" + where
                + " ORDER BY d.\"createdAt\" DESC LIMIT 200", params);
    }

    /** Customers to review: on hold, over / near (≥ 90 %) their limit, or overdue beyond the policy. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> reviewQueue() {
        Map<String, Object> policy = currentPolicy();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : db.rows("SELECT DISTINCT pa.id FROM \"Partner\" pa WHERE pa.\"organizationId\" = ?"
                + " AND pa.\"isCustomer\" AND pa.\"deletedAt\" IS NULL AND (pa.\"creditHold\" OR pa.\"creditLimit\" > 0 OR"
                + " EXISTS (SELECT 1 FROM \"CreditLimit\" l WHERE l.\"partnerId\" = pa.id) OR EXISTS (SELECT 1 FROM"
                + " \"Document\" d WHERE d.\"partnerId\" = pa.id AND d.\"organizationId\" = pa.\"organizationId\" AND"
                + " d.\"amountResidual\" > 0 AND d.\"dueDate\" < now() - make_interval(days => ?))) LIMIT 500", org(),
                ((Number) policy.get("overdueDays")).intValue())) {
            Map<String, Object> e = exposure(str(p.get("id")));
            BigDecimal limit = dec(e.get("limit"));
            boolean near = limit.signum() > 0 && dec(e.get("exposure")).compareTo(limit.multiply(new BigDecimal("0.9")))
                    >= 0;
            int days = ((Number) ((Map<?, ?>) e.get("overdue")).get("maxDays")).intValue();
            if (Boolean.TRUE.equals(e.get("creditHold")) || near || days > ((Number) policy.get("overdueDays")).intValue()) {
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ limits / policy / hold

    @Transactional(readOnly = true)
    public List<Map<String, Object>> limits(String partnerId) {
        return db.rows("SELECT l.*, p.name AS \"partnerName\" FROM \"CreditLimit\" l LEFT JOIN \"Partner\" p ON p.id ="
                + " l.\"partnerId\" WHERE l.\"organizationId\" = ? AND (?::text IS NULL OR l.\"partnerId\" = ?) ORDER BY"
                + " p.name", org(), partnerId, partnerId);
    }

    @Transactional
    public Map<String, Object> saveLimit(String id, Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("partnerId")) {
            String p = str(in.get("partnerId"));
            if (blank(p) || !db.exists("Partner", p)) {
                throw ApiException.notFound("Partner not found");
            }
            d.put("partnerId", p);
        }
        if (id == null || in.containsKey("amount")) {
            BigDecimal a = dec(in.get("amount"));
            if (a.signum() < 0) {
                throw ApiException.badRequest("amount must not be less than 0");
            }
            d.put("amount", a);
        }
        for (String k : List.of("branchId", "currencyCode", "notes")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (in.containsKey("riskCategory")) {
            String r = blank(str(in.get("riskCategory"))) ? null : str(in.get("riskCategory"));
            if (r != null && !Set.of("low", "medium", "high", "blocked").contains(r)) {
                throw ApiException.badRequest("riskCategory must be one of: blocked, high, low, medium");
            }
            d.put("riskCategory", r);
        }
        if (in.containsKey("periodDays")) {
            d.put("periodDays", in.get("periodDays") instanceof Number n ? n.intValue() : null);
        }
        for (String k : List.of("reviewDate", "validFrom", "validTo")) {
            if (in.containsKey(k)) {
                d.put(k, CreditDb.date(str(in.get(k))));
            }
        }
        if (d.get("validFrom") instanceof LocalDate f && d.get("validTo") instanceof LocalDate t && t.isBefore(f)) {
            throw ApiException.badRequest("validTo must be on or after validFrom");
        }
        Map<String, Object> row;
        try {
            if (id == null) {
                d.put("createdById", user());
                row = db.insert("CreditLimit", d);
            } else {
                db.require("CreditLimit", id, "Credit limit not found");
                row = db.patch("CreditLimit", id, d, true);
            }
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw ApiException.conflict("This customer already has a credit limit for that branch and currency");
        }
        audit.record(AuditInput.of("CreditLimit", str(row.get("id")), id == null ? "create" : "update", null, in));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteLimit(String id) {
        db.require("CreditLimit", id, "Credit limit not found");
        db.update("DELETE FROM \"CreditLimit\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("CreditLimit", id, "delete"));
        return Obj.of("ok", true);
    }

    /** Put a customer on (or take it off) hard credit hold. */
    @Transactional
    public Map<String, Object> setHold(String partnerId, boolean hold, String reason) {
        if (!db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        db.update("UPDATE \"Partner\" SET \"creditHold\" = ?, \"updatedAt\" = " + CreditDb.NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ?", hold, partnerId, org());
        audit.record(AuditInput.of("Partner", partnerId, "update", null, Obj.of("creditHold", hold, "reason", reason)));
        return exposure(partnerId);
    }

    /** Policy without creating the row (defaults when absent) — safe in read-only transactions. */
    Map<String, Object> currentPolicy() {
        Map<String, Object> p = db.row("SELECT * FROM \"CreditPolicy\" WHERE \"organizationId\" = ?", org());
        return p != null ? p : Obj.of("overdueDays", 30, "overdueAction", "approval", "depositsReduceExposure", true,
                "undeliveredBasis", "gross", "groupLimits", true, "checkpoints", List.of("quote_submit", "order_confirm",
                        "amendment", "trip_dispatch"), "autoHold", false);
    }

    @Transactional
    public Map<String, Object> policy() {
        db.update("INSERT INTO \"CreditPolicy\" (\"organizationId\") VALUES (?) ON CONFLICT DO NOTHING", org());
        return db.row("SELECT * FROM \"CreditPolicy\" WHERE \"organizationId\" = ?", org());
    }

    @Transactional
    public Map<String, Object> updatePolicy(Map<String, Object> in) {
        Map<String, Object> before = policy();
        Map<String, Object> d = new LinkedHashMap<>();
        if (in.containsKey("overdueDays")) {
            int v = in.get("overdueDays") instanceof Number n ? n.intValue() : -1;
            if (v < 0) {
                throw ApiException.badRequest("overdueDays must not be less than 0");
            }
            d.put("overdueDays", v);
        }
        if (in.containsKey("overdueAction")) {
            String v = str(in.get("overdueAction"));
            if (!Set.of("none", "approval", "hard").contains(v)) {
                throw ApiException.badRequest("overdueAction must be one of: approval, hard, none");
            }
            d.put("overdueAction", v);
        }
        if (in.containsKey("undeliveredBasis")) {
            String v = str(in.get("undeliveredBasis"));
            if (!Set.of("gross", "net").contains(v)) {
                throw ApiException.badRequest("undeliveredBasis must be one of: gross, net");
            }
            d.put("undeliveredBasis", v);
        }
        for (String k : List.of("depositsReduceExposure", "groupLimits", "autoHold")) {
            if (in.containsKey(k)) {
                d.put(k, Boolean.TRUE.equals(in.get(k)));
            }
        }
        if (in.containsKey("checkpoints")) {
            List<String> cps = new ArrayList<>(CreditDb.strings(in.get("checkpoints")));
            for (String c : cps) {
                if (!CHECKPOINTS.contains(c)) {
                    throw ApiException.badRequest("Unknown credit check point: " + c);
                }
            }
            if (!cps.contains("order_confirm")) {
                cps.add("order_confirm");
            }
            d.put("checkpoints", cps);
        }
        if (!d.isEmpty()) {
            d.put("updatedById", user());
            List<String> sets = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            d.forEach((k, v) -> {
                sets.add("\"" + k + "\" = ?");
                params.add(CreditDb.param(v));
            });
            params.add(org());
            db.jdbc().sql("UPDATE \"CreditPolicy\" SET " + String.join(", ", sets) + ", \"updatedAt\" = " + CreditDb.NOW
                    + " WHERE \"organizationId\" = ?").params(params).update();
            audit.record(AuditInput.of("CreditPolicy", org(), "update", before, in));
        }
        return policy();
    }

    /**
     * Daily job body: with {@code autoHold} and the {@code hard} overdue action, put customers overdue beyond the
     * policy on credit hold. Returns the customers held.
     */
    @Transactional
    public List<String> autoHold() {
        Map<String, Object> policy = currentPolicy();
        List<String> held = new ArrayList<>();
        if (!Boolean.TRUE.equals(policy.get("autoHold")) || !"hard".equals(policy.get("overdueAction"))) {
            return held;
        }
        for (Map<String, Object> p : db.rows("SELECT DISTINCT d.\"partnerId\" AS id FROM \"Document\" d JOIN \"Partner\""
                + " pa ON pa.id = d.\"partnerId\" WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text ="
                + " 'sales_invoice' AND d.status::text NOT IN ('draft', 'cancelled', 'rejected') AND d.\"amountResidual\" > 0"
                + " AND d.\"dueDate\" < now() - make_interval(days => ?) AND NOT pa.\"creditHold\"", org(),
                ((Number) policy.get("overdueDays")).intValue())) {
            String id = str(p.get("id"));
            db.update("UPDATE \"Partner\" SET \"creditHold\" = true, \"updatedAt\" = " + CreditDb.NOW + " WHERE id = ?", id);
            audit.record(AuditInput.of("Partner", id, "update", null, Obj.of("creditHold", true, "reason",
                    "Automatic: invoices overdue beyond " + policy.get("overdueDays") + " days")));
            held.add(id);
        }
        return held;
    }

    private String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }
}
