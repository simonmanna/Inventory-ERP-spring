package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADR-S006 package C: calendars + finite scheduling, time logs, scrap reasons, QC plans and rework. */
class ShopFloorIT extends MfgTestSupport {

    @Test
    @DisplayName("finite scheduling follows shifts, skips closures and queues behind existing load")
    void scheduling() {
        JsonNode wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "OVN-" + suffix(), "name", "Oven"));
        String wcId = wc.get("id").asString();
        LocalDate monday = LocalDate.now(ZoneOffset.UTC).plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
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

        Instant start = monday.atTime(16, 0).toInstant(ZoneOffset.UTC);
        JsonNode wos = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/schedule",
                Map.of("startAt", start.toString()));
        // Bake (120 min): Mon 16:00–17:00, Tue closed, Wed 08:00–09:00.
        assertThat(Instant.parse(wos.get(0).get("plannedStartAt").asString())).isEqualTo(start);
        assertThat(Instant.parse(wos.get(0).get("plannedEndAt").asString()))
                .isEqualTo(monday.plusDays(2).atTime(9, 0).toInstant(ZoneOffset.UTC));
        // Cool follows: Wed 09:00–10:00.
        assertThat(Instant.parse(wos.get(1).get("plannedEndAt").asString()))
                .isEqualTo(monday.plusDays(2).atTime(10, 0).toInstant(ZoneOffset.UTC));

        // A second order on the same single-slot centre queues behind the first.
        String order2 = started(bomId).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + order2 + "/work-orders/generate", null);
        JsonNode wos2 = ok(201, "POST", "/manufacturing/production-orders/" + order2 + "/schedule",
                Map.of("startAt", start.toString()));
        assertThat(Instant.parse(wos2.get(0).get("plannedStartAt").asString()))
                .isEqualTo(monday.plusDays(2).atTime(10, 0).toInstant(ZoneOffset.UTC));

        JsonNode gantt = ok(200, "GET", "/manufacturing/schedule?workCenterId=" + wcId + "&from=" + start
                + "&to=" + monday.plusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC), null);
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
}
