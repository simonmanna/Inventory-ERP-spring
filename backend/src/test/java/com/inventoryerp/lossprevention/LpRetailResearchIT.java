package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpInsightsService;
import com.inventoryerp.lossprevention.application.LpProjectionService;
import com.inventoryerp.lossprevention.web.LossPreventionResearchController;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * LP5, LP6b and LP7 through the API: zones over a scanner, a queue, a table, a store-room door and an entrance; a
 * replay of the retail rules that opens cases; a stock ledger line projected for a watched store room; camera counts
 * computed into footfall, queue and table figures; activities reviewed for research.
 *
 * <p>No camera and no detector run here: the observations are what an agent would send, so how well a camera counts is
 * not measured by this suite.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LpRetailResearchIT extends LpVisionSupport {

    @Autowired
    LpInsightsService insights;

    @Autowired
    LpProjectionService projection;

    /** A past day nobody else uses: DEMO is shared between suites and reruns. */
    final LocalDate day = LocalDate.of(2005, 1, 1).plusDays((long) (Math.random() * 1400));
    String agentId;
    String cameraId;
    String adminId;
    String scanZone;
    String queueZone;
    String diningZone;
    String storeZone;
    String doorZone;
    String tableId;
    String locationId;
    String productId;
    String firstActivity;
    String secondActivity;
    String thirdActivity;

    // ------------------------------------------------------------------ helpers

    /** A POS, kitchen or stock record, sent by the ERP (the admin's token). */
    Map<String, Object> record(String type, String time, String level, String source, Map<String, Object> ids,
            Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", "lp5-" + tag);
        e.put("sourceEventId", "rec-" + UUID.randomUUID());
        e.put("branchId", branch);
        e.put("businessDate", day.toString());
        e.put("occurredAt", at(time));
        e.put("schemaVersion", 1);
        e.put("type", type);
        e.putAll(ids);
        e.put("payload", payload);
        e.put("evidenceLevel", level);
        e.put("evidenceSource", source);
        e.put("observationStatus", "observed");
        return e;
    }

    /** What the edge agent saw in one of its zones; a gap is reported as unavailable. */
    Map<String, Object> seen(String zone, String type, String time, Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("source", "edge-" + tag);
        e.put("sourceEventId", "obs-" + UUID.randomUUID());
        e.put("branchId", branch);
        e.put("deviceId", zone);
        e.put("occurredAt", at(time));
        e.put("schemaVersion", 1);
        e.put("type", type);
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.put("cameraId", cameraId);
        e.put("payload", p);
        e.put("evidenceLevel", "zone");
        e.put("evidenceSource", "camera");
        e.put("observationStatus", "vision.gap".equals(type) ? "unavailable" : "observed");
        e.put("confidence", 0.9);
        return e;
    }

    String at(String time) {
        return day + "T" + time + "Z";
    }

    void sendRecords(List<Map<String, Object>> events) {
        JsonNode r = ok(201, "POST", "/loss-prevention/events", Map.of("events", events));
        assertThat(r.get("accepted").asInt()).as(r.toString()).isEqualTo(events.size());
    }

    void sendSeen(List<Map<String, Object>> events) {
        JsonNode r = agentOk(201, "POST", "/loss-prevention/events", Map.of("events", events));
        assertThat(r.get("accepted").asInt()).as(r.toString()).isEqualTo(events.size());
    }

    Map<String, Object> zone(String name, String type, Object polygon, Object... more) {
        Map<String, Object> z = new LinkedHashMap<>();
        z.put("cameraId", cameraId);
        z.put("name", name + " " + tag);
        z.put("zoneType", type);
        z.put("polygon", polygon);
        for (int i = 0; i < more.length; i += 2) {
            z.put((String) more[i], more[i + 1]);
        }
        return z;
    }

    static List<List<Double>> line(double x1, double y1, double x2, double y2) {
        return List.of(List.of(x1, y1), List.of(x2, y2));
    }

    String message(Map<String, Object> body) {
        Res r = as("POST", "/loss-prevention/zones", body);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        return r.body().get("message").asString();
    }

    JsonNode replay(String engine) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + adminToken);
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", "/loss-prevention/runs/replay", Map.of("engine", engine, "branchId", branch, "from",
                day.toString(), "to", day.toString()), headers);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    Map<String, Object> metricDay() {
        return jdbc.sql("SELECT state, \"lastError\" FROM \"LpMetricDay\" WHERE \"organizationId\" = ? AND"
                + " \"branchId\" = ? AND \"businessDate\" = ?").params(demoOrg, branch, java.sql.Date.valueOf(day))
                .query().singleRow();
    }

    /** Lets the day settle at once and runs what the insights job runs, until the day is computed. */
    void compute() {
        for (int i = 0; i < 5 && !"computed".equals(metricDay().get("state")); i++) {
            jdbc.sql("UPDATE \"LpMetricDay\" SET \"dirtyAt\" = \"dirtyAt\" - INTERVAL '1 hour' WHERE \"organizationId\""
                    + " = ? AND \"businessDate\" BETWEEN ? AND ?").params(demoOrg,
                    java.sql.Date.valueOf(day.minusDays(1)), java.sql.Date.valueOf(day.plusDays(1))).update();
            TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> insights.computeDue());
        }
    }

    String q() {
        return "from=" + day + "&to=" + day + "&branchId=" + branch;
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    Res review(String eventId, Map<String, Object> body) {
        return as("POST", "/loss-prevention/research/activities/" + eventId + "/review", body);
    }

    JsonNode activities(String filter) {
        return ok(200, "GET", "/loss-prevention/research/activities?" + q() + filter, null);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    @DisplayName("zones over a scanner, a queue, a table, a store room and an entrance are checked by type and link")
    void zones() throws Exception {
        Enrolled box = createAndEnrol("Shop box " + tag);
        agentId = box.agentId();
        agentToken = box.token();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        cameraId = ok(201, "POST", "/loss-prevention/cameras", Map.of("agentId", agentId, "name", "Shop cam " + tag,
                "streamAlias", "shop-1")).get("id").asString();
        tableId = jdbc.sql("SELECT id FROM \"PosTable\" WHERE \"organizationId\" = ? ORDER BY number LIMIT 1")
                .param(demoOrg).query(String.class).optional().orElse(null);
        if (tableId == null) {
            tableId = "zz-lp6b-" + UUID.randomUUID();
            jdbc.sql("INSERT INTO \"PosTable\" (id, \"organizationId\", name, number, \"updatedAt\") VALUES (?, ?, ?,"
                    + " ?, now())").params(tableId, demoOrg, "ZZ LP6b " + tag, 900_000 + (int) (Math.random() * 99_999))
                    .update();
        }
        // A store room and goods of this run only: the ids and codes sort last and the product is deleted, so no
        // suite that picks "the first product or location" of DEMO picks these.
        productId = "zz-lp5-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                + " \"updatedAt\", \"deletedAt\") VALUES (?, ?, ?, ?, 'stockable', 'AVCO'::\"CostingMethod\", now(),"
                + " now())").params(productId, demoOrg, "ZZ-LP5-" + tag, "ZZ LP5 goods " + tag).update();
        locationId = "zz-lp5-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'storage_room'::\"LocationType\", now())").params(locationId, demoOrg,
                "ZZ-LP5-" + tag, "ZZ LP5 store room " + tag).update();

        assertThat(message(zone("Scanner", "scan_area", square())))
                .isEqualTo("A scan_area zone must be linked to a till");
        assertThat(message(zone("Scanner", "scan_area", line(0.1, 0.5, 0.9, 0.5), "linkedType", "till", "linkedId",
                till))).isEqualTo("A scan_area zone must be a polygon of 3 or more points");
        assertThat(message(zone("Scanner", "scan_area", square(), "linkedType", "till", "linkedId", till, "params",
                Map.of("flowDirection", "diagonal")))).isEqualTo("params.flowDirection must be one of: left_to_right,"
                        + " right_to_left, top_to_bottom, bottom_to_top");
        assertThat(message(zone("Scanner", "scan_area", square(), "linkedType", "till", "linkedId", till, "params",
                Map.of("laneMargin", 2)))).isEqualTo("params.laneMargin must be between 0 and 1");
        JsonNode scan = ok(201, "POST", "/loss-prevention/zones", zone("Scanner", "scan_area", square(), "linkedType",
                "till", "linkedId", till, "params", Map.of("flowDirection", "right_to_left", "activityResearch",
                        true)));
        scanZone = scan.get("id").asString();
        JsonNode effective = scan.get("effectiveParams");
        assertThat(effective.get("flowDirection").asString()).isEqualTo("right_to_left");
        assertThat(effective.get("classes").get(0).asString()).isEqualTo("item");
        assertThat(dec(effective.get("laneMargin"))).isEqualByComparingTo("0.15");
        assertThat(effective.get("activityResearch").asBoolean()).isTrue();

        assertThat(message(zone("Queue", "queue", square(), "linkedType", "table", "linkedId", tableId)))
                .isEqualTo("A queue zone links to: till");
        queueZone = ok(201, "POST", "/loss-prevention/zones", zone("Queue", "queue", square(), "linkedType", "till",
                "linkedId", till)).get("id").asString();
        assertThat(message(zone("Table", "dining", square(), "linkedType", "table", "linkedId", "missing-" + tag)))
                .isEqualTo("Table not found");
        diningZone = ok(201, "POST", "/loss-prevention/zones", zone("Table", "dining", square(), "linkedType", "table",
                "linkedId", tableId)).get("id").asString();
        storeZone = ok(201, "POST", "/loss-prevention/zones", zone("Store room door", "storage", line(0.2, 0.1, 0.2,
                0.9), "inSide", "left", "linkedType", "location", "linkedId", locationId)).get("id").asString();
        doorZone = ok(201, "POST", "/loss-prevention/zones", zone("Front door", "entrance", line(0.1, 0.9, 0.9, 0.9),
                "inSide", "left")).get("id").asString();

        JsonNode config = agentOk(200, "GET", "/loss-prevention/agents/config", null);
        JsonNode zones = config.get("cameras").get(0).get("zones");
        assertThat(zones.size()).isEqualTo(5);
        JsonNode sent = null;
        for (JsonNode z : zones) {
            if (scanZone.equals(z.get("id").asString())) {
                sent = z;
            }
        }
        assertThat(sent).as(zones.toString()).isNotNull();
        assertThat(sent.get("params").get("activityResearch").asBoolean()).isTrue();

        // The zones were drawn before the day the tests replay, and the box has observed up to now.
        jdbc.sql("UPDATE \"LpZone\" SET \"createdAt\" = ? WHERE \"cameraId\" = ?").params(java.sql.Timestamp.valueOf(
                day.minusDays(2).atStartOfDay()), cameraId).update();
        String mark = Instant.now().minusSeconds(5).toString();
        agentOk(200, "POST", "/loss-prevention/agents/heartbeat", Map.of("configVersion", config.get("configVersion")
                .asInt(), "observedThrough", mark, "cameras", List.of(Map.of("cameraId", cameraId, "streamState", "ok",
                        "observedThrough", mark))));
    }

    @Test
    @Order(2)
    @DisplayName("V-R1 (switched on) and V-S1 find a sale short of the items scanned and goods carried out unrecorded")
    void retailCases() {
        ok(200, "PUT", "/loss-prevention/rules/V-R1", Map.of("enabled", true, "severity", "warning"));
        try {
            sendRecords(List.of(
                    record("sale.completed", "10:00:00", "order", "pos", Map.of("transactionId", "r0-" + tag, "orderId",
                            "o0-" + tag), Map.of("tillId", till, "total", 5, "lines", List.of(Map.of("quantity", 1,
                                    "total", 5)))),
                    record("sale.completed", "10:08:00", "order", "pos", Map.of("transactionId", "r1-" + tag, "orderId",
                            "o1-" + tag), Map.of("tillId", till, "total", 30, "lines", List.of(Map.of("quantity", 2,
                                    "total", 20), Map.of("quantity", 1, "total", 10)))),
                    // Explains what was carried out between 16:00 and 17:00, not what went between 14:00 and 15:00.
                    record("stock.movement", "16:30:00", "item", "stock", Map.of("lineId", "il-" + tag), Map.of(
                            "locationId", locationId, "direction", "out", "quantity", 2, "kind", "transfer_out"))));
            List<Map<String, Object>> observed = new ArrayList<>();
            // Six items over the scanner between the two sales; the second sale records three.
            for (int i = 1; i <= 6; i++) {
                observed.add(seen(scanZone, "vision.line_crossed", "10:0" + i + ":10", Map.of("class", "item",
                        "direction", "out", "count", 1, "trackId", "t" + i)));
            }
            observed.add(seen(storeZone, "vision.line_crossed", "14:10:00", Map.of("class", "bag", "direction", "out",
                    "count", 2)));
            observed.add(seen(storeZone, "vision.line_crossed", "14:20:00", Map.of("class", "bag", "direction", "out",
                    "count", 1)));
            observed.add(seen(storeZone, "vision.line_crossed", "16:10:00", Map.of("class", "bag", "direction", "out",
                    "count", 2)));
            sendSeen(observed);

            JsonNode run = replay("retail_vision");
            assertThat(run.get("findings").asInt()).as(run.toString()).isEqualTo(2);
        } finally {
            ok(200, "PUT", "/loss-prevention/rules/V-R1", Map.of("enabled", false, "severity", "warning"));
        }
        drainOutboxFully();
        String period = "&branchId=" + branch + "&from=" + day + "&to=" + day;
        JsonNode r1 = ok(200, "GET", "/loss-prevention/cases?ruleCode=V-R1" + period, null);
        assertThat(r1.get("meta").get("total").asInt()).as(r1.toString()).isEqualTo(1);
        assertThat(r1.get("data").get(0).get("subjectId").asString()).isEqualTo(till);
        JsonNode s1 = ok(200, "GET", "/loss-prevention/cases?ruleCode=V-S1" + period, null);
        assertThat(s1.get("meta").get("total").asInt()).as(s1.toString()).isEqualTo(1);
        assertThat(s1.get("data").get(0).get("subjectId").asString()).isEqualTo(locationId);
        Res unknown = call("POST", "/loss-prevention/runs/replay", Map.of("engine", "retail", "branchId", branch,
                "from", day.toString(), "to", day.toString()), Map.of("Authorization", "Bearer " + adminToken,
                        "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body().get("message").asString()).contains("retail_vision");
    }

    @Test
    @Order(3)
    @DisplayName("a stock ledger line at a watched store room is projected as stock.movement")
    void stockProjection() {
        String ledgerId = "zz-lp5-" + UUID.randomUUID();
        // The ledger's own check: balanceAfter = qtyBefore + quantityChange.
        jdbc.sql("INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\", \"productId\","
                + " \"locationId\", type, \"qtyBefore\", \"quantityChange\", \"balanceAfter\", \"referenceType\","
                + " \"referenceId\") VALUES (?, ?, ?, ?, ?, 'transfer_out'::\"StockMoveType\", 5, -3, 2, 'lp5_test',"
                + " ?)")
                .params(ledgerId, demoOrg, "ZZ-LP5-" + tag, productId, locationId, "ref-" + tag).update();
        String source = "inventory-ledger:" + ledgerId;
        java.util.function.LongSupplier stored = () -> jdbc.sql("SELECT count(*) FROM \"LpEvent\" WHERE"
                + " \"organizationId\" = ? AND \"sourceEventId\" = ?").params(demoOrg, source).query(Long.class)
                .single();
        // Other runs' store rooms are read too; the page is bounded, so a few sweeps may be needed.
        Map<String, Object> swept = Map.of();
        for (int i = 0; i < 20 && stored.getAsLong() == 0; i++) {
            swept = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                    () -> projection.sweep("stock_movements"));
            assertThat(swept.get("error")).as(swept.toString()).isNull();
            assertThat(((Number) swept.get("rejected")).intValue()).as(swept.toString()).isZero();
        }
        assertThat(stored.getAsLong()).as(swept.toString()).isEqualTo(1);
        Map<String, Object> e = jdbc.sql("SELECT type, \"branchId\", \"evidenceLevel\", \"evidenceSource\","
                + " payload::text AS payload FROM \"LpEvent\" WHERE \"organizationId\" = ? AND \"sourceEventId\" = ?")
                .params(demoOrg, source).query().singleRow();
        assertThat(e.get("type")).isEqualTo("stock.movement");
        assertThat(e.get("branchId")).as("the store room has no branch: the zone's").isEqualTo(branch);
        assertThat(e.get("evidenceLevel")).isEqualTo("item");
        assertThat(e.get("evidenceSource")).isEqualTo("stock");
        JsonNode payload = mapper.readTree((String) e.get("payload"));
        assertThat(payload.get("locationId").asString()).isEqualTo(locationId);
        assertThat(payload.get("direction").asString()).isEqualTo("out");
        assertThat(dec(payload.get("quantity"))).isEqualByComparingTo("3");
        assertThat(payload.get("kind").asString()).isEqualTo("transfer_out");
        assertThat(payload.get("referenceType").asString()).isEqualTo("lp5_test");
    }

    @Test
    @Order(4)
    @DisplayName("LP6b: counts at the door, the queue and the table become footfall, waits, queue length and occupancy")
    void cameraMetrics() {
        sendSeen(List.of(
                seen(doorZone, "vision.line_crossed", "09:10:00", Map.of("class", "person", "direction", "in", "count",
                        3)),
                // Not a person: not footfall.
                seen(doorZone, "vision.line_crossed", "09:20:00", Map.of("class", "bag", "direction", "in", "count",
                        5)),
                seen(doorZone, "vision.line_crossed", "09:40:00", Map.of("class", "person", "direction", "out", "count",
                        2)),
                seen(doorZone, "vision.line_crossed", "11:15:00", Map.of("class", "person", "direction", "in", "count",
                        1)),
                // Waits of 120 s and 30 s, each counted at the hour it ended.
                seen(queueZone, "vision.dwell", "12:02:00", Map.of("class", "person", "startedAt", at("12:00:00"),
                        "endedAt", at("12:02:00"), "durationSeconds", 120)),
                seen(queueZone, "vision.dwell", "12:01:30", Map.of("class", "person", "startedAt", at("12:01:00"),
                        "endedAt", at("12:01:30"))),
                // Three minutes of queue: 2, 1 and 0 people.
                seen(queueZone, "vision.occupancy", "12:01:00", Map.of("class", "person", "count", 2, "periodSeconds",
                        60)),
                seen(queueZone, "vision.occupancy", "12:02:00", Map.of("class", "person", "count", 1, "periodSeconds",
                        60)),
                seen(queueZone, "vision.occupancy", "12:03:00", Map.of("class", "person", "count", 0, "periodSeconds",
                        60)),
                seen(queueZone, "vision.gap", "15:05:00", Map.of("from", at("15:00:00"), "to", at("15:05:00"),
                        "reason", "stream_lost")),
                // The table: empty, seated at 13:05 for ten minutes, empty again.
                seen(diningZone, "vision.occupancy", "13:05:00", Map.of("count", 0, "periodSeconds", 300)),
                seen(diningZone, "vision.occupancy", "13:10:00", Map.of("count", 2, "periodSeconds", 300)),
                seen(diningZone, "vision.occupancy", "13:15:00", Map.of("count", 2, "periodSeconds", 300)),
                seen(diningZone, "vision.occupancy", "13:20:00", Map.of("count", 0, "periodSeconds", 300))));
        // The table's first order reaches the kitchen three minutes after it was seated.
        sendRecords(List.of(record("kitchen.ticket_created", "13:08:00", "ticket", "kds", Map.of("orderId",
                "ko-" + tag, "ticketId", "kt-" + tag), Map.of("station", "Hot", "itemCount", 2, "tableId", tableId))));
        compute();
        Map<String, Object> computed = metricDay();
        assertThat(computed.get("lastError")).as("the computation failed").isNull();
        assertThat(computed.get("state")).isEqualTo("computed");

        // Footfall: 4 in, 2 out; the two sales of the day give a conversion of 0.5.
        JsonNode foot = ok(200, "GET", "/loss-prevention/insights/footfall?" + q(), null);
        JsonNode totals = foot.get("totals");
        assertThat(totals.get("in").asLong()).isEqualTo(4);
        assertThat(totals.get("out").asLong()).isEqualTo(2);
        assertThat(totals.get("sales").asLong()).isEqualTo(2);
        assertThat(dec(totals.get("conversionRate"))).isEqualByComparingTo("0.5");
        assertThat(foot.get("days").size()).isEqualTo(1);
        assertThat(foot.get("days").get(0).get("day").asString()).isEqualTo(day.toString());
        JsonNode hours = foot.get("hours");
        assertThat(hours.size()).isEqualTo(24);
        assertThat(hours.get(9).get("in").asLong()).isEqualTo(3);
        assertThat(hours.get(9).get("out").asLong()).isEqualTo(2);
        assertThat(hours.get(11).get("in").asLong()).isEqualTo(1);
        assertThat(hours.get(10).get("sales").asLong()).isEqualTo(2);
        assertThat(hours.get(10).get("conversionRate").isNull()).as("nobody came in at 10").isTrue();
        assertThat(foot.get("zones").size()).isEqualTo(1);
        assertThat(foot.get("zones").get(0).get("zoneId").asString()).isEqualTo(doorZone);
        assertThat(foot.get("zones").get(0).get("in").asLong()).isEqualTo(4);
        assertThat(foot.get("cameraNote").asString()).contains("never identify anyone");

        // Queue: waits of 120 and 30 s; 180 person-seconds over 180 s observed.
        JsonNode queues = ok(200, "GET", "/loss-prevention/insights/queues?" + q(), null);
        assertThat(queues.get("rows").size()).isEqualTo(1);
        JsonNode queue = queues.get("rows").get(0);
        assertThat(queue.get("zoneId").asString()).isEqualTo(queueZone);
        assertThat(queue.get("tillId").asString()).isEqualTo(till);
        assertThat(queue.get("waits").asLong()).isEqualTo(2);
        assertThat(dec(queue.get("averageWaitSeconds"))).isEqualByComparingTo("75");
        assertThat(dec(queue.get("worstDayP90WaitSeconds"))).isEqualByComparingTo("111");
        assertThat(dec(queue.get("observedSeconds"))).isEqualByComparingTo("180");
        assertThat(dec(queue.get("averageLength"))).isEqualByComparingTo("1");
        assertThat(dec(queue.get("worstDayP90Length"))).isEqualByComparingTo("1.8");
        assertThat(dec(queue.get("gapSeconds"))).isEqualByComparingTo("300");
        JsonNode noon = queues.get("hours").get(12);
        assertThat(noon.get("waits").asLong()).isEqualTo(2);
        assertThat(dec(noon.get("averageLength"))).isEqualByComparingTo("1");

        // Table: half of 20 minutes occupied, one seating, its first order after 180 s.
        JsonNode occupancy = ok(200, "GET", "/loss-prevention/insights/occupancy?" + q(), null);
        assertThat(occupancy.get("rows").size()).isEqualTo(1);
        JsonNode table = occupancy.get("rows").get(0);
        assertThat(table.get("zoneId").asString()).isEqualTo(diningZone);
        assertThat(table.get("linkedType").asString()).isEqualTo("table");
        assertThat(table.get("linkedId").asString()).isEqualTo(tableId);
        assertThat(table.get("tableName").isNull()).isFalse();
        assertThat(dec(table.get("observedSeconds"))).isEqualByComparingTo("1200");
        assertThat(dec(table.get("occupiedSeconds"))).isEqualByComparingTo("600");
        assertThat(dec(table.get("occupancyRate"))).isEqualByComparingTo("0.5");
        assertThat(table.get("seatings").asLong()).isEqualTo(1);
        assertThat(table.get("ordered").asLong()).isEqualTo(1);
        assertThat(dec(table.get("averageWaitToOrderSeconds"))).isEqualByComparingTo("180");
        assertThat(dec(table.get("worstDayP90WaitToOrderSeconds"))).isEqualByComparingTo("180");
        assertThat(occupancy.get("hours").get(13).get("seatings").asLong()).isEqualTo(1);

        JsonNode summary = ok(200, "GET", "/loss-prevention/insights/summary?" + q(), null);
        JsonNode camera = summary.get("metrics").get("camera");
        assertThat(camera.get("footfall").get("in").asLong()).isEqualTo(4);
        assertThat(dec(camera.get("footfall").get("conversionRate"))).isEqualByComparingTo("0.5");
        assertThat(camera.get("queues").get("waits").asLong()).isEqualTo(2);
        assertThat(dec(camera.get("queues").get("averageLength"))).isEqualByComparingTo("1");
        assertThat(dec(camera.get("tables").get("occupancyRate"))).isEqualByComparingTo("0.5");
        assertThat(camera.get("tables").get("ordered").asLong()).isEqualTo(1);
        JsonNode coverage = summary.get("coverage").get("camera");
        assertThat(coverage.get("daysWithData").asLong()).isEqualTo(1);
        assertThat(dec(coverage.get("gapSeconds"))).isEqualByComparingTo("300");
        assertThat(coverage.get("measured").asBoolean()).isFalse();
        assertThat(summary.get("cameraNote").asString()).isNotBlank();
    }

    @Test
    @Order(5)
    @DisplayName("LP7: activities are listed newest first, reviewed with checks, and summed into precision")
    void research() {
        sendSeen(List.of(
                seen(scanZone, "vision.activity", "17:00:20", Map.of("activity", "shelf_sweep", "recognizer",
                        "research_v1@1", "startedAt", at("17:00:00"), "endedAt", at("17:00:20"), "score", 0.8)),
                seen(scanZone, "vision.activity", "17:30:10", Map.of("activity", "shelf_sweep", "recognizer",
                        "research_v1@1", "startedAt", at("17:30:00"), "endedAt", at("17:30:10"), "score", 0.6)),
                seen(scanZone, "vision.activity", "18:00:30", Map.of("activity", "restocking", "recognizer",
                        "research_v1@1", "startedAt", at("18:00:00"), "endedAt", at("18:00:30")))));
        JsonNode all = activities("");
        assertThat(all.get("meta").get("total").asInt()).isEqualTo(3);
        JsonNode newest = all.get("data").get(0);
        assertThat(newest.get("activity").asString()).isEqualTo("restocking");
        assertThat(newest.get("zoneId").asString()).isEqualTo(scanZone);
        assertThat(newest.get("zoneType").asString()).isEqualTo("scan_area");
        assertThat(newest.get("cameraId").asString()).isEqualTo(cameraId);
        assertThat(newest.get("recognizer").asString()).isEqualTo("research_v1@1");
        assertThat(newest.get("startedAt").asString()).isEqualTo(at("18:00:00"));
        assertThat(newest.get("review").isNull()).isTrue();
        assertThat(newest.get("reviews").asLong()).isZero();
        thirdActivity = newest.get("eventId").asString();
        secondActivity = all.get("data").get(1).get("eventId").asString();
        firstActivity = all.get("data").get(2).get("eventId").asString();

        JsonNode correct = ok(201, "POST", "/loss-prevention/research/activities/" + firstActivity + "/review",
                Map.of("verdict", "correct"));
        assertThat(correct.get("eventId").asString()).isEqualTo(firstActivity);
        assertThat(correct.get("verdict").asString()).isEqualTo("correct");
        assertThat(correct.get("label").isNull()).isTrue();
        assertThat(correct.get("reviewedBy").asString()).isEqualTo(adminId);
        ok(201, "POST", "/loss-prevention/research/activities/" + secondActivity + "/review", Map.of("verdict",
                "unsure"));
        // A later review counts; the earlier one is kept.
        JsonNode relabelled = ok(201, "POST", "/loss-prevention/research/activities/" + secondActivity + "/review",
                Map.of("verdict", "incorrect", "label", "restocking", "note", "Refilling the shelf"));
        assertThat(relabelled.get("label").asString()).isEqualTo("restocking");

        assertThat(review(thirdActivity, Map.of("verdict", "maybe")).body().get("message").asString())
                .isEqualTo("verdict must be one of: correct, incorrect, unsure");
        assertThat(review(thirdActivity, Map.of()).body().get("message").asString())
                .isEqualTo("verdict must be one of: correct, incorrect, unsure");
        assertThat(review(thirdActivity, Map.of("verdict", "incorrect", "label", "Restocking!")).body().get("message")
                .asString()).isEqualTo("label must be 1 to 64 characters: lowercase letters, digits or '_', starting"
                        + " with a letter");
        assertThat(review(thirdActivity, Map.of("verdict", "correct", "label", "restocking")).body().get("message")
                .asString()).isEqualTo("A label is given only with the verdict incorrect");
        assertThat(review(thirdActivity, Map.of("verdict", "unsure", "note", "x".repeat(1001))).body().get("message")
                .asString()).isEqualTo("note holds at most 1000 characters");
        Res missing = review(UUID.randomUUID().toString(), Map.of("verdict", "correct"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().get("message").asString()).isEqualTo("Activity not found");
        String dwell = jdbc.sql("SELECT id FROM \"LpEvent\" WHERE \"deviceId\" = ? AND type = 'vision.dwell' LIMIT 1")
                .param(queueZone).query(String.class).single();
        assertThat(review(dwell, Map.of("verdict", "correct")).status()).as("not an activity").isEqualTo(404);

        JsonNode incorrect = activities("&verdict=incorrect");
        assertThat(incorrect.get("meta").get("total").asInt()).isEqualTo(1);
        JsonNode latest = incorrect.get("data").get(0);
        assertThat(latest.get("eventId").asString()).isEqualTo(secondActivity);
        assertThat(latest.get("reviews").asLong()).isEqualTo(2);
        assertThat(latest.get("review").get("label").asString()).isEqualTo("restocking");
        assertThat(latest.get("review").get("reviewerName").isNull()).isFalse();
        assertThat(activities("&verdict=unreviewed").get("data").get(0).get("eventId").asString())
                .isEqualTo(thirdActivity);
        assertThat(activities("&verdict=unsure").get("meta").get("total").asInt()).as("only the latest review counts")
                .isZero();
        assertThat(activities("&activity=restocking").get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(activities("&recognizer=other").get("meta").get("total").asInt()).isZero();
        assertThat(activities("&zoneId=" + scanZone).get("meta").get("total").asInt()).isEqualTo(3);
        JsonNode second = activities("&page=2&pageSize=2");
        assertThat(second.get("data").size()).isEqualTo(1);
        assertThat(second.get("meta").get("totalPages").asInt()).isEqualTo(2);
        Res bad = as("GET", "/loss-prevention/research/activities?" + q() + "&verdict=bogus", null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("message").asString())
                .isEqualTo("verdict must be one of: unreviewed, correct, incorrect, unsure");

        JsonNode summary = ok(200, "GET", "/loss-prevention/research/activities/summary?" + q(), null);
        JsonNode rows = summary.get("rows");
        assertThat(rows.size()).isEqualTo(2);
        JsonNode restocking = rows.get(0);
        assertThat(restocking.get("activity").asString()).isEqualTo("restocking");
        assertThat(restocking.get("events").asLong()).isEqualTo(1);
        assertThat(restocking.get("reviewed").asLong()).isZero();
        assertThat(restocking.get("precision").isNull()).isTrue();
        JsonNode sweep = rows.get(1);
        assertThat(sweep.get("activity").asString()).isEqualTo("shelf_sweep");
        assertThat(sweep.get("events").asLong()).isEqualTo(2);
        assertThat(sweep.get("reviewed").asLong()).isEqualTo(2);
        assertThat(sweep.get("correct").asLong()).isEqualTo(1);
        assertThat(sweep.get("incorrect").asLong()).isEqualTo(1);
        assertThat(sweep.get("unsure").asLong()).isZero();
        assertThat(dec(sweep.get("precision"))).isEqualByComparingTo("0.5");
        JsonNode relabel = summary.get("relabels").get(0);
        assertThat(relabel.get("activity").asString()).isEqualTo("shelf_sweep");
        assertThat(relabel.get("label").asString()).isEqualTo("restocking");
        assertThat(relabel.get("count").asLong()).isEqualTo(1);
        assertThat(summary.get("note").asString()).contains("shadow mode");

        // Reviews are append-only.
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"LpActivityReview\" SET verdict = 'correct' WHERE \"eventId\" = ?")
                .param(secondActivity).update()).hasMessageContaining("append-only");
    }

    @Test
    @Order(6)
    @DisplayName("another organization sees none of it; every research route needs its permission")
    void isolationAndAuthorization() {
        JsonNode list = call("GET", "/loss-prevention/research/activities?from=" + day + "&to=" + day, null,
                otherToken).body();
        assertThat(list.get("meta").get("total").asInt()).isZero();
        Res foreign = call("POST", "/loss-prevention/research/activities/" + firstActivity + "/review", Map.of(
                "verdict", "incorrect"), otherToken);
        assertThat(foreign.status()).isEqualTo(404);
        JsonNode queues = call("GET", "/loss-prevention/insights/queues?from=" + day + "&to=" + day, null, otherToken)
                .body();
        assertThat(queues.get("rows").size()).isZero();
        assertThat(assertAuthorizationMatrix(LossPreventionResearchController.class)).isEqualTo(3);
    }
}
