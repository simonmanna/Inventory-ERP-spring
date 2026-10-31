package com.inventoryerp.credit.application;

import static com.inventoryerp.credit.application.CreditDb.blank;
import static com.inventoryerp.credit.application.CreditDb.dec;
import static com.inventoryerp.credit.application.CreditDb.org;
import static com.inventoryerp.credit.application.CreditDb.str;
import static com.inventoryerp.credit.application.CreditDb.user;

import com.inventoryerp.credit.application.CreditExposureContributor.Contribution;
import com.inventoryerp.kernel.approvals.ControlledActionService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer credit management (docs/spec/credit.md, ADR-S022): the one place that resolves limits, measures exposure,
 * decides, overrides and reserves credit for every channel — sales quotations and orders, POS / back-office orders,
 * invoices, manual delivery orders and trip dispatch.
 *
 * <p><b>Profile.</b> The customer's {@code Partner.creditLimit}, {@code creditHold} and {@code creditRiskCategory} are
 * authoritative. {@code CreditLimit} rows with a branch or a currency are sub-limits that can only lower the available
 * credit; an org-wide row mirrors the profile (V211) and is never read for the limit.
 *
 * <p><b>Exposure</b> {@code = undelivered + delivered-not-invoiced + receivables + other − deposits − credits}
 * (deposits only when the policy says so), summed over the {@link CreditExposureContributor}s: sales, orders, fleet,
 * invoicing, pos. Each commercial event sits in exactly one bucket. {@code headroom = effective limit − exposure},
 * where the effective limit adds active customer override grants that the current user did not grant.
 *
 * <p><b>Decisions.</b> {@link #check} locks the customer row (the reservation: a concurrent check waits until this
 * transaction commits its document) and records an immutable {@code CreditDecision}: {@code blocked} for a hard hold
 * not covered by a grant, risk category {@code blocked}, or overdue under the {@code hard} policy; {@code hold} when the
 * limit would be exceeded or overdue receivables exist under the {@code approval} policy; else {@code pass}. The
 * overdue rule applies to the policy's {@code overdueCheckpoints} only. A customer with no limit, no hold and no
 * applicable overdue rule passes without a decision row. A held document is released by {@link #override}
 * ({@code credit:override}); a customer grant ({@link #grantCustomerOverride}) adds headroom or waives the hold until it
 * expires, after approval and separation of duties.
 */
@Service
public class CreditService {

    public static final Set<String> CHECKPOINTS = Set.of("quote_submit", "order_confirm", "amendment", "trip_dispatch",
            "pos_credit_sale", "delivery_order", "invoice_post");

    private static final List<String> SALES_CHECKPOINTS = List.of("quote_submit", "order_confirm", "amendment",
            "trip_dispatch");

    private final CreditDb db;
    private final List<CreditExposureContributor> contributors;
    private final AuditService audit;
    private final ControlledActionService controlled;

    public CreditService(CreditDb db, List<CreditExposureContributor> contributors, AuditService audit,
            ControlledActionService controlled) {
        this.db = db;
        this.contributors = contributors;
        this.audit = audit;
        this.controlled = controlled;
    }

    /** Outcome of a credit check. {@code id} is null when nothing needed evaluating (no limit, no hold). */
    public record Decision(String id, String result, List<String> reasons, Map<String, Object> exposure) {

        public boolean passed() {
            return "pass".equals(result) || "override".equals(result);
        }
    }

    /** {@code POST /credit-control/customers/:id/overrides}. */
    public record GrantInput(BigDecimal amount, Boolean coversHold, String reason, Instant expiresAt) {}

    private record Grant(BigDecimal amount, boolean coversHold) {}

    // ------------------------------------------------------------------ exposure

    /** Exposure of one customer (credit.md §2) with limit, headroom, overdue and group figures. */
    @Transactional(readOnly = true)
    public Map<String, Object> exposure(String partnerId) {
        return exposure(partnerId, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exposure(String partnerId, String branchId) {
        return exposure(partnerId, branchId, null);
    }

    /** The same, leaving out {@code excludeDocumentId} (the document a check is about to add). */
    @Transactional(readOnly = true)
    public Map<String, Object> exposure(String partnerId, String branchId, String excludeDocumentId) {
        Map<String, Object> partner = partner(partnerId);
        if (partner == null) {
            throw ApiException.notFound("Partner not found");
        }
        Map<String, Object> policy = currentPolicy();
        Contribution c = contribution(partnerId, excludeDocumentId);
        BigDecimal exposure = total(c, policy);
        Map<String, Object> limit = limit(partnerId, branchId, partner);
        BigDecimal amount = dec(limit.get("amount"));
        Grant grant = grant(partnerId);
        BigDecimal effective = amount.signum() > 0 ? amount.add(grant.amount()) : BigDecimal.ZERO;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partnerId", partnerId);
        out.put("partnerName", partner.get("name"));
        out.put("currencyCode", orgCurrency());
        out.put("limit", JsNumbers.js(amount));
        out.put("limitSource", limit.get("source"));
        out.put("profileLimit", JsNumbers.js(dec(partner.get("creditLimit"))));
        out.put("effectiveLimit", JsNumbers.js(effective));
        out.put("components", Obj.of("undelivered", JsNumbers.js(c.undelivered()), "deliveredUninvoiced",
                JsNumbers.js(c.deliveredUninvoiced()), "receivables", JsNumbers.js(c.receivables()), "other",
                JsNumbers.js(c.other()), "deposits", JsNumbers.js(c.deposits()), "credits", JsNumbers.js(c.credits())));
        out.put("exposure", JsNumbers.js(exposure));
        out.put("headroom", amount.signum() > 0 ? JsNumbers.js(effective.subtract(exposure)) : null);
        out.put("overdue", Obj.of("amount", JsNumbers.js(c.overdueAmount()), "maxDays", c.overdueDays()));
        out.put("creditHold", Boolean.TRUE.equals(partner.get("creditHold")));
        out.put("riskCategory", partner.get("creditRiskCategory") != null ? partner.get("creditRiskCategory")
                : limit.get("riskCategory"));
        out.put("grants", Obj.of("amount", JsNumbers.js(grant.amount()), "coversHold", grant.coversHold()));
        out.put("group", null);
        String parent = str(partner.get("parentId"));
        if (parent != null && Boolean.TRUE.equals(policy.get("groupLimits"))) {
            Map<String, Object> p = partner(parent);
            if (p != null) {
                BigDecimal groupLimit = dec(limit(parent, branchId, p).get("amount"));
                BigDecimal groupExposure = total(contribution(parent, excludeDocumentId), policy);
                for (Map<String, Object> child : db.rows("SELECT id FROM \"Partner\" WHERE \"parentId\" = ? AND"
                        + " \"organizationId\" = ?", parent, org())) {
                    groupExposure = groupExposure.add(total(contribution(str(child.get("id")), excludeDocumentId),
                            policy));
                }
                out.put("group", Obj.of("parentId", parent, "limit", JsNumbers.js(groupLimit), "exposure",
                        JsNumbers.js(groupExposure), "creditHold", Boolean.TRUE.equals(p.get("creditHold"))));
            }
        }
        return out;
    }

    private Map<String, Object> partner(String partnerId) {
        return partnerId == null ? null : db.row("SELECT id, name, \"creditLimit\", \"creditHold\","
                + " \"creditRiskCategory\", \"parentId\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?",
                partnerId, org());
    }

    private Contribution contribution(String partnerId, String excludeDocumentId) {
        Contribution c = Contribution.none();
        for (CreditExposureContributor k : contributors) {
            c = c.plus(k.contribute(partnerId, excludeDocumentId));
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

    /**
     * The limit that applies: the customer profile, lowered by the most specific active branch / currency sub-limit
     * (0 = unlimited for both).
     */
    private Map<String, Object> limit(String partnerId, String branchId, Map<String, Object> partner) {
        BigDecimal profile = dec(partner.get("creditLimit"));
        Map<String, Object> sub = db.row("SELECT amount, \"riskCategory\" FROM \"CreditLimit\" WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ? AND (\"branchId\" IS NOT NULL OR \"currencyCode\" IS NOT NULL) AND (\"branchId\""
                + " IS NULL OR \"branchId\" = ?) AND (\"currencyCode\" IS NULL OR \"currencyCode\" = ?) AND (\"validFrom\""
                + " IS NULL OR \"validFrom\" <= CURRENT_DATE) AND (\"validTo\" IS NULL OR \"validTo\" >= CURRENT_DATE)"
                + " ORDER BY (\"branchId\" IS NOT NULL) DESC, (\"currencyCode\" IS NOT NULL) DESC LIMIT 1", org(),
                partnerId, branchId == null ? "" : branchId, orgCurrency());
        BigDecimal subAmount = sub == null ? BigDecimal.ZERO : dec(sub.get("amount"));
        if (subAmount.signum() > 0 && (profile.signum() <= 0 || subAmount.compareTo(profile) < 0)) {
            return Obj.of("amount", subAmount, "source", "sub_limit", "riskCategory", sub.get("riskCategory"));
        }
        return Obj.of("amount", profile, "source", profile.signum() > 0 ? "profile" : "none", "riskCategory",
                sub == null ? null : sub.get("riskCategory"));
    }

    /** Active customer override grants, leaving out those the current user granted: nobody clears their own sale. */
    private Grant grant(String partnerId) {
        Map<String, Object> o = db.row("SELECT COALESCE(sum(amount), 0) AS amount, COALESCE(bool_or(\"coversHold\"),"
                + " false) AS hold FROM \"PartnerCreditOverride\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND"
                + " \"revokedAt\" IS NULL AND \"expiresAt\" > now() AND \"createdBy\" IS DISTINCT FROM ?", org(),
                partnerId, TenantContext.userId());
        return new Grant(dec(o.get("amount")), Boolean.TRUE.equals(o.get("hold")));
    }

    // ------------------------------------------------------------------ decisions

    /**
     * Credit check at a check point for a document adding {@code amount} of new exposure. Check points switched off in
     * the policy pass without evaluation (except {@code order_confirm}).
     */
    @Transactional
    public Decision check(String checkpoint, String partnerId, String documentType, String documentId,
            String documentNumber, BigDecimal amount, String branchId) {
        return check(checkpoint, partnerId, documentType, documentId, documentNumber, amount, branchId, documentId);
    }

    /** The same, leaving {@code excludeDocumentId} out of the exposure (the document already exists unbilled). */
    @Transactional
    @SuppressWarnings("unchecked")
    public Decision check(String checkpoint, String partnerId, String documentType, String documentId,
            String documentNumber, BigDecimal amount, String branchId, String excludeDocumentId) {
        if (!CHECKPOINTS.contains(checkpoint)) {
            throw ApiException.badRequest("Unknown credit check point: " + checkpoint);
        }
        Map<String, Object> policy = currentPolicy();
        boolean enabled = "order_confirm".equals(checkpoint) || CreditDb.strings(policy.get("checkpoints"))
                .contains(checkpoint);
        boolean overdueRule = CreditDb.strings(policy.get("overdueCheckpoints")).contains(checkpoint);
        Map<String, Object> p = partner(partnerId);
        if (p == null) {
            throw ApiException.notFound("Partner not found");
        }
        // Sales check points always leave a decision; the other channels skip customers with nothing to check.
        boolean nothingToCheck = !SALES_CHECKPOINTS.contains(checkpoint) && !Boolean.TRUE.equals(p.get("creditHold")) && dec(p.get("creditLimit")).signum() <= 0
                && !"blocked".equals(p.get("creditRiskCategory")) && !overdueRule && p.get("parentId") == null
                && !hasSubLimit(partnerId);
        if (!enabled || nothingToCheck) {
            if (!enabled) {
                return new Decision(record(partnerId, checkpoint, documentType, documentId, documentNumber, amount,
                        BigDecimal.ZERO, "pass", List.of("Check point switched off"), Map.of(), null), "pass",
                        List.of(), Map.of());
            }
            return new Decision(null, "pass", List.of(), Map.of());
        }
        // Reservation: one check per customer at a time; the lock holds until this transaction writes its document.
        db.row("SELECT id FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? FOR NO KEY UPDATE", partnerId, org());
        Map<String, Object> e = exposure(partnerId, branchId, excludeDocumentId);
        List<String> reasons = new ArrayList<>();
        String result = "pass";
        BigDecimal add = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal limit = dec(e.get("limit"));
        BigDecimal effective = dec(e.get("effectiveLimit"));
        BigDecimal projected = dec(e.get("exposure")).add(add);
        Map<String, Object> overdue = (Map<String, Object>) e.get("overdue");
        int days = ((Number) overdue.get("maxDays")).intValue();
        boolean holdCovered = Boolean.TRUE.equals(((Map<String, Object>) e.get("grants")).get("coversHold"));
        if (Boolean.TRUE.equals(e.get("creditHold")) && !holdCovered) {
            reasons.add("Customer is on credit hold");
            result = "blocked";
        } else if ("blocked".equals(e.get("riskCategory"))) {
            reasons.add("Customer credit risk is blocked");
            result = "blocked";
        }
        if (overdueRule && !"none".equals(policy.get("overdueAction"))
                && days > ((Number) policy.get("overdueDays")).intValue() && dec(overdue.get("amount")).signum() > 0) {
            reasons.add("Invoices overdue by " + days + " days");
            result = "hard".equals(policy.get("overdueAction")) ? "blocked" : max(result, "hold");
        }
        if (limit.signum() > 0 && projected.compareTo(effective) > 0) {
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
        String id = record(partnerId, checkpoint, documentType, documentId, documentNumber, amount, limit, result,
                reasons, e, null);
        return new Decision(id, result, reasons, e);
    }

    private boolean hasSubLimit(String partnerId) {
        return db.row("SELECT 1 AS x FROM \"CreditLimit\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND"
                + " (\"branchId\" IS NOT NULL OR \"currencyCode\" IS NOT NULL) LIMIT 1", org(), partnerId) != null;
    }

    /** True when goods may not leave for the customer: hard hold not covered by an active grant. */
    @Transactional(readOnly = true)
    public boolean holdBlocks(String partnerId) {
        Map<String, Object> p = partner(partnerId);
        return p != null && Boolean.TRUE.equals(p.get("creditHold")) && !grant(partnerId).coversHold();
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

    // ------------------------------------------------------------------ profile, sub-limits, hold

    @Transactional(readOnly = true)
    public List<Map<String, Object>> limits(String partnerId) {
        return db.rows("SELECT l.*, p.name AS \"partnerName\" FROM \"CreditLimit\" l LEFT JOIN \"Partner\" p ON p.id ="
                + " l.\"partnerId\" WHERE l.\"organizationId\" = ? AND (?::text IS NULL OR l.\"partnerId\" = ?) ORDER BY"
                + " p.name", org(), partnerId, partnerId);
    }

    /**
     * Save a limit row. A row without branch and currency is the customer profile: the amount and risk category are
     * written to the partner (authoritative) and the row mirrors them for the existing screen.
     */
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
        if (row.get("branchId") == null && row.get("currencyCode") == null) {
            writeProfile(str(row.get("partnerId")), dec(row.get("amount")), null, str(row.get("riskCategory")),
                    in.containsKey("riskCategory"));
        }
        audit.record(AuditInput.of("CreditLimit", str(row.get("id")), id == null ? "create" : "update", null, in));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteLimit(String id) {
        Map<String, Object> row = db.require("CreditLimit", id, "Credit limit not found");
        db.update("DELETE FROM \"CreditLimit\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        if (row.get("branchId") == null && row.get("currencyCode") == null) {
            writeProfile(str(row.get("partnerId")), BigDecimal.ZERO, null, null, false);
        }
        audit.record(AuditInput.of("CreditLimit", id, "delete"));
        return Obj.of("ok", true);
    }

    /**
     * Set the customer profile limit and / or hold (null leaves a value unchanged). The org-wide limit row, when one
     * exists, follows the profile.
     */
    @Transactional
    public Map<String, Object> updateProfile(String partnerId, BigDecimal creditLimit, Boolean creditHold, String reason) {
        Map<String, Object> before = partner(partnerId);
        if (before == null) {
            throw ApiException.notFound("Partner not found");
        }
        if (creditLimit != null && creditLimit.signum() < 0) {
            throw ApiException.badRequest("creditLimit must not be less than 0");
        }
        writeProfile(partnerId, creditLimit, creditHold, null, false);
        audit.record(AuditInput.of("Partner", partnerId, "update", Obj.of("creditLimit", before.get("creditLimit"),
                "creditHold", before.get("creditHold")), Obj.of("creditLimit", creditLimit, "creditHold", creditHold,
                "reason", reason)));
        return exposure(partnerId);
    }

    private void writeProfile(String partnerId, BigDecimal limit, Boolean hold, String risk, boolean setRisk) {
        if (limit != null) {
            db.update("UPDATE \"Partner\" SET \"creditLimit\" = ?, \"updatedAt\" = " + CreditDb.NOW + " WHERE id = ? AND"
                    + " \"organizationId\" = ?", limit, partnerId, org());
            db.update("UPDATE \"CreditLimit\" SET amount = ?, \"updatedAt\" = " + CreditDb.NOW + " WHERE"
                    + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"branchId\" IS NULL AND \"currencyCode\" IS NULL"
                    + " AND amount <> ?", limit, org(), partnerId, limit);
        }
        if (hold != null) {
            db.update("UPDATE \"Partner\" SET \"creditHold\" = ?, \"updatedAt\" = " + CreditDb.NOW + " WHERE id = ? AND"
                    + " \"organizationId\" = ?", hold, partnerId, org());
        }
        if (setRisk) {
            db.update("UPDATE \"Partner\" SET \"creditRiskCategory\" = ?, \"updatedAt\" = " + CreditDb.NOW + " WHERE id"
                    + " = ? AND \"organizationId\" = ?", risk, partnerId, org());
        }
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
        writeProfile(partnerId, null, hold, null, false);
        audit.record(AuditInput.of("Partner", partnerId, "update", null, Obj.of("creditHold", hold, "reason", reason)));
        return exposure(partnerId);
    }

    // ------------------------------------------------------------------ customer override grants

    /**
     * Grant extra headroom and / or waive the hold until {@code expiresAt}. The grant can need an approval (controlled
     * action {@code credit_override}); it covers the customer, so no single sale's maker is known.
     */
    @Transactional
    public String grantCustomerOverride(String partnerId, GrantInput in) {
        if (partner(partnerId) == null) {
            throw ApiException.notFound("Partner not found");
        }
        BigDecimal amount = in.amount() == null ? BigDecimal.ZERO : in.amount();
        boolean coversHold = Boolean.TRUE.equals(in.coversHold());
        if (amount.signum() < 0) {
            throw ApiException.badRequest("amount must not be less than 0");
        }
        if (amount.signum() == 0 && !coversHold) {
            throw ApiException.badRequest("An override needs an amount or must cover the hold");
        }
        if (in.expiresAt() == null || !in.expiresAt().isAfter(Instant.now())) {
            throw ApiException.badRequest("expiresAt must be in the future");
        }
        if (in.reason() == null || in.reason().isBlank()) {
            throw ApiException.badRequest("reason should not be empty");
        }
        ControlledActionService.Outcome outcome = controlled.authorize(new ControlledActionService.Action(
                SegregationOfDutiesService.CREDIT_OVERRIDE, "Partner", partnerId, amount, null, null, List.of(),
                Obj.of("coversHold", coversHold, "reason", in.reason())));
        if (!outcome.allowed()) {
            throw ApiException.badRequest("Approval required before overriding this customer's credit. Pending"
                    + " request " + outcome.approvalRequestId() + ".");
        }
        String id = CreditDb.newId();
        db.update("INSERT INTO \"PartnerCreditOverride\" (id, \"organizationId\", \"partnerId\", amount, \"coversHold\","
                + " reason, \"expiresAt\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, org(), partnerId, amount,
                coversHold, in.reason().trim(), Timestamp.from(in.expiresAt()), user());
        audit.recordInTx(AuditInput.of("PartnerCreditOverride", id, "create", null, Obj.of("partnerId", partnerId,
                "amount", amount, "coversHold", coversHold, "reason", in.reason(), "expiresAt", in.expiresAt())));
        return id;
    }

    /** Revoke a grant; returns its customer. */
    @Transactional
    public String revokeCustomerOverride(String id) {
        Map<String, Object> r = db.row("UPDATE \"PartnerCreditOverride\" SET \"revokedAt\" = now(), \"revokedBy\" = ? WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"revokedAt\" IS NULL RETURNING \"partnerId\"", user(), id, org());
        if (r == null) {
            throw ApiException.notFound("Credit override not found");
        }
        audit.recordInTx(AuditInput.of("PartnerCreditOverride", id, "cancel"));
        return str(r.get("partnerId"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> customerOverrides(String partnerId) {
        return db.rows("SELECT id, amount, \"coversHold\", reason, \"expiresAt\", \"createdBy\", \"createdAt\","
                + " \"revokedBy\", \"revokedAt\" FROM \"PartnerCreditOverride\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? ORDER BY \"createdAt\" DESC LIMIT 50", org(), partnerId);
    }

    // ------------------------------------------------------------------ reconciliation

    /**
     * ADR-S022 §6: per customer with a limit or a hold, the profile limit, the central exposure by bucket, the exposure
     * the retired SALES-1 formula gave (receivables + unbilled orders + manual delivery orders) and the V211 log.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> reconciliation() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : db.rows("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS"
                + " NULL AND (\"creditLimit\" > 0 OR \"creditHold\" OR EXISTS (SELECT 1 FROM \"CreditLimit\" l WHERE"
                + " l.\"partnerId\" = \"Partner\".id)) ORDER BY name LIMIT 1000", org())) {
            String id = str(p.get("id"));
            Map<String, Object> e = exposure(id);
            // The retired SALES-1 formula, verbatim: receivables + unbilled orders + manual delivery orders.
            Map<String, Object> legacy = db.row("""
                    SELECT (SELECT COALESCE(sum("amountResidual"), 0) FROM "Document" WHERE "organizationId" = ?
                              AND "partnerId" = ? AND "documentType"::text = 'sales_invoice'
                              AND status::text IN ('posted', 'paid') AND "amountResidual" > 0)
                         + (SELECT COALESCE(sum("amountResidual"), 0) FROM "Invoice" WHERE "organizationId" = ?
                              AND "partnerId" = ? AND status::text IN ('posted', 'paid') AND "amountResidual" > 0)
                         + (SELECT COALESCE(sum(o."totalAmount"), 0) FROM "Order" o WHERE o."organizationId" = ?
                              AND o."partnerId" = ? AND o."invoiceId" IS NULL
                              AND o.status::text NOT IN ('draft', 'completed', 'closed', 'cancelled')
                              AND (o."paymentMethod" IS NULL
                                   OR o."paymentMethod"::text NOT IN ('cash', 'card', 'mobile_money'))
                              AND NOT EXISTS (SELECT 1 FROM "FleetDeliveryOrder" f WHERE f."organizationId" = o."organizationId"
                                   AND f."sourceType" = 'sales_order' AND f."sourceId" = o.id
                                   AND f."goodsInvoiceId" IS NOT NULL))
                         + (SELECT COALESCE(sum((l."orderedQty" - l."returnedQty") * COALESCE(l."unitPrice", 0)), 0)
                              FROM "FleetDeliveryOrderLine" l JOIN "FleetDeliveryOrder" f ON f.id = l."deliveryOrderId"
                             WHERE f."organizationId" = ? AND f."partnerId" = ? AND f."sourceType" = 'manual'
                               AND f."goodsInvoiceId" IS NULL
                               AND f.status IN ('open', 'planned', 'in_progress', 'delivered')) AS total""",
                    org(), id, org(), id, org(), id, org(), id);
            BigDecimal legacyTotal = dec(legacy.get("total"));
            Map<String, Object> row = new LinkedHashMap<>(e);
            row.put("legacyExposure", JsNumbers.js(legacyTotal));
            row.put("difference", JsNumbers.js(dec(e.get("exposure")).subtract(legacyTotal)));
            row.put("migration", db.row("SELECT \"partnerLimit\", \"salesLimit\", \"resultLimit\", note, \"createdAt\""
                    + " FROM \"CreditMigrationLog\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? ORDER BY"
                    + " \"createdAt\" DESC LIMIT 1", org(), id));
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ policy

    /** Policy without creating the row (defaults when absent) — safe in read-only transactions. */
    Map<String, Object> currentPolicy() {
        Map<String, Object> p = db.row("SELECT * FROM \"CreditPolicy\" WHERE \"organizationId\" = ?", org());
        return p != null ? p : Obj.of("overdueDays", 30, "overdueAction", "approval", "depositsReduceExposure", true,
                "undeliveredBasis", "gross", "groupLimits", true, "checkpoints", List.of("quote_submit", "order_confirm",
                        "amendment", "trip_dispatch", "pos_credit_sale", "delivery_order", "invoice_post"),
                "overdueCheckpoints", SALES_CHECKPOINTS, "autoHold", false);
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
            List<String> cps = checkpoints(in.get("checkpoints"));
            if (!cps.contains("order_confirm")) {
                cps.add("order_confirm");
            }
            d.put("checkpoints", cps);
        }
        if (in.containsKey("overdueCheckpoints")) {
            d.put("overdueCheckpoints", checkpoints(in.get("overdueCheckpoints")));
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

    private static List<String> checkpoints(Object raw) {
        List<String> cps = new ArrayList<>(CreditDb.strings(raw));
        for (String c : cps) {
            if (!CHECKPOINTS.contains(c)) {
                throw ApiException.badRequest("Unknown credit check point: " + c);
            }
        }
        return cps;
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
