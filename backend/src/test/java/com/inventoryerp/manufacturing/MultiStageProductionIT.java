package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.events.OutboxWorker;
import com.inventoryerp.manufacturing.web.MultiStageController;
import java.math.BigDecimal;
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
 * Multi-stage production &amp; WIP between stages (ADR-S011, Phase 2.1) on Testcontainers Postgres: a three-stage order
 * (crush → intermediate into a stage location → mix consumes it, pass-through → mould with co-product + waste),
 * partial completion, by-product at NRV, joint-cost allocation, GL WIP / FG balanced and reconciled, LIFO reversal,
 * QC stage gate, multi-level child orders with pegging and roll-up, concurrency on stage issue, idempotent replay,
 * legacy-route guards, tenant isolation and the authorization matrix of the new routes.
 */
class MultiStageProductionIT extends MfgTestSupport {

    @Autowired
    OutboxWorker outbox;

    void drainOutbox() {
        for (int i = 0; i < 5; i++) {
            detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
            outbox.tick();
        }
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res keyedRes(String path, Object body) {
        return call("POST", path, body, key());
    }

    JsonNode keyed(String path, Object body) {
        Res r = keyedRes(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    String routing(String name, String... ops) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < ops.length; i++) {
            list.add(Map.of("sequence", (i + 1) * 10, "name", ops[i]));
        }
        return ok(201, "POST", "/manufacturing/routings", Map.of("name", name + " " + suffix(), "operations", list))
                .get("id").asString();
    }

    List<String> opIds(String routingId) {
        List<String> ids = new ArrayList<>();
        ok(200, "GET", "/manufacturing/routings/" + routingId, null).get("operations").forEach(o -> ids.add(o.get("id")
                .asString()));
        return ids;
    }

    String activeBom(String output, int outputQty, List<Map<String, Object>> lines, String routingId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Staged " + suffix(), "outputProductId", output,
                "outputQuantity", outputQty, "lines", lines));
        if (routingId != null) {
            body.put("routingId", routingId);
        }
        String id = ok(201, "POST", "/manufacturing/boms", body).get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + id + "/activate", null);
        return id;
    }

    static Map<String, Object> line(String product, int qty) {
        return Map.of("componentProductId", product, "quantity", qty);
    }

    static Map<String, Object> stage(String opId, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationId", opId);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    JsonNode stages(String orderId) {
        return ok(200, "GET", "/manufacturing/production-orders/" + orderId + "/stages", null);
    }

    static String stageId(JsonNode view, int index) {
        return view.get("stages").get(index).get("id").asString();
    }

    static String outputId(JsonNode stage, String productId) {
        for (JsonNode o : stage.get("outputs")) {
            if (productId.equals(o.get("productId").asString())) {
                return o.get("id").asString();
            }
        }
        throw new AssertionError("no output " + productId);
    }

    BigDecimal unbalancedStageJournals() {
        return jdbc.sql("SELECT coalesce(sum(jl.debit), 0) - coalesce(sum(jl.credit), 0) FROM \"JournalLine\" jl JOIN"
                + " \"JournalEntry\" je ON je.id = jl.\"journalEntryId\" WHERE je.\"organizationId\" = ? AND"
                + " je.\"sourceType\" IN ('production_stage', 'production_stage_reversal')").param(demoOrg)
                .query(BigDecimal.class).single();
    }

    long unbalancedEntries() {
        return jdbc.sql("SELECT count(*) FROM (SELECT je.id FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = je.id WHERE je.\"organizationId\" = ? AND je.\"sourceType\" IN"
                + " ('production_stage', 'production_stage_reversal') GROUP BY je.id HAVING sum(jl.debit) <>"
                + " sum(jl.credit)) x").param(demoOrg).query(Long.class).single();
    }

    @Test
    @DisplayName("3 stages: crush (by-product at NRV) → intermediate in stage location → mix (manual, pass-through)"
            + " → mould (co-product + waste), partial completion, GL WIP / FG balanced, LIFO reversal")
    void threeStageOrder() {
        String rock = product("ROCK");
        String cement = product("CEMENT");
        String crushed = product("CRUSHED");
        String dust = product("DUST");
        String block = product("BLOCK");
        String half = product("HALF");
        String fines = product("FINES");
        String stageLoc = location("production");
        stockIn(bakery, rock, 200, 5);
        stockIn(bakery, cement, 50, 20);

        String routingId = routing("Block line", "Crush", "Mix", "Mould");
        List<String> ops = opIds(routingId);
        JsonNode setupRes = ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages",
                List.of(stage(ops.get(0), "issueMode", "backflush", "outputProductId", crushed, "outputPerUnit", 2,
                                "stageLocationId", stageLoc, "materialProductIds", List.of(rock)),
                        stage(ops.get(1), "issueMode", "manual", "materialProductIds", List.of(cement)),
                        stage(ops.get(2), "issueMode", "backflush"))));
        assertThat(setupRes.get("operations").get(0).get("stage").get("outputProductId").asString()).isEqualTo(crushed);
        assertThat(setupRes.get("operations").get(2).get("isLast").asBoolean()).isTrue();
        Res badOp = call("PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages",
                List.of(stage(UUID.randomUUID().toString()))), adminToken);
        assertThat(badOp.status()).isEqualTo(400);

        String bomId = activeBom(block, 100, List.of(line(rock, 200), line(cement, 50)), routingId);
        JsonNode bomSetup = ok(200, "PUT", "/manufacturing/boms/" + bomId + "/stage-setup", Map.of(
                "coProducts", List.of(
                        Map.of("productId", dust, "role", "by_product", "operationId", ops.get(0), "quantity", 20,
                                "valuation", "nrv", "nrvUnitValue", 2),
                        Map.of("productId", half, "role", "co_product", "quantity", 20),
                        Map.of("productId", fines, "role", "waste", "quantity", 10)),
                "allocationRule", Map.of("method", "quantity")));
        assertThat(bomSetup.get("coProducts")).hasSize(3);
        assertThat(bomSetup.get("effectiveRule").get("source").asString()).isEqualTo("bom");
        assertThat(bomSetup.get("coProducts").get(2).get("valuation").asString()).isEqualTo("zero");
        Res dup = call("PUT", "/manufacturing/boms/" + bomId + "/stage-setup", Map.of("coProducts", List.of(
                Map.of("productId", block, "role", "co_product", "quantity", 1))), adminToken);
        assertThat(dup.body().get("message").asString()).isEqualTo("The BOM output cannot also be a co-product.");

        BigDecimal wip0 = balance(wipAccount);
        BigDecimal sv0 = balance(mapped("stock_valuation"));
        BigDecimal unbalanced0 = unbalancedStageJournals();

        JsonNode created = ok(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", 100, "locationId", bakery, "outputLocationId", warehouse)));
        String orderId = created.get("id").asString();
        String code = created.get("orderCode").asString();
        assertThat(created.get("multiStage").asBoolean()).isTrue();
        assertThat(created.get("stages")).hasSize(3);
        assertThat(num(created.get("stages").get(0).get("plannedOutputQty"))).isEqualByComparingTo("200");
        assertThat(created.get("stages").get(0).get("materials")).hasSize(1);
        assertThat(created.get("stages").get(1).get("materials").get(0).get("productId").asString()).isEqualTo(cement);
        assertThat(created.get("stages").get(2).get("outputs")).hasSize(3); // main + co-product + waste
        String s1 = stageId(created, 0);
        String s2 = stageId(created, 1);
        String s3 = stageId(created, 2);

        // Draft orders cannot post; the single-leg start refuses a multi-stage order.
        assertThat(keyedRes("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 1)).body()
                .get("message").asString()).isEqualTo("Confirm order " + code + " before posting to its stages.");
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/confirm", null);
        assertThat(as("POST", "/manufacturing/production-orders/" + orderId + "/start", Map.of()).body().get("message")
                .asString()).isEqualTo("Order " + code + " is a multi-stage order; use its stage routes.");

        // Stage 1, partial: 100 t crushed (+ 10 t dust by default) backflushes half the rock.
        JsonNode p1 = keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 100));
        assertThat(p1.get("status").asString()).isEqualTo("in_progress");
        assertThat(onHand(rock, bakery)).isEqualByComparingTo("100");
        assertThat(onHand(crushed, stageLoc)).isEqualByComparingTo("100");
        assertThat(onHand(dust, warehouse)).isEqualByComparingTo("10");
        assertThat(num(p1.get("stages").get(0).get("wipValue"))).isEqualByComparingTo("0");
        assertThat(num(p1.get("stages").get(0).get("outputCost"))).isEqualByComparingTo("500");
        assertThat(num(p1.get("yield").get("goodValue"))).isEqualByComparingTo("500");

        // Stage 3 cannot complete before stage 2; stage 2 needs stage 1 complete.
        assertThat(keyedRes("/manufacturing/production-stages/" + s3 + "/output", Map.of("qtyGood", 1, "complete",
                true)).body().get("message").asString()).isEqualTo("Complete stage 1 · Crush first.");

        JsonNode p2 = keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 100, "complete",
                true));
        assertThat(p2.get("stages").get(0).get("status").asString()).isEqualTo("completed");
        assertThat(num(p2.get("stages").get(0).get("qtyGood"))).isEqualByComparingTo("200");
        assertThat(onHand(rock, bakery)).isEqualByComparingTo("0");
        assertThat(num(p2.get("stages").get(0).get("intermediateAvailable"))).isEqualByComparingTo("200");

        // Stage 2 (manual): issue the intermediate from the stage location + cement, add labour, complete.
        String cementLine = p2.get("stages").get(1).get("materials").get(0).get("id").asString();
        Res tooMuch = keyedRes("/manufacturing/production-stages/" + s2 + "/issue", Map.of("lines",
                List.of(Map.of("productId", crushed, "quantity", 250))));
        assertThat(tooMuch.body().get("message").asString())
                .isEqualTo("Only 200 of stage 1's released output is available for stage 2.");
        keyed("/manufacturing/production-stages/" + s2 + "/issue", Map.of("lines", List.of(
                Map.of("productId", crushed, "quantity", 200), Map.of("materialId", cementLine, "quantity", 50))));
        JsonNode conv = keyed("/manufacturing/production-stages/" + s2 + "/conversion-costs", Map.of("amount", 40,
                "kind", "labour"));
        assertThat(num(conv.get("stages").get(1).get("wipValue"))).isEqualByComparingTo("2000");
        assertThat(onHand(crushed, stageLoc)).isEqualByComparingTo("0");

        // WIP reconciled: GL WIP moved by exactly the order's stage WIP.
        assertThat(balance(wipAccount).subtract(wip0)).isEqualByComparingTo("2000");
        JsonNode report = ok(200, "GET", "/manufacturing/reports/stage-wip", null);
        BigDecimal orderWip = BigDecimal.ZERO;
        for (JsonNode r : report.get("stages")) {
            if (orderId.equals(r.get("orderId").asString())) {
                orderWip = orderWip.add(num(r.get("wipValue")));
            }
        }
        assertThat(orderWip).isEqualByComparingTo("2000");
        assertThat(report.get("totals").has("drift")).isTrue();
        JsonNode legacyRecon = ok(200, "GET", "/manufacturing/reports/wip-reconciliation", null);
        boolean seen = false;
        for (JsonNode r : legacyRecon.get("openOrders")) {
            if (orderId.equals(r.get("id").asString())) {
                seen = true;
                assertThat(num(r.get("wip"))).isEqualByComparingTo("2000");
            }
        }
        assertThat(seen).isTrue();

        JsonNode p4 = keyed("/manufacturing/production-stages/" + s2 + "/output", Map.of("qtyGood", 100, "complete",
                true));
        assertThat(num(p4.get("stages").get(1).get("wipValue"))).isEqualByComparingTo("0");
        assertThat(num(p4.get("stages").get(2).get("transferredInCost"))).isEqualByComparingTo("2000");

        // Stage 3, partial then complete: 50 blocks + 10 half + 5 fines each time.
        JsonNode s3view = p4.get("stages").get(2);
        String halfOut = outputId(s3view, half);
        String finesOut = outputId(s3view, fines);
        JsonNode p5 = keyed("/manufacturing/production-stages/" + s3 + "/output", Map.of("qtyGood", 50, "outputs",
                List.of(Map.of("outputId", halfOut, "quantity", 10), Map.of("outputId", finesOut, "quantity", 5))));
        assertThat(p5.get("status").asString()).isEqualTo("in_progress");
        assertThat(num(p5.get("producedQty"))).isEqualByComparingTo("50");
        assertThat(onHand(block, warehouse)).isEqualByComparingTo("50");
        assertThat(num(p5.get("stages").get(2).get("wipValue"))).isEqualByComparingTo("1000");

        JsonNode done = keyed("/manufacturing/production-stages/" + s3 + "/output", Map.of("qtyGood", 50, "complete",
                true, "outputs", List.of(Map.of("outputId", halfOut, "quantity", 10),
                        Map.of("outputId", finesOut, "quantity", 5))));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(num(done.get("producedQty"))).isEqualByComparingTo("100");
        assertThat(num(done.get("wipValue"))).isEqualByComparingTo("0");
        assertThat(onHand(block, warehouse)).isEqualByComparingTo("100");
        assertThat(onHand(half, warehouse)).isEqualByComparingTo("20");
        assertThat(onHand(fines, warehouse)).isEqualByComparingTo("10");
        BigDecimal blockValue = BigDecimal.ZERO;
        BigDecimal halfValue = BigDecimal.ZERO;
        BigDecimal finesValue = BigDecimal.ZERO;
        BigDecimal dustValue = BigDecimal.ZERO;
        for (JsonNode o : done.get("outputs")) {
            String p = o.get("productId").asString();
            BigDecimal v = num(o.get("totalCost"));
            if (p.equals(block)) {
                blockValue = v;
                assertThat(o.get("outputRole").asString()).isEqualTo("main");
            } else if (p.equals(half)) {
                halfValue = v;
            } else if (p.equals(fines)) {
                finesValue = v;
            } else if (p.equals(dust)) {
                dustValue = v;
            }
        }
        // Joint cost 2,000 by quantity over 100 blocks + 20 halves; fines at zero; dust at NRV 2 × 20.
        assertThat(blockValue.add(halfValue)).isEqualByComparingTo("2000");
        assertThat(blockValue).isEqualByComparingTo("1666.666666");
        assertThat(finesValue).isEqualByComparingTo("0");
        assertThat(dustValue).isEqualByComparingTo("40");
        // GL: WIP back to where it was, stock up by the absorbed labour, every entry balanced.
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(balance(mapped("stock_valuation")).subtract(sv0)).isEqualByComparingTo("40");
        assertThat(unbalancedStageJournals()).isEqualByComparingTo(unbalanced0);
        assertThat(unbalancedEntries()).isZero();
        assertThat(keyedRes("/manufacturing/production-stages/" + s3 + "/output", Map.of("qtyGood", 1)).body()
                .get("message").asString()).isEqualTo("Order " + code + " is completed; its stages are closed.");

        // Reversal: only the latest posting can be reversed (LIFO), then the whole order.
        String firstPosting = done.get("stages").get(0).get("postings").get(0).get("id").asString();
        Res notLatest = keyedRes("/manufacturing/stage-postings/" + firstPosting + "/reverse", Map.of("reason", "x"));
        assertThat(notLatest.body().get("message").asString()).startsWith("Reverse the later postings of this order");
        JsonNode lastPosting = done.get("stages").get(2).get("postings").get(1);
        JsonNode afterOne = keyed("/manufacturing/stage-postings/" + lastPosting.get("id").asString() + "/reverse",
                Map.of("reason", "wrong count"));
        assertThat(afterOne.get("status").asString()).isEqualTo("in_progress");
        assertThat(onHand(block, warehouse)).isEqualByComparingTo("50");
        assertThat(num(afterOne.get("stages").get(2).get("wipValue"))).isEqualByComparingTo("1000");
        assertThat(as("POST", "/manufacturing/production-orders/" + orderId + "/cancel", Map.of()).body()
                .get("message").asString()).isEqualTo("Order " + code
                + " is a multi-stage order; use its stage routes (reverse its stage postings to cancel it).");

        JsonNode reversed = keyed("/manufacturing/production-orders/" + orderId + "/stages/reverse",
                Map.of("reason", "test"));
        assertThat(reversed.get("status").asString()).isEqualTo("cancelled");
        assertThat(onHand(rock, bakery)).isEqualByComparingTo("200");
        assertThat(onHand(cement, bakery)).isEqualByComparingTo("50");
        assertThat(onHand(block, warehouse)).isEqualByComparingTo("0");
        assertThat(onHand(crushed, stageLoc)).isEqualByComparingTo("0");
        assertThat(onHand(dust, warehouse)).isEqualByComparingTo("0");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(balance(mapped("stock_valuation")).subtract(sv0).abs()).isLessThan(new BigDecimal("0.05"));
        assertThat(unbalancedEntries()).isZero();
        assertThat(keyedRes("/manufacturing/production-orders/" + orderId + "/stages/reverse", Map.of()).body()
                .get("message").asString()).isEqualTo("Order already reversed.");
    }

    @Test
    @DisplayName("stage gate: QC-gated output waits in quarantine, blocks the next stage until passed; idempotent replay")
    void stageGate() {
        String sand = product("SAND");
        String green = product("GREEN");
        String cured = product("CURED");
        String curing = location("production");
        stockIn(bakery, sand, 10, 3);
        String routingId = routing("Gate line", "Mould", "Cure");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "outputProductId", green, "stageLocationId", curing, "qcGate", true,
                        "qcCheckpointCode", "GREEN-STRENGTH"))));
        String bomId = activeBom(cured, 10, List.of(line(sand, 10)), routingId);
        JsonNode order = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "plannedQty", 10,
                "locationId", bakery, "outputLocationId", warehouse));
        String orderId = order.get("id").asString();
        JsonNode view = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/stages/initialize", Map.of());
        assertThat(view.get("stages").get(0).get("qcStatus").asString()).isEqualTo("pending");
        // Initialize is idempotent.
        assertThat(ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/stages/initialize", null)
                .get("stages")).hasSize(2);
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/confirm", null);
        String s1 = stageId(view, 0);
        String s2 = stageId(view, 1);

        // Idempotent replay: the same key and body run the posting once.
        Map<String, String> h = key();
        Res first = call("POST", "/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 10,
                "complete", true), h);
        Res replay = call("POST", "/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 10,
                "complete", true), h);
        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("stages").get(0).get("postings").get(0).get("code").asString())
                .isEqualTo(first.body().get("stages").get(0).get("postings").get(0).get("code").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductionStagePosting\" WHERE \"orderId\" = ?").param(orderId)
                .query(Long.class).single()).isEqualTo(1L);
        Res noKey = call("POST", "/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 1),
                adminToken);
        assertThat(noKey.status()).isEqualTo(400);

        String quarantine = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                + " AND code = 'QC-QUARANTINE' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
        assertThat(onHand(green, quarantine)).isEqualByComparingTo("10");
        assertThat(onHand(green, curing)).isEqualByComparingTo("0");
        String held = "Stage 1 · Mould is held at its QC checkpoint (pending); stage 2 cannot proceed.";
        assertThat(keyedRes("/manufacturing/production-stages/" + s2 + "/output", Map.of("qtyGood", 10, "complete",
                true)).body().get("message").asString()).isEqualTo(held);
        assertThat(stages(orderId).get("stages").get(1).get("blockedBy").asString()).isEqualTo(held);

        keyed("/manufacturing/production-stages/" + s1 + "/qc-decision", Map.of("result", "failed", "reference",
                "LOT-1"));
        assertThat(keyedRes("/manufacturing/production-stages/" + s2 + "/output", Map.of("qtyGood", 10)).body()
                .get("message").asString()).contains("(failed)");
        assertThat(keyedRes("/manufacturing/production-stages/" + s2 + "/qc-decision", Map.of("result", "passed"))
                .body().get("message").asString()).isEqualTo("Stage 2 has no QC checkpoint.");

        JsonNode passed = keyed("/manufacturing/production-stages/" + s1 + "/qc-decision", Map.of("result", "passed",
                "reference", "LOT-2"));
        assertThat(passed.get("stages").get(0).get("qcStatus").asString()).isEqualTo("passed");
        assertThat(passed.get("stages").get(0).get("qcReference").asString()).isEqualTo("LOT-2");
        assertThat(onHand(green, curing)).isEqualByComparingTo("10");
        assertThat(onHand(green, quarantine)).isEqualByComparingTo("0");

        JsonNode done = keyed("/manufacturing/production-stages/" + s2 + "/output", Map.of("qtyGood", 10, "complete",
                true));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(onHand(green, curing)).isEqualByComparingTo("0");
        assertThat(onHand(cured, warehouse)).isEqualByComparingTo("10");
        assertThat(num(done.get("outputUnitCost"))).isEqualByComparingTo("3");
        assertThat(unbalancedEntries()).isZero();
    }

    @Test
    @DisplayName("stage gate + quality (2.6): gated output opens an in-process lot; reject fails, release passes the gate")
    void stageGateWithQualityLot() {
        String sand = product("SAND");
        String green = product("GREEN");
        String cured = product("CURED");
        String curing = location("production");
        stockIn(bakery, sand, 20, 3);
        String checkpoint = "CURE-" + suffix();
        String routingId = routing("QC line", "Mould", "Cure");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "outputProductId", green, "stageLocationId", curing, "qcGate", true,
                        "qcCheckpointCode", checkpoint))));
        JsonNode spec = ok(201, "POST", "/quality/specifications", Map.of("code", "SPEC-" + suffix(), "name",
                "Green strength", "productId", green, "parameters", List.of(Map.of("code", "STR", "name", "Strength",
                        "kind", "numeric", "unit", "MPa", "minValue", 3))));
        ok(200, "POST", "/quality/specifications/" + spec.get("id").asString() + "/activate", null);
        ok(201, "POST", "/quality/inspection-plans", Map.of("code", "PLAN-" + suffix(), "name", "Curing plan",
                "stage", "in_process", "productId", green, "specificationId", spec.get("id").asString(), "stageKey",
                checkpoint, "independentRelease", false, "samplingMethod", "fixed", "sampleSize", 1));
        String bomId = activeBom(cured, 10, List.of(line(sand, 10)), routingId);

        // Order 1: the lot is rejected → the stage gate fails and the output stays in quarantine.
        String o1 = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "plannedQty", 10,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        JsonNode v1 = ok(201, "POST", "/manufacturing/production-orders/" + o1 + "/stages/initialize", Map.of());
        ok(201, "POST", "/manufacturing/production-orders/" + o1 + "/confirm", null);
        String s1 = stageId(v1, 0);
        keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 10, "complete", true));
        JsonNode lots1 = ok(200, "GET", "/quality/inspection-lots?sourceType=production_order&sourceId=" + o1, null)
                .get("data");
        assertThat(lots1).hasSize(1);
        JsonNode lot1 = lots1.get(0);
        assertThat(lot1.get("stage").asString()).isEqualTo("in_process");
        assertThat(lot1.get("stockControl").asString()).isEqualTo("source");
        assertThat(num(lot1.get("quantity"))).isEqualByComparingTo("10");
        String quarantine = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                + " AND code = 'QC-QUARANTINE' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
        assertThat(onHand(green, quarantine)).isEqualByComparingTo("10");
        Res rej = call("POST", "/quality/inspection-lots/" + lot1.get("id").asString() + "/reject", Map.of(
                "quantity", 10, "disposition", "scrap", "reason", "Cracked"), key());
        assertThat(rej.status()).as(rej.raw().body()).isEqualTo(200);
        drainOutbox();
        JsonNode st1 = stages(o1).get("stages").get(0);
        assertThat(st1.get("qcStatus").asString()).isEqualTo("failed");
        assertThat(st1.get("qcReference").asString()).isEqualTo(lot1.get("lotNumber").asString());

        // Order 2: results pass and the lot is released → the gate passes, the output moves to the stage location.
        String o2 = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "plannedQty", 10,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        JsonNode v2 = ok(201, "POST", "/manufacturing/production-orders/" + o2 + "/stages/initialize", Map.of());
        ok(201, "POST", "/manufacturing/production-orders/" + o2 + "/confirm", null);
        String s21 = stageId(v2, 0);
        keyed("/manufacturing/production-stages/" + s21 + "/output", Map.of("qtyGood", 10, "complete", true));
        String lot2 = ok(200, "GET", "/quality/inspection-lots?sourceType=production_order&sourceId=" + o2, null)
                .get("data").get(0).get("id").asString();
        ok(200, "POST", "/quality/inspection-lots/" + lot2 + "/results", Map.of("results", List.of(Map.of(
                "parameterCode", "STR", "sampleNo", 1, "numericValue", 4.5))));
        Res rel = call("POST", "/quality/inspection-lots/" + lot2 + "/release", Map.of(), key());
        assertThat(rel.status()).as(rel.raw().body()).isEqualTo(200);
        assertThat(rel.body().get("status").asString()).isEqualTo("closed");
        drainOutbox();
        assertThat(stages(o2).get("stages").get(0).get("qcStatus").asString()).isEqualTo("passed");
        assertThat(onHand(green, curing)).isEqualByComparingTo("10");
        JsonNode done = keyed("/manufacturing/production-stages/" + stageId(v2, 1) + "/output", Map.of("qtyGood", 10,
                "complete", true));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(unbalancedEntries()).isZero();
    }

    @Test
    @DisplayName("multi-level: parent order creates pegged child + grandchild orders (net of stock), roll-up")
    void multiLevel() {
        String steel = product("STEEL");
        String frame = product("FRAME");
        String fiber = product("FIBER");
        String foam = product("FOAM");
        String seat = product("SEAT");
        String chair = product("CHAIR");
        bom(frame, steel, 1, 2, Map.of());
        bom(foam, fiber, 1, 3, Map.of());
        bom(seat, foam, 1, 1, Map.of());
        String chairBom = activeBom(chair, 1, List.of(line(frame, 2), line(seat, 1)), null);
        stockIn(bakery, frame, 5, 50);
        stockIn(bakery, steel, 30, 10);

        JsonNode parent = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", chairBom, "plannedQty",
                10, "locationId", bakery, "outputLocationId", warehouse));
        String parentId = parent.get("id").asString();
        JsonNode tree = ok(201, "POST", "/manufacturing/production-orders/" + parentId + "/child-orders",
                Map.of("netAgainstStock", true));
        assertThat(tree.get("children")).hasSize(2);
        assertThat(tree.get("rolledStatus").asString()).isEqualTo("awaiting_components");
        JsonNode frameNode = null;
        JsonNode seatNode = null;
        for (JsonNode c : tree.get("children")) {
            if (frame.equals(c.get("outputProductId").asString())) {
                frameNode = c;
            } else if (seat.equals(c.get("outputProductId").asString())) {
                seatNode = c;
            }
        }
        assertThat(frameNode).isNotNull();
        assertThat(seatNode).isNotNull();
        assertThat(num(frameNode.get("plannedQty"))).isEqualByComparingTo("15"); // 20 needed − 5 on hand
        assertThat(num(frameNode.get("peg").get("quantity"))).isEqualByComparingTo("15");
        assertThat(seatNode.get("children")).hasSize(1); // FOAM grandchild
        assertThat(seatNode.get("children").get(0).get("peg").get("level").asInt()).isEqualTo(2);
        String frameOrder = frameNode.get("id").asString();
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + frameOrder, null).get("parentOrderId")
                .asString()).isEqualTo(parentId);

        // Idempotent: pegged materials are not exploded again.
        assertThat(ok(201, "POST", "/manufacturing/production-orders/" + parentId + "/child-orders", null)
                .get("children")).hasSize(2);

        // Child delivers into the parent's production location.
        ok(201, "POST", "/manufacturing/production-orders/" + frameOrder + "/confirm", null);
        ok(201, "POST", "/manufacturing/production-orders/" + frameOrder + "/start", Map.of());
        ok(201, "POST", "/manufacturing/production-orders/" + frameOrder + "/complete", Map.of("qtyProduced", 15));
        assertThat(onHand(frame, bakery)).isEqualByComparingTo("20");
        JsonNode after = ok(200, "GET", "/manufacturing/production-orders/" + parentId + "/order-tree", null);
        assertThat(after.get("childrenDone").asInt()).isEqualTo(1);
        assertThat(after.get("childrenOpen").asInt()).isEqualTo(1);
        assertThat(after.get("rolledStatus").asString()).isEqualTo("awaiting_components");

        ok(201, "POST", "/manufacturing/production-orders/" + parentId + "/confirm", null);
        stockIn(bakery, seat, 10, 1);
        ok(201, "POST", "/manufacturing/production-orders/" + parentId + "/start", Map.of());
        assertThat(as("POST", "/manufacturing/production-orders/" + parentId + "/child-orders", Map.of()).body()
                .get("message").asString()).startsWith("Child orders can only be created for a draft or confirmed");
    }

    @Test
    @DisplayName("concurrency: two simultaneous manual issues of the same scarce material — one wins, stock never negative")
    void concurrentIssue() throws Exception {
        String comp = product("COMP");
        String out = product("OUT");
        stockIn(bakery, comp, 10, 4);
        String routingId = routing("Single", "Assemble");
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(opIds(routingId).get(0), "issueMode", "manual"))));
        String bomId = activeBom(out, 10, List.of(line(comp, 10)), routingId);
        JsonNode view = ok(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", 10, "locationId", bakery, "outputLocationId", warehouse)));
        String orderId = view.get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/confirm", null);
        String s1 = stageId(view, 0);
        String materialId = view.get("stages").get(0).get("materials").get(0).get("id").asString();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return keyedRes("/manufacturing/production-stages/" + s1 + "/issue", Map.of("lines",
                        List.of(Map.of("materialId", materialId, "quantity", 8))));
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
                assertThat(r.body().get("message").asString()).startsWith("Insufficient stock for");
            }
        }
        pool.shutdown();
        assertThat(okCount).isEqualTo(1);
        assertThat(refused).isEqualTo(1);
        assertThat(onHand(comp, bakery)).isEqualByComparingTo("2");
        JsonNode after = stages(orderId);
        assertThat(num(after.get("stages").get(0).get("materialCost"))).isEqualByComparingTo("32");
        assertThat(after.get("stages").get(0).get("postings")).hasSize(1);
    }

    @Test
    @DisplayName("allocation rule settings: organization default and BOM overrides")
    void allocationRules() {
        JsonNode saved = ok(200, "PUT", "/manufacturing/cost-allocation-rules/default", Map.of("method",
                "market_value"));
        assertThat(saved.get("organizationDefault").get("method").asString()).isEqualTo("market_value");
        assertThat(saved.get("effectiveDefault").get("source").asString()).isEqualTo("organization");
        Res bad = call("PUT", "/manufacturing/cost-allocation-rules/default", Map.of("method", "random"), adminToken);
        assertThat(bad.status()).isEqualTo(400);
        ok(200, "PUT", "/manufacturing/cost-allocation-rules/default", Map.of("method", "quantity"));
        assertThat(ok(200, "GET", "/manufacturing/cost-allocation-rules", null).get("bomOverrides").isArray()).isTrue();
    }

    @Test
    @DisplayName("tenant isolation and 404s")
    void tenancy() {
        String a = product("TA");
        String b = product("TB");
        String routingId = routing("Iso", "Only");
        String bomId = activeBom(a, 1, List.of(line(b, 1)), routingId);
        JsonNode view = ok(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", 1, "locationId", bakery, "outputLocationId", warehouse)));
        String orderId = view.get("id").asString();
        String s1 = stageId(view, 0);
        assertThat(call("GET", "/manufacturing/production-orders/" + orderId + "/stages", null, otherToken).status())
                .isEqualTo(404);
        Map<String, String> other = new LinkedHashMap<>();
        other.put("Authorization", "Bearer " + otherToken);
        other.put("Idempotency-Key", UUID.randomUUID().toString());
        Res foreign = call("POST", "/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 1), other);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.body().get("message").asString()).isEqualTo("Production stage not found");
        assertThat(call("GET", "/manufacturing/boms/" + bomId + "/stage-setup", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/manufacturing/routings/" + routingId + "/stage-setup", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/manufacturing/reports/stage-wip", null, otherToken).body().toString())
                .doesNotContain(orderId);
        assertThat(as("GET", "/manufacturing/production-orders/" + UUID.randomUUID() + "/order-tree", null).body()
                .get("message").asString()).isEqualTo("Production order not found");
        assertThat(keyedRes("/manufacturing/stage-postings/" + UUID.randomUUID() + "/reverse", Map.of()).body()
                .get("message").asString()).isEqualTo("Stage posting not found");
    }

    @Test
    @DisplayName("authorization matrix of the multi-stage routes: 401 without token, 403 without permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(MultiStageController.class)).isEqualTo(18);
    }
}
