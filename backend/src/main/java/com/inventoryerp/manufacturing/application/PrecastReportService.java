package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Block / precast analytics (ADR-S014, Phase 2.4): per order the yield (pressed, broken at the press and at cubing,
 * second grade, good), the cost build-up (materials, conversion by kind, finished-goods value, second-grade NRV,
 * abnormal scrap) and the <b>cost per acceptable block</b> (finished-goods value ÷ good blocks), plus the material
 * usage variance against the mix design (standard = quantity per batch × (1 + scrap %) × blocks processed at the
 * press ÷ units per batch). Period reports: cost per block by order / product and breakage by reason.
 */
@Service
public class PrecastReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 6;

    private final MfgDb db;

    public PrecastReportService(MfgDb db) {
        this.db = db;
    }

    /** Yield, cost and variance of one precast order. */
    Map<String, Object> orderCosting(String orderId, Map<String, Object> mix) {
        List<Map<String, Object>> stages = SqlRows.list(db.jdbc().sql("SELECT * FROM \"ProductionOrderStage\" WHERE"
                + " \"organizationId\" = ? AND \"orderId\" = ? ORDER BY sequence").params(MfgDb.org(), orderId));
        Map<String, Object> out = new LinkedHashMap<>();
        if (stages.size() < 2) {
            return out;
        }
        Map<String, Object> press = stages.get(stages.size() - 2);
        Map<String, Object> cure = stages.getLast();
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT l.* FROM \"ProductionStagePostingLine\" l"
                + " JOIN \"ProductionStagePosting\" p ON p.id = l.\"postingId\" WHERE l.\"organizationId\" = ?"
                + " AND p.\"orderId\" = ? AND p.\"reversedAt\" IS NULL").params(MfgDb.org(), orderId));
        BigDecimal good = BigDecimal.ZERO;
        BigDecimal fgValue = BigDecimal.ZERO;
        BigDecimal second = BigDecimal.ZERO;
        BigDecimal secondValue = BigDecimal.ZERO;
        BigDecimal wasteQty = BigDecimal.ZERO;
        BigDecimal scrapValue = BigDecimal.ZERO;
        BigDecimal material = BigDecimal.ZERO;
        Map<String, BigDecimal> conversion = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            String role = str(l, "role");
            BigDecimal q = dec(l, "quantity");
            BigDecimal v = dec(l, "value");
            boolean onCure = cure.get("id").equals(l.get("stageId"));
            switch (role) {
                case "main_output" -> {
                    if (onCure) {
                        good = good.add(q);
                        fgValue = fgValue.add(v);
                    }
                }
                case "by_product" -> {
                    second = second.add(q);
                    secondValue = secondValue.add(v);
                }
                case "waste" -> wasteQty = wasteQty.add(q);
                case "abnormal_scrap" -> scrapValue = scrapValue.add(v);
                case "material" -> material = material.add(v);
                case "conversion" -> conversion.merge(l.get("sourceRef") != null ? str(l, "sourceRef")
                        : String.valueOf(l.get("costSource")), v, BigDecimal::add);
                default -> { }
            }
        }
        BigDecimal pressedGood = dec(press, "qtyGood");
        BigDecimal brokenPress = dec(press, "qtyScrap");
        BigDecimal brokenCure = dec(cure, "qtyScrap");
        BigDecimal pressed = pressedGood.add(brokenPress);
        BigDecimal conversionTotal = conversion.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        out.put("blocksPressed", pressed);
        out.put("goodOffPress", pressedGood);
        out.put("brokenAtPress", brokenPress);
        out.put("brokenAtCubing", brokenCure);
        out.put("secondGrade", second);
        out.put("waste", wasteQty);
        out.put("goodBlocks", good);
        out.put("inCuring", pressedGood.subtract(good).subtract(second).subtract(brokenCure).max(BigDecimal.ZERO));
        out.put("rejectionRatePct", pressed.signum() == 0 ? null : brokenPress.add(brokenCure).add(second)
                .multiply(HUNDRED).divide(pressed, 2, RoundingMode.HALF_UP));
        out.put("materialCost", material);
        out.put("conversionCost", conversionTotal);
        out.put("conversionByKind", conversion);
        out.put("totalCost", material.add(conversionTotal));
        out.put("finishedGoodsValue", fgValue);
        out.put("secondGradeValue", secondValue);
        out.put("abnormalScrapValue", scrapValue);
        out.put("costPerAcceptableBlock", good.signum() == 0 ? null : fgValue.divide(good, SCALE, RoundingMode.HALF_UP));
        out.put("costPerPressedBlock", pressed.signum() == 0 ? null
                : material.add(conversionTotal).divide(pressed, SCALE, RoundingMode.HALF_UP));
        out.put("materialVariance", materialVariance(orderId, mix, dec(press, "qtyProcessed")));
        return out;
    }

    /** Actual vs standard usage of every order material, standard from the mix design per processed block. */
    List<Map<String, Object>> materialVariance(String orderId, Map<String, Object> mix, BigDecimal processed) {
        Map<String, Map<String, Object>> perBatch = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT * FROM \"BomLine\" WHERE \"bomId\" = ?"
                + " AND \"organizationId\" = ?").params(mix.get("bomId"), MfgDb.org()))) {
            perBatch.put(str(l, "componentProductId"), l);
        }
        BigDecimal unitsPerBatch = dec(mix, "unitsPerBatch");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : SqlRows.list(db.jdbc().sql("SELECT m.*, p.name AS \"productName\" FROM"
                + " \"ProductionMaterial\" m LEFT JOIN \"Product\" p ON p.id = m.\"productId\" WHERE m.\"orderId\" = ?"
                + " ORDER BY m.sequence").params(orderId))) {
            Map<String, Object> line = perBatch.get(str(m, "productId"));
            BigDecimal standard = BigDecimal.ZERO;
            if (line != null && unitsPerBatch.signum() > 0) {
                BigDecimal scrap = line.get("scrapPct") == null ? BigDecimal.ZERO : dec(line, "scrapPct");
                standard = dec(line, "quantity").multiply(BigDecimal.ONE.add(scrap.divide(HUNDRED, 10,
                        RoundingMode.HALF_UP))).multiply(processed).divide(unitsPerBatch, SCALE, RoundingMode.HALF_UP);
            }
            BigDecimal actual = dec(m, "qtyConsumed");
            BigDecimal unit = dec(m, "unitCost");
            BigDecimal variance = actual.subtract(standard);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", m.get("productId"));
            row.put("productName", m.get("productName"));
            row.put("qtyPlanned", m.get("qtyPlanned"));
            row.put("standardQty", standard);
            row.put("actualQty", actual);
            row.put("varianceQty", variance);
            row.put("variancePct", standard.signum() == 0 ? null : variance.multiply(HUNDRED).divide(standard, 2,
                    RoundingMode.HALF_UP));
            row.put("varianceValue", variance.multiply(unit).setScale(2, RoundingMode.HALF_UP));
            out.add(row);
        }
        return out;
    }

    /** Cost per acceptable block per precast order created in the period, with product totals. */
    @Transactional(readOnly = true)
    public Map<String, Object> costPerBlock(String from, String to, String productId) {
        return costPerBlock(from, to, productId, null);
    }

    /** {@link #costPerBlock(String, String, String)} limited to orders at locations of branches; null = all. */
    @Transactional(readOnly = true)
    public Map<String, Object> costPerBlock(String from, String to, String productId, String[] branchIds) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        String where = period(from, to, productId, branchIds, params);
        List<Map<String, Object>> orders = SqlRows.list(db.jdbc().sql("SELECT o.id, o.\"orderCode\", o.status,"
                + " o.\"createdAt\", o.\"outputProductId\", p.name AS \"productName\", m.\"bomId\","
                + " b.\"outputQuantity\" AS \"unitsPerBatch\" FROM \"ProductionOrder\" o JOIN \"PrecastMixDesign\" m"
                + " ON m.\"bomId\" = o.\"bomId\" AND m.\"organizationId\" = o.\"organizationId\""
                + " JOIN \"Bom\" b ON b.id = o.\"bomId\" LEFT JOIN \"Product\" p ON p.id = o.\"outputProductId\""
                + " WHERE o.\"organizationId\" = ? AND o.status <> 'cancelled'" + where
                + " ORDER BY o.\"createdAt\" LIMIT 500").params(params));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> o : orders) {
            Map<String, Object> c = orderCosting(str(o, "id"), o);
            if (c.isEmpty()) {
                continue;
            }
            c.remove("materialVariance");
            c.remove("conversionByKind");
            Map<String, Object> row = new LinkedHashMap<>(o);
            row.remove("bomId");
            row.remove("unitsPerBatch");
            row.putAll(c);
            rows.add(row);
            Map<String, Object> t = byProduct.computeIfAbsent(str(o, "outputProductId"), k -> {
                Map<String, Object> init = new LinkedHashMap<>();
                init.put("productId", k);
                init.put("productName", o.get("productName"));
                init.put("orders", 0);
                for (String f : List.of("blocksPressed", "goodBlocks", "secondGrade", "brokenAtPress",
                        "brokenAtCubing", "totalCost", "finishedGoodsValue")) {
                    init.put(f, BigDecimal.ZERO);
                }
                return init;
            });
            t.put("orders", (Integer) t.get("orders") + 1);
            for (String f : List.of("blocksPressed", "goodBlocks", "secondGrade", "brokenAtPress", "brokenAtCubing",
                    "totalCost", "finishedGoodsValue")) {
                t.put(f, ((BigDecimal) t.get(f)).add((BigDecimal) c.get(f)));
            }
        }
        for (Map<String, Object> t : byProduct.values()) {
            BigDecimal good = (BigDecimal) t.get("goodBlocks");
            BigDecimal pressed = (BigDecimal) t.get("blocksPressed");
            t.put("costPerAcceptableBlock", good.signum() == 0 ? null
                    : ((BigDecimal) t.get("finishedGoodsValue")).divide(good, SCALE, RoundingMode.HALF_UP));
            t.put("rejectionRatePct", pressed.signum() == 0 ? null : ((BigDecimal) t.get("brokenAtPress"))
                    .add((BigDecimal) t.get("brokenAtCubing")).add((BigDecimal) t.get("secondGrade"))
                    .multiply(HUNDRED).divide(pressed, 2, RoundingMode.HALF_UP));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("from", from);
        res.put("to", to);
        res.put("rows", rows);
        res.put("byProduct", new ArrayList<>(byProduct.values()));
        return res;
    }

    /** Broken blocks by reason and stage (pressing / cubing) plus second grade, per product, in the period. */
    @Transactional(readOnly = true)
    public Map<String, Object> breakage(String from, String to, String productId) {
        return breakage(from, to, productId, null);
    }

    /** {@link #breakage(String, String, String)} limited to orders at locations of {@code branchIds}; null = all. */
    @Transactional(readOnly = true)
    public Map<String, Object> breakage(String from, String to, String productId, String[] branchIds) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        String where = period(from, to, productId, branchIds, params);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT o.\"outputProductId\" AS \"productId\","
                + " p.name AS \"productName\", CASE WHEN s.\"isLast\" THEN 'cubing' ELSE 'pressing' END AS stage,"
                + " coalesce(ps.\"scrapReasonCode\", '') AS \"reasonCode\", sr.name AS \"reasonName\","
                + " sum(ps.\"qtyScrap\") AS quantity, count(*) AS postings FROM \"ProductionStagePosting\" ps"
                + " JOIN \"ProductionOrderStage\" s ON s.id = ps.\"stageId\" JOIN \"ProductionOrder\" o ON o.id ="
                + " ps.\"orderId\" JOIN \"PrecastMixDesign\" m ON m.\"bomId\" = o.\"bomId\" AND m.\"organizationId\" ="
                + " o.\"organizationId\" LEFT JOIN \"Product\" p ON p.id = o.\"outputProductId\""
                + " LEFT JOIN \"ScrapReason\" sr ON sr.code = ps.\"scrapReasonCode\" AND sr.\"organizationId\" ="
                + " ps.\"organizationId\" WHERE o.\"organizationId\" = ? AND ps.\"reversedAt\" IS NULL"
                + " AND ps.kind = 'output' AND ps.\"qtyScrap\" > 0" + where + " GROUP BY o.\"outputProductId\", p.name,"
                + " s.\"isLast\", ps.\"scrapReasonCode\", sr.name ORDER BY p.name, stage, quantity DESC").params(params));
        List<Object> p2 = new ArrayList<>(List.of(MfgDb.org()));
        String where2 = period(from, to, productId, branchIds, p2);
        List<Map<String, Object>> second = SqlRows.list(db.jdbc().sql("SELECT o.\"outputProductId\" AS \"productId\","
                + " p.name AS \"productName\", sum(b.\"qtySecondGrade\") AS \"secondGrade\", sum(b.\"qtyGood\")"
                + " AS \"goodBlocks\", sum(b.quantity) AS \"cured\" FROM \"PrecastCuringBatch\" b JOIN"
                + " \"ProductionOrder\" o ON o.id = b.\"orderId\" LEFT JOIN \"Product\" p ON p.id = o.\"outputProductId\""
                + " WHERE o.\"organizationId\" = ? AND b.status IN ('cubed', 'released', 'failed')" + where2
                + " GROUP BY o.\"outputProductId\", p.name ORDER BY p.name").params(p2));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("from", from);
        res.put("to", to);
        res.put("byReason", rows);
        res.put("secondGrade", second);
        return res;
    }

    private static String period(String from, String to, String productId, String[] branchIds, List<Object> params) {
        StringBuilder w = new StringBuilder();
        if (from != null && !from.isBlank()) {
            w.append(" AND o.\"createdAt\" >= ?");
            params.add(java.sql.Timestamp.valueOf(parse(from).atStartOfDay()));
        }
        if (to != null && !to.isBlank()) {
            w.append(" AND o.\"createdAt\" < ?");
            params.add(java.sql.Timestamp.valueOf(parse(to).plusDays(1).atStartOfDay()));
        }
        if (productId != null && !productId.isBlank()) {
            w.append(" AND o.\"outputProductId\" = ?");
            params.add(productId);
        }
        if (branchIds != null) {
            w.append(" AND o.\"locationId\" IN (SELECT bl.id FROM \"InventoryLocation\" bl WHERE"
                    + " bl.\"organizationId\" = o.\"organizationId\" AND bl.\"branchId\" = ANY(?::text[]))");
            params.add(branchIds);
        }
        return w.toString();
    }

    private static LocalDate parse(String d) {
        try {
            return LocalDate.parse(d.length() > 10 ? d.substring(0, 10) : d);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("from / to must be ISO dates (YYYY-MM-DD)");
        }
    }
}
