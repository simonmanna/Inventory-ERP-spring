package com.inventoryerp.gatepass.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-only view of the documents a gate pass can be issued for. For each source it reads the header (number, branch,
 * customer / supplier, whether it was cancelled, whether the goods were issued from stock, payment), the transport
 * details to prefill (driver, phone, licence, vehicle, transporter, destination) and the item lines with their
 * quantities. Every query runs on the source tables directly, with the organization filter.
 */
@Component
public class GatePassSources {

    /** Every source type, in the order the register shows them. */
    public static final List<String> TYPES = List.of("delivery", "sales_order", "invoice", "cash_sale", "stock_transfer",
            "pick_order", "rental", "repair", "supplier_return");

    static final Map<String, String> LABELS = Map.of("delivery", "Delivery", "sales_order", "Sales order", "invoice",
            "Invoice", "cash_sale", "Cash sale", "stock_transfer", "Stock transfer", "pick_order", "Pick order", "rental",
            "Rental agreement", "repair", "Repair order", "supplier_return", "Supplier return");

    /** One item line of the source with the quantity the pass may carry at most. */
    public record Line(String id, String productId, String variantId, String description, BigDecimal quantity,
            String unit) {}

    /** The source document as the gate pass sees it. */
    public record Source(String type, String id, String number, String branchId, String partnerId, String partnerName,
            String status, boolean cancelled, boolean goodsIssued, String paymentState, Map<String, Object> prefill,
            List<Line> lines, String rootType, String rootId) {

        public String label() {
            return LABELS.get(type);
        }

        /** Whether this source is its own root document (no order / cash sale above it). */
        public boolean isRoot() {
            return type.equals(rootType) && id.equals(rootId);
        }
    }

    private static final String UNIT = "(SELECT coalesce(u.symbol, u.code, u.name) FROM \"UnitOfMeasure\" u WHERE u.id = %s)";
    private static final String PRODUCT = "(SELECT pr.name FROM \"Product\" pr WHERE pr.id = %s)";
    private static final String PARTNER = "(SELECT pa.name FROM \"Partner\" pa WHERE pa.id = %s)";
    private static final String LOC_BRANCH = "(SELECT il.\"branchId\" FROM \"InventoryLocation\" il WHERE il.id = %s)";
    private static final String LOC_NAME = "(SELECT il.name FROM \"InventoryLocation\" il WHERE il.id = %s)";

    private final JdbcClient jdbc;

    public GatePassSources(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The source, or 404 {@code "<Label> not found"}. Unknown type → 400. */
    public Source load(String type, String id) {
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("sourceType must be one of: " + String.join(", ", TYPES));
        }
        String org = TenantContext.organizationId();
        Map<String, Object> h = header(type, id, org);
        // The lines query always runs so a 404 still proves every source query against the schema.
        List<Line> lines = lines(type, id, org);
        if (h == null) {
            throw ApiException.notFound(LABELS.get(type) + " not found");
        }
        String payment = payment(type, id, h, org);
        Map<String, Object> prefill = new LinkedHashMap<>();
        for (String k : List.of("bearerName", "bearerPhone", "bearerIdNumber", "vehiclePlate", "vehicleType",
                "transportCompany", "destination")) {
            Object v = h.get(k);
            prefill.put(k, v == null || v.toString().isBlank() ? null : v.toString().trim());
        }
        prefill.put("bearerType", prefill.get("vehiclePlate") != null ? "driver" : "person");
        String[] root = root(type, id, h, org);
        return new Source(type, id, s(h.get("number")), s(h.get("branchId")), s(h.get("partnerId")),
                s(h.get("partnerName")), s(h.get("status")), Boolean.TRUE.equals(h.get("cancelled")),
                Boolean.TRUE.equals(h.get("goodsIssued")), payment, prefill, lines, root[0], root[1]);
    }

    /**
     * The document the goods of a source belong to, so passes on related documents share one cap: an invoice raised from
     * a cash sale or a sales order belongs to that sale / order, and a delivery to its sales order (or to the invoice it
     * delivers, and through it to the invoice's own root). Every other source is its own root.
     */
    private String[] root(String type, String id, Map<String, Object> h, String org) {
        if ("invoice".equals(type)) {
            return invoiceRoot(id, s(h.get("docSourceType")), s(h.get("docSourceId")));
        }
        if ("delivery".equals(type)) {
            if ("sales_order".equals(h.get("orderType")) && h.get("orderId") != null) {
                return new String[] {"sales_order", s(h.get("orderId"))};
            }
            if ("invoice".equals(h.get("orderType")) && h.get("orderId") != null) {
                List<Map<String, Object>> d = SqlRows.list(jdbc.sql("SELECT \"sourceType\", \"sourceId\" FROM \"Document\""
                        + " WHERE id = ? AND \"organizationId\" = ?").params(h.get("orderId"), org));
                return d.isEmpty() ? new String[] {"invoice", s(h.get("orderId"))}
                        : invoiceRoot(s(h.get("orderId")), s(d.get(0).get("sourceType")), s(d.get(0).get("sourceId")));
            }
        }
        return new String[] {type, id};
    }

    private static String[] invoiceRoot(String invoiceId, String docSourceType, String docSourceId) {
        if (docSourceId != null && ("cash_sale".equals(docSourceType) || "sales_order".equals(docSourceType))) {
            return new String[] {docSourceType, docSourceId};
        }
        return new String[] {"invoice", invoiceId};
    }

    private Map<String, Object> header(String type, String id, String org) {
        String sql = switch (type) {
            case "delivery" -> """
                    SELECT l.code AS number, coalesce(d."branchId", %s) AS "branchId", d."partnerId", pa.name AS "partnerName",
                           l.status, l.status = 'cancelled' AS cancelled, l."stockIssuedAt" IS NOT NULL AS "goodsIssued",
                           d."sourceType" AS "orderType", d."sourceId" AS "orderId",
                           coalesce(nullif(trim(e."firstName" || ' ' || coalesce(e."lastName", '')), ''),
                                    l."transporterDriverName") AS "bearerName",
                           coalesce(l."driverPhone", e.phone) AS "bearerPhone", dr."licenceNumber" AS "bearerIdNumber",
                           coalesce(v."registrationPlate", l."transporterPlate") AS "vehiclePlate", l."vehicleType",
                           coalesce(l."transportCompany", %s) AS "transportCompany",
                           coalesce(l."deliveryAddress", %s) AS destination
                    FROM "FleetLoad" l
                    LEFT JOIN "FleetDeliveryOrder" d ON d.id = l."deliveryOrderId"
                    LEFT JOIN "Partner" pa ON pa.id = d."partnerId"
                    LEFT JOIN "FleetVehicle" v ON v.id = l."vehicleId"
                    LEFT JOIN "FleetDriver" dr ON dr.id = l."driverId"
                    LEFT JOIN "HrEmployee" e ON e.id = dr."employeeId"
                    WHERE l.id = ? AND l."organizationId" = ?
                    """.formatted(LOC_BRANCH.formatted("l.\"locationId\""), PARTNER.formatted("l.\"transporterPartnerId\""),
                    LOC_NAME.formatted("l.\"destinationLocationId\""));
            case "sales_order" -> """
                    SELECT o.number, o."branchId", o."partnerId", pa.name AS "partnerName", o.status,
                           o.status = 'cancelled' AS cancelled, o."deliveryStatus" = 'full' AS "goodsIssued",
                           o.total, o."invoiceStatus", h.driver AS "bearerName", h."contactPhone" AS "bearerPhone",
                           h.vehicle AS "vehiclePlate", h."courierName" AS "transportCompany",
                           coalesce(h.address, o."deliveryAddress", %s) AS destination
                    FROM "SalesOrder" o
                    LEFT JOIN "Partner" pa ON pa.id = o."partnerId"
                    LEFT JOIN "SalesHandover" h ON h."organizationId" = o."organizationId" AND h."sourceType" = 'sales_order'
                                               AND h."sourceId" = o.id
                    WHERE o.id = ? AND o."organizationId" = ? AND o."deletedAt" IS NULL
                    """.formatted(LOC_NAME.formatted("h.\"destinationLocationId\""));
            case "invoice" -> """
                    SELECT d."documentNumber" AS number, d."branchId", d."partnerId", pa.name AS "partnerName",
                           d.status::text AS status, d.status::text IN ('cancelled', 'rejected') AS cancelled,
                           d.status::text IN ('posted', 'paid') AS "goodsIssued", d."paymentStatus"::text AS "paymentStatus",
                           d."sourceType" AS "docSourceType", d."sourceId" AS "docSourceId",
                           coalesce(h.driver, ss."deliveryDriver") AS "bearerName",
                           coalesce(h."contactPhone", ss."deliveryContactPhone") AS "bearerPhone",
                           coalesce(h.vehicle, ss."deliveryVehicle") AS "vehiclePlate", h."courierName" AS "transportCompany",
                           coalesce(h.address, d."deliveryAddress", ss."deliveryAddress") AS destination
                    FROM "Document" d
                    LEFT JOIN "Partner" pa ON pa.id = d."partnerId"
                    LEFT JOIN "SalesHandover" h ON h."organizationId" = d."organizationId" AND h."sourceType" = 'invoice'
                                               AND h."sourceId" = d.id
                    LEFT JOIN "SalesSubmission" ss ON d."sourceType" = 'cash_sale' AND ss.id = d."sourceId"
                    WHERE d.id = ? AND d."organizationId" = ? AND d."documentType"::text = 'sales_invoice'
                    """;
            case "cash_sale" -> """
                    SELECT s.number, s."branchId", s."partnerId", coalesce(pa.name, s."customerName") AS "partnerName",
                           s."executionStatus" AS status, s."executionStatus" IN ('voided', 'cancelled') AS cancelled,
                           s."executionStatus" = 'confirmed' AS "goodsIssued", s."grandTotal", s."paidTotal",
                           s."deliveryDriver" AS "bearerName", s."deliveryContactPhone" AS "bearerPhone",
                           s."deliveryVehicle" AS "vehiclePlate",
                           coalesce(s."deliveryAddress", s."customerAddress") AS destination
                    FROM "SalesSubmission" s
                    LEFT JOIN "Partner" pa ON pa.id = s."partnerId"
                    WHERE s.id = ? AND s."organizationId" = ?
                    """;
            case "stock_transfer" -> """
                    SELECT t."transferCode" AS number, %s AS "branchId", NULL::text AS "partnerId",
                           NULL::text AS "partnerName", t.status::text AS status,
                           t.status::text IN ('cancelled', 'rejected', 'reversed') AS cancelled,
                           (t."dispatchedAt" IS NOT NULL OR t.status::text IN ('completed', 'in_transit', 'partially_received'))
                               AS "goodsIssued",
                           %s AS destination
                    FROM "StockTransfer" t
                    WHERE t.id = ? AND t."organizationId" = ?
                    """.formatted(LOC_BRANCH.formatted("t.\"fromLocId\""), LOC_NAME.formatted("t.\"toLocId\""));
            case "pick_order" -> """
                    SELECT w.code AS number, %s AS "branchId", NULL::text AS "partnerId", w."sourceRef" AS "partnerName",
                           w.status, w.status = 'cancelled' AS cancelled, w.status IN ('packed', 'shipped') AS "goodsIssued",
                           w.carrier AS "transportCompany"
                    FROM "WarehousePickOrder" w
                    WHERE w.id = ? AND w."organizationId" = ?
                    """.formatted(LOC_BRANCH.formatted("w.\"locationId\""));
            case "rental" -> """
                    SELECT r."agreementNumber" AS number, r."branchId", r."partnerId", pa.name AS "partnerName",
                           r.status::text AS status, r.status::text = 'cancelled' AS cancelled,
                           r.status::text IN ('checked_out', 'partially_returned', 'returned', 'closed') AS "goodsIssued",
                           r."invoiceId"
                    FROM "RentalAgreement" r
                    LEFT JOIN "Partner" pa ON pa.id = r."partnerId"
                    WHERE r.id = ? AND r."organizationId" = ?
                    """;
            case "repair" -> """
                    SELECT r."repairNumber" AS number, r."branchId", r."partnerId", pa.name AS "partnerName",
                           r.status::text AS status, r.status::text = 'cancelled' AS cancelled,
                           r.status::text IN ('ready_pickup', 'delivered', 'closed') AS "goodsIssued", r."invoiceId",
                           r."plateNumber" AS "vehiclePlate"
                    FROM "RepairOrder" r
                    LEFT JOIN "Partner" pa ON pa.id = r."partnerId"
                    WHERE r.id = ? AND r."organizationId" = ?
                    """;
            case "supplier_return" -> """
                    SELECT n."noteNumber" AS number, %s AS "branchId", n."partnerId", pa.name AS "partnerName",
                           n.status::text AS status, n.status::text IN ('cancelled', 'reversed') AS cancelled,
                           n.status::text = 'posted' AS "goodsIssued", pa.name AS destination
                    FROM "DebitNote" n
                    LEFT JOIN "Partner" pa ON pa.id = n."partnerId"
                    WHERE n.id = ? AND n."organizationId" = ? AND n."deletedAt" IS NULL
                      AND n.direction::text = 'inbound' AND n.reason::text = 'returned_goods'
                    """.formatted(LOC_BRANCH.formatted("n.\"customFields\"->>'locationId'"));
            default -> throw new IllegalStateException(type);
        };
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(sql).params(id, org));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Line> lines(String type, String id, String org) {
        String sql = switch (type) {
            case "delivery" -> """
                    SELECT ll.id, ll."productId", dl."variantId", coalesce(dl.description, %s) AS description,
                           CASE WHEN ll."dispatchedQty" > 0 THEN ll."dispatchedQty" WHEN ll."loadedQty" > 0 THEN ll."loadedQty"
                                ELSE ll."plannedQty" END AS quantity, %s AS unit
                    FROM "FleetLoadLine" ll LEFT JOIN "FleetDeliveryOrderLine" dl ON dl.id = ll."deliveryOrderLineId"
                    WHERE ll."loadId" = ? AND ll."organizationId" = ? ORDER BY dl."lineNumber", ll."createdAt"
                    """.formatted(PRODUCT.formatted("ll.\"productId\""), UNIT.formatted("ll.\"uomId\""));
            case "sales_order" -> """
                    SELECT l.id, l."productId", l."variantId", coalesce(l.description, %s) AS description,
                           l.quantity - coalesce(l."qtyCancelled", 0) AS quantity, %s AS unit
                    FROM "SalesOrderLine" l WHERE l."orderId" = ? AND l."organizationId" = ? AND l."productId" IS NOT NULL
                    ORDER BY l."sortOrder", l.id
                    """.formatted(PRODUCT.formatted("l.\"productId\""), UNIT.formatted("l.\"uomId\""));
            case "invoice" -> """
                    SELECT l.id, l."productId", l."variantId", coalesce(l.description, %s) AS description, l.quantity,
                           %s AS unit
                    FROM "DocumentLine" l WHERE l."documentId" = ? AND l."organizationId" = ? AND l."productId" IS NOT NULL
                      AND coalesce(l."lineType"::text, 'product') = 'product'
                    ORDER BY l."lineNumber", l.id
                    """.formatted(PRODUCT.formatted("l.\"productId\""), UNIT.formatted("l.\"uomId\""));
            case "cash_sale" -> """
                    SELECT l.id, l."productId", l."variantId", coalesce(l.description, %s) AS description, l.quantity,
                           %s AS unit
                    FROM "SalesSubmissionLine" l WHERE l."submissionId" = ? AND l."organizationId" = ?
                      AND l."productId" IS NOT NULL
                    ORDER BY l."sortOrder", l.id
                    """.formatted(PRODUCT.formatted("l.\"productId\""), UNIT.formatted("l.\"uomId\""));
            case "stock_transfer" -> """
                    SELECT i.id, i."productId", i."variantId", coalesce(i."productName", %s) AS description,
                           CASE WHEN i."qtyDispatched" > 0 THEN i."qtyDispatched" ELSE i."qtyRequested" END AS quantity,
                           i.unit
                    FROM "StockTransferItem" i WHERE i."transferId" = ? AND i."organizationId" = ? ORDER BY i.id
                    """.formatted(PRODUCT.formatted("i.\"productId\""));
            case "pick_order" -> """
                    SELECT pl.id, pl."productId", NULL::text AS "variantId", %s AS description,
                           CASE WHEN pl."shippedQty" > 0 THEN pl."shippedQty" WHEN pl."packedQty" > 0 THEN pl."packedQty"
                                WHEN pl."pickedQty" > 0 THEN pl."pickedQty" ELSE pl.quantity END AS quantity,
                           (SELECT coalesce(u.symbol, u.code, u.name) FROM "Product" pr JOIN "UnitOfMeasure" u
                              ON u.id = pr."uomId" WHERE pr.id = pl."productId") AS unit
                    FROM "WarehousePickLine" pl WHERE pl."pickOrderId" = ? AND pl."organizationId" = ? ORDER BY pl.id
                    """.formatted(PRODUCT.formatted("pl.\"productId\""));
            case "rental" -> """
                    SELECT l.id, l."productId", NULL::text AS "variantId",
                           concat_ws(' · ', %s, (SELECT ru."unitCode" FROM "RentalUnit" ru WHERE ru.id = l."unitId"))
                               AS description, l.quantity, NULL::text AS unit
                    FROM "RentalAgreementLine" l WHERE l."agreementId" = ? AND l."organizationId" = ? ORDER BY l.id
                    """.formatted(PRODUCT.formatted("l.\"productId\""));
            case "repair" -> """
                    SELECT i.id, NULL::text AS "productId", NULL::text AS "variantId",
                           concat_ws(' · ', nullif(concat_ws(' ', i."itemType", i.brand, i.model), ''),
                                     'S/N ' || i."serialNumber", 'IMEI ' || i.imei, 'Plate ' || i."plateNumber",
                                     'VIN ' || i.vin) AS description,
                           1::numeric AS quantity, NULL::text AS unit
                    FROM "RepairOrderItem" i WHERE i."repairOrderId" = ? AND i."organizationId" = ?
                    UNION ALL
                    SELECT r.id, NULL, NULL,
                           concat_ws(' · ', nullif(concat_ws(' ', r."itemType", r.brand, r.model), ''),
                                     'S/N ' || r."serialNumber", 'IMEI ' || r.imei, 'Plate ' || r."plateNumber",
                                     'VIN ' || r.vin), 1::numeric, NULL
                    FROM "RepairOrder" r WHERE r.id = ? AND r."organizationId" = ?
                      AND NOT EXISTS (SELECT 1 FROM "RepairOrderItem" x WHERE x."repairOrderId" = r.id)
                    """;
            case "supplier_return" -> """
                    SELECT l.id, l."productId", l."variantId", coalesce(l.description, %s) AS description, l.quantity,
                           %s AS unit
                    FROM "DebitNoteLine" l WHERE l."debitNoteId" = ? AND l."productId" IS NOT NULL
                      AND EXISTS (SELECT 1 FROM "DebitNote" n WHERE n.id = l."debitNoteId" AND n."organizationId" = ?)
                    ORDER BY l."lineNumber", l.id
                    """.formatted(PRODUCT.formatted("l.\"productId\""), UNIT.formatted("l.\"uomId\""));
            default -> throw new IllegalStateException(type);
        };
        Object[] params = "repair".equals(type) ? new Object[] {id, org, id, org} : new Object[] {id, org};
        List<Line> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql).params(Arrays.asList(params)))) {
            BigDecimal q = r.get("quantity") instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(r.get("quantity")));
            if (q.signum() > 0) {
                String d = s(r.get("description"));
                out.add(new Line(s(r.get("id")), s(r.get("productId")), s(r.get("variantId")),
                        d == null || d.isBlank() ? "Item" : d, q, s(r.get("unit"))));
            }
        }
        return out;
    }

    private static final Set<String> PAID = Set.of("paid", "overpaid");

    /** paid / partial / unpaid where the source carries money, else not_applicable. */
    private String payment(String type, String id, Map<String, Object> h, String org) {
        return switch (type) {
            case "invoice" -> documentPayment(s(h.get("paymentStatus")));
            case "cash_sale" -> amounts(dec(h.get("grandTotal")), dec(h.get("paidTotal")));
            case "sales_order" -> orderPayment(id, h, org);
            case "delivery" -> "sales_order".equals(h.get("orderType")) ? orderPayment(s(h.get("orderId")), null, org)
                    : "invoice".equals(h.get("orderType")) ? invoicePayment(s(h.get("orderId")), org) : "not_applicable";
            case "rental", "repair" -> invoicePayment(s(h.get("invoiceId")), org);
            default -> "not_applicable";
        };
    }

    private String invoicePayment(String invoiceId, String org) {
        if (invoiceId == null) {
            return "unpaid";
        }
        for (String table : List.of("Document", "Invoice")) {
            List<String> st = jdbc.sql("SELECT \"paymentStatus\"::text FROM \"" + table + "\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(invoiceId, org).query(String.class).list();
            if (!st.isEmpty()) {
                return documentPayment(st.get(0));
            }
        }
        return "unpaid";
    }

    private static String documentPayment(String status) {
        return status == null ? "unpaid" : PAID.contains(status) ? "paid" : "partial".equals(status) ? "partial" : "unpaid";
    }

    /** Order: paid once fully invoiced with nothing left to pay; partial when invoices or deposits brought money. */
    private String orderPayment(String orderId, Map<String, Object> order, String org) {
        if (orderId == null && order == null) {
            return "not_applicable";
        }
        Map<String, Object> o = order;
        if (o == null || o.get("total") == null) {
            List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT total, \"invoiceStatus\" FROM \"SalesOrder\""
                    + " WHERE id = ? AND \"organizationId\" = ?").params(orderId, org));
            if (rows.isEmpty()) {
                return "not_applicable";
            }
            o = rows.get(0);
        }
        Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT count(*) AS n, coalesce(sum(\"amountPaid\"), 0) AS paid,"
                + " coalesce(sum(\"amountResidual\"), 0) AS residual FROM \"Document\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'sales_order' AND \"sourceId\" = ? AND status::text NOT IN ('draft', 'cancelled',"
                + " 'rejected')").params(org, orderId));
        BigDecimal deposits = jdbc.sql("SELECT coalesce(sum(amount - \"refundedAmount\"), 0) FROM \"CustomerDeposit\""
                + " WHERE \"organizationId\" = ? AND \"salesOrderId\" = ? AND status <> 'void'").params(org, orderId)
                .query(BigDecimal.class).single();
        long n = ((Number) inv.get("n")).longValue();
        BigDecimal paid = dec(inv.get("paid")).add(deposits);
        if (n > 0 && "invoiced".equals(o.get("invoiceStatus")) && dec(inv.get("residual")).signum() <= 0) {
            return "paid";
        }
        if (n == 0 && paid.compareTo(dec(o.get("total"))) >= 0 && dec(o.get("total")).signum() > 0) {
            return "paid";
        }
        return paid.signum() > 0 ? "partial" : "unpaid";
    }

    private static String amounts(BigDecimal total, BigDecimal paid) {
        if (total.signum() <= 0 || paid.compareTo(total) >= 0) {
            return "paid";
        }
        return paid.signum() > 0 ? "partial" : "unpaid";
    }

    static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    static String s(Object v) {
        return v == null ? null : v.toString();
    }
}
