package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory integrity checks (hardening batch R0, {@code GET /inventory/reports/integrity}). Each check states one
 * invariant of the stock engine and lists the rows that break it; nothing here writes or corrects anything. The
 * quantity checks compare in the database; the value checks reuse the accounting valuation (by costing method) and the
 * inventory-to-GL tie-out, so the report always agrees with {@code /reports/inventory/valuation} and
 * {@code /reports/inventory/gl-tieout}. A location filter limits the location-level checks to that location; the
 * organisation-level parts (GL tie-out, Σ open transit = transit-location quantity) are then skipped.
 */
@Service
public class InventoryIntegrityService {

    /** Most rows one check returns; {@code truncated} says when there were more. */
    public static final int ROW_LIMIT = 5000;

    /** Quantity tolerance of the ledger, lot and transit checks. */
    public static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.000001");

    /** Value tolerance of the valuation-vs-sub-ledger check, per product. */
    public static final BigDecimal VALUE_TOLERANCE = new BigDecimal("0.01");

    public static final String LEDGER_VS_QUANT = "ledger_vs_quant";
    public static final String QUANT_VS_LOTS = "quant_vs_lots";
    public static final String QUANT_VS_SERIALS = "quant_vs_serials";
    public static final String SERIAL_STATE = "serial_state";
    public static final String VALUATION_VS_SUBLEDGER = "valuation_vs_subledger";
    public static final String VALUATION_VS_GL = "valuation_vs_gl";
    public static final String TRANSIT = "transit";
    public static final String RESERVATIONS = "reservations";
    public static final String OFFLINE_QUEUE = "offline_queue";

    /** Every check, in report order. */
    public static final List<String> CHECKS = List.of(LEDGER_VS_QUANT, QUANT_VS_LOTS, QUANT_VS_SERIALS, SERIAL_STATE,
            VALUATION_VS_SUBLEDGER, VALUATION_VS_GL, TRANSIT, RESERVATIONS, OFFLINE_QUEUE);

    /** The checks on stock value (the ones that hold a period close when the monitor raises them). */
    public static final List<String> VALUE_CHECKS = List.of(VALUATION_VS_SUBLEDGER, VALUATION_VS_GL);

    /** Kind of the offline transfer receipt waiting for its dispatch (sync module, {@code SyncStockService}). */
    static final String OFFLINE_AWAITING = "offline_awaiting_dispatch";

    /** Age after which a waiting offline receipt on a dispatched transfer is a finding. */
    static final Duration OFFLINE_MAX_AGE = Duration.ofHours(24);

    /** Ledger reference types of a transit transfer's moves into and out of the transit location. */
    private static final String TRANSIT_SOURCES = "'stock_transfer_dispatch', 'stock_transfer_receipt',"
            + " 'stock_transfer_loss', 'stock_transfer_recall'";

    /**
     * One check: {@code ok} when nothing breaks the invariant; {@code failures} counts every breaking row, of which at
     * most {@value #ROW_LIMIT} come back ({@code truncated}). {@code tolerance} null = exact or not numeric. A skipped
     * check (organisation level under a location filter) is ok and says why in {@code note}.
     */
    public record IntegrityCheck(String key, boolean ok, boolean skipped, String invariant, BigDecimal tolerance,
            long failures, boolean truncated, List<Map<String, Object>> rows, String note) {}

    /** The whole report: {@code ok} when every check is. */
    public record IntegrityReport(Instant checkedAt, String locationId, boolean ok, int rowLimit,
            List<IntegrityCheck> checks) {

        /** The failing checks. */
        public List<IntegrityCheck> failing() {
            return checks.stream().filter(c -> !c.ok()).toList();
        }
    }

    private record Found(long total, List<Map<String, Object>> rows) {}

    private final JdbcClient jdbc;
    private final InventoryValuationService valuation;
    private final SettingResolverService settings;
    private final LocationScope scope;
    private final List<IncomingSupplyProvider> incoming;

    public InventoryIntegrityService(JdbcClient jdbc, InventoryValuationService valuation,
            SettingResolverService settings, LocationScope scope, List<IncomingSupplyProvider> incoming) {
        this.jdbc = jdbc;
        this.valuation = valuation;
        this.settings = settings;
        this.scope = scope;
        this.incoming = incoming;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * The integrity report for the caller: {@code locationId} (optional) goes through the user's data scope like the
     * stock reconciliation (a restricted user's blank filter becomes their one location).
     */
    @Transactional(readOnly = true)
    public IntegrityReport report(String locationId) {
        return run(scope.resolve(locationId));
    }

    /**
     * Run every check for the current organisation, without a user data scope (the daily monitor).
     * {@code locationId} null = every location.
     */
    @Transactional(readOnly = true)
    public IntegrityReport run(String locationId) {
        String loc = locationId == null || locationId.isBlank() ? null : locationId;
        List<IntegrityCheck> checks = new ArrayList<>();
        checks.add(ledgerVsQuant(loc));
        checks.add(quantVsLots(loc));
        checks.add(quantVsSerials(loc));
        checks.add(serialState(loc));
        checks.add(valuationVsSubledger(loc));
        checks.add(valuationVsGl(loc));
        checks.add(transit(loc));
        checks.add(reservations(loc));
        checks.add(offlineQueue(loc));
        return new IntegrityReport(Instant.now(), loc, checks.stream().allMatch(IntegrityCheck::ok), ROW_LIMIT,
                checks);
    }

    // ------------------------------------------------------------------ checks

    private IntegrityCheck ledgerVsQuant(String loc) {
        Found f = find(InventoryQueryService.reconciliationCells(loc != null) + " " + """
                 SELECT c.pid AS "productId", c.vk AS "variantKey", c.lid AS "locationId", c.code, c.name,
                        c.lname AS "locationName", c.cached AS quantity, c.ledger AS "ledgerQuantity",
                        c.cached - c.ledger AS difference, count(*) OVER () AS failures_total
                   FROM c WHERE abs(c.cached - c.ledger) > :tol
                  ORDER BY c.name, c.pid, c.lid, c.vk""", params(loc, QTY_TOLERANCE));
        return check(LEDGER_VS_QUANT,
                "Σ InventoryLedger.quantityChange = StockItem.quantity per (product, variant, location)",
                QTY_TOLERANCE, f, null);
    }

    private IntegrityCheck quantVsLots(String loc) {
        Found f = find(InventoryQueryService.reconciliationCells(loc != null) + " " + """
                 SELECT c.pid AS "productId", c.vk AS "variantKey", c.lid AS "locationId", c.code, c.name,
                        c.lname AS "locationName", c.cached AS quantity, c.batches AS "lotQuantity",
                        GREATEST(c.cached, 0) - c.batches AS difference, count(*) OVER () AS failures_total
                   FROM c WHERE c.lots AND abs(GREATEST(c.cached, 0) - c.batches) > :tol
                  ORDER BY c.name, c.pid, c.lid, c.vk""", params(loc, QTY_TOLERANCE));
        return check(QUANT_VS_LOTS, "lot-tracked: max(0, StockItem.quantity) = Σ InventoryBatch.quantity per"
                + " (product, variant, location)", QTY_TOLERANCE, f, null);
    }

    /**
     * Serial-tracked cells. Under the default serial policy ({@code capture_optional}) quantity-only movements are
     * allowed, so a cell may hold units without a serial: there the in-stock serials must not exceed the quantity.
     * Under {@code required} (resolved per product, category and location) they must equal it.
     */
    private IntegrityCheck quantVsSerials(String loc) {
        Map<String, Object> p = params(loc, null);
        String locS = loc == null ? "" : " AND s.\"locationId\" = :loc";
        String locR = loc == null ? "" : " AND r.\"locationId\" = :loc";
        List<Map<String, Object>> candidates = SqlRows.list(jdbc.sql("""
                WITH si AS (SELECT s."productId" AS pid, s."variantKey" AS vk, s."locationId" AS lid,
                                   SUM(s.quantity) AS qty
                              FROM "StockItem" s WHERE s."organizationId" = :org%s GROUP BY 1, 2, 3),
                     sr AS (SELECT r."productId" AS pid, COALESCE(r."variantId", '') AS vk, r."locationId" AS lid,
                                   count(*)::numeric AS n
                              FROM "InventorySerial" r
                             WHERE r."organizationId" = :org AND r.status = 'in_stock'%s GROUP BY 1, 2, 3),
                     k AS (SELECT pid, vk, lid FROM si UNION SELECT pid, vk, lid FROM sr)
                SELECT k.pid AS "productId", k.vk AS "variantKey", k.lid AS "locationId", p.code, p.name,
                       p."categoryId" AS category_id, l.name AS "locationName", COALESCE(si.qty, 0) AS quantity,
                       COALESCE(sr.n, 0) AS "serialsInStock"
                  FROM k
                  JOIN "Product" p ON p.id = k.pid AND p."organizationId" = :org AND p."serialTracking" = true
                  LEFT JOIN si ON si.pid = k.pid AND si.vk = k.vk AND si.lid = k.lid
                  LEFT JOIN sr ON sr.pid = k.pid AND sr.vk = k.vk AND sr.lid = k.lid
                  LEFT JOIN "InventoryLocation" l ON l.id = k.lid
                 WHERE COALESCE(si.qty, 0) <> COALESCE(sr.n, 0)
                 ORDER BY p.name, k.pid, k.lid, k.vk""".formatted(locS, locR)).params(p));
        long total = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            BigDecimal qty = SqlRows.dec(c, "quantity");
            BigDecimal serials = SqlRows.dec(c, "serialsInStock");
            String policy = settings.resolveString("inventory.serialPolicy", new SettingContext(
                    (String) c.get("productId"), (String) c.get("category_id"), (String) c.get("locationId")));
            boolean required = "required".equals(policy);
            boolean broken = serials.compareTo(qty.max(BigDecimal.ZERO)) > 0
                    || (required && serials.compareTo(qty) != 0);
            if (!broken) {
                continue;
            }
            total++;
            if (rows.size() < ROW_LIMIT) {
                Map<String, Object> row = new LinkedHashMap<>(c);
                row.remove("category_id");
                row.put("serialPolicy", policy);
                row.put("difference", qty.subtract(serials));
                rows.add(render(row));
            }
        }
        return check(QUANT_VS_SERIALS, "serial-tracked: in-stock serials ≤ max(0, StockItem.quantity) per (product,"
                + " variant, location), and = quantity where the serial policy is required", null,
                new Found(total, rows), null);
    }

    /**
     * The movements of a serial are its own ledger rows (issues carry {@code serialId}) and the receipt row it came
     * in on (matched through {@code receiptRef}; receipts and transfers write one row for several serials, without
     * {@code serialId}). Only serials with at least one such movement are judged.
     */
    private IntegrityCheck serialState(String loc) {
        String locS = loc == null ? "" : " AND sr.\"locationId\" = :loc";
        Found f = find("""
                WITH s AS (SELECT sr.id, sr."productId", sr."variantId", sr."locationId", sr."serialNumber",
                                  sr.status::text AS status, sr."receiptRef"
                             FROM "InventorySerial" sr
                            WHERE sr."organizationId" = :org AND sr.status IN ('in_stock', 'issued')%s),
                     mv AS (SELECT l."serialId" AS sid, l."createdAt", l."ledgerCode", l."quantityChange",
                                   l.type::text AS type, l."referenceType", l."referenceId"
                              FROM "InventoryLedger" l JOIN s ON s.id = l."serialId"
                             WHERE l."organizationId" = :org AND l."quantityChange" <> 0
                            UNION ALL
                            SELECT s.id, l."createdAt", l."ledgerCode", l."quantityChange", l.type::text,
                                   l."referenceType", l."referenceId"
                              FROM s JOIN "InventoryLedger" l ON l."organizationId" = :org
                                   AND l."productId" = s."productId" AND l."quantityChange" > 0
                                   AND COALESCE(l."referenceType", 'receipt') || ':'
                                       || COALESCE(l."referenceId", l."ledgerCode") = s."receiptRef"),
                     last AS (SELECT DISTINCT ON (sid) * FROM mv ORDER BY sid, "createdAt" DESC, "ledgerCode" DESC)
                SELECT s.id AS "serialId", s."serialNumber", s."productId", s."variantId", s."locationId", p.code,
                       p.name, s.status, last.type AS "lastMoveType", last."quantityChange" AS "lastQuantityChange",
                       last."ledgerCode" AS "lastLedgerCode", last."referenceType" AS "lastReferenceType",
                       last."referenceId" AS "lastReferenceId", last."createdAt" AS "lastMovedAt",
                       count(*) OVER () AS failures_total
                  FROM s JOIN last ON last.sid = s.id
                  LEFT JOIN "Product" p ON p.id = s."productId"
                 WHERE (s.status = 'issued' AND last."quantityChange" > 0)
                    OR (s.status = 'in_stock' AND last."quantityChange" < 0)
                 ORDER BY p.name, s."serialNumber\"""".formatted(locS), params(loc, null));
        return check(SERIAL_STATE, "an issued serial's latest movement is an outflow; an in-stock serial's latest"
                + " movement is an inflow", null, f, null);
    }

    /**
     * Per product: the current valuation by costing method (the accounting valuation's per-quant value, CM-1 adjunct
     * components included) against Σ signed ledger value ({@code totalValue}, negative for outflows).
     */
    private IntegrityCheck valuationVsSubledger(String loc) {
        Map<String, BigDecimal[]> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> q : valuation.currentValueByQuant(loc)) {
            BigDecimal[] v = byProduct.computeIfAbsent((String) q.get("product_id"),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            v[0] = v[0].add(SqlRows.dec(q, "value"));
        }
        String locL = loc == null ? "" : " AND l.\"locationId\" = :loc";
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                SELECT l."productId" AS product_id, SUM(CASE WHEN l."quantityChange" >= 0
                       THEN COALESCE(l."totalValue", 0) ELSE -COALESCE(l."totalValue", 0) END) AS value
                  FROM "InventoryLedger" l WHERE l."organizationId" = :org%s GROUP BY 1""".formatted(locL))
                .params(params(loc, null)))) {
            BigDecimal[] v = byProduct.computeIfAbsent((String) r.get("product_id"),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            v[1] = v[1].add(SqlRows.dec(r, "value"));
        }
        List<String> failing = new ArrayList<>();
        byProduct.forEach((id, v) -> {
            if (v[0].subtract(v[1]).abs().compareTo(VALUE_TOLERANCE) > 0) {
                failing.add(id);
            }
        });
        Map<String, Map<String, Object>> products = new HashMap<>();
        if (!failing.isEmpty()) {
            SqlRows.list(jdbc.sql("SELECT id, code, name, \"costingMethod\"::text AS method FROM \"Product\""
                    + " WHERE \"organizationId\" = :org AND id IN (:ids)").param("org", org()).param("ids", failing))
                    .forEach(p -> products.put((String) p.get("id"), p));
        }
        failing.sort((a, b) -> byProduct.get(b)[0].subtract(byProduct.get(b)[1]).abs()
                .compareTo(byProduct.get(a)[0].subtract(byProduct.get(a)[1]).abs()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : failing) {
            if (rows.size() == ROW_LIMIT) {
                break;
            }
            Map<String, Object> p = products.getOrDefault(id, Map.of());
            BigDecimal[] v = byProduct.get(id);
            rows.add(render(Obj.of("productId", id, "code", p.get("code"), "name", p.get("name"), "costingMethod",
                    p.get("method"), "valuationValue", v[0], "ledgerValue", v[1], "difference", v[0].subtract(v[1]))));
        }
        return check(VALUATION_VS_SUBLEDGER, "per product: current valuation (by costing method) = Σ signed"
                + " InventoryLedger.totalValue", VALUE_TOLERANCE, new Found(failing.size(), rows), null);
    }

    /** The inventory-to-GL tie-out (organisation level, every inventory control account together). */
    @SuppressWarnings("unchecked")
    private IntegrityCheck valuationVsGl(String loc) {
        String invariant = "stock valuation = balance of the inventory control account(s) (the GL tie-out)";
        BigDecimal tolerance = tieOutTolerance();
        if (loc != null) {
            return new IntegrityCheck(VALUATION_VS_GL, true, true, invariant, tolerance, 0, false, List.of(),
                    "Organisation-level check: run the report without a location filter");
        }
        Map<String, Object> t = valuation.glTieOut(null, tolerance.doubleValue());
        boolean within = Boolean.TRUE.equals(t.get("withinTolerance"));
        String note = "variance " + t.get("variance") + " (sub-ledger " + t.get("subledgerValue") + ", GL "
                + t.get("glBalance") + ")" + (t.get("warning") != null ? "; " + t.get("warning") : "");
        List<Map<String, Object>> rows = within ? List.of() : List.of(new LinkedHashMap<>(t));
        return check(VALUATION_VS_GL, invariant, tolerance, new Found(within ? 0 : 1, rows), note);
    }

    /** {@code inventory.glTieOut.tolerance} (base currency), never below 0; 1 when unset. */
    BigDecimal tieOutTolerance() {
        Number configured = settings.resolveNumber("inventory.glTieOut.tolerance", SettingContext.EMPTY);
        double tol = Math.max(0, configured == null ? 1 : configured.doubleValue());
        return new BigDecimal(JsNumbers.toJsString(tol));
    }

    /**
     * Open transit transfers ({@code in_transit}, {@code partially_received}): per transfer and product, the open
     * quantity of its lines (dispatched − received − damaged − short − recalled) against the ledger balance its moves
     * left at its transit location; then, organisation level, Σ open per product against the quantity held at the
     * transit location(s).
     */
    private IntegrityCheck transit(String loc) {
        String locT = loc == null ? "" : " AND (t.\"fromLocId\" = :loc OR t.\"toLocId\" = :loc)";
        Map<String, Object> p = params(loc, QTY_TOLERANCE);
        Found perTransfer = find("""
                WITH t AS (SELECT t.id, t."transferCode", t."transitLocId", t.status::text AS status
                             FROM "StockTransfer" t
                            WHERE t."organizationId" = :org AND t.mode = 'transit'
                              AND t.status::text IN ('in_transit', 'partially_received')%s),
                     it AS (SELECT i."transferId" AS tid, i."productId" AS pid, COALESCE(i."variantId", '') AS vk,
                                   SUM(COALESCE(i."qtyDispatched", 0)) AS dispatched,
                                   SUM(COALESCE(i."qtyReceived", 0)) AS received,
                                   SUM(COALESCE(i."qtyDamaged", 0)) AS damaged,
                                   SUM(COALESCE(i."qtyShort", 0)) AS short_qty,
                                   SUM(COALESCE(i."qtyRecalled", 0)) AS recalled
                              FROM "StockTransferItem" i JOIN t ON t.id = i."transferId"
                             WHERE i."organizationId" = :org GROUP BY 1, 2, 3),
                     lg AS (SELECT t.id AS tid, l."productId" AS pid, COALESCE(l."variantId", '') AS vk,
                                   SUM(l."quantityChange") AS qty
                              FROM t JOIN "InventoryLedger" l ON l."organizationId" = :org
                                   AND l."locationId" = t."transitLocId" AND l."referenceId" = t."transferCode"
                                   AND l."referenceType" IN (%s)
                             GROUP BY 1, 2, 3),
                     k AS (SELECT tid, pid, vk FROM it UNION SELECT tid, pid, vk FROM lg),
                     x AS (SELECT k.*, COALESCE(it.dispatched, 0) AS dispatched, COALESCE(it.received, 0) AS received,
                                  COALESCE(it.damaged, 0) AS damaged, COALESCE(it.short_qty, 0) AS short_qty,
                                  COALESCE(it.recalled, 0) AS recalled,
                                  COALESCE(it.dispatched - it.received - it.damaged - it.short_qty - it.recalled, 0)
                                      AS open_qty,
                                  COALESCE(lg.qty, 0) AS transit_qty
                             FROM k LEFT JOIN it ON it.tid = k.tid AND it.pid = k.pid AND it.vk = k.vk
                             LEFT JOIN lg ON lg.tid = k.tid AND lg.pid = k.pid AND lg.vk = k.vk)
                SELECT 'transfer' AS kind, x.tid AS "transferId", t."transferCode", t.status, x.pid AS "productId",
                       x.vk AS "variantKey", p.code, p.name, x.dispatched, x.received, x.damaged,
                       x.short_qty AS "short", x.recalled, x.open_qty AS "openQuantity",
                       x.transit_qty AS "transitQuantity",
                       x.open_qty - x.transit_qty AS difference, count(*) OVER () AS failures_total
                  FROM x JOIN t ON t.id = x.tid LEFT JOIN "Product" p ON p.id = x.pid
                 WHERE abs(x.open_qty - x.transit_qty) > :tol OR x.open_qty < -:tol
                 ORDER BY t."transferCode", p.name, x.pid, x.vk""".formatted(locT, TRANSIT_SOURCES), p);
        if (loc != null) {
            return check(TRANSIT, transitInvariant(), QTY_TOLERANCE, perTransfer,
                    "Σ open = transit-location quantity is organisation level: skipped under a location filter");
        }
        Found perProduct = find("""
                WITH op AS (SELECT i."productId" AS pid, COALESCE(i."variantId", '') AS vk,
                                   SUM(COALESCE(i."qtyDispatched", 0) - COALESCE(i."qtyReceived", 0)
                                       - COALESCE(i."qtyDamaged", 0) - COALESCE(i."qtyShort", 0)
                                       - COALESCE(i."qtyRecalled", 0)) AS open_qty
                              FROM "StockTransferItem" i JOIN "StockTransfer" t ON t.id = i."transferId"
                             WHERE i."organizationId" = :org AND t."organizationId" = :org AND t.mode = 'transit'
                               AND t.status::text IN ('in_transit', 'partially_received')
                             GROUP BY 1, 2),
                     tq AS (SELECT s."productId" AS pid, s."variantKey" AS vk, SUM(s.quantity) AS qty
                              FROM "StockItem" s JOIN "InventoryLocation" l ON l.id = s."locationId"
                             WHERE s."organizationId" = :org AND l.type::text = 'transit' GROUP BY 1, 2),
                     k AS (SELECT pid, vk FROM op UNION SELECT pid, vk FROM tq)
                SELECT 'transit_location' AS kind, k.pid AS "productId", k.vk AS "variantKey", p.code, p.name,
                       COALESCE(op.open_qty, 0) AS "openQuantity", COALESCE(tq.qty, 0) AS "transitQuantity",
                       COALESCE(op.open_qty, 0) - COALESCE(tq.qty, 0) AS difference, count(*) OVER () AS failures_total
                  FROM k LEFT JOIN op ON op.pid = k.pid AND op.vk = k.vk LEFT JOIN tq ON tq.pid = k.pid AND tq.vk = k.vk
                  LEFT JOIN "Product" p ON p.id = k.pid
                 WHERE abs(COALESCE(op.open_qty, 0) - COALESCE(tq.qty, 0)) > :tol
                 ORDER BY p.name, k.pid, k.vk""", params(null, QTY_TOLERANCE));
        return check(TRANSIT, transitInvariant(), QTY_TOLERANCE, merge(perTransfer, perProduct), null);
    }

    private static String transitInvariant() {
        return "per open transit transfer and product: dispatched − received − damaged − short − recalled = its"
                + " ledger balance at the transit location; Σ open per product = transit-location quantity";
    }

    /**
     * Active reservations: none negative; per cell, reserved ≤ on-hand + incoming supply (the replenishment providers:
     * open purchase orders and requests, production, transfers into the location); none whose source is missing,
     * closed or cancelled for the source types that can be resolved — {@code invoice} (cancelled / refunded),
     * {@code sales_order_line} (order done / cancelled / deleted), {@code production_order} (completed / cancelled),
     * {@code processing_job_input} (job cancelled / deleted). Other source types (manual, free text) are not judged.
     */
    private IntegrityCheck reservations(String loc) {
        String locR = loc == null ? "" : " AND r.\"locationId\" = :loc";
        Map<String, Object> p = params(loc, null);
        Found negative = find("""
                SELECT 'negative' AS kind, r.id AS "reservationId", r."productId", r."variantKey", r."locationId",
                       p.code, p.name, r.quantity, r."sourceType", r."sourceId", count(*) OVER () AS failures_total
                  FROM "StockReservation" r LEFT JOIN "Product" p ON p.id = r."productId"
                 WHERE r."organizationId" = :org AND r.status = 'active' AND r.quantity < 0%s
                 ORDER BY p.name, r.id""".formatted(locR), p);
        Found closed = find("""
                SELECT 'closed_source' AS kind, r.id AS "reservationId", r."productId", r."variantKey",
                       r."locationId", p.code, p.name, r.quantity, r."sourceType", r."sourceId",
                       count(*) OVER () AS failures_total
                  FROM "StockReservation" r LEFT JOIN "Product" p ON p.id = r."productId"
                 WHERE r."organizationId" = :org AND r.status = 'active'%s AND (
                       (r."sourceType" = 'invoice' AND NOT EXISTS (SELECT 1 FROM "Invoice" i
                            WHERE i.id = r."sourceId" AND i."organizationId" = :org
                              AND i.status::text NOT IN ('cancelled', 'refunded')))
                    OR (r."sourceType" = 'sales_order_line' AND NOT EXISTS (SELECT 1 FROM "SalesOrderLine" sl
                            JOIN "SalesOrder" so ON so.id = sl."orderId"
                            WHERE sl.id = r."sourceId" AND so."organizationId" = :org
                              AND so.status NOT IN ('done', 'cancelled') AND so."deletedAt" IS NULL))
                    OR (r."sourceType" = 'production_order' AND NOT EXISTS (SELECT 1 FROM "ProductionOrder" po
                            WHERE po.id = r."sourceId" AND po."organizationId" = :org
                              AND po.status::text NOT IN ('completed', 'cancelled')))
                    OR (r."sourceType" = 'processing_job_input' AND NOT EXISTS (SELECT 1 FROM "ProcessingJobInput" pi
                            JOIN "ProcessingJob" pj ON pj.id = pi."jobId"
                            WHERE pi.id = r."sourceId" AND pj."organizationId" = :org
                              AND pj."cancelledAt" IS NULL AND pj."deletedAt" IS NULL)))
                 ORDER BY p.name, r.id""".formatted(locR), p);
        String locS = loc == null ? "" : " AND s.\"locationId\" = :loc";
        List<Map<String, Object>> over = SqlRows.list(jdbc.sql("""
                WITH r AS (SELECT r."productId" AS pid, r."variantKey" AS vk, r."locationId" AS lid,
                                  SUM(r.quantity) AS reserved
                             FROM "StockReservation" r WHERE r."organizationId" = :org AND r.status = 'active'%s
                            GROUP BY 1, 2, 3),
                     s AS (SELECT s."productId" AS pid, s."variantKey" AS vk, s."locationId" AS lid,
                                  SUM(s.quantity) AS qty
                             FROM "StockItem" s WHERE s."organizationId" = :org%s GROUP BY 1, 2, 3)
                SELECT 'over_reserved' AS kind, r.pid AS "productId", r.vk AS "variantKey", r.lid AS "locationId",
                       p.code, p.name, l.name AS "locationName", r.reserved, COALESCE(s.qty, 0) AS "onHand"
                  FROM r LEFT JOIN s ON s.pid = r.pid AND s.vk = r.vk AND s.lid = r.lid
                  LEFT JOIN "Product" p ON p.id = r.pid LEFT JOIN "InventoryLocation" l ON l.id = r.lid
                 WHERE r.reserved > COALESCE(s.qty, 0)
                 ORDER BY p.name, r.pid, r.lid, r.vk""".formatted(locR, locS)).params(p));
        long overTotal = 0;
        List<Map<String, Object>> overRows = new ArrayList<>();
        for (Map<String, Object> c : over) {
            String variantKey = (String) c.get("variantKey");
            BigDecimal supply = BigDecimal.ZERO;
            for (IncomingSupplyProvider provider : incoming) {
                BigDecimal in = provider.incoming((String) c.get("productId"),
                        variantKey == null || variantKey.isEmpty() ? null : variantKey, (String) c.get("locationId"),
                        false);
                supply = supply.add(in == null ? BigDecimal.ZERO : in);
            }
            BigDecimal available = SqlRows.dec(c, "onHand").add(supply);
            if (SqlRows.dec(c, "reserved").compareTo(available) <= 0) {
                continue;
            }
            overTotal++;
            if (overRows.size() < ROW_LIMIT) {
                Map<String, Object> row = new LinkedHashMap<>(c);
                row.put("incoming", supply);
                row.put("excess", SqlRows.dec(c, "reserved").subtract(available));
                overRows.add(render(row));
            }
        }
        return check(RESERVATIONS, "active reservations: none negative; reserved ≤ on-hand + incoming per (product,"
                + " variant, location); none on a missing, closed or cancelled source", null,
                merge(negative, new Found(overTotal, overRows), closed),
                "Sources judged: invoice, sales_order_line, production_order, processing_job_input");
    }

    private IntegrityCheck offlineQueue(String loc) {
        String locE = loc == null ? "" : " AND e.\"locationId\" = :loc";
        Map<String, Object> p = params(loc, null);
        p.put("kind", OFFLINE_AWAITING);
        p.put("before", SqlRows.ts(Instant.now().minus(OFFLINE_MAX_AGE)));
        Found f = find("""
                SELECT e.id AS "exceptionId", e."locationId", e."createdAt", t.id AS "transferId", t."transferCode",
                       t.status::text AS "transferStatus", t."dispatchedAt", count(*) OVER () AS failures_total
                  FROM "InventoryException" e
                  JOIN "StockTransfer" t ON t.id = e.payload->>'transferId' AND t."organizationId" = e."organizationId"
                 WHERE e."organizationId" = :org AND e.kind = :kind AND e.status = 'open' AND e."createdAt" < :before
                   AND t.status::text IN ('in_transit', 'partially_received')%s
                 ORDER BY e."createdAt", e.id""".formatted(locE), p);
        return check(OFFLINE_QUEUE, "no open offline_awaiting_dispatch receipt older than 24 h on a transfer that is"
                + " already dispatched", null, f, null);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> params(String loc, BigDecimal tolerance) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", org());
        if (loc != null) {
            p.put("loc", loc);
        }
        if (tolerance != null) {
            p.put("tol", tolerance);
        }
        return p;
    }

    /** Rows of {@code sql} (which selects {@code failures_total} = count(*) OVER ()), at most {@value #ROW_LIMIT}. */
    private Found find(String sql, Map<String, Object> params) {
        List<Map<String, Object>> found = SqlRows.list(jdbc.sql(sql + " LIMIT " + ROW_LIMIT).params(params));
        long total = found.isEmpty() ? 0 : ((Number) found.getFirst().get("failures_total")).longValue();
        List<Map<String, Object>> rows = new ArrayList<>(found.size());
        for (Map<String, Object> r : found) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.remove("failures_total");
            rows.add(render(row));
        }
        return new Found(total, rows);
    }

    private static Found merge(Found... parts) {
        long total = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Found f : parts) {
            total += f.total();
            for (Map<String, Object> r : f.rows()) {
                if (rows.size() < ROW_LIMIT) {
                    rows.add(r);
                }
            }
        }
        return new Found(total, rows);
    }

    /** Decimals as exact decimal strings (decimal.js {@code toString}), like the other inventory reports. */
    private static Map<String, Object> render(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof BigDecimal b ? JsNumbers.dec(b) : v));
        return out;
    }

    private static IntegrityCheck check(String key, String invariant, BigDecimal tolerance, Found f, String note) {
        return new IntegrityCheck(key, f.total() == 0, false, invariant, tolerance, f.total(),
                f.total() > f.rows().size(), f.rows(), note);
    }

    /** The distinct product ids of a check's rows (for the monitor's exception payload). */
    static Set<String> productIds(IntegrityCheck check) {
        Set<String> ids = new LinkedHashSet<>();
        check.rows().forEach(r -> {
            if (r.get("productId") != null) {
                ids.add(String.valueOf(r.get("productId")));
            }
        });
        return ids;
    }
}
