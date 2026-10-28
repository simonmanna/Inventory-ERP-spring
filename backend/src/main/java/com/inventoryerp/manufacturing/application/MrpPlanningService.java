package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.BomService.ExplodedMaterial;
import com.inventoryerp.manufacturing.application.MfgCommands.CreateOrder;
import com.inventoryerp.manufacturing.domain.MrpPlannedOrder;
import com.inventoryerp.manufacturing.domain.MrpRun;
import com.inventoryerp.manufacturing.domain.MrpRunItem;
import com.inventoryerp.manufacturing.domain.ProductPlanning;
import com.inventoryerp.procurement.domain.PurchaseRequest;
import com.inventoryerp.procurement.domain.PurchaseRequestLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time-phased, multi-level MRP (ADR-S006 package B). A run nets every planned product level by level (low-level
 * codes from the active-BOM graph): gross requirement (open orders / proforma documents, open production requests,
 * open production orders' remaining materials, dependent demand of planned make orders) + safety stock − (on hand −
 * other reservations + open PO remainders + open production output); a shortfall or a breach of the reorder point
 * becomes a lot-sized planned order, due at its earliest demand and released lead-time days earlier. Planned make
 * orders explode into their components' demand at the release date. Planned orders are firmed into draft production
 * orders (make) or draft purchase requests (buy).
 */
@Service
public class MrpPlanningService {

    private static final int MAX_LEVEL = 20;
    private static final int MAX_PEGS = 50;

    /** One requirement for a product. */
    record Demand(BigDecimal qty, Instant date, Map<String, Object> ref) {}

    /** Effective planning parameters of a product (ProductPlanning row or defaults). */
    record Params(String productId, String procurement, int leadTimeDays, BigDecimal safetyStock,
            BigDecimal reorderPoint, BigDecimal minOrderQty, BigDecimal orderMultiple, String supplierId,
            String locationId, boolean configured) {}

    private final MfgDb db;
    private final SequenceService seq;
    private final BomService boms;
    private final ProductionService production;
    private final UomConversionService uom;

    public MrpPlanningService(MfgDb db, SequenceService seq, BomService boms, ProductionService production,
            UomConversionService uom) {
        this.db = db;
        this.seq = seq;
        this.boms = boms;
        this.production = production;
        this.uom = uom;
    }

    // ================================================================== planning parameters

    /** Configured parameter rows (optionally one product), with the product's code and name. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listParams(String productId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : db.list(ProductPlanning.class, productId != null ? " AND \"productId\" = ?"
                + " ORDER BY \"createdAt\" ASC" : " ORDER BY \"createdAt\" ASC",
                productId != null ? new Object[] {productId} : new Object[0])) {
            p.put("product", SqlRows.one(db.jdbc().sql("SELECT id, code, name FROM \"Product\" WHERE id = ?")
                    .param(p.get("productId"))));
            out.add(p);
        }
        return out;
    }

    /** Create or replace the parameters of one product. */
    @Transactional
    public Map<String, Object> upsertParams(String productId, MfgCommands.PlanningParams dto) {
        if (db.jdbc().sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(productId, MfgDb.org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Product not found");
        }
        if (dto.locationId() != null) {
            production.requireLocation(dto.locationId());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("procurement", dto.procurement() != null ? dto.procurement() : "buy");
        data.put("leadTimeDays", dto.leadTimeDays() != null ? dto.leadTimeDays() : 0);
        data.put("safetyStock", nz(dto.safetyStock()));
        data.put("reorderPoint", nz(dto.reorderPoint()));
        data.put("minOrderQty", nz(dto.minOrderQty()));
        data.put("orderMultiple", nz(dto.orderMultiple()));
        data.put("supplierId", dto.supplierId());
        data.put("locationId", dto.locationId());
        data.put("isActive", dto.isActive() != null ? dto.isActive() : true);
        List<Map<String, Object>> existing = db.list(ProductPlanning.class, " AND \"productId\" = ?", productId);
        if (existing.isEmpty()) {
            data.put("productId", productId);
            return db.insert(ProductPlanning.class, data);
        }
        return db.update(ProductPlanning.class, str(existing.getFirst(), "id"), data);
    }

    Params params(String productId) {
        List<Map<String, Object>> rows = db.list(ProductPlanning.class, " AND \"productId\" = ? AND \"isActive\" = true",
                productId);
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.getFirst();
            return new Params(productId, str(r, "procurement"), ((Number) r.get("leadTimeDays")).intValue(),
                    dec(r, "safetyStock"), dec(r, "reorderPoint"), dec(r, "minOrderQty"), dec(r, "orderMultiple"),
                    str(r, "supplierId"), str(r, "locationId"), true);
        }
        // Defaults: products with an active BOM are made, the rest bought; the product's minimum is the reorder point.
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT \"minQuantity\", \"supplierId\" FROM \"Product\""
                + " WHERE id = ?").param(productId));
        boolean make = boms.findActive(productId, "") != null;
        return new Params(productId, make ? "make" : "buy", 0, BigDecimal.ZERO,
                p == null ? BigDecimal.ZERO : dec(p, "minQuantity"), BigDecimal.ZERO, BigDecimal.ZERO,
                p == null ? null : str(p, "supplierId"), null, false);
    }

    // ================================================================== run

    /** Plan the horizon and store the run (worksheet + planned orders); supersedes earlier unfirmed proposals. */
    @Transactional
    public Map<String, Object> run(MfgCommands.MrpRunInput dto) {
        int horizon = dto != null && dto.horizonDays() != null ? dto.horizonDays() : 30;
        String locationId = dto != null ? dto.locationId() : null;
        if (locationId != null) {
            production.requireLocation(locationId);
        }
        Instant now = Instant.now();
        Instant horizonEnd = now.plus(horizon, ChronoUnit.DAYS);

        Map<String, List<Demand>> demand = new LinkedHashMap<>();
        collectIndependentDemand(demand, horizonEnd, now);
        // Reorder-point / safety-stock products are planned even without demand.
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT \"productId\" FROM \"ProductPlanning\" WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = true AND (\"safetyStock\" > 0 OR \"reorderPoint\" > 0)")
                .param(MfgDb.org()))) {
            demand.computeIfAbsent(str(r, "productId"), k -> new ArrayList<>());
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND \"isActive\" = true AND \"minQuantity\" > 0").param(MfgDb.org()))) {
            demand.computeIfAbsent(str(r, "id"), k -> new ArrayList<>());
        }

        Map<String, Integer> llc = lowLevelCodes(demand.keySet());
        String runNumber = seq.next("mrp_run", "MRP-", 5);
        supersedePrevious();
        Map<String, Object> run = db.insert(MrpRun.class, Obj.of("runNumber", runNumber, "status", "completed",
                "horizonDays", horizon, "locationId", locationId, "startedAt", now, "createdBy", TenantContext.userId(),
                "summary", new MfgDb.Json(Map.of())));
        String runId = str(run, "id");

        Set<String> done = new LinkedHashSet<>();
        int planned = 0;
        int make = 0;
        int late = 0;
        for (int level = 0; level <= MAX_LEVEL; level++) {
            List<String> atLevel = new ArrayList<>();
            for (String p : new ArrayList<>(demand.keySet())) {
                if (!done.contains(p) && llc.getOrDefault(p, 0) == level) {
                    atLevel.add(p);
                }
            }
            for (String productId : atLevel) {
                done.add(productId);
                List<Demand> gross = demand.get(productId);
                Params prm = params(productId);
                BigDecimal grossQty = gross.stream().map(Demand::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal onHand = onHand(productId, locationId);
                BigDecimal reserved = reservedByOthers(productId, locationId);
                BigDecimal receipts = openPurchase(productId).add(openProductionOutput(productId));
                BigDecimal projected = onHand.subtract(reserved).add(receipts).subtract(grossQty);
                BigDecimal net = prm.safetyStock().subtract(projected).max(BigDecimal.ZERO);
                if (prm.reorderPoint().signum() > 0 && projected.compareTo(prm.reorderPoint()) < 0) {
                    net = net.max(prm.reorderPoint().subtract(projected));
                }
                BigDecimal qty = net.signum() > 0 ? lotSize(net, prm) : BigDecimal.ZERO;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("runId", runId);
                item.put("productId", productId);
                item.put("lowLevelCode", level);
                item.put("procurement", prm.procurement());
                item.put("grossRequirement", grossQty);
                item.put("onHand", onHand);
                item.put("reserved", reserved);
                item.put("scheduledReceipts", receipts);
                item.put("safetyStock", prm.safetyStock());
                item.put("netRequirement", net);
                item.put("plannedQty", qty);
                db.insert(MrpRunItem.class, item);
                if (qty.signum() <= 0) {
                    continue;
                }
                Instant due = gross.stream().map(Demand::date).min(Comparator.naturalOrder()).orElse(now);
                Instant release = due.minus(prm.leadTimeDays(), ChronoUnit.DAYS);
                Map<String, Object> bom = "make".equals(prm.procurement()) ? boms.findActive(productId, "") : null;
                String kind = bom != null ? "make" : "buy";
                Map<String, Object> po = new LinkedHashMap<>();
                po.put("runId", runId);
                po.put("productId", productId);
                po.put("kind", kind);
                po.put("quantity", qty);
                po.put("bomId", bom == null ? null : bom.get("id"));
                po.put("supplierId", "buy".equals(kind) ? prm.supplierId() : null);
                po.put("locationId", prm.locationId() != null ? prm.locationId() : locationId);
                po.put("dueDate", due);
                po.put("releaseDate", release);
                po.put("lowLevelCode", level);
                po.put("pegging", new MfgDb.Json(gross.stream().limit(MAX_PEGS).map(d -> Obj.merge(
                        new LinkedHashMap<>(d.ref()), Obj.of("qty", JsNumbers.dec(d.qty())))).toList()));
                Map<String, Object> plannedOrder = db.insert(MrpPlannedOrder.class, po);
                planned++;
                if (release.isBefore(now)) {
                    late++;
                }
                if (bom != null) {
                    make++;
                    for (ExplodedMaterial m : BomService.explode(BomService.explodable(bom), qty).materials()) {
                        demand.computeIfAbsent(m.componentProductId(), k -> new ArrayList<>()).add(new Demand(
                                m.qtyPlanned(), release, Obj.of("type", "planned_order", "id", plannedOrder.get("id"),
                                        "productId", productId)));
                        llc.merge(m.componentProductId(), level + 1, Math::max);
                    }
                }
            }
        }
        Map<String, Object> summary = Obj.of("products", done.size(), "plannedOrders", planned, "make", make,
                "buy", planned - make, "late", late);
        db.update(MrpRun.class, runId, Obj.of("summary", new MfgDb.Json(summary), "completedAt", Instant.now()));
        return get(runId);
    }

    /** Runs, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRuns(Integer limit, Integer offset) {
        return db.list(MrpRun.class, " ORDER BY \"startedAt\" DESC" + MfgDb.page(limit, offset));
    }

    /** A run with its worksheet and planned orders. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> run = db.byId(MrpRun.class, id);
        if (run == null) {
            throw ApiException.notFound("MRP run not found");
        }
        run.put("items", db.children(MrpRunItem.class, "runId", id, "\"lowLevelCode\" ASC"));
        run.put("plannedOrders", db.children(MrpPlannedOrder.class, "runId", id,
                "\"lowLevelCode\" ASC, \"releaseDate\" ASC"));
        return run;
    }

    // ================================================================== firm / cancel

    /** Firm one planned order: make → draft production order, buy → draft purchase request. */
    @Transactional
    public Map<String, Object> firm(String plannedOrderId) {
        Map<String, Object> po = requirePlanned(plannedOrderId);
        Map<String, Object> run = db.byId(MrpRun.class, str(po, "runId"));
        if ("make".equals(po.get("kind"))) {
            return firmMake(po, run);
        }
        return firmBuy(List.of(po), run).getFirst();
    }

    /** Firm every (or the listed) planned order of a run; buy orders are grouped per supplier. */
    @Transactional
    public Map<String, Object> firmRun(String runId, List<String> ids) {
        Map<String, Object> run = db.byId(MrpRun.class, runId);
        if (run == null) {
            throw ApiException.notFound("MRP run not found");
        }
        List<Map<String, Object>> firmed = new ArrayList<>();
        Map<String, List<Map<String, Object>>> buys = new LinkedHashMap<>();
        for (Map<String, Object> po : db.children(MrpPlannedOrder.class, "runId", runId, "\"lowLevelCode\" ASC")) {
            if (!"planned".equals(po.get("status")) || (ids != null && !ids.contains(str(po, "id")))) {
                continue;
            }
            if ("make".equals(po.get("kind"))) {
                firmed.add(firmMake(po, run));
            } else {
                buys.computeIfAbsent(po.get("supplierId") == null ? "" : str(po, "supplierId"),
                        k -> new ArrayList<>()).add(po);
            }
        }
        for (List<Map<String, Object>> group : buys.values()) {
            firmed.addAll(firmBuy(group, run));
        }
        return Obj.of("firmed", firmed.size(), "plannedOrders", firmed);
    }

    @Transactional
    public Map<String, Object> cancel(String plannedOrderId) {
        requirePlanned(plannedOrderId);
        return db.update(MrpPlannedOrder.class, plannedOrderId, Obj.of("status", "cancelled"));
    }

    private Map<String, Object> firmMake(Map<String, Object> po, Map<String, Object> run) {
        Instant release = (Instant) po.get("releaseDate");
        Instant now = Instant.now();
        Map<String, Object> order = production.createOrder(new CreateOrder(str(po, "bomId"), str(po, "productId"), null,
                str(po, "locationId"), null, null, dec(po, "quantity"),
                (release.isBefore(now) ? now : release).toString(), null, null, null,
                "MRP " + run.get("runNumber"), null, null));
        return db.update(MrpPlannedOrder.class, str(po, "id"), Obj.of("status", "firmed", "firmedDocType",
                "production_order", "firmedDocId", order.get("id")));
    }

    private List<Map<String, Object>> firmBuy(List<Map<String, Object>> group, Map<String, Object> run) {
        Instant neededBy = group.stream().map(p -> (Instant) p.get("dueDate")).min(Comparator.naturalOrder())
                .orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestNumber", seq.next("purchase_request", "MRPPR-", 5));
        data.put("requestedById", TenantContext.userId());
        data.put("partnerId", group.getFirst().get("supplierId"));
        data.put("description", "MRP " + run.get("runNumber"));
        data.put("neededBy", neededBy);
        data.put("status", "draft");
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> pr = db.insert(PurchaseRequest.class, data);
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> po : group) {
            Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT \"uomId\", \"purchaseUomId\" FROM"
                    + " \"Product\" WHERE id = ?").param(po.get("productId")));
            BigDecimal qty = dec(po, "quantity");
            Object unit = product == null ? null : product.get("uomId");
            if (product != null && product.get("purchaseUomId") != null
                    && !product.get("purchaseUomId").equals(product.get("uomId"))) {
                qty = uom.fromBase(qty, str(product, "purchaseUomId"), str(po, "productId"), str(product, "uomId"),
                        false).setScale(6, RoundingMode.CEILING);
                unit = product.get("purchaseUomId");
            }
            db.insert(PurchaseRequestLine.class, Obj.of("purchaseRequestId", pr.get("id"), "productId",
                    po.get("productId"), "description", "MRP " + run.get("runNumber"), "quantity", qty,
                    "unitOfMeasureId", unit, "lineNumber", i++));
            out.add(db.update(MrpPlannedOrder.class, str(po, "id"), Obj.of("status", "firmed", "firmedDocType",
                    "purchase_request", "firmedDocId", pr.get("id"))));
        }
        return out;
    }

    private Map<String, Object> requirePlanned(String id) {
        Map<String, Object> po = db.byId(MrpPlannedOrder.class, id);
        if (po == null) {
            throw ApiException.notFound("Planned order not found");
        }
        if (!"planned".equals(po.get("status"))) {
            throw ApiException.badRequest("Planned order is already " + po.get("status") + ".");
        }
        return po;
    }

    // ================================================================== demand & supply

    private void collectIndependentDemand(Map<String, List<Demand>> demand, Instant horizonEnd, Instant now) {
        // Open back-office orders (not POS tickets in service): every product line.
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT i.\"productId\", i.quantity, o.id, o.\"openedAt\""
                + " FROM \"OrderItem\" i JOIN \"Order\" o ON o.id = i.\"orderId\" WHERE o.\"organizationId\" = ?"
                + " AND o.status IN ('draft', 'confirmed') AND i.\"productId\" IS NOT NULL AND i.cancelled = false")
                .param(MfgDb.org()))) {
            add(demand, str(r, "productId"), dec(r, "quantity"), max(now, (Instant) r.get("openedAt")),
                    Obj.of("type", "order", "id", r.get("id")));
        }
        // Proforma documents (quotes / advance orders) due inside the horizon.
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT l.\"productId\", l.quantity, d.id, d.\"dueDate\""
                + " FROM \"DocumentLine\" l JOIN \"Document\" d ON d.id = l.\"documentId\" WHERE d.\"organizationId\" = ?"
                + " AND d.\"documentType\" = 'proforma_invoice' AND d.status IN ('draft', 'submitted', 'approved',"
                + " 'confirmed', 'issued', 'active') AND l.\"productId\" IS NOT NULL"
                + " AND (d.\"dueDate\" IS NULL OR d.\"dueDate\" <= ?)").params(MfgDb.org(), MfgDb.param(horizonEnd)))) {
            add(demand, str(r, "productId"), dec(r, "quantity"), max(now, (Instant) r.get("dueDate")),
                    Obj.of("type", "document", "id", r.get("id")));
        }
        // Production requests not yet consolidated into a plan.
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT l.\"productId\", l.quantity, r.id,"
                + " coalesce(l.\"neededBy\", r.\"neededBy\") AS due FROM \"ProductionRequestLine\" l JOIN"
                + " \"ProductionRequest\" r ON r.id = l.\"requestId\" WHERE l.\"organizationId\" = ?"
                + " AND r.\"deletedAt\" IS NULL AND r.status IN ('submitted', 'approved') AND l.\"plannedLineId\" IS NULL")
                .param(MfgDb.org()))) {
            add(demand, str(r, "productId"), dec(r, "quantity"), max(now, (Instant) r.get("due")),
                    Obj.of("type", "production_request", "id", r.get("id")));
        }
        // Remaining materials of open (unconsumed) production orders.
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT m.\"productId\","
                + " greatest(m.\"qtyPlanned\" - m.\"qtyConsumed\", 0) AS qty, o.id, o.\"scheduledFor\""
                + " FROM \"ProductionMaterial\" m JOIN \"ProductionOrder\" o ON o.id = m.\"orderId\""
                + " WHERE o.\"organizationId\" = ? AND o.status IN ('draft', 'confirmed')").param(MfgDb.org()))) {
            if (dec(r, "qty").signum() > 0) {
                add(demand, str(r, "productId"), dec(r, "qty"), max(now, (Instant) r.get("scheduledFor")),
                        Obj.of("type", "production_order", "id", r.get("id")));
            }
        }
    }

    private static void add(Map<String, List<Demand>> demand, String productId, BigDecimal qty, Instant date,
            Map<String, Object> ref) {
        demand.computeIfAbsent(productId, k -> new ArrayList<>()).add(new Demand(qty, date, ref));
    }

    private static Instant max(Instant now, Instant date) {
        return date == null || date.isBefore(now) ? now : date;
    }

    /**
     * Low-level code of each product: the deepest level at which it appears in any active BOM tree rooted at a
     * demanded product (cycle- and depth-guarded).
     */
    Map<String, Integer> lowLevelCodes(Set<String> roots) {
        Map<String, List<String>> children = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT b.\"outputProductId\", l.\"componentProductId\""
                + " FROM \"BomLine\" l JOIN \"Bom\" b ON b.id = l.\"bomId\" WHERE b.\"organizationId\" = ?"
                + " AND b.\"deletedAt\" IS NULL AND b.status = 'active'").param(MfgDb.org()))) {
            children.computeIfAbsent(str(r, "outputProductId"), k -> new ArrayList<>())
                    .add(str(r, "componentProductId"));
        }
        Map<String, Integer> llc = new HashMap<>();
        for (String root : roots) {
            walk(root, 0, children, llc, new LinkedHashSet<>());
        }
        return llc;
    }

    private void walk(String p, int level, Map<String, List<String>> children, Map<String, Integer> llc,
            Set<String> path) {
        if (level > MAX_LEVEL || path.contains(p) || llc.getOrDefault(p, -1) >= level) {
            return;
        }
        llc.put(p, level);
        path.add(p);
        for (String c : children.getOrDefault(p, List.of())) {
            walk(c, level + 1, children, llc, path);
        }
        path.remove(p);
    }

    private BigDecimal onHand(String productId, String locationId) {
        return locationId != null
                ? db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"locationId\" = ?").params(MfgDb.org(), productId, locationId)
                        .query(BigDecimal.class).single()
                : db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ?").params(MfgDb.org(), productId).query(BigDecimal.class).single();
    }

    /** Active reservations held for anything but production orders (those are already counted as demand). */
    private BigDecimal reservedByOthers(String productId, String locationId) {
        return db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockReservation\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND status = 'active' AND \"sourceType\" <> 'production_order'"
                        + (locationId != null ? " AND \"locationId\" = ?" : ""))
                .params(locationId != null ? List.of(MfgDb.org(), productId, locationId) : List.of(MfgDb.org(), productId))
                .query(BigDecimal.class).single();
    }

    private BigDecimal openPurchase(String productId) {
        return db.jdbc().sql("SELECT coalesce(sum(greatest(l.quantity - l.\"receivedQuantity\", 0)), 0) FROM"
                + " \"PurchaseOrderLine\" l JOIN \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"productId\" = ? AND o.\"deletedAt\" IS NULL"
                + " AND o.status IN ('draft', 'submitted', 'approved', 'active', 'sent', 'acknowledged',"
                + " 'partially_received')").params(MfgDb.org(), productId).query(BigDecimal.class).single();
    }

    private BigDecimal openProductionOutput(String productId) {
        return db.jdbc().sql("SELECT coalesce(sum(greatest(\"plannedQty\" - \"producedQty\", 0)), 0) FROM"
                + " \"ProductionOrder\" WHERE \"organizationId\" = ? AND \"outputProductId\" = ?"
                + " AND status IN ('draft', 'confirmed', 'in_progress')").params(MfgDb.org(), productId)
                .query(BigDecimal.class).single();
    }

    /** Minimum order quantity, then round up to the order multiple. */
    static BigDecimal lotSize(BigDecimal net, Params p) {
        BigDecimal q = net.max(p.minOrderQty());
        if (p.orderMultiple().signum() > 0) {
            q = Money.div(q, p.orderMultiple()).setScale(0, RoundingMode.CEILING).multiply(p.orderMultiple());
        }
        return q;
    }

    private void supersedePrevious() {
        db.jdbc().sql("UPDATE \"MrpPlannedOrder\" SET status = 'cancelled', \"updatedAt\" = " + MfgDb.NOW
                + " WHERE \"organizationId\" = ? AND status = 'planned'").param(MfgDb.org()).update();
        db.jdbc().sql("UPDATE \"MrpRun\" SET status = 'superseded' WHERE \"organizationId\" = ? AND status = 'completed'")
                .param(MfgDb.org()).update();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
