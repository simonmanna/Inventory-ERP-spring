package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.LayerCostWriter;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.rental.application.RentalLocationConfigService;
import com.inventoryerp.rental.application.RentalPostingService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Hardening R5b (inventory review 2026-11-22, P2-1 / P2-2 / P2-3 / P2-7 / P2-11, gates G3 / G4): the engine lock
 * order (count submit and landed cost take the costing locks before the quant locks, reservations take the quant's
 * advisory lock before its row), lots and serials (transfer and production reversals bring back their own lots, count
 * lines post against the counted lot, a serial number received twice at once is refused), values (serial and STANDARD
 * transfers, the AVCO residue at zero), rental units moving their variant and serial, the ledger audit trail and the
 * cost fields behind {@code inventory_valuation:read}. One isolated organisation for the class; the integrity report
 * of that organisation is green at the end of every scenario that runs in it.
 */
class InventoryEngineHardeningIT extends InventoryAuditSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    LayerCostWriter layerCostWriter;

    @Autowired
    RentalPostingService rentalPosting;

    @Autowired
    RentalLocationConfigService rentalLocations;

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ helpers

    static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    static boolean bool(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    String quantKey(String productId, String loc) {
        return "avco:" + org + ":" + productId + "::" + loc;
    }

    String costingKey(String productId) {
        return productId == null ? "costing:" + org : "costing:" + org + ":" + productId;
    }

    /** Wait until some backend waits on a lock; fails when {@code request} finished first. */
    void awaitLockWait(CompletableFuture<?> request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (request.isDone()) {
                throw new AssertionError("the request finished without waiting: " + request.join());
            }
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND pid <> pg_backend_pid() AND query ~ 'pg_advisory'").query(Long.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no request waited on an advisory lock");
    }

    /** True when another session could take the quant's advisory lock right now (nobody holds it). */
    boolean quantLockFree(String productId, String loc) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                return bool(c, "SELECT pg_try_advisory_xact_lock(hashtext(?)::bigint)", quantKey(productId, loc));
            } finally {
                c.rollback();
            }
        }
    }

    BigDecimal lotQty(String productId, String loc, String batchNumber) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"locationId\" = ? AND \"batchNumber\" = ? AND \"isActive\" = true")
                .params(org, productId, loc, batchNumber).query(BigDecimal.class).single();
    }

    BigDecimal lotRowQty(String batchId) {
        return jdbc.sql("SELECT quantity FROM \"InventoryBatch\" WHERE id = ?").param(batchId).query(BigDecimal.class)
                .single();
    }

    String lotId(String productId, String loc, String batchNumber, BigDecimal unitCost) {
        return jdbc.sql("SELECT id FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " \"locationId\" = ? AND \"batchNumber\" = ? AND \"unitCost\" = ?")
                .params(org, productId, loc, batchNumber, unitCost).query(String.class).single();
    }

    String transfer(String from, String to, String productId, Object qty) {
        String id = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", from, "toLocationId", to,
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId",
                        productId, "qtyRequested", qty)))).get("id").asString();
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        return id;
    }

    Map<String, Object> ledgerRow(String productId, String loc, String type) {
        return jdbc.sql("SELECT * FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " \"locationId\" = ? AND type::text = ? ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(org, productId, loc, type).query().singleRow();
    }

    BigDecimal signedLedgerValue(String productId, String loc) {
        return jdbc.sql("SELECT COALESCE(SUM(CASE WHEN \"quantityChange\" >= 0 THEN COALESCE(\"totalValue\", 0)"
                        + " ELSE -COALESCE(\"totalValue\", 0) END), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"locationId\" = ?")
                .params(org, productId, loc).query(BigDecimal.class).single();
    }

    String keyedApi(String method, String path, Object body, int... statuses) {
        Res r = call(method, path, body, Map.of("Authorization", "Bearer " + auditToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isIn(toList(statuses));
        return r.body() == null || r.body().get("id") == null ? null : r.body().get("id").asString();
    }

    static List<Integer> toList(int... values) {
        List<Integer> out = new ArrayList<>();
        for (int v : values) {
            out.add(v);
        }
        return out;
    }

    String readerToken(List<String> permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, permissions, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())").params(role, org, "Reader " + sfx(), permissions.toArray(String[]::new)).update();
        String email = "reader-" + sfx() + "@audit.test";
        createUser(org, email, "Reader@12345", role);
        return login(orgCode, email, "Reader@12345");
    }

    /** G1 / G2 evidence: every check of the organisation's integrity report passes. */
    void assertIntegrityGreen() {
        JsonNode report = okA(200, "GET", "/inventory/reports/integrity", null);
        List<String> failing = new ArrayList<>();
        for (JsonNode c : report.get("checks")) {
            if (!c.get("ok").asBoolean()) {
                failing.add(c.get("key").asString() + " " + c.get("rows"));
            }
        }
        assertThat(failing).as("integrity checks").isEmpty();
    }

    /** A count session over one product at main: {@code counts} maps a line predicate (by lot id) to its count. */
    JsonNode startCount(String productId) {
        return okA(201, "POST", "/inventory/counts/start", Map.of("locationId", main, "countType", "spot",
                "scopeProductIds", List.of(productId)));
    }

    // ------------------------------------------------------------------ P2-1 lock order

    @Test
    @DisplayName("P2-1: count submit takes the costing lock before its quant locks (no cycle with standard activation)")
    void countSubmitTakesCostingLockFirst() throws Exception {
        String p = product("CNT-STD");
        receive(p, 10, 5);
        JsonNode session = startCount(p);
        String id = session.get("id").asString();
        okA(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId",
                session.get("lines").get(0).get("id").asString(), "countedQty", 8, "reason", "breakage"))));
        CompletableFuture<Res> submit;
        try (Connection activation = dataSource.getConnection()) {
            activation.setAutoCommit(false);
            try {
                // What a standard cost activation of the product holds while it re-costs: the product key exclusively.
                exec(activation, "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", costingKey(p));
                submit = CompletableFuture.supplyAsync(() -> api("POST", "/inventory/counts/" + id + "/submit",
                        Map.of()));
                awaitLockWait(submit);
                // The submit waits for the costing lock without holding the quant: the activation could take it.
                assertThat(quantLockFree(p, main)).as("quant lock free while the submit waits").isTrue();
            } finally {
                activation.commit();
            }
        }
        Res done = submit.get(30, TimeUnit.SECONDS);
        assertThat(done.status()).as(done.raw().body()).isEqualTo(200);
        assertThat(onHand(p, main)).isEqualByComparingTo("8");

        // And for real: a count submit and a standard activation of the same product at once both complete.
        String q = product("CNT-STD2");
        receive(q, 10, 5);
        JsonNode s2 = startCount(q);
        String id2 = s2.get("id").asString();
        okA(200, "PATCH", "/inventory/counts/" + id2 + "/draft", Map.of("lines", List.of(Map.of("lineId",
                s2.get("lines").get(0).get("id").asString(), "countedQty", 7, "reason", "breakage"))));
        String std = okA(201, "POST", "/inventory/standard-costs", Map.of("productId", q, "effectiveFrom",
                asOrg(() -> java.time.LocalDate.now(com.inventoryerp.kernel.common.BusinessZone.get()).toString()),
                "materialCost", 6, "convertMethod", true)).get("id").asString();
        okA(200, "POST", "/inventory/standard-costs/" + std + "/approve", null);
        List<Callable<Integer>> tasks = List.of(
                () -> api("POST", "/inventory/counts/" + id2 + "/submit", Map.of()).status(),
                () -> call("POST", "/inventory/standard-costs/" + std + "/activate", null, Map.of("Authorization",
                        "Bearer " + auditToken, "Idempotency-Key", UUID.randomUUID().toString())).status());
        List<Integer> statuses = parallel(tasks);
        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(200, 201);
        assertThat(onHand(q, main)).isEqualByComparingTo("7");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("P2-1: a reservation takes the quant's advisory lock before its StockItem row")
    void reservationTakesQuantLockFirst() throws Exception {
        String p = product("RSV-LOCK");
        receive(p, 10, 2);
        CompletableFuture<Res> reserve;
        try (Connection move = dataSource.getConnection()) {
            move.setAutoCommit(false);
            try {
                // A stock move holding the quant (advisory half), about to lock the row.
                exec(move, "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", quantKey(p, main));
                reserve = CompletableFuture.supplyAsync(() -> api("POST", "/inventory/reservations", Map.of("productId",
                        p, "locationId", main, "quantity", 2, "sourceType", "r5b_test", "sourceId", "R-" + sfx())));
                awaitLockWait(reserve);
                // The reservation waits without holding the row, so the move can lock it.
                exec(move, "SET LOCAL lock_timeout = '2s'");
                exec(move, "SELECT id FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " \"locationId\" = ? FOR UPDATE", org, p, main);
            } finally {
                move.commit();
            }
        }
        Res done = reserve.get(30, TimeUnit.SECONDS);
        assertThat(done.status()).as(done.raw().body()).isEqualTo(201);

        // Opposite-order reservations and moves over two products in parallel: no deadlock.
        String a = product("RSV-A");
        String b = product("RSV-B");
        receive(a, 50, 1);
        receive(b, 50, 1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String first = i % 2 == 0 ? a : b;
            String second = i % 2 == 0 ? b : a;
            String src = "R-" + sfx();
            tasks.add(() -> api("POST", "/inventory/reservations", Map.of("productId", first, "locationId", main,
                    "quantity", 1, "sourceType", "r5b_test", "sourceId", src)).status());
            tasks.add(() -> api("POST", "/inventory/direct-stock/out", direct(main, List.of(item(second, 1, null),
                    item(first, 1, null)))).status());
        }
        assertThat(parallel(tasks)).allMatch(s -> s == 200 || s == 201);
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("P2-3: landed cost layer writers take the costing locks before the quant locks")
    void landedCostTakesCostingLock() throws Exception {
        String p = product("LC-LOCK");
        receive(p, 4, 3);
        CompletableFuture<Boolean> writer;
        try (Connection activation = dataSource.getConnection()) {
            activation.setAutoCommit(false);
            try {
                // A cost policy activation holds the organisation key exclusively.
                exec(activation, "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", costingKey(null));
                writer = CompletableFuture.supplyAsync(() -> asOrg(() -> {
                    layerCostWriter.lockQuants(List.of(new LayerCostWriter.Quant(p, null, main)));
                    return true;
                }));
                awaitLockWait(writer);
                assertThat(quantLockFree(p, main)).isTrue();
            } finally {
                activation.commit();
            }
        }
        assertThat(writer.get(30, TimeUnit.SECONDS)).isTrue();
    }

    // ------------------------------------------------------------------ P2-2 lots and serials

    @Test
    @DisplayName("P2-2: a transfer reversal brings back the transferred lot, not the destination's oldest")
    void transferReversalReturnsTransferredLot() {
        String p = product("TRF-LOT", Map.of("batchTracking", true));
        String old = "OLD-" + sfx();
        String moved = "NEW-" + sfx();
        receive(p, alt, 10, 1, m -> m.batch(old));
        receive(p, main, 5, 2, m -> m.batch(moved));
        String trf = transfer(main, alt, p, 5);
        assertThat(lotQty(p, alt, moved)).isEqualByComparingTo("5");
        okA(200, "POST", "/inventory/transfers/" + trf + "/reverse", Map.of("reason", "wrong store"));
        assertThat(lotQty(p, alt, old)).as("the destination's own older lot stays").isEqualByComparingTo("10");
        assertThat(lotQty(p, alt, moved)).isEqualByComparingTo("0");
        assertThat(lotQty(p, main, moved)).isEqualByComparingTo("5");
        assertThat(lotQty(p, main, old)).isEqualByComparingTo("0");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("P2-2: count lines post against the counted lot when two lots share a number")
    void countAdjustsTheCountedLot() {
        String p = product("CNT-LOT", Map.of("batchTracking", true));
        String number = "SAME-" + sfx();
        receive(p, main, 5, 2, m -> m.batch(number));
        receive(p, main, 5, 3, m -> m.batch(number));
        String first = lotId(p, main, number, new BigDecimal("2.000000"));
        String second = lotId(p, main, number, new BigDecimal("3.000000"));
        JsonNode session = startCount(p);
        String id = session.get("id").asString();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode l : session.get("lines")) {
            String batchId = l.get("batchId").isNull() ? null : l.get("batchId").asString();
            if (second.equals(batchId)) {
                lines.add(Map.of("lineId", l.get("id").asString(), "countedQty", 3, "reason", "damaged"));
            } else if (first.equals(batchId)) {
                lines.add(Map.of("lineId", l.get("id").asString(), "countedQty", 6, "reason", "found"));
            }
        }
        assertThat(lines).hasSize(2);
        okA(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", lines));
        okA(200, "POST", "/inventory/counts/" + id + "/submit", Map.of());
        assertThat(lotRowQty(second)).as("the counted lot lost 2").isEqualByComparingTo("3");
        assertThat(lotRowQty(first)).as("the gain went back into the counted lot").isEqualByComparingTo("6");
        assertThat(count("SELECT count(*) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ?",
                org, p)).as("no new lot row").isEqualTo(2L);
        assertThat(onHand(p, main)).isEqualByComparingTo("9");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("P2-2: the same new serial received at two locations at once: one receipt wins, the other is a 400")
    void concurrentSameSerialReceipt() throws Exception {
        String p = product("SER-RACE", Map.of("serialTracking", true));
        String serial = "SN-" + sfx();
        List<Callable<String>> tasks = new ArrayList<>();
        for (String loc : List.of(main, alt)) {
            tasks.add(() -> {
                try {
                    receive(p, loc, 1, 10, m -> m.serials(List.of(serial)));
                    return "ok:" + loc;
                } catch (ApiException e) {
                    return "400:" + e.getMessage();
                }
            });
        }
        List<String> results = parallel(tasks);
        assertThat(results.stream().filter(r -> r.startsWith("ok:")).count()).as(results.toString()).isEqualTo(1);
        String failure = results.stream().filter(r -> r.startsWith("400:")).findFirst().orElseThrow();
        assertThat(failure).containsAnyOf("is already in stock at", "already exist for this product");
        String winner = results.stream().filter(r -> r.startsWith("ok:")).findFirst().orElseThrow().substring(3);
        String loser = winner.equals(main) ? alt : main;
        assertThat(jdbc.sql("SELECT \"locationId\" FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"serialNumber\" = ?").params(org, p, serial).query(String.class).single())
                .isEqualTo(winner);
        assertThat(onHand(p, winner)).isEqualByComparingTo("1");
        assertThat(onHand(p, loser)).isEqualByComparingTo("0");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("P2-2: a production reversal issues the produced lot, not the oldest lot (FEFO)")
    void productionReversalIssuesProducedLot() {
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        String category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" LIMIT 1").param(demoOrg).query(String.class).single();
        String unit = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).single();
        String bakery = ok(201, "POST", "/inventory/locations", Map.of("code", "R5B-P-" + sfx(), "name", "Bakery "
                + sfx(), "type", "production")).get("id").asString();
        String store = ok(201, "POST", "/inventory/locations", Map.of("code", "R5B-W-" + sfx(), "name", "Store "
                + sfx(), "type", "warehouse")).get("id").asString();
        String flour = UUID.randomUUID().toString();
        String cake = UUID.randomUUID().toString();
        for (String[] pr : List.of(new String[] {flour, "false"}, new String[] {cake, "true"})) {
            jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                            + " \"costPrice\", \"categoryId\", \"uomId\", \"batchTracking\", \"serialTracking\","
                            + " \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', 'AVCO', 1, ?, ?, ?,"
                            + " false, true, now())")
                    .params(pr[0], demoOrg, "R5B-" + sfx(), "R5B item " + sfx(), category, unit,
                            Boolean.parseBoolean(pr[1])).update();
        }
        String oldLot = "OLD-" + sfx();
        String producedLot = "PRD-" + sfx();
        Map<String, Object> flourLine = new LinkedHashMap<>(Map.of("productId", flour, "quantity", 10, "unitCost", 50));
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", bakery, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(flourLine)));
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", store, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", cake, "quantity", 5, "unitCost", 9,
                        "batchNumber", oldLot))));
        JsonNode bom = ok(201, "POST", "/manufacturing/boms", Map.of("name", "R5B " + sfx(), "outputProductId", cake,
                "outputQuantity", 10, "lines", List.of(Map.of("componentProductId", flour, "quantity", 2))));
        ok(201, "POST", "/manufacturing/boms/" + bom.get("id").asString() + "/activate", null);
        String order = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bom.get("id").asString(),
                "runs", 1, "locationId", bakery, "outputLocationId", store)).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + order + "/confirm", null);
        ok(201, "POST", "/manufacturing/production-orders/" + order + "/start", Map.of());
        ok(201, "POST", "/manufacturing/production-orders/" + order + "/complete", Map.of("qtyProduced", 10,
                "batchNumber", producedLot));
        ok(201, "POST", "/manufacturing/production-orders/" + order + "/reverse", Map.of("reason", "wrong recipe"));
        BigDecimal oldLeft = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"productId\" = ?"
                + " AND \"locationId\" = ? AND \"batchNumber\" = ?").params(cake, store, oldLot).query(BigDecimal.class)
                .single();
        BigDecimal producedLeft = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE"
                + " \"productId\" = ? AND \"locationId\" = ? AND \"batchNumber\" = ?").params(cake, store, producedLot)
                .query(BigDecimal.class).single();
        assertThat(oldLeft).as("the older lot is untouched").isEqualByComparingTo("5");
        assertThat(producedLeft).as("the produced lot went back").isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ P2-3 values

    @Test
    @DisplayName("P2-3: a serial transfer carries the serial units' own cost in the ledger")
    void serialTransferValue() {
        String p = product("SER-VAL", Map.of("serialTracking", true));
        receive(p, main, 1, 10, m -> m.serials(List.of("A-" + sfx())));
        receive(p, main, 1, 30, m -> m.serials(List.of("B-" + sfx())));
        transfer(main, alt, p, 1);
        Map<String, Object> out = ledgerRow(p, main, "transfer_out");
        Map<String, Object> in = ledgerRow(p, alt, "transfer_in");
        assertThat(dec(out.get("totalValue"))).as("oldest serial's cost").isEqualByComparingTo("10");
        assertThat(dec(in.get("totalValue"))).isEqualByComparingTo("10");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("P2-3: a STANDARD quant carries its standard on receipt and its transfers move at it")
    void standardTransferValue() {
        String p = product("STD-TRF", Map.of("costingMethod", "STANDARD", "costPrice", 7));
        receive(p, 10, 9);
        BigDecimal avg = jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(p, main).query(BigDecimal.class).single();
        assertThat(avg).isEqualByComparingTo("7");
        transfer(main, alt, p, 4);
        assertThat(dec(ledgerRow(p, main, "transfer_out").get("totalValue"))).isEqualByComparingTo("28");
        assertThat(dec(ledgerRow(p, alt, "transfer_in").get("totalValue"))).isEqualByComparingTo("28");
        assertThat(jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(p, alt).query(BigDecimal.class).single()).isEqualByComparingTo("7");
        assertThat(signedLedgerValue(p, alt)).isEqualByComparingTo("28");
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("P2-3: the AVCO rounding residue is swept when a quant reaches zero")
    void avcoResidueSwept() {
        String p = product("AVCO-RES");
        receive(p, 1, 1);
        receive(p, 2, 2);
        for (int i = 0; i < 3; i++) {
            issue(p, main, 1, null);
        }
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        List<Map<String, Object>> swept = jdbc.sql("SELECT \"totalValue\", notes FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND type = 'cost_adjustment'").params(org, p).query()
                .listOfRows();
        assertThat(swept).hasSize(1);
        assertThat(dec(swept.getFirst().get("totalValue"))).isEqualByComparingTo("0.000001");
        assertThat((String) swept.getFirst().get("notes")).startsWith("AVCO rounding residue");
        assertThat(signedLedgerValue(p, main)).isEqualByComparingTo("0");
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ P2-7 rental

    @Test
    @DisplayName("P2-7: a rental checkout moves the unit's variant and its own serial")
    void rentalTransferCarriesVariantAndSerial() {
        String p = product("RENT-SER", Map.of("serialTracking", true, "hasVariants", true));
        String variant = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, \"combinationKey\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, now())").params(variant, org, p, "Blue", variant).update();
        String stockLoc = asOrg(() -> rentalLocations.resolve(org, "stock"));
        String outLoc = asOrg(() -> rentalLocations.resolve(org, "out"));
        String older = "RS0-" + sfx();
        String unitSerial = "RS1-" + sfx();
        receive(p, stockLoc, 1, 40, m -> m.variant(variant).serials(List.of(older)));
        receive(p, stockLoc, 1, 50, m -> m.variant(variant).serials(List.of(unitSerial)));
        String serialId = jdbc.sql("SELECT id FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND"
                + " \"serialNumber\" = ?").params(org, unitSerial).query(String.class).single();
        String unitId = insert("RentalUnit", Map.of("organizationId", org, "unitCode", "U-" + sfx(), "productId", p,
                "variantId", variant, "inventorySerialId", serialId, "updatedAt", now()));
        asOrgRun(() -> rentalPosting.checkoutTransfer("AG-" + sfx(), p, 1, unitId));
        assertThat(jdbc.sql("SELECT \"locationId\" FROM \"InventorySerial\" WHERE id = ?").param(serialId)
                .query(String.class).single()).as("the unit's serial moved").isEqualTo(outLoc);
        assertThat(jdbc.sql("SELECT \"locationId\" FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND"
                + " \"serialNumber\" = ?").params(org, older).query(String.class).single()).isEqualTo(stockLoc);
        Map<String, Object> row = jdbc.sql("SELECT \"variantId\", \"totalValue\" FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND type = 'transfer_in'").params(org, p).query()
                .singleRow();
        assertThat(row.get("variantId")).isEqualTo(variant);
        assertThat(dec(row.get("totalValue"))).isEqualByComparingTo("50");
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ P2-11 audit trail, cost gating

    @Test
    @DisplayName("P2-11: the ledger detail shows the reversed row, the people and recordedOffline")
    void ledgerDetailAuditTrail() {
        String p = product("LDG-AUD");
        receive(p, 5, 4);
        String out = okA(200, "POST", "/inventory/stock-outs", Map.of("locationId", main, "responsibleById",
                auditUserId, "approvedById", auditUserId, "category", "sample", "items", List.of(Map.of("productId", p,
                        "qty", 2)))).get("id").asString();
        okA(200, "POST", "/inventory/stock-outs/" + out + "/approve", null);
        Map<String, Object> issued = jdbc.sql("SELECT id, \"ledgerCode\" FROM \"InventoryLedger\" WHERE \"productId\" = ?"
                + " AND \"quantityChange\" < 0").param(p).query().singleRow();
        String issueRow = (String) issued.get("id");
        okA(200, "POST", "/inventory/stock-outs/" + out + "/reverse", Map.of("reason", "counted twice"));
        String backRow = jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"reversesLedgerId\" = ?").param(issueRow)
                .query(String.class).single();
        JsonNode entry = okA(200, "GET", "/inventory/ledger/" + backRow, null).get("entry");
        assertThat(entry.get("reverses").get("id").asString()).isEqualTo(issueRow);
        assertThat(entry.get("reverses").get("ledgerCode").asString()).isEqualTo(issued.get("ledgerCode"));
        assertThat(entry.get("performedByName").asString()).isEqualTo("T");
        assertThat(entry.get("recordedOffline").asBoolean()).isFalse();

        String trf = transfer(main, alt, p, 1);
        jdbc.sql("UPDATE \"StockTransfer\" SET \"recordedOffline\" = true WHERE id = ?").param(trf).update();
        String inRow = (String) ledgerRow(p, alt, "transfer_in").get("id");
        JsonNode detail = okA(200, "GET", "/inventory/ledger/" + inRow, null);
        assertThat(detail.get("entry").get("recordedOffline").asBoolean()).isTrue();
        assertThat(detail.get("entry").get("reverses").isNull()).isTrue();
        assertThat(detail.get("entry").get("approvedByName").asString()).isEqualTo("T");
        for (JsonNode line : detail.get("lines")) {
            assertThat(line.get("recordedOffline").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("P2-4 remainder: ledger, ledger detail, item movements and movement summary hide costs without"
            + " inventory_valuation:read")
    void costFieldsNeedValuationPermission() {
        String p = product("LDG-COST");
        receive(p, 3, 7);
        String rowId = (String) ledgerRow(p, main, "receipt").get("id");
        String reader = readerToken(List.of(Permissions.INVENTORY__READ));
        Res ledger = call("GET", "/inventory/ledger?productId=" + p, null, reader);
        assertThat(ledger.status()).isEqualTo(200);
        JsonNode row = ledger.body().get("data").get(0);
        assertThat(row.get("unitCost").isNull()).isTrue();
        assertThat(row.get("totalValue").isNull()).isTrue();
        assertThat(num(row.get("quantityChange"))).isEqualByComparingTo("3");
        Res detail = call("GET", "/inventory/ledger/" + rowId, null, reader);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("entry").get("totalValue").isNull()).isTrue();
        assertThat(detail.body().get("totals").get("totalValue").isNull()).isTrue();
        Res moves = call("GET", "/inventory/reports/item-movements?productId=" + p, null, reader);
        assertThat(moves.status()).isEqualTo(200);
        JsonNode m = rowFor(moves.body().get("data"), p);
        assertThat(m.get("valueIn").isNull()).isTrue();
        assertThat(m.get("closingValue").isNull()).isTrue();
        assertThat(m.get("avgCost").isNull()).isTrue();
        assertThat(num(m.get("qtyIn"))).isEqualByComparingTo("3");
        assertThat(moves.body().get("totals").get("valueIn").isNull()).isTrue();
        Res summary = call("GET", "/inventory/reports/movements", null, reader);
        assertThat(summary.status()).isEqualTo(200);
        JsonNode s = null;
        for (JsonNode r : summary.body()) {
            if (p.equals(r.get("product").get("id").asString())) {
                s = r;
            }
        }
        assertThat(s).isNotNull();
        assertThat(s.get("netValue").isNull()).isTrue();
        assertThat(s.get("byType").get("receipt").get("value").isNull()).isTrue();

        String costReader = readerToken(List.of(Permissions.INVENTORY__READ, Permissions.INVENTORY_VALUATION__READ));
        assertThat(num(call("GET", "/inventory/ledger?productId=" + p, null, costReader).body().get("data").get(0)
                .get("totalValue"))).isEqualByComparingTo("21");
        assertThat(num(rowFor(call("GET", "/inventory/reports/item-movements?productId=" + p, null, costReader).body()
                .get("data"), p).get("valueIn"))).isEqualByComparingTo("21");
    }

    static JsonNode rowFor(JsonNode rows, String productId) {
        for (JsonNode r : rows) {
            if (productId.equals(r.get("productId").asString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + productId);
    }
}
