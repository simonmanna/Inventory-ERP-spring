package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.settings.SettingResolverService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Stock engine hardening: concurrent receipts / issues on one quant keep quantity, running average and the ledger
 * chain exact; opposite-order multi-line documents and transfers do not deadlock; lot-tracked shortfalls are netted
 * by the next receipt (open lots = max(0, on-hand)) with a cost true-up; transit lines move their own lots; the strict
 * negative-stock setting blocks document issues; reservations stay unique per source line.
 */
class StockConcurrencyIT extends ApiTestSupport {

    String adminId;
    String category;
    String uom;

    @Autowired
    SettingResolverService settingResolver;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" LIMIT 1")
                .param(demoOrg).query(String.class).optional().orElse(null);
        uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).optional().orElse(null);
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location(String type) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "C-" + suffix(), "name", "Loc " + suffix(),
                "type", type)).get("id").asString();
    }

    String product(String costing, boolean batch) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"categoryId\", \"uomId\", \"batchTracking\", \"serialTracking\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', ?::\"CostingMethod\", 4, ?, ?, ?, false, now())")
                .params(id, demoOrg, "P-" + suffix(), "Item " + suffix(), costing, category, uom, batch).update();
        return id;
    }

    Map<String, Object> line(String productId, Object qty, Object unitCost, String batch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        if (unitCost != null) {
            m.put("unitCost", unitCost);
        }
        if (batch != null) {
            m.put("batchNumber", batch);
        }
        return m;
    }

    Map<String, Object> direct(String loc, List<Map<String, Object>> items) {
        return Map.of("locationId", loc, "responsibleById", adminId, "approvedById", adminId, "items", items);
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, loc).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    BigDecimal openLots(String productId, String loc) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"productId\" = ?"
                        + " AND \"locationId\" = ? AND quantity > 0 AND \"isActive\" = true")
                .params(productId, loc).query(BigDecimal.class).single();
    }

    void assertLotInvariant(String productId, String loc) {
        assertThat(openLots(productId, loc)).as("open lots = max(0, on-hand)")
                .isEqualByComparingTo(onHand(productId, loc).max(BigDecimal.ZERO));
    }

    /** Run every task at once; returns the HTTP statuses. */
    List<Integer> parallel(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> t : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                return t.call();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();
        return statuses;
    }

    static long seqOf(Object ledgerCode) {
        return Long.parseLong(String.valueOf(ledgerCode).replaceAll("\\D", ""));
    }

    @Test
    @DisplayName("parallel receipts and issues on one AVCO quant: no lost quantity, serial-order average, exact chain")
    void parallelReceiptsAndIssues() throws Exception {
        String loc = location("warehouse");
        String p = product("AVCO", false);
        ok(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(line(p, 100, 10, null))));
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int cost = 11 + i;
            tasks.add(() -> as("POST", "/inventory/direct-stock/in", direct(loc, List.of(line(p, 10, cost, null))))
                    .status());
            tasks.add(() -> as("POST", "/inventory/direct-stock/out", direct(loc, List.of(line(p, 5, null, null))))
                    .status());
        }
        assertThat(parallel(tasks)).allMatch(s -> s == 200);
        assertThat(onHand(p, loc)).isEqualByComparingTo("140");

        List<Map<String, Object>> rows = new ArrayList<>(jdbc.sql("SELECT \"ledgerCode\", \"qtyBefore\","
                        + " \"quantityChange\", \"balanceAfter\", \"unitCost\" FROM \"InventoryLedger\" WHERE \"productId\" = ?"
                        + " AND \"locationId\" = ?")
                .params(p, loc).query().listOfRows());
        rows.sort(Comparator.comparingLong(r -> seqOf(r.get("ledgerCode"))));
        assertThat(rows).hasSize(17);
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal avg = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal before = (BigDecimal) r.get("qtyBefore");
            BigDecimal change = (BigDecimal) r.get("quantityChange");
            BigDecimal unit = (BigDecimal) r.get("unitCost");
            assertThat(before).as("chain at " + r.get("ledgerCode")).isEqualByComparingTo(balance);
            assertThat((BigDecimal) r.get("balanceAfter")).isEqualByComparingTo(before.add(change));
            if (change.signum() > 0) {
                avg = balance.signum() <= 0 ? unit
                        : balance.multiply(avg).add(change.multiply(unit)).divide(balance.add(change), 10,
                                RoundingMode.HALF_UP);
            } else {
                assertThat(unit.subtract(avg).abs()).as("issue at the running average " + r.get("ledgerCode"))
                        .isLessThan(new BigDecimal("0.001"));
            }
            balance = balance.add(change);
        }
        BigDecimal stored = jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ?"
                + " AND \"locationId\" = ?").params(p, loc).query(BigDecimal.class).single();
        assertThat(stored.subtract(avg).abs()).isLessThan(new BigDecimal("0.001"));
    }

    @Test
    @DisplayName("opposite-order multi-line transfers and stock-outs run in parallel without deadlock")
    void oppositeOrderDocuments() throws Exception {
        String a = location("warehouse");
        String b = location("store");
        String p1 = product("AVCO", false);
        String p2 = product("AVCO", false);
        ok(200, "POST", "/inventory/direct-stock/in", direct(a, List.of(line(p1, 100, 2, null), line(p2, 100, 3, null))));
        ok(200, "POST", "/inventory/direct-stock/in", direct(b, List.of(line(p2, 100, 3, null), line(p1, 100, 2, null))));
        List<String> transfers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean ab = i % 2 == 0;
            List<Map<String, Object>> items = ab
                    ? List.of(Map.of("productId", p1, "qtyRequested", 3), Map.of("productId", p2, "qtyRequested", 3))
                    : List.of(Map.of("productId", p2, "qtyRequested", 3), Map.of("productId", p1, "qtyRequested", 3));
            transfers.add(ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", ab ? a : b, "toLocationId",
                    ab ? b : a, "responsibleById", adminId, "approvedById", adminId, "items", items)).get("id")
                    .asString());
        }
        List<String> outs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            List<Map<String, Object>> items = i % 2 == 0
                    ? List.of(Map.of("productId", p1, "qty", 1), Map.of("productId", p2, "qty", 1))
                    : List.of(Map.of("productId", p2, "qty", 1), Map.of("productId", p1, "qty", 1));
            outs.add(ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", a, "responsibleById", adminId,
                    "approvedById", adminId, "items", items)).get("id").asString());
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        transfers.forEach(t -> tasks.add(() -> as("POST", "/inventory/transfers/" + t + "/approve", null).status()));
        outs.forEach(o -> tasks.add(() -> as("POST", "/inventory/stock-outs/" + o + "/approve", null).status()));
        tasks.add(() -> as("POST", "/inventory/direct-stock/in", direct(a, List.of(line(p2, 1, 3, null),
                line(p1, 1, 2, null)))).status());
        assertThat(parallel(tasks)).allMatch(s -> s == 200);
        // conserved: 200 each, +1 received, −4 issued at A
        assertThat(onHand(p1, a).add(onHand(p1, b))).isEqualByComparingTo("197");
        assertThat(onHand(p2, a).add(onHand(p2, b))).isEqualByComparingTo("197");
    }

    @Test
    @DisplayName("lot-tracked shortfall: the next receipt nets it (open lots = max(0, on-hand)) and trues up the cost")
    void lotShortfallNetting() {
        String loc = location("warehouse");
        String p = product("FIFO", true);
        ok(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(line(p, 5, 2, "L1"))));
        assertLotInvariant(p, loc);
        ok(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(line(p, 8, null, null))));
        assertThat(onHand(p, loc)).isEqualByComparingTo("-3");
        assertLotInvariant(p, loc);
        JsonNode in = ok(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(line(p, 10, 3, "L2"))));
        assertThat(onHand(p, loc)).isEqualByComparingTo("7");
        assertLotInvariant(p, loc);
        // 3 short units issued at 2, covered at 3 → 3 × (3 − 2) = 3 to cost of sales, once (posting key = ledger code)
        List<BigDecimal> corrections = jdbc.sql("SELECT l.debit FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                        + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? AND e.\"sourceId\" = ?"
                        + " AND e.\"postingKey\" LIKE 'inventory:cost_correction:%' AND l.debit > 0")
                .params(demoOrg, in.get("code").asString()).query(BigDecimal.class).list();
        assertThat(corrections).hasSize(1);
        assertThat(corrections.getFirst()).isEqualByComparingTo("3");
        // the phantom is gone: only the 7 really on hand can be issued from lots
        ok(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(line(p, 7, null, null))));
        assertThat(onHand(p, loc)).isEqualByComparingTo("0");
        assertLotInvariant(p, loc);
        // a named lot that is short draws the other lots before going short
        ok(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(line(p, 2, 4, "L3"), line(p, 2, 5, "L4"))));
        Map<String, Object> named = line(p, 3, null, "L3");
        named.put("distStrategy", "MANUAL");
        ok(200, "POST", "/inventory/direct-stock/out", direct(loc, List.of(named)));
        assertThat(onHand(p, loc)).isEqualByComparingTo("1");
        assertLotInvariant(p, loc);
    }

    @Test
    @DisplayName("transit: each transfer receives the lots it dispatched, not the oldest lots in transit")
    void transitMovesOwnLots() {
        String s1 = location("warehouse");
        String s2 = location("warehouse");
        String d1 = location("branch");
        String d2 = location("branch");
        String p = product("FIFO", true);
        ok(200, "POST", "/inventory/direct-stock/in", direct(s1, List.of(line(p, 3, 2, "OLD"))));
        ok(200, "POST", "/inventory/direct-stock/in", direct(s2, List.of(line(p, 3, 5, "NEW"))));
        String t1 = transit(s1, d1, p, 3);
        String t2 = transit(s2, d2, p, 3);
        String item2 = ok(200, "GET", "/inventory/transfers/" + t2, null).get("items").get(0).get("id").asString();
        ok(200, "POST", "/inventory/transfers/" + t2 + "/receive", Map.of("lines", List.of(Map.of("itemId", item2,
                "received", 2, "damaged", 1))));
        Map<String, Object> lot = jdbc.sql("SELECT \"batchNumber\", \"unitCost\" FROM \"InventoryBatch\" WHERE"
                + " \"productId\" = ? AND \"locationId\" = ? AND quantity > 0").params(p, d2).query().singleRow();
        assertThat(lot.get("batchNumber")).isEqualTo("NEW");
        assertThat((BigDecimal) lot.get("unitCost")).isEqualByComparingTo("5");
        String item1 = ok(200, "GET", "/inventory/transfers/" + t1, null).get("items").get(0).get("id").asString();
        ok(200, "POST", "/inventory/transfers/" + t1 + "/recall", Map.of("reason", "not needed"));
        assertThat(item1).isNotNull();
        String back = jdbc.sql("SELECT \"batchNumber\" FROM \"InventoryBatch\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ? AND quantity > 0").params(p, s1).query(String.class).single();
        assertThat(back).isEqualTo("OLD");
        assertLotInvariant(p, s1);
        assertLotInvariant(p, d2);
    }

    String transit(String from, String to, String p, int qty) {
        String id = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", from, "toLocationId", to, "mode",
                "transit", "responsibleById", adminId, "approvedById", adminId, "items", List.of(Map.of("productId", p,
                        "qtyRequested", qty)))).get("id").asString();
        ok(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        ok(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        return id;
    }

    @Test
    @DisplayName("allowNegativeStock=false blocks a stock-out going short even when the item policy is warn")
    void strictNegativeStock() {
        String loc = location("store");
        String p = product("AVCO", false);
        ok(200, "POST", "/inventory/direct-stock/in", direct(loc, List.of(line(p, 2, 1, null))));
        String so = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", p, "qty", 5)))).get("id").asString();
        String setting = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                        + " (?, ?, 'inventory.allowNegativeStock', 'false'::jsonb, 'organization', now())")
                .params(setting, demoOrg).update();
        try {
            settingResolver.invalidate(demoOrg);
            Res r = as("POST", "/inventory/stock-outs/" + so + "/approve", null);
            assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).startsWith("Insufficient stock for ");
            assertThat(onHand(p, loc)).isEqualByComparingTo("2");
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(setting).update();
            settingResolver.invalidate(demoOrg);
        }
    }

    @Test
    @DisplayName("reservations: parallel reserves of one line keep one active row; ATP needs a product; variants checked")
    void reservationsAndGuards() throws Exception {
        String loc = location("warehouse");
        // no stock row yet: nothing to row-lock, the partial unique index keeps the line single
        String p = product("AVCO", false);
        String src = "SO-" + suffix();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            int q = i;
            tasks.add(() -> as("POST", "/inventory/reservations", Map.of("productId", p, "locationId", loc, "quantity", q,
                    "sourceType", "order", "sourceId", src)).status());
        }
        assertThat(parallel(tasks)).allMatch(s -> s == 201);
        long active = jdbc.sql("SELECT count(*) FROM \"StockReservation\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?"
                + " AND status = 'active'").params(demoOrg, src).query(Long.class).single();
        assertThat(active).isEqualTo(1);
        Res atp = as("GET", "/inventory/reservations/atp?locationId=" + loc, null);
        assertThat(atp.status()).isEqualTo(400);
        assertThat(atp.body().get("message").asString()).isEqualTo("productId is required");
        Map<String, Object> foreign = line(p, 1, null, null);
        foreign.put("variantId", UUID.randomUUID().toString());
        Res variant = as("POST", "/inventory/direct-stock/out", direct(loc, List.of(foreign)));
        assertThat(variant.status()).isEqualTo(404);
        assertThat(variant.body().get("message").asString()).isEqualTo("Variant not found");
    }
}
