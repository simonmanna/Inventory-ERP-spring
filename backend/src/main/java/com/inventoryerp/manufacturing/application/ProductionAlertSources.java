package com.inventoryerp.manufacturing.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** What production tells the Phase 5 alerts: yield under the minimum and curing batches about to be ready. */
public final class ProductionAlertSources {

    private static final int YIELD_WINDOW_DAYS = 7;

    private ProductionAlertSources() {}

    /**
     * Below the threshold over the last 7 days: per crushing plant, output on feed ({@link CrushingReportService}); per
     * block product, good blocks on blocks cubed. A week, not a run: one poor run is noise, a poor week is a finding.
     */
    @Component
    public static class YieldBelowThreshold implements AlertSource {

        private final CrushingReportService crushing;
        private final MfgDb db;

        public YieldBelowThreshold(CrushingReportService crushing, MfgDb db) {
            this.crushing = crushing;
            this.db = db;
        }

        @Override
        public String alertType() {
            return AlertTypes.YIELD_BELOW_THRESHOLD;
        }

        @Override
        @Transactional(readOnly = true)
        public List<AlertItem> collect(AlertScan scan) {
            List<AlertItem> out = new ArrayList<>();
            if (scan.threshold() == null) {
                return out;
            }
            String from = scan.asOf().minusDays(YIELD_WINDOW_DAYS - 1L).toString();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> plants = (List<Map<String, Object>>) crushing.yieldReport(from,
                    scan.asOf().toString(), (String) null).get("plants");
            for (Map<String, Object> plant : plants) {
                BigDecimal actual = (BigDecimal) plant.get("actualYieldPct");
                if (actual != null && actual.compareTo(scan.threshold()) < 0) {
                    String shown = actual.setScale(1, RoundingMode.HALF_UP).toPlainString();
                    out.add(new AlertItem("crushing:" + plant.get("plantId"), AlertItem.WARNING, "Crushing plant "
                            + plant.get("plantCode") + " yield " + shown + " % over 7 days",
                            "Minimum " + scan.threshold().stripTrailingZeros().toPlainString() + " %. Check the grading"
                                    + " profile, the feed and the losses.", "/manufacturing/crushing/reports",
                            Obj.of("actualYieldPct", actual, "feedTonnes", plant.get("feedTonnes"), "outputTonnes",
                                    plant.get("outputTonnes"), "lossTonnes", plant.get("lossTonnes"))));
                }
            }
            Instant since = scan.asOf().minusDays(YIELD_WINDOW_DAYS - 1L).atStartOfDay(BusinessZone.get()).toInstant();
            for (Map<String, Object> row : SqlRows.list(db.jdbc().sql("SELECT p.id, p.name, sum(b.\"qtyGood\") AS good,"
                    + " sum(b.quantity) AS cured FROM \"PrecastCuringBatch\" b JOIN \"Product\" p ON p.id ="
                    + " b.\"productId\" WHERE b.\"organizationId\" = ? AND b.status IN ('cubed', 'released', 'failed')"
                    + " AND b.\"cubedAt\" >= ? GROUP BY p.id, p.name ORDER BY p.name")
                    .params(scan.organizationId(), SqlRows.ts(since)))) {
                BigDecimal rate = CrushingCalculator.pctOf(SqlRows.dec(row, "good"), SqlRows.dec(row, "cured"));
                if (rate != null && rate.compareTo(scan.threshold()) < 0) {
                    out.add(new AlertItem("blocks:" + row.get("id"), AlertItem.WARNING, row.get("name") + ": "
                            + rate.setScale(1, RoundingMode.HALF_UP).toPlainString() + " % good blocks over 7 days",
                            "Minimum " + scan.threshold().stripTrailingZeros().toPlainString() + " %. See the breakage"
                                    + " report.", "/manufacturing/precast/reports",
                            Obj.of("goodPct", rate, "good", row.get("good"), "cured", row.get("cured"))));
                }
            }
            return out;
        }
    }

    /** Batches still curing whose minimum curing time ends within the threshold, in hours, or has already ended. */
    @Component
    public static class CuringReleaseDue implements AlertSource {

        private static final int MAX_BATCHES = 200;

        private final PrecastProductionService precast;

        public CuringReleaseDue(PrecastProductionService precast) {
            this.precast = precast;
        }

        @Override
        public String alertType() {
            return AlertTypes.CURING_RELEASE_DUE;
        }

        @Override
        @Transactional(readOnly = true)
        public List<AlertItem> collect(AlertScan scan) {
            long hours = scan.threshold() == null ? 0 : scan.threshold().longValue();
            Instant now = Instant.now();
            Instant until = now.plus(Duration.ofHours(hours));
            List<AlertItem> out = new ArrayList<>();
            // Soonest first, so the first batch past the window ends the list.
            for (Map<String, Object> b : precast.batches("curing", null, null, null, null, 1, MAX_BATCHES).data()) {
                Instant ready = (Instant) b.get("curingUntil");
                if (ready.isAfter(until)) {
                    break;
                }
                boolean late = !now.isBefore(ready);
                out.add(new AlertItem((String) b.get("id"), late ? AlertItem.WARNING : AlertItem.INFO, "Curing batch "
                        + b.get("code") + " (" + b.get("productName") + ") " + (late ? "is ready to cube"
                                : "is ready on " + ready.atZone(BusinessZone.get()).toLocalDate()),
                        b.get("palletLocation") == null ? "Plan the cubing." : "Pallets at " + b.get("palletLocation")
                                + ". Plan the cubing.", "/manufacturing/precast/curing",
                        Obj.of("quantity", b.get("quantity"), "curingUntil", ready.toString())));
            }
            return out;
        }
    }
}
