package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demand forecast from sales history (port of {@code forecast.service.ts}): for each finished good with an active
 * BOM, average the last weeks of sales (direct + via menu items using it) by weekday and suggest a production
 * quantity for the target day. Weekdays are taken in UTC (the Nest server's zone in the stock deployment).
 */
@Service
public class ForecastService {

    private static final String[] WEEKDAYS = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
    private static final long DAY_MS = 86_400_000L;

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

    /** JS {@code Date#getDay()} in UTC: 0 = Sunday. */
    private static int dow(Instant at) {
        return at.atOffset(ZoneOffset.UTC).getDayOfWeek().getValue() % 7;
    }
}
