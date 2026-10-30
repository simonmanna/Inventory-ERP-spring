package com.inventoryerp.fleet.application;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.fleet.application.DispatchCommands.CloseOrderInput;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryLineInput;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryOrderInput;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryOrderPatch;
import com.inventoryerp.fleet.application.DispatchCommands.DeliveryOrderQuery;
import com.inventoryerp.fleet.application.DispatchCommands.FromSourceInput;
import com.inventoryerp.fleet.domain.FleetDeliveryOrder;
import com.inventoryerp.fleet.domain.FleetDeliveryOrderLine;
import com.inventoryerp.fleet.domain.FleetLoad;
import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.orders.application.OrdersService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
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
 * Delivery orders (fleet 1.3): created by hand, from a confirmed back-office <b>sales order</b> (orders module
 * {@code Order}, through {@link OrdersService}) or from a <b>sales invoice</b> (invoicing {@code Document}, through
 * {@link InvoiceService}); POS sales are never delivered from here. Each line keeps ordered / planned / dispatched /
 * delivered / returned quantities in the line unit with a snapshot factor to base units.
 *
 * <p><b>Stock mode.</b> {@code dispatch}: fleet issues the goods from the yard location per load (manual orders,
 * un-billed sales orders, draft invoices — the draft invoice is marked so its posting does not issue them again).
 * {@code invoiced}: the source already issued them (a posted invoice, or a sales order billed through the POS spine);
 * dispatch then moves no stock, but returns still put goods back. Status is derived from the lines and loads:
 * {@code open → planned → in_progress → delivered}, or {@code closed} (short-closed) / {@code cancelled}.
 */
@Service
public class FleetDeliveryOrderService {

    static final String AUDIT_ENTITY = "FleetDeliveryOrder";

    static final String SELECT = """
            SELECT o.*, p.name AS "partnerName", p.code AS "partnerCode", s.code AS "siteCode", s.name AS "siteName",
                   s.address AS "siteAddress", s.city AS "siteCity", s.zone AS "siteZone", s.latitude AS "siteLatitude",
                   s.longitude AS "siteLongitude", s."distanceKm" AS "siteDistanceKm", l.name AS "locationName"
            FROM "FleetDeliveryOrder" o
            LEFT JOIN "Partner" p ON p.id = o."partnerId" AND p."organizationId" = o."organizationId"
            LEFT JOIN "FleetCustomerSite" s ON s.id = o."siteId" AND s."organizationId" = o."organizationId"
            LEFT JOIN "InventoryLocation" l ON l.id = o."locationId" AND l."organizationId" = o."organizationId"
            """;

    /** Trip summary columns (shared with the dispatch service and the board). */
    static final String TRIP_SELECT = """
            SELECT t.*, v.code AS "vehicleCode", v."registrationPlate" AS "vehiclePlate", tr.code AS "trailerCode",
                   d.code AS "driverCode", trim(coalesce(e."firstName", '') || ' ' || coalesce(e."lastName", ''))
                   AS "driverName", e."userId" AS "driverUserId", tp.name AS "transporterName", o.code AS
                   "deliveryOrderCode", o."partnerId", p.name AS "partnerName", s.name AS "siteName", s.code AS "siteCode"
            FROM "FleetLoad" t
            JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId"
            LEFT JOIN "Partner" p ON p.id = o."partnerId"
            LEFT JOIN "FleetCustomerSite" s ON s.id = o."siteId"
            LEFT JOIN "FleetVehicle" v ON v.id = t."vehicleId"
            LEFT JOIN "FleetVehicle" tr ON tr.id = t."trailerId"
            LEFT JOIN "FleetDriver" d ON d.id = t."driverId"
            LEFT JOIN "HrEmployee" e ON e.id = d."employeeId"
            LEFT JOIN "Partner" tp ON tp.id = t."transporterPartnerId"
            """;

    private static final Set<String> SORTABLE_ORDER_STATUSES = Set.of("confirmed", "in_progress", "completed");

    private final FleetDb db;
    private final SequenceService sequence;
    private final AuditService audit;
    private final FleetCustomerSiteService sites;
    private final UomConversionService uoms;
    private final PosStockLocation stockLocations;
    private final OrdersService orders;
    private final InvoiceService invoices;
    private final FleetLoadFactors factors;
    private final List<DeliverySourcePort> sources;

    public FleetDeliveryOrderService(FleetDb db, SequenceService sequence, AuditService audit,
            FleetCustomerSiteService sites, UomConversionService uoms, PosStockLocation stockLocations,
            OrdersService orders, InvoiceService invoices, FleetLoadFactors factors, List<DeliverySourcePort> sources) {
        this.sources = sources;
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
        this.sites = sites;
        this.uoms = uoms;
        this.stockLocations = stockLocations;
        this.orders = orders;
        this.invoices = invoices;
        this.factors = factors;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(DeliveryOrderQuery q) {
        int p = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 25 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE o.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.search())) {
            String like = "%" + q.search().trim().replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (o.code ILIKE ? OR o.\"sourceNumber\" ILIKE ? OR p.name ILIKE ? OR s.name ILIKE ?)");
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        if (FleetVehicleService.notBlank(q.status())) {
            if ("active".equals(q.status())) {
                where.append(" AND o.status IN ('open', 'planned', 'in_progress')");
            } else {
                where.append(" AND o.status = ?");
                params.add(q.status());
            }
        }
        addEq(where, params, "o.\"partnerId\"", q.partnerId());
        addEq(where, params, "o.\"siteId\"", q.siteId());
        addEq(where, params, "o.\"sourceType\"", q.sourceType());
        if (q.dateFrom() != null) {
            where.append(" AND o.\"requestedDate\" >= ?");
            params.add(java.sql.Date.valueOf(q.dateFrom()));
        }
        if (q.dateTo() != null) {
            where.append(" AND o.\"requestedDate\" <= ?");
            params.add(java.sql.Date.valueOf(q.dateTo()));
        }
        String from = " FROM \"FleetDeliveryOrder\" o LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\" LEFT JOIN"
                + " \"FleetCustomerSite\" s ON s.id = o.\"siteId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY o.\"requestedDate\" DESC,"
                + " o.code DESC LIMIT ? OFFSET ?").params(pp))) {
            r.putAll(progress((String) r.get("id")));
            rows.add(r);
        }
        return PageResult.of(rows, p, size, total);
    }

    /** Ordered / remaining totals and trip counts of a delivery order. */
    Map<String, Object> progress(String id) {
        Map<String, Object> agg = SqlRows.one(db.jdbc().sql("SELECT count(*) AS lines, COALESCE(SUM(\"orderedQty\"), 0)"
                + " AS ordered, COALESCE(SUM(GREATEST(\"orderedQty\" - \"plannedQty\", 0)), 0) AS unplanned,"
                + " COALESCE(SUM(\"deliveredQty\"), 0) AS delivered FROM \"FleetDeliveryOrderLine\" WHERE"
                + " \"organizationId\" = ? AND \"deliveryOrderId\" = ?").params(FleetDb.org(), id));
        Map<String, Object> trips = SqlRows.one(db.jdbc().sql("SELECT count(*) FILTER (WHERE status NOT IN ('cancelled',"
                + " 'returned')) AS active, count(*) FILTER (WHERE status IN ('delivered', 'closed')) AS done FROM"
                + " \"FleetLoad\" WHERE \"organizationId\" = ? AND \"deliveryOrderId\" = ?").params(FleetDb.org(), id));
        return Obj.of("lineCount", agg.get("lines"), "orderedQty", agg.get("ordered"), "unplannedQty",
                agg.get("unplanned"), "deliveredQty", agg.get("delivered"), "tripCount", trips.get("active"),
                "tripsDone", trips.get("done"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> o = SqlRows.one(db.jdbc().sql(SELECT + " WHERE o.id = ? AND o.\"organizationId\" = ?")
                .params(id, FleetDb.org()));
        if (o == null) {
            throw ApiException.notFound("Delivery order not found");
        }
        o.put("site", o.get("siteId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT * FROM \"FleetCustomerSite\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(o.get("siteId"), FleetDb.org())));
        o.put("lines", lines(id));
        o.put("trips", SqlRows.list(db.jdbc().sql(TRIP_SELECT + " WHERE t.\"organizationId\" = ? AND"
                + " t.\"deliveryOrderId\" = ? ORDER BY t.sequence").params(FleetDb.org(), id)));
        o.put("discrepancies", SqlRows.list(db.jdbc().sql("SELECT x.*, l.code AS \"tripCode\", pr.name AS"
                + " \"productName\" FROM \"FleetDeliveryDiscrepancy\" x JOIN \"FleetLoad\" l ON l.id = x.\"loadId\" JOIN"
                + " \"Product\" pr ON pr.id = x.\"productId\" WHERE x.\"organizationId\" = ? AND x.\"deliveryOrderId\" = ?"
                + " ORDER BY x.\"createdAt\"").params(FleetDb.org(), id)));
        o.put("charges", SqlRows.list(db.jdbc().sql("SELECT * FROM \"FleetDeliveryCharge\" WHERE \"organizationId\" = ?"
                + " AND \"deliveryOrderId\" = ? ORDER BY \"createdAt\"").params(FleetDb.org(), id)));
        o.putAll(progress(id));
        return o;
    }

    /** Lines with product / unit names, remaining (unplanned) quantity and planning factors per line unit. */
    List<Map<String, Object>> lines(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT l.*, p.code AS \"productCode\", p.name AS"
                + " \"productName\", p.\"trackInventory\", u.code AS \"uomCode\" FROM \"FleetDeliveryOrderLine\" l JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"deliveryOrderId\" = ? ORDER BY l.\"lineNumber\"")
                .params(FleetDb.org(), id))) {
            BigDecimal remaining = SqlRows.dec(l, "orderedQty").subtract(SqlRows.dec(l, "plannedQty"));
            l.put("remainingQty", remaining.signum() < 0 ? BigDecimal.ZERO.setScale(3) : remaining);
            FleetLoadFactors.Factor f = factors.forProduct((String) l.get("productId"))
                    .perLineUnit(SqlRows.dec(l, "uomFactor"));
            l.put("tonnesPerUnit", f.tonnesPerUnit());
            l.put("m3PerUnit", f.m3PerUnit());
            l.put("factorSource", f.source());
            out.add(l);
        }
        return out;
    }

    /** The row (404). {@code lock}: {@code FOR UPDATE} — the dispatch services lock the order before its trips. */
    public Map<String, Object> require(String id, boolean lock) {
        return db.require(FleetDeliveryOrder.class, id, lock, "Delivery order not found");
    }

    // ------------------------------------------------------------------ create

    private record Line(String productId, String variantId, String description, String uomId, BigDecimal uomFactor,
            BigDecimal qty, String sourceLineId, BigDecimal unitPrice, BigDecimal discountPercent, String taxId,
            String batchNumber, String notes) {}

    private record Header(String partnerId, String siteId, String locationId, LocalDate requestedDate, String windowFrom,
            String windowTo, String priority, String contactName, String contactPhone, String costCenterId,
            String branchId, String notes) {}

    /** Manual delivery order. */
    @Transactional
    public Map<String, Object> create(DeliveryOrderInput in) {
        List<Line> lines = new ArrayList<>();
        for (DeliveryLineInput l : in.lines()) {
            Map<String, Object> product = product(l.productId());
            String uomId = blank(l.uomId()) == null ? (String) product.get("uomId") : l.uomId();
            BigDecimal factor = uoms.factorToBase(l.productId(), uomId);
            lines.add(new Line(l.productId(), null, FleetVehicleService.trim(l.description()), uomId, factor,
                    l.quantity(), null, l.unitPrice() != null ? l.unitPrice() : (BigDecimal) product.get("salesPrice"),
                    null, blank(l.taxId()), FleetVehicleService.trim(l.batchNumber()),
                    FleetVehicleService.trim(l.notes())));
        }
        Header h = new Header(in.partnerId(), blank(in.siteId()), blank(in.locationId()), in.requestedDate(),
                in.windowFrom(), in.windowTo(), in.priority(), in.contactName(), in.contactPhone(),
                blank(in.costCenterId()), blank(in.branchId()), in.notes());
        return insert(h, "manual", null, null, "dispatch", null, null, null, lines);
    }

    /** From a confirmed back-office sales order (never a POS sale). */
    @Transactional
    public Map<String, Object> fromSalesOrder(FromSourceInput in) {
        DeliverySourcePort port = sourcePort("sales_order", in.sourceId());
        if (port != null) {
            return fromSource(port, in);
        }
        Map<String, Object> order = orders.getOrder(in.sourceId());
        String number = str(order.get("orderNumber"));
        String status = str(order.get("status"));
        if (order.get("tableId") != null || order.get("cashSessionId") != null) {
            throw ApiException.badRequest("Order " + number + " is a POS sale; POS sales are not delivered from fleet"
                    + " dispatch");
        }
        if (!SORTABLE_ORDER_STATUSES.contains(status)) {
            throw ApiException.badRequest("Only confirmed sales orders can be delivered (order " + number + " is "
                    + status + ")");
        }
        assertNoLiveOrder("sales_order", in.sourceId(), "Sales order " + number);
        List<Line> lines = new ArrayList<>();
        Object items = order.get("items");
        if (items instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> it) || it.get("productId") == null
                        || Boolean.TRUE.equals(it.get("cancelled"))) {
                    continue;
                }
                String productId = str(it.get("productId"));
                Map<String, Object> product = product(productId);
                BigDecimal qty = dec(it.get("quantity"));
                if (it.get("voidedQty") != null) {
                    qty = qty.subtract(dec(it.get("voidedQty")));
                }
                if (qty.signum() <= 0) {
                    continue;
                }
                lines.add(new Line(productId, str(it.get("variantId")), str(it.get("description")),
                        (String) product.get("uomId"), BigDecimal.ONE, qty.setScale(3, RoundingMode.HALF_UP),
                        str(it.get("id")), decOrNull(it.get("unitPrice")), decOrNull(it.get("discountPercent")),
                        str(it.get("taxId")), null, str(it.get("note"))));
            }
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Sales order " + number + " has no product lines to deliver");
        }
        String invoiceId = str(order.get("invoiceId"));
        String invoiceNumber = null;
        if (order.get("invoice") instanceof Map<?, ?> inv) {
            invoiceNumber = str(inv.get("invoiceNumber"));
        }
        Header h = header(in, str(order.get("partnerId")), str(order.get("branchId")), null);
        return insert(h, "sales_order", in.sourceId(), number, invoiceId != null ? "invoiced" : "dispatch", invoiceId,
                invoiceNumber, invoiceId != null ? "pos_invoice" : null, lines);
    }

    /** Provider owning a source document (ADR-S011 sales orders), or null for the legacy back-office order path. */
    public DeliverySourcePort sourcePort(String sourceType, String sourceId) {
        for (DeliverySourcePort p : sources) {
            if (p.supports(sourceType, sourceId)) {
                return p;
            }
        }
        return null;
    }

    /** From a B2B sales order (ADR-S011): open quantity of each line, the order's site / yard by default. */
    private Map<String, Object> fromSource(DeliverySourcePort port, FromSourceInput in) {
        port.assertDeliverable(in.sourceId());
        DeliverySourcePort.Source src = port.load(in.sourceId());
        assertNoLiveOrder("sales_order", in.sourceId(), "Sales order " + src.number());
        List<Line> lines = new ArrayList<>();
        for (DeliverySourcePort.SourceLine l : src.lines()) {
            if (l.quantity().signum() <= 0) {
                continue;
            }
            Map<String, Object> product = product(l.productId());
            String uomId = l.uomId() != null ? l.uomId() : (String) product.get("uomId");
            lines.add(new Line(l.productId(), null, l.description(), uomId, l.uomFactor(), l.quantity().setScale(3,
                    RoundingMode.HALF_UP), l.sourceLineId(), l.unitPrice(), l.discountPercent(), l.taxId(), null, null));
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Sales order " + src.number() + " has nothing left to deliver");
        }
        FromSourceInput withDefaults = new FromSourceInput(in.sourceId(), in.siteId() != null ? in.siteId() : src.siteId(),
                in.locationId() != null ? in.locationId() : src.locationId(), in.requestedDate(), in.windowFrom(),
                in.windowTo(), in.priority(), in.contactName(), in.contactPhone(), in.costCenterId(), in.notes());
        Header h = header(withDefaults, src.partnerId(), src.branchId(), src.requestedDate());
        return insert(h, "sales_order", in.sourceId(), src.number(), "dispatch", null, null, null, lines);
    }

    /** From a sales invoice: draft → fleet issues the stock (the invoice is marked), posted → already issued. */
    @Transactional
    public Map<String, Object> fromInvoice(FromSourceInput in) {
        Map<String, Object> head = SqlRows.one(db.jdbc().sql("SELECT id, \"documentNumber\", status::text AS status,"
                + " \"partnerId\", \"branchId\", \"deliveryDate\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"documentType\" = 'sales_invoice'").params(in.sourceId(), FleetDb.org()));
        if (head == null) {
            throw ApiException.notFound("Invoice not found");
        }
        String number = str(head.get("documentNumber"));
        String status = str(head.get("status"));
        if (!Set.of("draft", "posted", "paid").contains(status)) {
            throw ApiException.badRequest("Invoice " + number + " is " + status + " and cannot be delivered");
        }
        assertNoLiveOrder("invoice", in.sourceId(), "Invoice " + number);
        Map<String, Object> doc = invoices.findOne(in.sourceId());
        List<Line> lines = new ArrayList<>();
        if (doc.get("lines") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> l) || l.get("productId") == null) {
                    continue;
                }
                BigDecimal qty = dec(l.get("quantity"));
                if (qty.signum() <= 0) {
                    continue;
                }
                String productId = str(l.get("productId"));
                Map<String, Object> product = product(productId);
                String uomId = l.get("uomId") != null ? str(l.get("uomId")) : (String) product.get("uomId");
                BigDecimal factor = l.get("uomFactor") != null ? dec(l.get("uomFactor"))
                        : uoms.factorToBase(productId, uomId);
                lines.add(new Line(productId, null, str(l.get("description")), uomId, factor,
                        qty.setScale(3, RoundingMode.HALF_UP), str(l.get("id")), decOrNull(l.get("unitPrice")),
                        decOrNull(l.get("discountPercent")), str(l.get("taxId")), null, null));
            }
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Invoice " + number + " has no product lines to deliver");
        }
        LocalDate deliveryDate = head.get("deliveryDate") instanceof Instant i
                ? i.atZone(BusinessZone.get()).toLocalDate() : null;
        Header h = header(in, str(head.get("partnerId")), str(head.get("branchId")), deliveryDate);
        boolean draft = "draft".equals(status);
        Map<String, Object> out = insert(h, "invoice", in.sourceId(), number, draft ? "dispatch" : "invoiced",
                in.sourceId(), number, "document", lines);
        if (draft) {
            invoices.setStockIssuedByDelivery(in.sourceId(), (String) out.get("code"));
        }
        return out;
    }

    private Header header(FromSourceInput in, String partnerId, String branchId, LocalDate fallbackDate) {
        LocalDate date = in.requestedDate() != null ? in.requestedDate() : fallbackDate != null ? fallbackDate : today();
        return new Header(partnerId, blank(in.siteId()), blank(in.locationId()), date, in.windowFrom(), in.windowTo(),
                in.priority(), in.contactName(), in.contactPhone(), blank(in.costCenterId()), branchId, in.notes());
    }

    private Map<String, Object> insert(Header h, String sourceType, String sourceId, String sourceNumber,
            String stockMode, String goodsInvoiceId, String goodsInvoiceNumber, String goodsInvoiceKind,
            List<Line> lines) {
        if (h.partnerId() == null || !db.exists("Partner", h.partnerId(), true)) {
            throw ApiException.badRequest("Customer not found");
        }
        Map<String, Object> site = h.siteId() == null ? null : sites.requireFor(h.siteId(), h.partnerId());
        String location = stockLocations.resolve(FleetDb.org(), h.locationId());
        if (location == null) {
            throw ApiException.badRequest(h.locationId() == null
                    ? "Choose the yard / warehouse location the goods leave from"
                    : "Location not found");
        }
        assertCostCenter(h.costCenterId());
        assertWindow(h.windowFrom(), h.windowTo());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", sequence.next("fleet_delivery_order", "DO-", 6));
        data.put("partnerId", h.partnerId());
        data.put("siteId", h.siteId());
        data.put("sourceType", sourceType);
        data.put("sourceId", sourceId);
        data.put("sourceNumber", sourceNumber);
        data.put("stockMode", stockMode);
        data.put("goodsInvoiceId", goodsInvoiceId);
        data.put("goodsInvoiceNumber", goodsInvoiceNumber);
        data.put("goodsInvoiceKind", goodsInvoiceKind);
        data.put("branchId", h.branchId());
        data.put("locationId", location);
        data.put("costCenterId", h.costCenterId());
        data.put("requestedDate", h.requestedDate());
        data.put("windowFrom", h.windowFrom());
        data.put("windowTo", h.windowTo());
        data.put("priority", h.priority() == null ? "normal" : h.priority());
        data.put("contactName", FleetVehicleService.trim(h.contactName() != null ? h.contactName()
                : site == null ? null : (String) site.get("contactName")));
        data.put("contactPhone", FleetVehicleService.trim(h.contactPhone() != null ? h.contactPhone()
                : site == null ? null : (String) site.get("contactPhone")));
        data.put("notes", FleetVehicleService.trim(h.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row;
        try {
            row = db.insert(FleetDeliveryOrder.class, data);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("This document already has a live delivery order");
        }
        String id = (String) row.get("id");
        int n = 0;
        for (Line l : lines) {
            Map<String, Object> ld = new LinkedHashMap<>();
            ld.put("deliveryOrderId", id);
            ld.put("lineNumber", ++n);
            ld.put("productId", l.productId());
            ld.put("variantId", l.variantId());
            ld.put("description", l.description());
            ld.put("uomId", l.uomId());
            ld.put("uomFactor", l.uomFactor() == null ? BigDecimal.ONE : l.uomFactor());
            ld.put("orderedQty", l.qty());
            ld.put("sourceLineId", l.sourceLineId());
            ld.put("unitPrice", l.unitPrice());
            ld.put("discountPercent", l.discountPercent());
            ld.put("taxId", l.taxId());
            ld.put("batchNumber", l.batchNumber());
            ld.put("notes", l.notes());
            db.insert(FleetDeliveryOrderLine.class, ld);
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, Obj.of("code", row.get("code"),
                "sourceType", sourceType, "sourceNumber", sourceNumber, "lines", lines.size())));
        return get(id);
    }

    // ------------------------------------------------------------------ edit / cancel / close

    @Transactional
    public Map<String, Object> update(String id, DeliveryOrderPatch in) {
        Map<String, Object> before = require(id, true);
        assertEditable(before);
        Map<String, Object> data = new LinkedHashMap<>();
        if (blank(in.siteId()) != null) {
            sites.requireFor(in.siteId(), (String) before.get("partnerId"));
            data.put("siteId", in.siteId());
        }
        if (blank(in.locationId()) != null) {
            if (stockLocations.resolve(FleetDb.org(), in.locationId()) == null) {
                throw ApiException.badRequest("Location not found");
            }
            long moved = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND"
                    + " \"deliveryOrderId\" = ? AND \"stockIssuedAt\" IS NOT NULL").params(FleetDb.org(), id)
                    .query(Long.class).single();
            if (moved > 0 && !in.locationId().equals(before.get("locationId"))) {
                throw ApiException.badRequest("Goods already left the yard; the location of new trips is set on the"
                        + " trip");
            }
            data.put("locationId", in.locationId());
        }
        if (blank(in.costCenterId()) != null) {
            assertCostCenter(in.costCenterId());
            data.put("costCenterId", in.costCenterId());
        }
        putIf(data, "requestedDate", in.requestedDate());
        putIf(data, "windowFrom", in.windowFrom());
        putIf(data, "windowTo", in.windowTo());
        putIf(data, "priority", in.priority());
        putIf(data, "contactName", FleetVehicleService.trim(in.contactName()));
        putIf(data, "contactPhone", FleetVehicleService.trim(in.contactPhone()));
        putIf(data, "notes", FleetVehicleService.trim(in.notes()));
        if (in.clear() != null) {
            for (String c : in.clear()) {
                data.put(c, null);
            }
        }
        assertWindow((String) (data.containsKey("windowFrom") ? data.get("windowFrom") : before.get("windowFrom")),
                (String) (data.containsKey("windowTo") ? data.get("windowTo") : before.get("windowTo")));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetDeliveryOrder.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Cancel: only while no goods left the yard; open trips are cancelled with it; a marked draft invoice is freed. */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> before = require(id, true);
        assertEditable(before);
        long moved = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ? AND status IN ('dispatched', 'in_transit', 'delivered', 'closed')")
                .params(FleetDb.org(), id).query(Long.class).single();
        if (moved > 0) {
            throw ApiException.badRequest("Delivery order " + before.get("code") + " has dispatched trips; return or"
                    + " deliver them, then close the order instead");
        }
        db.jdbc().sql("UPDATE \"FleetLoad\" SET status = 'cancelled', \"cancelledAt\" = " + FleetDb.NOW + ","
                + " \"cancelReason\" = ?, \"updatedAt\" = " + FleetDb.NOW + " WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ? AND status IN ('planned', 'loading', 'weighed')")
                .params("Delivery order cancelled: " + reason.trim(), FleetDb.org(), id).update();
        db.jdbc().sql("UPDATE \"FleetDeliveryOrderLine\" SET \"plannedQty\" = 0, \"updatedAt\" = " + FleetDb.NOW
                + " WHERE \"organizationId\" = ? AND \"deliveryOrderId\" = ?").params(FleetDb.org(), id).update();
        Map<String, Object> after = db.update(FleetDeliveryOrder.class, id, Obj.of("status", "cancelled",
                "cancelReason", reason.trim(), "cancelledAt", Instant.now(), "updatedBy", TenantContext.userId()));
        if ("invoice".equals(before.get("sourceType")) && "dispatch".equals(before.get("stockMode"))) {
            String st = db.jdbc().sql("SELECT status::text FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(before.get("sourceId"), FleetDb.org()).query(String.class).optional().orElse(null);
            if ("draft".equals(st)) {
                invoices.setStockIssuedByDelivery((String) before.get("sourceId"), null);
            }
        }
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancel", before, after));
        return get(id);
    }

    /** Short-close: the remaining quantity will not be delivered; trips still open must be finished or cancelled. */
    @Transactional
    public Map<String, Object> close(String id, CloseOrderInput in) {
        Map<String, Object> before = require(id, true);
        assertEditable(before);
        long open = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ? AND status IN ('planned', 'loading', 'weighed', 'dispatched', 'in_transit')")
                .params(FleetDb.org(), id).query(Long.class).single();
        if (open > 0) {
            throw ApiException.badRequest("Delivery order " + before.get("code") + " has " + open + " trip(s) still"
                    + " open; deliver, return or cancel them first");
        }
        Map<String, Object> after = db.update(FleetDeliveryOrder.class, id, Obj.of("status", "closed", "closedAt",
                Instant.now(), "closeReason", in == null ? null : FleetVehicleService.trim(in.reason()), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Re-derive the status from lines and trips (called after every trip change). */
    public void refreshStatus(String id) {
        Map<String, Object> o = require(id, false);
        String status = (String) o.get("status");
        if ("cancelled".equals(status) || "closed".equals(status)) {
            return;
        }
        List<Map<String, Object>> trips = db.list(FleetLoad.class, " AND \"deliveryOrderId\" = ?", id);
        BigDecimal unplanned = db.jdbc().sql("SELECT COALESCE(SUM(GREATEST(\"orderedQty\" - \"plannedQty\", 0)), 0)"
                + " FROM \"FleetDeliveryOrderLine\" WHERE \"organizationId\" = ? AND \"deliveryOrderId\" = ?")
                .params(FleetDb.org(), id).query(BigDecimal.class).single();
        boolean anyMoved = false;
        boolean allDone = true;
        boolean anyActive = false;
        for (Map<String, Object> t : trips) {
            String s = (String) t.get("status");
            if (!TripStatusPolicy.isActive(s)) {
                if ("returned".equals(s)) {
                    anyMoved = true;
                }
                continue;
            }
            anyActive = true;
            if (!TripStatusPolicy.isOpen(s)) {
                anyMoved = true;
            }
            if (!"delivered".equals(s) && !"closed".equals(s)) {
                allDone = false;
            }
        }
        String next;
        if (anyMoved && anyActive && allDone && unplanned.signum() <= 0) {
            next = "delivered";
        } else if (anyMoved) {
            next = "in_progress";
        } else if (anyActive && unplanned.signum() <= 0) {
            next = "planned";
        } else {
            next = "open";
        }
        if (!next.equals(status)) {
            db.update(FleetDeliveryOrder.class, id, Obj.of("status", next));
        }
    }

    // ------------------------------------------------------------------ lookups / board

    /** Sales orders and sales invoices that can still become a delivery order. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sources(String type, String search) {
        String like = FleetVehicleService.notBlank(search)
                ? "%" + search.trim().replace("%", "\\%").replace("_", "\\_") + "%" : "%";
        if ("invoice".equals(type)) {
            return SqlRows.list(db.jdbc().sql("SELECT d.id, d.\"documentNumber\" AS number, d.status::text AS status,"
                    + " d.\"issueDate\" AS date, d.\"totalAmount\" AS total, d.\"partnerId\", p.name AS \"partnerName\""
                    + " FROM \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" ="
                    + " ? AND d.\"documentType\" = 'sales_invoice' AND d.status::text IN ('draft', 'posted', 'paid') AND"
                    + " (d.\"documentNumber\" ILIKE ? OR p.name ILIKE ?) AND NOT EXISTS (SELECT 1 FROM"
                    + " \"FleetDeliveryOrder\" f WHERE f.\"organizationId\" = d.\"organizationId\" AND f.\"sourceType\" ="
                    + " 'invoice' AND f.\"sourceId\" = d.id AND f.status <> 'cancelled') ORDER BY d.\"issueDate\" DESC"
                    + " LIMIT 50").params(FleetDb.org(), like, like));
        }
        return SqlRows.list(db.jdbc().sql("SELECT o.id, o.\"orderNumber\" AS number, o.status::text AS status,"
                + " o.\"openedAt\" AS date, o.\"totalAmount\" AS total, o.\"partnerId\", p.name AS \"partnerName\","
                + " o.\"invoiceId\" FROM \"Order\" o LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\" WHERE"
                + " o.\"organizationId\" = ? AND o.status::text IN ('confirmed', 'in_progress', 'completed') AND"
                + " o.\"tableId\" IS NULL AND o.\"cashSessionId\" IS NULL AND (o.\"orderNumber\" ILIKE ? OR p.name ILIKE"
                + " ?) AND EXISTS (SELECT 1 FROM \"OrderItem\" i WHERE i.\"orderId\" = o.id AND i.\"productId\" IS NOT"
                + " NULL AND i.cancelled = false) AND NOT EXISTS (SELECT 1 FROM \"FleetDeliveryOrder\" f WHERE"
                + " f.\"organizationId\" = o.\"organizationId\" AND f.\"sourceType\" = 'sales_order' AND f.\"sourceId\" ="
                + " o.id AND f.status <> 'cancelled') ORDER BY o.\"openedAt\" DESC NULLS LAST LIMIT 50")
                .params(FleetDb.org(), like, like));
    }

    /** Form / board lookups: vehicles with capacity, drivers, transporters + rates, yard locations, cost centres. */
    @Transactional(readOnly = true)
    public Map<String, Object> lookups() {
        String org = FleetDb.org();
        return Obj.of(
                "vehicles", SqlRows.list(db.jdbc().sql("SELECT v.id, v.code, v.\"registrationPlate\", v.status,"
                        + " v.ownership, v.\"payloadCapacityT\", v.\"bodyVolumeM3\", v.\"costCenterId\", c.name AS"
                        + " \"categoryName\", c.kind FROM \"FleetVehicle\" v LEFT JOIN \"FleetVehicleCategory\" c ON c.id ="
                        + " v.\"categoryId\" WHERE v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL AND v.status <>"
                        + " 'disposed' AND (c.kind IS NULL OR c.kind IN ('road_vehicle', 'trailer')) ORDER BY v.code")
                        .param(org)),
                "drivers", SqlRows.list(db.jdbc().sql("SELECT d.id, d.code, d.status, trim(coalesce(e.\"firstName\", '')"
                        + " || ' ' || coalesce(e.\"lastName\", '')) AS name FROM \"FleetDriver\" d LEFT JOIN \"HrEmployee\""
                        + " e ON e.id = d.\"employeeId\" WHERE d.\"organizationId\" = ? AND d.\"deletedAt\" IS NULL AND"
                        + " d.status = 'active' ORDER BY d.code").param(org)),
                "transporters", SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Partner\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isSupplier\" ORDER BY name LIMIT 500")
                        .param(org)),
                "transporterRates", SqlRows.list(db.jdbc().sql("SELECT id, \"partnerId\", name, basis, rate, zone FROM"
                        + " \"FleetTransporterRate\" WHERE \"organizationId\" = ? AND \"isActive\" ORDER BY name")
                        .param(org)),
                "locations", SqlRows.list(db.jdbc().sql("SELECT id, code, name, type::text AS type FROM"
                        + " \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\""
                        + " ORDER BY name").param(org)),
                "defaultLocationId", stockLocations.resolve(org, null),
                "costCenters", SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"CostCenter\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY code").param(org)),
                "taxes", SqlRows.list(db.jdbc().sql("SELECT id, name, rate FROM \"Tax\" WHERE \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL ORDER BY name").param(org)));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> customers(String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        return SqlRows.list(db.jdbc().sql("SELECT id, code, name, phone FROM \"Partner\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL AND (name ILIKE ? OR code ILIKE ?) ORDER BY \"isCustomer\" DESC, name LIMIT 30")
                .params(FleetDb.org(), like, like));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> products(String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : SqlRows.list(db.jdbc().sql("SELECT p.id, p.code, p.name, p.\"uomId\", u.code AS"
                + " \"uomCode\", p.\"salesPrice\", p.\"trackInventory\", p.\"batchTracking\" FROM \"Product\" p LEFT JOIN"
                + " \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL"
                + " AND (p.name ILIKE ? OR p.code ILIKE ?) ORDER BY p.name LIMIT 30").params(FleetDb.org(), like, like))) {
            FleetLoadFactors.Factor f = factors.forProduct((String) p.get("id"));
            p.put("tonnesPerUnit", f.tonnesPerUnit());
            p.put("m3PerUnit", f.m3PerUnit());
            out.add(p);
        }
        return out;
    }

    /**
     * Dispatch board: trips per planned day in [{@code from}, {@code to}] (default this week) and the delivery orders
     * that still have quantity to plan, optionally for one customer / site.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> board(LocalDate from, LocalDate to, String partnerId, String siteId) {
        LocalDate f = from == null ? today() : from;
        LocalDate t = to == null ? f.plusDays(6) : to;
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must be on or after from");
        }
        if (f.plusDays(62).isBefore(t)) {
            throw ApiException.badRequest("The board shows at most 62 days");
        }
        StringBuilder extra = new StringBuilder();
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), java.sql.Date.valueOf(f), java.sql.Date.valueOf(t)));
        if (FleetVehicleService.notBlank(partnerId)) {
            extra.append(" AND o.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (FleetVehicleService.notBlank(siteId)) {
            extra.append(" AND o.\"siteId\" = ?");
            params.add(siteId);
        }
        List<Map<String, Object>> trips = SqlRows.list(db.jdbc().sql(TRIP_SELECT + " WHERE t.\"organizationId\" = ? AND"
                + " t.\"plannedDate\" BETWEEN ? AND ? AND t.status <> 'cancelled'" + extra
                + " ORDER BY t.\"plannedDate\", t.code").params(params));
        Map<String, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
            byDay.put(d.toString(), new ArrayList<>());
        }
        for (Map<String, Object> trip : trips) {
            byDay.computeIfAbsent(trip.get("plannedDate").toString(), k -> new ArrayList<>()).add(trip);
        }
        List<Map<String, Object>> days = new ArrayList<>();
        byDay.forEach((d, list) -> days.add(Obj.of("date", d, "trips", list)));
        List<Object> up = new ArrayList<>(List.of(FleetDb.org(), java.sql.Date.valueOf(t)));
        StringBuilder upExtra = new StringBuilder();
        if (FleetVehicleService.notBlank(partnerId)) {
            upExtra.append(" AND o.\"partnerId\" = ?");
            up.add(partnerId);
        }
        if (FleetVehicleService.notBlank(siteId)) {
            upExtra.append(" AND o.\"siteId\" = ?");
            up.add(siteId);
        }
        List<Map<String, Object>> unplanned = new ArrayList<>();
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql(SELECT + " WHERE o.\"organizationId\" = ? AND o.status IN"
                + " ('open', 'planned', 'in_progress') AND o.\"requestedDate\" <= ?" + upExtra + " AND EXISTS (SELECT 1"
                + " FROM \"FleetDeliveryOrderLine\" x WHERE x.\"deliveryOrderId\" = o.id AND x.\"orderedQty\" >"
                + " x.\"plannedQty\") ORDER BY o.\"requestedDate\", o.priority DESC, o.code LIMIT 200").params(up))) {
            o.putAll(progress((String) o.get("id")));
            unplanned.add(o);
        }
        return Obj.of("from", f.toString(), "to", t.toString(), "days", days, "unplanned", unplanned);
    }

    // ------------------------------------------------------------------ helpers

    private void assertNoLiveOrder(String sourceType, String sourceId, String label) {
        String existing = db.jdbc().sql("SELECT code FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = ? AND \"sourceId\" = ? AND status <> 'cancelled' LIMIT 1")
                .params(FleetDb.org(), sourceType, sourceId).query(String.class).optional().orElse(null);
        if (existing != null) {
            throw ApiException.conflict(label + " already has delivery order " + existing);
        }
    }

    private Map<String, Object> product(String productId) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT id, name, \"uomId\", \"salesPrice\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(productId, FleetDb.org()));
        if (p == null) {
            throw ApiException.badRequest("Product not found");
        }
        return p;
    }

    private void assertCostCenter(String id) {
        if (id != null && !db.exists("CostCenter", id, true)) {
            throw ApiException.badRequest("Cost centre not found");
        }
    }

    static void assertEditable(Map<String, Object> o) {
        String s = (String) o.get("status");
        if ("cancelled".equals(s) || "closed".equals(s)) {
            throw ApiException.badRequest("Delivery order " + o.get("code") + " is " + s + " and cannot be changed");
        }
    }

    private static void assertWindow(String from, String to) {
        if (from != null && to != null && to.compareTo(from) <= 0) {
            throw ApiException.badRequest("The delivery window must end after it starts");
        }
    }

    private static void addEq(StringBuilder where, List<Object> params, String col, String v) {
        if (FleetVehicleService.notBlank(v)) {
            where.append(" AND ").append(col).append(" = ?");
            params.add(v);
        }
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    static LocalDate today() {
        return LocalDate.now(BusinessZone.get());
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static BigDecimal dec(Object o) {
        BigDecimal d = decOrNull(o);
        return d == null ? BigDecimal.ZERO : d;
    }

    static BigDecimal decOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        return new BigDecimal(o.toString());
    }
}
