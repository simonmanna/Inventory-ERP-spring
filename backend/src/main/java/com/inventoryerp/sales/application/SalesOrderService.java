package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
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
    private final DocumentBuilderService builder;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public SalesOrderService(SalesDb db, SalesSettingsService settings, SalesLinePricing pricing,
            DocumentBuilderService builder, SequenceService sequence, AuditService audit, EventBus events) {
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
        o.put("relations", relations(o));
        o.put("canAmend", OPEN.contains(o.get("status")) && TenantContext.has(Permissions.SALES__ORDER_AMEND));
        return o;
    }

    List<Map<String, Object>> lines(String orderId) {
        List<Map<String, Object>> lines = db.rows("SELECT l.*, pr.name AS \"productName\", pr.code AS \"productCode\","
                + " pr.\"trackInventory\", t.name AS \"taxName\", t.rate AS \"taxRate\", u.code AS \"uomCode\" FROM"
                + " \"SalesOrderLine\" l LEFT JOIN \"Product\" pr ON pr.id = l.\"productId\" LEFT JOIN \"Tax\" t ON t.id ="
                + " l.\"taxId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"orderId\" = ? ORDER BY"
                + " l.\"sortOrder\"", orderId);
        for (Map<String, Object> l : lines) {
            l.put("closedShort", l.get("closedShortAt") != null);
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
        return Obj.of("quotation", quote, "deliveries", deliveries, "invoices", invoices, "changeRequests", changes,
                "revisions", revisions);
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

    /** New draft order (direct order flow). */
    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String partnerId = str(in.get("partnerId"));
        if (blank(partnerId)) {
            throw ApiException.badRequest("partnerId should not be empty");
        }
        requirePartner(partnerId);
        Map<String, Object> header = header(in, true);
        header.put("partnerId", partnerId);
        header.put("number", nextNumber());
        header.putIfAbsent("currencyCode", orgCurrency());
        header.putIfAbsent("ownerId", user());
        header.put("createdById", user());
        Map<String, Object> row = db.insert("SalesOrder", header);
        String id = str(row.get("id"));
        writeLines(id, partnerId, LocalDate.now(), in.get("lines"), str(header.get("locationId")));
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
        header.putIfAbsent("branchId", q.get("branchId"));
        header.putIfAbsent("siteId", q.get("siteId"));
        header.putIfAbsent("deliveryAddress", q.get("deliveryAddress"));
        header.putIfAbsent("paymentTermId", q.get("paymentTermId"));
        header.putIfAbsent("pricelistId", q.get("pricelistId"));
        header.put("currencyCode", q.get("currencyCode"));
        header.putIfAbsent("ownerId", q.get("ownerId") == null ? user() : q.get("ownerId"));
        header.putIfAbsent("teamId", q.get("teamId"));
        header.putIfAbsent("notes", q.get("notes"));
        header.putIfAbsent("terms", q.get("terms"));
        header.put("number", nextNumber());
        header.put("createdById", user());
        Map<String, Object> row = db.insert("SalesOrder", header);
        String id = str(row.get("id"));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : quoteLines) {
            if (Boolean.TRUE.equals(l.get("isOptional"))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("productId", "description", "quantity", "uomId", "unitPrice", "discountPercent",
                    "taxId", "taxInclusive", "listPrice")) {
                m.put(k, l.get(k));
            }
            m.put("priceSource", "quotation");
            m.put("quotationLineId", l.get("id"));
            lines.add(m);
        }
        writeLines(id, str(q.get("partnerId")), LocalDate.now(), lines, str(header.get("locationId")));
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
        if (in.containsKey("partnerId")) {
            String p = str(in.get("partnerId"));
            requirePartner(p);
            d.put("partnerId", p);
        }
        d.put("version", ((Number) o.get("version")).intValue() + 1);
        db.patch("SalesOrder", id, d, true);
        if (in.containsKey("lines")) {
            writeLines(id, str(d.getOrDefault("partnerId", o.get("partnerId"))), LocalDate.now(), in.get("lines"),
                    str(d.getOrDefault("locationId", o.get("locationId"))));
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
                "deliveryMethod", "paymentTermId", "currencyCode", "incoterm", "notes", "terms")) {
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
                "customerRef", "incoterm", "notes", "terms", "teamId")) {
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

    /** Replace the lines of a draft order (price resolved for product lines without an explicit price). */
    @SuppressWarnings("unchecked")
    void writeLines(String orderId, String partnerId, LocalDate on, Object raw, String defaultLocation) {
        db.update("DELETE FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ?", orderId, org());
        List<Map<String, Object>> lines = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            rows.add(line(partnerId, on, l, defaultLocation));
        }
        insertLines(orderId, rows, 0);
        retotal(orderId);
    }

    /** Validated, priced line values (not yet taxed). */
    Map<String, Object> line(String partnerId, LocalDate on, Map<String, Object> l, String defaultLocation) {
        String productId = blank(str(l.get("productId"))) ? null : str(l.get("productId"));
        BigDecimal qty = dec(l.get("quantity"));
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("quantity must be greater than 0");
        }
        QuotationPricingPort.Price price = productId == null ? null : pricing.price(partnerId, productId, qty, on);
        BigDecimal unit;
        BigDecimal disc;
        String source;
        if (l.get("unitPrice") != null || l.get("discountPercent") != null) {
            unit = l.get("unitPrice") == null ? price == null ? BigDecimal.ZERO : price.unitPrice() : dec(l.get("unitPrice"));
            disc = l.get("discountPercent") == null ? price == null ? BigDecimal.ZERO : price.discountPercent()
                    : dec(l.get("discountPercent"));
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
            BigDecimal cost = pl.productId() == null ? null : costOf(pl.productId());
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("orderId", orderId);
            ins.put("sortOrder", sortFrom + i);
            ins.put("productId", pl.productId());
            ins.put("description", pl.description());
            ins.put("quantity", pl.quantity());
            ins.put("uomId", pl.uomId());
            ins.put("uomFactor", pl.uomFactor() == null ? BigDecimal.ONE : pl.uomFactor());
            ins.put("listPrice", m.get("listPrice"));
            ins.put("unitPrice", pl.unitPrice());
            ins.put("discountPercent", pl.discountPercent());
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
            ids.add(str(db.insert("SalesOrderLine", ins).get("id")));
            i++;
        }
        return ids;
    }

    private BigDecimal costOf(String productId) {
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

    /**
     * Confirm a draft order. The exchange rate is locked; a customer on credit hold puts the order on hold (credit
     * exposure checks arrive with the credit module, phase S3).
     */
    @Transactional
    public Map<String, Object> confirm(String id) {
        Map<String, Object> o = lock(id);
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("Only draft orders can be confirmed");
        }
        if (db.count("SELECT count(*) FROM \"SalesOrderLine\" WHERE \"orderId\" = ?", List.of(id)) == 0) {
            throw ApiException.badRequest("Add at least one line before confirming");
        }
        Map<String, Object> partner = db.row("SELECT \"creditHold\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?", o.get("partnerId"), org());
        boolean hold = partner != null && Boolean.TRUE.equals(partner.get("creditHold"));
        Map<String, Object> d = Obj.of("status", "confirmed", "confirmedAt", Instant.now(), "confirmedById", user(),
                "version", ((Number) o.get("version")).intValue() + 1);
        if (hold) {
            d.put("onHold", true);
            d.put("holdReason", "Customer is on credit hold");
        }
        db.patch("SalesOrder", id, d, true);
        audit.record(AuditInput.of("SalesOrder", id, "approve", null, Obj.of("status", "confirmed", "onHold", hold)));
        publish(DomainEvents.SALES_ORDER_CONFIRMED, id, Map.of());
        if (hold) {
            publish(DomainEvents.SALES_ORDER_HELD, id, Map.of("reason", "Customer is on credit hold"));
        }
        return get(id);
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
        db.patch("SalesOrder", id, Obj.of("onHold", true, "holdReason", reason, "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("onHold", true, "reason", reason)));
        publish(DomainEvents.SALES_ORDER_HELD, id, Map.of("reason", reason));
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
        db.patch("SalesOrder", id, Obj.of("onHold", false, "holdReason", null, "version",
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
        db.patch("SalesOrder", id, Obj.of("status", "cancelled", "cancelledAt", Instant.now(), "cancelledById", user(),
                "cancelReason", blank(reason) ? null : reason, "onHold", false, "version",
                ((Number) o.get("version")).intValue() + 1), true);
        audit.record(AuditInput.of("SalesOrder", id, "cancel", null, Obj.of("reason", reason)));
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

    String nextNumber() {
        int year = LocalDate.now().getYear();
        return sequence.next("sales_order:" + year, "SO-" + year + "-", 5);
    }

    private void visibility(StringBuilder where, List<Object> params) {
        if (TenantContext.has(Permissions.SALES__ORDER_READ_ALL)) {
            return;
        }
        where.append(" AND (o.\"ownerId\" = ? OR o.\"createdById\" = ? OR o.\"ownerId\" IS NULL)");
        params.add(user());
        params.add(user());
    }

    Map<String, Object> require(String id) {
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
        if (!db.exists("Partner", partnerId)) {
            throw ApiException.notFound("Partner not found");
        }
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
