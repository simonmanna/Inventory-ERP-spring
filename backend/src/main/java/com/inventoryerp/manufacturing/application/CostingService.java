package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.SubstituteInput;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.BomLine;
import com.inventoryerp.manufacturing.domain.BomLineSubstitute;
import com.inventoryerp.manufacturing.domain.BomStandardCost;
import com.inventoryerp.manufacturing.domain.ProductionMaterial;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.ProductionVariance;
import com.inventoryerp.manufacturing.domain.Routing;
import com.inventoryerp.manufacturing.domain.RoutingOperation;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Costing & engineering read models (ADR-S006 package D): multi-level BOM cost roll-up, frozen standard costs and
 * the analytic variances of completed orders against them (no GL effect — stock stays actual-costed), where-used,
 * component substitutes and order material availability.
 */
@Service
public class CostingService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final int MAX_DEPTH = 10;

    private final MfgDb db;
    private final BomService boms;
    private final UomConversionService uom;

    private final com.inventoryerp.inventory.application.StandardCostService productStandards;

    public CostingService(MfgDb db, BomService boms, UomConversionService uom,
            com.inventoryerp.inventory.application.StandardCostService productStandards) {
        this.productStandards = productStandards;
        this.db = db;
        this.boms = boms;
        this.uom = uom;
    }

    // ================================================================== roll-up

    /** Cost of one run of the BOM (material, labour/machine, subcontract, overhead) and per output unit. */
    @Transactional(readOnly = true)
    public Map<String, Object> rollup(String bomId) {
        Map<String, Object> bom = boms.getOrThrow(bomId);
        Map<String, Object> r = rollup(bom, new HashSet<>(), 0);
        r.put("bomId", bomId);
        r.put("bomCode", bom.get("code"));
        r.put("version", bom.get("version"));
        return render(r);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rollup(Map<String, Object> bom, Set<String> path, int depth) {
        String output = str(bom, "outputProductId");
        path.add(output);
        BigDecimal material = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) bom.get("lines")) {
            String component = str(l, "componentProductId");
            BigDecimal scrap = BigDecimal.ONE.add(Money.div(dec(l, "scrapPct"), HUNDRED));
            BigDecimal qty = BomService.mul(dec(l, "quantity"), scrap);
            Map<String, Object> product = SqlRows.one(db.jdbc().sql("SELECT id, name, \"uomId\", \"costPrice\" FROM"
                    + " \"Product\" WHERE id = ?").param(component));
            BigDecimal baseQty = product == null ? qty
                    : uom.toBase(qty, str(l, "uomId"), component, str(product, "uomId"), false);
            String source;
            BigDecimal unit;
            Map<String, Object> child = path.contains(component) || depth >= MAX_DEPTH ? null
                    : boms.findActive(component, str(l, "componentVariantId"));
            if (child != null) {
                Map<String, Object> sub = rollup(child, path, depth + 1);
                unit = (BigDecimal) sub.get("unitCost");
                source = "bom";
            } else {
                unit = averageCost(component);
                source = "average";
                if (unit == null) {
                    unit = product == null ? BigDecimal.ZERO : dec(product, "costPrice");
                    source = "cost_price";
                }
            }
            BigDecimal cost = BomService.mul(baseQty, unit);
            material = material.add(cost);
            lines.add(Obj.of("kind", "material", "productId", component, "name", product == null ? null
                    : product.get("name"), "quantity", baseQty, "unitCost", unit, "cost", cost, "source", source));
        }
        path.remove(output);

        BigDecimal labour = BigDecimal.ZERO;
        BigDecimal subcontract = BigDecimal.ZERO;
        BigDecimal outputQty = dec(bom, "outputQuantity");
        for (Map<String, Object> op : routingOperations(bom)) {
            if (Boolean.TRUE.equals(op.get("isSubcontracted"))) {
                BigDecimal cost = BomService.mul(dec(op, "subcontractCostPerUnit"), outputQty);
                subcontract = subcontract.add(cost);
                lines.add(Obj.of("kind", "subcontract", "operation", op.get("name"), "cost", cost));
                continue;
            }
            Map<String, Object> wc = op.get("workCenterId") == null ? null
                    : db.byId(WorkCenter.class, str(op, "workCenterId"));
            if (wc == null) {
                continue;
            }
            BigDecimal minutes = BigDecimal.valueOf(((Number) op.get("durationMins")).intValue()
                    + ((Number) op.get("setupMins")).intValue());
            BigDecimal eff = dec(wc, "efficiencyPct").signum() > 0 ? dec(wc, "efficiencyPct") : HUNDRED;
            BigDecimal cost = Money.div(BomService.mul(Money.div(minutes, SIXTY), dec(wc, "costPerHour")),
                    Money.div(eff, HUNDRED));
            labour = labour.add(cost);
            lines.add(Obj.of("kind", "labour", "operation", op.get("name"), "workCenterId", wc.get("id"), "minutes",
                    minutes, "cost", cost));
        }
        BigDecimal overhead = dec(bom, "overheadCost");
        BigDecimal total = material.add(labour).add(subcontract).add(overhead);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outputQuantity", outputQty);
        out.put("materialCost", material);
        out.put("labourCost", labour);
        out.put("subcontractCost", subcontract);
        out.put("overheadCost", overhead);
        out.put("totalCost", total);
        out.put("unitCost", outputQty.signum() > 0 ? Money.div(total, outputQty) : BigDecimal.ZERO);
        out.put("breakdown", lines);
        return out;
    }

    /** The routing of a BOM: its routingId, else an active routing linked to the BOM or its product. */
    List<Map<String, Object>> routingOperations(Map<String, Object> bom) {
        Map<String, Object> routing = bom.get("routingId") != null ? db.byId(Routing.class, str(bom, "routingId")) : null;
        if (routing == null) {
            List<Map<String, Object>> rows = db.list(Routing.class, " AND (\"bomId\" = ? OR (\"bomId\" IS NULL AND"
                    + " \"productId\" = ?)) AND \"isActive\" = true ORDER BY \"bomId\" NULLS LAST LIMIT 1",
                    bom.get("id"), bom.get("outputProductId"));
            routing = rows.isEmpty() ? null : rows.getFirst();
        }
        return routing == null ? List.of()
                : db.children(RoutingOperation.class, "routingId", routing.get("id"), "sequence ASC");
    }

    /** Quantity-weighted running average cost across the product's stock lines, or null without stock. */
    private BigDecimal averageCost(String productId) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql("SELECT sum(quantity * \"runningAverageCost\") AS v,"
                + " sum(quantity) AS q FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND quantity > 0").params(MfgDb.org(), productId));
        if (r == null || r.get("q") == null || dec(r, "q").signum() <= 0) {
            return null;
        }
        if (dec(r, "v").signum() > 0) {
            return Money.div(dec(r, "v"), dec(r, "q"));
        }
        // FIFO and lot-tracked stock carries its cost on the batches, not on the running average (ADR-S060).
        Map<String, Object> b = SqlRows.one(db.jdbc().sql("SELECT sum(quantity * \"unitCost\") AS v, sum(quantity) AS q"
                + " FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND quantity > 0 AND"
                + " \"isActive\" = true").params(MfgDb.org(), productId));
        return b == null || b.get("q") == null || dec(b, "q").signum() <= 0 ? null : Money.div(dec(b, "v"), dec(b, "q"));
    }

    // ================================================================== standard cost

    /** Freeze the current roll-up as the BOM's standard (the previous standard stops being current). */
    @Transactional
    public Map<String, Object> freezeStandard(String bomId) {
        Map<String, Object> bom = boms.getOrThrow(bomId);
        Map<String, Object> r = rollup(bom, new HashSet<>(), 0);
        db.jdbc().sql("UPDATE \"BomStandardCost\" SET \"isCurrent\" = false WHERE \"organizationId\" = ? AND \"bomId\" = ?")
                .params(MfgDb.org(), bomId).update();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bomId", bomId);
        data.put("bomVersion", bom.get("version"));
        data.put("outputQuantity", r.get("outputQuantity"));
        data.put("materialCost", r.get("materialCost"));
        data.put("labourCost", r.get("labourCost"));
        data.put("overheadCost", r.get("overheadCost"));
        data.put("subcontractCost", r.get("subcontractCost"));
        data.put("totalCost", r.get("totalCost"));
        data.put("unitCost", r.get("unitCost"));
        data.put("breakdown", new MfgDb.Json(render(Obj.of("b", r.get("breakdown"))).get("b")));
        data.put("isCurrent", true);
        data.put("createdBy", TenantContext.userId());
        data.put("computedAt", Instant.now());
        return db.insert(BomStandardCost.class, data);
    }

    /**
     * ADR-S008: freeze the roll-up as the BOM standard and propose it as a draft product standard (per output unit,
     * by element) for approval and activation — the standard that drives GL variances.
     */
    @Transactional
    public Map<String, Object> proposeProductStandard(String bomId, String effectiveFrom, Boolean convertMethod) {
        Map<String, Object> bom = boms.getOrThrow(bomId);
        Map<String, Object> frozen = freezeStandard(bomId);
        BigDecimal qty = dec(frozen, "outputQuantity");
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("The BOM output quantity must be positive to derive a unit standard");
        }
        java.util.function.Function<String, BigDecimal> perUnit = k -> Money.div(dec(frozen, k), qty)
                .setScale(6, java.math.RoundingMode.HALF_UP);
        return productStandards.create(new com.inventoryerp.inventory.application.StandardCostService.StandardInput(
                str(bom, "outputProductId"), effectiveFrom != null ? effectiveFrom
                        : java.time.LocalDate.now(com.inventoryerp.kernel.common.BusinessZone.get()).toString(),
                perUnit.apply("materialCost"), perUnit.apply("labourCost"), perUnit.apply("overheadCost"),
                perUnit.apply("subcontractCost"), "From BOM v" + bom.get("version") + " roll-up", convertMethod,
                "bom_rollup", str(frozen, "id")));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> standards(String bomId) {
        boms.getOrThrow(bomId);
        return db.list(BomStandardCost.class, " AND \"bomId\" = ? ORDER BY \"computedAt\" DESC", bomId);
    }

    /** Current standard of a BOM, or null. */
    String currentStandardId(String bomId) {
        if (bomId == null) {
            return null;
        }
        return db.jdbc().sql("SELECT id FROM \"BomStandardCost\" WHERE \"organizationId\" = ? AND \"bomId\" = ?"
                + " AND \"isCurrent\" = true ORDER BY \"computedAt\" DESC LIMIT 1").params(MfgDb.org(), bomId)
                .query(String.class).optional().orElse(null);
    }

    /**
     * Analytic variances of a completed order against its standard (positive = unfavourable): per material price
     * (actual vs standard unit cost × actual qty) and usage ((actual − standard qty for the good output) × standard
     * cost), labour, overhead, subcontract, and the order's yield variance.
     */
    void recordVariances(String orderId) {
        Map<String, Object> order = db.byId(ProductionOrder.class, orderId);
        if (order == null || order.get("standardCostId") == null) {
            return;
        }
        Map<String, Object> std = db.byId(BomStandardCost.class, str(order, "standardCostId"));
        BigDecimal produced = dec(order, "producedQty");
        if (std == null || produced.signum() <= 0 || dec(std, "outputQuantity").signum() <= 0) {
            return;
        }
        db.jdbc().sql("DELETE FROM \"ProductionVariance\" WHERE \"organizationId\" = ? AND \"orderId\" = ?")
                .params(MfgDb.org(), orderId).update();
        BigDecimal scale = Money.div(produced, dec(std, "outputQuantity"));
        Map<String, BigDecimal[]> actual = new LinkedHashMap<>();
        for (Map<String, Object> m : db.children(ProductionMaterial.class, "orderId", orderId, null)) {
            BigDecimal[] a = actual.computeIfAbsent(str(m, "productId"),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(dec(m, "qtyConsumed"));
            a[1] = a[1].add(dec(m, "totalCost"));
        }
        if (std.get("breakdown") instanceof JsonNode lines) {
            for (JsonNode l : lines) {
                if (!"material".equals(text(l, "kind"))) {
                    continue;
                }
                String productId = text(l, "productId");
                BigDecimal stdQty = BomService.mul(new BigDecimal(text(l, "quantity")), scale);
                BigDecimal stdUnit = new BigDecimal(text(l, "unitCost"));
                BigDecimal[] a = actual.remove(productId);
                BigDecimal actQty = a == null ? BigDecimal.ZERO : a[0];
                BigDecimal actCost = a == null ? BigDecimal.ZERO : a[1];
                BigDecimal actUnit = actQty.signum() > 0 ? Money.div(actCost, actQty) : stdUnit;
                variance(orderId, "material_price", productId, null, actQty, BomService.mul(stdUnit, actQty), actCost,
                        BomService.mul(actUnit.subtract(stdUnit), actQty));
                variance(orderId, "material_usage", productId, stdQty, actQty, BomService.mul(stdQty, stdUnit),
                        BomService.mul(actQty, stdUnit), BomService.mul(actQty.subtract(stdQty), stdUnit));
            }
        }
        // Materials outside the standard (substitutes, ad-hoc additions) are pure usage variance.
        actual.forEach((productId, a) -> variance(orderId, "material_usage", productId, BigDecimal.ZERO, a[0],
                BigDecimal.ZERO, a[1], a[1]));
        BigDecimal labour = componentTotal(orderId, "'labour', 'machine'");
        BigDecimal overhead = componentTotal(orderId, "'overhead'");
        BigDecimal outsourcing = componentTotal(orderId, "'outsourcing'");
        stdVariance(orderId, "labour", BomService.mul(dec(std, "labourCost"), scale), labour);
        stdVariance(orderId, "overhead", BomService.mul(dec(std, "overheadCost"), scale), overhead);
        stdVariance(orderId, "subcontract", BomService.mul(dec(std, "subcontractCost"), scale), outsourcing);
        if (dec(order, "yieldVariance").signum() != 0) {
            variance(orderId, "yield", str(order, "outputProductId"), null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                    dec(order, "yieldVariance"));
        }
    }

    private void stdVariance(String orderId, String kind, BigDecimal standard, BigDecimal actual) {
        if (standard.signum() != 0 || actual.signum() != 0) {
            variance(orderId, kind, null, null, null, standard, actual, actual.subtract(standard));
        }
    }

    private BigDecimal componentTotal(String orderId, String kinds) {
        return db.jdbc().sql("SELECT coalesce(sum(amount), 0) FROM \"ProductionCostComponent\" WHERE"
                + " \"organizationId\" = ? AND \"orderId\" = ? AND kind::text IN (" + kinds + ")")
                .params(MfgDb.org(), orderId).query(BigDecimal.class).single();
    }

    private void variance(String orderId, String kind, String productId, BigDecimal stdQty, BigDecimal actQty,
            BigDecimal stdCost, BigDecimal actCost, BigDecimal amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("kind", kind);
        data.put("productId", productId);
        data.put("standardQty", stdQty);
        data.put("actualQty", actQty);
        data.put("standardCost", stdCost);
        data.put("actualCost", actCost);
        data.put("amount", amount);
        db.insert(ProductionVariance.class, data);
    }

    /** Variances of completed orders in the window (or of one order), with totals by kind. */
    @Transactional(readOnly = true)
    public Map<String, Object> variances(String from, String to, String orderId) {
        StringBuilder sql = new StringBuilder("SELECT v.*, o.\"orderCode\", o.\"completedAt\" FROM"
                + " \"ProductionVariance\" v JOIN \"ProductionOrder\" o ON o.id = v.\"orderId\" WHERE"
                + " v.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (orderId != null) {
            sql.append(" AND v.\"orderId\" = ?");
            params.add(orderId);
        }
        if (from != null && !from.isEmpty()) {
            sql.append(" AND o.\"completedAt\" >= ?");
            params.add(MfgDb.param(EntityPatch.toInstant(from)));
        }
        if (to != null && !to.isEmpty()) {
            sql.append(" AND o.\"completedAt\" <= ?");
            params.add(MfgDb.param(EntityPatch.toInstant(to)));
        }
        sql.append(" ORDER BY o.\"completedAt\" DESC, v.kind");
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(sql.toString()).params(params));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            totals.merge(str(r, "kind"), dec(r, "amount"), BigDecimal::add);
            total = total.add(dec(r, "amount"));
        }
        Map<String, Object> byKind = new LinkedHashMap<>();
        totals.forEach((k, v) -> byKind.put(k, JsNumbers.dec(v)));
        return Obj.of("rows", rows, "totals", byKind, "total", JsNumbers.dec(total));
    }

    // ================================================================== where-used

    /** Every active BOM that uses the product, directly or through sub-assemblies (level 1 = direct parent). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> whereUsed(String productId) {
        List<Map<String, Object>> out = new ArrayList<>();
        whereUsed(productId, 1, new HashSet<>(List.of(productId)), out);
        return out;
    }

    private void whereUsed(String productId, int level, Set<String> seen, List<Map<String, Object>> out) {
        if (level > MAX_DEPTH) {
            return;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT b.id AS \"bomId\", b.code, b.name, b.version,"
                + " b.\"outputProductId\", p.name AS \"outputName\", l.quantity, l.\"uomId\" FROM \"BomLine\" l"
                + " JOIN \"Bom\" b ON b.id = l.\"bomId\" LEFT JOIN \"Product\" p ON p.id = b.\"outputProductId\""
                + " WHERE b.\"organizationId\" = ? AND b.\"deletedAt\" IS NULL AND b.status = 'active'"
                + " AND l.\"componentProductId\" = ? ORDER BY b.code").params(MfgDb.org(), productId))) {
            r.put("componentProductId", productId);
            r.put("level", level);
            out.add(r);
            String parent = str(r, "outputProductId");
            if (seen.add(parent)) {
                whereUsed(parent, level + 1, seen, out);
            }
        }
    }

    // ================================================================== substitutes & availability

    /** The BOM's lines, each with its substitutes. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> substitutes(String bomId) {
        Map<String, Object> bom = boms.getOrThrow(bomId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) bom.get("lines")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> line = new LinkedHashMap<>((Map<String, Object>) o);
            line.put("substitutes", db.children(BomLineSubstitute.class, "bomLineId", line.get("id"), "priority ASC"));
            out.add(line);
        }
        return out;
    }

    /** Replace the substitutes of one BOM line. */
    @Transactional
    public List<Map<String, Object>> saveSubstitutes(String bomId, String lineId, List<SubstituteInput> subs) {
        boms.getOrThrow(bomId);
        Map<String, Object> line = SqlRows.one(BomLine.class, db.jdbc().sql("SELECT * FROM \"BomLine\" WHERE id = ?"
                + " AND \"bomId\" = ? AND \"organizationId\" = ?").params(lineId, bomId, MfgDb.org()));
        if (line == null) {
            throw ApiException.notFound("BOM line not found");
        }
        db.jdbc().sql("DELETE FROM \"BomLineSubstitute\" WHERE \"organizationId\" = ? AND \"bomLineId\" = ?")
                .params(MfgDb.org(), lineId).update();
        for (SubstituteInput s : subs) {
            if (s.productId().equals(line.get("componentProductId"))) {
                throw ApiException.badRequest("A component cannot substitute itself.");
            }
            if (db.jdbc().sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(s.productId(), MfgDb.org()).query(Long.class).single() == 0) {
                throw ApiException.notFound("Product " + s.productId() + " not found");
            }
            db.insert(BomLineSubstitute.class, Obj.of("bomLineId", lineId, "productId", s.productId(), "ratio",
                    s.ratio() != null ? s.ratio() : BigDecimal.ONE, "priority", s.priority() != null ? s.priority() : 0,
                    "notes", s.notes()));
        }
        return db.children(BomLineSubstitute.class, "bomLineId", lineId, "priority ASC");
    }

    /** The substitute row allowing {@code substituteId} for {@code componentId} on the order's BOM, or null. */
    Map<String, Object> substituteFor(String bomId, String componentId, String substituteId) {
        if (bomId == null) {
            return null;
        }
        return SqlRows.one(db.jdbc().sql("SELECT s.* FROM \"BomLineSubstitute\" s JOIN \"BomLine\" l ON l.id ="
                + " s.\"bomLineId\" WHERE l.\"bomId\" = ? AND l.\"componentProductId\" = ? AND s.\"productId\" = ?"
                + " AND s.\"organizationId\" = ? LIMIT 1").params(bomId, componentId, substituteId, MfgDb.org()));
    }

    /** Remaining material need of an order vs stock at its location, and the substitutes that could cover a gap. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availability(String orderId) {
        Map<String, Object> order = db.byId(ProductionOrder.class, orderId);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        String loc = str(order, "locationId");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : db.children(ProductionMaterial.class, "orderId", orderId, "sequence ASC")) {
            BigDecimal required = dec(m, "qtyPlanned").subtract(dec(m, "qtyConsumed")).max(BigDecimal.ZERO);
            BigDecimal onHand = stockAt(str(m, "productId"), loc);
            BigDecimal shortage = required.subtract(onHand).max(BigDecimal.ZERO);
            List<Map<String, Object>> subs = new ArrayList<>();
            if (order.get("bomId") != null) {
                for (Map<String, Object> s : SqlRows.list(db.jdbc().sql("SELECT s.\"productId\", s.ratio, p.name FROM"
                        + " \"BomLineSubstitute\" s JOIN \"BomLine\" l ON l.id = s.\"bomLineId\" LEFT JOIN \"Product\" p"
                        + " ON p.id = s.\"productId\" WHERE l.\"bomId\" = ? AND l.\"componentProductId\" = ?"
                        + " ORDER BY s.priority").params(order.get("bomId"), m.get("productId")))) {
                    BigDecimal subOnHand = stockAt(str(s, "productId"), loc);
                    BigDecimal needed = BomService.mul(shortage, dec(s, "ratio"));
                    subs.add(Obj.of("productId", s.get("productId"), "name", s.get("name"), "ratio", s.get("ratio"),
                            "onHand", JsNumbers.dec(subOnHand), "covers", shortage.signum() > 0
                                    && subOnHand.compareTo(needed) >= 0));
                }
            }
            out.add(Obj.of("lineId", m.get("id"), "productId", m.get("productId"), "productName", m.get("productName"),
                    "required", JsNumbers.dec(required), "onHand", JsNumbers.dec(onHand), "shortage",
                    JsNumbers.dec(shortage), "substitutes", subs));
        }
        return out;
    }

    private BigDecimal stockAt(String productId, String locationId) {
        return db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"locationId\" = ?").params(MfgDb.org(), productId, locationId)
                .query(BigDecimal.class).single();
    }

    // ---- rendering

    /** Decimals as decimal.js strings for JSON (roll-ups are computed in memory, not read back from rows). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> render(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof BigDecimal b ? JsNumbers.dec(b)
                : v instanceof List<?> l ? l.stream().map(i -> i instanceof Map<?, ?> mm
                        ? render((Map<String, Object>) mm) : i).toList()
                : v));
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
