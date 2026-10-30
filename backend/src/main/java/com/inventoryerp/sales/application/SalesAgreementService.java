package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales agreements (ADR-S011 phase S9): blanket orders and contracts with a customer — agreed price, contract quantity
 * and validity per product. Sales orders that carry {@code agreementId} are its releases: they are priced from the
 * agreement first (pricing engine tier 0) and consume its balance once confirmed. A release beyond the balance (or
 * below the minimum release quantity) needs commercial approval unless the agreement allows over-release.
 *
 * <p>Released quantity = confirmed / in-fulfilment / done order lines of the agreement, net of quantities closed
 * short, in the product's base unit. States: draft → active → expired | closed; draft / active → cancelled.
 */
@Service
public class SalesAgreementService {

    static final Set<String> RELEASED = Set.of("confirmed", "in_fulfilment", "done");

    private final SalesDb db;
    private final SequenceService sequence;
    private final AuditService audit;

    public SalesAgreementService(SalesDb db, SequenceService sequence, AuditService audit) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder sql = new StringBuilder("SELECT a.*, p.name AS \"partnerName\", (SELECT count(*) FROM"
                + " \"SalesOrder\" o WHERE o.\"agreementId\" = a.id AND o.\"deletedAt\" IS NULL AND o.status <> 'cancelled')"
                + " AS releases FROM \"SalesAgreement\" a LEFT JOIN \"Partner\" p ON p.id = a.\"partnerId\" WHERE"
                + " a.\"organizationId\" = ?");
        if (!blank(q.get("status"))) {
            sql.append(" AND a.status = ?");
            params.add(q.get("status"));
        }
        if (!blank(q.get("partnerId"))) {
            sql.append(" AND a.\"partnerId\" = ?");
            params.add(q.get("partnerId"));
        }
        if (!blank(q.get("expiringWithin"))) {
            sql.append(" AND a.status = 'active' AND a.\"endDate\" <= ?");
            params.add(LocalDate.now().plusDays(SalesDb.parseInt(q.get("expiringWithin"), 30)));
        }
        sql.append(" ORDER BY a.\"endDate\", a.number");
        return db.rows(sql.toString(), params);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> a = db.row("SELECT a.*, p.name AS \"partnerName\" FROM \"SalesAgreement\" a LEFT JOIN"
                + " \"Partner\" p ON p.id = a.\"partnerId\" WHERE a.id = ? AND a.\"organizationId\" = ?", id, org());
        if (a == null) {
            throw ApiException.notFound("Agreement not found");
        }
        Map<String, BigDecimal> released = released(id);
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.name AS \"productName\", pr.code AS \"productCode\","
                + " u.code AS \"uomCode\" FROM \"SalesAgreementLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\""
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"agreementId\" = ? ORDER BY pr.name", id);
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal releasedValue = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            BigDecimal r = released.getOrDefault(str(l.get("productId")), BigDecimal.ZERO);
            BigDecimal contract = dec(l.get("contractQty"));
            l.put("releasedQty", JsNumbers.js(r));
            l.put("balanceQty", JsNumbers.js(contract.subtract(r)));
            l.put("releasedPercent", JsNumbers.js(r.multiply(SalesPricingEngine.HUNDRED).divide(contract, 2,
                    java.math.RoundingMode.HALF_UP)));
            value = value.add(contract.multiply(dec(l.get("unitPrice"))));
            releasedValue = releasedValue.add(r.multiply(dec(l.get("unitPrice"))));
        }
        a.put("lines", lines);
        a.put("contractValue", JsNumbers.js(value));
        a.put("releasedValue", JsNumbers.js(releasedValue));
        a.put("releases", db.rows("SELECT id, number, status, total, \"confirmedAt\", \"createdAt\" FROM \"SalesOrder\""
                + " WHERE \"agreementId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY \"createdAt\"",
                id, org()));
        return a;
    }

    /** Released base quantity per product of confirmed releases. */
    Map<String, BigDecimal> released(String agreementId) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : db.rows("SELECT l.\"productId\", sum((l.quantity - l.\"qtyCancelled\") *"
                + " l.\"uomFactor\") AS qty FROM \"SalesOrderLine\" l JOIN \"SalesOrder\" o ON o.id = l.\"orderId\" WHERE"
                + " o.\"agreementId\" = ? AND o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL AND o.status IN"
                + " ('confirmed', 'in_fulfilment', 'done') AND l.\"productId\" IS NOT NULL GROUP BY l.\"productId\"",
                agreementId, org())) {
            out.put(str(r.get("productId")), dec(r.get("qty")));
        }
        return out;
    }

    // ------------------------------------------------------------------ write

    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String partnerId = str(in.get("partnerId"));
        if (blank(partnerId) || !db.exists("Partner", partnerId)) {
            throw ApiException.badRequest("partnerId must be an existing customer");
        }
        Map<String, Object> d = header(in, true);
        d.put("partnerId", partnerId);
        int year = LocalDate.now().getYear();
        d.put("number", sequence.next("sales_agreement:" + year, "AGR-" + year + "-", 5));
        d.put("createdById", user());
        Map<String, Object> row = db.insert("SalesAgreement", d);
        String id = str(row.get("id"));
        writeLines(id, in.get("lines"));
        audit.record(AuditInput.of("SalesAgreement", id, "create", null, Obj.of("number", row.get("number"))));
        return get(id);
    }

    /** Draft: everything; active: notes, end date (extension), over-release flag, sites only. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> a = lock(id);
        String status = str(a.get("status"));
        if (!Set.of("draft", "active").contains(status)) {
            throw ApiException.badRequest("Only draft or active agreements can be edited");
        }
        Map<String, Object> d = header(in, false);
        if ("active".equals(status)) {
            for (String k : List.of("name", "kind", "startDate", "currencyCode", "paymentTermId")) {
                if (d.containsKey(k)) {
                    throw ApiException.badRequest("An active agreement only accepts changes to its end date, sites,"
                            + " notes and over-release; cancel it and start a new one instead");
                }
            }
            if (in.containsKey("lines")) {
                throw ApiException.badRequest("Lines of an active agreement cannot be changed");
            }
        }
        LocalDate start = d.containsKey("startDate") ? (LocalDate) d.get("startDate") : (LocalDate) a.get("startDate");
        LocalDate end = d.containsKey("endDate") ? (LocalDate) d.get("endDate") : (LocalDate) a.get("endDate");
        if (end.isBefore(start)) {
            throw ApiException.badRequest("endDate must not be before startDate");
        }
        db.patch("SalesAgreement", id, d, true);
        if (in.containsKey("lines")) {
            writeLines(id, in.get("lines"));
        }
        audit.record(AuditInput.of("SalesAgreement", id, "update", null, Obj.of("fields", d.keySet())));
        return get(id);
    }

    private Map<String, Object> header(Map<String, Object> in, boolean create) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (create || in.containsKey("name")) {
            if (blank(str(in.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            d.put("name", str(in.get("name")));
        }
        if (in.containsKey("kind")) {
            if (!Set.of("blanket", "contract").contains(str(in.get("kind")))) {
                throw ApiException.badRequest("kind must be one of: blanket, contract");
            }
            d.put("kind", str(in.get("kind")));
        }
        for (String k : List.of("startDate", "endDate")) {
            if (create || in.containsKey(k)) {
                LocalDate v = SalesDb.date(str(in.get(k)));
                if (v == null) {
                    throw ApiException.badRequest(k + " should not be empty");
                }
                d.put(k, v);
            }
        }
        if (create && ((LocalDate) d.get("endDate")).isBefore((LocalDate) d.get("startDate"))) {
            throw ApiException.badRequest("endDate must not be before startDate");
        }
        for (String k : List.of("currencyCode", "paymentTermId", "notes")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (d.get("paymentTermId") != null && !db.exists("PaymentTerm", str(d.get("paymentTermId")))) {
            throw ApiException.notFound("Payment term not found");
        }
        if (in.containsKey("siteIds")) {
            d.put("siteIds", SalesDb.strings(in.get("siteIds")));
        }
        if (in.containsKey("allowOverRelease")) {
            d.put("allowOverRelease", Boolean.TRUE.equals(in.get("allowOverRelease")));
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private void writeLines(String id, Object raw) {
        db.update("DELETE FROM \"SalesAgreementLine\" WHERE \"agreementId\" = ? AND \"organizationId\" = ?", id, org());
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> l : lines) {
            String productId = str(l.get("productId"));
            if (blank(productId) || !db.exists("Product", productId)) {
                throw ApiException.notFound("Product not found");
            }
            if (!seen.add(productId)) {
                throw ApiException.badRequest("A product can appear only once in an agreement");
            }
            BigDecimal qty = dec(l.get("contractQty"));
            if (qty.signum() <= 0) {
                throw ApiException.badRequest("contractQty must be greater than 0");
            }
            BigDecimal price = dec(l.get("unitPrice"));
            if (l.get("unitPrice") == null || price.signum() < 0) {
                throw ApiException.badRequest("unitPrice must not be less than 0");
            }
            BigDecimal disc = dec(l.get("discountPercent"));
            if (disc.signum() < 0 || disc.compareTo(SalesPricingEngine.HUNDRED) > 0) {
                throw ApiException.badRequest("discountPercent must be between 0 and 100");
            }
            db.insert("SalesAgreementLine", Obj.of("agreementId", id, "productId", productId, "uomId",
                    blank(str(l.get("uomId"))) ? null : str(l.get("uomId")), "contractQty", qty, "unitPrice", price,
                    "discountPercent", disc, "minReleaseQty", dec(l.get("minReleaseQty"))));
        }
    }

    /** Draft → active (the agreement starts pricing its releases). */
    @Transactional
    public Map<String, Object> activate(String id) {
        Map<String, Object> a = lock(id);
        if (!"draft".equals(a.get("status"))) {
            throw ApiException.badRequest("Only draft agreements can be activated");
        }
        if (db.count("SELECT count(*) FROM \"SalesAgreementLine\" WHERE \"agreementId\" = ?", List.of(id)) == 0) {
            throw ApiException.badRequest("Add at least one line before activating");
        }
        if (((LocalDate) a.get("endDate")).isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Agreement has already ended");
        }
        db.patch("SalesAgreement", id, Obj.of("status", "active", "approvedById", user(), "approvedAt", Instant.now()),
                true);
        audit.record(AuditInput.of("SalesAgreement", id, "approve", null, Obj.of("status", "active")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> close(String id) {
        return transition(id, Set.of("active", "expired"), "closed", "Only active or expired agreements can be closed");
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> a = lock(id);
        if (db.count("SELECT count(*) FROM \"SalesOrder\" WHERE \"agreementId\" = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND status IN ('confirmed', 'in_fulfilment', 'done')", List.of(id, org())) > 0) {
            throw ApiException.badRequest("Agreement has confirmed releases; close it instead");
        }
        return transition(str(a.get("id")), Set.of("draft", "active"), "cancelled",
                "Only draft or active agreements can be cancelled");
    }

    private Map<String, Object> transition(String id, Set<String> from, String to, String message) {
        Map<String, Object> a = lock(id);
        if (!from.contains(str(a.get("status")))) {
            throw ApiException.badRequest(message);
        }
        db.patch("SalesAgreement", id, Obj.of("status", to), true);
        audit.record(AuditInput.of("SalesAgreement", id, "update", null, Obj.of("status", to)));
        return get(id);
    }

    /** Active agreements whose end date has passed become expired (daily job). */
    @Transactional
    public int expireDue(LocalDate asOf) {
        return db.update("UPDATE \"SalesAgreement\" SET status = 'expired', \"updatedAt\" = " + SalesDb.NOW + " WHERE"
                + " \"organizationId\" = ? AND status = 'active' AND \"endDate\" < ?", org(), asOf);
    }

    private Map<String, Object> lock(String id) {
        Map<String, Object> a = db.row("SELECT * FROM \"SalesAgreement\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE",
                id, org());
        if (a == null) {
            throw ApiException.notFound("Agreement not found");
        }
        return a;
    }

    // ------------------------------------------------------------------ releases (sales orders)

    /** Check that {@code agreementId} can take releases for {@code partnerId}; returns the agreement. */
    @Transactional(readOnly = true)
    public Map<String, Object> requireReleasable(String agreementId, String partnerId) {
        Map<String, Object> a = db.row("SELECT * FROM \"SalesAgreement\" WHERE id = ? AND \"organizationId\" = ?",
                agreementId, org());
        if (a == null) {
            throw ApiException.notFound("Agreement not found");
        }
        if (!"active".equals(a.get("status"))) {
            throw ApiException.badRequest("Agreement " + a.get("number") + " is not active");
        }
        if (partnerId != null && !partnerId.equals(a.get("partnerId"))) {
            throw ApiException.badRequest("Agreement " + a.get("number") + " belongs to another customer");
        }
        LocalDate today = LocalDate.now();
        if (today.isBefore((LocalDate) a.get("startDate")) || today.isAfter((LocalDate) a.get("endDate"))) {
            throw ApiException.badRequest("Agreement " + a.get("number") + " is not valid today");
        }
        return a;
    }

    /**
     * Approval reasons of releasing {@code lines} of order {@code o} against its agreement: quantity beyond the
     * remaining balance (unless over-release is allowed), below the minimum release quantity, or the agreement no
     * longer valid.
     */
    List<String> releaseReasons(Map<String, Object> o, List<Map<String, Object>> lines) {
        String agreementId = str(o.get("agreementId"));
        List<String> out = new ArrayList<>();
        if (agreementId == null) {
            return out;
        }
        Map<String, Object> a = db.row("SELECT * FROM \"SalesAgreement\" WHERE id = ? AND \"organizationId\" = ?",
                agreementId, org());
        LocalDate today = LocalDate.now();
        if (a == null || !"active".equals(a.get("status")) || today.isBefore((LocalDate) a.get("startDate"))
                || today.isAfter((LocalDate) a.get("endDate"))) {
            out.add("Agreement " + (a == null ? "" : a.get("number")) + " is not active today");
            return out;
        }
        Map<String, BigDecimal> released = released(agreementId);
        Map<String, BigDecimal> thisOrder = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") != null) {
                BigDecimal base = dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled"))).multiply(
                        l.get("uomFactor") == null ? BigDecimal.ONE : dec(l.get("uomFactor")));
                thisOrder.merge(str(l.get("productId")), base, BigDecimal::add);
                names.putIfAbsent(str(l.get("productId")), str(l.get("description")));
            }
        }
        for (Map<String, Object> al : db.rows("SELECT * FROM \"SalesAgreementLine\" WHERE \"agreementId\" = ?",
                agreementId)) {
            String product = str(al.get("productId"));
            BigDecimal qty = thisOrder.get(product);
            if (qty == null) {
                continue;
            }
            BigDecimal balance = dec(al.get("contractQty")).subtract(released.getOrDefault(product, BigDecimal.ZERO));
            if (!Boolean.TRUE.equals(a.get("allowOverRelease")) && qty.compareTo(balance) > 0) {
                out.add("Release of " + names.get(product) + " (" + qty.stripTrailingZeros().toPlainString()
                        + ") exceeds the agreement balance of " + balance.max(BigDecimal.ZERO).stripTrailingZeros()
                                .toPlainString());
            }
            BigDecimal min = dec(al.get("minReleaseQty"));
            if (min.signum() > 0 && qty.compareTo(min) < 0) {
                out.add("Release of " + names.get(product) + " is below the minimum release quantity of "
                        + min.stripTrailingZeros().toPlainString());
            }
        }
        return out;
    }
}
