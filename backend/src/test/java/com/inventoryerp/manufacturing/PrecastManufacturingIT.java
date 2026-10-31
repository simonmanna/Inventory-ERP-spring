package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.events.OutboxWorker;
import com.inventoryerp.manufacturing.application.MfgDb;
import com.inventoryerp.manufacturing.application.PrecastCuringJob;
import com.inventoryerp.manufacturing.application.PrecastProductionService;
import com.inventoryerp.manufacturing.web.PrecastController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Concrete block / precast manufacturing (ADR-S014, Phase 2.4) on Postgres: block spec, curing area, mix design
 * (versioned BOM) with batch scaling, precast order → pressing run (backflush, breakage) → curing batch (maturity)
 * → minimum curing hold → cubing (good / second grade / broken) → strength QC via a quality lot → release to finished
 * goods → cost per acceptable block, variance, reports, labels; GL balanced; deferred gate pass by the curing job;
 * idempotent replay; contended cubing; tenant isolation and the authorization matrix.
 */
class PrecastManufacturingIT extends MfgTestSupport {

    static final String BASE = "/manufacturing/precast";

    @Autowired
    OutboxWorker outbox;

    @Autowired
    MfgDb mfgDb;

    @Autowired
    PrecastProductionService precast;

    void drainOutbox() {
        drainOutboxFully();
    }

    Map<String, String> key(String k) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", k);
        return h;
    }

    Res keyedRes(String path, Object body) {
        return call("POST", path, body, key(UUID.randomUUID().toString()));
    }

    JsonNode keyed(String path, Object body) {
        Res r = keyedRes(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static String daysAgo(int d) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(d).toString();
    }

    long unbalancedEntries() {
        return jdbc.sql("SELECT count(*) FROM (SELECT je.id FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = je.id WHERE je.\"organizationId\" = ? AND je.\"sourceType\" IN"
                + " ('production_stage', 'production_stage_reversal') GROUP BY je.id HAVING sum(jl.debit) <>"
                + " sum(jl.credit)) x").param(demoOrg).query(Long.class).single();
    }

    String quarantine() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'QC-QUARANTINE'"
                + " AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
    }

    /** Fixture: block + second grade + cement / sand / aggregate in stock, spec, curing area, active mix design. */
    record Plant(String block, String second, String cement, String sand, String agg, String area, String mixId,
            String checkpoint) {}

    Plant plant(int minCuringDays) {
        String cement = product("CEM");
        String sand = product("SAND");
        String agg = product("AGG");
        String block = product("BLK6");
        String second = product("BLK6-2ND");
        stockIn(bakery, cement, 1000, 1);
        stockIn(bakery, sand, 5000, 1);
        stockIn(bakery, agg, 5000, 1);
        JsonNode spec = ok(200, "PUT", BASE + "/product-specs/" + block, Map.of("blockType", "hollow", "lengthMm", 400,
                "widthMm", 150, "heightMm", 200, "strengthClass", "7 N/mm²", "strengthMpa", 7, "weightKg", 17.5,
                "unitsPerPallet", 100, "unitsPerCycle", 10, "secondGradeProductId", second));
        assertThat(spec.get("unitsPerPallet").asInt()).isEqualTo(100);
        String area = ok(201, "POST", BASE + "/curing-areas", Map.of("code", "YARD-" + suffix(), "name", "North yard",
                "kind", "yard", "capacityPallets", 40)).get("id").asString();
        String checkpoint = "CURE-" + suffix();
        Map<String, Object> body = new HashMap<>();
        body.put("name", "6in hollow " + suffix());
        body.put("productId", block);
        body.put("unitsPerBatch", 100);
        body.put("lines", List.of(Map.of("productId", cement, "qtyPerBatch", 50), Map.of("productId", sand,
                "qtyPerBatch", 200), Map.of("productId", agg, "qtyPerBatch", 300)));
        body.put("cementProductId", cement);
        body.put("cementPerBag", 50);
        body.put("batchVolumeM3", 0.5);
        body.put("waterCementRatio", 0.45);
        body.put("minCuringDays", minCuringDays);
        body.put("curingCheckpointCode", checkpoint);
        body.put("defaultCuringAreaId", area);
        body.put("targetMaturity", 3000);
        body.put("defaultLocationId", bakery);
        body.put("defaultOutputLocationId", warehouse);
        JsonNode mix = ok(201, "POST", BASE + "/mix-designs", body);
        assertThat(mix.get("status").asString()).isEqualTo("draft");
        assertThat(mix.get("lines")).hasSize(3);
        assertThat(num(mix.get("perBatch").get("cementBags"))).isEqualByComparingTo("1");
        assertThat(mix.get("coProducts").get(0).get("productId").asString()).isEqualTo(second);
        String mixId = mix.get("id").asString();
        Res early = call("POST", BASE + "/orders", Map.of("mixDesignId", mixId, "basis", "units", "value", 100),
                adminToken);
        assertThat(early.body().get("message").asString()).endsWith("is draft; activate it first.");
        assertThat(ok(200, "POST", BASE + "/mix-designs/" + mixId + "/activate", null).get("status").asString())
                .isEqualTo("active");
        return new Plant(block, second, cement, sand, agg, area, mixId, checkpoint);
    }

    JsonNode order(Plant p, int units) {
        JsonNode o = ok(201, "POST", BASE + "/orders", Map.of("mixDesignId", p.mixId(), "basis", "units", "value",
                units));
        assertThat(o.get("status").asString()).isEqualTo("confirmed");
        return o;
    }

    @Test
    @DisplayName("block order → recipe scaling → backflush → pressing → curing → strength lot → release + rejects"
            + " → cost per acceptable block, GL balanced")
    void endToEnd() {
        Plant p = plant(7);
        JsonNode scale = ok(200, "GET", BASE + "/mix-designs/" + p.mixId() + "/scale?basis=units&value=1000", null);
        assertThat(num(scale.get("batches"))).isEqualByComparingTo("10");
        assertThat(num(scale.get("cementBags"))).isEqualByComparingTo("10");
        assertThat(scale.get("pallets").asInt()).isEqualTo(10);
        assertThat(scale.get("cycles").asInt()).isEqualTo(100);
        assertThat(num(scale.get("volumeM3"))).isEqualByComparingTo("5");
        assertThat(num(scale.get("materials").get(1).get("quantity"))).isEqualByComparingTo("2000");
        assertThat(num(ok(200, "GET", BASE + "/mix-designs/" + p.mixId() + "/scale?basis=cement_bags&value=7.5", null)
                .get("batches"))).isEqualByComparingTo("7");

        // Quality: in-process strength plan on the curing checkpoint.
        JsonNode qs = ok(201, "POST", "/quality/specifications", Map.of("code", "QS-" + suffix(), "name",
                "Block strength", "productId", p.block(), "parameters", List.of(Map.of("code", "CS7", "name",
                        "7-day compressive strength", "kind", "numeric", "unit", "N/mm²", "minValue", 7))));
        ok(200, "POST", "/quality/specifications/" + qs.get("id").asString() + "/activate", null);
        ok(201, "POST", "/quality/inspection-plans", Map.of("code", "QP-" + suffix(), "name", "Curing strength",
                "stage", "in_process", "productId", p.block(), "specificationId", qs.get("id").asString(), "stageKey",
                p.checkpoint(), "independentRelease", false, "samplingMethod", "fixed", "sampleSize", 1));

        BigDecimal wip0 = balance(wipAccount);
        JsonNode created = order(p, 200);
        String orderId = created.get("id").asString();
        assertThat(num(created.get("plannedQty"))).isEqualByComparingTo("200");
        assertThat(num(created.get("scaling").get("batches"))).isEqualByComparingTo("2");
        assertThat(created.get("stages")).hasSize(2);
        JsonNode cure = created.get("stages").get(1);
        assertThat(cure.get("role").asString()).isEqualTo("curing");
        assertThat(cure.get("qcGate").asBoolean()).isTrue();
        assertThat(cure.get("minCuringDays").asInt()).isEqualTo(7);
        assertThat(cure.get("qcCheckpointCode").asString()).isEqualTo(p.checkpoint());

        // Pressing run 10 days ago: 196 good, 4 broken at the press; the last run completes pressing.
        JsonNode pressed = keyed(BASE + "/orders/" + orderId + "/pressing-runs", Map.of("qtyGood", 196, "qtyBroken", 4,
                "cycles", 20, "palletLocation", "Row A", "shift", "day", "lastRun", true, "date", daysAgo(10)));
        JsonNode batch = pressed.get("batch");
        String batchId = batch.get("id").asString();
        assertThat(batch.get("status").asString()).isEqualTo("curing");
        assertThat(batch.get("pallets").asInt()).isEqualTo(2);
        assertThat(batch.get("curingAreaId").asString()).isEqualTo(p.area());
        assertThat(batch.get("ready").asBoolean()).isTrue();
        assertThat(onHand(p.cement(), bakery)).isEqualByComparingTo("900"); // 50 × 2 batches backflushed
        assertThat(onHand(p.sand(), bakery)).isEqualByComparingTo("4600");

        // Maturity: 24 h at 20 °C above the −10 °C datum.
        JsonNode read = ok(201, "POST", BASE + "/curing-batches/" + batchId + "/readings", Map.of("temperatureC", 20,
                "readAt", daysAgo(9)));
        assertThat(num(read.get("maturity"))).isEqualByComparingTo("720");
        assertThat(num(read.get("maturityPct"))).isEqualByComparingTo("24.0");
        JsonNode moved = ok(200, "POST", BASE + "/curing-batches/" + batchId + "/move", Map.of("palletLocation",
                "Row C"));
        assertThat(moved.get("palletLocation").asString()).isEqualTo("Row C");
        assertThat(moved.get("readings")).hasSize(2);
        JsonNode labels = ok(200, "GET", BASE + "/curing-batches/" + batchId + "/labels", null);
        assertThat(labels.get("labels")).hasSize(2);
        assertThat(labels.get("labels").get(1).get("quantity").asInt()).isEqualTo(96);
        assertThat(labels.get("labels").get(0).get("qrSvg").asString()).startsWith("<svg");

        // Cubing: every block must be accounted for.
        Res bad = keyedRes(BASE + "/curing-batches/" + batchId + "/cube", Map.of("qtyGood", 190, "qtyBroken", 2));
        assertThat(bad.body().get("message").asString()).startsWith("Account for all 196 blocks of batch");
        JsonNode cubed = keyed(BASE + "/curing-batches/" + batchId + "/cube", Map.of("qtyGood", 190, "qtySecondGrade",
                4, "qtyBroken", 2));
        assertThat(cubed.get("batch").get("status").asString()).isEqualTo("cubed");
        assertThat(onHand(p.block(), quarantine())).isEqualByComparingTo("190");
        assertThat(onHand(p.second(), warehouse)).isEqualByComparingTo("4");

        // Strength lot opened at the gate → result → release → batch released to finished goods.
        JsonNode lots = ok(200, "GET", "/quality/inspection-lots?sourceType=production_order&sourceId=" + orderId, null)
                .get("data");
        assertThat(lots).hasSize(1);
        String lotId = lots.get(0).get("id").asString();
        ok(200, "POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(Map.of(
                "parameterCode", "CS7", "sampleNo", 1, "numericValue", 8.2))));
        Res rel = call("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of(), key(UUID.randomUUID()
                .toString()));
        assertThat(rel.status()).as(rel.raw().body()).isEqualTo(200);
        drainOutbox();
        JsonNode after = ok(200, "GET", BASE + "/curing-batches/" + batchId, null);
        assertThat(after.get("status").asString()).isEqualTo("released");
        assertThat(after.get("qualityLots")).hasSize(1);
        assertThat(onHand(p.block(), warehouse)).isEqualByComparingTo("190");

        JsonNode detail = ok(200, "GET", BASE + "/orders/" + orderId, null);
        assertThat(detail.get("status").asString()).isEqualTo("completed");
        JsonNode c = detail.get("costing");
        assertThat(num(c.get("blocksPressed"))).isEqualByComparingTo("200");
        assertThat(num(c.get("goodBlocks"))).isEqualByComparingTo("190");
        assertThat(num(c.get("secondGrade"))).isEqualByComparingTo("4");
        assertThat(num(c.get("brokenAtPress"))).isEqualByComparingTo("4");
        assertThat(num(c.get("brokenAtCubing"))).isEqualByComparingTo("2");
        assertThat(num(c.get("rejectionRatePct"))).isEqualByComparingTo("5");
        assertThat(num(c.get("materialCost"))).isEqualByComparingTo("1100");
        BigDecimal fg = num(c.get("finishedGoodsValue"));
        assertThat(fg.add(num(c.get("secondGradeValue"))).add(num(c.get("abnormalScrapValue"))))
                .isEqualByComparingTo(num(c.get("totalCost")));
        assertThat(num(c.get("secondGradeValue"))).isEqualByComparingTo("60"); // NRV = 4 × sales price 15
        assertThat(num(c.get("costPerAcceptableBlock"))).isEqualByComparingTo(fg.divide(BigDecimal.valueOf(190), 6,
                java.math.RoundingMode.HALF_UP));
        JsonNode cementVar = c.get("materialVariance").get(0);
        assertThat(num(cementVar.get("standardQty"))).isEqualByComparingTo("100");
        assertThat(num(cementVar.get("varianceQty"))).isEqualByComparingTo("0");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(unbalancedEntries()).isZero();

        JsonNode report = ok(200, "GET", BASE + "/reports/cost-per-block?productId=" + p.block(), null);
        assertThat(report.get("rows")).hasSize(1);
        assertThat(num(report.get("byProduct").get(0).get("goodBlocks"))).isEqualByComparingTo("190");
        JsonNode breakage = ok(200, "GET", BASE + "/reports/breakage?productId=" + p.block() + "&from=" + daysAgo(1),
                null);
        assertThat(breakage.get("byReason")).hasSize(2);
        assertThat(num(breakage.get("secondGrade").get(0).get("secondGrade"))).isEqualByComparingTo("4");
        assertThat(ok(200, "GET", BASE + "/orders?productId=" + p.block(), null).get("meta").get("total").asInt())
                .isEqualTo(1);
        // No final plan for the product → explicit 400.
        assertThat(call("POST", BASE + "/curing-batches/" + batchId + "/final-inspection", null, adminToken).body()
                .get("message").asString()).startsWith("No active final inspection plan");
    }

    @Test
    @DisplayName("Phase 2 exit (b): order → backflush → pressing → curing → strength QC → release, with actual labour,"
            + " machine and overhead in the cost per acceptable block")
    void actualCostPerAcceptableBlock() {
        Plant p = plant(7);
        String press = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "PRS-" + suffix(), "name",
                "Block press")).get("id").asString();
        ok(200, "PUT", "/manufacturing/costing/rates/" + press, Map.of("labourRatePerHour", 10, "machineRatePerHour", 20,
                "overheadRatePerHour", 5));
        JsonNode qs = ok(201, "POST", "/quality/specifications", Map.of("code", "QS-" + suffix(), "name",
                "Block strength", "productId", p.block(), "parameters", List.of(Map.of("code", "CS7", "name",
                        "7-day compressive strength", "kind", "numeric", "unit", "N/mm²", "minValue", 7))));
        ok(200, "POST", "/quality/specifications/" + qs.get("id").asString() + "/activate", null);
        ok(201, "POST", "/quality/inspection-plans", Map.of("code", "QP-" + suffix(), "name", "Curing strength",
                "stage", "in_process", "productId", p.block(), "specificationId", qs.get("id").asString(), "stageKey",
                p.checkpoint(), "independentRelease", false, "samplingMethod", "fixed", "sampleSize", 1));

        BigDecimal wip0 = balance(wipAccount);
        String orderId = order(p, 200).get("id").asString();
        String wo = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null).get(0)
                .get("id").asString();
        ok(200, "PATCH", "/manufacturing/work-orders/" + wo + "/assign", Map.of("workCenterId", press));
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of("labourMins", 240));

        String batchId = keyed(BASE + "/orders/" + orderId + "/pressing-runs", Map.of("qtyGood", 196, "qtyBroken", 4,
                "cycles", 20, "lastRun", true, "date", daysAgo(10))).get("batch").get("id").asString();
        // The pressing stage has closed, so the four hours land on the curing stage: 40 + 80 + 20.
        JsonNode absorbed = keyed("/manufacturing/costing/orders/" + orderId + "/absorb", null);
        assertThat(num(absorbed.get("total"))).isEqualByComparingTo("140");
        assertThat(absorbed.get("skipped")).isEmpty();

        keyed(BASE + "/curing-batches/" + batchId + "/cube", Map.of("qtyGood", 190, "qtySecondGrade", 4, "qtyBroken", 2));
        String lotId = ok(200, "GET", "/quality/inspection-lots?sourceType=production_order&sourceId=" + orderId, null)
                .get("data").get(0).get("id").asString();
        ok(200, "POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(Map.of(
                "parameterCode", "CS7", "sampleNo", 1, "numericValue", 8.2))));
        Res rel = call("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of(), key(UUID.randomUUID()
                .toString()));
        assertThat(rel.status()).as(rel.raw().body()).isEqualTo(200);
        drainOutbox();
        assertThat(onHand(p.block(), warehouse)).isEqualByComparingTo("190");

        JsonNode c = ok(200, "GET", BASE + "/orders/" + orderId, null).get("costing");
        assertThat(num(c.get("materialCost"))).isEqualByComparingTo("1100");
        assertThat(num(c.get("conversionCost"))).isEqualByComparingTo("140");
        assertThat(num(c.get("totalCost"))).isEqualByComparingTo("1240");
        BigDecimal fg = num(c.get("finishedGoodsValue"));
        assertThat(fg.add(num(c.get("secondGradeValue"))).add(num(c.get("abnormalScrapValue")))).isEqualByComparingTo("1240");
        BigDecimal perBlock = fg.divide(BigDecimal.valueOf(190), 6, java.math.RoundingMode.HALF_UP);
        assertThat(num(c.get("costPerAcceptableBlock"))).isEqualByComparingTo(perBlock);

        JsonNode cost = ok(200, "GET", "/manufacturing/costing/orders/" + orderId, null);
        assertThat(num(cost.get("labourCost"))).isEqualByComparingTo("40");
        assertThat(num(cost.get("machineCost"))).isEqualByComparingTo("80");
        assertThat(num(cost.get("overheadCost"))).isEqualByComparingTo("20");
        assertThat(num(cost.get("precast").get("costPerAcceptableBlock"))).isEqualByComparingTo(perBlock);
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(unbalancedEntries()).isZero();
    }

    @Test
    @DisplayName("minimum curing: cubing refused before the hold, a manual pass waits (curing) until the job releases")
    void curingHoldAndDeferredGate() {
        Plant p = plant(7);
        String orderId = order(p, 100).get("id").asString();
        String batchId = keyed(BASE + "/orders/" + orderId + "/pressing-runs", Map.of("qtyGood", 100, "lastRun", true))
                .get("batch").get("id").asString();
        Res early = keyedRes(BASE + "/curing-batches/" + batchId + "/cube", Map.of("qtyGood", 100));
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.body().get("message").asString()).contains("cures until").endsWith("(minimum 7 day(s)).");
        assertThat(ok(200, "GET", BASE + "/curing-batches?ready=true&orderId=" + orderId, null).get("data")).isEmpty();
        assertThat(ok(200, "GET", BASE + "/curing-batches?status=curing&orderId=" + orderId, null).get("data"))
                .hasSize(1);

        // The yard batch is declared cured (e.g. data corrected), but the pressing posting is only minutes old:
        // the 2.1 gate hold keeps a pass waiting.
        jdbc.sql("UPDATE \"PrecastCuringBatch\" SET \"curingUntil\" = \"curingUntil\" - interval '8 days' WHERE id = ?")
                .param(batchId).update();
        keyed(BASE + "/curing-batches/" + batchId + "/cube", Map.of("qtyGood", 100));
        JsonNode held = keyed(BASE + "/curing-batches/" + batchId + "/gate-decision", Map.of("result", "passed",
                "reference", "Cube test 7.4"));
        assertThat(held.get("qcStatus").asString()).isEqualTo("curing");
        assertThat(held.get("qcPassDueAt").isNull()).isFalse();
        assertThat(held.get("status").asString()).isEqualTo("cubed");
        assertThat(onHand(p.block(), warehouse)).isEqualByComparingTo("0");
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + orderId + "/stages", null).get("status")
                .asString()).isEqualTo("qc_hold");

        // Time passes: the job passes the gate once the hold is over.
        jdbc.sql("UPDATE \"ProductionOrderStage\" SET \"qcPassDueAt\" = now() - interval '1 minute' WHERE \"orderId\" = ?"
                + " AND \"qcStatus\" = 'curing'").param(orderId).update();
        jdbc.sql("UPDATE \"ProductionStagePosting\" SET \"postedAt\" = \"postedAt\" - interval '8 days'"
                + " WHERE \"orderId\" = ?").param(orderId).update();
        // Jobs are off in tests (app.jobs.enabled=false): drive the tick directly, as FleetMaintenanceIT does.
        Map<String, Object> tick = new PrecastCuringJob(mfgDb, precast).runForOrg(demoOrg);
        assertThat(((Number) tick.get("gatesPassed")).intValue()).isGreaterThanOrEqualTo(1);
        JsonNode released = ok(200, "GET", BASE + "/curing-batches/" + batchId, null);
        assertThat(released.get("status").asString()).isEqualTo("released");
        assertThat(released.get("qcStatus").asString()).isEqualTo("passed");
        assertThat(released.get("qcReference").asString()).isEqualTo("Cube test 7.4");
        assertThat(onHand(p.block(), warehouse)).isEqualByComparingTo("100");
        assertThat(unbalancedEntries()).isZero();
    }

    @Test
    @DisplayName("mix design versions, validation, curing areas and specs")
    void setupRules() {
        Plant p = plant(5);
        Res activeEdit = call("PATCH", BASE + "/mix-designs/" + p.mixId(), Map.of("minCuringDays", 9), adminToken);
        assertThat(activeEdit.body().get("message").asString()).endsWith("revise it to change the recipe.");
        JsonNode v2 = ok(201, "POST", BASE + "/mix-designs/" + p.mixId() + "/revise", Map.of("reason", "More cement"));
        assertThat(v2.get("status").asString()).isEqualTo("draft");
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("minCuringDays").asInt()).isEqualTo(5);
        assertThat(v2.get("versions")).hasSize(2);
        assertThat(v2.get("change").get("number").asString()).startsWith("ECO-");
        String v2Id = v2.get("id").asString();
        JsonNode edited = ok(200, "PATCH", BASE + "/mix-designs/" + v2Id, Map.of("minCuringDays", 9, "lines", List.of(
                Map.of("productId", p.cement(), "qtyPerBatch", 60), Map.of("productId", p.sand(), "qtyPerBatch", 190))));
        assertThat(edited.get("minCuringDays").asInt()).isEqualTo(9);
        assertThat(edited.get("lines")).hasSize(2);
        Res noCement = call("PATCH", BASE + "/mix-designs/" + v2Id, Map.of("cementProductId", p.agg(), "lines",
                List.of(Map.of("productId", p.sand(), "qtyPerBatch", 1))), adminToken);
        assertThat(noCement.body().get("message").asString()).isEqualTo("The cement product must be one of the mix lines.");
        assertThat(call("POST", BASE + "/mix-designs", Map.of("name", "x", "productId", p.block(), "unitsPerBatch", 0,
                "lines", List.of()), adminToken).status()).isEqualTo(400);
        assertThat(ok(200, "GET", BASE + "/mix-designs?productId=" + p.block(), null).get("meta").get("total").asInt())
                .isEqualTo(2);
        assertThat(ok(200, "GET", BASE + "/mix-designs?productId=" + p.block() + "&status=active", null).get("data"))
                .hasSize(1);

        String code = "CH-" + suffix();
        String areaId = ok(201, "POST", BASE + "/curing-areas", Map.of("code", code, "name", "Steam chamber", "kind",
                "chamber")).get("id").asString();
        assertThat(call("POST", BASE + "/curing-areas", Map.of("code", code.toLowerCase(), "name", "dup"), adminToken)
                .status()).isEqualTo(409);
        assertThat(ok(200, "PATCH", BASE + "/curing-areas/" + areaId, Map.of("capacityPallets", 12)).get(
                "capacityPallets").asInt()).isEqualTo(12);
        assertThat(ok(200, "GET", BASE + "/curing-areas", null).toString()).contains(code.toUpperCase());
        Res same = call("PUT", BASE + "/product-specs/" + p.block(), Map.of("secondGradeProductId", p.block()),
                adminToken);
        assertThat(same.body().get("message").asString()).isEqualTo(
                "The second-grade product must differ from the block product.");
        assertThat(ok(200, "GET", BASE + "/product-specs?search=7%20N", null).get("meta").get("total").asInt())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("idempotent pressing replay, Idempotency-Key required, contended cubing")
    void idempotencyAndConcurrency() throws Exception {
        Plant p = plant(0);
        String orderId = order(p, 100).get("id").asString();
        String k = UUID.randomUUID().toString();
        Map<String, Object> run = Map.of("qtyGood", 100, "lastRun", true);
        Res first = call("POST", BASE + "/orders/" + orderId + "/pressing-runs", run, key(k));
        Res replay = call("POST", BASE + "/orders/" + orderId + "/pressing-runs", run, key(k));
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("batch").get("id").asString()).isEqualTo(first.body().get("batch").get("id")
                .asString());
        assertThat(ok(200, "GET", BASE + "/curing-batches?orderId=" + orderId, null).get("data")).hasSize(1);
        assertThat(call("POST", BASE + "/orders/" + orderId + "/pressing-runs", run, adminToken).status())
                .isEqualTo(400);
        String batchId = first.body().get("batch").get("id").asString();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return keyedRes(BASE + "/curing-batches/" + batchId + "/cube", Map.of("qtyGood", 100));
            }));
        }
        go.countDown();
        int okCount = 0;
        int refused = 0;
        for (Future<Res> f : futures) {
            Res r = f.get();
            if (r.status() == 200) {
                okCount++;
            } else {
                refused++;
                assertThat(r.body().get("message").asString()).endsWith("only a curing batch can be cubed.");
            }
        }
        pool.shutdown();
        assertThat(okCount).isEqualTo(1);
        assertThat(refused).isEqualTo(1);
        assertThat(onHand(p.block(), quarantine())).isEqualByComparingTo("100");
        // No quality plan: the manual gate releases it (no curing hold with 0 days).
        JsonNode rel = keyed(BASE + "/curing-batches/" + batchId + "/gate-decision", Map.of("result", "passed"));
        assertThat(rel.get("status").asString()).isEqualTo("released");
        assertThat(onHand(p.block(), warehouse)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("tenant isolation: another organization sees none of the precast records")
    void tenancy() {
        Plant p = plant(0);
        String orderId = order(p, 100).get("id").asString();
        String batchId = keyed(BASE + "/orders/" + orderId + "/pressing-runs", Map.of("qtyGood", 50)).get("batch")
                .get("id").asString();
        assertThat(call("GET", BASE + "/mix-designs/" + p.mixId(), null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", BASE + "/orders/" + orderId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", BASE + "/curing-batches/" + batchId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", BASE + "/curing-batches", null, otherToken).body().toString()).doesNotContain(batchId);
        assertThat(call("GET", BASE + "/orders", null, otherToken).body().toString()).doesNotContain(orderId);
        assertThat(call("GET", BASE + "/curing-areas", null, otherToken).body().toString()).doesNotContain(p.area());
        assertThat(call("GET", BASE + "/product-specs", null, otherToken).body().toString()).doesNotContain(p.block());
        Map<String, String> other = new LinkedHashMap<>();
        other.put("Authorization", "Bearer " + otherToken);
        other.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", BASE + "/curing-batches/" + batchId + "/cube", Map.of("qtyGood", 50), other).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("authorization matrix of the precast routes: 401 without token, 403 without permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(PrecastController.class)).isEqualTo(26);
    }
}
