package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.core.application.PriceSnapshotService;
import com.inventoryerp.core.application.ProductSizingService;
import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
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
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * B2B sales orders (ADR-S011, phase S1). Lifecycle {@code draft → (pending_approval) → confirmed → in_fulfilment →
 * done}, {@code cancelled}; {@code onHold} overlays an open order (credit / manual). Draft orders are edited in
 * place; confirmed orders change only through {@link SalesOrderChangeService} (immutable revisions). Ordered and
 * closed-short quantities are owned here; delivered / billable / invoiced / reserved / returned columns are
 * projections of the owning modules, maintained by later phases.
 */
@Service
public class SalesOrderService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final Set<String> OPEN = Set.of("confirmed", "in_fulfilment");

    private static final String SELECT = """
            SELECT o.*, p.name AS "partnerName", q.number AS "quotationNumber",
                   COALESCE(NULLIF(trim(concat_ws(' ', u."firstName", u."lastName")), ''), u.email) AS "ownerName"
              FROM "SalesOrder" o
              LEFT JOIN "Partner" p ON p.id = o."partnerId"
              LEFT JOIN "SalesQuotation" q ON q.id = o."quotationId"
              LEFT JOIN "User" u ON u.id = o."ownerId"
            """;

    private final SalesDb db;
    private final SalesSettingsService settings;
    private final SalesLinePricing pricing;
    private final SalesPricingEngine engine;
    private final ApprovalsService approvals;
    private final CreditService credit;
    private final PriceSnapshotService snapshots;
    private final SalesAtpService atp;
    private final DocumentBuilderService builder;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final ProductVariantService variants;
    private ProductSizingService sizing;

    /** Sell by size (ADR-S066). */
    @org.springframework.beans.factory.annotation.Autowired
    void setSizing(ProductSizingService sizing) {
        this.sizing = sizing;
    }
    private SalesAgreementService agreements;
    private SalesAccess access;
    private SalesAlerts alerts;
    private SalesCreditRequestService creditRequests;

    @org.springframework.beans.factory.annotation.Autowired
    void setAlerts(SalesAlerts alerts, @org.springframework.context.annotation.Lazy SalesCreditRequestService creditRequests) {
        this.alerts = alerts;
        this.creditRequests = creditRequests;
    }

    public SalesOrderService(SalesDb db, SalesSettingsService settings, SalesLinePricing pricing,
            SalesPricingEngine engine, ApprovalsService approvals, CreditService credit, SalesAtpService atp,
            DocumentBuilderService builder, SequenceService sequence, AuditService audit, EventBus events,
            PriceSnapshotService snapshots, ProductVariantService variants) {
        this.variants = variants;
        this.snapshots = snapshots;
        this.credit = credit;
        this.atp = atp;
        this.engine = engine;
        this.approvals = approvals;
        this.db = db;
        this.settings = settings;
        this.pricing = pricing;
        this.builder = builder;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ queries

    /** Paginated list {@code {data, meta, totals}}. Filters: status (csv), partnerId, ownerId, onHold, q, from, to. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE o.\"organizationId\" = ? AND o.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        visibility(where, params);
        if (!blank(p.get("status"))) {
            List<String> st = List.of(p.get("status").split(","));
            where.append(" AND o.status IN (").append(SalesDb.marks(st.size())).append(')');
            params.addAll(st);
        }
        for (String k : List.of("partnerId", "ownerId", "quotationId", "branchId")) {
            if (!blank(p.get(k))) {
                where.append(" AND o.\"").append(k).append("\" = ?");
                params.add(p.get(k));
            }
        }
        if ("true".equals(p.get("onHold"))) {
            where.append(" AND o.\"onHold\"");
        }
        if ("true".equals(p.get("toFulfil"))) {
            where.append(" AND o.status IN ('confirmed', 'in_fulfilment') AND o.\"deliveryStatus\" <> 'full'");
        }
        if (!blank(p.get("q"))) {
            String like = "%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(p.get("q").trim()) + "%";
            where.append(" AND (o.number ILIKE ? OR p.name ILIKE ? OR o.\"customerRef\" ILIKE ?)");
            params.addAll(List.of(like, like, like));
        }
        if (!blank(p.get("from"))) {
            where.append(" AND o.\"createdAt\" >= ?");
            params.add(SalesDb.date(p.get("from")));
        }
        if (!blank(p.get("to"))) {
            where.append(" AND o.\"createdAt\" < ?::date + 1");
            params.add(SalesDb.date(p.get("to")));
        }
        int page = Math.max(1, SalesDb.parseInt(p.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, SalesDb.parseInt(p.get("pageSize"), 50)));
        List<Map<String, Object>> rows = db.rows(SELECT + where + " ORDER BY o.\"createdAt\" DESC OFFSET "
                + (long) (page - 1) * pageSize + " LIMIT " + pageSize, params);
        Map<String, Object> totals = db.row("SELECT count(*) AS count, COALESCE(sum(o.total), 0) AS total FROM"
                + " \"SalesOrder\" o LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\"" + where, params.toArray());
        long total = ((Number) totals.get("count")).longValue();
        return Obj.of("data", rows, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))), "totals", totals);
    }

    /** Order with lines (+ quantity breakdown), linked documents (smart buttons) and permissions of the caller. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> o = require(id);
        List<Map<String, Object>> lines = lines(id);
        o.put("lines", lines);
        if ("draft".equals(o.get("status"))) {
            o.put("stockWarnings", atp.shortages(id));
        }
        o.put("relations", relations(o));
        o.put("canAmend", OPEN.contains(o.get("status")) && TenantContext.has(Permissions.SALES__ORDER_AMEND));
        return o;
    }

    List<Map<String, Object>> lines(String orderId) {
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.name AS \"productName\", pr.code AS \"productCode\", (SELECT v.name FROM"
                + " \"ProductVariant\" v WHERE v.id = l.\"variantId\") AS \"variantName\","
                + " pr.\"trackInventory\", pr.\"sizingMode\", pr.\"sizingUnit\", t.name AS \"taxName\", t.rate AS"
                + " \"taxRate\", u.code AS \"uomCode\" FROM \"SalesOrderLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"Tax\" t ON t.id ="
                + " l.\"taxId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"orderId\" = ? ORDER BY"
                + " l.\"sortOrder\"", orderId);
        for (Map<String, Object> l : lines) {
            l.put("closedShort", l.get("closedShortAt") != null);
            if (l.get("lineDiscountPercent") != null) {
                l.put("netDiscountPercent", l.get("discountPercent"));
                l.put("discountPercent", l.get("lineDiscountPercent"));
            }
            l.put("qty", quantities(l));
        }
        return lines;
    }

    /** Commercial vs physical vs financial quantities of a line (sales.md §4). */
    static Map<String, Object> quantities(Map<String, Object> l) {
        BigDecimal ordered = dec(l.get("quantity"));
        BigDecimal cancelled = dec(l.get("qtyCancelled"));
        BigDecimal delivered = dec(l.get("qtyDelivered"));
        BigDecimal billable = dec(l.get("qtyBillable"));
        BigDecimal invoiced = dec(l.get("qtyInvoiced"));
        BigDecimal credited = dec(l.get("qtyCredited"));
        BigDecimal net = invoiced.subtract(credited);
        BigDecimal remaining = ordered.subtract(cancelled).subtract(delivered).max(BigDecimal.ZERO);
        return Obj.of("ordered", JsNumbers.js(ordered), "cancelled", JsNumbers.js(cancelled), "reserved",
                JsNumbers.js(dec(l.get("qtyReserved"))), "delivered", JsNumbers.js(delivered), "billable",
                JsNumbers.js(billable), "grossInvoiced", JsNumbers.js(invoiced), "credited", JsNumbers.js(credited),
                "netInvoiced", JsNumbers.js(net), "returned", JsNumbers.js(dec(l.get("qtyReturned"))), "remaining",
                JsNumbers.js(remaining), "outstandingBillable", JsNumbers.js(billable.subtract(net).max(BigDecimal.ZERO)));
    }

    private Map<String, Object> relations(Map<String, Object> o) {
        String id = str(o.get("id"));
        Map<String, Object> quote = o.get("quotationId") == null ? null
                : Obj.of("id", o.get("quotationId"), "number", o.get("quotationNumber"));
        List<Map<String, Object>> deliveries = db.rows("SELECT id, code AS number, status FROM \"FleetDeliveryOrder\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = 'sales_order' AND \"sourceId\" = ? ORDER BY \"createdAt\"",
                org(), id);
        List<Map<String, Object>> invoices = db.rows("SELECT id, \"documentNumber\" AS number, status::text AS status,"
                + " \"documentType\"::text AS type, \"totalAmount\" AS total FROM \"Document\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'sales_order' AND \"sourceId\" = ? ORDER BY \"createdAt\"", org(), id);
        long changes = db.count("SELECT count(*) FROM \"SalesOrderChangeRequest\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ?", List.of(id, org()));
        long revisions = db.count("SELECT count(*) FROM \"SalesOrderRevision\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ?", List.of(id, org()));
        Map<String, Object> agreement = o.get("agreementId") == null ? null : db.row("SELECT id, number, name, status"
                + " FROM \"SalesAgreement\" WHERE id = ? AND \"organizationId\" = ?", o.get("agreementId"), org());
        return Obj.of("quotation", quote, "agreement", agreement, "deliveries", deliveries, "invoices", invoices,
                "changeRequests", changes, "revisions", revisions);
    }

    /** Revision snapshots, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> revisions(String id) {
        require(id);
        return db.rows("SELECT id, revision, total, reason, diff, \"changeRequestId\", \"createdById\", \"createdAt\","
                + " snapshot FROM \"SalesOrderRevision\" WHERE \"orderId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " revision DESC", id, org());
    }

    // ------------------------------------------------------------------ create / edit (draft)

    @org.springframework.beans.factory.annotation.Autowired
    void setAgreements(SalesAgreementService agreements) {
        this.agreements = agreements;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAccess(SalesAccess access) {
        this.access = access;
    }

    /** New draft order (direct order flow). */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String partnerId = str(in.get("partnerId"));
        if (blank(partnerId)) {
            throw ApiException.badRequest("partnerId should not be empty");
        }
        requirePartner(partnerId);
        Map<String, Object> header = header(in, true);
        customerDefaults(header, partnerId);
        header.put("branchId", access.documentBranch(str(header.get("branchId"))));
        header.put("partnerId", partnerId);
        if (header.get("agreementId") == null) {
            String project = engine.projectAgreement(partnerId, str(header.get("siteId")), LocalDate.now());
            if (project != null) {
                header.put("agreementId", project);
            }
        }
        agreement(header, partnerId);
        header.put("number", nextNumber(str(header.get("branchId"))));
        header.putIfAbsent("currencyCode", orgCurrency());
        header.putIfAbsent("ownerId", user());
        header.put("createdById", user());
        Map<String, Object> row = db.insert("SalesOrder", header);
        String id = str(row.get("id"));
        writeLines(id, partnerId, str(header.get("agreementId")), LocalDate.now(), in.get("lines"),
                str(header.get("locationId")), PriceScope.of(header));
        audit.record(AuditInput.of("SalesOrder", id, "create", null, Obj.of("number", row.get("number"))));
        return get(id);
    }

    /**
     * Order from a quotation (called by {@link SalesQuotationService#convert} in its transaction): header and lines
     * are copied with their agreed prices (price source {@code quotation}).
     */
    @Transactional
    public Map<String, Object> createFromQuotation(Map<String, Object> q, List<Map<String, Object>> quoteLines,
            Map<String, Object> in) {
        Map<String, Object> header = header(in, true);
        header.put("partnerId", q.get("partnerId"));
        header.put("contactId", q.get("contactId"));
        header.put("quotationId", q.get("id"));
        header.put("dealId", q.get("dealId"));
        header.put("branchId", access.documentBranch(str(header.containsKey("branchId") ? header.get("branchId")
                : q.get("branchId"))));
        header.putIfAbsent("siteId", q.get("siteId"));
        header.putIfAbsent("deliveryAddress", q.get("deliveryAddress"));
        header.putIfAbsent("paymentTermId", q.get("paymentTermId"));
        header.putIfAbsent("pricelistId", q.get("pricelistId"));
        header.put("currencyCode", q.get("currencyCode"));
        header.putIfAbsent("exchangeRate", q.get("exchangeRate"));
        header.putIfAbsent("agreementId", q.get("agreementId"));
        header.putIfAbsent("ownerId", q.get("ownerId") == null ? user() : q.get("ownerId"));
        header.putIfAbsent("teamId", q.get("teamId"));
        header.putIfAbsent("notes", q.get("notes"));
        header.putIfAbsent("terms", q.get("terms"));
        header.putIfAbsent("commitmentDate", q.get("commitmentDate"));
        header.put("number", nextNumber(str(header.get("branchId"))));
        header.put("createdById", user());
        Map<String, Object> row = db.insert("SalesOrder", header);
        String id = str(row.get("id"));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : quoteLines) {
            if (Boolean.TRUE.equals(l.get("isOptional"))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("productId", "variantId", "description", "quantity", "uomId", "unitPrice",
                    "discountPercent", "taxId", "taxInclusive", "listPrice", "pieces", "length", "width", "height")) {
                m.put(k, l.get(k));
            }
            m.put("priceSource", "quotation");
            m.put("quotationLineId", l.get("id"));
            lines.add(m);
        }
        writeLines(id, str(q.get("partnerId")), null, LocalDate.now(), lines, str(header.get("locationId")),
                PriceScope.of(header));
        audit.record(AuditInput.of("SalesOrder", id, "create", null, Obj.of("number", row.get("number"), "quotationId",
                q.get("id"))));
        return get(id);
    }

    /** Edit header and replace lines while draft. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be edited; request a change for a confirmed order");
        }
        Map<String, Object> d = header(in, false);
        if (d.containsKey("branchId")) {
            d.put("branchId", access.documentBranch(str(d.get("branchId"))));
        }
        if (in.containsKey("partnerId")) {
            String p = str(in.get("partnerId"));
            requirePartner(p);
            d.put("partnerId", p);
        }
        if (d.containsKey("agreementId") || d.containsKey("partnerId") && o.get("agreementId") != null) {
            if (!d.containsKey("agreementId")) {
                d.put("agreementId", o.get("agreementId"));
            }
            agreement(d, str(d.getOrDefault("partnerId", o.get("partnerId"))));
        }
        d.put("version", ((Number) o.get("version")).intValue() + 1);
        db.patch("SalesOrder", id, d, true);
        if (in.containsKey("lines")) {
            writeLines(id, str(d.getOrDefault("partnerId", o.get("partnerId"))), str(d.getOrDefault("agreementId",
                    o.get("agreementId"))), LocalDate.now(), in.get("lines"), str(d.getOrDefault("locationId",
                            o.get("locationId"))), PriceScope.of(o, d));
        }
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("fields", d.keySet(), "lines",
                in.containsKey("lines"))));
        return get(id);
    }

    /** Soft-delete a draft. */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be deleted; cancel it instead");
        }
        db.update("UPDATE \"SalesOrder\" SET \"deletedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        audit.record(AuditInput.of("SalesOrder", id, "delete"));
        return Obj.of("ok", true);
    }

    /** Copy of an order as a new draft (any status). */
    @Transactional
    public Map<String, Object> duplicate(String id) {
        Map<String, Object> o = require(id);
        Map<String, Object> in = new LinkedHashMap<>();
        for (String k : List.of("partnerId", "contactId", "branchId", "locationId", "siteId", "deliveryAddress",
                "deliveryMethod", "paymentTermId", "currencyCode", "incoterm", "notes", "terms", "agreementId",
                "orderDiscountPercent", "orderDiscountAmount", "paymentMode")) {
            in.put(k, o.get(k));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : lines(id)) {
            lines.add(Obj.of("productId", l.get("productId"), "description", l.get("description"), "quantity",
                    l.get("quantity"), "uomId", l.get("uomId"), "unitPrice", l.get("unitPrice"), "discountPercent",
                    l.get("discountPercent"), "taxId", l.get("taxId"), "taxInclusive", l.get("taxInclusive")));
        }
        in.put("lines", lines);
        return create(in);
    }

    private Map<String, Object> header(Map<String, Object> in, boolean create) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("contactId", "branchId", "locationId", "siteId", "deliveryAddress", "paymentTermId",
                "customerRef", "incoterm", "notes", "terms", "teamId", "agreementId")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (in.containsKey("ownerId")) {
            d.put("ownerId", blank(str(in.get("ownerId"))) ? null : requireUser(str(in.get("ownerId"))));
        }
        if (in.containsKey("currencyCode") && !blank(str(in.get("currencyCode")))) {
            d.put("currencyCode", str(in.get("currencyCode")));
        }
        if (in.containsKey("paymentMode")) {
            String m = str(in.get("paymentMode"));
            if (!Set.of("credit", "cash").contains(m)) {
                throw ApiException.badRequest("paymentMode must be one of: cash, credit");
            }
            d.put("paymentMode", m);
        }
        if (in.containsKey("priority")) {
            d.put("priority", in.get("priority") instanceof Number n ? n.intValue() : 0);
        }
        for (String k : List.of("requestedDate", "commitmentDate")) {
            if (in.containsKey(k)) {
                d.put(k, SalesDb.date(str(in.get(k))));
            }
        }
        if (in.containsKey("deliveryMethod")) {
            String m = str(in.get("deliveryMethod"));
            if (!Set.of("delivery", "collect").contains(m)) {
                throw ApiException.badRequest("deliveryMethod must be one of: collect, delivery");
            }
            d.put("deliveryMethod", m);
        }
        for (String k : List.of("orderDiscountPercent", "orderDiscountAmount")) {
            if (in.containsKey(k)) {
                d.put(k, in.get(k) == null || dec(in.get(k)).signum() == 0 ? null : dec(in.get(k)));
            }
        }
        if (!create && (d.containsKey("orderDiscountPercent") || d.containsKey("orderDiscountAmount")) && !in.containsKey("lines")) {
            throw ApiException.badRequest("Send the lines with an order discount change");
        }
        if (in.containsKey("exchangeRate")) {
            BigDecimal r = dec(in.get("exchangeRate"));
            if (r.signum() <= 0) {
                throw ApiException.badRequest("exchangeRate must be greater than 0");
            }
            d.put("exchangeRate", r);
        }
        for (String k : List.of("branchId")) {
            if (d.get(k) != null && !db.exists("Branch", str(d.get(k)))) {
                throw ApiException.notFound("Branch not found");
            }
        }
        if (d.get("locationId") != null && !db.exists("InventoryLocation", str(d.get("locationId")))) {
            throw ApiException.notFound("Location not found");
        }
        if (d.get("paymentTermId") != null && !db.exists("PaymentTerm", str(d.get("paymentTermId")))) {
            throw ApiException.notFound("Payment term not found");
        }
        if (create && d.get("terms") == null) {
            d.put("terms", settings.settings().get("defaultQuoteTerms"));
        }
        return d;
    }

    /** SG-5: the customer's home branch, currency and payment term fill what the order leaves empty. */
    private void customerDefaults(Map<String, Object> header, String partnerId) {
        Map<String, Object> p = db.row("SELECT \"homeBranchId\", \"defaultCurrencyCode\", \"paymentTermId\" FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ?", partnerId, org());
        if (p == null) {
            return;
        }
        if (header.get("branchId") == null && p.get("homeBranchId") != null) {
            header.put("branchId", p.get("homeBranchId"));
        }
        if (header.get("currencyCode") == null && p.get("defaultCurrencyCode") != null) {
            header.put("currencyCode", p.get("defaultCurrencyCode"));
        }
        if (header.get("paymentTermId") == null && p.get("paymentTermId") != null) {
            header.put("paymentTermId", p.get("paymentTermId"));
        }
    }

    /** Validate the header's agreement for {@code partnerId}; its payment term applies when none is given. */
    private void agreement(Map<String, Object> header, String partnerId) {
        String agreementId = str(header.get("agreementId"));
        if (agreementId == null) {
            return;
        }
        Map<String, Object> a = agreements.requireReleasable(agreementId, partnerId);
        if (header.get("paymentTermId") == null && a.get("paymentTermId") != null) {
            header.put("paymentTermId", a.get("paymentTermId"));
        }
    }

    /** Replace the lines of a draft order (price resolved for product lines without an explicit price). */
    /** The order header fields that select price rules. */
    record PriceScope(String siteId, String branchId, String currencyCode) {

        static PriceScope of(Map<String, Object> order) {
            return new PriceScope(str(order.get("siteId")), str(order.get("branchId")), str(order.get("currencyCode")));
        }

        /** {@code changes} win over the stored order. */
        static PriceScope of(Map<String, Object> order, Map<String, Object> changes) {
            Map<String, Object> merged = new LinkedHashMap<>(order);
            merged.putAll(changes);
            return of(merged);
        }
    }

    @SuppressWarnings("unchecked")
    void writeLines(String orderId, String partnerId, String agreementId, LocalDate on, Object raw,
            String defaultLocation, PriceScope scope) {
        db.update("DELETE FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?", orderId, org());
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            rows.add(line(partnerId, agreementId, on, l, defaultLocation, scope));
        }
        applyOrderDiscount(orderId, rows);
        insertLines(orderId, rows, 0);
        retotal(orderId);
    }

    /** SG-6: spread the header discount over the lines. The line keeps its own discount for later edits. */
    private void applyOrderDiscount(String orderId, List<Map<String, Object>> rows) {
        Map<String, Object> h = db.row("SELECT \"orderDiscountPercent\" AS p, \"orderDiscountAmount\" AS a FROM \"SalesOrder\" WHERE id = ?", orderId);
        BigDecimal net = BigDecimal.ZERO;
        for (Map<String, Object> m : rows) {
            net = net.add(SalesDiscounts.net(dec(m.get("quantity")), dec(m.get("unitPrice")), dec(m.get("discountPercent"))));
        }
        BigDecimal percent = SalesDiscounts.orderPercent(h.get("p") == null ? null : dec(h.get("p")), h.get("a") == null ? null
                : dec(h.get("a")), net);
        if (percent.signum() == 0) {
            return;
        }
        for (Map<String, Object> m : rows) {
            m.put("lineDiscountPercent", m.get("discountPercent"));
            m.put("discountPercent", SalesDiscounts.combine(dec(m.get("discountPercent")), percent));
        }
    }

    /** Validated, priced line values (not yet taxed). */
    Map<String, Object> line(String partnerId, String agreementId, LocalDate on, Map<String, Object> l,
            String defaultLocation, PriceScope scope) {
        String productId = blank(str(l.get("productId"))) ? null : str(l.get("productId"));
        // ADR-S066: a line sold by size gets its quantity from pieces × length [× width [× height]]
        ProductSizingService.Sizing size = sizing.fromLine(productId, l);
        BigDecimal qty = size != null ? size.quantity() : dec(l.get("quantity"));
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("quantity must be greater than 0");
        }
        ProductVariantService.VariantRef variant = variants.resolve(productId, str(l.get("variantId")));
        String variantId = variant == null ? null : variant.id();
        QuotationPricingPort.Price price = productId == null ? null : pricing.price(partnerId, productId, variantId, qty,
                blank(str(l.get("uomId"))) ? null : str(l.get("uomId")), on, agreementId, scope.siteId(), scope.branchId(),
                scope.currencyCode());
        BigDecimal unit;
        BigDecimal disc;
        String source;
        if (l.get("unitPrice") != null || l.get("discountPercent") != null || l.get("discountAmount") != null) {
            unit = l.get("unitPrice") == null ? price == null ? BigDecimal.ZERO : price.unitPrice() : dec(l.get("unitPrice"));
            disc = l.get("discountPercent") == null ? price == null ? BigDecimal.ZERO : price.discountPercent()
                    : dec(l.get("discountPercent"));
            if (l.get("discountAmount") != null) {
                disc = SalesDiscounts.lineAmountToPercent(dec(l.get("discountAmount")), qty, unit);
            }
            boolean same = price != null && unit.compareTo(price.unitPrice()) == 0
                    && disc.compareTo(price.discountPercent()) == 0;
            source = !blank(str(l.get("priceSource"))) ? str(l.get("priceSource")) : same ? price.source() : "manual";
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
        String location = blank(str(l.get("sourceLocationId"))) ? defaultLocation : str(l.get("sourceLocationId"));
        if (location != null && !db.exists("InventoryLocation", location)) {
            throw ApiException.notFound("Location not found");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("variantId", variantId);
        m.put("description", description);
        m.put("quantity", qty);
        m.put("uomId", blank(str(l.get("uomId"))) ? null : str(l.get("uomId")));
        m.put("listPrice", l.get("listPrice") != null ? dec(l.get("listPrice")) : price == null ? unit : price.listPrice());
        m.put("unitPrice", unit);
        m.put("discountPercent", disc);
        m.put("priceSource", source);
        m.put("priceRuleId", price == null || !"rule".equals(source) ? null : price.ruleId());
        m.put("taxId", l.containsKey("taxId") ? (blank(str(l.get("taxId"))) ? null : str(l.get("taxId")))
                : price == null ? null : price.taxId());
        m.put("taxInclusive", l.get("taxInclusive") instanceof Boolean b ? b : price != null && price.taxInclusive());
        m.put("promisedDate", SalesDb.date(str(l.get("promisedDate"))));
        m.put("sourceLocationId", location);
        m.put("tolerancePct", l.get("tolerancePct") == null ? BigDecimal.ZERO : dec(l.get("tolerancePct")));
        m.put("quotationLineId", str(l.get("quotationLineId")));
        m.putAll(ProductSizingService.Sizing.columns(size));
        return m;
    }

    /** Insert priced lines (taxed by the invoicing tax engine) starting at {@code sortFrom}; returns new ids. */
    List<String> insertLines(String orderId, List<Map<String, Object>> rows, int sortFrom) {
        List<String> ids = new ArrayList<>();
        if (rows.isEmpty()) {
            return ids;
        }
        List<LineInput> inputs = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            inputs.add(new LineInput(str(m.get("productId")), null, null, null, null, str(m.get("description")),
                    dec(m.get("quantity")), dec(m.get("unitPrice")), dec(m.get("discountPercent")), null, null, null,
                    "manual", str(m.get("taxId")), null, Boolean.TRUE.equals(m.get("taxInclusive")),
                    str(m.get("uomId"))));
        }
        Totals totals = builder.prepareLines(inputs);
        int i = 0;
        for (PreparedLine pl : totals.prepared()) {
            Map<String, Object> m = rows.get(i);
            BigDecimal cost = pl.productId() == null ? null : costOf(pl.productId(), str(m.get("variantId")));
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("orderId", orderId);
            ins.put("sortOrder", sortFrom + i);
            ins.put("productId", pl.productId());
            ins.put("variantId", m.get("variantId"));
            ins.put("description", pl.description());
            ins.put("quantity", pl.quantity());
            ins.put("uomId", pl.uomId());
            ins.put("uomFactor", pl.uomFactor() == null ? BigDecimal.ONE : pl.uomFactor());
            ins.put("listPrice", m.get("listPrice"));
            ins.put("unitPrice", pl.unitPrice());
            ins.put("discountPercent", pl.discountPercent());
            ins.put("lineDiscountPercent", m.get("lineDiscountPercent"));
            ins.put("priceSource", m.get("priceSource"));
            ins.put("priceRuleId", m.get("priceRuleId"));
            ins.put("taxId", pl.taxId());
            ins.put("taxInclusive", pl.taxInclusive());
            ins.put("lineSubtotal", pl.subtotal());
            ins.put("lineTax", pl.taxAmount());
            ins.put("lineTotal", pl.total());
            ins.put("promisedDate", m.get("promisedDate"));
            ins.put("sourceLocationId", m.get("sourceLocationId"));
            ins.put("costSnapshot", cost);
            ins.put("tolerancePct", m.get("tolerancePct"));
            ins.put("quotationLineId", m.get("quotationLineId"));
            ins.putAll(ProductSizingService.Sizing.columns(ProductSizingService.Sizing.stored(m)));
            ids.add(str(db.insert("SalesOrderLine", ins).get("id")));
            i++;
        }
        return ids;
    }

    private BigDecimal costOf(String productId, String variantId) {
        if (variantId != null) {
            ProductVariantService.VariantRef v = variants.find(variantId);
            if (v != null && v.costPrice() != null) {
                return v.costPrice();
            }
        }
        Map<String, Object> r = db.row("SELECT \"costPrice\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?",
                productId, org());
        return r == null || r.get("costPrice") == null ? null : dec(r.get("costPrice"));
    }

    /** Recompute header totals and the highest discount vs list price from the lines. */
    void retotal(String orderId) {
        Map<String, Object> t = db.row("SELECT COALESCE(sum(\"lineSubtotal\"), 0) AS sub, COALESCE(sum(\"lineTax\"), 0)"
                + " AS tax, COALESCE(sum(\"lineTotal\"), 0) AS tot, COALESCE(sum(quantity * \"unitPrice\" * \"discountPercent\""
                + " / 100), 0) AS disc, COALESCE(max(CASE WHEN \"listPrice\" > 0 AND \"unitPrice\" * (1 - \"discountPercent\""
                + " / 100) < \"listPrice\" THEN (1 - \"unitPrice\" * (1 - \"discountPercent\" / 100) / \"listPrice\") * 100"
                + " END), 0) AS maxdisc FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?", orderId,
                org());
        db.update("UPDATE \"SalesOrder\" SET subtotal = ?, \"taxAmount\" = ?, total = ?, \"discountTotal\" = ?,"
                + " \"maxDiscountPercent\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                dec(t.get("sub")), dec(t.get("tax")), dec(t.get("tot")), dec(t.get("disc")).setScale(6,
                        RoundingMode.HALF_UP), dec(t.get("maxdisc")).min(HUNDRED).setScale(4, RoundingMode.HALF_UP),
                orderId, org());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Approval entity of sales orders (kernel approval engine). */
    public static final String APPROVAL_ENTITY = "sales_order";

    /**
     * Confirm a draft order. Commercial policy (extra discount beyond the authorised price, order amount, margin floor)
     * sends it to approval first ({@code pending_approval}); otherwise it is confirmed at once. On confirmation the
     * exchange rate is locked and a customer on credit hold puts the order on hold (credit exposure checks: S3).
     */
    @Transactional
    public Map<String, Object> confirm(String id) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be confirmed");
        }
        requirePartner(str(o.get("partnerId")));
        List<Map<String, Object>> lines = db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? ORDER BY"
                + " \"sortOrder\"", id);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Add at least one line before confirming");
        }
        Map<String, Object> policy = settings.settings();
        List<Map<String, Object>> shortages = "none".equals(policy.get("stockCheckPolicy")) ? List.of()
                : atp.shortages(id);
        if ("block".equals(policy.get("stockCheckPolicy")) && !shortages.isEmpty()) {
            throw ApiException.badRequest("Not enough stock to confirm this order: short by "
                    + shortages.get(0).get("shortage") + " on " + shortDescription(lines, shortages.get(0)));
        }
        List<String> reasons = approvalReasons(o, lines);
        if (!reasons.isEmpty()) {
            Map<String, Object> snapshot = Obj.of("number", o.get("number"), "partnerId", o.get("partnerId"), "total",
                    o.get("total"), "amount", o.get("total"), "maxDiscountPercent", o.get("maxDiscountPercent"), "reasons",
                    reasons);
            Map<String, Object> req = approvals.requestApproval(APPROVAL_ENTITY, id, snapshot, null);
            db.patch("SalesOrder", id, Obj.of("status", "pending_approval", "approvalReasons", reasons,
                    "approvalRequestId", req == null ? null : req.get("id"), "version",
                    ((Number) o.get("version")).intValue() + 1), true);
            audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("status", "pending_approval", "reasons",
                    reasons)));
            return get(id);
        }
        return finishConfirm(id);
    }

    private static String shortDescription(List<Map<String, Object>> lines, Map<String, Object> shortage) {
        return lines.stream().filter(l -> l.get("id").equals(shortage.get("lineId"))).map(l -> str(l.get("description")))
                .findFirst().orElse("a line");
    }

    /** Why this order needs commercial approval (empty = within policy). */
    List<String> approvalReasons(Map<String, Object> o, List<Map<String, Object>> lines) {
        Map<String, Object> s = settings.settings();
        List<String> reasons = new ArrayList<>(engine.breaches(str(o.get("partnerId")), str(o.get("branchId")),
                LocalDate.now(), lines.stream().filter(l -> !Set.of("quotation", "agreement").contains(str(l.get(
                        "priceSource")))).toList(), dec(s.get("orderMaxDiscountPercent")), Boolean.TRUE.equals(s.get(
                                "marginCheck"))));
        reasons.addAll(agreements.releaseReasons(o, lines));
        if (Boolean.TRUE.equals(s.get("orderApprovalAlways"))) {
            reasons.add("Every sales order needs approval");
        }
        BigDecimal amount = dec(s.get("orderApprovalAmount"));
        if (amount.signum() > 0 && dec(o.get("total")).compareTo(amount) > 0) {
            reasons.add("Total exceeds the approval amount of " + amount.stripTrailingZeros().toPlainString());
        }
        return reasons;
    }

    /** Approve a pending order (commercial approver, not the requester) and confirm it. */
    @Transactional
    public Map<String, Object> approve(String id, String comment) {
        Map<String, Object> o = lock(id);
        if (!"pending_approval".equals(o.get("status"))) {
            throw ApiException.badRequest("Order is not awaiting approval");
        }
        if (user() != null && user().equals(o.get("createdById")) && !TenantContext.has(Permissions.SALES__CONFIGURE)) {
            throw ApiException.forbidden("You cannot approve your own order");
        }
        audit.record(AuditInput.of("SalesOrder", id, "approve", null, Obj.of("comment", comment)));
        return finishConfirm(id);
    }

    /** Reject a pending order: back to draft with the reason. */
    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (!"pending_approval".equals(o.get("status"))) {
            throw ApiException.badRequest("Order is not awaiting approval");
        }
        db.patch("SalesOrder", id, Obj.of("status", "draft", "approvalReasons", List.of("Rejected: " + (blank(reason)
                ? "no reason given" : reason)), "approvalRequestId", null, "version", ((Number) o.get("version"))
                .intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "reject", null, Obj.of("reason", reason)));
        return get(id);
    }

    /** Apply a kernel approval-engine decision (idempotent: ignored unless still pending). */
    @Transactional
    public void applyDecision(String id, boolean approved, String comment) {
        Map<String, Object> o = db.row("SELECT status FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        if (o == null || !"pending_approval".equals(o.get("status"))) {
            return;
        }
        if (approved) {
            finishConfirm(id);
        } else {
            db.patch("SalesOrder", id, Obj.of("status", "draft", "approvalReasons", List.of("Rejected: " + (blank(comment)
                    ? "rejected in approval workflow" : comment)), "approvalRequestId", null), true);
        }
        audit.record(AuditInput.of("SalesOrder", id, approved ? "approve" : "reject", null, Obj.of("via",
                "approval_engine")));
    }

    private Map<String, Object> finishConfirm(String id) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? FOR UPDATE", id);
        Map<String, Object> policy0 = settings.settings();
        boolean cash = "cash".equals(o.get("paymentMode")) && Boolean.TRUE.equals(policy0.get("cashBypassesCredit"));
        CreditService.Decision c = cash ? new CreditService.Decision(null, "pass", List.of(), Map.of())
                : credit.check("order_confirm", str(o.get("partnerId")), "sales_order", id, str(o.get("number")),
                        dec(o.get("total")), str(o.get("branchId")));
        String reason = String.join("; ", c.reasons());
        // SG-8: a hold on the customer always stops the order; going over the limit follows the organization's policy.
        boolean overLimit = !c.passed() && !"blocked".equals(c.result());
        boolean covered = overLimit && creditRequests.approvedFor(id, dec(o.get("total")));
        if (overLimit && !covered) {
            String over = str(policy0.get("overLimitPolicy"));
            if ("block".equals(over)) {
                throw ApiException.badRequest("This order exceeds the customer's credit: " + reason + ". Ask for a credit approval"
                        + " or take payment first.");
            }
            if ("approve".equals(over)) {
                Map<String, Object> request = creditRequests.open(o, "Over the credit limit: " + reason);
                Map<String, Object> waiting = get(id);
                waiting.put("creditRequest", request);
                return waiting;
            }
        }
        boolean hold = !c.passed() && !covered;
        Map<String, Object> d = Obj.of("status", "confirmed", "confirmedAt", Instant.now(), "confirmedById", user(),
                "approvalReasons", List.of(), "version", ((Number) o.get("version")).intValue() + 1);
        if (hold) {
            d.put("onHold", true);
            d.put("holdReason", reason);
            d.put("holdKind", "blocked".equals(c.result()) ? "customer" : "credit");
        }
        db.patch("SalesOrder", id, d, true);
        audit.record(AuditInput.of("SalesOrder", id, "approve", null, Obj.of("status", "confirmed", "onHold", hold,
                "creditDecision", c.id())));
        Map<String, Object> policy = settings.settings();
        if ("confirm".equals(policy.get("reservationTrigger"))) {
            atp.reserveOrder(id);
        }
        snapshots.record("sales_order", id);
        publish(DomainEvents.SALES_ORDER_CONFIRMED, id, Map.of());
        if (hold) {
            publish(DomainEvents.SALES_ORDER_HELD, id, Map.of("reason", reason));
            if (Boolean.TRUE.equals(policy.get("notifyOnHold"))) {
                alerts.order(o, "sales_order_hold", "Order " + o.get("number") + " is on hold", reason);
            }
        }
        List<Map<String, Object>> shortages = "none".equals(policy.get("stockCheckPolicy")) ? List.of()
                : atp.shortages(id);
        if (!shortages.isEmpty() && Boolean.TRUE.equals(policy.get("notifyOnShortage"))) {
            alerts.order(o, "sales_order_shortage", "Order " + o.get("number") + " is short of stock", shortages.size()
                    + " line(s) cannot be covered from stock today.");
        }
        Map<String, Object> out = get(id);
        out.put("stockWarnings", shortages);
        return out;
    }

    /** SG-8: confirm after a credit request was approved, whatever the order's approval state was. */
    @Transactional
    public Map<String, Object> confirmAfterCredit(String id) {
        return finishConfirm(id);
    }

    /** Put an open order on hold (no fulfilment while held). */
    @Transactional
    public Map<String, Object> hold(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (!OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can be put on hold");
        }
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        db.patch("SalesOrder", id, Obj.of("onHold", true, "holdReason", reason, "holdKind", "manual", "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("onHold", true, "reason", reason)));
        publish(DomainEvents.SALES_ORDER_HELD, id, Map.of("reason", reason));
        if (Boolean.TRUE.equals(settings.settings().get("notifyOnHold"))) {
            alerts.order(o, "sales_order_hold", "Order " + o.get("number") + " is on hold", reason);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> release(String id) {
        Map<String, Object> o = lock(id);
        if (!Boolean.TRUE.equals(o.get("onHold"))) {
            throw ApiException.badRequest("Order is not on hold");
        }
        Map<String, Object> partner = db.row("SELECT \"creditHold\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?", o.get("partnerId"), org());
        if (partner != null && Boolean.TRUE.equals(partner.get("creditHold"))) {
            throw ApiException.badRequest("Customer is on credit hold; lift the hold on the customer first");
        }
        if (!"manual".equals(o.get("holdKind")) && o.get("holdKind") != null) {
            credit.override(str(o.get("partnerId")), "sales_order", id, str(o.get("number")), null);
        }
        db.patch("SalesOrder", id, Obj.of("onHold", false, "holdReason", null, "holdKind", null, "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("onHold", false)));
        publish(DomainEvents.SALES_ORDER_RELEASED, id, Map.of());
        return get(id);
    }

    /** Cancel an order that has nothing delivered or invoiced (close short what remains otherwise). */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> o = lock(id);
        if (Set.of("cancelled", "done").contains(o.get("status"))) {
            throw ApiException.badRequest("Order cannot be cancelled");
        }
        Map<String, Object> moved = db.row("SELECT COALESCE(sum(\"qtyDelivered\"), 0) AS d, COALESCE(sum(\"qtyInvoiced\"),"
                + " 0) AS i FROM \"SalesOrderLine\" WHERE \"orderId\" = ?", id);
        if (dec(moved.get("d")).signum() > 0 || dec(moved.get("i")).signum() > 0) {
            throw ApiException.badRequest("Order has deliveries or invoices; close the remaining quantities short instead");
        }
        if (db.count("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'sales_order' AND \"sourceId\" = ? AND status NOT IN ('cancelled')", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Cancel the delivery orders of this order first");
        }
        if (OPEN.contains(o.get("status")) && blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        if (db.count("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ? AND \"sourceType\" = 'sales_order'"
                + " AND \"sourceId\" = ? AND \"documentType\"::text <> 'proforma_invoice' AND status::text NOT IN"
                + " ('cancelled', 'rejected')", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Cancel the invoices of this order first");
        }
        if (db.count("SELECT count(*) FROM \"CustomerDeposit\" WHERE \"organizationId\" = ? AND \"salesOrderId\" = ? AND"
                + " status = 'open' AND amount - \"appliedAmount\" - \"refundedAmount\" > 0", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Refund or apply the deposits of this order first");
        }
        if (db.count("SELECT count(*) FROM \"SalesOrderChangeRequest\" WHERE \"organizationId\" = ? AND \"orderId\" = ? AND"
                + " status = 'pending_approval'", List.of(org(), id)) > 0) {
            throw ApiException.badRequest("Decide the pending change requests of this order first");
        }
        db.patch("SalesOrder", id, Obj.of("status", "cancelled", "cancelledAt", Instant.now(), "cancelledById", user(),
                "cancelReason", blank(reason) ? null : reason, "onHold", false, "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "cancel", null, Obj.of("reason", reason)));
        atp.releaseOrder(id);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", blank(reason) ? null : reason);
        publish(DomainEvents.SALES_ORDER_CANCELLED, id, extra);
        return get(id);
    }

    /**
     * Close the undelivered remainder of a line short: {@code qtyCancelled = ordered − max(delivered, billable,
     * net invoiced)}. History (delivered / invoiced) is untouched.
     */
    @Transactional
    public Map<String, Object> closeShort(String id, String lineId, String reason) {
        Map<String, Object> o = lock(id);
        if (!OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Only confirmed orders can be closed short");
        }
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        Map<String, Object> l = db.row("SELECT * FROM \"SalesOrderLine\" WHERE id = ? AND \"orderId\" = ? AND"
                + " \"organizationId\" = ? FOR UPDATE", lineId, id, org());
        if (l == null) {
            throw ApiException.notFound("Order line not found");
        }
        if (l.get("closedShortAt") != null) {
            throw ApiException.badRequest("Line is already closed short");
        }
        BigDecimal floor = committedFloor(l);
        BigDecimal cancel = dec(l.get("quantity")).subtract(floor);
        if (cancel.signum() <= 0) {
            throw ApiException.badRequest("Nothing left to close on this line");
        }
        db.update("UPDATE \"SalesOrderLine\" SET \"qtyCancelled\" = ?, \"closedShortAt\" = " + NOW + ","
                + " \"closeShortReason\" = ? WHERE id = ?", cancel, reason, lineId);
        db.patch("SalesOrder", id, Obj.of("version", ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("lineId", lineId, "closedShort", cancel,
                "reason", reason)));
        publish(DomainEvents.SALES_ORDER_LINE_CLOSED_SHORT, id, Map.of("lineId", lineId, "quantity",
                JsNumbers.js(cancel)));
        refreshStatus(id);
        atp.reserveOrder(id);
        return get(id);
    }

    /** Quantity of a line that can no longer be reduced: max(delivered, billable, net invoiced). */
    static BigDecimal committedFloor(Map<String, Object> l) {
        return dec(l.get("qtyDelivered")).max(dec(l.get("qtyBillable")))
                .max(dec(l.get("qtyInvoiced")).subtract(dec(l.get("qtyCredited"))));
    }

    /**
     * Derive delivery / invoice status and move {@code confirmed → in_fulfilment → done} from the line quantities
     * (called whenever a projection changes).
     */
    @Transactional
    public void refreshStatus(String id) {
        Map<String, Object> o = db.row("SELECT status FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        if (o == null || !OPEN.contains(o.get("status"))) {
            return;
        }
        Map<String, Object> s = db.row("SELECT COALESCE(sum(quantity - \"qtyCancelled\"), 0) AS open,"
                + " COALESCE(sum(\"qtyDelivered\"), 0) AS delivered, COALESCE(sum(\"qtyBillable\"), 0) AS billable,"
                + " COALESCE(sum(\"qtyInvoiced\" - \"qtyCredited\"), 0) AS invoiced, bool_and(\"qtyDelivered\" >="
                + " (quantity - \"qtyCancelled\") * (1 - \"tolerancePct\" / 100)) AS alldelivered FROM \"SalesOrderLine\""
                + " WHERE \"orderId\" = ?", id);
        BigDecimal delivered = dec(s.get("delivered"));
        BigDecimal billable = dec(s.get("billable"));
        BigDecimal invoiced = dec(s.get("invoiced"));
        boolean allDelivered = Boolean.TRUE.equals(s.get("alldelivered"));
        String delivery = delivered.signum() == 0 && !allDelivered ? "none" : allDelivered ? "full" : "partial";
        String invoice = invoiced.signum() == 0 ? billable.signum() > 0 ? "to_invoice" : "nothing"
                : invoiced.compareTo(billable) >= 0 && allDelivered ? "invoiced" : "partial";
        String status = allDelivered && "invoiced".equals(invoice) ? "done"
                : delivered.signum() > 0 || invoiced.signum() > 0 ? "in_fulfilment" : str(o.get("status"));
        Map<String, Object> d = Obj.of("deliveryStatus", delivery, "invoiceStatus", invoice, "status", status);
        if ("done".equals(status)) {
            d.put("doneAt", Instant.now());
            d.put("onHold", false);
        }
        db.patch("SalesOrder", id, d, true);
        if ("done".equals(status)) {
            publish(DomainEvents.SALES_ORDER_DONE, id, Map.of());
            atp.releaseOrder(id);
        }
    }

    // ------------------------------------------------------------------ helpers

    void publish(String event, String id, Map<String, Object> extra) {
        Map<String, Object> o = db.row("SELECT number, \"partnerId\", total FROM \"SalesOrder\" WHERE id = ?", id);
        Map<String, Object> payload = Obj.of("v", 1, "organizationId", org(), "orderId", id, "number", o.get("number"),
                "partnerId", o.get("partnerId"), "total", o.get("total"));
        payload.putAll(extra);
        events.publish(event, payload);
    }

    /** SG-9: numbers run per branch when the setting says so; the branch code in the prefix keeps series apart. */
    String nextNumber(String branchId) {
        int year = LocalDate.now().getYear();
        String code = branchCode(branchId);
        return code == null ? sequence.next("sales_order:" + year, "SO-" + year + "-", 5)
                : sequence.next("sales_order:" + code + ":" + year, "SO-" + code + "-" + year + "-", 5);
    }

    private String branchCode(String branchId) {
        if (branchId == null || !"branch".equals(settings.settings().get("numberingScope"))) {
            return null;
        }
        Map<String, Object> b = db.row("SELECT code FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ?", branchId, org());
        return b == null ? null : str(b.get("code"));
    }

    private void visibility(StringBuilder where, List<Object> params) {
        if (user() == null) {
            return; // scheduled work has no user and is not limited to one
        }
        access.filterDocument(where, params, "o");
        if (TenantContext.has(Permissions.SALES__ORDER_READ_ALL)) {
            return;
        }
        where.append(" AND (o.\"ownerId\" = ? OR o.\"createdById\" = ? OR o.\"ownerId\" IS NULL)");
        params.add(user());
        params.add(user());
    }

    public Map<String, Object> require(String id) {
        StringBuilder where = new StringBuilder(" WHERE o.id = ? AND o.\"organizationId\" = ? AND o.\"deletedAt\" IS"
                + " NULL");
        List<Object> params = new ArrayList<>(List.of(id, org()));
        visibility(where, params);
        Map<String, Object> o = db.row(SELECT + where, params.toArray());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        return o;
    }

    Map<String, Object> lock(String id) {
        require(id);
        return db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE", id, org());
    }

    private void requirePartner(String partnerId) {
        db.requireActivePartner(partnerId);
    }

    private String requireUser(String id) {
        if (db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                List.of(id, org())) == 0) {
            throw ApiException.notFound("User not found");
        }
        return id;
    }

    String orgCurrency() {
        Map<String, Object> o = db.row("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        return o == null || o.get("currencyCode") == null ? "USD" : str(o.get("currencyCode"));
    }
}
