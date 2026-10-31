package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.ProductionOrder;
import com.inventoryerp.manufacturing.domain.WorkCenterCostRate;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manufacturing cost analytics (Track C 2.8, ADR-S018): the actual cost build-up of an order (materials, labour,
 * machine,
 * energy, overhead, per stage, cost per acceptable block or per tonne), its variances, and the period reports: cost by
 * shift, plant, product or month, and gross margin by product or customer. Read only.
 */
@Service
@Transactional(readOnly = true)
public class ManufacturingCostingService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final int SCALE = 6;

    private final MfgDb db;
    private final StageExecutionService stages;
    private final CostAbsorptionService absorption;
    private final PrecastReportService precast;

    public ManufacturingCostingService(MfgDb db, StageExecutionService stages, CostAbsorptionService absorption,
            PrecastReportService precast) {
        this.db = db;
        this.stages = stages;
        this.absorption = absorption;
        this.precast = precast;
    }

    private record Range(LocalDate from, LocalDate to) {
        Object start() {
            return MfgDb.param(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        }

        Object end() {
            return MfgDb.param(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        }
    }

    private static Range range(String from, String to) {
        LocalDate t = CrushingSupport.date(to) != null ? CrushingSupport.date(to) : LocalDate.now(BusinessZone.get());
        LocalDate f = CrushingSupport.date(from) != null ? CrushingSupport.date(from) : t.withDayOfMonth(1);
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must not be before from");
        }
        return new Range(f, t);
    }

    /** The cost bucket of a conversion line: its kind, or where the cost came from. */
    static String bucket(String sourceRef, String costSource) {
        String key = sourceRef != null ? sourceRef : costSource;
        if (key == null) {
            return "overhead";
        }
        return switch (key) {
            case "labour", "work_order" -> "labour";
            case "machine" -> "machine";
            case "utility" -> "utility";
            default -> "overhead";
        };
    }

    private Map<String, Object> requireOrder(String orderId) {
        Map<String, Object> order = db.byId(ProductionOrder.class, orderId);
        if (order == null) {
            throw ApiException.notFound("Production order not found");
        }
        return order;
    }

    // ================================================================== order build-up

    /** Actual cost of one order so far, the absorption still due and the product-specific unit cost. */
    public Map<String, Object> orderCost(String orderId) {
        Map<String, Object> order = requireOrder(orderId);
        Map<String, BigDecimal> cost = buckets(orderId, order);
        BigDecimal total = cost.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal produced = dec(order, "producedQty");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("orderCode", order.get("orderCode"));
        out.put("status", order.get("status"));
        out.put("plannedQty", order.get("plannedQty"));
        out.put("producedQty", produced);
        out.put("materialCost", cost.get("material"));
        out.put("labourCost", cost.get("labour"));
        out.put("machineCost", cost.get("machine"));
        out.put("energyCost", cost.get("utility"));
        out.put("overheadCost", cost.get("overhead"));
        out.put("totalCost", total);
        out.put("unitCost", produced.signum() > 0 ? total.divide(produced, SCALE, RoundingMode.HALF_UP) : null);
        out.put("stages", SqlRows.list(db.jdbc().sql("SELECT sequence, name, status, \"materialCost\","
                + " \"conversionCost\","
                + " \"outputCost\", \"scrapCost\", \"qtyGood\", \"qtyScrap\" FROM \"ProductionOrderStage\""
                + " WHERE \"organizationId\" = ? AND \"orderId\" = ? ORDER BY sequence").params(MfgDb.org(), orderId)));
        out.put("absorptionDue", absorption.preview(orderId));
        out.put("absorbed", SqlRows.list(db.jdbc().sql("SELECT a.kind, a.\"basisQty\", a.rate, a.amount,"
                + " a.\"createdAt\","
                + " w.name AS \"workCenterName\" FROM \"ProductionCostAbsorption\" a JOIN \"WorkCenter\" w ON w.id ="
                + " a.\"workCenterId\" LEFT JOIN \"ProductionStagePosting\" p ON p.id = a.\"stagePostingId\""
                + " WHERE a.\"organizationId\" = ? AND a.\"orderId\" = ? AND p.\"reversedAt\" IS NULL"
                + " ORDER BY a.\"createdAt\"").params(MfgDb.org(), orderId)));
        Map<String, Object> mix = SqlRows.one(db.jdbc().sql("SELECT m.\"bomId\", b.\"outputQuantity\" AS"
                + " \"unitsPerBatch\""
                + " FROM \"PrecastMixDesign\" m JOIN \"Bom\" b ON b.id = m.\"bomId\" WHERE m.\"organizationId\" = ?"
                + " AND m.\"bomId\" = ?").params(MfgDb.org(), order.get("bomId")));
        if (mix != null) {
            out.put("precast", precast.orderCosting(orderId, mix));
        }
        Map<String, Object> run = SqlRows.one(db.jdbc().sql("SELECT id, code, \"feedTonnes\", \"outputTonnes\","
                + " \"feedCost\", \"conversionCost\", \"costPerTonne\" FROM \"CrushingRun\" WHERE \"organizationId\" ="
                + " ?"
                + " AND \"productionOrderId\" = ? AND \"deletedAt\" IS NULL").params(MfgDb.org(), orderId));
        if (run != null) {
            out.put("crushing", run);
        }
        return out;
    }

    private Map<String, BigDecimal> buckets(String orderId, Map<String, Object> order) {
        Map<String, BigDecimal> cost = new LinkedHashMap<>();
        for (String k : List.of("material", "labour", "machine", "utility", "overhead")) {
            cost.put(k, BigDecimal.ZERO);
        }
        if (stages.isMultiStage(orderId)) {
            for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT l.role, l.value, l.\"costSource\","
                    + " l.\"sourceRef\" FROM \"ProductionStagePostingLine\" l JOIN \"ProductionStagePosting\" p ON"
                    + " p.id = l.\"postingId\" WHERE l.\"organizationId\" = ? AND p.\"orderId\" = ?"
                    + " AND p.\"reversedAt\" IS NULL AND l.role IN ('material', 'conversion')")
                    .params(MfgDb.org(), orderId))) {
                String key = "material".equals(l.get("role")) ? "material"
                        : bucket(str(l, "sourceRef"), str(l, "costSource"));
                cost.merge(key, dec(l, "value"), BigDecimal::add);
            }
            return cost;
        }
        cost.put("material", dec(order, "materialCost"));
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT kind::text AS kind, sum(amount) AS amount FROM"
                + " \"ProductionCostComponent\" WHERE \"organizationId\" = ? AND \"orderId\" = ? AND kind <> 'material'"
                + " GROUP BY kind").params(MfgDb.org(), orderId))) {
            String key = bucket(str(c, "kind"), null);
            cost.merge(key, dec(c, "amount"), BigDecimal::add);
        }
        return cost;
    }

    // ================================================================== variances

    /** Material price and usage per line, labour efficiency, overhead and yield of one order. */
    public Map<String, Object> orderVariances(String orderId) {
        Map<String, Object> order = requireOrder(orderId);
        Map<String, BigDecimal> standard = standardUnits(order);
        List<Map<String, Object>> materials = new ArrayList<>();
        BigDecimal price = BigDecimal.ZERO;
        BigDecimal usage = BigDecimal.ZERO;
        BigDecimal stdMaterial = BigDecimal.ZERO;
        for (Map<String, Object> m : SqlRows.list(db.jdbc().sql("SELECT m.\"productId\", p.name AS \"productName\","
                + " p.\"costPrice\", sum(m.\"qtyPlanned\") AS planned, sum(m.\"qtyConsumed\") AS actual,"
                + " sum(m.\"totalCost\") AS cost FROM \"ProductionMaterial\" m LEFT JOIN \"Product\" p ON p.id ="
                + " m.\"productId\" WHERE m.\"organizationId\" = ? AND m.\"orderId\" = ?"
                + " GROUP BY m.\"productId\", p.name, p.\"costPrice\" ORDER BY p.name").params(MfgDb.org(), orderId))) {
            BigDecimal planned = dec(m, "planned");
            BigDecimal actual = dec(m, "actual");
            BigDecimal actualCost = dec(m, "cost");
            BigDecimal stdUnit = standard.containsKey(str(m, "productId")) ? standard.get(str(m, "productId"))
                    : dec(m, "costPrice");
            if (stdUnit.signum() == 0 && actual.signum() > 0) {
                stdUnit = actualCost.divide(actual, SCALE, RoundingMode.HALF_UP);
            }
            VarianceCalculator.Material v = VarianceCalculator.material(planned, actual, actualCost, stdUnit);
            price = price.add(v.price());
            usage = usage.add(v.usage());
            stdMaterial = stdMaterial.add(planned.multiply(stdUnit));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", m.get("productId"));
            row.put("productName", m.get("productName"));
            row.put("plannedQty", planned);
            row.put("actualQty", actual);
            row.put("standardUnitCost", stdUnit);
            row.put("actualCost", actualCost);
            row.put("priceVariance", v.price());
            row.put("usageVariance", v.usage());
            materials.add(row);
        }
        BigDecimal efficiency = BigDecimal.ZERO;
        BigDecimal stdLabour = BigDecimal.ZERO;
        BigDecimal stdOverhead = BigDecimal.ZERO;
        BigDecimal actualHours = BigDecimal.ZERO;
        BigDecimal stdHoursTotal = BigDecimal.ZERO;
        for (Map<String, Object> wo : db.list(WorkOrder.class, " AND \"productionOrderId\" = ? AND status <>"
                + " 'cancelled'"
                + " AND \"workCenterId\" IS NOT NULL", orderId)) {
            BigDecimal stdHours = wo.get("plannedDurationMins") instanceof Number n
                    ? BigDecimal.valueOf(n.longValue()).divide(SIXTY, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal hours = absorption.labourHours(wo);
            stdHoursTotal = stdHoursTotal.add(stdHours);
            actualHours = actualHours.add(hours);
            List<Map<String, Object>> rates = db.list(WorkCenterCostRate.class, " AND \"workCenterId\" = ?",
                    wo.get("workCenterId"));
            if (rates.isEmpty()) {
                continue;
            }
            Map<String, Object> rate = rates.getFirst();
            efficiency = efficiency.add(VarianceCalculator.efficiency(hours, stdHours, dec(rate, "labourRatePerHour")));
            stdLabour = stdLabour.add(stdHours.multiply(dec(rate, "labourRatePerHour")));
            stdOverhead = stdOverhead.add(stdHours.multiply(dec(rate, "overheadRatePerHour")));
        }
        Map<String, BigDecimal> cost = buckets(orderId, order);
        BigDecimal overhead = VarianceCalculator.round(cost.get("overhead").subtract(stdOverhead));
        BigDecimal expected = expectedOutput(order);
        BigDecimal stdTotal = stdMaterial.add(stdLabour).add(stdOverhead);
        BigDecimal stdUnitCost = expected.signum() > 0 ? stdTotal.divide(expected, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal yieldVariance = "completed".equals(order.get("status"))
                ? VarianceCalculator.yield(expected, dec(order, "producedQty"), stdUnitCost)
                : BigDecimal.ZERO.setScale(2);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("orderCode", order.get("orderCode"));
        out.put("expectedOutput", expected);
        out.put("producedQty", order.get("producedQty"));
        out.put("standardUnitCost", stdUnitCost);
        out.put("actualHours", actualHours.setScale(2, RoundingMode.HALF_UP));
        out.put("standardHours", stdHoursTotal.setScale(2, RoundingMode.HALF_UP));
        out.put("materials", materials);
        out.put("materialPrice", price);
        out.put("materialUsage", usage);
        out.put("labourEfficiency", efficiency);
        out.put("overhead", overhead);
        out.put("yield", yieldVariance);
        out.put("total", price.add(usage).add(efficiency).add(overhead).add(yieldVariance));
        return out;
    }

    private BigDecimal expectedOutput(Map<String, Object> order) {
        Map<String, Object> bom = order.get("bomId") == null ? null : db.byId(Bom.class, str(order, "bomId"));
        BigDecimal yieldPct = bom == null ? HUNDRED : dec(bom, "expectedYieldPct");
        return dec(order, "plannedQty").multiply(yieldPct).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    /** Standard unit cost per material from the BOM's current cost roll-up. */
    private Map<String, BigDecimal> standardUnits(Map<String, Object> order) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (order.get("bomId") == null) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT e->>'productId' AS \"productId\","
                + " (e->>'unitCost')::numeric AS \"unitCost\" FROM \"BomStandardCost\" s,"
                + " jsonb_array_elements(s.breakdown::jsonb) e WHERE s.\"organizationId\" = ? AND s.\"bomId\" = ?"
                + " AND s.\"isCurrent\" = true AND e->>'kind' = 'material'").params(MfgDb.org(), order.get("bomId")))) {
            out.put(str(r, "productId"), dec(r, "unitCost"));
        }
        return out;
    }

    /** Variances of the orders completed in the period: totals and a row per product. */
    public Map<String, Object> variances(String from, String to, String productId) {
        Range r = range(from, to);
        List<Object> params = new ArrayList<>(List.of(MfgDb.org(), r.start(), r.end()));
        String extra = "";
        if (CrushingSupport.text(productId) != null) {
            extra = " AND o.\"outputProductId\" = ?";
            params.add(productId);
        }
        List<String> kinds = List.of("materialPrice", "materialUsage", "labourEfficiency", "overhead", "yield",
                "total");
        Map<String, Object> totals = new LinkedHashMap<>();
        kinds.forEach(k -> totals.put(k, BigDecimal.ZERO));
        Map<String, Map<String, Object>> products = new LinkedHashMap<>();
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> o : SqlRows.list(db.jdbc().sql("SELECT o.id, o.\"orderCode\", o.\"outputProductId\","
                + " p.name AS \"productName\" FROM \"ProductionOrder\" o LEFT JOIN \"Product\" p ON p.id ="
                + " o.\"outputProductId\" WHERE o.\"organizationId\" = ? AND o.status = 'completed'"
                + " AND o.\"completedAt\" >= ? AND o.\"completedAt\" < ?" + extra
                + " ORDER BY o.\"completedAt\" LIMIT 500").params(params))) {
            Map<String, Object> v = orderVariances(str(o, "id"));
            Map<String, Object> product = products.computeIfAbsent(str(o, "outputProductId"), k -> {
                Map<String, Object> init = Obj.of("productId", k, "productName", o.get("productName"));
                kinds.forEach(c -> init.put(c, BigDecimal.ZERO));
                return init;
            });
            Map<String, Object> row = Obj.of("orderId", o.get("id"), "orderCode", o.get("orderCode"), "productName",
                    o.get("productName"));
            for (String k : kinds) {
                BigDecimal value = (BigDecimal) v.get(k);
                row.put(k, value);
                totals.put(k, ((BigDecimal) totals.get(k)).add(value));
                product.put(k, ((BigDecimal) product.get(k)).add(value));
            }
            orders.add(row);
        }
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "totals", totals, "byProduct",
                new ArrayList<>(products.values()), "orders", orders);
    }

    // ================================================================== period reports

    /**
     * Finished output with its cost: crushing fractions, last-stage output of staged orders, plain completed orders.
     */
    private List<Map<String, Object>> facts(Range r) {
        Object from = java.sql.Date.valueOf(r.from());
        Object to = java.sql.Date.valueOf(r.to());
        return SqlRows.list(db.jdbc().sql("""
                SELECT r."runDate"::timestamp AS at, r.shift AS shift, c.name AS plant, x."productId",
                       pr.name AS "productName", x."actualTonnes" AS qty, coalesce(x.value, 0) AS cost
                FROM "CrushingRunOutput" x JOIN "CrushingRun" r ON r.id = x."runId"
                JOIN "CrushingPlant" c ON c.id = r."plantId" JOIN "Product" pr ON pr.id = x."productId"
                WHERE x."organizationId" = ? AND r.status = 'posted' AND r."deletedAt" IS NULL
                  AND r."runDate" >= ? AND r."runDate" <= ? AND x."actualTonnes" > 0
                UNION ALL
                SELECT ps."postedAt", coalesce(sf.name, b.shift), w.name, l."productId", pr.name, l.quantity, l.value
                FROM "ProductionStagePostingLine" l JOIN "ProductionStagePosting" ps ON ps.id = l."postingId"
                JOIN "ProductionOrderStage" s ON s.id = l."stageId" LEFT JOIN "WorkCenter" w ON w.id = s."workCenterId"
                LEFT JOIN "Product" pr ON pr.id = l."productId"
                LEFT JOIN "PrecastCuringBatch" b ON b."cubingPostingId" = ps.id
                LEFT JOIN "ShopFloorProductionReport" rep ON rep."stagePostingId" = ps.id
                LEFT JOIN "ShopFloorShiftReport" sr ON sr.id = rep."shiftReportId"
                LEFT JOIN "ShopFloorShift" sf ON sf.id = sr."shiftId"
                WHERE l."organizationId" = ? AND ps."reversedAt" IS NULL AND l.role = 'main_output' AND s."isLast"
                  AND ps."postedAt" >= ? AND ps."postedAt" < ?
                  AND NOT EXISTS (SELECT 1 FROM "CrushingRun" cr WHERE cr."productionOrderId" = ps."orderId")
                UNION ALL
                SELECT o."completedAt", NULL::text, NULL::text, o."outputProductId", pr.name, o."producedQty",
                       o."totalCost"
                FROM "ProductionOrder" o LEFT JOIN "Product" pr ON pr.id = o."outputProductId"
                WHERE o."organizationId" = ? AND o.status = 'completed' AND o."completedAt" >= ? AND o."completedAt" < ?
                  AND NOT EXISTS (SELECT 1 FROM "ProductionOrderStage" s2 WHERE s2."orderId" = o.id)
                """).params(MfgDb.org(), from, to, MfgDb.org(), r.start(), r.end(), MfgDb.org(), r.start(), r.end()));
    }

    /** Output, cost and unit cost grouped by shift, plant, product (default) or month. */
    public Map<String, Object> summary(String from, String to, String groupBy) {
        Range r = range(from, to);
        String group = groupBy == null || groupBy.isBlank() ? "product" : groupBy;
        if (!List.of("shift", "plant", "product", "month").contains(group)) {
            throw ApiException.badRequest("Group by one of: shift, plant, product, month");
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (Map<String, Object> f : facts(r)) {
            String key;
            String label;
            switch (group) {
                case "shift" -> {
                    key = f.get("shift") == null ? "-" : str(f, "shift");
                    label = key;
                }
                case "plant" -> {
                    key = f.get("plant") == null ? "-" : str(f, "plant");
                    label = key;
                }
                case "month" -> {
                    key = YearMonth.from(((Instant) f.get("at")).atOffset(ZoneOffset.UTC)).toString();
                    label = key;
                }
                default -> {
                    key = str(f, "productId");
                    label = str(f, "productName");
                }
            }
            Map<String, Object> row = rows.computeIfAbsent(key, k -> Obj.of("key", k, "label", label, "quantity",
                    BigDecimal.ZERO, "cost", BigDecimal.ZERO));
            row.put("quantity", dec(row, "quantity").add(dec(f, "qty")));
            row.put("cost", dec(row, "cost").add(dec(f, "cost")));
            qty = qty.add(dec(f, "qty"));
            cost = cost.add(dec(f, "cost"));
        }
        for (Map<String, Object> row : rows.values()) {
            row.put("unitCost", unit(dec(row, "cost"), dec(row, "quantity")));
        }
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "groupBy", group, "rows",
                new ArrayList<>(rows.values()), "totals", Obj.of("quantity", qty, "cost", cost, "unitCost",
                        unit(cost, qty)));
    }

    private static BigDecimal unit(BigDecimal cost, BigDecimal qty) {
        return qty.signum() > 0 ? cost.divide(qty, SCALE, RoundingMode.HALF_UP) : null;
    }

    /**
     * Gross margin of posted sales by product (default) or customer. Cost of a sold unit = the period's production cost
     * per unit of the product, else its average stock cost, else its cost price; each row says which.
     */
    public Map<String, Object> margin(String from, String to, String groupBy) {
        return margin(from, to, groupBy, new MarginScope(null, null, null));
    }

    /** Narrowing of {@link #margin(String, String, String)} for analytics (ADR-S020); null means no limit. */
    public record MarginScope(String[] branchIds, String productId, String partnerId) {

        private String where(String header, String lineProduct, List<Object> params) {
            StringBuilder w = new StringBuilder();
            if (branchIds != null) {
                w.append(" AND ").append(header).append(".\"branchId\" = ANY(?::text[])");
                params.add(branchIds);
            }
            if (productId != null && !productId.isBlank()) {
                w.append(" AND ").append(lineProduct).append(" = ?");
                params.add(productId);
            }
            if (partnerId != null && !partnerId.isBlank()) {
                w.append(" AND ").append(header).append(".\"partnerId\" = ?");
                params.add(partnerId);
            }
            return w.toString();
        }
    }

    /** {@link #margin(String, String, String)} over a {@link MarginScope}. */
    public Map<String, Object> margin(String from, String to, String groupBy, MarginScope scope) {
        Range r = range(from, to);
        String group = groupBy == null || groupBy.isBlank() ? "product" : groupBy;
        if (!List.of("product", "customer").contains(group)) {
            throw ApiException.badRequest("Group by one of: product, customer");
        }
        Map<String, BigDecimal[]> produced = new HashMap<>();
        for (Map<String, Object> f : facts(r)) {
            BigDecimal[] a = produced.computeIfAbsent(str(f, "productId"),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(dec(f, "qty"));
            a[1] = a[1].add(dec(f, "cost"));
        }
        Map<String, Object[]> unitCosts = new HashMap<>();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        List<Object> params = new ArrayList<>(List.of(MfgDb.org(), r.start(), r.end()));
        String invoiceWhere = scope.where("v", "i.\"productId\"", params);
        params.addAll(List.of(MfgDb.org(), r.start(), r.end()));
        String documentWhere = scope.where("d", "l.\"productId\"", params);
        for (Map<String, Object> s : SqlRows.list(db.jdbc().sql("""
                SELECT i."productId", p.name AS "productName", v."partnerId", pa.name AS "partnerName", i.quantity,
                       i.subtotal FROM "InvoiceItem" i JOIN "Invoice" v ON v.id = i."invoiceId"
                JOIN "Product" p ON p.id = i."productId" LEFT JOIN "Partner" pa ON pa.id = v."partnerId"
                WHERE i."organizationId" = ? AND v.status IN ('posted',
                        'paid') AND v."issueDate" >= ? AND v."issueDate" < ?""" + invoiceWhere + """

                UNION ALL
                SELECT l."productId", p.name, d."partnerId", pa.name, l.quantity, l.subtotal
                FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId" JOIN "Product" p ON p.id = l."productId"
                LEFT JOIN "Partner" pa ON pa.id = d."partnerId"
                WHERE d."organizationId" = ? AND d."documentType" = 'sales_invoice' AND d.status IN ('posted', 'paid')
                  AND d."issueDate" >= ? AND d."issueDate" < ?""" + documentWhere).params(params))) {
            String productId = str(s, "productId");
            Object[] unit = unitCosts.computeIfAbsent(productId, k -> unitCost(k, produced.get(k)));
            BigDecimal sold = dec(s, "quantity");
            BigDecimal value = dec(s, "subtotal");
            BigDecimal cost = sold.multiply((BigDecimal) unit[0]).setScale(2, RoundingMode.HALF_UP);
            boolean byProduct = "product".equals(group);
            String key = byProduct ? productId : s.get("partnerId") == null ? "-" : str(s, "partnerId");
            String label = byProduct ? str(s, "productName") : str(s, "partnerName");
            Map<String, Object> row = rows.computeIfAbsent(key, k -> {
                Map<String, Object> init = Obj.of("key", k, "label", label, "quantity", BigDecimal.ZERO, "revenue",
                        BigDecimal.ZERO, "cost", BigDecimal.ZERO);
                if (byProduct) {
                    init.put("costBasis", unit[1]);
                }
                return init;
            });
            row.put("quantity", dec(row, "quantity").add(sold));
            row.put("revenue", dec(row, "revenue").add(value));
            row.put("cost", dec(row, "cost").add(cost));
            revenue = revenue.add(value);
            costTotal = costTotal.add(cost);
        }
        for (Map<String, Object> row : rows.values()) {
            BigDecimal margin = dec(row, "revenue").subtract(dec(row, "cost"));
            row.put("margin", margin);
            row.put("marginPct", pct(margin, dec(row, "revenue")));
        }
        BigDecimal margin = revenue.subtract(costTotal);
        return Obj.of("from", r.from().toString(), "to", r.to().toString(), "groupBy", group, "rows",
                new ArrayList<>(rows.values()), "totals", Obj.of("revenue", revenue, "cost", costTotal, "margin",
                        margin,
                        "marginPct", pct(margin, revenue)));
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() > 0 ? part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP) : null;
    }

    /** {@code [unit cost, basis]}. */
    private Object[] unitCost(String productId, BigDecimal[] produced) {
        if (produced != null && produced[0].signum() > 0) {
            return new Object[] {produced[1].divide(produced[0], SCALE, RoundingMode.HALF_UP), "production"};
        }
        BigDecimal average = db.jdbc().sql("SELECT CASE WHEN sum(quantity) > 0 THEN sum(quantity *"
                + " \"runningAverageCost\") / sum(quantity) END FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ?").params(MfgDb.org(),
                        productId).query(BigDecimal.class).optional().orElse(null);
        if (average != null && average.signum() > 0) {
            return new Object[] {average, "stock_average"};
        }
        BigDecimal price = db.jdbc().sql("SELECT \"costPrice\" FROM \"Product\" WHERE id = ?").param(productId)
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        return new Object[] {price, "cost_price"};
    }
}
