package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.events.OutboxHandlers;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import tools.jackson.databind.JsonNode;

/**
 * Integrity fixes 2026-11-24 (manufacturing production readiness, MR-1 … MR-11): BOM effectivity on a named BOM,
 * frozen ECO content on every BOM / routing set-up route, process-period variance source, order locks on confirm /
 * rework / child orders / time logs, per-lot curing clock, reversed inspection lots, released intermediate layers and a
 * cancel whose reservation release fails. Each test failed (or could fail under the race) before its fix.
 */
class ManufacturingReadyIT extends MfgTestSupport {

    private static final String PO = "/manufacturing/production-orders/";
    private static final String PLM = "/manufacturing/plm";
    private static final String STAGES = "/manufacturing/production-stages/";

    @Autowired
    OutboxHandlers outboxHandlers;

    @Autowired
    ObjectProvider<FlywayConnectionDetails> ownerDetails;

    private String approverToken;

    // ------------------------------------------------------------------ helpers

    String approver() {
        if (approverToken == null) {
            String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id"
                    + " JOIN \"User\" u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1")
                    .query(String.class).single();
            String email = "mfg-ready-" + suffix() + "@demo.test";
            createUser(demoOrg, email, "Approver@12345", role);
            approverToken = login("DEMO", email, "Approver@12345");
        }
        return approverToken;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode keyed(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    static String msg(Res r) {
        return r.body().get("message").asString();
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
        ok(200, "GET", "/manufacturing/routings/" + routingId, null).get("operations")
                .forEach(o -> ids.add(o.get("id").asString()));
        return ids;
    }

    static Map<String, Object> stage(String opId, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operationId", opId);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A confirmed multi-stage order of {@code planned} on a BOM with {@code routingId}. */
    JsonNode multistage(String output, String component, int planned, String routingId) {
        String bomId = bom(output, component, planned, planned, Map.of("routingId", routingId));
        JsonNode created = ok(201, "POST", "/manufacturing/multistage-orders", Map.of("order", Map.of("bomId", bomId,
                "plannedQty", planned, "locationId", bakery, "outputLocationId", warehouse)));
        ok(201, "POST", PO + created.get("id").asString() + "/confirm", null);
        return created;
    }

    static String stageId(JsonNode view, int index) {
        return view.get("stages").get(index).get("id").asString();
    }

    String quarantine() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'QC-QUARANTINE'"
                + " AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
    }

    String qcStatus(String postingId) {
        return jdbc.sql("SELECT \"qcStatus\" FROM \"ProductionStagePosting\" WHERE id = ?").param(postingId)
                .query(String.class).single();
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

    /** DDL as the schema owner (external-DB runs connect as the owner already: their Flyway has no own URL). */
    void owner(String... statements) {
        FlywayConnectionDetails o = ownerDetails.getIfAvailable();
        if (o == null || o.getJdbcUrl() == null) {
            for (String sql : statements) {
                jdbc.sql(sql).update();
            }
            return;
        }
        try (Connection c = DriverManager.getConnection(o.getJdbcUrl(), o.getUsername(), o.getPassword());
                Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> List<T> race(int n, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return call.call();
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    // ------------------------------------------------------------------ MR-1

    @Test
    @DisplayName("MR-1: a named BOM must be effective on the order's date (today when none)")
    void namedBomEffectivity() {
        String flour = product("EFF-FL");
        String bread = product("EFF-BR");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String from = today.plusDays(10).toString();
        String bomId = bom(bread, flour, 10, 1, Map.of("effectiveFrom", from));
        String code = ok(200, "GET", "/manufacturing/boms/" + bomId, null).get("code").asString();

        Res refused = as("POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                bakery));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(msg(refused)).isEqualTo("BOM " + code + " is not effective on " + today + ".");

        // Scheduled inside the window: allowed.
        ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId", bakery,
                "scheduledFor", today.plusDays(12).toString()));
        // No effectivity at all: allowed as before.
        String open = bom(product("EFF-BR2"), flour, 10, 1, Map.of());
        ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", open, "runs", 1, "locationId", bakery));
    }

    // ------------------------------------------------------------------ MR-2

    @Test
    @DisplayName("MR-2: substitutes, co-products / allocation and routing stage set-up are frozen once the ECO is approved")
    void approvedChangeFreezesSetup() {
        String flour = product("FRZ-FL");
        String rye = product("FRZ-RY");
        String bran = product("FRZ-BN");
        String loaf = product("FRZ-LF");
        String bomId = bom(loaf, flour, 10, 2, Map.of());
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "stockDisposition",
                "none"));
        String newBom = eco.get("newBomId").asString();
        String newCode = ok(200, "GET", "/manufacturing/boms/" + newBom, null).get("code").asString();
        String lineId = ok(200, "GET", "/manufacturing/boms/" + newBom, null).get("lines").get(0).get("id").asString();
        Map<String, Object> subs = Map.of("substitutes", List.of(Map.of("productId", rye, "ratio", 1)));
        Map<String, Object> setup = Map.of("coProducts", List.of(Map.of("productId", bran, "role", "by_product",
                "quantity", 1, "valuation", "zero", "stocked", false)));

        // Draft change: still editable.
        ok(200, "PUT", "/manufacturing/boms/" + newBom + "/lines/" + lineId + "/substitutes", subs);
        ok(200, "PUT", "/manufacturing/boms/" + newBom + "/stage-setup", setup);

        Res approved = call("POST", "/manufacturing/engineering-changes/" + eco.get("id").asString() + "/approve",
                null, approver());
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(201);
        String frozen = "BOM " + newCode + " is part of approved change " + eco.get("number").asString()
                + "; it can no longer be edited.";
        Res s = as("PUT", "/manufacturing/boms/" + newBom + "/lines/" + lineId + "/substitutes", subs);
        assertThat(s.status()).isEqualTo(400);
        assertThat(msg(s)).isEqualTo(frozen);
        Res c = as("PUT", "/manufacturing/boms/" + newBom + "/stage-setup", Map.of("coProducts", List.of(),
                "allocationRule", Map.of("method", "quantity")));
        assertThat(c.status()).isEqualTo(400);
        assertThat(msg(c)).isEqualTo(frozen);

        // Routing change: the new routing's stage set-up follows the same rule.
        String routingId = routing("Frz line", "Mix");
        JsonNode r = ok(201, "POST", PLM + "/changes", Map.of("type", "routing", "routingId", routingId));
        String newRouting = r.get("newRoutingId").asString();
        String routingName = ok(200, "GET", "/manufacturing/routings/" + newRouting, null).get("name").asString();
        String op = opIds(newRouting).get(0);
        Map<String, Object> st = Map.of("stages", List.of(stage(op, "issueMode", "backflush", "qcGate", true)));
        ok(200, "PUT", "/manufacturing/routings/" + newRouting + "/stage-setup", st);
        String review = null;
        for (JsonNode n : ok(200, "GET", PLM + "/stages", null)) {
            if ("Engineering review".equals(n.get("name").asString())) {
                review = n.get("id").asString();
            }
        }
        ok(201, "POST", PLM + "/changes/" + r.get("id").asString() + "/stage", Map.of("stageId", review));
        Res decided = call("POST", PLM + "/changes/" + r.get("id").asString() + "/decisions", Map.of("decision",
                "approved"), approver());
        assertThat(decided.status()).as(decided.raw().body()).isEqualTo(201);
        assertThat(decided.body().get("status").asString()).isEqualTo("approved");
        Res rs = as("PUT", "/manufacturing/routings/" + newRouting + "/stage-setup", st);
        assertThat(rs.status()).isEqualTo(400);
        assertThat(msg(rs)).isEqualTo("Routing " + routingName + " is part of approved change "
                + r.get("number").asString() + "; it can no longer be edited.");
    }

    // ------------------------------------------------------------------ MR-3

    @Test
    @DisplayName("MR-3: a process period's standard-cost variance is registered against the period (process_costing)")
    void processPeriodVarianceSource() {
        String ore = product("PCV-OR");
        String brick = product("PCV-BR");
        stockIn(bakery, ore, 100, 10);
        JsonNode draft = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", brick, "effectiveFrom",
                LocalDate.now().toString(), "materialCost", 5, "convertMethod", true));
        ok(200, "POST", "/inventory/standard-costs/" + draft.get("id").asString() + "/approve", null);
        keyed("/inventory/standard-costs/" + draft.get("id").asString() + "/activate", null);
        String proc = ok(201, "POST", "/manufacturing/process-costing/processes", Map.of("code", "KILN-" + suffix(),
                "name", "Kiln", "sequence", 1, "outputProductId", brick, "outputLocationId", warehouse)).get("id")
                .asString();
        String period = ok(201, "POST", "/manufacturing/process-costing/processes/" + proc + "/periods", Map.of(
                "periodFrom", LocalDate.now().withDayOfMonth(1).toString(), "periodTo", LocalDate.now().toString()))
                .get("id").asString();
        keyed("/manufacturing/process-costing/periods/" + period + "/materials", Map.of("productId", ore,
                "locationId", bakery, "quantity", 100));
        ok(200, "PATCH", "/manufacturing/process-costing/periods/" + period + "/units", Map.of("unitsStarted", 100,
                "unitsCompleted", 100));
        keyed("/manufacturing/process-costing/periods/" + period + "/close", null);

        List<Map<String, Object>> rows = jdbc.sql("SELECT \"sourceType\", \"sourceId\", amount FROM"
                + " \"StandardCostVariance\" WHERE \"organizationId\" = ? AND \"productId\" = ?").params(demoOrg, brick)
                .query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("sourceType")).isEqualTo("process_costing");
        assertThat(rows.getFirst().get("sourceId")).isEqualTo(period);
        assertThat((BigDecimal) rows.getFirst().get("amount")).isEqualByComparingTo("500");
    }

    // ------------------------------------------------------------------ MR-4

    @Test
    @DisplayName("MR-4: confirm racing cancel never brings a cancelled order back to confirmed")
    void confirmRacingCancel() throws Exception {
        String flour = product("CRC-FL");
        String roll = product("CRC-RL");
        stockIn(bakery, flour, 50, 10);
        String bomId = bom(roll, flour, 10, 2, Map.of());
        for (int i = 0; i < 5; i++) {
            String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                    "locationId", bakery)).get("id").asString();
            int[] turn = {0};
            List<int[]> res = race(2, () -> {
                boolean first;
                synchronized (turn) {
                    first = turn[0]++ == 0;
                }
                return first ? new int[] {0, as("POST", PO + id + "/confirm", null).status()}
                        : new int[] {1, as("POST", PO + id + "/cancel", Map.of("reason", "race")).status()};
            });
            int cancel = res.stream().filter(r -> r[0] == 1).findFirst().orElseThrow()[1];
            assertThat(cancel).isEqualTo(201);
            assertThat(ok(200, "GET", PO + id, null).get("status").asString()).as("order %s", id)
                    .isEqualTo("cancelled");
            assertThat(jdbc.sql("SELECT count(*) FROM \"StockReservation\" WHERE \"sourceId\" = ? AND status ="
                    + " 'active'").param(id).query(Long.class).single()).isZero();
        }
    }

    @Test
    @DisplayName("MR-4: a double-tapped rework opens one rework order")
    void reworkOnce() throws Exception {
        String flour = product("RWK-FL");
        String cake = product("RWK-CK");
        stockIn(bakery, flour, 40, 100);
        String bomId = bom(cake, flour, 20, 4, Map.of("qcRequired", true));
        String id = started(bomId).get("id").asString();
        ok(201, "POST", PO + id + "/complete", Map.of("qtyProduced", 20));
        ok(201, "POST", PO + id + "/qc", Map.of("passedQty", 12, "reworkQty", 8));
        List<Integer> statuses = race(2, () -> as("POST", PO + id + "/rework", Map.of()).status());
        assertThat(statuses).containsExactlyInAnyOrder(201, 400);
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductionOrder\" WHERE \"parentOrderId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("MR-4: concurrent child-order explosions create each child once")
    void childOrdersOnce() throws Exception {
        String raw = product("CHL-RW");
        String semi = product("CHL-SM");
        String top = product("CHL-TP");
        bom(semi, raw, 1, 1, Map.of());
        String bomId = bom(top, semi, 1, 1, Map.of());
        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 5,
                "locationId", bakery)).get("id").asString();
        race(2, () -> as("POST", PO + id + "/child-orders", Map.of("initializeStages", false)).status());
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductionOrderPeg\" WHERE \"parentOrderId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductionOrder\" WHERE \"parentOrderId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("MR-4: a time log racing the work-order complete never changes the minutes already costed")
    void timeLogRacingComplete() throws Exception {
        String wcId = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "WC-" + suffix(), "name", "Oven"))
                .get("id").asString();
        String res = ok(201, "POST", "/manufacturing/resources", Map.of("name", "Baker", "kind", "labour",
                "workCenterId", wcId, "costPerHour", 120, "capacityMinsPerDay", 480)).get("id").asString();
        String flour = product("TLR-FL");
        String bun = product("TLR-BN");
        stockIn(bakery, flour, 50, 100);
        String bomId = bom(bun, flour, 10, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Bun line " + suffix(), "bomId", bomId,
                "operations", List.of(Map.of("sequence", 1, "name", "Bake", "workCenterId", wcId, "durationMins", 30))));
        for (int i = 0; i < 3; i++) {
            String orderId = started(bomId).get("id").asString();
            String woId = ok(201, "POST", PO + orderId + "/work-orders/generate", null).get(0).get("id").asString();
            ok(200, "PATCH", "/manufacturing/work-orders/" + woId + "/assign", Map.of("resourceId", res));
            ok(201, "POST", "/manufacturing/work-orders/" + woId + "/start", null);
            int[] turn = {0};
            race(2, () -> {
                boolean first;
                synchronized (turn) {
                    first = turn[0]++ == 0;
                }
                return first ? as("POST", "/manufacturing/work-orders/" + woId + "/complete", Map.of("labourMins", 30))
                        .status() : as("POST", "/manufacturing/work-orders/" + woId + "/time-logs", Map.of("minutes",
                                15)).status();
            });
            assertThat(jdbc.sql("SELECT \"labourMins\" FROM \"WorkOrder\" WHERE id = ?").param(woId)
                    .query(Integer.class).single()).as("work order %s keeps the costed minutes", woId).isEqualTo(30);
            assertThat(ok(200, "GET", PO + orderId, null).get("overheadCost").asString()).isEqualTo("60");
        }
    }

    // ------------------------------------------------------------------ MR-6

    @Test
    @DisplayName("MR-6: the curing clock runs per lot — an older lot releases on its own due date while output continues")
    void curingClockPerLot() {
        String sand = product("CUR-SD");
        String green = product("CUR-GR");
        String block = product("CUR-BL");
        String yard = location("production");
        stockIn(bakery, sand, 20, 3);
        String routingId = routing("Curing line", "Mould", "Cube");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "issueMode", "backflush", "outputProductId", green, "stageLocationId", yard,
                        "qcGate", true, "minCuringDays", 7, "materialProductIds", List.of(sand)))));
        JsonNode created = multistage(block, sand, 20, routingId);
        String s1 = stageId(created, 0);
        String lotA = keyed(STAGES + s1 + "/output", Map.of("qtyGood", 10, "date",
                LocalDate.now(ZoneOffset.UTC).minusDays(8).toString())).get("posting").get("id").asString();
        String lotB = keyed(STAGES + s1 + "/output", Map.of("qtyGood", 10, "complete", true)).get("posting").get("id")
                .asString();

        keyed(STAGES + s1 + "/qc-decision", Map.of("result", "passed"));
        assertThat(qcStatus(lotA)).as("cured 8 of 7 days").isEqualTo("passed");
        assertThat(qcStatus(lotB)).as("pressed today").isEqualTo("curing");
        assertThat(onHand(green, yard)).isEqualByComparingTo("10");
        // Lot B keeps its own due date (today + 7), not a later one.
        java.time.LocalDateTime due = jdbc.sql("SELECT \"qcPassDueAt\" FROM \"ProductionStagePosting\" WHERE id = ?")
                .param(lotB).query(java.time.LocalDateTime.class).single();
        assertThat(due.toLocalDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(7));
    }

    // ------------------------------------------------------------------ MR-7

    @Test
    @DisplayName("MR-7: a quality decision on the lot of a reversed output decides nothing (not every undecided lot)")
    void reversedInspectionLotDecidesNothing() {
        String sand = product("RIL-SD");
        String green = product("RIL-GR");
        String block = product("RIL-BL");
        String yard = location("production");
        stockIn(bakery, sand, 20, 3);
        String routingId = routing("Inspect line", "Mould", "Cube");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "issueMode", "backflush", "outputProductId", green, "stageLocationId", yard,
                        "qcGate", true, "materialProductIds", List.of(sand)))));
        JsonNode created = multistage(block, sand, 20, routingId);
        String s1 = stageId(created, 0);
        String l1 = UUID.randomUUID().toString();
        String l2 = UUID.randomUUID().toString();
        String a = keyed(STAGES + s1 + "/output", Map.of("qtyGood", 10)).get("posting").get("id").asString();
        jdbc.sql("UPDATE \"ProductionStagePosting\" SET \"qcLotId\" = ? WHERE id = ?").params(l1, a).update();
        keyed("/manufacturing/stage-postings/" + a + "/reverse", Map.of("reason", "wrong mix"));
        String b = keyed(STAGES + s1 + "/output", Map.of("qtyGood", 10)).get("posting").get("id").asString();
        jdbc.sql("UPDATE \"ProductionStagePosting\" SET \"qcLotId\" = ? WHERE id = ?").params(l2, b).update();

        Map<String, Object> payload = new HashMap<>(Map.of("organizationId", demoOrg, "sourceLineId", s1,
                "sourceType", "production_order", "stockControl", "source", "decision", "release", "status", "closed",
                "lotId", l1, "lotNumber", "QL-" + suffix()));
        outboxHandlers.dispatch("quality.lot.decided", mapper.valueToTree(payload));
        assertThat(qcStatus(b)).as("output B was never inspected").isEqualTo("pending");
        assertThat(onHand(green, yard)).isEqualByComparingTo("0");

        payload.put("lotId", l2);
        outboxHandlers.dispatch("quality.lot.decided", mapper.valueToTree(payload));
        assertThat(qcStatus(b)).isEqualTo("passed");
        assertThat(onHand(green, yard)).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ MR-8

    @Test
    @DisplayName("MR-8: the next stage issues a released (QC-gated) intermediate at its own cost, not the location average")
    void releasedIntermediateCarriesItsCost() {
        String rock = product("RIC-RK");
        String crushed = product("RIC-CR");
        String block = product("RIC-BL");
        String yard = location("production");
        stockIn(bakery, rock, 10, 3);
        stockIn(yard, crushed, 100, 50); // another order's stock at a very different cost
        String routingId = routing("Gated line", "Crush", "Mould");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "issueMode", "backflush", "outputProductId", crushed, "stageLocationId", yard,
                        "qcGate", true, "materialProductIds", List.of(rock)),
                stage(ops.get(1), "issueMode", "backflush"))));
        JsonNode created = multistage(block, rock, 10, routingId);
        String s1 = stageId(created, 0);
        String s2 = stageId(created, 1);
        keyed(STAGES + s1 + "/output", Map.of("qtyGood", 10, "complete", true));
        keyed(STAGES + s1 + "/qc-decision", Map.of("result", "passed"));
        String p2 = keyed(STAGES + s2 + "/output", Map.of("qtyGood", 10, "complete", true)).get("posting").get("id")
                .asString();
        assertThat(lineValue(p2, "intermediate_in")).as("10 × 3, as released").isEqualByComparingTo("30");
        assertThat(onHand(crushed, yard)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("MR-8: lot-tracked — a reversed next-stage issue, then a re-issue, draws the released own lot")
    void releasedLotReissuedAfterReversal() {
        String rock = product("RLR-RK");
        String crushed = productLotTracked("RLR-CR");
        String block = product("RLR-BL");
        String yard = location("production");
        String own = "OWN-" + suffix();
        String other = "OTH-" + suffix();
        stockIn(bakery, rock, 10, 3);
        stockInLot(yard, crushed, 100, 50, other);
        String routingId = routing("Gated lot line", "Crush", "Mould");
        List<String> ops = opIds(routingId);
        ok(200, "PUT", "/manufacturing/routings/" + routingId + "/stage-setup", Map.of("stages", List.of(
                stage(ops.get(0), "issueMode", "backflush", "outputProductId", crushed, "stageLocationId", yard,
                        "qcGate", true, "materialProductIds", List.of(rock)),
                stage(ops.get(1), "issueMode", "backflush"))));
        JsonNode created = multistage(block, rock, 10, routingId);
        String s1 = stageId(created, 0);
        String s2 = stageId(created, 1);
        keyed(STAGES + s1 + "/output", Map.of("qtyGood", 10, "complete", true, "batchNumber", own));
        keyed(STAGES + s1 + "/qc-decision", Map.of("result", "passed"));
        assertThat(lotQty(crushed, yard, own)).isEqualByComparingTo("10");
        String partial = keyed(STAGES + s2 + "/output", Map.of("qtyGood", 4)).get("posting").get("id").asString();
        assertThat(lotQty(crushed, yard, other)).as("first issue from the own lot").isEqualByComparingTo("100");
        keyed("/manufacturing/stage-postings/" + partial + "/reverse", Map.of("reason", "miscount"));
        String p2 = keyed(STAGES + s2 + "/output", Map.of("qtyGood", 10, "complete", true)).get("posting").get("id")
                .asString();
        assertThat(lineValue(p2, "intermediate_in")).isEqualByComparingTo("30");
        assertThat(lotQty(crushed, yard, own)).isEqualByComparingTo("0");
        assertThat(lotQty(crushed, yard, other)).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------ MR-9

    @Test
    @DisplayName("MR-9: a cancel whose reservation release fails still commits (the failure stays soft)")
    void cancelSurvivesReleaseFailure() {
        String flour = product("CRF-FL");
        String roll = product("CRF-RL");
        stockIn(bakery, flour, 20, 10);
        String bomId = bom(roll, flour, 10, 2, Map.of());
        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery)).get("id").asString();
        ok(201, "POST", PO + id + "/confirm", null);
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockReservation\" WHERE \"sourceId\" = ? AND status = 'active'")
                .param(id).query(Long.class).single()).isPositive();
        String trigger = "mfg_ready_fault_" + suffix().replace("-", "");
        owner("CREATE OR REPLACE FUNCTION mfg_ready_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " IF NEW.\"sourceId\" = TG_ARGV[0] THEN RAISE EXCEPTION 'MFG: injected release failure'; END IF;"
                        + " RETURN NEW; END $$",
                "CREATE TRIGGER " + trigger + " BEFORE UPDATE ON \"StockReservation\" FOR EACH ROW EXECUTE FUNCTION"
                        + " mfg_ready_fault('" + id + "')");
        try {
            Res cancelled = as("POST", PO + id + "/cancel", Map.of("reason", "not needed"));
            assertThat(cancelled.status()).as(cancelled.raw().body()).isEqualTo(201);
            assertThat(ok(200, "GET", PO + id, null).get("status").asString()).isEqualTo("cancelled");
        } finally {
            owner("DROP TRIGGER IF EXISTS " + trigger + " ON \"StockReservation\"");
        }
    }
}
