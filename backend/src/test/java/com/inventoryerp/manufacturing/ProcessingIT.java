package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.inventoryerp.BooksGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
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
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Multi-stage processing (ADR-S060) end to end on Testcontainers Postgres: the granite chain with exact cost, a late
 * landed cost following the goods, mixed units, many-to-many genealogy, reservations and races, reconciliation,
 * reversal, frozen values and history, plus the authorization and tenancy of the routes.
 */
@ExtendWith(BooksGuard.class)
class ProcessingIT extends MfgTestSupport {

    private static final String BASE = "/manufacturing/processing";
    String supplier;

    @BeforeAll
    void supplier() {
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(supplier, demoOrg, "PS-" + suffix(), "Stone " + suffix()).update();
    }

    // ------------------------------------------------------------------ fixtures

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

    String item(String name, boolean lots, String role, int salesPrice) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"batchTracking\", \"stockPolicy\", \"manufacturingRole\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, ?, ?, 'block', ?::\"ManufacturingRole\", now())")
                .params(id, demoOrg, "PS-" + suffix(), name + " " + suffix(), salesPrice, lots, role).update();
        return id;
    }

    String grn(String product, Object qty, Object cost, String lot) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("description", "Goods");
        line.put("quantity", qty);
        line.put("unitCost", cost);
        line.put("batchNumber", lot);
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "receivedAt", java.time.Instant.now().toString(), "lines", List.of(line))).get("id").asString();
    }

    static Map<String, Object> in(String productId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("role", "traceable");
        return m;
    }

    static Map<String, Object> out(String productId, String role, int pct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("role", role);
        m.put("expectedPct", pct);
        return m;
    }

    JsonNode template(int yieldPct, String method, List<Map<String, Object>> inputs, List<Map<String, Object>> outputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "T-" + suffix());
        body.put("name", "Step " + suffix());
        body.put("allocationMethod", method);
        body.put("yieldBasisUnit", "t");
        body.put("expectedYieldPct", yieldPct);
        body.put("defaultInputLocationId", warehouse);
        body.put("defaultOutputLocationId", warehouse);
        body.put("inputs", inputs);
        body.put("outputs", outputs);
        String id = ok(201, "POST", BASE + "/templates", body).get("id").asString();
        return ok(201, "POST", BASE + "/templates/" + id + "/activate", null);
    }

    JsonNode simple(int yieldPct, String from, String to) {
        return template(yieldPct, "market_value", List.of(in(from)), List.of(out(to, "main", yieldPct)));
    }

    static String firstId(JsonNode t, String field) {
        return t.get(field).get(0).get("id").asString();
    }

    Map<String, Object> job(JsonNode t, String lot, Object qty) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("templateInputId", firstId(t, "inputs"));
        input.put("batchNumber", lot);
        input.put("quantity", qty);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", t.get("id").asString());
        m.put("inputs", List.of(input));
        return m;
    }

    static Map<String, Object> outLine(String templateOutputId, Object qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateOutputId", templateOutputId);
        m.put("quantity", qty);
        return m;
    }

    /** Draft, start, cost and complete in one transaction. */
    JsonNode run(JsonNode t, String lot, Object qty, Object outQty, Object loss, Object cost) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("lines", List.of(outLine(firstId(t, "outputs"), outQty)));
        output.put("lossBasis", loss);
        output.put("complete", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", job(t, lot, qty));
        body.put("output", output);
        if (cost != null) {
            body.put("costs", List.of(Map.of("category", "machine", "amount", cost)));
        }
        return keyed(BASE + "/jobs/post", body);
    }

    String outLot(JsonNode job) {
        return job.get("outputs").get(0).get("batchNumber").asString();
    }

    void sell(String product, String lot, Object qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", product);
        line.put("quantity", qty);
        line.put("batchNumber", lot);
        line.put("distStrategy", "MANUAL");
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", warehouse, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line)));
    }

    BigDecimal lotCost(String product, String lot) {
        return jdbc.sql("SELECT \"unitCost\" FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"batchNumber\" = ?")
                .params(product, lot).query(BigDecimal.class).single();
    }

    BigDecimal lotQty(String product, String lot) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"batchNumber\" = ?")
                .params(product, lot).query(BigDecimal.class).single();
    }

    BigDecimal issuedValue(String product, String lot) {
        return jdbc.sql("SELECT coalesce(sum(abs(g.\"totalValue\")), 0) FROM \"InventoryLedger\" g JOIN \"InventoryBatch\" b ON"
                + " b.id = g.\"batchId\" WHERE g.\"productId\" = ? AND b.\"batchNumber\" = ? AND g.type = 'issue'")
                .params(product, lot).query(BigDecimal.class).single();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static org.assertj.core.data.Offset<BigDecimal> cents() {
        return within(new BigDecimal("0.01"));
    }

    // ------------------------------------------------------------------ the chain

    @Test
    @DisplayName("granite chain: every step has its own cost, and each stage can be sold or processed further")
    void goldenChain() {
        String raw = item("Raw", true, "raw", 30);
        String block = item("Block", true, "semi_finished", 55);
        String polished = item("Polished", true, "semi_finished", 100);
        String tile = item("Tile", true, "finished", 180);
        String lot = "GR-" + suffix();
        grn(raw, 100, 20, lot);

        JsonNode j1 = run(simple(80, raw, block), lot, 60, 48, 12, 500);
        assertThat(j1.get("status").asString()).isEqualTo("completed");
        assertThat(num(j1.get("wipValue"))).isEqualByComparingTo("0");
        String blockLot = outLot(j1);
        assertThat(blockLot).isEqualTo(lot + "-P1");
        assertThat(lotCost(block, blockLot)).isCloseTo(new BigDecimal("35.416667"), within(new BigDecimal("0.000001")));
        assertThat(lotQty(raw, lot)).isEqualByComparingTo("40");

        sell(block, blockLot, 20);
        assertThat(issuedValue(block, blockLot)).isCloseTo(new BigDecimal("708.33"), cents());

        JsonNode j2 = run(simple(90, block, polished), blockLot, 20, 18, 2, 400);
        String polishedLot = outLot(j2);
        assertThat(polishedLot).isEqualTo(lot + "-P2");
        assertThat(lotCost(polished, polishedLot)).isCloseTo(new BigDecimal("61.57"), cents());
        assertThat(lotQty(block, blockLot)).isEqualByComparingTo("8");

        JsonNode j3 = run(simple(90, polished, tile), polishedLot, 10, 9, 1, 450);
        assertThat(lotCost(tile, outLot(j3))).isCloseTo(new BigDecimal("118.42"), cents());
        assertThat(lotQty(polished, polishedLot)).isEqualByComparingTo("8");
        assertThat(num(j3.get("reconciliation").get("massYieldPct"))).isEqualByComparingTo("90");

        // Trace: the tile lot leads back to the source lot, and the source lot forward to the tile lot.
        JsonNode back = ok(200, "GET", BASE + "/genealogy?productId=" + tile + "&batchNumber=" + outLot(j3), null).get("root");
        JsonNode step = back.get("children").get(0).get("children").get(0).get("children").get(0);
        assertThat(step.get("batchNumber").asString()).isEqualTo(lot);
        JsonNode fwd = ok(200, "GET", BASE + "/genealogy?productId=" + raw + "&batchNumber=" + lot + "&direction=forward", null)
                .get("root");
        assertThat(fwd.get("children").get(0).get("batchNumber").asString()).isEqualTo(blockLot);
        JsonNode quality = ok(200, "GET", "/quality/traceability?productId=" + tile + "&batchNumber=" + outLot(j3) + "&depth=6", null);
        assertThat(quality.toString()).contains(lot);
    }

    @Test
    @DisplayName("late landed cost follows the goods: stock on hand, stock further on, and COGS for what was sold")
    void lateCostFollowsTheGoods() {
        String raw = item("Raw", true, "raw", 30);
        String block = item("Block", true, "semi_finished", 55);
        String polished = item("Polished", true, "finished", 100);
        String lot = "LC-" + suffix();
        String grnId = grn(raw, 100, 100, lot);
        JsonNode j1 = run(simple(80, raw, block), lot, 60, 48, 12, null);
        String blockLot = outLot(j1);
        sell(block, blockLot, 20);
        run(simple(100, block, polished), blockLot, 8, 8, 0, null);

        BigDecimal wipBefore = balance(wipAccount);
        BigDecimal cogsBefore = balance(mapped("cogs"));
        Map<String, Object> voucher = new LinkedHashMap<>();
        voucher.put("goodsReceiptIds", List.of(grnId));
        voucher.put("charges", List.of(Map.of("kind", "other", "amount", 2000)));
        Res created = call("POST", "/procurement/landed-cost-vouchers", voucher, key());
        assertThat(created.status()).as(created.raw().body()).isEqualTo(200);
        String id = created.body().get("id").asString();
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + id + "/submit", null);
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + id + "/approve", null);
        Res posted = call("POST", "/procurement/landed-cost-vouchers/" + id + "/post", null, key());
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);

        // Raw still on hand rises by $20/t; what the job consumed waits in WIP of its order.
        assertThat(lotCost(raw, lot)).isCloseTo(new BigDecimal("120"), cents());
        assertThat(balance(wipAccount).subtract(wipBefore)).isCloseTo(new BigDecimal("1200"), cents());
        assertThat(balance(mapped("cogs")).subtract(cogsBefore)).isCloseTo(BigDecimal.ZERO, cents());

        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        keyed("/costing/cost-adjustment-runs", Map.of("from", today, "to", today));

        // Of the $1,200: 48 t of blocks → 20 t sold ($500 to COGS), 8 t polished and 20 t on hand stay in inventory.
        assertThat(balance(wipAccount).subtract(wipBefore)).isCloseTo(BigDecimal.ZERO, cents());
        assertThat(balance(mapped("cogs")).subtract(cogsBefore)).isCloseTo(new BigDecimal("500"), cents());
        assertThat(ok(200, "GET", "/costing/cost-adjustment-runs/preview", null).get("lines")).isEmpty();
    }

    // ------------------------------------------------------------------ mixed units and genealogy

    @Test
    @DisplayName("mixed units: tonnes in, m² out; mass and commercial yield are separate; residual keeps its lot")
    void mixedUnits() {
        String block = item("Block", true, "raw", 0);
        String slab = item("Slab", true, "finished", 40);
        String offcut = item("Offcut", true, "semi_finished", 5);
        String lot = "MX-" + suffix();
        grn(block, 10, 100, lot);
        Map<String, Object> main = out(slab, "main", 80);
        main.put("basisFactor", "0.094118");
        JsonNode t = template(90, "weight", List.of(in(block)), List.of(main, out(offcut, "residual", 10)));

        Map<String, Object> slabLine = outLine(t.get("outputs").get(0).get("id").asString(), 85);
        slabLine.put("weight", 8);
        slabLine.put("grade", "A");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("lines", List.of(slabLine, outLine(t.get("outputs").get(1).get("id").asString(), 1)));
        output.put("lossBasis", 1);
        output.put("complete", true);
        JsonNode j = keyed(BASE + "/jobs/post", Map.of("job", job(t, lot, 10), "output", output));

        JsonNode rec = j.get("reconciliation");
        assertThat(num(rec.get("massYieldPct"))).isEqualByComparingTo("90");
        assertThat(num(rec.get("commercialYield"))).isEqualByComparingTo("8.5");
        assertThat(rec.get("estimated").asBoolean()).isFalse();
        // $1,000 by weight: 8 t and 1 t, so slab $888.89 over 85 m², offcut $111.11.
        assertThat(num(j.get("outputs").get(0).get("value"))).isCloseTo(new BigDecimal("888.89"), cents());
        assertThat(num(j.get("outputs").get(1).get("value"))).isCloseTo(new BigDecimal("111.11"), cents());
        assertThat(num(j.get("wipValue"))).isEqualByComparingTo("0");
        assertThat(lotQty(slab, outLot(j))).isEqualByComparingTo("85");
    }

    @Test
    @DisplayName("mixed units refuse quantity allocation; a template is immutable once active")
    void templateRules() {
        String a = item("A", true, "raw", 0);
        String b = item("B", true, "finished", 0);
        Map<String, Object> main = out(b, "main", 90);
        main.put("basisFactor", 2);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "Q-" + suffix());
        body.put("name", "Mixed");
        body.put("allocationMethod", "quantity");
        body.put("inputs", List.of(in(a)));
        body.put("outputs", List.of(main));
        String id = ok(201, "POST", BASE + "/templates", body).get("id").asString();
        Res refused = call("POST", BASE + "/templates/" + id + "/activate", null, Map.of("Authorization", "Bearer " + adminToken));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("weight or market value");

        JsonNode active = simple(100, a, b);
        Map<String, Object> edit = new LinkedHashMap<>(body);
        edit.put("code", active.get("code").asString());
        edit.put("allocationMethod", "weight");
        Res locked = call("PUT", BASE + "/templates/" + active.get("id").asString(), edit,
                Map.of("Authorization", "Bearer " + adminToken));
        assertThat(locked.status()).isEqualTo(400);
        assertThat(locked.raw().body()).contains("create a new version");

        JsonNode v2 = ok(201, "POST", BASE + "/templates/" + active.get("id").asString() + "/new-version", null);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        JsonNode activated = ok(201, "POST", BASE + "/templates/" + v2.get("id").asString() + "/activate", null);
        assertThat(activated.get("status").asString()).isEqualTo("active");
        assertThat(ok(200, "GET", BASE + "/templates/" + active.get("id").asString(), null).get("status").asString())
                .isEqualTo("archived");
    }

    @Test
    @DisplayName("genealogy: two lots in and two lots out link many-to-many; a consumable carries cost but no lot link")
    void genealogyManyToMany() {
        String stone = item("Stone", true, "raw", 0);
        String chem = item("Chemical", false, "raw", 0);
        String top = item("Top", true, "finished", 100);
        String side = item("Side", true, "semi_finished", 10);
        String lotA = "GA-" + suffix();
        String lotB = "GB-" + suffix();
        grn(stone, 10, 10, lotA);
        grn(stone, 30, 10, lotB);
        stockIn(warehouse, chem, 5, 8);
        Map<String, Object> consumable = in(chem);
        consumable.put("role", "consumable");
        JsonNode t = template(100, "market_value", List.of(in(stone), consumable), List.of(out(top, "main", 75),
                out(side, "by_product", 25)));
        String stoneLine = t.get("inputs").get(0).get("id").asString();
        String chemLine = t.get("inputs").get(1).get("id").asString();
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("templateId", t.get("id").asString());
        draft.put("inputs", List.of(Map.of("templateInputId", stoneLine, "batchNumber", lotA, "quantity", 10),
                Map.of("templateInputId", stoneLine, "batchNumber", lotB, "quantity", 30),
                Map.of("templateInputId", chemLine, "quantity", 5)));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("lines", List.of(outLine(t.get("outputs").get(0).get("id").asString(), 30),
                outLine(t.get("outputs").get(1).get("id").asString(), 10)));
        output.put("complete", true);
        JsonNode j = keyed(BASE + "/jobs/post", Map.of("job", draft, "output", output));

        JsonNode links = j.get("genealogy");
        assertThat(links).hasSize(4);
        BigDecimal toTopFromA = BigDecimal.ZERO;
        for (JsonNode l : links) {
            assertThat(l.get("sourceProductId").asString()).isEqualTo(stone);
            if (lotA.equals(l.get("sourceBatchNumber").asString()) && top.equals(l.get("outputProductId").asString())) {
                toTopFromA = num(l.get("attributedQty"));
                assertThat(num(l.get("sharePct"))).isEqualByComparingTo("25");
            }
        }
        assertThat(toTopFromA).isEqualByComparingTo("7.5");
        // $400 stone + $40 chemical shared by market value: top 3000 : side 100.
        BigDecimal total = num(j.get("outputs").get(0).get("value")).add(num(j.get("outputs").get(1).get("value")));
        assertThat(total).isCloseTo(new BigDecimal("440"), cents());
    }

    // ------------------------------------------------------------------ stock protection

    @Test
    @DisplayName("a draft reserves its input: other movements and jobs cannot take it; cancel releases it")
    void reservations() {
        String raw = item("Raw", true, "raw", 0);
        String done = item("Done", true, "finished", 10);
        String lot = "RS-" + suffix();
        grn(raw, 10, 5, lot);
        JsonNode t = simple(100, raw, done);
        String draft = keyed(BASE + "/jobs", job(t, lot, 8)).get("id").asString();

        Res sale = call("POST", "/inventory/direct-stock/out", Map.of("locationId", warehouse, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", raw, "quantity", 5))), bearer());
        assertThat(sale.status()).isEqualTo(400);
        assertThat(sale.raw().body()).contains("reserved for processing");
        Res second = keyedRes(BASE + "/jobs", job(t, lot, 5));
        assertThat(second.status()).isEqualTo(400);
        assertThat(second.raw().body()).contains("unreserved");

        JsonNode cancelled = ok(201, "POST", BASE + "/jobs/" + draft + "/cancel", Map.of("reason", "Plan changed"));
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        sell(raw, lot, 5);
        assertThat(lotQty(raw, lot)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("two jobs racing for the same stock: one wins, nothing is left half-posted")
    void raceForStock() throws Exception {
        String raw = item("Raw", true, "raw", 0);
        String done = item("Done", true, "finished", 10);
        String lot = "RC-" + suffix();
        grn(raw, 10, 5, lot);
        JsonNode t = simple(100, raw, done);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("lines", List.of(outLine(firstId(t, "outputs"), 8)));
                output.put("complete", true);
                return keyedRes(BASE + "/jobs/post", Map.of("job", job(t, lot, 8), "output", output)).status();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get());
        }
        pool.shutdown();
        assertThat(codes).containsAnyOf(200, 201).contains(400);
        assertThat(lotQty(raw, lot)).isEqualByComparingTo("2");
        assertThat(ok(200, "GET", BASE + "/jobs?templateId=" + t.get("id").asString(), null).get("data")).hasSize(1);
    }

    @Test
    @DisplayName("a short named lot is refused, not made up from another lot with no lot on the ledger")
    void namedLotRefused() {
        String raw = item("Raw", true, "raw", 0);
        String done = item("Done", true, "finished", 10);
        String a = "NA-" + suffix();
        String b = "NB-" + suffix();
        grn(raw, 2, 5, a);
        grn(raw, 20, 5, b);
        Res r = keyedRes(BASE + "/jobs", job(simple(100, raw, done), a, 5));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("lot " + a);
    }

    @Test
    @DisplayName("partial output keeps the rest in WIP at its cost; complete refuses an unaccounted remainder and an unexplained loss")
    void reconciliation() {
        String raw = item("Raw", true, "raw", 0);
        String done = item("Done", true, "finished", 10);
        String lot = "RE-" + suffix();
        grn(raw, 20, 10, lot);
        JsonNode t = simple(90, raw, done);
        String id = keyed(BASE + "/jobs", job(t, lot, 20)).get("id").asString();
        JsonNode started = keyed(BASE + "/jobs/" + id + "/start", null);
        assertThat(started.get("status").asString()).isEqualTo("in_progress");
        assertThat(num(started.get("wipValue"))).isCloseTo(new BigDecimal("200"), cents());

        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("lines", List.of(outLine(firstId(t, "outputs"), 8)));
        JsonNode after = keyed(BASE + "/jobs/" + id + "/output", partial);
        assertThat(after.get("status").asString()).isEqualTo("partially_output");
        assertThat(num(after.get("wipValue"))).isBetween(new BigDecimal("100"), new BigDecimal("130"));

        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("lines", List.of(outLine(firstId(t, "outputs"), 8)));
        complete.put("complete", true);
        Res unaccounted = keyedRes(BASE + "/jobs/" + id + "/output", complete);
        assertThat(unaccounted.status()).isEqualTo(400);
        assertThat(unaccounted.raw().body()).contains("not accounted for");

        complete.put("lossBasis", 4);
        Res noReason = keyedRes(BASE + "/jobs/" + id + "/output", complete);
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.raw().body()).contains("State the reason");

        complete.put("lossReason", "Cracked block");
        JsonNode closed = keyed(BASE + "/jobs/" + id + "/output", complete);
        assertThat(closed.get("status").asString()).isEqualTo("completed");
        assertThat(num(closed.get("wipValue"))).isEqualByComparingTo("0");
        JsonNode losses = closed.get("losses");
        assertThat(losses.toString()).contains("abnormal").contains("Cracked block");
    }

    @Test
    @DisplayName("reversal is refused while a lot has moved, even with other stock on hand, and names the document")
    void reversalChecksTheLot() {
        String raw = item("Raw", true, "raw", 0);
        String done = item("Done", true, "finished", 10);
        String lot = "RV-" + suffix();
        grn(raw, 11, 10, lot);
        grn(done, 50, 1, "OTHER-" + suffix());
        JsonNode t = simple(100, raw, done);
        JsonNode j = run(t, lot, 10, 10, 0, null);
        sell(done, outLot(j), 3);
        Res refused = keyedRes(BASE + "/jobs/" + j.get("id").asString() + "/reverse", Map.of("reason", "Wrong"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("no longer fully in stock").contains("direct_stock_out");

        JsonNode j2 = run(t, lot, 1, 1, 0, null);
        JsonNode reversed = keyed(BASE + "/jobs/" + j2.get("id").asString() + "/reverse", Map.of("reason", "Wrong"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(reversed.get("genealogy").get(0).get("reversedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("the same source record cannot be costed twice; a foreign cost needs a rate; a later price change does not move allocation")
    void costsAndFrozenValues() {
        String raw = item("Raw", true, "raw", 0);
        String main = item("Main", true, "finished", 100);
        String co = item("Co", true, "semi_finished", 100);
        String lot = "CO-" + suffix();
        grn(raw, 20, 10, lot);
        JsonNode t = template(100, "market_value", List.of(in(raw)), List.of(out(main, "main", 50), out(co, "co_product", 50)));
        String id = keyed(BASE + "/jobs", job(t, lot, 20)).get("id").asString();
        keyed(BASE + "/jobs/" + id + "/start", null);

        String account = mapped("overhead_absorbed");
        String sourceId = UUID.randomUUID().toString();
        Map<String, Object> cost = Map.of("category", "fuel", "amount", 100, "sourceType", "expense", "sourceId", sourceId,
                "creditAccountId", account);
        keyed(BASE + "/jobs/" + id + "/costs", cost);
        Res twice = keyedRes(BASE + "/jobs/" + id + "/costs", cost);
        assertThat(twice.status()).isEqualTo(409);
        Res noRate = keyedRes(BASE + "/jobs/" + id + "/costs", Map.of("category", "labour", "amount", 10, "currency", "QXA"));
        assertThat(noRate.status()).isEqualTo(400);
        assertThat(noRate.raw().body()).contains("exchange rate");

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("lines", List.of(outLine(t.get("outputs").get(0).get("id").asString(), 5),
                outLine(t.get("outputs").get(1).get("id").asString(), 5)));
        keyed(BASE + "/jobs/" + id + "/output", first);
        jdbc.sql("UPDATE \"Product\" SET \"salesPrice\" = 900 WHERE id = ?").param(main).update();
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("complete", true);
        JsonNode done = keyed(BASE + "/jobs/" + id + "/output", second);
        // Both postings split 50:50 on the frozen prices, whatever the price list says now.
        assertThat(num(done.get("outputs").get(0).get("value"))).isCloseTo(num(done.get("outputs").get(1).get("value")), cents());
        assertThat(num(done.get("outputs").get(0).get("value")).add(num(done.get("outputs").get(1).get("value"))))
                .isCloseTo(new BigDecimal("300"), cents());
    }

    // ------------------------------------------------------------------ access

    @Test
    @DisplayName("routes need their permission; another organisation sees nothing; stock routes need an Idempotency-Key")
    void access() {
        String raw = item("Raw", true, "raw", 0);
        String done = item("Done", true, "finished", 10);
        String lot = "AC-" + suffix();
        grn(raw, 10, 10, lot);
        JsonNode t = simple(100, raw, done);
        String jobId = run(t, lot, 2, 2, 0, null).get("id").asString();

        Res noKey = call("POST", BASE + "/jobs", job(t, lot, 1), bearer());
        assertThat(noKey.status()).isEqualTo(400);

        Map<String, String> other = Map.of("Authorization", "Bearer " + otherToken);
        assertThat(call("GET", BASE + "/jobs/" + jobId, null, other).status()).isEqualTo(404);
        assertThat(call("GET", BASE + "/templates/" + t.get("id").asString(), null, other).status()).isEqualTo(404);

        assertThat(assertAuthorizationMatrix(com.inventoryerp.manufacturing.web.ProcessingController.class)).isEqualTo(25);
        ok(200, "GET", BASE + "/dashboard", null);
        ok(200, "GET", BASE + "/stage-margin", null);
        assertThat(ok(200, "GET", BASE + "/lots?productId=" + done, null)).isNotEmpty();
    }

    @Test
    @DisplayName("sell at any stage: a sale resolves to its exact lot, customer and actual cost; only canBeSold stops a sale")
    void sellAtAnyStage() {
        String first = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse' AND"
                + " \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" ASC LIMIT 1").param(demoOrg)
                .query(String.class).single();
        String raw = item("Raw", true, "raw", 0);
        String semi = item("Semi", true, "semi_finished", 50);
        String lot = "SL-" + suffix();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", raw);
        line.put("description", "Goods");
        line.put("quantity", 10);
        line.put("unitCost", 10);
        line.put("batchNumber", lot);
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", first, "receivedAt",
                java.time.Instant.now().toString(), "lines", List.of(line)));
        JsonNode t = simple(100, raw, semi);
        Map<String, Object> draft = job(t, lot, 10);
        draft.put("locationId", first);
        draft.put("outputLocationId", first);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("lines", List.of(outLine(firstId(t, "outputs"), 10)));
        output.put("complete", true);
        String semiLot = outLot(keyed(BASE + "/jobs/post", Map.of("job", draft, "output", output)));

        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "PC-" + suffix(), "Buyer " + suffix()).update();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        Map<String, Object> invoice = Map.of("partnerId", customer, "issueDate", today, "lines", List.of(Map.of("productId", semi,
                "quantity", 2, "unitPrice", 50)));
        String id = ok(200, "POST", "/invoices", invoice).get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);

        JsonNode fwd = ok(200, "GET", BASE + "/genealogy?productId=" + semi + "&batchNumber=" + semiLot + "&direction=forward",
                null).get("root");
        JsonNode sale = fwd.get("sales").get(0);
        assertThat(sale.get("partnerName").asString()).startsWith("Buyer");
        assertThat(num(sale.get("quantity"))).isEqualByComparingTo("2");
        assertThat(num(sale.get("cost"))).isCloseTo(new BigDecimal("20"), cents());

        JsonNode margin = ok(200, "GET", BASE + "/stage-margin?from=" + today + "&to=" + today, null);
        JsonNode row = null;
        for (JsonNode r : margin) {
            if (semi.equals(r.get("productId").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(num(row.get("revenue"))).isCloseTo(new BigDecimal("100"), cents());
        assertThat(num(row.get("cogs"))).isCloseTo(new BigDecimal("20"), cents());
        assertThat(num(row.get("grossProfit"))).isCloseTo(new BigDecimal("80"), cents());

        jdbc.sql("UPDATE \"Product\" SET \"canBeSold\" = false WHERE id = ?").param(semi).update();
        Res refused = call("POST", "/invoices", invoice, bearer());
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("cannot be sold");
    }

    @Test
    @DisplayName("a residual can be the input product, with its own lot; a later job's use of an output blocks reversal; a step's BOM is locked")
    void residualLaterUseAndLockedRecipe() {
        String raw = item("Raw", true, "raw", 20);
        String good = item("Good", true, "finished", 100);
        String next = item("Next", true, "finished", 150);
        String lot = "RD-" + suffix();
        grn(raw, 10, 10, lot);
        JsonNode t = template(100, "market_value", List.of(in(raw)), List.of(out(good, "main", 70), out(raw, "residual", 30)));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("lines", List.of(outLine(t.get("outputs").get(0).get("id").asString(), 7), outLine(t.get("outputs").get(1).get("id").asString(), 3)));
        output.put("complete", true);
        JsonNode j = keyed(BASE + "/jobs/post", Map.of("job", job(t, lot, 10), "output", output));
        String residualLot = j.get("outputs").get(1).get("batchNumber").asString();
        assertThat(residualLot).isNotEqualTo(lot);
        assertThat(lotQty(raw, residualLot)).isEqualByComparingTo("3");
        assertThat(num(j.get("wipValue"))).isEqualByComparingTo("0");

        run(simple(100, good, next), j.get("outputs").get(0).get("batchNumber").asString(), 2, 2, 0, null);
        Res refused = keyedRes(BASE + "/jobs/" + j.get("id").asString() + "/reverse", Map.of("reason", "Wrong"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("production_stage");

        String bomId = ok(200, "GET", BASE + "/templates/" + t.get("id").asString(), null).get("bomId").asString();
        {
            Res locked = call("PUT", "/manufacturing/boms/" + bomId + "/stage-setup", Map.of("coProducts", List.of()), bearer());
            assertThat(locked.status()).isEqualTo(400);
            assertThat(locked.raw().body()).contains("processing step");
        }
    }

    @Test
    @DisplayName("a user can set up a lot-tracked product for processing through the product API")
    void productSetup() {
        String code = "PA-" + suffix();
        Res created = call("POST", "/products", Map.of("code", code, "name", "Api " + code, "productType", "stockable", "trackInventory", true,
                "batchTracking", true, "stockPolicy", "block", "manufacturingRole", "semi_finished", "canBeSold", false), bearer());
        assertThat(created.status()).as(created.raw().body()).isIn(200, 201);
        assertThat(created.body().get("stockPolicy").asString()).isEqualTo("block");
        assertThat(created.body().get("manufacturingRole").asString()).isEqualTo("semi_finished");
        assertThat(created.body().get("canBeSold").asBoolean()).isFalse();
        Res edited = call("PATCH", "/products/" + created.body().get("id").asString(), Map.of("canBeSold", true, "manufacturingRole", "finished"), bearer());
        assertThat(edited.status()).as(edited.raw().body()).isEqualTo(200);
        assertThat(edited.body().get("canBeSold").asBoolean()).isTrue();
        assertThat(edited.body().get("manufacturingRole").asString()).isEqualTo("finished");
    }

    @Test
    @DisplayName("a lot-tracked block item never goes short, even with negative stock allowed; an invoice ships from the lot's warehouse")
    void lotSaleNeverGoesShort() {
        String raw = item("Raw", true, "raw", 0);
        String semi = item("Semi", true, "semi_finished", 50);
        String lot = "NS-" + suffix();
        String elsewhere = location("warehouse");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", raw);
        line.put("description", "Goods");
        line.put("quantity", 10);
        line.put("unitCost", 10);
        line.put("batchNumber", lot);
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", elsewhere, "receivedAt",
                java.time.Instant.now().toString(), "lines", List.of(line)));
        JsonNode t = simple(100, raw, semi);
        Map<String, Object> draft = job(t, lot, 10);
        draft.put("locationId", elsewhere);
        draft.put("outputLocationId", elsewhere);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("lines", List.of(outLine(firstId(t, "outputs"), 10)));
        output.put("complete", true);
        String semiLot = outLot(keyed(BASE + "/jobs/post", Map.of("job", draft, "output", output)));

        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "PN-" + suffix(), "Buyer " + suffix()).update();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String ok = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines", List.of(Map.of("productId", semi,
                "quantity", 4, "unitPrice", 50)))).get("id").asString();
        ok(200, "POST", "/invoices/" + ok + "/post", null);
        assertThat(lotQty(semi, semiLot)).isEqualByComparingTo("6");
        assertThat(issuedValue(semi, semiLot)).isCloseTo(new BigDecimal("40"), cents());

        String tooMany = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines", List.of(Map.of("productId", semi,
                "quantity", 7, "unitPrice", 50)))).get("id").asString();
        Res refused = call("POST", "/invoices/" + tooMany + "/post", null, bearer());
        assertThat(refused.status()).as(refused.raw().body()).isGreaterThanOrEqualTo(400);
        assertThat(refused.raw().body()).contains("Insufficient stock");
        assertThat(lotQty(semi, semiLot)).isEqualByComparingTo("6");
        assertThat(jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(semi)
                .query(BigDecimal.class).single()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("a route is guidance: it follows a template's active version")
    void routes() {
        String a = item("A", true, "raw", 0);
        String b = item("B", true, "finished", 0);
        JsonNode t = simple(100, a, b);
        JsonNode route = ok(201, "POST", BASE + "/routes", Map.of("code", "RT-" + suffix(), "name", "Chain", "steps",
                List.of(Map.of("templateCode", t.get("code").asString()))));
        assertThat(route.get("steps").get(0).get("templateId").asString()).isEqualTo(t.get("id").asString());
        assertThat(ok(200, "GET", BASE + "/templates?inputProductId=" + a + "&status=active", null)).hasSize(1);
    }

    private Map<String, String> bearer() {
        return Map.of("Authorization", "Bearer " + adminToken);
    }
}
