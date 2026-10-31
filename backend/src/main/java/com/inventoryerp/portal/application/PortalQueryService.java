package com.inventoryerp.portal.application;

import com.inventoryerp.accounting.application.ReportExportService.Export;
import com.inventoryerp.invoicing.application.InvoiceDeliveryService;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.StatementService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a customer sees (ADR-S050). The partner always comes from {@link PortalAccountService#current()}; a document of
 * another customer answers 404, like one of another organization. Rows are explicit column lists: costs, freight,
 * margins, staff names and internal notes never leave this class.
 */
@Service
public class PortalQueryService {

    private static final Set<String> ON_THE_ROAD = Set.of("dispatched", "in_transit");

    private static final String TRIP_COLUMNS = """
            SELECT t.id, t.code, t.status, t."plannedDate", t."loadingStartedAt", t."weighedAt", t."dispatchedAt",
                   t."departedAt", t."deliveredAt", t."plannedTonnes", t."loadedTonnes", t."deliveredTonnes",
                   t."deliveryNoteNumber", t."vehicleId", o.code AS "deliveryOrderCode",
                   o."sourceNumber" AS "orderNumber",
                   s.name AS "siteName", COALESCE(v."registrationPlate", t."transporterPlate") AS "vehiclePlate",
                   e."firstName" AS "driverFirstName"
            FROM "FleetLoad" t
            JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId"
            LEFT JOIN "FleetCustomerSite" s ON s.id = o."siteId"
            LEFT JOIN "FleetVehicle" v ON v.id = t."vehicleId"
            LEFT JOIN "FleetDriver" d ON d.id = t."driverId"
            LEFT JOIN "HrEmployee" e ON e.id = d."employeeId"
            """;

    private final JdbcClient jdbc;
    private final PortalAccountService accounts;
    private final InvoiceService invoices;
    private final InvoiceDeliveryService invoicePdf;
    private final StatementService statements;

    public PortalQueryService(JdbcClient jdbc, PortalAccountService accounts, InvoiceService invoices,
            InvoiceDeliveryService invoicePdf, StatementService statements) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.invoices = invoices;
        this.invoicePdf = invoicePdf;
        this.statements = statements;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private String partner() {
        return accounts.current().partnerId();
    }

    private static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int size(Integer pageSize) {
        return pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
    }

    // ------------------------------------------------------------------ home

    /** Who I am, what I owe, and how many deliveries are on their way. */
    @Transactional(readOnly = true)
    public Map<String, Object> home() {
        String partnerId = partner();
        Map<String, Object> customer = SqlRows.one(jdbc.sql("SELECT id, code, name, email, phone FROM \"Partner\" WHERE"
                + " id = ? AND \"organizationId\" = ?").params(partnerId, org()));
        Map<String, Object> organization = SqlRows.one(jdbc.sql("SELECT name, \"currencyCode\" FROM \"Organization\""
                + " WHERE id = ?").param(org()));
        BigDecimal open = jdbc.sql("""
                SELECT COALESCE(sum(x.r), 0) FROM (
                  SELECT "amountResidual" AS r FROM "Document" WHERE "organizationId" = ? AND "partnerId" = ?
                    AND "documentType"::text = 'sales_invoice' AND status::text IN ('posted', 'paid')
                  UNION ALL
                  SELECT "amountResidual" AS r FROM "Invoice" WHERE "organizationId" = ? AND "partnerId" = ?
                    AND status::text IN ('posted', 'paid')) x""")
                .params(org(), partnerId, org(), partnerId).query(BigDecimal.class).single();
        long live = jdbc.sql("""
                SELECT count(*) FROM "FleetLoad" t JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId"
                WHERE t."organizationId" = ? AND o."partnerId" = ?
                  AND t.status IN ('planned', 'loading', 'weighed', 'dispatched', 'in_transit')""")
                .params(org(), partnerId).query(Long.class).single();
        long orders = jdbc.sql("SELECT count(*) FROM \"Order\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?"
                + " AND status::text IN ('confirmed', 'in_progress')").params(org(), partnerId).query(Long.class)
                .single();
        return Obj.of("customer", customer, "organization", organization, "openBalance", open, "liveDeliveries", live,
                "openOrders", orders);
    }

    // ------------------------------------------------------------------ orders

    /** My sales orders, newest first; {@code status} narrows the list. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> orders(String status, Integer page, Integer pageSize) {
        String partnerId = partner();
        int p = page(page);
        int size = size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ? AND \"partnerId\" = ?"
                + " AND status::text <> 'draft'");
        List<Object> params = new ArrayList<>(List.of(org(), partnerId));
        if (status != null && !status.isBlank()) {
            where.append(" AND status::text = ?");
            params.add(status);
        }
        long total = jdbc.sql("SELECT count(*) FROM \"Order\"" + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, \"orderNumber\", status::text AS status,"
                + " \"openedAt\", \"closedAt\", \"totalAmount\", \"invoiceId\" FROM \"Order\"" + where
                + " ORDER BY \"openedAt\" DESC, id LIMIT ? OFFSET ?").params(params));
        return PageResult.of(rows, p, size, total);
    }

    /** One of my orders: lines and the deliveries against it. */
    @Transactional(readOnly = true)
    public Map<String, Object> order(String id) {
        String partnerId = partner();
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\", status::text AS status, \"openedAt\","
                + " \"closedAt\", subtotal, \"discountTotal\", \"taxAmount\", \"totalAmount\", \"invoiceId\" FROM"
                + " \"Order\" WHERE id = ? AND \"organizationId\" = ? AND \"partnerId\" = ?")
                .params(id, org(), partnerId));
        if (o == null || "draft".equals(o.get("status"))) {
            throw ApiException.notFound("Order not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(o);
        out.put("items", SqlRows.list(jdbc.sql("SELECT id, description, quantity, \"unitPrice\", \"discountAmount\""
                + " FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ? AND cancelled = false"
                + " ORDER BY \"createdAt\", id").params(id, org())));
        out.put("deliveries", SqlRows.list(jdbc.sql(TRIP_COLUMNS + """
                 WHERE t."organizationId" = ? AND o."partnerId" = ? AND o."sourceType" = 'sales_order'
                   AND o."sourceId" = ? ORDER BY t."plannedDate", t.code""").params(org(), partnerId, id)));
        return out;
    }

    // ------------------------------------------------------------------ deliveries

    /** My deliveries (trips); {@code scope} = active (default, not finished), done or all. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> deliveries(String scope, Integer page, Integer pageSize) {
        String partnerId = partner();
        int p = page(page);
        int size = size(pageSize);
        String filter = switch (scope == null ? "active" : scope) {
            case "done" -> " AND t.status IN ('delivered', 'closed', 'returned')";
            case "all" -> "";
            default -> " AND t.status IN ('planned', 'loading', 'weighed', 'dispatched', 'in_transit')";
        };
        long total = jdbc.sql("SELECT count(*) FROM \"FleetLoad\" t JOIN \"FleetDeliveryOrder\" o ON o.id ="
                + " t.\"deliveryOrderId\" WHERE t.\"organizationId\" = ? AND o.\"partnerId\" = ?" + filter)
                .params(org(), partnerId).query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(TRIP_COLUMNS + " WHERE t.\"organizationId\" = ? AND"
                + " o.\"partnerId\" = ?" + filter + " ORDER BY t.\"plannedDate\" DESC, t.code DESC LIMIT ? OFFSET ?")
                .params(org(), partnerId, size, (p - 1) * size));
        return PageResult.of(rows, p, size, total);
    }

    /** One delivery: lines, proof of delivery, and the truck's last position while it is on the road. */
    @Transactional(readOnly = true)
    public Map<String, Object> delivery(String id) {
        String partnerId = partner();
        Map<String, Object> t = SqlRows.one(jdbc.sql(TRIP_COLUMNS + " WHERE t.id = ? AND t.\"organizationId\" = ? AND"
                + " o.\"partnerId\" = ?").params(id, org(), partnerId));
        if (t == null) {
            throw ApiException.notFound("Delivery not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(t);
        Object vehicleId = out.remove("vehicleId");
        out.put("lines", SqlRows.list(jdbc.sql("""
                SELECT l.id, pr.name AS "productName", l."plannedQty", l."loadedQty", l."dispatchedQty",
                       l."deliveredQty", l."returnedQty", u.code AS "uomCode"
                FROM "FleetLoadLine" l JOIN "Product" pr ON pr.id = l."productId"
                LEFT JOIN "UnitOfMeasure" u ON u.id = l."uomId"
                WHERE l."loadId" = ? AND l."organizationId" = ? ORDER BY l."createdAt", l.id""")
                .params(id, org())));
        out.put("proofOfDelivery", SqlRows.one(jdbc.sql("SELECT \"receiverName\", \"receivedAt\", notes FROM"
                + " \"FleetProofOfDelivery\" WHERE \"loadId\" = ? AND \"organizationId\" = ?").params(id, org())));
        out.put("position", ON_THE_ROAD.contains(out.get("status")) && vehicleId != null
                ? position((String) vehicleId, (Instant) out.get("dispatchedAt")) : null);
        return out;
    }

    private Map<String, Object> position(String vehicleId, Instant since) {
        return SqlRows.one(jdbc.sql("""
                SELECT "recordedAt", latitude, longitude, "speedKmh" FROM "FleetPosition"
                WHERE "organizationId" = ? AND "vehicleId" = ? AND "recordedAt" >= ?::timestamp
                ORDER BY "recordedAt" DESC LIMIT 1""").params(org(), vehicleId, SqlRows.ts(since == null
                ? Instant.now().minusSeconds(86_400) : since)));
    }

    // ------------------------------------------------------------------ invoices

    /** My issued invoices, manual and POS, newest first; {@code open} = with a balance to pay. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> invoices(Boolean open, Integer page, Integer pageSize) {
        String partnerId = partner();
        int p = page(page);
        int size = size(pageSize);
        String balance = Boolean.TRUE.equals(open) ? " AND \"amountResidual\" > 0" : "";
        String union = """
                SELECT id, "documentNumber" AS number, "issueDate", "dueDate", "totalAmount", "amountPaid",
                       "amountResidual", status::text AS status
                FROM "Document" WHERE "organizationId" = ? AND "partnerId" = ?
                  AND "documentType"::text = 'sales_invoice' AND status::text IN ('posted', 'paid')""" + balance + """

                UNION ALL
                SELECT id, "invoiceNumber" AS number, "issueDate", "dueDate", "totalAmount", "amountPaid",
                       "amountResidual", status::text AS status
                FROM "Invoice" WHERE "organizationId" = ? AND "partnerId" = ?
                  AND status::text IN ('posted', 'paid')""" + balance;
        long total = jdbc.sql("SELECT count(*) FROM (" + union + ") x").params(org(), partnerId, org(), partnerId)
                .query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM (" + union + ") x ORDER BY"
                + " \"issueDate\" DESC, id LIMIT ? OFFSET ?").params(org(), partnerId, org(), partnerId, size,
                (p - 1) * size));
        return PageResult.of(rows, p, size, total);
    }

    /** One of my issued invoices with its lines. */
    @Transactional(readOnly = true)
    public Map<String, Object> invoice(String id) {
        Map<String, Object> doc = mine(id);
        List<Map<String, Object>> lines = new ArrayList<>();
        if (doc.get("lines") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> l) {
                    lines.add(Obj.of("description", l.get("description"), "quantity", l.get("quantity"), "unitPrice",
                            l.get("unitPrice"), "taxAmount", l.get("taxAmount"), "total", l.get("total")));
                }
            }
        }
        return Obj.of("id", doc.get("id"), "number", doc.get("documentNumber"), "status", doc.get("status"),
                "issueDate", doc.get("issueDate"), "dueDate", doc.get("dueDate"), "subtotal", doc.get("subtotal"),
                "discountTotal", doc.get("discountTotal"), "taxAmount", doc.get("taxAmount"),
                "totalAmount", doc.get("totalAmount"), "amountPaid", doc.get("amountPaid"),
                "amountResidual", doc.get("amountResidual"), "lines", lines);
    }

    /** PDF of one of my issued invoices. */
    @Transactional(readOnly = true)
    public Export invoicePdf(String id) {
        mine(id);
        return invoicePdf.pdf(id);
    }

    private Map<String, Object> mine(String id) {
        String partnerId = partner();
        Map<String, Object> doc = invoices.findOne(id);
        if (doc == null || !partnerId.equals(doc.get("partnerId"))
                || !List.of("posted", "paid").contains(String.valueOf(doc.get("status")))) {
            throw ApiException.notFound("Invoice not found");
        }
        return doc;
    }

    // ------------------------------------------------------------------ statement

    /** My statement of account (posted GL lines, running balance, aging). */
    @Transactional(readOnly = true)
    public Map<String, Object> statement(String from, String to) {
        return statements.statement("ar", partner(), from, to);
    }

    /** PDF of my statement. */
    @Transactional(readOnly = true)
    public byte[] statementPdf(String from, String to) {
        return statements.pdf("ar", partner(), from, to);
    }
}
