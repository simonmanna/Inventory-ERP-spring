package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.accounting.application.OrgDates;
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
import java.util.HashSet;
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
    /** Hardening R3: per product, Σ stock-account GL (product-stamped lines) = Σ ledger value since the cutover. */
    public static final String VALUATION_VS_GL_BY_PRODUCT = "valuation_vs_gl_by_product";
    public static final String TRANSIT = "transit";
    public static final String RESERVATIONS = "reservations";
    public static final String OFFLINE_QUEUE = "offline_queue";

    /** Every check, in report order. */
    public static final List<String> CHECKS = List.of(LEDGER_VS_QUANT, QUANT_VS_LOTS, QUANT_VS_SERIALS, SERIAL_STATE,
            VALUATION_VS_SUBLEDGER, VALUATION_VS_GL, VALUATION_VS_GL_BY_PRODUCT, TRANSIT, RESERVATIONS, OFFLINE_QUEUE);

    // IPR Phase 0 (task T05): report-only checks. They are listed in the report's reportOnlyChecks, never in checks
    // or VALUE_CHECKS: they do not change the report's ok, and the monitor raises them as integrity_<key> findings
    // that cannot hold a period close (the close gates skip every integrity_ kind except INTEGRITY_VALUE_KINDS).
    public static final String NEGATIVE_QUANTS = "negative_quants";
    public static final String ISSUED_SERIAL_LOCATED = "issued_serial_located";
    public static final String OVER_RETURNED = "over_returned";
    public static final String UNLINKED_RESTOCKS = "unlinked_restocks";
    public static final String GL_TIEOUT_BY_ACCOUNT = "gl_tieout_by_account";
    public static final String STOCK_POSTING_JOBS = "stock_posting_jobs";
    public static final String TRANSIT_AGE = "transit_age";
    public static final String INVOICE_UNDELIVERED = "invoice_undelivered";
    public static final String DELIVERY_RETURN_OVERLAP = "delivery_return_overlap";
    public static final String QUALITY_HOLD_UNQUARANTINED = "quality_hold_unquarantined";
    public static final String PERIOD_SHIFTED = "period_shifted";
    public static final String FEATURE_RISK = "feature_risk";
    /**
     * IPR GL-11 / R4: per manufacturing cost object, GL WIP (base currency) = its sub-ledger on the basis of
     * docs/spec/manufacturing-wip-reconciliation.md. Report-only until the owner promotes it to a value check.
     */
    public static final String WIP_TIEOUT = "wip_tieout";

    /** Every report-only check, in report order. */
    public static final List<String> REPORT_ONLY_CHECKS = List.of(NEGATIVE_QUANTS, ISSUED_SERIAL_LOCATED, OVER_RETURNED,
            UNLINKED_RESTOCKS, GL_TIEOUT_BY_ACCOUNT, STOCK_POSTING_JOBS, TRANSIT_AGE, INVOICE_UNDELIVERED,
            DELIVERY_RETURN_OVERLAP, QUALITY_HOLD_UNQUARANTINED, PERIOD_SHIFTED, FEATURE_RISK, WIP_TIEOUT);

    /**
     * Report-only checks that only inform: always ok (so the monitor never raises them), their rows show the scale of a
     * known gap (period-shifted movements: R3). Unlinked restocks left this list in IPR 1d: they fail for new rows.
     */
    public static final List<String> INFORMATIONAL_CHECKS = List.of(PERIOD_SHIFTED);

    /** Age after which a pending or processing stock-posting job is stale. */
    public static final Duration STOCK_JOB_MAX_AGE = Duration.ofHours(6);

    /** Age after which an open transit transfer is a finding. */
    public static final Duration TRANSIT_MAX_AGE = Duration.ofDays(7);

    /** How far back the period-shifted report looks (by ledger createdAt), to keep it cheap. */
    public static final Duration PERIOD_SHIFT_LOOKBACK = Duration.ofDays(366);

    /**
     * Ledger reference types that undo or restock an earlier movement, with the direction of the undoing row
     * (+1 inflow, -1 outflow, 0 both). A row of one of them without {@code reversesLedgerId} is an unlinked restock.
     */
    static final Map<String, Integer> RESTOCK_SOURCES = Map.ofEntries(Map.entry("pos_refund", 1),
            Map.entry("fleet_trip_return", 1), Map.entry("sales_return", 1), Map.entry("quarry_explosive_return", 1),
            Map.entry("project_issue_reversal", 1), Map.entry("production_reversal", 0),
            Map.entry("production_order_cancel", 1), Map.entry("sales_invoice_cancel", 1), Map.entry("credit_note", 1),
            Map.entry("cash_sale_void", 1), Map.entry("debit_note_reversal", 1), Map.entry("pos_order_cancel", 1),
            Map.entry("pos_order_void", 1), Map.entry("repair_part_return", 1), Map.entry("vendor_bill_cancel", -1),
            Map.entry("credit_note_cancel", -1));

    /** The checks on stock value (the ones that hold a period close when the monitor raises them). */
    public static final List<String> VALUE_CHECKS = List.of(VALUATION_VS_SUBLEDGER, VALUATION_VS_GL,
            VALUATION_VS_GL_BY_PRODUCT);

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

    /**
     * The whole report: {@code ok} when every check of {@code checks} is. The report-only checks (IPR Phase 0) come in
     * {@code reportOnlyChecks} with their own {@code reportOnlyOk}; they never change {@code ok}.
     */
    public record IntegrityReport(Instant checkedAt, String locationId, boolean ok, int rowLimit,
            List<IntegrityCheck> checks, boolean reportOnlyOk, List<IntegrityCheck> reportOnlyChecks) {

        /** The failing checks. */
        public List<IntegrityCheck> failing() {
            return checks.stream().filter(c -> !c.ok()).toList();
        }

        /** The failing report-only checks. */
        public List<IntegrityCheck> failingReportOnly() {
            return reportOnlyChecks.stream().filter(c -> !c.ok()).toList();
        }
    }

    private record Found(long total, List<Map<String, Object>> rows) {}

    private final JdbcClient jdbc;
    private final InventoryValuationService valuation;
    private final SettingResolverService settings;
    private final LocationScope scope;
    private final List<IncomingSupplyProvider> incoming;
    private final StockPostingService stockPosting;
    private final InventoryFeatureUsageService featureUsage;

    public InventoryIntegrityService(JdbcClient jdbc, InventoryValuationService valuation,
            SettingResolverService settings, LocationScope scope, List<IncomingSupplyProvider> incoming,
            StockPostingService stockPosting, InventoryFeatureUsageService featureUsage) {
        this.jdbc = jdbc;
        this.valuation = valuation;
        this.settings = settings;
        this.scope = scope;
        this.incoming = incoming;
        this.stockPosting = stockPosting;
        this.featureUsage = featureUsage;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * The integrity report for the caller: {@code locationId} (optional) goes through the user's data scope like the
     * stock reconciliation. IPR S-1: a restricted user's blank filter covers every location of their access; with
     * several, each location is checked and the results are merged ({@link #merge}), instead of a 400.
     */
    @Transactional(readOnly = true)
    public IntegrityReport report(String locationId) {
        List<String> locations = scope.readLocations(locationId);
        if (locations == null || locations.size() == 1) {
            return run(LocationScope.single(locations));
        }
        return merge(locations.stream().map(this::run).toList());
    }

    /**
     * One report from several single-location reports, check by check: a check passes when it passes everywhere, is
     * skipped when it is skipped everywhere, and lists the failures of every location (at most {@link #ROW_LIMIT}
     * rows). The merged report has no single {@code locationId}.
     */
    static IntegrityReport merge(List<IntegrityReport> reports) {
        IntegrityReport first = reports.getFirst();
        List<IntegrityCheck> checks = mergeChecks(reports.stream().map(IntegrityReport::checks).toList());
        List<IntegrityCheck> reportOnly = mergeChecks(reports.stream().map(IntegrityReport::reportOnlyChecks).toList());
        return new IntegrityReport(first.checkedAt(), null, checks.stream().allMatch(IntegrityCheck::ok),
                first.rowLimit(), checks, reportOnly.stream().allMatch(IntegrityCheck::ok), reportOnly);
    }

    private static List<IntegrityCheck> mergeChecks(List<List<IntegrityCheck>> perLocation) {
        List<IntegrityCheck> out = new ArrayList<>();
        for (int i = 0; i < perLocation.getFirst().size(); i++) {
            int index = i;
            List<IntegrityCheck> same = perLocation.stream().map(l -> l.get(index)).toList();
            IntegrityCheck c = same.getFirst();
            List<Map<String, Object>> rows = new ArrayList<>();
            long failures = 0;
            boolean truncated = false;
            for (IntegrityCheck s : same) {
                failures += s.failures();
                truncated |= s.truncated();
                for (Map<String, Object> r : s.rows()) {
                    if (rows.size() < ROW_LIMIT) {
                        rows.add(r);
                    } else {
                        truncated = true;
                    }
                }
            }
            out.add(new IntegrityCheck(c.key(), same.stream().allMatch(IntegrityCheck::ok),
                    same.stream().allMatch(IntegrityCheck::skipped), c.invariant(), c.tolerance(), failures, truncated,
                    rows, c.note()));
        }
        return out;
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
        checks.add(valuationVsGlByProduct(loc));
        checks.add(transit(loc));
        checks.add(reservations(loc));
        checks.add(offlineQueue(loc));
        List<IntegrityCheck> reportOnly = reportOnly(loc);
        return new IntegrityReport(Instant.now(), loc, checks.stream().allMatch(IntegrityCheck::ok), ROW_LIMIT,
                checks, reportOnly.stream().allMatch(IntegrityCheck::ok), reportOnly);
    }

    /**
     * IPR GL-3 / GL-10: only the checks on stock value ({@link #VALUE_CHECKS}), organisation level, run now. The period
     * close and the resolution of a value finding re-run these instead of trusting an earlier finding.
     */
    @Transactional(readOnly = true)
    public List<IntegrityCheck> valueChecks() {
        return List.of(valuationVsSubledger(null), valuationVsGl(null), valuationVsGlByProduct(null));
    }

    /** IPR Phase 0: the report-only checks, in {@link #REPORT_ONLY_CHECKS} order. */
    private List<IntegrityCheck> reportOnly(String loc) {
        List<IntegrityCheck> checks = new ArrayList<>();
        checks.add(negativeQuants(loc));
        checks.add(issuedSerialLocated(loc));
        checks.add(overReturned(loc));
        checks.add(unlinkedRestocks(loc));
        checks.add(glTieOutByAccount(loc));
        checks.add(stockPostingJobs(loc));
        checks.add(transitAge(loc));
        checks.add(invoiceUndelivered(loc));
        checks.add(deliveryReturnOverlap(loc));
        checks.add(qualityHoldUnquarantined(loc));
        checks.add(periodShifted(loc));
        checks.add(featureRisk(loc));
        checks.add(wipTieOut(loc));
        return checks;
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

    /**
     * Hardening R3: the GL per product. From the cutover ({@code inventory.productGlCutoverAt}, written by V613; absent
     * = the whole history) every stock-account journal line carries its product
     * ({@link InventoryValuationService#PRODUCT_DIMENSION}). Per product, Σ (debit − credit) of those lines on the
     * inventory control accounts must equal Σ signed {@code InventoryLedger.totalValue} of the rows recorded since the
     * cutover, within 0.01. A line without the key is attributed by its source document when every ledger row of that
     * document (reference or ledger code) is one product; the rest is reported as one {@code unattributed} row (a
     * posting path that does not stamp). The CM-1 adjunct accounts (price differences, NRV allowance) and their
     * value-only components are left to the organisation-level tie-out; so is the branch split (product-level
     * postings — revaluations, landed cost — have no single location). Organisation level: skipped under a location
     * filter, and while no stamped line exists.
     */
    private IntegrityCheck valuationVsGlByProduct(String loc) {
        String invariant = "per product since the cutover: Σ stock-account GL (debit − credit) = Σ signed"
                + " InventoryLedger.totalValue";
        if (loc != null) {
            return new IntegrityCheck(VALUATION_VS_GL_BY_PRODUCT, true, true, invariant, VALUE_TOLERANCE, 0, false,
                    List.of(), "Organisation-level check: run the report without a location filter");
        }
        String org = org();
        String cutover = jdbc.sql("SELECT value #>> '{}' FROM \"Setting\" WHERE \"organizationId\" = ? AND key = ?"
                        + " AND \"scopeType\" = 'organization' LIMIT 1")
                .params(org, InventoryValuationService.PRODUCT_GL_CUTOVER_SETTING).query(String.class).optional()
                .orElse("1970-01-01 00:00:00");
        Set<String> accounts = new LinkedHashSet<>(valuation.inventoryAccountIds());
        accounts.removeAll(jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ?"
                + " AND key IN ('inventory_price_difference', 'inventory_nrv_allowance')").param(org)
                .query(String.class).list());
        String note = "cutover " + cutover;
        if (accounts.isEmpty()) {
            return new IntegrityCheck(VALUATION_VS_GL_BY_PRODUCT, true, true, invariant, VALUE_TOLERANCE, 0, false,
                    List.of(), "No inventory control account is mapped");
        }
        Map<String, Object> p = new HashMap<>();
        p.put("org", org);
        p.put("cut", cutover);
        p.put("accts", List.copyOf(accounts));
        p.put("key", InventoryValuationService.PRODUCT_DIMENSION);
        Map<String, BigDecimal> gl = new LinkedHashMap<>();
        BigDecimal unattributed = BigDecimal.ZERO;
        long unattributedLines = 0;
        boolean anyStamped = false;
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                WITH gl AS (SELECT e."sourceId" AS sid, jl.dimensions ->> :key AS pid,
                                   jl."baseDebit" - jl."baseCredit" AS amt
                              FROM "JournalLine" jl JOIN "JournalEntry" e ON e.id = jl."journalEntryId"
                             WHERE e."organizationId" = :org AND e.status::text <> 'draft'
                               AND jl."accountId" IN (:accts) AND e."createdAt" >= CAST(:cut AS timestamp)),
                     src AS (SELECT x.sid, MIN(x.pid) AS pid FROM (
                               SELECT l."referenceId" AS sid, l."productId" AS pid FROM "InventoryLedger" l
                                WHERE l."organizationId" = :org AND l."createdAt" >= CAST(:cut AS timestamp)
                                  AND l."valuationComponent" IS NULL AND l."referenceId" IS NOT NULL
                               UNION ALL
                               SELECT l."ledgerCode", l."productId" FROM "InventoryLedger" l
                                WHERE l."organizationId" = :org AND l."createdAt" >= CAST(:cut AS timestamp)
                                  AND l."valuationComponent" IS NULL) x
                              GROUP BY x.sid HAVING COUNT(DISTINCT x.pid) = 1)
                SELECT COALESCE(g.pid, s.pid) AS product_id, SUM(g.amt) AS amount, COUNT(*) AS lines,
                       BOOL_OR(g.pid IS NOT NULL) AS stamped
                  FROM gl g LEFT JOIN src s ON g.pid IS NULL AND s.sid = g.sid
                 GROUP BY 1""").params(p))) {
            String pid = (String) r.get("product_id");
            if (Boolean.TRUE.equals(r.get("stamped"))) {
                anyStamped = true;
            }
            if (pid == null) {
                unattributed = unattributed.add(SqlRows.dec(r, "amount"));
                unattributedLines += ((Number) r.get("lines")).longValue();
            } else {
                gl.merge(pid, SqlRows.dec(r, "amount"), BigDecimal::add);
            }
        }
        if (!anyStamped) {
            return new IntegrityCheck(VALUATION_VS_GL_BY_PRODUCT, true, true, invariant, VALUE_TOLERANCE, 0, false,
                    List.of(), "No product-stamped stock journal line yet (" + note + ")");
        }
        Map<String, BigDecimal> ledger = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("""
                SELECT l."productId" AS product_id, SUM(CASE WHEN l."quantityChange" >= 0
                       THEN COALESCE(l."totalValue", 0) ELSE -COALESCE(l."totalValue", 0) END) AS value
                  FROM "InventoryLedger" l WHERE l."organizationId" = :org
                   AND l."createdAt" >= CAST(:cut AS timestamp) AND l."valuationComponent" IS NULL
                 GROUP BY 1""").params(p))) {
            ledger.put((String) r.get("product_id"), SqlRows.dec(r, "value"));
        }
        Set<String> ids = new LinkedHashSet<>(ledger.keySet());
        ids.addAll(gl.keySet());
        List<String> failing = new ArrayList<>();
        for (String id : ids) {
            BigDecimal d = ledger.getOrDefault(id, BigDecimal.ZERO).subtract(gl.getOrDefault(id, BigDecimal.ZERO));
            if (d.abs().compareTo(VALUE_TOLERANCE) > 0) {
                failing.add(id);
            }
        }
        Map<String, Map<String, Object>> products = new HashMap<>();
        Map<String, BigDecimal> qty = new HashMap<>();
        if (!failing.isEmpty()) {
            SqlRows.list(jdbc.sql("SELECT id, code, name, \"costingMethod\"::text AS method FROM \"Product\""
                    + " WHERE \"organizationId\" = :org AND id IN (:ids)").param("org", org).param("ids", failing))
                    .forEach(r -> products.put((String) r.get("id"), r));
            SqlRows.list(jdbc.sql("SELECT \"productId\" AS id, SUM(quantity) AS qty FROM \"StockItem\" WHERE"
                    + " \"organizationId\" = :org AND \"productId\" IN (:ids) GROUP BY 1").param("org", org)
                    .param("ids", failing)).forEach(r -> qty.put((String) r.get("id"), SqlRows.dec(r, "qty")));
        }
        java.util.function.Function<String, BigDecimal> diff = id -> ledger.getOrDefault(id, BigDecimal.ZERO)
                .subtract(gl.getOrDefault(id, BigDecimal.ZERO)).abs();
        failing.sort((a, b) -> diff.apply(b).compareTo(diff.apply(a)));
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean unattributedFails = unattributed.abs().compareTo(VALUE_TOLERANCE) > 0;
        if (unattributedFails) {
            rows.add(render(Obj.of("productId", null, "unattributed", true, "lines", unattributedLines, "glValue",
                    unattributed)));
        }
        for (String id : failing) {
            if (rows.size() == ROW_LIMIT) {
                break;
            }
            Map<String, Object> pr = products.getOrDefault(id, Map.of());
            BigDecimal l = ledger.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal g = gl.getOrDefault(id, BigDecimal.ZERO);
            rows.add(render(Obj.of("productId", id, "code", pr.get("code"), "name", pr.get("name"), "costingMethod",
                    pr.get("method"), "quantity", qty.getOrDefault(id, BigDecimal.ZERO), "subledgerValue", l,
                    "glValue", g, "difference", l.subtract(g))));
        }
        return check(VALUATION_VS_GL_BY_PRODUCT, invariant, VALUE_TOLERANCE,
                new Found(failing.size() + (unattributedFails ? 1 : 0), rows), note);
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

    // ------------------------------------------------------------------ report-only checks (IPR Phase 0)

    /** Organisation-level report-only check under a location filter: ok, skipped, with the reason. */
    private static IntegrityCheck orgLevelSkipped(String key, String invariant, BigDecimal tolerance) {
        return new IntegrityCheck(key, true, true, invariant, tolerance, 0, false, List.of(),
                "Organisation-level check: run the report without a location filter");
    }

    /** Informational check: always ok, its rows show the scale; {@code note} says how many rows stand behind them. */
    private static IntegrityCheck informational(String key, String invariant, Found f, String note) {
        return new IntegrityCheck(key, true, false, invariant, null, 0, f.total() > f.rows().size(), f.rows(), note);
    }

    /** StockItem quantities below zero (quant_vs_lots compares max(0, quantity), so it never shows them). */
    private IntegrityCheck negativeQuants(String loc) {
        String locS = loc == null ? "" : " AND s.\"locationId\" = :loc";
        Found f = find("""
                SELECT s."productId", s."variantKey", s."locationId", p.code, p.name, l.name AS "locationName",
                       s.quantity, count(*) OVER () AS failures_total
                  FROM "StockItem" s LEFT JOIN "Product" p ON p.id = s."productId"
                  LEFT JOIN "InventoryLocation" l ON l.id = s."locationId"
                 WHERE s."organizationId" = :org AND s.quantity < -:tol%s
                 ORDER BY s.quantity, p.name, s.id""".formatted(locS), params(loc, QTY_TOLERANCE));
        return check(NEGATIVE_QUANTS, "StockItem.quantity ≥ 0 per (product, variant, location)", QTY_TOLERANCE, f,
                null);
    }

    /**
     * An issued serial whose issuing row came back. The engine's issue leaves {@code InventorySerial.locationId} set
     * ({@code StockService} only flips the status), so "issued with a location" is every issued serial; instead: the
     * serial is {@code issued}, and its latest outflow row (one row per serial, carrying {@code serialId}) has been
     * reversed by ≥ 1 unit net ({@code reversesLedgerId} rows, net of their own reversals) — the unit is back at a
     * location while the serial still says issued. Restocks that do not link their issue (unlinked_restocks) are not
     * seen here.
     */
    private IntegrityCheck issuedSerialLocated(String loc) {
        String locS = loc == null ? "" : " AND sr.\"locationId\" = :loc";
        Found f = find("""
                WITH s AS (SELECT sr.id, sr."productId", sr."variantId", sr."locationId", sr."serialNumber"
                             FROM "InventorySerial" sr
                            WHERE sr."organizationId" = :org AND sr.status = 'issued'%s),
                     iss AS (SELECT DISTINCT ON (l."serialId") l."serialId" AS sid, l.id AS lid, l."ledgerCode",
                                    l."locationId" AS lloc, l."referenceType", l."referenceId"
                               FROM "InventoryLedger" l JOIN s ON s.id = l."serialId"
                              WHERE l."organizationId" = :org AND l."quantityChange" < 0
                              ORDER BY l."serialId", l."createdAt" DESC, l.seq DESC),
                     back AS (SELECT r."reversesLedgerId" AS oid,
                                     SUM(r."quantityChange" + COALESCE(x.q, 0)) AS qty
                                FROM iss JOIN "InventoryLedger" r ON r."organizationId" = :org
                                     AND r."reversesLedgerId" = iss.lid
                                LEFT JOIN LATERAL (SELECT SUM(y."quantityChange") AS q FROM "InventoryLedger" y
                                                    WHERE y."organizationId" = :org AND y."reversesLedgerId" = r.id) x
                                     ON true
                               GROUP BY 1)
                SELECT s.id AS "serialId", s."serialNumber", s."productId", s."variantId", s."locationId", p.code,
                       p.name, iss."ledgerCode" AS "issueLedgerCode", iss."referenceType" AS "issueReferenceType",
                       iss."referenceId" AS "issueReferenceId", back.qty AS "returnedQuantity",
                       count(*) OVER () AS failures_total
                  FROM s JOIN iss ON iss.sid = s.id JOIN back ON back.oid = iss.lid
                  LEFT JOIN "Product" p ON p.id = s."productId"
                 WHERE back.qty >= 1 - :tol
                 ORDER BY p.name, s."serialNumber\"""".formatted(locS), params(loc, QTY_TOLERANCE));
        return check(ISSUED_SERIAL_LOCATED, "an issued serial's issuing ledger row is not reversed (a serial whose unit"
                + " came back is in stock, not issued)", QTY_TOLERANCE, f, null);
    }

    /**
     * Per ledger row with reversals: |Σ net reversing quantity| ≤ |its quantity|. Net = each reversing row
     * ({@code reversesLedgerId} = the row) plus the rows that reverse it in turn (a credit-note cancel takes back the
     * credit-note restock). Covers issues returned more than issued and receipts reversed more than received.
     */
    private IntegrityCheck overReturned(String loc) {
        String locO = loc == null ? "" : " AND o.\"locationId\" = :loc";
        Found f = find("""
                WITH r AS (SELECT r.id, r."reversesLedgerId" AS oid, r."quantityChange" AS q
                             FROM "InventoryLedger" r
                            WHERE r."organizationId" = :org AND r."reversesLedgerId" IS NOT NULL
                              AND r."quantityChange" <> 0
                              -- IPR 1b (count spec 3.1): a late count correction may exceed its variance row by design
                              AND COALESCE(r."referenceType", '') <> 'inventory_count_late_correction'),
                     rr AS (SELECT oid, SUM(q) AS q FROM r GROUP BY oid),
                     net AS (SELECT r.oid, SUM(r.q + COALESCE(rr.q, 0)) AS back
                               FROM r LEFT JOIN rr ON rr.oid = r.id GROUP BY r.oid)
                SELECT o.id AS "ledgerId", o."ledgerCode", o."productId", o."variantId", o."locationId", p.code,
                       p.name, o.type::text AS type, o."referenceType", o."referenceId",
                       o."quantityChange" AS quantity, net.back AS "reversedQuantity",
                       abs(net.back) - abs(o."quantityChange") AS excess, count(*) OVER () AS failures_total
                  FROM net JOIN "InventoryLedger" o ON o.id = net.oid AND o."organizationId" = :org
                  LEFT JOIN "Product" p ON p.id = o."productId"
                 WHERE o."quantityChange" <> 0 AND sign(net.back) = -sign(o."quantityChange")
                   AND abs(net.back) > abs(o."quantityChange") + :tol%s
                 ORDER BY abs(net.back) - abs(o."quantityChange") DESC, o."ledgerCode\"""".formatted(locO),
                params(loc, QTY_TOLERANCE));
        return check(OVER_RETURNED, "per ledger row: |Σ net reversing quantity (reversesLedgerId)| ≤ |quantity|",
                QTY_TOLERANCE, f, null);
    }

    /**
     * Undo / restock rows without {@code reversesLedgerId}, per source type and direction (all of them listed, history
     * included). IPR 1d: a source type is linked once it has written a linked row; from then on an unlinked row of that
     * type written after its first linked one ({@code afterLinked}) is a regression and fails the check. Rows written
     * before (history, never repaired) and source types still unlinked (deferred: vendor-bill cancel, production
     * reversal) are listed only. Self-calibrating: no cut-off date to configure.
     */
    private IntegrityCheck unlinkedRestocks(String loc) {
        String locL = loc == null ? "" : " AND l.\"locationId\" = :loc";
        String in = types(1);
        String out = types(-1);
        String both = types(0);
        Found f = find("""
                WITH firsts AS (SELECT x."referenceType" AS rt, MIN(x."createdAt") AS at FROM "InventoryLedger" x
                                 WHERE x."organizationId" = :org AND x."reversesLedgerId" IS NOT NULL
                                   AND x."referenceType" IN (%s, %s, %s, 'fleet_work_order')
                                 GROUP BY 1)
                SELECT l."referenceType", CASE WHEN l."quantityChange" > 0 THEN 'inflow' ELSE 'outflow' END AS direction,
                       count(*) AS rows, SUM(abs(l."quantityChange")) AS quantity, MIN(l."createdAt") AS "firstAt",
                       MAX(l."createdAt") AS "lastAt", MIN(fi.at) AS "linkedSince",
                       count(*) FILTER (WHERE fi.at IS NOT NULL AND l."createdAt" > fi.at) AS "afterLinked",
                       count(*) OVER () AS failures_total
                  FROM "InventoryLedger" l LEFT JOIN firsts fi ON fi.rt = l."referenceType"
                 WHERE l."organizationId" = :org AND l."reversesLedgerId" IS NULL AND l."quantityChange" <> 0
                   AND ((l."referenceType" IN (%s) AND l."quantityChange" > 0)
                     OR (l."referenceType" IN (%s) AND l."quantityChange" < 0)
                     OR l."referenceType" IN (%s)
                     OR (l."referenceType" = 'fleet_work_order' AND l.type::text = 'return_in'))%s
                 GROUP BY 1, 2
                 ORDER BY count(*) DESC, 1, 2""".formatted(in, out, both, in, out, both, locL), params(loc, null));
        long rows = f.rows().stream().mapToLong(r -> ((Number) r.get("rows")).longValue()).sum();
        long after = f.rows().stream().mapToLong(r -> ((Number) r.get("afterLinked")).longValue()).sum();
        return new IntegrityCheck(UNLINKED_RESTOCKS, after == 0, false, "restock / undo rows (by source type) link the"
                + " movement they undo (reversesLedgerId); an unlinked row written after its source type's first linked"
                + " row fails", null, after, f.total() > f.rows().size(), f.rows(), rows + " unlinked row(s) in "
                + f.total() + " (source type, direction) group(s); " + after + " written after their type was linked");
    }

    private static String types(int direction) {
        return RESTOCK_SOURCES.entrySet().stream().filter(e -> e.getValue() == direction).map(e -> "'" + e.getKey() + "'")
                .sorted().reduce((a, b) -> a + ", " + b).orElse("''");
    }

    /**
     * Per inventory control account, in base currency: the current valuation of the products whose receipts debit it
     * ({@link StockPostingService#stockAccount}; CM-1 price-difference and NRV components on their mapped accounts) =
     * Σ (baseDebit − baseCredit) of its non-draft journal lines dated up to today. Products are resolved once per
     * (category, account override, product-specific rule) group. Organisation level.
     */
    private IntegrityCheck glTieOutByAccount(String loc) {
        String invariant = "per inventory control account: valuation of its products = Σ (baseDebit − baseCredit) of its"
                + " journal lines";
        BigDecimal tolerance = tieOutTolerance();
        if (loc != null) {
            return orgLevelSkipped(GL_TIEOUT_BY_ACCOUNT, invariant, tolerance);
        }
        String org = org();
        List<String> accounts = valuation.inventoryAccountIds();
        if (accounts.isEmpty()) {
            return new IntegrityCheck(GL_TIEOUT_BY_ACCOUNT, true, true, invariant, tolerance, 0, false, List.of(),
                    "No inventory control account is mapped");
        }
        Map<String, BigDecimal> productValue = new HashMap<>();
        // IPR S13 (opt-in): the value of stock at transit locations sits in the goods-in-transit account.
        String transitAccount = valuation.transitAccountId();
        Set<String> transitLocations = transitAccount == null ? Set.of() : new HashSet<>(jdbc.sql("SELECT id FROM"
                + " \"InventoryLocation\" WHERE \"organizationId\" = ? AND type::text = 'transit'").param(org)
                .query(String.class).list());
        Map<String, BigDecimal> transitValue = new LinkedHashMap<>();
        for (Map<String, Object> q : valuation.currentValueByQuant(null)) {
            if (transitLocations.contains((String) q.get("location_id"))) {
                transitValue.merge(transitAccount, SqlRows.dec(q, "value"), BigDecimal::add);
                continue;
            }
            productValue.merge((String) q.get("product_id"), SqlRows.dec(q, "value"), BigDecimal::add);
        }
        Map<String, String> mapped = new HashMap<>();
        SqlRows.list(jdbc.sql("SELECT key, \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key IN"
                + " ('inventory_price_difference', 'inventory_nrv_allowance')").param(org))
                .forEach(r -> mapped.put((String) r.get("key"), (String) r.get("accountId")));
        Map<String, BigDecimal> componentByAccountKey = new HashMap<>();
        Map<String, BigDecimal> componentByProduct = new HashMap<>();
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT \"productId\" AS product_id, \"valuationComponent\""
                + " AS component, SUM(\"totalValue\") AS value FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"valuationComponent\" IS NOT NULL GROUP BY 1, 2").param(org))) {
            String account = mapped.get("inventory_" + c.get("component"));
            BigDecimal v = SqlRows.dec(c, "value");
            if (account != null) {
                componentByAccountKey.merge(account, v, BigDecimal::add);
                componentByProduct.merge((String) c.get("product_id"), v, BigDecimal::add);
            }
        }
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        componentByAccountKey.forEach((a, v) -> byAccount.merge(a, v, BigDecimal::add));
        transitValue.forEach((a, v) -> byAccount.merge(a, v, BigDecimal::add));
        List<String> ids = productValue.entrySet().stream().filter(e -> e.getValue()
                .subtract(componentByProduct.getOrDefault(e.getKey(), BigDecimal.ZERO)).signum() != 0)
                .map(Map.Entry::getKey).toList();
        Map<String, String> groupAccount = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 5000) {
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("""
                    SELECT p.id, COALESCE(p."categoryId", '') || '|' || COALESCE(p."inventoryAccountOverrideId", '')
                           || '|' || CASE WHEN EXISTS (SELECT 1 FROM "InventoryPostingRule" r
                                                        WHERE r."organizationId" = :org AND r."productId" = p.id
                                                          AND r."movementType"::text = 'STOCK_IN')
                                          THEN p.id ELSE '' END AS grp
                      FROM "Product" p WHERE p."organizationId" = :org AND p.id IN (:ids)""").param("org", org)
                    .param("ids", ids.subList(i, Math.min(ids.size(), i + 5000))))) {
                String id = (String) p.get("id");
                String account = groupAccount.computeIfAbsent((String) p.get("grp"), g -> {
                    try {
                        return stockPosting.stockAccount(id, BigDecimal.ONE);
                    } catch (RuntimeException e) {
                        return "(unresolved)";
                    }
                });
                byAccount.merge(account, productValue.get(id).subtract(componentByProduct.getOrDefault(id,
                        BigDecimal.ZERO)), BigDecimal::add);
            }
        }
        Instant now = Instant.now();
        Instant endOfToday = java.time.LocalDate.now(OrgDates.orgTimezone(jdbc, org)).atTime(23, 59, 59, 999_000_000)
                .toInstant(java.time.ZoneOffset.UTC);
        Map<String, BigDecimal> gl = new LinkedHashMap<>();
        SqlRows.list(jdbc.sql("SELECT jl.\"accountId\" AS account_id, SUM(COALESCE(jl.\"baseDebit\", 0)"
                        + " - COALESCE(jl.\"baseCredit\", 0)) AS amount FROM \"JournalLine\" jl JOIN \"JournalEntry\" e"
                        + " ON e.id = jl.\"journalEntryId\" WHERE e.\"organizationId\" = :org AND e.status <> 'draft'"
                        + " AND e.\"postingDate\" <= :asOf AND jl.\"accountId\" IN (:accts) GROUP BY 1")
                .param("org", org).param("asOf", SqlRows.ts(endOfToday.isAfter(now) ? endOfToday : now))
                .param("accts", accounts)).forEach(r -> gl.put((String) r.get("account_id"), SqlRows.dec(r, "amount")));
        Set<String> keys = new LinkedHashSet<>(accounts);
        keys.addAll(byAccount.keySet());
        Map<String, Map<String, Object>> names = new HashMap<>();
        SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Account\" WHERE id IN (:ids)").param("ids", List.copyOf(keys)))
                .forEach(a -> names.put((String) a.get("id"), a));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String a : keys) {
            BigDecimal v = byAccount.getOrDefault(a, BigDecimal.ZERO);
            BigDecimal g = gl.getOrDefault(a, BigDecimal.ZERO);
            if (v.subtract(g).abs().compareTo(tolerance) > 0) {
                Map<String, Object> n = names.getOrDefault(a, Map.of());
                rows.add(render(Obj.of("accountId", a, "code", n.get("code"), "name", n.get("name"), "valuationValue", v,
                        "glBaseValue", g, "difference", v.subtract(g))));
            }
        }
        return check(GL_TIEOUT_BY_ACCOUNT, invariant, tolerance, new Found(rows.size(), rows),
                "base currency (baseDebit/baseCredit); " + byAccount.size() + " account(s) hold valued stock");
    }

    /** Failed stock-posting jobs, and pending / processing ones older than {@link #STOCK_JOB_MAX_AGE}. */
    private IntegrityCheck stockPostingJobs(String loc) {
        String invariant = "no failed stock-posting job; none pending or processing for more than "
                + STOCK_JOB_MAX_AGE.toHours() + " h (abandoned jobs excluded)";
        if (loc != null) {
            return orgLevelSkipped(STOCK_POSTING_JOBS, invariant, null);
        }
        Map<String, Object> p = params(null, null);
        p.put("before", SqlRows.ts(Instant.now().minus(STOCK_JOB_MAX_AGE)));
        Found f = find("""
                SELECT j.id AS "jobId", j."invoiceId", j."invoiceNumber", j."orderId", j.status, j.attempts,
                       left(j."lastError", 300) AS "lastError", j."createdAt", j."nextRetryAt",
                       count(*) OVER () AS failures_total
                  FROM "StockPostingJob" j
                 WHERE j."organizationId" = :org AND (j.status = 'failed'
                    OR (j.status IN ('pending', 'processing') AND j."createdAt" < :before))
                 ORDER BY j."createdAt", j.id""", p);
        return check(STOCK_POSTING_JOBS, invariant, null, f, null);
    }

    /** Open transit transfers dispatched more than {@link #TRANSIT_MAX_AGE} ago. */
    private IntegrityCheck transitAge(String loc) {
        String locT = loc == null ? "" : " AND (t.\"fromLocId\" = :loc OR t.\"toLocId\" = :loc)";
        Map<String, Object> p = params(loc, null);
        Instant now = Instant.now();
        p.put("now", SqlRows.ts(now));
        p.put("before", SqlRows.ts(now.minus(TRANSIT_MAX_AGE)));
        Found f = find("""
                SELECT t.id AS "transferId", t."transferCode", t.status::text AS status, t."fromLocId", t."toLocId",
                       t."transitLocId", t."dispatchedAt",
                       floor(extract(epoch FROM (CAST(:now AS timestamp) - COALESCE(t."dispatchedAt", t."updatedAt")))
                             / 86400)::int AS "ageDays", count(*) OVER () AS failures_total
                  FROM "StockTransfer" t
                 WHERE t."organizationId" = :org AND t.mode = 'transit'
                   AND t.status::text IN ('in_transit', 'partially_received')
                   AND COALESCE(t."dispatchedAt", t."updatedAt") < :before%s
                 ORDER BY COALESCE(t."dispatchedAt", t."updatedAt"), t."transferCode\"""".formatted(locT), p);
        return check(TRANSIT_AGE, "no transit transfer open (in transit / partially received) for more than "
                + TRANSIT_MAX_AGE.toDays() + " days", null, f, null);
    }

    /**
     * F1 detector. A posted sales invoice whose stock issue was handed to a delivery ({@code data.stockIssuedBy}; cash
     * sales excluded), or that is awaiting delivery after its delivery order was cancelled or closed short (IPR F1,
     * {@code data.stockAwaitingDelivery}: listed until a new delivery order or a collection issues the goods), whose
     * delivery orders are all finished (none open, planned or in progress) with at least one
     * cancelled or closed, and, per product (base units), invoiced − credited (posted credit notes of the invoice) >
     * delivered (net {@code fleet_trip} issues − {@code fleet_trip_return} put-backs of the loads of those orders, plus
     * any {@code sales_invoice} issue of its own). The delivery orders of an invoice are found as
     * {@code CreditNoteService.invoiceIssueSource} finds them: from the invoice, allocated to it, or of its sales order
     * (shared by every invoice of the order, so an order invoiced in parts is judged per invoice against the whole
     * order's deliveries).
     */
    private IntegrityCheck invoiceUndelivered(String loc) {
        String invariant = "a posted invoice whose delivery orders are cancelled or closed has its invoiced quantity"
                + " issued (net of credit notes)";
        String locG = loc == null ? "" : " AND g.\"locationId\" = :loc";
        Found f = find("""
                WITH inv AS (SELECT d.id, d."documentNumber", d.status::text AS status, d."sourceType", d."sourceId",
                                    d.data->>'stockIssuedBy' AS issued_by
                               FROM "Document" d
                              WHERE d."organizationId" = :org AND d."documentType" = 'sales_invoice'
                                AND d.status::text NOT IN ('draft', 'cancelled')
                                AND (d.data->>'stockIssuedBy' IS NOT NULL
                                     OR d.data->>'stockAwaitingDelivery' IS NOT NULL)
                                AND d."sourceType" IS DISTINCT FROM 'cash_sale'),
                     dos AS (SELECT inv.id AS iid, o.id AS oid, o.status FROM inv JOIN "FleetDeliveryOrder" o
                                 ON o."organizationId" = :org AND o."sourceType" = 'invoice' AND o."sourceId" = inv.id
                             UNION
                             SELECT inv.id, o.id, o.status FROM inv JOIN "FleetDeliveryOrder" o
                                 ON o."organizationId" = :org AND o."goodsInvoiceId" = inv.id
                             UNION
                             SELECT inv.id, o.id, o.status FROM inv JOIN "FleetDeliveryOrder" o
                                 ON o."organizationId" = :org AND inv."sourceType" = 'sales_order'
                                AND o."sourceType" = 'sales_order' AND o."sourceId" = inv."sourceId"
                             UNION
                             SELECT inv.id, o.id, o.status FROM inv JOIN "FleetDeliveryInvoiceLine" fil
                                 ON fil."organizationId" = :org AND fil."documentId" = inv.id
                               JOIN "FleetDeliveryOrder" o ON o.id = fil."deliveryOrderId"),
                     judged AS (SELECT iid, string_agg(DISTINCT status, ',') AS statuses FROM dos GROUP BY iid
                                HAVING bool_and(status NOT IN ('open', 'planned', 'in_progress'))
                                   AND bool_or(status IN ('cancelled', 'closed'))),
                     trips AS (SELECT DISTINCT dos.iid, l.id AS tid FROM dos JOIN judged ON judged.iid = dos.iid
                                 JOIN "FleetLoad" l ON l."deliveryOrderId" = dos.oid AND l."organizationId" = :org),
                     moved AS (SELECT trips.iid, g."productId" AS pid,
                                      SUM(CASE WHEN g."referenceType" = 'fleet_trip' THEN -g."quantityChange"
                                               ELSE 0 END) AS issued,
                                      SUM(CASE WHEN g."referenceType" = 'fleet_trip_return' THEN g."quantityChange"
                                               ELSE 0 END) AS put_back
                                 FROM trips JOIN "InventoryLedger" g ON g."organizationId" = :org
                                      AND g."referenceType" IN ('fleet_trip', 'fleet_trip_return')
                                      AND g."referenceId" = trips.tid
                                WHERE true%s
                                GROUP BY 1, 2),
                     own AS (SELECT judged.iid, g."productId" AS pid, -SUM(g."quantityChange") AS issued
                               FROM judged JOIN "InventoryLedger" g ON g."organizationId" = :org
                                    AND g."referenceType" = 'sales_invoice' AND g."referenceId" = judged.iid
                              GROUP BY 1, 2),
                     lines AS (SELECT dl."documentId" AS iid, dl."productId" AS pid,
                                      SUM(dl.quantity * COALESCE(NULLIF(dl."uomFactor", 0), 1)) AS invoiced
                                 FROM judged JOIN "DocumentLine" dl ON dl."documentId" = judged.iid
                                 JOIN "Product" p ON p.id = dl."productId" AND p."trackInventory" = true
                                      AND p."productType"::text IN ('stockable', 'consumable')
                                WHERE dl."organizationId" = :org
                                GROUP BY 1, 2),
                     credited AS (SELECT c."reversedDocumentId" AS iid, cl."productId" AS pid,
                                         SUM(cl.quantity * COALESCE(NULLIF(cl."uomFactor", 0), 1)) AS qty
                                    FROM judged JOIN "Document" c ON c."organizationId" = :org
                                         AND c."reversedDocumentId" = judged.iid AND c."documentType" = 'credit_note'
                                         AND c.status::text NOT IN ('draft', 'cancelled')
                                    JOIN "DocumentLine" cl ON cl."documentId" = c.id
                                   GROUP BY 1, 2)
                SELECT inv.id AS "invoiceId", inv."documentNumber", inv.status AS "invoiceStatus",
                       inv.issued_by AS "stockIssuedBy", inv."sourceType", judged.statuses AS "deliveryOrderStatuses",
                       lines.pid AS "productId", p.code, p.name, lines.invoiced AS "invoicedQuantity",
                       COALESCE(credited.qty, 0) AS "creditedQuantity",
                       COALESCE(moved.issued, 0) - COALESCE(moved.put_back, 0) + COALESCE(own.issued, 0)
                           AS "deliveredQuantity",
                       lines.invoiced - COALESCE(credited.qty, 0) - (COALESCE(moved.issued, 0)
                           - COALESCE(moved.put_back, 0) + COALESCE(own.issued, 0)) AS "undeliveredQuantity",
                       count(*) OVER () AS failures_total
                  FROM lines JOIN inv ON inv.id = lines.iid JOIN judged ON judged.iid = lines.iid
                  LEFT JOIN moved ON moved.iid = lines.iid AND moved.pid = lines.pid
                  LEFT JOIN own ON own.iid = lines.iid AND own.pid = lines.pid
                  LEFT JOIN credited ON credited.iid = lines.iid AND credited.pid = lines.pid
                  LEFT JOIN "Product" p ON p.id = lines.pid
                 WHERE lines.invoiced - COALESCE(credited.qty, 0) - (COALESCE(moved.issued, 0)
                       - COALESCE(moved.put_back, 0) + COALESCE(own.issued, 0)) > :tol
                 ORDER BY inv."documentNumber", p.name""".formatted(locG), params(loc, QTY_TOLERANCE));
        return check(INVOICE_UNDELIVERED, invariant, QTY_TOLERANCE, f, loc == null ? null
                : "Location filter applies to the delivery movements only");
    }

    /**
     * F3 detector. Per delivery group (the sales order, else the invoice, else the delivery order of dispatch-mode
     * delivery orders) and product: put-backs ({@code fleet_trip_return}, written without {@code reversesLedgerId}
     * before IPR F3; a linked put-back counts once, with the linked returns) plus returns linked to the trips' issue rows (credit-note restock, invoice cancel, sales return; net of their own
     * reversals) ≤ the quantity the trips issued.
     */
    private IntegrityCheck deliveryReturnOverlap(String loc) {
        String locI = loc == null ? "" : " AND g.\"locationId\" = :loc";
        Found f = find("""
                WITH o AS (SELECT o.id, CASE WHEN o."sourceType" = 'sales_order' THEN 'sales_order:' || o."sourceId"
                                             WHEN o."goodsInvoiceId" IS NOT NULL THEN 'invoice:' || o."goodsInvoiceId"
                                             WHEN o."sourceType" = 'invoice' THEN 'invoice:' || o."sourceId"
                                             ELSE 'delivery_order:' || o.id END AS grp
                             FROM "FleetDeliveryOrder" o
                            WHERE o."organizationId" = :org AND o."stockMode" = 'dispatch'),
                     trips AS (SELECT o.grp, l.id AS tid FROM o JOIN "FleetLoad" l ON l."deliveryOrderId" = o.id
                                    AND l."organizationId" = :org
                                WHERE l."stockIssuedAt" IS NOT NULL),
                     iss AS (SELECT trips.grp, g.id, g."productId" AS pid, g."quantityChange" AS q
                               FROM trips JOIN "InventoryLedger" g ON g."organizationId" = :org
                                    AND g."referenceType" = 'fleet_trip' AND g."referenceId" = trips.tid
                              WHERE g."quantityChange" < 0%s),
                     issued AS (SELECT grp, pid, -SUM(q) AS qty FROM iss GROUP BY 1, 2),
                     put_back AS (SELECT trips.grp, g."productId" AS pid, SUM(g."quantityChange") AS qty
                                    FROM trips JOIN "InventoryLedger" g ON g."organizationId" = :org
                                         AND g."referenceType" = 'fleet_trip_return' AND g."referenceId" = trips.tid
                                         AND g."reversesLedgerId" IS NULL
                                   GROUP BY 1, 2),
                     linked AS (SELECT iss.grp, iss.pid, SUM(r."quantityChange" + COALESCE(x.q, 0)) AS qty
                                  FROM iss JOIN "InventoryLedger" r ON r."organizationId" = :org
                                       AND r."reversesLedgerId" = iss.id
                                  LEFT JOIN LATERAL (SELECT SUM(y."quantityChange") AS q FROM "InventoryLedger" y
                                                      WHERE y."organizationId" = :org AND y."reversesLedgerId" = r.id) x
                                       ON true
                                 GROUP BY 1, 2)
                SELECT issued.grp AS "deliveryGroup", issued.pid AS "productId", p.code, p.name,
                       issued.qty AS "issuedQuantity", COALESCE(put_back.qty, 0) AS "putBackQuantity",
                       COALESCE(linked.qty, 0) AS "linkedReturnQuantity",
                       COALESCE(put_back.qty, 0) + COALESCE(linked.qty, 0) - issued.qty AS excess,
                       count(*) OVER () AS failures_total
                  FROM issued LEFT JOIN put_back ON put_back.grp = issued.grp AND put_back.pid = issued.pid
                  LEFT JOIN linked ON linked.grp = issued.grp AND linked.pid = issued.pid
                  LEFT JOIN "Product" p ON p.id = issued.pid
                 WHERE COALESCE(put_back.qty, 0) + COALESCE(linked.qty, 0) > issued.qty + :tol
                 ORDER BY 1, p.name""".formatted(locI), params(loc, QTY_TOLERANCE));
        return check(DELIVERY_RETURN_OVERLAP, "per delivery group and product: put-backs + linked returns ≤ quantity"
                + " the trips issued", QTY_TOLERANCE, f, null);
    }

    private WipTieOutProvider wip;

    /** IPR GL-11: the manufacturing module's WIP tie-out (absent without it). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWipTieOut(WipTieOutProvider wip) {
        this.wip = wip;
    }

    /** IPR GL-11 / R4: the WIP tie-out per cost object (organisation level; manufacturing implements it). */
    private IntegrityCheck wipTieOut(String loc) {
        String invariant = "per production order / process / late cost: GL WIP (base currency) = its sub-ledger";
        if (loc != null) {
            return orgLevelSkipped(WIP_TIEOUT, invariant, VALUE_TOLERANCE);
        }
        if (wip == null) {
            return new IntegrityCheck(WIP_TIEOUT, true, true, invariant, VALUE_TOLERANCE, 0, false, List.of(),
                    "Manufacturing is not part of this build");
        }
        return wip.wipTieOut();
    }

    /** Quality-module lots on hold (stock under quality control) whose stock was never moved to quarantine. */
    private IntegrityCheck qualityHoldUnquarantined(String loc) {
        String locQ = loc == null ? "" : " AND q.\"locationId\" = :loc";
        Found f = find("""
                SELECT q.id AS "lotId", q."lotNumber", q."productId", q."variantId", q."batchNumber", q."locationId",
                       l.name AS "locationName", p.code, p.name,
                       q.quantity - q."releasedQty" - q."rejectedQty" - q."downgradedQty" AS "openQuantity",
                       COALESCE(si.qty, 0) AS "onHandAtLocation", count(*) OVER () AS failures_total
                  FROM "QualityInspectionLot" q
                  LEFT JOIN "InventoryLocation" l ON l.id = q."locationId"
                  LEFT JOIN "Product" p ON p.id = q."productId"
                  LEFT JOIN LATERAL (SELECT SUM(s.quantity) AS qty FROM "StockItem" s
                                      WHERE s."organizationId" = :org AND s."productId" = q."productId"
                                        AND s."locationId" = q."locationId"
                                        AND s."variantKey" = COALESCE(q."variantId", '')) si ON true
                 WHERE q."organizationId" = :org AND q.status = 'hold' AND q."stockControl" = 'quality'
                   AND q."quarantineLocationId" IS NULL AND q."locationId" IS NOT NULL
                   AND COALESCE(l.code, '') <> '%s'
                   AND q.quantity - q."releasedQty" - q."rejectedQty" - q."downgradedQty" > :tol%s
                 ORDER BY q."lotNumber\"""".formatted(VirtualLocationService.QC_QUARANTINE, locQ),
                params(loc, QTY_TOLERANCE));
        return check(QUALITY_HOLD_UNQUARANTINED, "a quality lot on hold has its open quantity in QC quarantine",
                QTY_TOLERANCE, f, null);
    }

    /**
     * Informational (R3): ledger rows of the last {@link #PERIOD_SHIFT_LOOKBACK} whose inventory journal is dated in
     * another period than the movement happened (IPR 1b / ADR-S088: its effective time, else createdAt for rows before
     * V700, in the organisation's zone): effective period vs posting period. The journal is matched as
     * the GL tie-out dates rows (ledger code, else reference id, when that source's entries fall on one day). A period
     * is the shortest fiscal period holding the date, else its calendar month. Grouped by source type and period pair.
     */
    private IntegrityCheck periodShifted(String loc) {
        String locL = loc == null ? "" : " AND l.\"locationId\" = :loc";
        Map<String, Object> p = params(loc, null);
        p.put("since", SqlRows.ts(Instant.now().minus(PERIOD_SHIFT_LOOKBACK)));
        p.put("tz", OrgDates.orgTimezone(jdbc, org()).getId());
        Found f = find("""
                WITH je AS (SELECT e."sourceId" AS sid, MIN(e."postingDate") AS pd, MAX(e."postingDate") AS pd_max
                              FROM "JournalEntry" e
                             WHERE e."organizationId" = :org AND e.status <> 'draft' AND e."sourceId" IS NOT NULL
                               AND e."createdAt" >= CAST(:since AS timestamp) - interval '1 day'
                             GROUP BY 1),
                     x AS (SELECT l."referenceType",
                                  ((COALESCE(l."effectiveAt", l."createdAt") AT TIME ZONE 'UTC') AT TIME ZONE :tz)::date
                                      AS moved_on,
                                  COALESCE(CASE WHEN date_trunc('day', jc.pd) = date_trunc('day', jc.pd_max) THEN jc.pd END,
                                           CASE WHEN date_trunc('day', jr.pd) = date_trunc('day', jr.pd_max) THEN jr.pd END
                                  )::date AS posted_on
                             FROM "InventoryLedger" l
                             LEFT JOIN je jc ON jc.sid = l."ledgerCode"
                             LEFT JOIN je jr ON jr.sid = l."referenceId"
                            WHERE l."organizationId" = :org AND l."createdAt" >= :since
                              AND l."valuationComponent" IS NULL%s),
                     d AS (SELECT "referenceType", moved_on, posted_on, count(*) AS n FROM x
                            WHERE posted_on IS NOT NULL AND posted_on <> moved_on GROUP BY 1, 2, 3),
                     dated AS (SELECT d.*, COALESCE(mp.name, to_char(d.moved_on, 'YYYY-MM')) AS moved_period,
                                      COALESCE(pp.name, to_char(d.posted_on, 'YYYY-MM')) AS posted_period
                                 FROM d
                                 LEFT JOIN LATERAL (SELECT fp.name FROM "FiscalPeriod" fp
                                                     WHERE fp."organizationId" = :org
                                                       AND fp."startDate"::date <= d.moved_on
                                                       AND fp."endDate"::date >= d.moved_on
                                                     ORDER BY fp."endDate" - fp."startDate", fp.id LIMIT 1) mp ON true
                                 LEFT JOIN LATERAL (SELECT fp.name FROM "FiscalPeriod" fp
                                                     WHERE fp."organizationId" = :org
                                                       AND fp."startDate"::date <= d.posted_on
                                                       AND fp."endDate"::date >= d.posted_on
                                                     ORDER BY fp."endDate" - fp."startDate", fp.id LIMIT 1) pp ON true)
                SELECT "referenceType", moved_period AS "movedPeriod", posted_period AS "postedPeriod",
                       SUM(n) AS rows, MIN(moved_on) AS "firstMovedOn", MAX(moved_on) AS "lastMovedOn",
                       count(*) OVER () AS failures_total
                  FROM dated WHERE moved_period <> posted_period
                 GROUP BY 1, 2, 3
                 ORDER BY SUM(n) DESC, 1, 2, 3""".formatted(locL), p);
        long rows = f.rows().stream().mapToLong(r -> new BigDecimal(String.valueOf(r.get("rows"))).longValue()).sum();
        return informational(PERIOD_SHIFTED, "informational: movements whose journal posting date is in another period"
                + " than the movement (last " + PERIOD_SHIFT_LOOKBACK.toDays() + " days)", f, rows
                + " shifted row(s) in " + f.total() + " (source type, period pair) group(s)");
    }

    /**
     * IPR R6: the deferred P2 findings this organisation's features promote to go-live blockers
     * ({@link InventoryFeatureUsageService#P2_FEATURES}). Organisation level.
     */
    private IntegrityCheck featureRisk(String loc) {
        String invariant = "no open deferred P2 finding applies to a feature this organisation uses";
        if (loc != null) {
            return orgLevelSkipped(FEATURE_RISK, invariant, null);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InventoryFeatureUsageService.PromotedP2 p2 : featureUsage.usage().promotedP2s()) {
            rows.add(Obj.of("p2", p2.id(), "title", p2.title(), "features", p2.matchedFeatures()));
        }
        return check(FEATURE_RISK, invariant, null, new Found(rows.size(), rows),
                "See GET /inventory/reports/feature-usage and docs/spec/inventory-feature-risk-map.md");
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
