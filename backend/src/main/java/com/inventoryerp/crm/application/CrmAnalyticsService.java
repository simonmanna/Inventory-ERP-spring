package com.inventoryerp.crm.application;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRM pipeline analytics (port of {@code crm-analytics.service.ts}): open-stage pipeline with weighted value, win
 * rate, expected-close forecast by month, top partners by open-deal value and the partner 360 spend card. Amounts
 * leave as JavaScript numbers.
 */
@Service
@Transactional(readOnly = true)
public class CrmAnalyticsService {

    private static final Map<String, Double> STAGE_WEIGHTS = Map.of("lead", 0.1, "qualified", 0.25, "proposal", 0.5,
            "negotiation", 0.8);
    private static final String OPEN = "stage::text IN ('lead', 'qualified', 'proposal', 'negotiation')";

    private final JdbcClient jdbc;

    public CrmAnalyticsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static double num(Object v) {
        return v == null ? 0 : new BigDecimal(v.toString()).doubleValue();
    }

    /** Per-stage counts + totals for open stages, plus open and weighted pipeline value. */
    public Map<String, Object> getPipeline() {
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT stage::text AS stage, count(*) AS n, SUM(amount) AS total FROM"
                + " \"Deal\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND " + OPEN + " GROUP BY stage")
                .param(org()).query().listOfRows()) {
            rows.put((String) r.get("stage"), r);
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        double open = 0;
        double weighted = 0;
        for (String stage : CrmService.OPEN_STAGES) {
            Map<String, Object> row = rows.get(stage);
            double total = row == null ? 0 : num(row.get("total"));
            double weight = STAGE_WEIGHTS.getOrDefault(stage, 0d);
            stages.add(Obj.of("stage", stage, "count", row == null ? 0 : ((Number) row.get("n")).longValue(),
                    "totalAmount", JsNumbers.js(total), "weight", weight));
            open += total;
            weighted += total * weight;
        }
        return Obj.of("stages", stages, "openPipelineValue", JsNumbers.js(open), "weightedValue",
                JsNumbers.js(weighted));
    }

    /** Won / lost counts and win rate within an optional {@code updatedAt} window. */
    public Map<String, Object> getWinRate(String from, String to) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL AND stage::text IN"
                + " ('won', 'lost')");
        if (from != null && !from.isEmpty()) {
            where.append(" AND \"updatedAt\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(from)));
        }
        if (to != null && !to.isEmpty()) {
            where.append(" AND \"updatedAt\" <= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(to)));
        }
        long won = 0;
        long lost = 0;
        for (Map<String, Object> r : jdbc.sql("SELECT stage::text AS stage, count(*) AS n FROM \"Deal\" WHERE" + where
                + " GROUP BY stage").params(params).query().listOfRows()) {
            long n = ((Number) r.get("n")).longValue();
            if ("won".equals(r.get("stage"))) {
                won = n;
            } else {
                lost = n;
            }
        }
        long total = won + lost;
        return Obj.of("won", won, "lost", lost, "total", total, "rate",
                total > 0 ? JsNumbers.js((double) won / total) : 0);
    }

    /**
     * Expected-close amount per calendar month for open deals. Month keys of the prefilled buckets are built like
     * Nest ({@code month + i + 1}, so a horizon crossing December yields {@code YYYY-13}); a non-numeric
     * {@code months} leaves no buckets and an unbounded horizon.
     */
    public List<Map<String, Object>> getForecast(Double months) {
        double m = months == null ? 3 : months;
        double limit = Double.isNaN(m) ? Double.NaN : Math.min(12, Math.max(1, m));
        ZonedDateTime now = ZonedDateTime.now(BusinessZone.get());
        Instant nowInstant = now.toInstant();
        Instant horizon = Double.isNaN(limit) ? null : now.withDayOfMonth(1).toLocalDate()
                .plusMonths((long) Math.floor(limit)).atStartOfDay(BusinessZone.get()).toInstant();
        Map<String, Double> buckets = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) {
            buckets.put(now.getYear() + "-" + pad(now.getMonthValue() + i), 0d);
        }
        for (Map<String, Object> d : jdbc.sql("SELECT amount, \"expectedClose\" FROM \"Deal\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND " + OPEN + " AND \"expectedClose\" IS NOT NULL").param(org()).query()
                .listOfRows()) {
            Instant close = ((Timestamp) d.get("expectedClose")).toLocalDateTime().toInstant(ZoneOffset.UTC);
            if (close.isBefore(nowInstant) || (horizon != null && !close.isBefore(horizon))) {
                continue;
            }
            ZonedDateTime local = close.atZone(BusinessZone.get());
            String key = local.getYear() + "-" + pad(local.getMonthValue());
            buckets.put(key, buckets.getOrDefault(key, 0d) + num(d.get("amount")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        buckets.forEach((month, amount) -> out.add(Obj.of("month", month, "expectedAmount", JsNumbers.js(amount))));
        return out;
    }

    private static String pad(int month) {
        return month < 10 ? "0" + month : Integer.toString(month);
    }

    /** Top partners by total open-deal value. */
    public List<Map<String, Object>> getTopCustomers(int limit) {
        int take = Math.min(50, Math.max(1, limit));
        List<Map<String, Object>> rows = jdbc.sql("SELECT \"partnerId\", count(*) AS n, SUM(amount) AS total FROM \"Deal\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND " + OPEN + " GROUP BY \"partnerId\""
                + " ORDER BY SUM(amount) DESC LIMIT " + take).param(org()).query().listOfRows();
        Map<String, Map<String, Object>> partners = new HashMap<>();
        if (!rows.isEmpty()) {
            for (Map<String, Object> p : jdbc.sql("SELECT id, name, code FROM \"Partner\" WHERE id IN (:ids) AND"
                    + " \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", rows.stream()
                    .map(r -> r.get("partnerId")).toList()).param("org", org()).query().listOfRows()) {
                partners.put((String) p.get("id"), p);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> p = partners.get((String) r.get("partnerId"));
            out.add(Obj.of("partnerId", r.get("partnerId"), "name", p == null ? "Unknown partner" : p.get("name"),
                    "code", p == null ? null : p.get("code"), "dealCount", ((Number) r.get("n")).longValue(),
                    "dealValue", JsNumbers.js(num(r.get("total")))));
        }
        return out;
    }

    /** Partner 360: completed sales spend, order count, last order and open receivable. */
    public Map<String, Object> getPartner360(String partnerId) {
        Map<String, Object> completed = jdbc.sql("SELECT count(*) AS n, SUM(\"totalAmount\") AS total, MAX(\"issueDate\")"
                + " AS last FROM \"Document\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND status::text IN"
                + " ('posted', 'paid') AND \"documentType\"::text IN ('sales_invoice', 'proforma_invoice')")
                .params(org(), partnerId).query().singleRow();
        Object receivable = jdbc.sql("SELECT SUM(\"amountResidual\") AS total FROM \"Document\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND status = 'posted' AND \"paymentStatus\"::text IN ('not_paid', 'partial')")
                .params(org(), partnerId).query().listOfRows().getFirst().get("total");
        Object last = completed.get("last");
        return Obj.of("totalSpent", JsNumbers.js(num(completed.get("total"))), "orderCount",
                ((Number) completed.get("n")).longValue(), "lastOrderAt", last == null ? null
                        : ((Timestamp) last).toLocalDateTime().toInstant(ZoneOffset.UTC),
                "openReceivable", JsNumbers.js(num(receivable)));
    }
}
