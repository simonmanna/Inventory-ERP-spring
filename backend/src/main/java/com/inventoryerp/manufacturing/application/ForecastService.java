package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demand forecast from sales history (port of {@code forecast.service.ts}): for each finished good with an active
 * BOM, average the last weeks of sales (direct + via menu items using it) by weekday and suggest a production
 * quantity for the target day. Weekdays are taken in UTC (the Nest server's zone in the stock deployment).
 *
 * <p>{@link #demandForecast} (Phase 5) serves aggregates and blocks: they sell by the truckload on sales invoices,
 * which the POS {@code InvoiceItem} history above never sees, and an aggregate may have no BOM. It reads posted
 * invoices and credit notes in base units plus POS sales, buckets them by Monday-based week and smooths them
 * ({@link DemandForecastCalculator}). Planning consumes it through {@link #horizonQuantities}.
 */
@Service
public class ForecastService {

    private static final String[] WEEKDAYS = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
    private static final long DAY_MS = 86_400_000L;
    /** Weeks of history unless the caller says otherwise: a quarter sees a trend without chasing last year. */
    static final int DEFAULT_HISTORY_WEEKS = 12;
    private static final int MIN_HISTORY_WEEKS = 2;
    private static final int MAX_HISTORY_WEEKS = 104;
    private static final int DEFAULT_HORIZON_DAYS = 30;
    private static final String AGGREGATE = "aggregate";
    private static final String BLOCK = "block";

    private final MfgDb db;
    private final BomService boms;

    public ForecastService(MfgDb db, BomService boms) {
        this.db = db;
        this.boms = boms;
    }

    /** {@code weeks} to average (default 4), {@code targetDate} to forecast (default tomorrow). */
    @Transactional(readOnly = true)
    public Map<String, Object> forecast(Double weeksParam, String targetDate) {
        double weeks = weeksParam != null && weeksParam > 0 ? weeksParam : 4;
        long now = System.currentTimeMillis();
        Instant since = Instant.ofEpochMilli(now - (long) (weeks * 7 * DAY_MS));
        Instant target = targetDate != null && !targetDate.isEmpty() ? EntityPatch.toInstant(targetDate)
                : Instant.ofEpochMilli(now + DAY_MS);
        int targetDow = dow(target);

        List<Map<String, Object>> products = SqlRows.list(db.jdbc().sql("SELECT id, name, code FROM \"Product\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"manufacturingRole\" = 'finished'"
                + " AND \"isActive\" = true").param(MfgDb.org()));
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Map<String, Object> p : products) {
            Map<String, Object> bom = boms.findActive(str(p, "id"), "");
            if (bom == null) {
                continue;
            }
            List<String> menuItemIds = db.jdbc().sql("SELECT \"menuItemId\" FROM \"MenuProduct\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ?").params(MfgDb.org(), p.get("id"))
                    .query(String.class).list();
            String or = menuItemIds.isEmpty() ? "i.\"productId\" = :pid"
                    : "(i.\"productId\" = :pid OR i.\"menuItemId\" IN (:menu))";
            var spec = db.jdbc().sql("SELECT i.quantity, v.\"issueDate\" FROM \"InvoiceItem\" i JOIN \"Invoice\" v"
                            + " ON v.id = i.\"invoiceId\" WHERE i.\"organizationId\" = :org AND " + or
                            + " AND v.\"issueDate\" >= :since AND v.status NOT IN ('cancelled', 'draft')")
                    .param("org", MfgDb.org()).param("pid", p.get("id")).param("since", MfgDb.param(since));
            if (!menuItemIds.isEmpty()) {
                spec = spec.param("menu", menuItemIds);
            }
            List<Map<String, Object>> items = SqlRows.list(spec);
            if (items.isEmpty()) {
                continue;
            }
            BigDecimal[] byDow = new BigDecimal[7];
            java.util.Arrays.fill(byDow, BigDecimal.ZERO);
            for (Map<String, Object> it : items) {
                Instant at = it.get("issueDate") instanceof Instant i ? i : Instant.now();
                int d = dow(at);
                byDow[d] = byDow[d].add(dec(it, "quantity"));
            }
            double[] avg = new double[7];
            Map<String, Object> averageByWeekday = new LinkedHashMap<>();
            for (int i = 0; i < 7; i++) {
                avg[i] = Money.div(byDow[i], BigDecimal.valueOf(weeks)).setScale(2, RoundingMode.HALF_UP).doubleValue();
                averageByWeekday.put(WEEKDAYS[i], JsNumbers.js(avg[i]));
            }
            long suggested = (long) Math.ceil(avg[targetDow]);
            if (suggested <= 0) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("productId", p.get("id"));
            s.put("name", p.get("name"));
            s.put("code", p.get("code"));
            s.put("bomId", bom.get("id"));
            s.put("averageByWeekday", averageByWeekday);
            s.put("suggestedQty", suggested);
            s.put("targetWeekday", WEEKDAYS[targetDow]);
            suggestions.add(s);
        }
        return Obj.of("weeks", JsNumbers.js(weeks), "targetWeekday", WEEKDAYS[targetDow], "suggestions", suggestions);
    }

    /** One product's weekly history, oldest first, and its smoothing. */
    private record Smoothed(String productId, String family, List<BigDecimal> weekly,
            DemandForecastCalculator.Result result) {}

    /**
     * Forecast of a horizon per product with a family or an active BOM: the weekly history, the level, trend and error,
     * and the horizon quantity. {@code family} narrows to {@code aggregate} or {@code block}. Defaults: from today, 30
     * days, 12 weeks of complete history.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> demandForecast(String from, String to, Integer weeksParam, String family) {
        if (family != null && !family.isBlank() && !AGGREGATE.equals(family) && !BLOCK.equals(family)) {
            throw ApiException.badRequest("family must be aggregate or block");
        }
        int weeks = historyWeeks(weeksParam);
        LocalDate first = day(from, LocalDate.now(ZoneOffset.UTC));
        LocalDate last = day(to, first.plusDays(DEFAULT_HORIZON_DAYS));
        if (last.isBefore(first)) {
            throw ApiException.badRequest("to must not be before from");
        }
        long days = ChronoUnit.DAYS.between(first, last) + 1;
        LocalDate firstWeek = firstHistoryWeek(weeks);
        List<Smoothed> forecasts = smoothed(firstWeek, weeks);
        Set<String> ids = new HashSet<>();
        forecasts.forEach(f -> ids.add(f.productId()));
        Map<String, Map<String, Object>> names = productNames(ids);
        List<Map<String, Object>> products = new ArrayList<>();
        for (Smoothed f : forecasts) {
            if (family != null && !family.isBlank() && !family.equals(f.family())) {
                continue;
            }
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < f.weekly().size(); i++) {
                history.add(Obj.of("weekStart", firstWeek.plusWeeks(i).toString(), "quantity", f.weekly().get(i)));
            }
            Map<String, Object> name = names.get(f.productId());
            DemandForecastCalculator.Result r = f.result();
            products.add(Obj.of("productId", f.productId(), "code", name == null ? null : name.get("code"), "name",
                    name == null ? null : name.get("name"), "family", f.family(), "history", history, "level",
                    r.level().setScale(3, RoundingMode.HALF_UP), "trendPerWeek",
                    r.trend().setScale(3, RoundingMode.HALF_UP), "meanAbsoluteError",
                    r.meanAbsoluteError().setScale(3, RoundingMode.HALF_UP), "nextWeekQty",
                    DemandForecastCalculator.week(r, 1).setScale(3, RoundingMode.HALF_UP), "horizonQty",
                    DemandForecastCalculator.horizon(r, days), "confidence", r.confidence()));
        }
        return Obj.of("from", first.toString(), "to", last.toString(), "weeks", weeks, "method", "smoothed", "products",
                products);
    }

    /** Horizon quantity per product, above zero only, for {@link PlanningDemandService}. */
    public Map<String, BigDecimal> horizonQuantities(LocalDate from, LocalDate to, int weeksParam) {
        int weeks = historyWeeks(weeksParam);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Smoothed f : smoothed(firstHistoryWeek(weeks), weeks)) {
            BigDecimal qty = DemandForecastCalculator.horizon(f.result(), days);
            if (qty.signum() > 0) {
                out.put(f.productId(), qty);
            }
        }
        return out;
    }

    private static int historyWeeks(Integer weeks) {
        int w = weeks == null ? DEFAULT_HISTORY_WEEKS : weeks;
        return Math.max(MIN_HISTORY_WEEKS, Math.min(w, MAX_HISTORY_WEEKS));
    }

    /** The Monday {@code weeks} complete weeks back: this week is still running and would read as a collapse. */
    private static LocalDate firstHistoryWeek(int weeks) {
        return LocalDate.now(ZoneOffset.UTC).with(DayOfWeek.MONDAY).minusWeeks(weeks);
    }

    private static LocalDate day(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("from / to must be ISO dates (YYYY-MM-DD)");
        }
    }

    private List<Smoothed> smoothed(LocalDate firstWeek, int weeks) {
        Map<String, String> families = families();
        List<Smoothed> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : weeklySales(firstWeek, weeks).entrySet()) {
            String family = families.get(e.getKey());
            if (family == null && boms.findActive(e.getKey(), "") == null) {
                continue;
            }
            List<BigDecimal> weekly = List.of(e.getValue());
            out.add(new Smoothed(e.getKey(), family, weekly, DemandForecastCalculator.smooth(weekly)));
        }
        return out;
    }

    /** Products on an active grading profile are aggregates; products of a mix design are blocks. */
    private Map<String, String> families() {
        Map<String, String> out = new HashMap<>();
        for (String id : db.jdbc().sql("SELECT DISTINCT l.\"productId\" FROM \"CrushingGradingLine\" l JOIN"
                + " \"CrushingGradingProfile\" g ON g.id = l.\"profileId\" WHERE l.\"organizationId\" = ? AND"
                + " g.status = 'active' AND g.\"deletedAt\" IS NULL").param(MfgDb.org()).query(String.class).list()) {
            out.put(id, AGGREGATE);
        }
        for (String id : db.jdbc().sql("SELECT DISTINCT \"productId\" FROM \"PrecastMixDesign\" WHERE"
                + " \"organizationId\" = ?").param(MfgDb.org()).query(String.class).list()) {
            out.put(id, BLOCK);
        }
        return out;
    }

    /** Net sales per product and week: invoices less credit notes in base units, plus POS sales less refunds. */
    private Map<String, BigDecimal[]> weeklySales(LocalDate firstWeek, int weeks) {
        Object since = MfgDb.param(firstWeek.atStartOfDay(ZoneOffset.UTC).toInstant());
        Object until = MfgDb.param(firstWeek.plusWeeks(weeks).atStartOfDay(ZoneOffset.UTC).toInstant());
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT h."productId", h.week, sum(h.qty) AS qty FROM (
                  SELECT l."productId", date_trunc('week', d."issueDate")::date AS week,
                         CASE WHEN d."documentType"::text = 'credit_note' THEN -1 ELSE 1 END
                           * l.quantity * COALESCE(l."uomFactor", 1) AS qty
                    FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
                   WHERE l."organizationId" = ? AND l."productId" IS NOT NULL
                     AND d."documentType"::text IN ('sales_invoice', 'credit_note')
                     AND d.status::text IN ('posted', 'paid') AND d."issueDate" >= ? AND d."issueDate" < ?
                  UNION ALL
                  SELECT i."productId", date_trunc('week', v."issueDate")::date, i.quantity - i."refundedQty"
                    FROM "InvoiceItem" i JOIN "Invoice" v ON v.id = i."invoiceId"
                   WHERE i."organizationId" = ? AND i."productId" IS NOT NULL
                     AND v.status::text NOT IN ('cancelled', 'draft') AND v."issueDate" >= ? AND v."issueDate" < ?
                ) h GROUP BY h."productId", h.week ORDER BY h."productId", h.week""")
                .params(MfgDb.org(), since, until, MfgDb.org(), since, until))) {
            int index = (int) ChronoUnit.WEEKS.between(firstWeek, (LocalDate) r.get("week"));
            if (index >= 0 && index < weeks) {
                BigDecimal[] series = out.computeIfAbsent(str(r, "productId"), k -> zeros(weeks));
                series[index] = dec(r, "qty").max(BigDecimal.ZERO);
            }
        }
        return out;
    }

    private static BigDecimal[] zeros(int weeks) {
        BigDecimal[] series = new BigDecimal[weeks];
        java.util.Arrays.fill(series, BigDecimal.ZERO);
        return series;
    }

    private Map<String, Map<String, Object>> productNames(Set<String> ids) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> p : SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Product\" WHERE"
                + " \"organizationId\" = :org AND id IN (:ids)").param("org", MfgDb.org()).param("ids",
                        List.copyOf(ids)))) {
            out.put(str(p, "id"), p);
        }
        return out;
    }

    /** JS {@code Date#getDay()} in UTC: 0 = Sunday. */
    private static int dow(Instant at) {
        return at.atOffset(ZoneOffset.UTC).getDayOfWeek().getValue() % 7;
    }
}
