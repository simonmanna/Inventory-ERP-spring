package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.orders.application.OrdersService;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales quotations (ADR-S011, moved from the CRM suite of ADR-S010 in phase S0; same ids, numbers and JSON). Lines
 * are priced through {@link QuotationPricingPort} (CRM customer price rules until S2) and taxed
 * by the invoicing tax engine. Lifecycle: {@code draft → (pending_approval → approved) → sent → accepted →
 * converted}, with {@code rejected} (internal approval), {@code declined}-as-{@code rejected} by the customer,
 * {@code expired} and {@code cancelled}. Submitting checks the discount threshold, the amount threshold and the
 * customer's credit (hold, limit vs. open receivables + this quote); any breach needs approval through the kernel
 * approval engine when a workflow exists, else by a {@code crm:quotation:approve} holder. An accepted quote converts
 * into a back-office Order or a proforma invoice; the opportunity can be marked won automatically.
 */
@Service
public class SalesQuotationService {

    /** Approval entity of quotations; the legacy CRM name is kept until S2 rebuilds the approval rules. */
    public static final String APPROVAL_ENTITY = "crm_quotation";
    /** Entity type accepted from S2 on. */
    public static final String SALES_APPROVAL_ENTITY = "sales_quotation";
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Set<String> EDITABLE = Set.of("draft", "rejected");

    private final SalesDb db;
    private final SalesSettingsService config;
    private final ObjectProvider<QuotationPricingPort> pricing;
    private final ObjectProvider<QuotationOpportunityPort> opportunities;
    private final DocumentBuilderService builder;
    private final OrdersService orders;
    private final ApprovalsService approvals;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public SalesQuotationService(SalesDb db, SalesSettingsService config, ObjectProvider<QuotationPricingPort> pricing,
            ObjectProvider<QuotationOpportunityPort> opportunities, DocumentBuilderService builder,
            OrdersService orders,
            ApprovalsService approvals, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.config = config;
        this.pricing = pricing;
        this.opportunities = opportunities;
        this.builder = builder;
        this.orders = orders;
        this.approvals = approvals;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    private static final String SELECT = """
            SELECT q.*, p.name AS "partnerName", d.name AS "dealName",
                   COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email) AS "ownerName"
              FROM "SalesQuotation" q
              LEFT JOIN "Partner" p ON p.id = q."partnerId"
              LEFT JOIN "Deal" d ON d.id = q."dealId"
              LEFT JOIN "User" u ON u.id = q."ownerId"
            """;

    // ------------------------------------------------------------------ queries

    /** Paginated list {@code {data, meta, totals}}; expired quotes are flagged on read. */
    @Transactional
    public Map<String, Object> list(Map<String, String> p) {
        expireOverdue();
        StringBuilder where = new StringBuilder(" WHERE q.\"organizationId\" = ? AND q.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        visibility(where, params);
        for (String k : List.of("status", "partnerId", "dealId", "ownerId")) {
            if (!blank(p.get(k))) {
                if ("status".equals(k)) {
                    List<String> st = List.of(p.get(k).split(","));
                    where.append(" AND q.status IN (").append(SalesDb.marks(st.size())).append(')');
                    params.addAll(st);
                } else {
                    where.append(" AND q.\"").append(k).append("\" = ?");
                    params.add(p.get(k));
                }
            }
        }
        if (!blank(p.get("q"))) {
            String like = "%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (q.number ILIKE ? OR p.name ILIKE ? OR d.name ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        if (!blank(p.get("from"))) {
            where.append(" AND q.\"issueDate\" >= ?");
            params.add(SalesDb.date(p.get("from")));
        }
        if (!blank(p.get("to"))) {
            where.append(" AND q.\"issueDate\" <= ?");
            params.add(SalesDb.date(p.get("to")));
        }
        int page = Math.max(1, SalesDb.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, SalesDb.parseInt(p.get("pageSize"), 50)));
        List<Map<String, Object>> rows = db.rows(SELECT + where + " ORDER BY q.\"createdAt\" DESC OFFSET "
                + (long) (page - 1) * pageSize + " LIMIT " + pageSize, params);
        Map<String, Object> totals = db.row("SELECT count(*) AS count, COALESCE(sum(q.total), 0) AS total FROM"
                + " \"SalesQuotation\" q LEFT JOIN \"Partner\" p ON p.id = q.\"partnerId\" LEFT JOIN \"Deal\" d ON d.id ="
                + " q.\"dealId\"" + where, params.toArray());
        long total = ((Number) totals.get("count")).longValue();
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))), "totals", totals);
    }

    /** Quote with lines (+ stock availability), customer credit position and approval state. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> q = require(id);
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.name AS \"productName\", pr.code AS \"productCode\","
                + " pr.\"trackInventory\", pr.\"productType\"::text AS \"productType\", t.name AS \"taxName\","
                + " t.rate AS \"taxRate\" FROM \"SalesQuotationLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\""
                + " LEFT JOIN \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"quotationId\" = ? ORDER BY l.\"sortOrder\"", id);
        Map<String, Map<String, Object>> stock = stock(lines.stream().map(l -> str(l.get("productId")))
                .filter(s -> s != null).distinct().toList());
        for (Map<String, Object> l : lines) {
            Map<String, Object> s = stock.get(str(l.get("productId")));
            if (s != null && Boolean.TRUE.equals(l.get("trackInventory"))) {
                double available = ((Number) s.get("available")).doubleValue();
                double qty = ((Number) l.get("quantity")).doubleValue();
                l.put("stock", Obj.of("onHand", s.get("onHand"), "reserved", s.get("reserved"), "available",
                        s.get("available"), "sufficient", available >= qty));
            } else {
                l.put("stock", null);
            }
        }
        q.put("lines", lines);
        q.put("credit", credit(str(q.get("partnerId")), BigDecimal.ZERO));
        q.put("canApprove", canApprove());
        return q;
    }

    /** Available-to-promise per product (all locations): on hand − active reservations. */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> stock(List<String> productIds) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        List<Object> params = new ArrayList<>(List.of(org(), org()));
        params.addAll(productIds);
        for (Map<String, Object> r : db.rows("SELECT pid AS \"productId\", COALESCE(oh.qty, 0) AS \"onHand\","
                + " COALESCE(rs.qty, 0) AS reserved, COALESCE(oh.qty, 0) - COALESCE(rs.qty, 0) AS available FROM"
                + " unnest(?::text[]) pid LEFT JOIN (SELECT \"productId\", sum(quantity) AS qty FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? GROUP BY 1) oh ON oh.\"productId\" = pid LEFT JOIN (SELECT \"productId\","
                + " sum(quantity) AS qty FROM \"StockReservation\" WHERE \"organizationId\" = ? AND status = 'active' GROUP"
                + " BY 1) rs ON rs.\"productId\" = pid", productIds.toArray(String[]::new), org(), org())) {
            out.put(str(r.get("productId")), r);
        }
        return out;
    }

    /** Customer credit position: limit, hold, open receivables (posted, unpaid sales invoices) and headroom. */
    @Transactional(readOnly = true)
    public Map<String, Object> credit(String partnerId, BigDecimal extra) {
        Map<String, Object> p = db.row("SELECT \"creditLimit\", \"creditHold\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?", partnerId, org());
        if (p == null) {
            return null;
        }
        Map<String, Object> ar = db.row("SELECT COALESCE(sum(\"amountResidual\"), 0) AS open, COALESCE(sum("
                + "\"amountResidual\") FILTER (WHERE \"dueDate\" < now()), 0) AS overdue FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\"::text = 'sales_invoice' AND"
                + " status::text NOT IN ('draft', 'cancelled', 'rejected') AND \"amountResidual\" > 0", org(), partnerId);
        BigDecimal limit = dec(p.get("creditLimit"));
        BigDecimal open = dec(ar.get("open"));
        BigDecimal exposure = open.add(extra);
        boolean exceeded = limit.signum() > 0 && exposure.compareTo(limit) > 0;
        return Obj.of("creditLimit", JsNumbers.js(limit), "creditHold", Boolean.TRUE.equals(p.get("creditHold")),
                "openReceivables", JsNumbers.js(open), "overdueReceivables", ar.get("overdue"), "headroom",
                limit.signum() > 0 ? JsNumbers.js(limit.subtract(open)) : null, "exceeded", exceeded);
    }

    // ------------------------------------------------------------------ create / edit

    /** New draft quote; lines without a price are priced by the customer's price rules. */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String dealId = str(in.get("dealId"));
        Map<String, Object> deal = blank(dealId) ? null : opportunity(dealId);
        String partnerId = !blank(str(in.get("partnerId"))) ? str(in.get("partnerId"))
                : deal == null ? null : str(deal.get("partnerId"));
        if (blank(partnerId)) {
            throw ApiException.badRequest("A quotation needs a customer; convert the lead or set the customer first");
        }
        if (!db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
        Map<String, Object> settings = config.settings();
        LocalDate issue = in.get("issueDate") == null ? LocalDate.now() : SalesDb.date(str(in.get("issueDate")));
        LocalDate valid = in.get("validUntil") == null
                ? issue.plusDays(((Number) settings.get("quoteValidityDays")).longValue())
                : SalesDb.date(str(in.get("validUntil")));
        int year = issue.getYear();
        String number = sequence.next("crm_quotation:" + year, "QT-" + year + "-", 5);
        String currency = !blank(str(in.get("currencyCode"))) ? str(in.get("currencyCode"))
                : deal != null ? str(deal.get("currencyCode")) : orgCurrency();
        String owner = !blank(str(in.get("ownerId"))) ? str(in.get("ownerId"))
                : deal != null && deal.get("ownerId") != null ? str(deal.get("ownerId")) : user();
        Map<String, Object> row = db.insert("SalesQuotation", Obj.of("number", number, "dealId", blank(dealId) ? null
                : dealId, "partnerId", partnerId, "contactId", blank(str(in.get("contactId"))) ? null
                : str(in.get("contactId")), "ownerId", owner, "issueDate", issue, "validUntil", valid, "currencyCode",
                currency, "notes", str(in.get("notes")), "terms", in.containsKey("terms") ? str(in.get("terms"))
                : str(settings.get("defaultQuoteTerms")), "createdById", user()));
        String id = str(row.get("id"));
        writeLines(id, partnerId, issue, in.get("lines"));
        audit.record(AuditInput.of("SalesQuotation", id, "create", null, Obj.of("number", number, "dealId", dealId)));
        return get(id);
    }

    /** Edit header and replace lines while draft (a rejected quote returns to draft). */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> q = lock(id);
        if (!EDITABLE.contains(q.get("status"))) {
            throw ApiException.badRequest("Only draft quotations can be edited; revise it to make changes");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (in.containsKey("partnerId")) {
            String p = str(in.get("partnerId"));
            if (blank(p) || !db.exists("Partner", p)) {
                throw ApiException.notFound("Partner not found");
            }
            d.put("partnerId", p);
        }
        for (String k : List.of("notes", "terms", "contactId", "currencyCode")) {
            if (in.containsKey(k)) {
                d.put(k, str(in.get(k)));
            }
        }
        if (in.containsKey("ownerId")) {
            d.put("ownerId", blank(str(in.get("ownerId"))) ? null : requireUser(str(in.get("ownerId"))));
        }
        if (in.containsKey("issueDate")) {
            d.put("issueDate", SalesDb.date(str(in.get("issueDate"))));
        }
        if (in.containsKey("validUntil")) {
            d.put("validUntil", SalesDb.date(str(in.get("validUntil"))));
        }
        d.put("status", "draft");
        d.put("approvalReasons", List.of());
        d.put("rejectionReason", null);
        d.put("approvalRequestId", null);
        db.patch("SalesQuotation", id, d, true);
        if (in.containsKey("lines")) {
            String partner = str(d.getOrDefault("partnerId", q.get("partnerId")));
            LocalDate issue = (LocalDate) d.getOrDefault("issueDate", q.get("issueDate"));
            writeLines(id, partner, issue, in.get("lines"));
        }
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("fields", d.keySet(), "lines",
                in.containsKey("lines"))));
        return get(id);
    }

    @SuppressWarnings("unchecked")
    private void writeLines(String id, String partnerId, LocalDate on, Object raw) {
        db.update("DELETE FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? AND \"organizationId\" = ?", id, org());
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<LineInput> inputs = new ArrayList<>();
        List<Map<String, Object>> meta = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            String productId = str(l.get("productId"));
            BigDecimal qty = dec(l.get("quantity"));
            if (qty.signum() <= 0) {
                throw ApiException.badRequest("quantity must be greater than 0");
            }
            QuotationPricingPort.Price price = blank(productId) ? null : price(partnerId, productId, qty, on);
            BigDecimal list = price == null ? decOr(l.get("unitPrice"), BigDecimal.ZERO) : price.listPrice();
            BigDecimal unit;
            BigDecimal disc;
            String source;
            if (l.get("unitPrice") != null || l.get("discountPercent") != null) {
                unit = decOr(l.get("unitPrice"), price == null ? BigDecimal.ZERO : price.unitPrice());
                disc = decOr(l.get("discountPercent"), price == null ? BigDecimal.ZERO : price.discountPercent());
                boolean same = price != null && unit.compareTo(price.unitPrice()) == 0
                        && disc.compareTo(price.discountPercent()) == 0;
                source = same ? price.source() : "manual";
            } else {
                unit = price == null ? BigDecimal.ZERO : price.unitPrice();
                disc = price == null ? BigDecimal.ZERO : price.discountPercent();
                source = price == null ? "manual" : price.source();
            }
            if (unit.signum() < 0) {
                throw ApiException.badRequest("unitPrice must not be less than 0");
            }
            if (disc.signum() < 0 || disc.compareTo(HUNDRED) > 0) {
                throw ApiException.badRequest("discountPercent must be between 0 and 100");
            }
            String description = !blank(str(l.get("description"))) ? str(l.get("description"))
                    : price != null ? price.description() : null;
            if (blank(description)) {
                throw ApiException.badRequest("description should not be empty");
            }
            String taxId = l.containsKey("taxId") ? str(l.get("taxId")) : price == null ? null : price.taxId();
            Boolean incl = l.get("taxInclusive") instanceof Boolean b ? b : price != null && price.taxInclusive();
            inputs.add(new LineInput(blank(productId) ? null : productId, null, null, null, null, description, qty, unit,
                    disc, null, null, null, "manual", blank(taxId) ? null : taxId, null, incl));
            meta.add(Obj.of("listPrice", list, "source", source, "ruleId", price == null || !"rule".equals(source)
                    ? null : price.ruleId()));
        }
        if (inputs.isEmpty()) {
            db.update("UPDATE \"SalesQuotation\" SET subtotal = 0, \"discountTotal\" = 0, \"taxAmount\" = 0, total = 0,"
                    + " \"maxDiscountPercent\" = 0, \"updatedAt\" = " + NOW + " WHERE id = ?", id);
            return;
        }
        Totals totals = builder.prepareLines(inputs);
        BigDecimal maxDisc = BigDecimal.ZERO;
        int i = 0;
        for (PreparedLine pl : totals.prepared()) {
            Map<String, Object> m = meta.get(i);
            BigDecimal list = (BigDecimal) m.get("listPrice");
            BigDecimal net = pl.unitPrice().multiply(BigDecimal.ONE.subtract(pl.discountPercent().divide(HUNDRED, 10,
                    RoundingMode.HALF_UP)));
            if (list.signum() > 0 && net.compareTo(list) < 0) {
                maxDisc = maxDisc.max(BigDecimal.ONE.subtract(net.divide(list, 10, RoundingMode.HALF_UP)).multiply(HUNDRED));
            }
            db.insert("SalesQuotationLine", Obj.of("quotationId", id, "sortOrder", i, "productId", pl.productId(),
                    "description", pl.description(), "quantity", pl.quantity(), "listPrice", list, "unitPrice",
                    pl.unitPrice(), "discountPercent", pl.discountPercent(), "priceSource", m.get("source"),
                    "priceRuleId", m.get("ruleId"), "taxId", pl.taxId(), "taxInclusive", pl.taxInclusive(),
                    "lineSubtotal", pl.subtotal(), "lineTax", pl.taxAmount(), "lineTotal", pl.total()));
            i++;
        }
        db.update("UPDATE \"SalesQuotation\" SET subtotal = ?, \"discountTotal\" = ?, \"taxAmount\" = ?, total = ?,"
                + " \"maxDiscountPercent\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?", totals.subtotal(),
                totals.discountTotal(), totals.taxAmount(), totals.total(), maxDisc.setScale(4, RoundingMode.HALF_UP), id);
    }

    // ------------------------------------------------------------------ approval

    /**
     * Submit for approval. Returns the quote: {@code approved} directly when no threshold is breached, else
     * {@code pending_approval} with the reasons.
     */
    @Transactional
    public Map<String, Object> submit(String id) {
        Map<String, Object> q = lock(id);
        if (!EDITABLE.contains(q.get("status"))) {
            throw ApiException.badRequest("Only draft quotations can be submitted");
        }
        if (db.count("SELECT count(*) FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ?", List.of(id)) == 0) {
            throw ApiException.badRequest("Add at least one line before submitting");
        }
        List<String> reasons = approvalReasons(q);
        if (reasons.isEmpty()) {
            db.patch("SalesQuotation", id, Obj.of("status", "approved", "approvalReasons", List.of(), "approvedAt",
                    java.time.Instant.now(), "approvedById", user()), true);
            audit.record(AuditInput.of("SalesQuotation", id, "approve", null, Obj.of("auto", true)));
            return get(id);
        }
        Map<String, Object> snapshot = Obj.of("number", q.get("number"), "partnerId", q.get("partnerId"), "total",
                q.get("total"), "amount", q.get("total"), "maxDiscountPercent", q.get("maxDiscountPercent"), "reasons",
                reasons);
        Map<String, Object> req = approvals.requestApproval(APPROVAL_ENTITY, id, snapshot, null);
        db.patch("SalesQuotation", id, Obj.of("status", "pending_approval", "approvalReasons", reasons,
                "approvalRequestId", req == null ? null : req.get("id"), "rejectionReason", null), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("status", "pending_approval", "reasons",
                reasons)));
        return get(id);
    }

    /** Why this quote needs approval (empty = within policy). */
    List<String> approvalReasons(Map<String, Object> q) {
        Map<String, Object> s = config.settings();
        List<String> reasons = new ArrayList<>();
        BigDecimal maxDisc = dec(q.get("maxDiscountPercent"));
        BigDecimal limit = dec(s.get("quoteMaxDiscountPercent"));
        // Discounts granted by price rules are pre-approved: only the part beyond the resolved price counts.
        BigDecimal extra = extraDiscount(str(q.get("id")), str(q.get("partnerId")), (LocalDate) q.get("issueDate"));
        if (extra.compareTo(limit) > 0) {
            reasons.add("Discount " + extra.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    + "% exceeds the " + limit.stripTrailingZeros().toPlainString() + "% limit");
        }
        BigDecimal amount = dec(s.get("quoteApprovalAmount"));
        BigDecimal total = dec(q.get("total"));
        if (amount.signum() > 0 && total.compareTo(amount) > 0) {
            reasons.add("Total exceeds the approval amount of " + amount.stripTrailingZeros().toPlainString());
        }
        if (Boolean.TRUE.equals(s.get("creditCheck"))) {
            Map<String, Object> c = credit(str(q.get("partnerId")), total);
            if (c != null && Boolean.TRUE.equals(c.get("creditHold"))) {
                reasons.add("Customer is on credit hold");
            } else if (c != null && Boolean.TRUE.equals(c.get("exceeded"))) {
                reasons.add("Customer credit limit would be exceeded");
            }
        }
        if (maxDisc.signum() < 0) {
            reasons.add("Invalid discount");
        }
        return reasons;
    }

    private BigDecimal extraDiscount(String id, String partnerId, LocalDate on) {
        BigDecimal max = BigDecimal.ZERO;
        for (Map<String, Object> l : db.rows("SELECT \"productId\", quantity, \"unitPrice\", \"discountPercent\","
                + " \"listPrice\" FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ?", id)) {
            BigDecimal net = dec(l.get("unitPrice")).multiply(BigDecimal.ONE.subtract(dec(l.get("discountPercent"))
                    .divide(HUNDRED, 10, RoundingMode.HALF_UP)));
            BigDecimal authorized = l.get("productId") == null ? dec(l.get("unitPrice"))
                    : price(partnerId, str(l.get("productId")), dec(l.get("quantity")), on).net();
            if (authorized.signum() > 0 && net.compareTo(authorized) < 0) {
                max = max.max(BigDecimal.ONE.subtract(net.divide(authorized, 10, RoundingMode.HALF_UP)).multiply(HUNDRED));
            }
        }
        return max;
    }

    /** Approve (needs {@code crm:quotation:approve}); also applied by the approval engine subscriber. */
    @Transactional
    public Map<String, Object> approve(String id, String comment) {
        Map<String, Object> q = lock(id);
        if (!"pending_approval".equals(q.get("status"))) {
            throw ApiException.badRequest("Quotation is not awaiting approval");
        }
        if (user() != null && user().equals(q.get("createdById")) && !TenantContext.has(Permissions.CRM__CONFIGURE)
                && !TenantContext.has(Permissions.SALES__CONFIGURE)) {
            throw ApiException.forbidden("You cannot approve your own quotation");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "approved", "approvedById", user(), "approvedAt",
                java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "approve", null, Obj.of("comment", comment)));
        events.publish(DomainEvents.CRM_QUOTATION_APPROVED, Obj.of("organizationId", org(), "quotationId", id,
                "number", q.get("number")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> q = lock(id);
        if (!"pending_approval".equals(q.get("status"))) {
            throw ApiException.badRequest("Quotation is not awaiting approval");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "rejected", "rejectedById", user(), "rejectionReason",
                blank(reason) ? "Rejected" : reason), true);
        audit.record(AuditInput.of("SalesQuotation", id, "reject", null, Obj.of("reason", reason)));
        return get(id);
    }

    /** Apply a kernel approval-engine decision (idempotent: ignored unless still pending). */
    @Transactional
    public void applyDecision(String id, boolean approved, String comment) {
        Map<String, Object> q = db.row("SELECT status FROM \"SalesQuotation\" WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        if (q == null || !"pending_approval".equals(q.get("status"))) {
            return;
        }
        db.patch("SalesQuotation", id, approved ? Obj.of("status", "approved", "approvedById", user(), "approvedAt",
                java.time.Instant.now()) : Obj.of("status", "rejected", "rejectedById", user(), "rejectionReason",
                blank(comment) ? "Rejected in approval workflow" : comment), true);
        audit.record(AuditInput.of("SalesQuotation", id, approved ? "approve" : "reject", null, Obj.of("via",
                "approval_engine")));
    }

    // ------------------------------------------------------------------ customer lifecycle

    /** Mark sent to the customer (approved quotes only); moves an opportunity in an early stage to proposal. */
    @Transactional
    public Map<String, Object> send(String id) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved quotations can be sent");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "sent", "sentAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "issue", null, Obj.of("status", "sent")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> accept(String id) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved or sent quotations can be accepted");
        }
        if (q.get("validUntil") instanceof LocalDate v && v.isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Quotation has expired; revise it first");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "accepted", "acceptedAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("status", "accepted")));
        return get(id);
    }

    /** Customer declined. */
    @Transactional
    public Map<String, Object> decline(String id, String reason) {
        Map<String, Object> q = lock(id);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved or sent quotations can be declined");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "rejected", "rejectionReason", blank(reason)
                ? "Declined by customer" : reason), true);
        audit.record(AuditInput.of("SalesQuotation", id, "reject", null, Obj.of("byCustomer", true, "reason", reason)));
        return get(id);
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> q = lock(id);
        if (Set.of("converted", "cancelled").contains(q.get("status"))) {
            throw ApiException.badRequest("Quotation cannot be cancelled");
        }
        db.patch("SalesQuotation", id, Obj.of("status", "cancelled"), true);
        audit.record(AuditInput.of("SalesQuotation", id, "cancel"));
        return get(id);
    }

    /** New revision back in draft (any state except converted). */
    @Transactional
    public Map<String, Object> revise(String id) {
        Map<String, Object> q = lock(id);
        if ("converted".equals(q.get("status"))) {
            throw ApiException.badRequest("A converted quotation cannot be revised");
        }
        snapshot(q, "revise");
        Map<String, Object> s = config.settings();
        db.patch("SalesQuotation", id, Obj.of("status", "draft", "revision", ((Number) q.get("revision")).intValue() + 1,
                "approvalReasons", List.of(), "approvalRequestId", null, "approvedById", null, "approvedAt", null,
                "rejectionReason", null, "sentAt", null, "acceptedAt", null, "validUntil", LocalDate.now().plusDays(
                        ((Number) s.get("quoteValidityDays")).longValue())), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("revision", "new")));
        return get(id);
    }

    /** Hard-delete a draft that was never submitted. */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> q = lock(id);
        if (!"draft".equals(q.get("status")) || q.get("approvalRequestId") != null) {
            throw ApiException.badRequest("Only draft quotations can be deleted; cancel it instead");
        }
        db.update("UPDATE \"SalesQuotation\" SET \"deletedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        audit.record(AuditInput.of("SalesQuotation", id, "delete"));
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ conversion

    /**
     * Convert an accepted (or approved / sent, which accepts it) quote into an Order ({@code target = order}) or a
     * proforma invoice ({@code target = proforma}). Stock is re-checked: products short of stock are reported, and
     * block the conversion unless {@code allowBackorder}.
     */
    @Transactional
    public Map<String, Object> convert(String id, Map<String, Object> in) {
        Map<String, Object> q = lock(id);
        String target = blank(str(in.get("target"))) ? "order" : str(in.get("target"));
        if (!Set.of("order", "proforma").contains(target)) {
            throw ApiException.badRequest("target must be one of: order, proforma");
        }
        if (!Set.of("approved", "sent", "accepted").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved, sent or accepted quotations can be converted");
        }
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.\"trackInventory\" FROM \"SalesQuotationLine\" l LEFT"
                + " JOIN \"Product\" pr ON pr.id = l.\"productId\" WHERE l.\"quotationId\" = ? ORDER BY l.\"sortOrder\"",
                id);
        if ("order".equals(target)) {
            List<String> shortages = shortages(lines);
            if (!shortages.isEmpty() && !Boolean.TRUE.equals(in.get("allowBackorder"))) {
                throw ApiException.withBody(org.springframework.http.HttpStatus.CONFLICT, Obj.of("statusCode", 409,
                        "message", "Insufficient stock for: " + String.join(", ", shortages), "error", "Conflict",
                        "shortages", shortages));
            }
        }
        String convertedId;
        String convertedNumber;
        if ("order".equals(target)) {
            List<Map<String, Object>> orderLines = new ArrayList<>();
            for (Map<String, Object> l : lines) {
                Map<String, Object> ol = new LinkedHashMap<>();
                ol.put("productId", l.get("productId"));
                ol.put("description", l.get("description"));
                ol.put("quantity", l.get("quantity"));
                ol.put("unitPrice", l.get("unitPrice"));
                ol.put("discountPercent", l.get("discountPercent"));
                ol.put("taxId", l.get("taxId"));
                ol.put("taxInclusive", l.get("taxInclusive"));
                ol.put("note", null);
                orderLines.add(ol);
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("partnerId", q.get("partnerId"));
            dto.put("orderType", "delivery");
            dto.put("notes", "From quotation " + q.get("number") + (q.get("notes") == null ? "" : " — " + q.get("notes")));
            dto.put("lines", orderLines);
            Map<String, Object> order = orders.createOrder(dto);
            convertedId = str(order.get("id"));
            convertedNumber = str(order.get("orderNumber"));
        } else {
            List<LineInput> inputs = new ArrayList<>();
            for (Map<String, Object> l : lines) {
                inputs.add(new LineInput(str(l.get("productId")), null, null, null, null, str(l.get("description")),
                        dec(l.get("quantity")), dec(l.get("unitPrice")), dec(l.get("discountPercent")), null, null, null,
                        "manual", str(l.get("taxId")), null, Boolean.TRUE.equals(l.get("taxInclusive"))));
            }
            HeaderInput h = new HeaderInput(str(q.get("partnerId")), LocalDate.now().toString(), q.get("validUntil") == null
                    ? null : q.get("validUntil").toString(), null, null, null, null, null, null, null, str(q.get("ownerId")),
                    null, null, null, null, null, str(q.get("number")), null, null, str(q.get("number")),
                    str(q.get("notes")), null, "crm_quotation", null);
            Map<String, Object> doc = builder.createDocument("proforma_invoice", h, inputs);
            convertedId = str(doc.get("id"));
            convertedNumber = str(doc.get("documentNumber"));
        }
        db.patch("SalesQuotation", id, Obj.of("status", "converted", "acceptedAt", q.get("acceptedAt") == null
                ? java.time.Instant.now() : q.get("acceptedAt"), "convertedType", target, "convertedId", convertedId,
                "convertedNumber", convertedNumber, "convertedAt", java.time.Instant.now()), true);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("status", "converted", "target", target,
                "convertedId", convertedId)));
        events.publish(DomainEvents.CRM_QUOTATION_CONVERTED, Obj.of("organizationId", org(), "quotationId", id,
                "convertedType", target, "convertedId", convertedId));
        String dealId = str(q.get("dealId"));
        if (!blank(dealId) && Boolean.TRUE.equals(config.settings().get("autoWinOnConversion"))) {
            QuotationOpportunityPort port = opportunities.getIfAvailable();
            if (port != null) {
                port.markWonIfOpen(dealId);
            }
        }
        Map<String, Object> out = get(id);
        out.put("converted", Obj.of("type", target, "id", convertedId, "number", convertedNumber));
        return out;
    }

    private List<String> shortages(List<Map<String, Object>> lines) {
        Map<String, BigDecimal> need = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") != null && Boolean.TRUE.equals(l.get("trackInventory"))) {
                need.merge(str(l.get("productId")), dec(l.get("quantity")), BigDecimal::add);
                names.put(str(l.get("productId")), str(l.get("description")));
            }
        }
        Map<String, Map<String, Object>> stock = stock(new ArrayList<>(need.keySet()));
        List<String> out = new ArrayList<>();
        need.forEach((pid, qty) -> {
            Map<String, Object> s = stock.get(pid);
            BigDecimal avail = s == null ? BigDecimal.ZERO : dec(s.get("available"));
            if (avail.compareTo(qty) < 0) {
                out.add(names.get(pid));
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Mark approved / sent quotes past their validity date as expired. */
    void expireOverdue() {
        db.update("UPDATE \"SalesQuotation\" SET status = 'expired', \"updatedAt\" = " + NOW + " WHERE \"organizationId\" ="
                + " ? AND status IN ('draft', 'approved', 'sent') AND \"validUntil\" < CURRENT_DATE AND \"deletedAt\" IS"
                + " NULL", org());
    }

    private void visibility(StringBuilder where, List<Object> params) {
        if (TenantContext.has(Permissions.CRM__DEAL_VIEW_ALL) || TenantContext.has(Permissions.SALES__ORDER_READ_ALL)) {
            return;
        }
        where.append(" AND (q.\"ownerId\" = ? OR q.\"createdById\" = ? OR q.\"ownerId\" IS NULL)");
        params.add(user());
        params.add(user());
    }

    private Map<String, Object> require(String id) {
        StringBuilder where = new StringBuilder(" WHERE q.id = ? AND q.\"organizationId\" = ? AND q.\"deletedAt\" IS"
                + " NULL");
        List<Object> params = new ArrayList<>(List.of(id, org()));
        visibility(where, params);
        Map<String, Object> q = db.row(SELECT + where, params.toArray());
        if (q == null) {
            throw ApiException.notFound("Quotation not found");
        }
        return q;
    }

    private Map<String, Object> lock(String id) {
        require(id);
        return db.row("SELECT * FROM \"SalesQuotation\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
    }

    private boolean canApprove() {
        return TenantContext.has(Permissions.CRM__QUOTATION_APPROVE)
                || TenantContext.has(Permissions.SALES__QUOTATION_APPROVE);
    }

    private Map<String, Object> opportunity(String dealId) {
        QuotationOpportunityPort port = opportunities.getIfAvailable();
        if (port == null) {
            throw ApiException.notFound("Opportunity not found");
        }
        return port.visibleOpportunity(dealId);
    }

    private QuotationPricingPort.Price price(String partnerId, String productId, BigDecimal qty, LocalDate on) {
        QuotationPricingPort port = pricing.getIfAvailable();
        if (port != null) {
            return port.price(partnerId, productId, qty, on);
        }
        Map<String, Object> p = db.row("SELECT name, \"salesPrice\", \"taxId\", \"taxInclusive\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", productId, org());
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        BigDecimal list = dec(p.get("salesPrice"));
        return new QuotationPricingPort.Price(list, list, BigDecimal.ZERO, "list", null, str(p.get("taxId")),
                Boolean.TRUE.equals(p.get("taxInclusive")), str(p.get("name")));
    }

    String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }

    private String requireUser(String id) {
        if (db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL", List.of(id, org())) == 0) {
            throw ApiException.notFound("User not found");
        }
        return id;
    }

    /** Immutable snapshot (header + lines) of the current revision, taken before a new revision starts. */
    private void snapshot(Map<String, Object> q, String reason) {
        String id = str(q.get("id"));
        Map<String, Object> header = SalesDb.json(q);
        header.put("lines", db.rows("SELECT * FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? ORDER BY"
                + " \"sortOrder\"", id));
        db.update("INSERT INTO \"SalesQuotationRevision\" (id, \"organizationId\", \"quotationId\", revision, status,"
                + " total, snapshot, reason, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT"
                + " (\"quotationId\", revision) DO NOTHING", SalesDb.newId(), org(), id,
                ((Number) q.get("revision")).intValue(), str(q.get("status")), dec(q.get("total")),
                SalesDb.toJson(header), reason, user());
    }

    /** Revision snapshots, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> revisions(String id) {
        require(id);
        return db.rows("SELECT id, revision, status, total, reason, \"createdById\", \"createdAt\", snapshot FROM"
                + " \"SalesQuotationRevision\" WHERE \"quotationId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " revision DESC", id, org());
    }

    private static BigDecimal decOr(Object v, BigDecimal fallback) {
        BigDecimal d = SalesDb.decOrNull(v);
        return d == null ? fallback : d;
    }
}
