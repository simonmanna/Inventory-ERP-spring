package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S006 package C: calendars + finite scheduling, time logs, scrap reasons, QC plans and rework. */
class ShopFloorIT extends MfgTestSupport {

    /** The DEMO organization's zone: work-centre calendars are local (integrity fix 2026-11-24, MP-7). */
    static final ZoneId ZONE = ZoneId.of("Africa/Kampala");

    @Test
    @DisplayName("finite scheduling follows shifts, skips closures and queues behind existing load")
    void scheduling() {
        JsonNode wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "OVN-" + suffix(), "name", "Oven"));
        String wcId = wc.get("id").asString();
        LocalDate monday = LocalDate.now(ZONE).plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // Mon–Fri 08:00–17:00; the Tuesday is closed.
        List<Map<String, Object>> shifts = new java.util.ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            shifts.add(Map.of("weekday", d, "startMinute", 480, "endMinute", 1020));
        }
        JsonNode cal = ok(200, "PUT", "/manufacturing/work-centers/" + wcId + "/calendar", Map.of("shifts", shifts,
                "closures", List.of(Map.of("date", monday.plusDays(1).toString(), "reason", "maintenance"))));
        assertThat(cal.get("shifts")).hasSize(5);
        assertThat(cal.get("closures")).hasSize(1);

        String flour = product("SFFLOUR");
        String pie = product("SFPIE");
        stockIn(bakery, flour, 10, 10);
        String bomId = bom(pie, flour, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Pie line", "bomId", bomId, "operations", List.of(
                Map.of("sequence", 1, "name", "Bake", "workCenterId", wcId, "durationMins", 100, "setupMins", 20),
                Map.of("sequence", 2, "name", "Cool", "workCenterId", wcId, "durationMins", 60))));
        String orderId = started(bomId).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null);

        Instant start = monday.atTime(16, 0).atZone(ZONE).toInstant();
        JsonNode wos = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/schedule",
                Map.of("startAt", start.toString()));
        // Bake (120 min): Mon 16:00–17:00, Tue closed, Wed 08:00–09:00.
        assertThat(Instant.parse(wos.get(0).get("plannedStartAt").asString())).isEqualTo(start);
        assertThat(Instant.parse(wos.get(0).get("plannedEndAt").asString()))
                .isEqualTo(monday.plusDays(2).atTime(9, 0).atZone(ZONE).toInstant());
        // Cool follows: Wed 09:00–10:00.
        assertThat(Instant.parse(wos.get(1).get("plannedEndAt").asString()))
                .isEqualTo(monday.plusDays(2).atTime(10, 0).atZone(ZONE).toInstant());

        // A second order on the same single-slot centre queues behind the first.
        String order2 = started(bomId).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + order2 + "/work-orders/generate", null);
        JsonNode wos2 = ok(201, "POST", "/manufacturing/production-orders/" + order2 + "/schedule",
                Map.of("startAt", start.toString()));
        assertThat(Instant.parse(wos2.get(0).get("plannedStartAt").asString()))
                .isEqualTo(monday.plusDays(2).atTime(10, 0).atZone(ZONE).toInstant());

        JsonNode gantt = ok(200, "GET", "/manufacturing/schedule?workCenterId=" + wcId + "&from=" + start
                + "&to=" + monday.plusDays(7).atStartOfDay().atZone(ZONE).toInstant(), null);
        assertThat(gantt).hasSize(4);
        assertThat(as("POST", "/manufacturing/production-orders/" + order2 + "/schedule", Map.of("startAt", "nope"))
                .status()).isEqualTo(400);

        // Manual time entries add up to the work order's labour.
        String woId = wos.get(0).get("id").asString();
        ok(201, "POST", "/manufacturing/work-orders/" + woId + "/time-logs", Map.of("minutes", 25, "kind", "setup"));
        ok(201, "POST", "/manufacturing/work-orders/" + woId + "/time-logs", Map.of("minutes", 15));
        assertThat(ok(200, "GET", "/manufacturing/work-orders/" + woId + "/time-logs", null)).hasSize(2);
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + orderId + "/work-orders", null).get(0)
                .get("labourMins").asInt()).isEqualTo(40);
    }

    @Test
    @DisplayName("scrap reasons, QC plan checklist validation and rework of a held batch")
    void qualityAndRework() {
        JsonNode r1 = ok(201, "POST", "/manufacturing/scrap-reasons", Map.of("code", "burnt", "name", "Burnt"));
        JsonNode r2 = ok(201, "POST", "/manufacturing/scrap-reasons", Map.of("code", "qc_rejection", "name", "QC"));
        try {
            assertThat(as("POST", "/manufacturing/scrap-reasons", Map.of("code", "burnt", "name", "x")).status())
                    .isEqualTo(409);
            String flour = product("QFLOUR");
            String tart = product("QTART");
            stockIn(bakery, flour, 20, 10);
            String bomId = bom(tart, flour, 10, 2, Map.of("qcRequired", true));
            JsonNode plan = ok(201, "POST", "/manufacturing/qc-plans", Map.of("name", "Tart QC", "productId", tart,
                    "items", List.of(Map.of("key", "temp", "label", "Core temp", "type", "number", "required", true,
                            "min", 70, "max", 95), Map.of("key", "sealed", "label", "Sealed", "type", "boolean"))));
            String id = started(bomId).get("id").asString();
            assertThat(ok(200, "GET", "/manufacturing/production-orders/" + id + "/qc-plan", null).get("id").asString())
                    .isEqualTo(plan.get("id").asString());
            ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete", Map.of("qtyProduced", 10));

            assertThat(as("POST", "/manufacturing/production-orders/" + id + "/qc", Map.of("passedQty", 5)).body()
                    .get("message").asString()).isEqualTo("Checklist item Core temp is required.");
            assertThat(as("POST", "/manufacturing/production-orders/" + id + "/qc", Map.of("passedQty", 5,
                    "checklist", List.of(Map.of("key", "temp", "value", 60)))).body().get("message").asString())
                    .isEqualTo("Checklist item Core temp is out of range.");
            assertThat(as("POST", "/manufacturing/production-orders/" + id + "/qc", Map.of("passedQty", 5,
                    "failedQty", 1, "wasteCategory", "dropped", "checklist", List.of(Map.of("key", "temp", "value", 80))))
                    .body().get("message").asString()).isEqualTo("Unknown scrap reason dropped.");

            JsonNode qc = ok(201, "POST", "/manufacturing/production-orders/" + id + "/qc", Map.of("passedQty", 6,
                    "failedQty", 1, "reworkQty", 3, "wasteCategory", "burnt", "checklist",
                    List.of(Map.of("key", "temp", "value", 80), Map.of("key", "sealed", "value", true))));
            assertThat(qc.get("status").asString()).isEqualTo("qc_hold");

            JsonNode rework = ok(201, "POST", "/manufacturing/production-orders/" + id + "/rework", Map.of());
            assertThat(rework.get("orderKind").asString()).isEqualTo("rework");
            assertThat(rework.get("parentOrderId").asString()).isEqualTo(id);
            assertThat(rework.get("materials").get(0).get("productId").asString()).isEqualTo(tart);
            assertThat(rework.get("materials").get(0).get("qtyPlanned").asString()).isEqualTo("3");
            assertThat(ok(200, "GET", "/manufacturing/production-orders/" + id, null).get("status").asString())
                    .isEqualTo("completed");
            Res again = as("POST", "/manufacturing/production-orders/" + id + "/rework", Map.of());
            assertThat(again.body().get("message").asString())
                    .isEqualTo("Only an order on QC hold can be reworked (status completed).");
            ok(204, "DELETE", "/manufacturing/qc-plans/" + plan.get("id").asString(), null);
        } finally {
            ok(204, "DELETE", "/manufacturing/scrap-reasons/" + r1.get("id").asString(), null);
            ok(204, "DELETE", "/manufacturing/scrap-reasons/" + r2.get("id").asString(), null);
        }
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    /**
     * IPR X-3: QC quarantine takes no manual stock-in, so one unit of {@code productId} (cost 10) goes on hold there by
     * an immediate transfer from the bakery.
     */
    private void holdInQuarantine(String quarantine, String productId) {
        stockIn(bakery, productId, 1, 10);
        String trf = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", bakery, "toLocationId",
                quarantine, "mode", "immediate", "responsibleById", adminId, "approvedById", adminId, "items",
                List.of(Map.of("productId", productId, "qtyRequested", 1)))).get("id").asString();
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", java.util.UUID.randomUUID().toString());
        assertThat(call("POST", "/inventory/transfers/" + trf + "/approve", Map.of(), h).status()).isIn(200, 201);
    }

    @Test
    @DisplayName("review 2026-11-17: an abnormal rework expenses its extra cost; the units keep what they carried")
    void abnormalRework() {
        String flour = product("RWFLOUR");
        String tart = product("RWTART");
        stockIn(bakery, flour, 20, 10);
        String bomId = bom(tart, flour, 10, 2, Map.of("qcRequired", true));
        String id = started(bomId).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete", Map.of("qtyProduced", 10));
        ok(201, "POST", "/manufacturing/production-orders/" + id + "/qc", Map.of("passedQty", 7, "reworkQty", 3));
        assertThat(as("POST", "/manufacturing/production-orders/" + id + "/rework", Map.of("treatment", "later"))
                .status()).isEqualTo(400);
        JsonNode rework = ok(201, "POST", "/manufacturing/production-orders/" + id + "/rework", Map.of("treatment",
                "abnormal", "materials", List.of(Map.of("productId", flour, "quantity", 1))));
        assertThat(rework.get("reworkTreatment").asString()).isEqualTo("abnormal");
        String rid = rework.get("id").asString();
        holdInQuarantine(rework.get("locationId").asString(), flour);
        String lossAccount = mapped("stock_adjustment_expense");
        BigDecimal wip0 = balance(wipAccount);
        BigDecimal loss0 = balance(lossAccount);
        ok(201, "POST", "/manufacturing/production-orders/" + rid + "/confirm", null);
        ok(201, "POST", "/manufacturing/production-orders/" + rid + "/start", Map.of());
        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + rid + "/complete", Map.of("qtyProduced",
                3));
        // the 3 tarts come back at the 2 a unit they carried; the extra flour (10) is a loss of the period
        assertThat(num(done.get("reworkLoss"))).isEqualByComparingTo("10");
        assertThat(num(done.get("outputUnitCost"))).isEqualByComparingTo("2");
        assertThat(balance(lossAccount).subtract(loss0)).isEqualByComparingTo("10");
        assertThat(balance(wipAccount).subtract(wip0)).isEqualByComparingTo("0");

        // reversing the rework order takes the loss back with it
        ok(201, "POST", "/manufacturing/production-orders/" + rid + "/reverse", Map.of("reason", "Test reversal"));
        assertThat(balance(lossAccount).subtract(loss0)).isEqualByComparingTo("0");
        assertThat(balance(wipAccount).subtract(wip0)).isEqualByComparingTo("0");

        // a normal rework (the default) keeps its cost in the units
        String id2 = started(bomId).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + id2 + "/complete", Map.of("qtyProduced", 10));
        ok(201, "POST", "/manufacturing/production-orders/" + id2 + "/qc", Map.of("passedQty", 8, "reworkQty", 2));
        JsonNode normal = ok(201, "POST", "/manufacturing/production-orders/" + id2 + "/rework", Map.of("materials",
                List.of(Map.of("productId", flour, "quantity", 1))));
        assertThat(normal.get("reworkTreatment").asString()).isEqualTo("normal");
        String nid = normal.get("id").asString();
        holdInQuarantine(normal.get("locationId").asString(), flour);
        ok(201, "POST", "/manufacturing/production-orders/" + nid + "/confirm", null);
        ok(201, "POST", "/manufacturing/production-orders/" + nid + "/start", Map.of());
        JsonNode kept = ok(201, "POST", "/manufacturing/production-orders/" + nid + "/complete", Map.of("qtyProduced",
                2));
        assertThat(num(kept.get("reworkLoss"))).isZero();
        assertThat(num(kept.get("outputUnitCost"))).isEqualByComparingTo("7"); // (2 × 2 + 10) / 2
    }
}
