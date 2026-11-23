package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Shared reads and the bin sub-ledger writes of warehouse management (roadmap 2.1, ADR-S026). Every quantity inside a
 * location splits into binned ({@code WarehouseBinStock}), staged (picked, not shipped) and unbinned (the rest of the
 * location's {@code StockItem} quantity). Callers run inside their own transaction.
 *
 * <p>Sourced pick orders (h-01 F2, readiness gaps 2026-11-17): the document behind the order (sale, delivery,
 * stock-out, transfer) takes the goods out of the ledger, the pick order moves them out of the bins. The document may
 * post before the pick: until the order ships, the goods it already issued are still physically in the bins or
 * staging, so the location holds its ledger quantity plus those ({@link #physicalQty}). The other order, shipping
 * before the document posts, is refused ({@link #documentShortfall}), so goods never leave the bins while the ledger
 * still counts them.
 */
@Component
public class WarehouseStore {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Per-location settings; defaults when the location has no row yet. */
    public record Settings(String locationId, boolean enabled, int outboundSteps, BigDecimal abcAPercent,
            BigDecimal abcBPercent, int abcLookbackDays, int countDaysA, int countDaysB, int countDaysC,
            boolean stored) {

        int countDays(char abcClass) {
            return abcClass == 'A' ? countDaysA : abcClass == 'B' ? countDaysB : countDaysC;
        }
    }

    private final JdbcClient jdbc;
    private final InventoryLookups lookups;
    private final LocationScope locationScope;
    private final SequenceService seq;

    public WarehouseStore(JdbcClient jdbc, InventoryLookups lookups, LocationScope locationScope,
            SequenceService seq) {
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.locationScope = locationScope;
        this.seq = seq;
    }

    /** One task row to insert. */
    record NewTask(String locationId, String type, String productId, String variantKey, String batchKey,
            BigDecimal quantity, String fromBinId, String toBinId, String pickOrderId, String pickLineId,
            String waveId, int sequence) {}

    String nextCode(String key, String prefix) {
        return seq.next(key, prefix, 6);
    }

    /** Insert an open task and return its id. */
    String insertTask(NewTask t) {
        String id = id();
        jdbc.sql("INSERT INTO \"WarehouseTask\" (id, \"organizationId\", \"locationId\", code, type, status,"
                        + " \"productId\", \"variantKey\", \"batchKey\", quantity, \"fromBinId\", \"toBinId\","
                        + " \"pickOrderId\", \"pickLineId\", \"waveId\", sequence, \"createdBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'open', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", "
                        + NOW + ")")
                .params(id, org(), t.locationId(), nextCode("wms_task", "WT-"), t.type(), t.productId(),
                        key(t.variantKey()), key(t.batchKey()), t.quantity(), t.fromBinId(), t.toBinId(),
                        t.pickOrderId(), t.pickLineId(), t.waveId(), t.sequence(), user())
                .update();
        return id;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    static String org() {
        return TenantContext.organizationId();
    }

    static String user() {
        return TenantContext.userId();
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static String key(String value) {
        return value == null ? "" : value;
    }

    /** The location (404 {@code Location not found}), checked against the caller's branch scope. */
    Map<String, Object> location(String locationId) {
        if (locationId == null || locationId.isBlank()) {
            throw ApiException.badRequest("locationId is required");
        }
        Map<String, Object> loc = lookups.requireLocation(locationId);
        locationScope.assertAllowed(locationId);
        return loc;
    }

    /**
     * {@link #location} for warehouse work (operate / manage routes): refused while the location's warehouse settings
     * are stored with {@code enabled = false}. A location that never saved settings keeps working as before.
     */
    Map<String, Object> operable(String locationId) {
        Map<String, Object> loc = location(locationId);
        Settings s = settings(locationId);
        if (s.stored() && !s.enabled()) {
            throw ApiException.badRequest("Warehouse management is switched off for this location; turn it on in the"
                    + " warehouse settings first");
        }
        return loc;
    }

    /**
     * Hardening R5a: {@link #location} for finishing work that already exists (complete or cancel a task, pack, ship
     * or cancel a pick order). Switching warehouse management off blocks new work only ({@link #operable}), so open
     * orders and tasks can still be finished or cleared afterwards.
     */
    Map<String, Object> finishable(String locationId) {
        return location(locationId);
    }

    Map<String, Object> product(String productId) {
        Map<String, Object> p = lookups.product(productId);
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }

    String productName(String productId) {
        Map<String, Object> p = lookups.product(productId);
        return p == null ? productId : SqlRows.str(p, "name");
    }

    Settings settings(String locationId) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"WarehouseSettings\" WHERE \"locationId\" = ?"
                + " AND \"organizationId\" = ?").params(locationId, org()));
        if (row == null) {
            return new Settings(locationId, false, 1, BigDecimal.valueOf(80), BigDecimal.valueOf(95), 90, 30, 90, 180,
                    false);
        }
        return new Settings(locationId, Boolean.TRUE.equals(row.get("enabled")), ((Number) row.get("outboundSteps"))
                .intValue(), SqlRows.dec(row, "abcAPercent"), SqlRows.dec(row, "abcBPercent"),
                ((Number) row.get("abcLookbackDays")).intValue(), ((Number) row.get("countDaysA")).intValue(),
                ((Number) row.get("countDaysB")).intValue(), ((Number) row.get("countDaysC")).intValue(), true);
    }

    /** A bin, zone, aisle or rack of the organization (404 {@code Bin not found}). */
    Map<String, Object> bin(String binId) {
        Map<String, Object> bin = binId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"WarehouseBin\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(binId, org()));
        if (bin == null) {
            throw ApiException.notFound("Bin not found");
        }
        return bin;
    }

    /** An active bin of kind {@code bin} inside {@code locationId}: the only kind that holds stock. */
    Map<String, Object> stockBin(String binId, String locationId) {
        Map<String, Object> bin = bin(binId);
        if (!locationId.equals(bin.get("locationId"))) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " is not in this location");
        }
        if (!"bin".equals(bin.get("kind"))) {
            throw ApiException.badRequest("Only bins hold stock; " + bin.get("code") + " is a " + bin.get("kind"));
        }
        if (!Boolean.TRUE.equals(bin.get("isActive"))) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " is inactive");
        }
        return bin;
    }

    /** Serialise warehouse work on one product in one location (putaway availability, picks). */
    void lock(String locationId, String productId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("wms:" + locationId + ":" + productId)
                .query().singleRow();
    }

    BigDecimal locationQty(String locationId, String productId, String variantKey) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?")
                .params(org(), locationId, productId, variantKey).query(BigDecimal.class).single();
    }

    BigDecimal binnedQty(String locationId, String productId, String variantKey) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?")
                .params(org(), locationId, productId, variantKey).query(BigDecimal.class).single();
    }

    /**
     * Picked and not yet shipped: out of the bins, still in the location's ledger quantity. Only orders still in
     * progress can hold staged stock (shipping sets shipped = picked), so the location's shipped history is not read.
     */
    BigDecimal stagedQty(String locationId, String productId, String variantKey) {
        return jdbc.sql("SELECT coalesce(sum(l.\"pickedQty\" - l.\"shippedQty\"), 0) FROM \"WarehousePickLine\" l"
                + " JOIN \"WarehousePickOrder\" o ON o.id = l.\"pickOrderId\" WHERE o.\"organizationId\" = ?"
                + " AND o.\"locationId\" = ? AND o.status IN " + IN_PROGRESS + " AND l.\"productId\" = ?"
                + " AND l.\"variantKey\" = ?")
                .params(org(), locationId, productId, variantKey).query(BigDecimal.class).single();
    }

    /** Pick order statuses between release and ship: the only ones whose lines can hold staged stock. */
    static final String IN_PROGRESS = "('released', 'picking', 'picked', 'packed')";

    /** Pick order statuses before ship: draft included. */
    static final String OPEN = "('draft', 'released', 'picking', 'picked', 'packed')";

    // ---------------------------------------------------------------- sourced pick orders (h-01 F2)

    /**
     * Ledger references (referenceType, referenceId) under which each source document of a {@code docs(st, ref, sid)}
     * CTE issues its stock, then {@code issued(st, ref, pid, vk, qty)}: the quantity the document has taken out of
     * stock at {@code :loc}, net of what its cancellations and trip returns put back. A sales order issues through its
     * invoices or through the trips of its delivery orders; a delivery order through its trips (or its goods invoice);
     * a stock-out and a transfer through their own postings. Parameters: {@code :org}, {@code :loc}.
     */
    private static final String DOC_ISSUES = """
            refs AS (
              SELECT d.st, d.ref, 'stock_out'::text AS rt, x."outCode" AS rid
                FROM docs d JOIN "StockOut" x ON x."organizationId" = :org AND (x.id = d.sid OR x."outCode" = d.ref)
               WHERE d.st = 'stock_out'
              UNION ALL
              SELECT d.st, d.ref, r.rt, x."transferCode"
                FROM docs d JOIN "StockTransfer" x ON x."organizationId" = :org
                 AND (x.id = d.sid OR x."transferCode" = d.ref)
               CROSS JOIN (VALUES ('stock_transfer'), ('stock_transfer_dispatch'), ('stock_transfer_recall')) r(rt)
               WHERE d.st = 'transfer'
              UNION ALL
              SELECT d.st, d.ref, r.rt, fl.id
                FROM docs d JOIN "FleetDeliveryOrder" f ON f."organizationId" = :org AND (f.id = d.sid OR f.code = d.ref)
                JOIN "FleetLoad" fl ON fl."deliveryOrderId" = f.id AND fl."organizationId" = :org
               CROSS JOIN (VALUES ('fleet_trip'), ('fleet_trip_return')) r(rt)
               WHERE d.st = 'delivery_order'
              UNION ALL
              SELECT d.st, d.ref, r.rt, f."goodsInvoiceId"
                FROM docs d JOIN "FleetDeliveryOrder" f ON f."organizationId" = :org AND (f.id = d.sid OR f.code = d.ref)
               CROSS JOIN (VALUES ('sales_invoice'), ('sales_invoice_cancel')) r(rt)
               WHERE d.st = 'delivery_order' AND f."goodsInvoiceId" IS NOT NULL
              UNION ALL
              SELECT d.st, d.ref, r.rt, fl.id
                FROM docs d JOIN "SalesOrder" so ON so."organizationId" = :org AND (so.id = d.sid OR so.number = d.ref)
                JOIN "FleetDeliveryOrder" f ON f."organizationId" = :org AND f."sourceType" = 'sales_order'
                 AND f."sourceId" = so.id
                JOIN "FleetLoad" fl ON fl."deliveryOrderId" = f.id AND fl."organizationId" = :org
               CROSS JOIN (VALUES ('fleet_trip'), ('fleet_trip_return')) r(rt)
               WHERE d.st = 'sales_order'
              UNION ALL
              SELECT d.st, d.ref, r.rt, doc.id
                FROM docs d JOIN "SalesOrder" so ON so."organizationId" = :org AND (so.id = d.sid OR so.number = d.ref)
                JOIN "Document" doc ON doc."organizationId" = :org AND doc."sourceType" = 'sales_order'
                 AND doc."sourceId" = so.id
               CROSS JOIN (VALUES ('sales_invoice'), ('sales_invoice_cancel')) r(rt)
               WHERE d.st = 'sales_order'
            ),
            issued AS (
              SELECT r.st, r.ref, g."productId" AS pid, COALESCE(g."variantId", '') AS vk, -SUM(g."quantityChange") AS qty
                FROM (SELECT DISTINCT st, ref, rt, rid FROM refs) r
                JOIN "InventoryLedger" g ON g."organizationId" = :org AND g."referenceType" = r.rt
                 AND g."referenceId" = r.rid AND g."locationId" = :loc
               GROUP BY 1, 2, 3, 4
            )""";

    /**
     * What the shipped pick orders of the same documents already used of their documents' issue: shipped
     * quantity per document, product and variant at {@code :loc} ({@code used(st, ref, pid, vk, qty)}).
     */
    private static final String USED = """
            used AS (
              SELECT o."sourceType" AS st, o."sourceRef" AS ref, l."productId" AS pid, l."variantKey" AS vk,
                     SUM(l."shippedQty") AS qty
                FROM (SELECT DISTINCT st, ref FROM docs) d
                JOIN "WarehousePickOrder" o ON o."organizationId" = :org AND o."sourceType" = d.st
                 AND o."sourceRef" = d.ref AND o."locationId" = :loc AND o.status = 'shipped' AND o.id <> :self
                JOIN "WarehousePickLine" l ON l."pickOrderId" = o.id
               GROUP BY 1, 2, 3, 4
            )""";

    /**
     * Goods that sourced pick orders of the location have not shipped yet although their document already took them
     * out of the stock ledger (the document posted first): per {@code productId|variantKey}. They are still in the
     * bins or in staging, so the location physically holds the ledger quantity plus this. Each document counts at most
     * the quantity of its open pick orders, after what its shipped pick orders already used. Pick orders still being
     * picked that are shipped first are not possible: shipping a sourced order waits for its document
     * ({@link #documentShortfall}).
     */
    Map<String, BigDecimal> issuedAwaitingShip(String locationId, String productId, String variantKey) {
        var spec = jdbc.sql("WITH open_lines AS ("
                        + " SELECT o.\"sourceType\" AS st, o.\"sourceRef\" AS ref, max(o.\"sourceId\") AS sid,"
                        + " l.\"productId\" AS pid, l.\"variantKey\" AS vk, SUM(l.quantity) AS q"
                        + " FROM \"WarehousePickOrder\" o JOIN \"WarehousePickLine\" l ON l.\"pickOrderId\" = o.id"
                        + " WHERE o.\"organizationId\" = :org AND o.\"locationId\" = :loc AND o.\"sourceType\" <> 'manual'"
                        + " AND o.status IN " + OPEN + " AND o.\"sourceRef\" IS NOT NULL"
                        + (productId == null ? "" : " AND l.\"productId\" = :prod AND l.\"variantKey\" = :vk")
                        + " GROUP BY 1, 2, 4, 5),"
                        + " docs AS (SELECT st, ref, max(sid) AS sid FROM open_lines GROUP BY 1, 2),"
                        + DOC_ISSUES + "," + USED
                        + " SELECT ol.pid AS \"productId\", ol.vk AS \"variantKey\","
                        + " SUM(LEAST(GREATEST(COALESCE(i.qty, 0) - COALESCE(u.qty, 0), 0), ol.q)) AS qty"
                        + " FROM open_lines ol"
                        + " LEFT JOIN issued i ON i.st = ol.st AND i.ref = ol.ref AND i.pid = ol.pid AND i.vk = ol.vk"
                        + " LEFT JOIN used u ON u.st = ol.st AND u.ref = ol.ref AND u.pid = ol.pid AND u.vk = ol.vk"
                        + " GROUP BY 1, 2")
                .param("org", org()).param("loc", locationId).param("self", "");
        if (productId != null) {
            spec = spec.param("prod", productId).param("vk", variantKey);
        }
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(spec)) {
            BigDecimal qty = SqlRows.dec(r, "qty");
            if (qty.signum() > 0) {
                out.put(r.get("productId") + "|" + r.get("variantKey"), qty);
            }
        }
        return out;
    }

    /** {@link #issuedAwaitingShip(String, String, String)} of one product and variant. */
    BigDecimal issuedAwaitingShipQty(String locationId, String productId, String variantKey) {
        return issuedAwaitingShip(locationId, productId, variantKey).getOrDefault(productId + "|" + variantKey,
                BigDecimal.ZERO);
    }

    /**
     * What the location physically holds by the books: the ledger quantity plus goods a posted document took out of the
     * ledger that are still waiting in the bins or staging to be picked and shipped.
     */
    BigDecimal physicalQty(String locationId, String productId, String variantKey) {
        return locationQty(locationId, productId, variantKey)
                .add(issuedAwaitingShipQty(locationId, productId, variantKey));
    }

    /** The source document of a sourced pick order: its id and its number. */
    record SourceDoc(String id, String number) {}

    private static final Map<String, String> SOURCE_KINDS = Map.of("sales_order", "Sales order", "delivery_order",
            "Delivery order", "stock_out", "Stock-out", "transfer", "Transfer");

    /**
     * h-01 F2: a sourced pick order names a real document, so its shipment can be matched with the document's stock
     * issue. The document is found by its number ({@code sourceRef}) or id ({@code sourceId}). A stock-out must take
     * the stock out of this location, a transfer must leave from it.
     */
    SourceDoc sourceDocument(String sourceType, String sourceId, String sourceRef, String locationId) {
        String kind = SOURCE_KINDS.get(sourceType);
        if (kind == null) {
            throw ApiException.badRequest("sourceType must be one of: manual, sales_order, delivery_order, transfer,"
                    + " stock_out");
        }
        String ref = sourceRef == null || sourceRef.isBlank() ? null : sourceRef.trim();
        String sid = sourceId == null || sourceId.isBlank() ? null : sourceId.trim();
        if (ref == null && sid == null) {
            throw ApiException.badRequest("A pick order for a " + kind.toLowerCase(Locale.ROOT)
                    + " needs the document number (sourceRef)");
        }
        String sql = switch (sourceType) {
            case "sales_order" -> "SELECT id, number, NULL AS loc FROM \"SalesOrder\" WHERE \"organizationId\" = :org"
                    + " AND (id = :sid OR number = :ref)";
            case "delivery_order" -> "SELECT id, code AS number, NULL AS loc FROM \"FleetDeliveryOrder\""
                    + " WHERE \"organizationId\" = :org AND (id = :sid OR code = :ref)";
            case "stock_out" -> "SELECT id, \"outCode\" AS number, \"locationId\" AS loc FROM \"StockOut\""
                    + " WHERE \"organizationId\" = :org AND (id = :sid OR \"outCode\" = :ref)";
            default -> "SELECT id, \"transferCode\" AS number, \"fromLocId\" AS loc FROM \"StockTransfer\""
                    + " WHERE \"organizationId\" = :org AND (id = :sid OR \"transferCode\" = :ref)";
        };
        Map<String, Object> doc = SqlRows.one(jdbc.sql(sql + " ORDER BY (id = :sid) DESC LIMIT 1").param("org", org())
                .param("sid", sid == null ? "" : sid).param("ref", ref == null ? "" : ref));
        if (doc == null) {
            throw ApiException.badRequest(kind + " " + (ref != null ? ref : sid) + " not found; a pick order for a"
                    + " document needs the number of an existing document");
        }
        Object loc = doc.get("loc");
        if (loc != null && !loc.equals(locationId)) {
            throw ApiException.badRequest(kind + " " + doc.get("number") + " takes its stock out of another location;"
                    + " pick it in that location");
        }
        return new SourceDoc(SqlRows.str(doc, "id"), SqlRows.str(doc, "number"));
    }

    /**
     * h-01 F2: what keeps a sourced pick order from shipping. Shipping it only records the shipment, so the goods must
     * already have left the stock ledger through its document: per product of the order, the picked quantity must be
     * covered by the document's issue at the location, less what the document's other shipped pick orders used.
     * Returns one line per uncovered product ({@code productName, picked, issued}); empty when the order may ship.
     */
    List<Map<String, Object>> documentShortfall(Map<String, Object> order) {
        return SqlRows.list(jdbc.sql("WITH lines AS ("
                        + " SELECT l.\"productId\" AS pid, l.\"variantKey\" AS vk, SUM(l.\"pickedQty\") AS picked,"
                        + " max(p.name) AS name FROM \"WarehousePickLine\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                        + " WHERE l.\"pickOrderId\" = :self GROUP BY 1, 2),"
                        + " docs AS (SELECT CAST(:st AS text) AS st, CAST(:ref AS text) AS ref, CAST(:sid AS text) AS sid),"
                        + DOC_ISSUES + "," + USED
                        + " SELECT ln.name AS \"productName\", ln.picked,"
                        + " GREATEST(COALESCE(i.qty, 0) - COALESCE(u.qty, 0), 0) AS issued FROM lines ln"
                        + " LEFT JOIN issued i ON i.pid = ln.pid AND i.vk = ln.vk"
                        + " LEFT JOIN used u ON u.pid = ln.pid AND u.vk = ln.vk"
                        + " WHERE ln.picked > 0 AND GREATEST(COALESCE(i.qty, 0) - COALESCE(u.qty, 0), 0) < ln.picked"
                        + " ORDER BY ln.name")
                .param("org", org()).param("loc", order.get("locationId")).param("self", order.get("id"))
                .param("st", order.get("sourceType")).param("ref", order.get("sourceRef"))
                .param("sid", order.get("sourceId")));
    }

    /** Quantity in open putaway tasks, optionally ignoring one task. */
    BigDecimal openPutawayQty(String locationId, String productId, String variantKey, String exceptTaskId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseTask\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND type = 'putaway'"
                + " AND status = 'open' AND id <> ?")
                .params(org(), locationId, productId, variantKey, exceptTaskId == null ? "" : exceptTaskId)
                .query(BigDecimal.class).single();
    }

    /**
     * Received into the location but in no bin and not staged (never negative). Goods a posted document already took
     * out of the ledger while they wait in the bins for their pick order count as in the location (h-01 F2).
     */
    BigDecimal unbinnedQty(String locationId, String productId, String variantKey) {
        return physicalQty(locationId, productId, variantKey).subtract(binnedQty(locationId, productId, variantKey))
                .subtract(stagedQty(locationId, productId, variantKey)).max(BigDecimal.ZERO);
    }

    /** Quantity in open pick tasks of the location (allocated to pick orders, still in the bins). */
    BigDecimal openPickQty(String locationId, String productId, String variantKey) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseTask\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND type = 'pick'"
                + " AND status = 'open'").params(org(), locationId, productId, variantKey).query(BigDecimal.class).single();
    }

    /** Quantity of one bin cell that open pick / move tasks will take out of the bin. */
    BigDecimal reservedInBin(String binId, String productId, String variantKey, String batchKey) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseTask\" WHERE \"fromBinId\" = ?"
                        + " AND status = 'open' AND \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?"
                        + " AND \"batchKey\" = ? AND type IN ('pick', 'move')")
                .params(binId, org(), productId, variantKey, batchKey).query(BigDecimal.class).single();
    }

    BigDecimal binQty(String binId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"binId\" = ?"
                + " AND \"organizationId\" = ?").params(binId, org()).query(BigDecimal.class).single();
    }

    /** Quantity on its way into a bin through open putaway / move tasks. */
    BigDecimal incomingQty(String binId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseTask\" WHERE \"toBinId\" = ?"
                + " AND status = 'open' AND \"organizationId\" = ? AND type IN ('putaway', 'move')")
                .params(binId, org()).query(BigDecimal.class).single();
    }

    /**
     * Lock a bin row before reading what it holds (Minor, h-01): the same {@code NO KEY UPDATE} lock {@link #addStock}
     * takes, so an edit or delete that checks the bin's quantity cannot interleave with a put into the bin.
     */
    void lockBin(String binId) {
        jdbc.sql("SELECT id FROM \"WarehouseBin\" WHERE id = ? AND \"organizationId\" = ? FOR NO KEY UPDATE")
                .params(binId, org()).query(String.class).optional();
    }

    boolean holdsOtherProduct(String binId, String productId, String variantKey) {
        return jdbc.sql("SELECT count(*) FROM \"WarehouseBinStock\" WHERE \"binId\" = ? AND quantity > 0"
                        + " AND (\"productId\" <> ? OR \"variantKey\" <> ?)")
                .params(binId, productId, variantKey).query(Long.class).single() > 0;
    }

    /** Put {@code qty} into a bin, enforcing its capacity and single-product flag. */
    void addStock(Map<String, Object> bin, String productId, String variantKey, String batchKey, BigDecimal qty) {
        String binId = SqlRows.str(bin, "id");
        // Serialise every put into this bin (any product): capacity and the single-product flag are read under the
        // row lock, so two putaways cannot both pass the checks. NO KEY UPDATE does not block the key-share locks of
        // task / bin-stock inserts referencing the bin.
        Map<String, Object> locked = SqlRows.one(jdbc.sql("SELECT \"maxQty\", \"mixedProducts\" FROM \"WarehouseBin\""
                + " WHERE id = ? AND \"organizationId\" = ? FOR NO KEY UPDATE").params(binId, org()));
        if (locked != null) {
            bin = new HashMap<>(bin);
            bin.put("maxQty", locked.get("maxQty"));
            bin.put("mixedProducts", locked.get("mixedProducts"));
        }
        BigDecimal max = (BigDecimal) bin.get("maxQty");
        if (max != null && binQty(binId).add(qty).compareTo(max) > 0) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " capacity exceeded (max " + max.stripTrailingZeros()
                    .toPlainString() + ")");
        }
        if (!Boolean.TRUE.equals(bin.get("mixedProducts")) && holdsOtherProduct(binId, productId, variantKey)) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " already holds another product");
        }
        jdbc.sql("INSERT INTO \"WarehouseBinStock\" (id, \"organizationId\", \"locationId\", \"binId\", \"productId\","
                        + " \"variantKey\", \"batchKey\", quantity, \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ")"
                        + " ON CONFLICT (\"binId\", \"productId\", \"variantKey\", \"batchKey\") DO UPDATE"
                        + " SET quantity = \"WarehouseBinStock\".quantity + EXCLUDED.quantity, \"updatedAt\" = " + NOW)
                .params(id(), org(), bin.get("locationId"), binId, productId, variantKey, batchKey, qty).update();
    }

    /** Take {@code qty} out of a bin (400 when the bin holds less). */
    void removeStock(Map<String, Object> bin, String productId, String variantKey, String batchKey, BigDecimal qty) {
        int n = jdbc.sql("UPDATE \"WarehouseBinStock\" SET quantity = quantity - ?, \"updatedAt\" = " + NOW
                        + " WHERE \"binId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"batchKey\" = ?"
                        + " AND \"organizationId\" = ? AND quantity >= ?")
                .params(qty, bin.get("id"), productId, variantKey, batchKey, org(), qty).update();
        if (n == 0) {
            throw ApiException.badRequest("Not enough stock in bin " + bin.get("code") + " for "
                    + productName(productId));
        }
        jdbc.sql("DELETE FROM \"WarehouseBinStock\" WHERE \"binId\" = ? AND quantity = 0").param(bin.get("id")).update();
    }
}
