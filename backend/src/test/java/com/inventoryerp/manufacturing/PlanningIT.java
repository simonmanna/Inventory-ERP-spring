package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.web.PlanningController;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Advanced planning (Track C 2.5, ADR-S017): demand, pegging, backward scheduling, Gantt, capacity, shift plans. */
class PlanningIT extends MfgTestSupport {

    static final String P = "/manufacturing/planning";

    final LocalDate monday = LocalDate.now(ZoneOffset.UTC).plusWeeks(3).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    /** A work centre working Monday to Friday, 08:00 to 17:00. */
    String centre(String code) {
        String id = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", code + "-" + suffix(), "name", code))
                .get("id").asString();
        List<Map<String, Object>> shifts = new ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            shifts.add(Map.of("weekday", d, "startMinute", 480, "endMinute", 1020));
        }
        ok(200, "PUT", "/manufacturing/work-centers/" + id + "/calendar", Map.of("shifts", shifts));
        return id;
    }

    String routing(String bomId, String... centres) {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (int i = 0; i < centres.length; i++) {
            ops.add(Map.of("sequence", i + 1, "name", "Op " + (i + 1), "workCenterId", centres[i], "durationMins", 60));
        }
        return ok(201, "POST", "/manufacturing/routings", Map.of("name", "Line " + suffix(), "bomId", bomId,
                "operations", ops)).get("id").asString();
    }

    List<String> operations(String routingId) {
        return jdbc.sql("SELECT id FROM \"RoutingOperation\" WHERE \"routingId\" = ? ORDER BY"
                + " sequence").param(routingId)
                .query(String.class).list();
    }

    String order(String bomId) {
        return ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                bakery,
                "outputLocationId", warehouse)).get("id").asString();
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, false, true, now())").params(id, demoOrg, "PL-" + suffix(), name)
                .update();
        return id;
    }

    /** One delivery order line, as fleet would store it. */
    String delivery(String partnerId, String productId, int qty, LocalDate date, String priority) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrder\" (id, \"organizationId\", code, \"partnerId\", \"locationId\","
                + " \"requestedDate\", priority) VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, demoOrg, "DO-" + suffix(),
                        partnerId, warehouse, java.sql.Date.valueOf(date), priority).update();
        jdbc.sql("INSERT INTO \"FleetDeliveryOrderLine\" (id, \"organizationId\", \"deliveryOrderId\", \"lineNumber\","
                + " \"productId\", \"orderedQty\") VALUES (?, ?, ?, 1, ?, ?)").params(UUID.randomUUID().toString(),
                        demoOrg, id, productId, qty).update();
        return id;
    }

    @Test
    @DisplayName("backward schedule from the due date keeps the curing lag between the press and cubing")
    void backwardWithCuring() {
        String press = centre("PRESS");
        String cube = centre("CUBE");
        String block = product("BLOCK");
        String cement = product("CEM");
        String bomId = bom(block, cement, 1, 1, Map.of());
        String routingId = routing(bomId, press, cube);
        List<String> ops = operations(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                Map.of("operationId", ops.get(1), "minCuringDays", 7))));
        String orderId = order(bomId);
        LocalDate friday = monday.plusDays(11);

        JsonNode out = ok(200, "POST", P + "/orders/" + orderId + "/schedule-backward",
                Map.of("dueAt", at(friday, 17).toString()));
        assertThat(out.get("feasible").asBoolean()).isTrue();
        assertThat(out.get("scheduleMode").asString()).isEqualTo("backward");
        assertThat(out.get("curingDays").asInt()).isEqualTo(7);
        assertThat(out.get("lateMinutes").asLong()).isZero();
        JsonNode wos = out.get("workOrders");
        // Cubing: Friday 16:00 to 17:00. Seven days earlier the press must be done: the previous Friday by 16:00.
        assertThat(Instant.parse(wos.get(1).get("plannedStartAt").asString())).isEqualTo(at(friday, 16));
        assertThat(Instant.parse(wos.get(1).get("plannedEndAt").asString())).isEqualTo(at(friday, 17));
        assertThat(Instant.parse(wos.get(0).get("plannedEndAt").asString())).isEqualTo(at(friday.minusDays(7), 16));
        assertThat(Instant.parse(wos.get(0).get("plannedStartAt").asString())).isEqualTo(at(friday.minusDays(7), 15));

        JsonNode gantt = ok(200, "GET",
                P + "/gantt?workCenterId=" + cube + "&from=" + friday + "&to=" + friday.plusDays(1),
                null);
        assertThat(gantt.get("lanes")).hasSize(1);
        JsonNode bar = gantt.get("lanes").get(0).get("tasks").get(0);
        assertThat(bar.get("productionOrderId").asString()).isEqualTo(orderId);
        assertThat(bar.get("late").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a due date that cannot be met falls back to forward scheduling and records the lateness")
    void infeasible() {
        String wc = centre("LATE");
        String a = product("LATEA");
        String bomId = bom(a, product("LATEB"), 1, 1, Map.of());
        routing(bomId, wc);
        String orderId = order(bomId);
        JsonNode out = ok(200, "POST", P + "/orders/" + orderId + "/schedule-backward",
                Map.of("dueAt", LocalDate.now(ZoneOffset.UTC).minusDays(2).toString()));
        assertThat(out.get("feasible").asBoolean()).isFalse();
        assertThat(out.get("scheduleMode").asString()).isEqualTo("forward");
        assertThat(out.get("lateMinutes").asLong()).isPositive();
        assertThat(as("POST", P + "/orders/" + order(bomId) + "/schedule-backward", Map.of()).body().get("message")
                .asString()).isEqualTo("Set a due date first.");
    }

    @Test
    @DisplayName("open orders are scheduled by priority: urgent work takes the slot next to the due date")
    void priorities() {
        String wc = centre("PRIO");
        String bomId = bom(product("PRIOA"), product("PRIOB"), 1, 1, Map.of());
        routing(bomId, wc);
        String normal = order(bomId);
        String urgent = order(bomId);
        LocalDate friday = monday.plusDays(4);
        ok(200, "PUT", P + "/orders/" + normal, Map.of("dueAt", at(friday, 17).toString(), "priority", 2));
        ok(200, "PUT", P + "/orders/" + urgent, Map.of("dueAt", at(friday, 17).toString(), "priority", 4,
                "priorityReason", "Site pour"));
        ok(200, "POST", P + "/schedule-open-orders", Map.of("dueFrom", friday.toString(), "dueTo", friday.toString()));
        String urgentEnd = jdbc.sql("SELECT \"plannedEndAt\"::text FROM \"WorkOrder\" WHERE \"productionOrderId\" = ?")
                .param(urgent).query(String.class).single();
        String normalEnd = jdbc.sql("SELECT \"plannedEndAt\"::text FROM \"WorkOrder\" WHERE \"productionOrderId\" = ?")
                .param(normal).query(String.class).single();
        assertThat(urgentEnd).startsWith(friday + " 17:00");
        assertThat(normalEnd).startsWith(friday + " 16:00");

        JsonNode board = ok(200, "GET", P + "/orders", null);
        List<String> ids = new ArrayList<>();
        board.forEach(o -> ids.add(o.get("id").asString()));
        assertThat(ids.indexOf(urgent)).isLessThan(ids.indexOf(normal));
    }

    @Test
    @DisplayName("a Gantt drag snaps to working time, honours capacity and warns about the sequence")
    void reschedule() {
        String wc = centre("DRAG");
        String bomId = bom(product("DRAGA"), product("DRAGB"), 1, 1, Map.of());
        String routingId = routing(bomId, wc, wc);
        assertThat(operations(routingId)).hasSize(2);
        String orderId = order(bomId);
        JsonNode wos = ok(200, "POST", P + "/orders/" + orderId + "/schedule-backward",
                Map.of("dueAt", at(monday.plusDays(1), 17).toString())).get("workOrders");
        String last = wos.get(1).get("id").asString();

        JsonNode moved = ok(200, "POST", P + "/work-orders/" + last + "/reschedule",
                Map.of("startAt", at(monday, 6).toString()));
        assertThat(moved.get("snapped").asBoolean()).isTrue();
        assertThat(Instant.parse(moved.get("workOrder").get("plannedStartAt").asString())).isEqualTo(at(monday, 8));
        assertThat(moved.get("warnings").toString()).contains("Starts before");
        assertThat(moved.get("planning").get("scheduleMode").asString()).isEqualTo("manual");

        assertThat(as("POST", P + "/work-orders/" + UUID.randomUUID() + "/reschedule",
                Map.of("startAt", at(monday, 8).toString())).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("demand from the delivery schedule nets against stock by priority; the plan is pegged to the customer")
    void planFromDemand() {
        String stone = product("DEMAND");
        String input = product("DEMANDIN");
        String bomId = bom(stone, input, 1, 1, Map.of());
        stockIn(warehouse, stone, 10, 5);
        String contractor = partner("Contractor " + suffix());
        LocalDate date = monday.plusDays(2);
        delivery(contractor, stone, 20, date.minusDays(1), "normal");
        String urgent = delivery(contractor, stone, 30, date, "urgent");

        JsonNode demand = ok(200, "GET", P + "/demand?to=" + date.plusDays(1), null);
        List<JsonNode> mine = new ArrayList<>();
        demand.get("rows").forEach(r -> {
            if (stone.equals(r.get("productId").asString())) {
                mine.add(r);
            }
        });
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).get("demandId").asString()).isEqualTo(urgent);
        assertThat(mine.get(0).get("coveredQty").asDouble()).isEqualTo(10.0);
        assertThat(mine.get(0).get("uncoveredQty").asDouble()).isEqualTo(20.0);
        assertThat(mine.get(1).get("uncoveredQty").asDouble()).isEqualTo(20.0);

        JsonNode plan = ok(201, "POST", P + "/plans/from-demand", Map.of("dateTo", date.plusDays(1).toString(),
                "locationId", bakery, "productIds", List.of(stone)));
        assertThat(plan.get("lines")).hasSize(1);
        assertThat(plan.get("lines").get(0).get("targetQty").asDouble()).isEqualTo(40.0);
        assertThat(plan.get("lines").get(0).get("bomId").asString()).isEqualTo(bomId);
        assertThat(plan.get("pegs")).hasSize(2);

        String planId = plan.get("id").asString();
        JsonNode confirmed = ok(201, "POST", "/manufacturing/plans/" + planId + "/confirm", null);
        String orderId = confirmed.get("lines").get(0).get("generatedOrderId").asString();
        JsonNode pegs = ok(200, "GET", P + "/pegs?productionOrderId=" + orderId, null);
        assertThat(pegs).hasSize(2);
        assertThat(pegs.get(0).get("partnerId").asString()).isEqualTo(contractor);
        JsonNode row = null;
        for (JsonNode o : ok(200, "GET", P + "/orders", null)) {
            if (orderId.equals(o.get("id").asString())) {
                row = o;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("priority").asInt()).isEqualTo(4);
        assertThat(row.get("partnerId").asString()).isEqualTo(contractor);
        assertThat(Instant.parse(row.get("dueAt").asString())).isEqualTo(at(date.minusDays(1), 0));

        // The new order is open production now, so the same demand has nothing left to plan.
        assertThat(as("POST", P + "/plans/from-demand", Map.of("dateTo", date.plusDays(1).toString(), "locationId",
                bakery,
                "productIds", List.of(stone))).body().get("message").asString())
                .isEqualTo("Nothing to plan: stock and open production cover the demand.");
    }

    @Test
    @DisplayName("capacity: load by day in hours and quantity, overload flagged, bottleneck named")
    void capacity() {
        String wc = centre("CAP");
        jdbc.sql("INSERT INTO \"ShopFloorWorkCenterProfile\" (id, \"organizationId\", \"workCenterId\","
                + " \"idealRatePerHour\", \"updatedAt\") VALUES (?, ?, ?, 120, now())")
                .params(UUID.randomUUID().toString(), demoOrg, wc).update();
        String bomId = bom(product("CAPA"), product("CAPB"), 1, 1, Map.of());
        routing(bomId, wc);
        // 1,200 units at 120 per hour is ten hours, more than one nine-hour shift.
        String orderId = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "plannedQty", 1200,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        LocalDate wednesday = monday.plusDays(2);
        ok(200, "POST", P + "/orders/" + orderId + "/schedule-backward", Map.of("dueAt", at(wednesday, 17).toString()));

        JsonNode cap = ok(200, "GET", P + "/capacity?from=" + monday + "&to=" + wednesday + "&workCenterId=" + wc,
                null);
        JsonNode row = cap.get("workCenters").get(0);
        assertThat(row.get("capacityHours").asDouble()).isEqualTo(27.0);
        assertThat(row.get("loadHours").asDouble()).isEqualTo(10.0);
        assertThat(row.get("loadQty").asDouble()).isEqualTo(1200.0);
        assertThat(row.get("isBottleneck").asBoolean()).isTrue();
        assertThat(row.get("buckets")).hasSize(3);
        assertThat(row.get("buckets").get(2).get("loadHours").asDouble()).isEqualTo(9.0);
        assertThat(cap.get("bottleneck").get("workCenterId").asString()).isEqualTo(wc);
        assertThat(as("GET", P + "/capacity?bucket=year", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("shift plans are edited as drafts, published, then locked; targets validate their subject")
    void shiftPlansAndTargets() {
        String wc = centre("SHIFT");
        String shift = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ShopFloorShift\" (id, \"organizationId\", code, name, \"startMinute\", \"endMinute\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Day', 480, 1020, now())").params(shift, demoOrg, "D" + suffix())
                .update();
        try {
            shiftPlans(wc, shift);
        } finally {
            // The demo organization is shared: a lone day shift would stop later suites from seeding the 24-hour
            // default pattern, so shop-floor tests would find no shift outside 08:00-17:00.
            jdbc.sql("UPDATE \"ShopFloorShift\" SET \"isActive\" = false WHERE id = ?").param(shift).update();
        }
    }

    private void shiftPlans(String wc, String shift) {
        String productId = product("SPLAN");
        Map<String, Object> body = Map.of("shiftDate", monday.toString(), "shiftId", shift, "workCenterId", wc,
                "productId", productId, "targetQty", 500);
        JsonNode plan = ok(201, "POST", P + "/shift-plans", body);
        String id = plan.get("id").asString();
        assertThat(plan.get("status").asString()).isEqualTo("draft");
        assertThat(plan.get("actualQty").asDouble()).isZero();
        assertThat(ok(200, "PUT", P + "/shift-plans/" + id, Map.of("shiftDate", monday.toString(), "shiftId", shift,
                "workCenterId", wc, "targetQty", 600)).get("targetQty").asDouble()).isEqualTo(600.0);
        assertThat(ok(200, "POST", P + "/shift-plans/" + id + "/publish", null).get("status").asString())
                .isEqualTo("published");
        assertThat(as("DELETE", P + "/shift-plans/" + id, null).status()).isEqualTo(400);
        assertThat(ok(200, "GET", P + "/shift-plans?workCenterId=" + wc,
                null).get("meta").get("total").asInt()).isEqualTo(1);

        JsonNode target = ok(201, "POST", P + "/targets", Map.of("scope", "work_center", "workCenterId", wc,
                "periodFrom", monday.toString(), "periodTo", monday.plusDays(6).toString(), "targetQty", 1000));
        assertThat(as("POST", P + "/targets", Map.of("scope", "crushing_plant", "plantId", UUID.randomUUID().toString(),
                "periodFrom", monday.toString(), "periodTo", monday.toString(), "targetQty",
                        1)).status()).isEqualTo(404);
        JsonNode report = ok(200, "GET", P + "/reports/production-vs-target?from=" + monday + "&to=" + monday, null);
        assertThat(report.get("rows").toString()).contains(target.get("id").asString());
        ok(204, "DELETE", P + "/targets/" + target.get("id").asString(), null);
    }

    @Test
    @DisplayName("production vs plan and plan adherence read the plan back")
    void reports() {
        String a = product("RPT");
        bom(a, product("RPTIN"), 1, 1, Map.of());
        JsonNode plan = ok(201, "POST", "/manufacturing/plans", Map.of("name", "Report", "locationId", bakery));
        String planId = plan.get("id").asString();
        ok(201, "POST", "/manufacturing/plans/" + planId + "/lines", Map.of("lines", List.of(Map.of("productId", a,
                "targetQty", 40))));
        JsonNode report = ok(200, "GET", P + "/reports/production-vs-plan?planId=" + planId, null);
        assertThat(report.get("rows")).hasSize(1);
        assertThat(report.get("totals").get("targetQty").asDouble()).isEqualTo(40.0);
        assertThat(report.get("totals").get("attainmentPct").asDouble()).isZero();
        JsonNode adherence = ok(200, "GET", P + "/reports/plan-adherence", null);
        assertThat(adherence.get("orders").has("adherencePct")).isTrue();
        assertThat(ok(200, "GET", P + "/reports/shortfall", null).has("products")).isTrue();
        assertThat(ok(200, "GET", P + "/aggregates", null).has("plans")).isTrue();
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of it")
    void tenancy() {
        String bomId = bom(product("TPLAN"), product("TPLANB"), 1, 1, Map.of());
        routing(bomId, centre("TEN"));
        String orderId = order(bomId);
        ok(200, "PUT", P + "/orders/" + orderId, Map.of("priority", 3));
        assertThat(call("PUT", P + "/orders/" + orderId, Map.of("priority", 1), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", P + "/orders/" + orderId + "/schedule-backward", Map.of("dueAt", "2030-01-01"),
                otherToken).status()).isEqualTo(404);
        assertThat(call("GET", P + "/orders", null, otherToken).body().toString()).doesNotContain(orderId);
        assertThat(call("GET", P + "/pegs?productionOrderId=" + orderId, null, otherToken).body()).isEmpty();
    }

    @Test
    @DisplayName("authorization matrix of the planning routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(PlanningController.class)).isEqualTo(25);
    }
}
