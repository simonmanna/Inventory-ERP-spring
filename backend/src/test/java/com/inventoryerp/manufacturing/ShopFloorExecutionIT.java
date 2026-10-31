package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.web.ShopFloorExecutionController;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Shop-floor execution &amp; data capture (ADR-S015, Phase 2.7): terminal start / pause (downtime) / resume / complete,
 * clock on / off, production reports on single-leg (work-order counts) and multi-stage orders (stage engine, GL
 * balanced), idempotent replay, meter readings, machine data (simulator poll, push, CSV) → drafts → confirmation
 * (concurrency) / rejection, shift reports with four-eyes approval and locking, OEE, live board, set-up rules,
 * tenant isolation and the authorization matrix of the 41 routes.
 */
class ShopFloorExecutionIT extends MfgTestSupport {

    static final String SF = "/manufacturing/shop-floor";

    String supervisorToken;
    String scrapCode;

    @BeforeAll
    void shopFloorFixtures() {
        ok(201, "POST", SF + "/shifts/defaults", null);
        ok(201, "POST", SF + "/downtime-reasons/defaults", null);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Supervisor-" + role,
                        new String[] {"shop_floor:approve", "work_order:read"}).update();
        String email = "supervisor-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Super@12345", role);
        supervisorToken = login("DEMO", email, "Super@12345");
        scrapCode = "SF_" + suffix();
        ok(201, "POST", "/manufacturing/scrap-reasons", Map.of("code", scrapCode, "name", "Shop-floor scrap"));
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode keyed(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String workCenter(String prefix) {
        return ok(201, "POST", "/manufacturing/work-centers", Map.of("code", prefix + "-" + suffix(), "name",
                prefix + " line")).get("id").asString();
    }

    JsonNode workOrders(String orderId) {
        return ok(200, "GET", "/manufacturing/production-orders/" + orderId + "/work-orders", null);
    }

    long unbalancedStageEntries() {
        return jdbc.sql("SELECT count(*) FROM (SELECT je.id FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = je.id WHERE je.\"organizationId\" = ? AND je.\"sourceType\" IN"
                + " ('production_stage', 'production_stage_reversal') GROUP BY je.id HAVING sum(jl.debit) <>"
                + " sum(jl.credit)) x").param(demoOrg).query(Long.class).single();
    }

    @Test
    @DisplayName("single-leg order on the terminal: start, stop with reason, resume, clock, output, meters, complete,"
            + " shift report submit / four-eyes approve / lock, OEE, board")
    void singleLegTerminal() {
        String wc = workCenter("PRS");
        ok(200, "PUT", SF + "/work-center-profiles/" + wc, Map.of("idealRatePerHour", 60, "rateUom", "pcs",
                "meterCodes", List.of("Hours", "hours")));
        String sand = product("SFSAND");
        String paver = product("SFPAVER");
        stockIn(bakery, sand, 100, 2);
        String bomId = bom(paver, sand, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Press " + suffix(), "bomId", bomId, "operations",
                List.of(Map.of("sequence", 10, "name", "Press", "workCenterId", wc, "durationMins", 60))));
        String orderId = started(bomId).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null);
        String wo = workOrders(orderId).get(0).get("id").asString();

        JsonNode queue = ok(200, "GET", SF + "/work-centers/" + wc + "/queue", null);
        assertThat(queue.get("workOrders")).hasSize(1);
        assertThat(queue.get("workOrders").get(0).get("multiStage").asBoolean()).isFalse();
        assertThat(queue.get("profile").get("meterCodes")).hasSize(1);

        // Start → stop with a reason (downtime) → resume closes the stop.
        assertThat(ok(201, "POST", SF + "/work-orders/" + wo + "/start", null).get("status").asString())
                .isEqualTo("in_progress");
        assertThat(as("POST", SF + "/work-orders/" + wo + "/pause", Map.of("reasonCode", "NOPE")).body()
                .get("message").asString()).isEqualTo("Unknown downtime reason NOPE.");
        JsonNode paused = ok(201, "POST", SF + "/work-orders/" + wo + "/pause", Map.of("reasonCode", "BREAKDOWN",
                "notes", "Belt torn"));
        assertThat(paused.get("workOrder").get("status").asString()).isEqualTo("paused");
        String stopId = paused.get("downtime").get("id").asString();
        assertThat(paused.get("downtime").get("isPlanned").asBoolean()).isFalse();
        JsonNode board = ok(200, "GET", SF + "/dashboard", null);
        JsonNode card = null;
        for (JsonNode c : board.get("workCenters")) {
            if (wc.equals(c.get("workCenterId").asString())) {
                card = c;
            }
        }
        assertThat(card).isNotNull();
        assertThat(card.get("status").asString()).isEqualTo("down");
        assertThat(card.get("openDowntime").get("reasonCode").asString()).isEqualTo("BREAKDOWN");
        assertThat(as("POST", SF + "/downtime", Map.of("workCenterId", wc, "reasonCode", "SETUP")).body()
                .get("message").asString()).startsWith("This work centre already has an open stop (BREAKDOWN)");
        ok(201, "POST", SF + "/work-orders/" + wo + "/start", null);
        JsonNode stops = ok(200, "GET", SF + "/downtime?workCenterId=" + wc, null);
        assertThat(stops.get("data").get(0).get("id").asString()).isEqualTo(stopId);
        assertThat(stops.get("data").get(0).get("endedAt").isNull()).isFalse();
        assertThat(stops.get("data").get(0).get("reasonName").asString()).isEqualTo("Mechanical breakdown");

        // A closed planned stop logged afterwards; ending a closed stop is refused.
        JsonNode planned = ok(201, "POST", SF + "/downtime", Map.of("workCenterId", wc, "workOrderId", wo,
                "reasonCode", "BREAK", "startedAt", Instant.now().minus(5, ChronoUnit.MINUTES).toString(), "minutes", 3));
        assertThat(planned.get("minutes").asInt()).isEqualTo(3);
        assertThat(planned.get("isPlanned").asBoolean()).isTrue();
        assertThat(as("POST", SF + "/downtime/" + planned.get("id").asString() + "/end", Map.of()).body()
                .get("message").asString()).isEqualTo("This stop has already ended.");
        JsonNode open = ok(201, "POST", SF + "/downtime", Map.of("workCenterId", wc, "reasonCode", "NO_MATERIAL"));
        assertThat(ok(201, "POST", SF + "/downtime/" + open.get("id").asString() + "/end", Map.of("notes", "Sand in"))
                .get("endedAt").isNull()).isFalse();

        // Clock on, switch work order (closes the first entry), clock off.
        JsonNode c1 = ok(201, "POST", SF + "/clock-entries", Map.of("workCenterId", wc, "crewCode", "A"));
        JsonNode c2 = ok(201, "POST", SF + "/clock-entries", Map.of("workCenterId", wc, "workOrderId", wo, "role",
                "operator"));
        JsonNode openEntries = ok(200, "GET", SF + "/clock-entries?open=true&workCenterId=" + wc, null);
        assertThat(openEntries.get("data")).hasSize(1);
        assertThat(openEntries.get("data").get(0).get("id").asString()).isEqualTo(c2.get("id").asString());
        assertThat(openEntries.get("data").get(0).get("userName").asString()).isNotBlank();
        assertThat(ok(200, "GET", SF + "/clock-entries?open=false&workCenterId=" + wc, null).get("data").get(0)
                .get("id").asString()).isEqualTo(c1.get("id").asString());
        assertThat(as("POST", SF + "/clock-entries", Map.of("workCenterId", wc, "userId", UUID.randomUUID()
                .toString())).body().get("message").asString()).isEqualTo("User not found in this organization.");

        // Output: Idempotency-Key required; replay returns the same report; counts land on the work order.
        Map<String, Object> out = Map.of("workOrderId", wo, "qtyGood", 40, "qtyScrap", 2, "scrapReasonCode", scrapCode);
        assertThat(as("POST", SF + "/production-reports", out).status()).isEqualTo(400);
        Map<String, String> h = key(adminToken);
        Res first = call("POST", SF + "/production-reports", out, h);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = call("POST", SF + "/production-reports", out, h);
        assertThat(replay.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        JsonNode report = first.body();
        assertThat(report.get("status").asString()).isEqualTo("posted");
        assertThat(report.get("stageId").isNull()).isTrue();
        String shiftReportId = report.get("shiftReportId").asString();
        assertThat(num(workOrders(orderId).get(0).get("qtyGood"))).isEqualByComparingTo("40");
        assertThat(num(workOrders(orderId).get(0).get("qtyScrap"))).isEqualByComparingTo("2");
        assertThat(call("POST", SF + "/production-reports", Map.of("workOrderId", wo, "qtyGood", 0), key(adminToken))
                .body().get("message").asString()).isEqualTo("Report a good or scrap quantity.");
        assertThat(call("POST", SF + "/production-reports", Map.of("workOrderId", wo, "qtyGood", 1, "completesStage",
                true), key(adminToken)).body().get("message").asString())
                .isEqualTo("Only a work order of a multi-stage order can complete a stage or report co-products.");
        assertThat(call("POST", SF + "/production-reports", Map.of("workOrderId", wo, "qtyGood", 1, "qtyScrap", 1,
                "scrapReasonCode", "nope-" + suffix()), key(adminToken)).status()).isEqualTo(400);
        assertThat(validationMessages(call("POST", SF + "/production-reports", Map.of("workOrderId", wo, "qtyGood", -1),
                key(adminToken)))).contains("qtyGood must not be less than 0");

        // Meters: monotonic unless reset; only configured meters.
        ok(201, "POST", SF + "/meter-readings", Map.of("workCenterId", wc, "meterCode", "hours", "reading", 100));
        assertThat(as("POST", SF + "/meter-readings", Map.of("workCenterId", wc, "meterCode", "hours", "reading", 90))
                .body().get("message").asString())
                .isEqualTo("Reading 90 is below the previous reading 100 of meter hours; mark it as a meter reset.");
        assertThat(ok(201, "POST", SF + "/meter-readings", Map.of("workCenterId", wc, "meterCode", "hours", "reading",
                5, "isReset", true)).get("delta").isNull()).isTrue();
        assertThat(num(ok(201, "POST", SF + "/meter-readings", Map.of("workCenterId", wc, "meterCode", "HOURS",
                "reading", 12.5)).get("delta"))).isEqualByComparingTo("7.5");
        assertThat(as("POST", SF + "/meter-readings", Map.of("workCenterId", wc, "meterCode", "kwh", "reading", 1))
                .body().get("message").asString()).startsWith("Meter kwh is not configured for work centre PRS-");
        assertThat(ok(200, "GET", SF + "/meter-readings?workCenterId=" + wc + "&meterCode=hours", null).get("meta")
                .get("total").asInt()).isEqualTo(3);

        // Finish the operation (labour costed by the work-order engine).
        assertThat(as("POST", SF + "/work-orders/" + wo + "/complete", Map.of()).status()).isEqualTo(400);
        assertThat(keyed(SF + "/work-orders/" + wo + "/complete", Map.of("notes", "done")).get("status").asString())
                .isEqualTo("completed");
        ok(201, "POST", SF + "/clock-entries/" + c2.get("id").asString() + "/clock-off", Map.of());
        assertThat(as("POST", SF + "/clock-entries/" + c2.get("id").asString() + "/clock-off", Map.of()).body()
                .get("message").asString()).isEqualTo("Already clocked off.");

        // Shift report: OEE, submit, four-eyes approval, lock.
        JsonNode sr = ok(200, "GET", SF + "/shift-reports/" + shiftReportId, null);
        assertThat(sr.get("status").asString()).isEqualTo("open");
        assertThat(num(sr.get("oee").get("goodQty"))).isEqualByComparingTo("40");
        assertThat(num(sr.get("oee").get("scrapQty"))).isEqualByComparingTo("2");
        assertThat(num(sr.get("oee").get("quality"))).isEqualByComparingTo("0.9524");
        assertThat(sr.get("productionReports")).hasSize(1);
        assertThat(sr.get("downtime").size()).isGreaterThanOrEqualTo(1);
        assertThat(sr.get("meterReadings").size()).isGreaterThanOrEqualTo(1);
        String code = sr.get("code").asString();
        assertThat(code).startsWith("SFR-");
        assertThat(ok(201, "POST", SF + "/shift-reports/" + shiftReportId + "/submit", Map.of("notes", "All good"))
                .get("status").asString()).isEqualTo("submitted");
        assertThat(as("POST", SF + "/shift-reports/" + shiftReportId + "/submit", Map.of()).body().get("message")
                .asString()).isEqualTo("Only an open shift report can be submitted (status submitted).");
        Res locked = call("POST", SF + "/production-reports", Map.of("workOrderId", wo, "qtyGood", 1, "reportedAt",
                sr.get("startsAt").asString()), key(adminToken));
        assertThat(locked.body().get("message").asString()).isEqualTo("Shift report " + code
                + " is submitted; reject it to reopen before changing its records.");
        assertThat(as("POST", SF + "/shift-reports/" + shiftReportId + "/approve", Map.of()).body().get("message")
                .asString()).isEqualTo("The supervisor who approves a shift report must not be the one who submitted it.");
        assertThat(call("POST", SF + "/shift-reports/" + shiftReportId + "/reject", Map.of("reason", "Count the scrap"),
                supervisorToken).body().get("status").asString()).isEqualTo("open");
        ok(201, "POST", SF + "/shift-reports/" + shiftReportId + "/submit", null);
        Res approved = call("POST", SF + "/shift-reports/" + shiftReportId + "/approve", Map.of("notes", "ok"),
                supervisorToken);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(201);
        assertThat(approved.body().get("status").asString()).isEqualTo("approved");
        assertThat(num(approved.body().get("goodQty"))).isEqualByComparingTo("40");
        assertThat(approved.body().get("rejectionReason").asString()).isEqualTo("Count the scrap");
        assertThat(call("POST", SF + "/production-reports", Map.of("workOrderId", wo, "qtyGood", 1, "reportedAt",
                sr.get("startsAt").asString()), key(adminToken)).body().get("message").asString())
                .isEqualTo("Shift report " + code + " is approved and locked.");
        assertThat(call("POST", SF + "/shift-reports/" + shiftReportId + "/approve", null, supervisorToken).body()
                .get("message").asString()).isEqualTo("Only a submitted shift report can be approved (status approved).");

        // Lists and OEE.
        JsonNode list = ok(200, "GET", SF + "/shift-reports?workCenterId=" + wc + "&status=approved", null);
        assertThat(list.get("data")).hasSize(1);
        assertThat(list.get("data").get(0).get("shiftCode").asString()).isNotBlank();
        JsonNode oee = ok(200, "GET", SF + "/oee?workCenterId=" + wc + "&from=" + sr.get("shiftDate").asString()
                + "&to=" + sr.get("shiftDate").asString(), null);
        assertThat(oee.get("workCenters")).hasSize(1);
        assertThat(num(oee.get("workCenters").get(0).get("goodQty"))).isEqualByComparingTo("40");
        assertThat(oee.get("total").get("shifts").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(as("GET", SF + "/oee?from=2026-02-01&to=2026-01-01", null).body().get("message").asString())
                .isEqualTo("from must not be after to.");
        assertThat(ok(200, "GET", SF + "/production-reports?workCenterId=" + wc + "&source=operator", null).get("meta")
                .get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("multi-stage order: terminal output posts through the stage engine (GL balanced); simulator, push and"
            + " CSV machine data become drafts; confirm (concurrent) / reject; tenant isolation")
    void multiStageAndMachineData() throws Exception {
        String wc = workCenter("CRS");
        String machine = "MC-" + suffix();
        ok(200, "PUT", SF + "/work-center-profiles/" + wc, Map.of("idealRatePerHour", 120, "machineCode", machine));
        String rock = product("SFROCK");
        String agg = product("SFAGG");
        stockIn(bakery, rock, 500, 5);
        List<Map<String, Object>> ops = List.of(Map.of("sequence", 10, "name", "Crush", "workCenterId", wc),
                Map.of("sequence", 20, "name", "Screen", "workCenterId", wc));
        String routingId = ok(201, "POST", "/manufacturing/routings", Map.of("name", "Crusher " + suffix(),
                "operations", ops)).get("id").asString();
        Map<String, Object> bomBody = new HashMap<>(Map.of("name", "Agg " + suffix(), "outputProductId", agg,
                "outputQuantity", 100, "lines", List.of(Map.of("componentProductId", rock, "quantity", 100)),
                "routingId", routingId));
        String bomId = ok(201, "POST", "/manufacturing/boms", bomBody).get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + bomId + "/activate", null);
        JsonNode order = ok(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", 300, "locationId", bakery, "outputLocationId", warehouse)));
        String orderId = order.get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/confirm", null);

        // A staged order without work orders shows up as such; generating them makes it workable.
        JsonNode q0 = ok(200, "GET", SF + "/work-centers/" + wc + "/queue", null);
        assertThat(q0.get("stagesWithoutWorkOrders")).hasSize(2);
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null);
        JsonNode wos = workOrders(orderId);
        String wo1 = wos.get(0).get("id").asString();
        String wo2 = wos.get(1).get("id").asString();
        JsonNode q1 = ok(200, "GET", SF + "/work-centers/" + wc + "/queue", null);
        assertThat(q1.get("stagesWithoutWorkOrders")).isEmpty();
        assertThat(q1.get("workOrders").get(0).get("multiStage").asBoolean()).isTrue();

        ok(201, "POST", SF + "/work-orders/" + wo1 + "/start", null);
        BigDecimal stock0 = onHand(rock, bakery);
        JsonNode r1 = keyed(SF + "/production-reports", Map.of("workOrderId", wo1, "qtyGood", 300, "completesStage",
                true, "notes", "Crushed"));
        assertThat(r1.get("stageId").isNull()).isFalse();
        assertThat(r1.get("stagePostingId").isNull()).isFalse();
        assertThat(r1.get("stageSequence").isNull()).isFalse();
        assertThat(onHand(rock, bakery)).isEqualByComparingTo(stock0.subtract(new BigDecimal("300")));
        JsonNode stages = ok(200, "GET", "/manufacturing/production-orders/" + orderId + "/stages", null);
        assertThat(stages.get("stages").get(0).get("status").asString()).isEqualTo("completed");
        keyed(SF + "/work-orders/" + wo1 + "/complete", Map.of());
        ok(201, "POST", SF + "/work-orders/" + wo2 + "/start", null);

        // Simulator poll: counts → production drafts for the single running work order; stops → downtime drafts.
        Instant until = Instant.now();
        Map<String, Object> poll = Map.of("adapter", "simulator", "workCenterIds", List.of(wc), "since",
                until.minus(3, ChronoUnit.HOURS).toString(), "until", until.toString());
        JsonNode polled = ok(201, "POST", SF + "/machine-data/poll", poll);
        int created = polled.get("created").asInt();
        assertThat(created).isGreaterThanOrEqualTo(4);
        assertThat(polled.get("drafted").asInt()).isEqualTo(created);
        JsonNode again = ok(201, "POST", SF + "/machine-data/poll", poll);
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(created);
        assertThat(as("POST", SF + "/machine-data/poll", Map.of("adapter", "rest")).body().get("message").asString())
                .isEqualTo("Adapter rest is not configured.");
        assertThat(as("POST", SF + "/machine-data/poll", Map.of("adapter", "x")).body().get("message").asString())
                .isEqualTo("Unknown machine-data adapter x.");
        JsonNode drafts = ok(200, "GET", SF + "/production-reports?workCenterId=" + wc + "&status=draft", null);
        assertThat(drafts.get("data").size()).isGreaterThanOrEqualTo(2);
        assertThat(drafts.get("data").get(0).get("workOrderId").asString()).isEqualTo(wo2);
        assertThat(drafts.get("data").get(0).get("source").asString()).isEqualTo("machine");
        String d1 = drafts.get("data").get(0).get("id").asString();
        String d2 = drafts.get("data").get(1).get("id").asString();

        // Two operators confirm the same draft at once: exactly one posts.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return call("POST", SF + "/production-reports/" + d1 + "/confirm", Map.of("qtyGood", 95),
                        key(adminToken));
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Res> f : futures) {
            statuses.add(f.get().status());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        JsonNode confirmed = ok(200, "GET", SF + "/production-reports?workCenterId=" + wc + "&status=posted"
                + "&source=machine", null);
        assertThat(confirmed.get("data")).hasSize(1);
        assertThat(num(confirmed.get("data").get(0).get("qtyGood"))).isEqualByComparingTo("95");
        assertThat(confirmed.get("data").get(0).get("stagePostingId").isNull()).isFalse();
        assertThat(ok(201, "POST", SF + "/production-reports/" + d2 + "/reject", Map.of("reason", "Double count"))
                .get("status").asString()).isEqualTo("rejected");
        assertThat(as("POST", SF + "/production-reports/" + d2 + "/reject", Map.of("reason", "x")).body()
                .get("message").asString()).isEqualTo("Production report is already rejected.");
        assertThat(unbalancedStageEntries()).isZero();

        // Machine stop drafts: confirm with the reason the machine sent, reject another.
        JsonNode stopDrafts = ok(200, "GET", SF + "/downtime?workCenterId=" + wc + "&status=draft&source=machine", null);
        assertThat(stopDrafts.get("data").size()).isGreaterThanOrEqualTo(2);
        JsonNode cs = ok(201, "POST", SF + "/downtime/" + stopDrafts.get("data").get(0).get("id").asString()
                + "/confirm", Map.of());
        assertThat(cs.get("status").asString()).isEqualTo("confirmed");
        assertThat(cs.get("reasonCode").asString()).isEqualTo("MINOR_STOP");
        ok(201, "POST", SF + "/downtime/" + stopDrafts.get("data").get(1).get("id").asString() + "/reject",
                Map.of("reason", "Sensor glitch"));

        // Push from a gateway: open + close a stop, an unknown machine, a bad event.
        Instant t = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        JsonNode pushed = ok(201, "POST", SF + "/machine-data/push", Map.of("source", "plc-gw", "events", List.of(
                Map.of("externalId", "p1-" + machine, "machineCode", machine, "type", "downtime_start", "occurredAt",
                        t.toString(), "reasonCode", "BLOCKAGE"),
                Map.of("externalId", "p2-" + machine, "machineCode", machine, "type", "downtime_stop", "occurredAt",
                        t.plus(4, ChronoUnit.MINUTES).toString()),
                Map.of("machineCode", "NOPE-" + machine, "type", "count", "occurredAt", t.toString(), "goodQty", 5),
                Map.of("machineCode", machine, "type", "count", "occurredAt", t.toString()))));
        assertThat(pushed.get("adapter").asString()).isEqualTo("push:plc-gw");
        assertThat(pushed.get("drafted").asInt()).isEqualTo(2);
        assertThat(pushed.get("failed").asInt()).isEqualTo(1);
        assertThat(pushed.get("errors")).hasSize(2);
        assertThat(pushed.get("errors").get(0).get("message").asString()).isEqualTo("Unknown machine NOPE-" + machine
                + ".");
        JsonNode pushedStop = ok(200, "GET", SF + "/downtime?workCenterId=" + wc + "&status=draft&source=machine",
                null).get("data").get(0);
        assertThat(pushedStop.get("minutes").asInt()).isEqualTo(4);
        assertThat(pushedStop.get("reasonCode").asString()).isEqualTo("BLOCKAGE");
        assertThat(ok(200, "GET", SF + "/machine-events?workCenterId=" + wc + "&status=drafted", null).get("meta")
                .get("total").asInt()).isEqualTo(created + 2);

        // CSV export (re-import is harmless).
        String csv = "machineCode,type,occurredAt,endedAt,goodQty,scrapQty\n" + machine + ",weight," + t + ","
                + t.plus(5, ChronoUnit.MINUTES) + ",12.5,0.5\n" + machine + ",count,bad,,1,\n";
        JsonNode imported = ok(201, "POST", SF + "/machine-data/csv", Map.of("content", csv, "filename", "belt.csv"));
        assertThat(imported.get("created").asInt()).isEqualTo(1);
        assertThat(imported.get("errors")).hasSize(1);
        assertThat(ok(201, "POST", SF + "/machine-data/csv", Map.of("content", csv)).get("duplicates").asInt())
                .isEqualTo(1);
        JsonNode adapters = ok(200, "GET", SF + "/machine-adapters", null);
        assertThat(adapters.toString()).contains("simulator", "rest", "push", "csv");

        // The shift report with pending machine drafts cannot be submitted.
        String draftReport = ok(200, "GET", SF + "/production-reports?workCenterId=" + wc + "&status=draft", null)
                .get("data").get(0).get("shiftReportId").asString();
        assertThat(as("POST", SF + "/shift-reports/" + draftReport + "/submit", null).body().get("message").asString())
                .startsWith("Confirm or reject the ");

        // Tenant isolation.
        assertThat(call("GET", SF + "/work-centers/" + wc + "/queue", null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", SF + "/shift-reports/" + draftReport, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", SF + "/production-reports", null, otherToken).body().toString()).doesNotContain(d1);
        assertThat(call("POST", SF + "/production-reports/" + d2 + "/confirm", Map.of(), key(otherToken)).status())
                .isEqualTo(404);
        assertThat(call("GET", SF + "/dashboard", null, otherToken).body().toString()).doesNotContain(wc);
    }

    @Test
    @DisplayName("set-up: shifts must not overlap, reason codes unique, machine codes unique, defaults idempotent")
    void setup() {
        int shifts = ok(200, "GET", SF + "/shifts", null).size();
        assertThat(ok(201, "POST", SF + "/shifts/defaults", null).size()).isEqualTo(shifts);
        String code = ("X" + suffix().substring(0, 6)).toUpperCase(java.util.Locale.ROOT);
        assertThat(as("POST", SF + "/shifts", Map.of("code", code, "name", "Early", "startMinute", 300, "endMinute",
                780)).body().get("message").asString()).endsWith("overlap; active shifts must not overlap.");
        JsonNode inactive = ok(201, "POST", SF + "/shifts", Map.of("code", code, "name", "Early", "startMinute", 300,
                "endMinute", 780, "isActive", false));
        assertThat(inactive.get("lengthMinutes").asInt()).isEqualTo(480);
        assertThat(as("PATCH", SF + "/shifts/" + inactive.get("id").asString(), Map.of("isActive", true)).status())
                .isEqualTo(400);
        assertThat(as("POST", SF + "/shifts", Map.of("code", code, "name", "Dup", "startMinute", 0, "endMinute", 10,
                "isActive", false)).status()).isEqualTo(409);
        assertThat(as("PATCH", SF + "/shifts/" + inactive.get("id").asString(), Map.of("breakMinutes", 600)).body()
                .get("message").asString()).isEqualTo("The break of shift " + code + " must be shorter than the shift.");
        assertThat(validationMessages(as("POST", SF + "/shifts", Map.of("code", "Z", "name", "Z", "startMinute", 1500,
                "endMinute", 0)))).contains("startMinute must be between 0 and 1439", "endMinute must be between 1 and"
                        + " 1440");

        int reasons = ok(200, "GET", SF + "/downtime-reasons", null).size();
        assertThat(ok(201, "POST", SF + "/downtime-reasons/defaults", null).size()).isEqualTo(reasons);
        String rc = "R" + suffix();
        JsonNode reason = ok(201, "POST", SF + "/downtime-reasons", Map.of("code", rc, "name", "Crusher jam",
                "category", "planned"));
        assertThat(reason.get("isPlanned").asBoolean()).isTrue();
        assertThat(reason.get("code").asString()).isEqualTo(rc.toUpperCase(java.util.Locale.ROOT));
        assertThat(as("POST", SF + "/downtime-reasons", Map.of("code", rc, "name", "x")).status()).isEqualTo(409);
        JsonNode off = ok(200, "PATCH", SF + "/downtime-reasons/" + reason.get("id").asString(), Map.of("isActive",
                false, "category", "breakdown", "isPlanned", false));
        assertThat(off.get("category").asString()).isEqualTo("breakdown");
        assertThat(ok(200, "GET", SF + "/downtime-reasons?includeInactive=true", null).toString()).contains(rc
                .toUpperCase(java.util.Locale.ROOT));
        assertThat(ok(200, "GET", SF + "/downtime-reasons", null).toString()).doesNotContain(rc
                .toUpperCase(java.util.Locale.ROOT));

        String wc1 = workCenter("M1");
        String wc2 = workCenter("M2");
        String mc = "MCX-" + suffix();
        ok(200, "PUT", SF + "/work-center-profiles/" + wc1, Map.of("machineCode", mc));
        assertThat(as("PUT", SF + "/work-center-profiles/" + wc2, Map.of("machineCode", mc)).status()).isEqualTo(409);
        assertThat(ok(200, "PUT", SF + "/work-center-profiles/" + wc1, Map.of("idealRatePerHour", 30)).get("machineCode")
                .isNull()).isTrue();
        assertThat(as("PUT", SF + "/work-center-profiles/" + UUID.randomUUID(), Map.of()).status()).isEqualTo(404);
        assertThat(ok(200, "GET", SF + "/work-center-profiles", null).toString()).contains(wc1, wc2);
        JsonNode s = null;
        for (JsonNode x : ok(200, "GET", SF + "/shifts", null)) {
            if (s == null && x.get("isActive").asBoolean()) {
                s = x;
            }
        }
        assertThat(s).isNotNull();
        JsonNode opened = ok(201, "POST", SF + "/shift-reports", Map.of("workCenterId", wc2, "shiftId",
                s.get("id").asString(), "shiftDate", "2026-01-05"));
        assertThat(opened.get("shiftDate").asString()).isEqualTo("2026-01-05");
        assertThat(ok(201, "POST", SF + "/shift-reports", Map.of("workCenterId", wc2, "shiftId", s.get("id").asString(),
                "shiftDate", "2026-01-05")).get("id").asString()).isEqualTo(opened.get("id").asString());
        assertThat(num(opened.get("oee").get("plannedMinutes"))).isPositive();
    }

    @Test
    @DisplayName("authorization matrix of the shop-floor routes: 401 without token, 403 without permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(ShopFloorExecutionController.class)).isEqualTo(41);
    }
}
