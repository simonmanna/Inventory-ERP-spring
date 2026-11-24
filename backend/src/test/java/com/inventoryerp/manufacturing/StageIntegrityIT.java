package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.BooksGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Integrity fixes 2026-11-24 (MS-1 … MS-8) of the multi-stage engine: intermediate issued at this order's own cost and
 * lots, fixed-% allocation over what a posting made, standard-cost variance rows offset on reversal, reversal checked
 * lot by lot, LIFO by a posting sequence, and scrapping a failed QC lot.
 */
@ExtendWith(BooksGuard.class)
class StageIntegrityIT extends MfgTestSupport {

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

    String activeBom(String output, int outputQty, String component, int qty, String routingId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Integrity " + suffix(), "outputProductId", output,
                "outputQuantity", outputQty, "routingId", routingId, "lines", List.of(Map.of("componentProductId",
                        component, "quantity", qty))));
        String id = ok(201, "POST", "/manufacturing/boms", body).get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + id + "/activate", null);
        return id;
    }

    static Map<String, Object> stage(String opId, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationId", opId);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A confirmed multi-stage order. */
    JsonNode order(String bomId, int planned) {
        JsonNode created = ok(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", planned, "locationId", bakery, "outputLocationId", warehouse)));
        ok(201, "POST", "/manufacturing/production-orders/" + created.get("id").asString() + "/confirm", null);
        return created;
    }

    static String stageId(JsonNode view, int index) {
        return view.get("stages").get(index).get("id").asString();
    }

    List<Map<String, Object>> lines(String postingId, String role) {
        return jdbc.sql("SELECT quantity, value FROM \"ProductionStagePostingLine\" WHERE \"postingId\" = ? AND role = ?")
                .params(postingId, role).query().listOfRows();
    }

    BigDecimal lineValue(String postingId, String role) {
        return jdbc.sql("SELECT coalesce(sum(value), 0) FROM \"ProductionStagePostingLine\" WHERE \"postingId\" = ?"
                + " AND role = ?").params(postingId, role).query(BigDecimal.class).single();
    }

    String productLotTracked(String code) {
        String id = product(code);
        jdbc.sql("UPDATE \"Product\" SET \"batchTracking\" = true WHERE id = ?").param(id).update();
        return id;
    }

    void stockInLot(String loc, String productId, int qty, int unitCost, String lot) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", productId, "quantity", qty, "unitCost",
                        unitCost, "batchNumber", lot))));
    }

    BigDecimal lotQty(String productId, String loc, String lot) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"InventoryBatch\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ? AND \"batchNumber\" = ?").params(productId, loc, lot).query(BigDecimal.class)
                .single();
    }

    String quarantine() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'QC-QUARANTINE'"
                + " AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
    }

    long unbalancedEntries() {
        return jdbc.sql("SELECT count(*) FROM (SELECT je.id FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = je.id WHERE je.\"organizationId\" = ? AND je.\"sourceType\" IN"
                + " ('production_stage', 'production_stage_reversal') GROUP BY je.id HAVING sum(jl.debit) <>"
                + " sum(jl.credit)) x").param(demoOrg).query(Long.class).single();
    }

    /** Two stages: crush (intermediate into {@code stageLoc}) → mould; 10 rock at 5 make 10 crushed → 10 blocks. */
    record TwoStage(String orderId, String s1, String s2) {}

    TwoStage twoStage(String rock, String crushed, String block, String stageLoc, boolean gate) {
        String routingId = routing("Crush line", "Crush", "Mould");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "issueMode", "backflush", "outputProductId", crushed, "outputPerUnit", 1,
                        "stageLocationId", stageLoc, "materialProductIds", List.of(rock), "qcGate", gate),
                stage(ops.get(1), "issueMode", "backflush"))));
        JsonNode created = order(activeBom(block, 10, rock, 10, routingId), 10);
        return new TwoStage(created.get("id").asString(), stageId(created, 0), stageId(created, 1));
    }

    // ------------------------------------------------------------------ MS-1

    @Test
    @DisplayName("MS-1: stage 2 issues this order's intermediate at the value stage 1 put out, not the location average")
    void intermediateCarriesItsOwnCost() {
        String rock = product("IRK");
        String crushed = product("ICR");
        String block = product("IBL");
        String stageLoc = location("production");
        stockIn(bakery, rock, 10, 5);
        // Another order's crushed stone already sits in the stage location at a very different cost.
        stockIn(stageLoc, crushed, 100, 50);
        TwoStage o = twoStage(rock, crushed, block, stageLoc, false);

        String p1 = keyed("/manufacturing/production-stages/" + o.s1() + "/output", Map.of("qtyGood", 10, "complete",
                true)).get("posting").get("id").asString();
        JsonNode done = keyed("/manufacturing/production-stages/" + o.s2() + "/output", Map.of("qtyGood", 10, "complete",
                true));
        String p2 = done.get("posting").get("id").asString();
        assertThat(lineValue(p1, "main_output")).isEqualByComparingTo("50");
        assertThat(lineValue(p2, "intermediate_in")).as("WIP out of stage 1 = WIP into stage 2").isEqualByComparingTo("50");
        assertThat(num(done.get("outputUnitCost"))).isEqualByComparingTo("5");
        // The other order's stock keeps its quantity and its cost.
        assertThat(onHand(crushed, stageLoc)).isEqualByComparingTo("100");
        assertThat(jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(crushed, stageLoc).query(BigDecimal.class).single())
                .isCloseTo(new BigDecimal("50"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(unbalancedEntries()).isZero();
    }

    @Test
    @DisplayName("MS-1: a lot-tracked intermediate is drawn from this order's own lot, never another order's older lot")
    void intermediateDrawsItsOwnLot() {
        String rock = product("LRK");
        String crushed = productLotTracked("LCR");
        String block = product("LBL");
        String stageLoc = location("production");
        String other = "OTH-" + suffix();
        String own = "OWN-" + suffix();
        stockIn(bakery, rock, 10, 5);
        stockInLot(stageLoc, crushed, 100, 50, other);
        TwoStage o = twoStage(rock, crushed, block, stageLoc, false);

        keyed("/manufacturing/production-stages/" + o.s1() + "/output", Map.of("qtyGood", 10, "complete", true,
                "batchNumber", own));
        String p2 = keyed("/manufacturing/production-stages/" + o.s2() + "/output", Map.of("qtyGood", 10, "complete",
                true)).get("posting").get("id").asString();
        assertThat(lotQty(crushed, stageLoc, own)).isEqualByComparingTo("0");
        assertThat(lotQty(crushed, stageLoc, other)).isEqualByComparingTo("100");
        assertThat(lineValue(p2, "intermediate_in")).isEqualByComparingTo("50");
    }

    // ------------------------------------------------------------------ MS-2

    @Test
    @DisplayName("MS-2: fixed % is renormalised over the outputs a partial posting made; nothing is expensed as scrap")
    void fixedPctOverWhatWasMade() {
        String ore = product("FPORE");
        String main = product("FPMAIN");
        String co = product("FPCO");
        stockIn(bakery, ore, 100, 10);
        String routingId = routing("Split", "Split");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(stage(ops.get(
                0), "issueMode", "backflush", "materialProductIds", List.of(ore)))));
        String bomId = activeBom(main, 100, ore, 100, routingId);
        ok(200, "PUT", "/manufacturing/boms/" + bomId + "/stage-setup", Map.of("coProducts", List.of(Map.of(
                "productId", co, "role", "co_product", "quantity", 50, "fixedPct", 30)), "allocationRule", Map.of(
                        "method", "fixed_pct")));
        JsonNode created = order(bomId, 100);
        String s1 = stageId(created, 0);
        String coOut = null;
        for (JsonNode out : created.get("stages").get(0).get("outputs")) {
            if (co.equals(out.get("productId").asString())) {
                coOut = out.get("id").asString();
            }
        }
        BigDecimal scrap0 = balance(scrapAccount);

        // Only the main output made: it takes the whole joint cost (before: 30 % valued the co-product and was scrapped).
        JsonNode a = keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 40, "outputs",
                List.of(Map.of("outputId", coOut, "quantity", 0))));
        String pa = a.get("posting").get("id").asString();
        assertThat(lineValue(pa, "co_product")).isEqualByComparingTo("0");
        assertThat(lineValue(pa, "abnormal_scrap")).isEqualByComparingTo("0");
        assertThat(lineValue(pa, "main_output")).isEqualByComparingTo(num(a.get("yield").get("goodValue")));

        // Only the co-product made: it takes the whole joint cost (before: the main 70 % went to residue scrap).
        JsonNode b = keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 0, "outputs",
                List.of(Map.of("outputId", coOut, "quantity", 20))));
        String pb = b.get("posting").get("id").asString();
        assertThat(lineValue(pb, "abnormal_scrap")).isEqualByComparingTo("0");
        assertThat(lineValue(pb, "co_product")).isEqualByComparingTo(num(b.get("yield").get("goodValue")));
        assertThat(balance(scrapAccount)).isEqualByComparingTo(scrap0);

        JsonNode done = keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 60, "complete",
                true, "outputs", List.of(Map.of("outputId", coOut, "quantity", 30))));
        assertThat(num(done.get("stages").get(0).get("wipValue"))).isEqualByComparingTo("0");
        assertThat(unbalancedEntries()).isZero();
    }

    // ------------------------------------------------------------------ MS-4

    @Test
    @DisplayName("MS-4: reversing a stage output at standard offsets its standard-cost variance rows (ACR-13)")
    void reversedStageVarianceOffset() {
        String flour = product("SVFL");
        String cake = product("SVCK");
        stockIn(bakery, flour, 10, 5);
        JsonNode draft = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", cake, "effectiveFrom", java.time.LocalDate.now().toString(),
                "materialCost", 6, "convertMethod", true));
        ok(200, "POST", "/inventory/standard-costs/" + draft.get("id").asString() + "/approve", null);
        keyed("/inventory/standard-costs/" + draft.get("id").asString() + "/activate", null);
        String routingId = routing("Bake", "Bake");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(stage(ops.get(
                0), "issueMode", "backflush", "materialProductIds", List.of(flour)))));
        JsonNode created = order(activeBom(cake, 10, flour, 10, routingId), 10);
        String p = keyed("/manufacturing/production-stages/" + stageId(created, 0) + "/output", Map.of("qtyGood", 10))
                .get("posting").get("id").asString();
        String sum = "SELECT coalesce(sum(amount), 0) FROM \"StandardCostVariance\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?";
        assertThat(jdbc.sql(sum).params(demoOrg, cake).query(BigDecimal.class).single()).isEqualByComparingTo("-10");
        keyed("/manufacturing/stage-postings/" + p + "/reverse", Map.of("reason", "Wrong batch"));
        assertThat(jdbc.sql(sum).params(demoOrg, cake).query(BigDecimal.class).single()).as("offset on the reversal")
                .isZero();
        assertThat(unbalancedEntries()).isZero();
    }

    // ------------------------------------------------------------------ MS-5

    @Test
    @DisplayName("MS-5: a stage output whose lot has moved cannot be reversed, even with another lot on hand")
    void reversalChecksTheLot() {
        String flour = product("RLFL");
        String block = productLotTracked("RLBK");
        String other = "OTH-" + suffix();
        String own = "OWN-" + suffix();
        stockIn(bakery, flour, 10, 5);
        stockInLot(warehouse, block, 100, 7, other);
        String routingId = routing("Press", "Press");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(stage(ops.get(
                0), "issueMode", "backflush", "materialProductIds", List.of(flour)))));
        JsonNode created = order(activeBom(block, 10, flour, 10, routingId), 10);
        JsonNode out = keyed("/manufacturing/production-stages/" + stageId(created, 0) + "/output", Map.of("qtyGood", 10,
                "batchNumber", own));
        String p = out.get("posting").get("id").asString();
        String code = out.get("posting").get("code").asString();
        Map<String, Object> sale = new LinkedHashMap<>(Map.of("productId", block, "quantity", 3, "batchNumber", own,
                "distStrategy", "MANUAL"));
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", warehouse, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(sale)));

        Res refused = keyedRes("/manufacturing/stage-postings/" + p + "/reverse", Map.of("reason", "x"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).isEqualTo("Cannot reverse " + code
                + ": its output is no longer fully in stock (consumed, sold or moved).");
        assertThat(lotQty(block, warehouse, other)).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------ MS-6

    @Test
    @DisplayName("MS-6: LIFO follows the posting sequence, not createdAt / code text; conversion returns its posting")
    void lifoBySequence() {
        String flour = product("SQFL");
        String bread = product("SQBR");
        stockIn(bakery, flour, 10, 5);
        String routingId = routing("Seq", "Bake");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(stage(ops.get(
                0), "issueMode", "backflush", "materialProductIds", List.of(flour)))));
        JsonNode created = order(activeBom(bread, 10, flour, 10, routingId), 10);
        String s1 = stageId(created, 0);
        JsonNode c1 = keyed("/manufacturing/production-stages/" + s1 + "/conversion-costs", Map.of("amount", 10));
        String first = c1.get("posting").get("id").asString();
        assertThat(c1.get("posting").get("kind").asString()).isEqualTo("conversion");
        String second = keyed("/manufacturing/production-stages/" + s1 + "/conversion-costs", Map.of("amount", 20))
                .get("posting").get("id").asString();
        // The later posting carries an earlier createdAt (its transaction began first and waited on the order lock).
        jdbc.sql("UPDATE \"ProductionStagePosting\" SET \"createdAt\" = (SELECT \"createdAt\" - interval '1 second' FROM"
                + " \"ProductionStagePosting\" WHERE id = ?) WHERE id = ?").params(first, second).update();
        Res wrong = keyedRes("/manufacturing/stage-postings/" + first + "/reverse", Map.of());
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.body().get("message").asString()).startsWith("Reverse the later postings of this order first");
        keyed("/manufacturing/stage-postings/" + second + "/reverse", Map.of());
        keyed("/manufacturing/stage-postings/" + first + "/reverse", Map.of());
    }

    // ------------------------------------------------------------------ MS-8

    @Test
    @DisplayName("MS-8: a failed QC lot is scrapped from quarantine as waste; only failed held lots; reversible (LIFO)")
    void scrapFailedLot() {
        String sand = product("QSSD");
        String green = product("QSGR");
        String cured = product("QSCU");
        String curing = location("production");
        stockIn(bakery, sand, 20, 3);
        String routingId = routing("Scrap line", "Mould", "Cure");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "issueMode", "backflush", "outputProductId", green, "stageLocationId", curing,
                        "qcGate", true, "materialProductIds", List.of(sand)))));
        JsonNode created = order(activeBom(cured, 20, sand, 20, routingId), 20);
        String s1 = stageId(created, 0);
        String s2 = stageId(created, 1);
        JsonNode a = keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 10));
        String lotA = a.get("posting").get("id").asString();
        String codeA = a.get("posting").get("code").asString();
        JsonNode b = keyed("/manufacturing/production-stages/" + s1 + "/output", Map.of("qtyGood", 10, "complete", true));
        String lotB = b.get("posting").get("id").asString();
        String codeB = b.get("posting").get("code").asString();
        String q = quarantine();
        BigDecimal held0 = onHand(green, q);

        assertThat(call("POST", "/manufacturing/stage-postings/" + lotA + "/qc-scrap", Map.of("reason", "x"),
                adminToken).status()).as("Idempotency-Key required").isEqualTo(400);
        assertThat(keyedRes("/manufacturing/stage-postings/" + lotA + "/qc-scrap", Map.of()).status()).isEqualTo(400);
        assertThat(keyedRes("/manufacturing/stage-postings/" + lotB + "/qc-scrap", Map.of("reason", "Cracked")).body()
                .get("message").asString()).isEqualTo("Lot " + codeB
                        + " has not failed QC (pending); only a failed lot can be scrapped.");
        assertThat(keyedRes("/manufacturing/stage-postings/" + UUID.randomUUID() + "/qc-scrap", Map.of("reason",
                "x")).status()).isEqualTo(404);

        keyed("/manufacturing/production-stages/" + s1 + "/qc-decision", Map.of("result", "failed", "postingId", lotA));
        JsonNode scrapped = keyed("/manufacturing/stage-postings/" + lotA + "/qc-scrap", Map.of("reason", "Cracked"));
        assertThat(onHand(green, q)).isEqualByComparingTo(held0.subtract(BigDecimal.TEN));
        JsonNode st = scrapped.get("stages").get(0);
        assertThat(num(st.get("qcLots").get(0).get("qtyHeld"))).isEqualByComparingTo("0");
        assertThat(num(st.get("qtyQuarantined"))).isEqualByComparingTo("10");
        assertThat(st.get("qcStatus").asString()).isEqualTo("pending"); // lot B still waits
        String scrapId = jdbc.sql("SELECT id FROM \"ProductionStagePosting\" WHERE \"releasesPostingId\" = ? AND kind ="
                + " 'qc_scrap'").param(lotA).query(String.class).single();
        assertThat(lineValue(scrapId, "qc_scrap")).isEqualByComparingTo("30");
        assertThat(keyedRes("/manufacturing/stage-postings/" + lotA + "/qc-scrap", Map.of("reason", "again")).body()
                .get("message").asString()).isEqualTo("Lot " + codeA + " holds nothing in quarantine.");

        // The scrap is the latest posting: reversing it brings the lot back into quarantine, held and failed.
        keyed("/manufacturing/stage-postings/" + scrapId + "/reverse", Map.of("reason", "Wrong lot"));
        assertThat(onHand(green, q)).isEqualByComparingTo(held0);
        keyed("/manufacturing/stage-postings/" + lotA + "/qc-scrap", Map.of("reason", "Cracked"));

        JsonNode passed = keyed("/manufacturing/production-stages/" + s1 + "/qc-decision", Map.of("result", "passed",
                "postingId", lotB));
        assertThat(passed.get("stages").get(0).get("qcStatus").asString()).isEqualTo("passed");
        assertThat(onHand(green, curing)).isEqualByComparingTo("10");
        JsonNode done = keyed("/manufacturing/production-stages/" + s2 + "/output", Map.of("qtyGood", 10, "complete",
                true));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(num(done.get("outputUnitCost"))).isEqualByComparingTo("3");
        assertThat(unbalancedEntries()).isZero();
    }
}
