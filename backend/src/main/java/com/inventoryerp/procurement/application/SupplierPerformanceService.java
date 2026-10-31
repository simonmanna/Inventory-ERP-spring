package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier scorecard (PROC-4): spend, on-time delivery and fill rate from purchase orders and posted receipts, quality
 * from incoming inspection lots, price variance from three-way match. The score is the weighted mean of the measures
 * a supplier has data for: on-time 40, quality 30, fill rate 20, price 10.
 */
@Service
public class SupplierPerformanceService {

    private static final BigDecimal WEIGHT_ON_TIME = BigDecimal.valueOf(40);
    private static final BigDecimal WEIGHT_QUALITY = BigDecimal.valueOf(30);
    private static final BigDecimal WEIGHT_FILL = BigDecimal.valueOf(20);
    private static final BigDecimal WEIGHT_PRICE = BigDecimal.valueOf(10);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Each 1 % paid over the ordered price costs 10 points of the price measure. */
    private static final BigDecimal PRICE_PENALTY = BigDecimal.TEN;

    private final JdbcClient jdbc;

    public SupplierPerformanceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The scorecard for {@code from..to} (default the last 90 days), best spend first. */
    @Transactional(readOnly = true)
    public Map<String, Object> report(String fromRaw, String toRaw, String partnerId) {
        return report(fromRaw, toRaw, partnerId, null);
    }

    /**
     * The scorecard limited to purchase orders of {@code branchIds} (null = every branch). Inspection lots carry no
     * branch, so a limited scorecard has no quality measure.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report(String fromRaw, String toRaw, String partnerId, String[] branchIds) {
        ZoneId zone = BusinessZone.get();
        LocalDate to = parseDate(toRaw) != null ? parseDate(toRaw) : LocalDate.now(zone);
        LocalDate from = parseDate(fromRaw) != null ? parseDate(fromRaw) : to.minusDays(90);
        if (from.isAfter(to)) {
            throw ApiException.badRequest("from must not be after to");
        }
        Timestamp start = SqlRows.ts(from.atStartOfDay(zone).toInstant());
        Timestamp end = SqlRows.ts(to.plusDays(1).atStartOfDay(zone).toInstant());
        Map<String, Map<String, Object>> byPartner = new LinkedHashMap<>();

        for (Map<String, Object> r : query("SELECT po.\"partnerId\", count(*) AS orders,"
                + " COALESCE(SUM(po.\"totalAmount\" * po.\"exchangeRate\"), 0) AS spend FROM \"PurchaseOrder\" po"
                + " WHERE po.\"organizationId\" = ? AND po.\"deletedAt\" IS NULL"
                + " AND po.status::text NOT IN ('draft', 'cancelled') AND po.\"orderDate\" >= ? AND po.\"orderDate\" "
                + "< ?",
                "po.\"partnerId\"", partnerId, start, end, branchIds)) {
            Map<String, Object> row = row(byPartner, r);
            row.put("orders", ((Number) r.get("orders")).longValue());
            row.put("spend", Money.dec(r.get("spend")));
        }
        for (Map<String, Object> r : query("SELECT po.\"partnerId\", count(*) AS receipts,"
                + " count(*) FILTER (WHERE po.\"expectedDeliveryDate\" IS NOT NULL) AS dated,"
                + " count(*) FILTER (WHERE r.\"receivedAt\"::date <= po.\"expectedDeliveryDate\"::date) AS \"onTime\","
                + " COALESCE(AVG(GREATEST(r.\"receivedAt\"::date - po.\"expectedDeliveryDate\"::date, 0))"
                + " FILTER (WHERE po.\"expectedDeliveryDate\" IS NOT NULL), 0) AS \"avgDaysLate\""
                + " FROM \"GoodsReceiptNote\" r JOIN \"PurchaseOrder\" po ON po.id = r.\"purchaseOrderId\""
                + " AND po.\"organizationId\" = r.\"organizationId\" WHERE r.\"organizationId\" = ?"
                + " AND r.status::text = 'posted' AND r.\"deletedAt\" IS NULL AND r.\"receivedAt\" >= ?"
                + " AND r.\"receivedAt\" < ?", "po.\"partnerId\"", partnerId, start, end, branchIds)) {
            Map<String, Object> row = row(byPartner, r);
            long dated = ((Number) r.get("dated")).longValue();
            row.put("receipts", ((Number) r.get("receipts")).longValue());
            row.put("onTimeRate", rate(((Number) r.get("onTime")).longValue(), dated));
            row.put("avgDaysLate", Money.dec(r.get("avgDaysLate")).setScale(1, RoundingMode.HALF_UP));
        }
        for (Map<String, Object> r : query("SELECT po.\"partnerId\", COALESCE(SUM(LEAST(l.\"receivedQuantity\","
                + " l.quantity)), 0) AS received, COALESCE(SUM(l.quantity), 0) AS ordered FROM \"PurchaseOrderLine\" l"
                + " JOIN \"PurchaseOrder\" po ON po.id = l.\"purchaseOrderId\" WHERE po.\"organizationId\" = ?"
                + " AND po.\"deletedAt\" IS NULL AND po.status::text NOT IN ('draft', 'cancelled')"
                + " AND po.\"orderDate\" >= ? AND po.\"orderDate\" < ?", "po.\"partnerId\"", partnerId, start, end,
                branchIds)) {
            BigDecimal ordered = Money.dec(r.get("ordered"));
            row(byPartner, r).put("fillRate", ordered.signum() == 0 ? null : Money.dec(r.get("received"))
                    .multiply(HUNDRED).divide(ordered, 1, RoundingMode.HALF_UP));
        }
        for (Map<String, Object> r : branchIds != null ? List.<Map<String, Object>>of() : query("SELECT"
                + " l.\"supplierPartnerId\" AS \"partnerId\", count(*) AS lots,"
                + " count(*) FILTER (WHERE l.result <> 'pending') AS evaluated,"
                + " count(*) FILTER (WHERE l.result = 'pass') AS passed,"
                + " COALESCE(SUM(l.\"rejectedQty\"), 0) AS \"rejectedQty\" FROM \"QualityInspectionLot\" l"
                + " WHERE l.\"organizationId\" = ? AND l.status <> 'cancelled' AND l.\"supplierPartnerId\" IS NOT NULL"
                + " AND l.\"createdAt\" >= ? AND l.\"createdAt\" < ?", "l.\"supplierPartnerId\"", partnerId, start,
                end, null)) {
            Map<String, Object> row = row(byPartner, r);
            row.put("inspectionLots", ((Number) r.get("lots")).longValue());
            row.put("qualityRate", rate(((Number) r.get("passed")).longValue(),
                    ((Number) r.get("evaluated")).longValue()));
            row.put("rejectedQty", JsNumbers.dec(Money.dec(r.get("rejectedQty"))));
        }
        for (Map<String, Object> r : query("SELECT po.\"partnerId\", COALESCE(SUM(m.\"priceVariance\" *"
                + " m.\"billedQuantity\"), 0) AS variance, COALESCE(SUM(m.\"orderedUnitPrice\" * "
                + "m.\"billedQuantity\"), 0)"
                + " AS base FROM \"ThreeWayMatch\" m JOIN \"PurchaseOrder\" po ON po.id = m.\"purchaseOrderId\""
                + " WHERE m.\"organizationId\" = ? AND m.\"billedUnitPrice\" IS NOT NULL AND po.\"deletedAt\" IS NULL"
                + " AND po.\"orderDate\" >= ? AND po.\"orderDate\" < ?", "po.\"partnerId\"", partnerId, start, end,
                branchIds)) {
            BigDecimal base = Money.dec(r.get("base"));
            BigDecimal variance = Money.dec(r.get("variance"));
            Map<String, Object> row = row(byPartner, r);
            row.put("priceVariance", JsNumbers.dec(variance));
            row.put("priceVarianceRate", base.signum() == 0 ? null : variance.multiply(HUNDRED).divide(base, 1,
                    RoundingMode.HALF_UP));
        }

        attachNames(byPartner);
        List<Map<String, Object>> rows = new ArrayList<>(byPartner.values());
        for (Map<String, Object> row : rows) {
            row.put("score", score(row));
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("spend")).reversed());
        BigDecimal spend = Money.ZERO;
        for (Map<String, Object> row : rows) {
            spend = spend.add((BigDecimal) row.get("spend"));
            row.put("spend", JsNumbers.dec((BigDecimal) row.get("spend")));
        }
        return Obj.of("from", from.toString(), "to", to.toString(), "rows", rows, "totals",
                Obj.of("suppliers", rows.size(), "spend", JsNumbers.dec(spend)));
    }

    private List<Map<String, Object>> query(String sql, String partnerColumn, String partnerId, Timestamp start,
            Timestamp end, String[] branchIds) {
        List<Object> params = new ArrayList<>(List.of(org(), start, end));
        StringBuilder full = new StringBuilder(sql);
        if (partnerId != null && !partnerId.isEmpty()) {
            full.append(" AND ").append(partnerColumn).append(" = ?");
            params.add(partnerId);
        }
        if (branchIds != null) {
            full.append(" AND po.\"branchId\" = ANY(?::text[])");
            params.add(branchIds);
        }
        full.append(" GROUP BY 1");
        return SqlRows.list(jdbc.sql(full.toString()).params(params));
    }

    private static Map<String, Object> row(Map<String, Map<String, Object>> byPartner, Map<String, Object> source) {
        return byPartner.computeIfAbsent((String) source.get("partnerId"), id -> Obj.of("partnerId", id, "partnerName",
                null, "orders", 0L, "spend", Money.ZERO, "receipts", 0L, "onTimeRate", null, "avgDaysLate", null,
                "fillRate", null, "inspectionLots", 0L, "qualityRate", null, "rejectedQty", "0", "priceVariance", "0",
                "priceVarianceRate", null, "score", null));
    }

    private void attachNames(Map<String, Map<String, Object>> byPartner) {
        if (byPartner.isEmpty()) {
            return;
        }
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE \"organizationId\" ="
                + " :org AND id IN (:ids)").param("org", org()).param("ids", List.copyOf(byPartner.keySet())))) {
            byPartner.get((String) p.get("id")).put("partnerName", p.get("name"));
        }
    }

    /** Weighted mean of the measures with data; null when the supplier has none. */
    static BigDecimal score(Map<String, Object> row) {
        BigDecimal sum = Money.ZERO;
        BigDecimal weight = Money.ZERO;
        BigDecimal priceRate = (BigDecimal) row.get("priceVarianceRate");
        BigDecimal priceScore = priceRate == null ? null
                : HUNDRED.subtract(priceRate.max(BigDecimal.ZERO).multiply(PRICE_PENALTY)).max(BigDecimal.ZERO);
        Object[][] measures = {{row.get("onTimeRate"), WEIGHT_ON_TIME}, {row.get("qualityRate"), WEIGHT_QUALITY},
                {row.get("fillRate"), WEIGHT_FILL}, {priceScore, WEIGHT_PRICE}};
        for (Object[] m : measures) {
            if (m[0] != null) {
                sum = sum.add(((BigDecimal) m[0]).multiply((BigDecimal) m[1]));
                weight = weight.add((BigDecimal) m[1]);
            }
        }
        return weight.signum() == 0 ? null : sum.divide(weight, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1,
                RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date: " + raw);
        }
    }
}
