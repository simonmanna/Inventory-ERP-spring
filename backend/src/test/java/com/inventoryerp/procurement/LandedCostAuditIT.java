package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost LC-0 accounting audit (ADR-S053, {@code docs/audits/landed-cost-gl-trace.md}). Every scenario checks,
 * after every step: each new journal entry balances, the inventory sub-ledger ties out to the GL, the open
 * price-difference adjunct of this class's products moves with the 1396 GL balance, and each landed amount sits in the
 * account its treatment implies (classification), not merely in a balanced total.
 *
 * <p>Fixtures are this class's own warehouses, suppliers and products; assertions are deltas or scoped to them, so no
 * seed or demo balance can make a scenario pass. Cases the current engine cannot yet satisfy are {@code @Disabled} with
 * the phase that fixes them (LC-1: lineage-correct AVCO / transfers / WIP).
 */
class LandedCostAuditIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    String adminId;
    String w1;
    String w2;
    String vendor;
    String forwarder;
    final List<String> mine = new ArrayList<>();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        w1 = location("Audit W1");
        w2 = location("Audit W2");
        vendor = partner("Audit vendor");
        forwarder = partner("Audit forwarder");
    }

    // ------------------------------------------------------------------ fixtures

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location(String name) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "LCA-" + suffix(), "name", name + " " + suffix(),
                "type", "warehouse")).get("id").asString();
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "LCA-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String product(String costing, boolean lot, int standardCost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"batchTracking\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, ?::\"CostingMethod\", ?, 0, ?, now())")
                .params(id, demoOrg, "LCA-" + suffix(), "Audit item " + suffix(), costing, standardCost, lot).update();
        mine.add(id);
        return id;
    }

    String receive(String productId, int qty, int unitCost) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "Goods");
        line.put("quantity", qty);
        line.put("unitCost", unitCost);
        if (Boolean.TRUE.equals(jdbc.sql("SELECT \"batchTracking\" FROM \"Product\" WHERE id = ?").param(productId)
                .query(Boolean.class).single())) {
            line.put("batchNumber", "LOT-" + suffix());
        }
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", vendor, "warehouseId", w1,
                "receivedAt", Instant.now().toString(), "lines", List.of(line))).get("id").asString();
    }

    void sell(String productId, String location, int qty) {
        asAdmin(() -> stock.issue(StockMove.of(productId, location, new BigDecimal(qty)).source("audit", suffix())));
    }

    static Map<String, Object> charge(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res createVoucher(List<String> receipts, List<Map<String, Object>> charges) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsReceiptIds", receipts);
        body.put("charges", charges);
        return call("POST", "/procurement/landed-cost-vouchers", body, key());
    }

    String voucher(List<String> receipts, List<Map<String, Object>> charges) {
        Res r = createVoucher(receipts, charges);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("id").asString();
    }

    JsonNode approveAndPost(String id) {
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + id + "/approve", null);
        return post(id);
    }

    JsonNode post(String id) {
        Res r = call("POST", "/procurement/landed-cost-vouchers/" + id + "/post", null, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    JsonNode reverse(String id) {
        Res r = call("POST", "/procurement/landed-cost-vouchers/" + id + "/reverse", null, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    <T> T asAdmin(Callable<T> body) {
        return TenantContext.run(TenantStore.user(demoOrg, adminId), () -> {
            try {
                return body.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ------------------------------------------------------------------ books

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    /** The account with {@code code}, or null while nothing created it yet (2155 / 2156 are created on first use). */
    String code(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL")
                .params(demoOrg, code).query(String.class).optional().orElse(null);
    }

    /** The accounts landed cost can touch, by role. */
    Map<String, String> accounts() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("stock", mapped("stock_valuation"));
        a.put("cogs", mapped("cogs"));
        a.put("tax", mapped("tax_receivable"));
        a.put("expense", mapped("default_expense"));
        a.put("clearing", code("2155"));
        a.put("accrual", code("2156"));
        a.put("adjunct", code("1396"));
        a.put("wip", mapped("wip"));
        return a;
    }

    BigDecimal balance(String accountId) {
        if (accountId == null) {
            return BigDecimal.ZERO;
        }
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class)
                .single();
    }

    /** Open price-difference adjunct of this class's products (sub-ledger of 1396 for them). */
    BigDecimal openAdjunct() {
        if (mine.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return jdbc.sql("SELECT coalesce(sum(\"openAmount\"), 0) FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"reversedAt\" IS NULL AND \"productId\" = ANY(?::text[])")
                .params(demoOrg, mine.toArray(String[]::new)).query(BigDecimal.class).single();
    }

    record Snap(Map<String, BigDecimal> balances, BigDecimal adjunct, BigDecimal tieOut, Timestamp at) {}

    /** Inventory sub-ledger minus GL for the organisation (other classes share it, so steps compare its movement). */
    BigDecimal tieOutDifference() {
        JsonNode t = ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.05", null);
        return new BigDecimal(t.get("valuationVsLedger").asString());
    }

    Snap snap() {
        Map<String, BigDecimal> b = new LinkedHashMap<>();
        accounts().forEach((role, id) -> b.put(role, balance(id)));
        Timestamp at = jdbc.sql("SELECT clock_timestamp()::timestamp(3)").query(Timestamp.class).single();
        return new Snap(b, openAdjunct(), tieOutDifference(), at);
    }

    /**
     * After a step: every journal entry since {@code s} balances, the step leaves the inventory / GL tie-out difference
     * unchanged (other classes share the organisation, so its absolute value is theirs), the 1396 movement equals
     * the movement of this class's open adjunct, and each account moved exactly as {@code expected} (role → delta;
     * roles not named must not have moved).
     */
    void assertBooks(Snap s, Map<String, String> expected) {
        List<String> unbalanced = jdbc.sql("SELECT e.id FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                        + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? AND e.\"createdAt\" >= ?"
                        + " GROUP BY e.id HAVING sum(l.\"baseDebit\") <> sum(l.\"baseCredit\")")
                .params(demoOrg, s.at()).query(String.class).list();
        assertThat(unbalanced).as("unbalanced journal entries").isEmpty();
        assertThat(tieOutDifference().subtract(s.tieOut())).as("the step keeps inventory tied out to the GL")
                .isBetween(new BigDecimal("-0.05"), new BigDecimal("0.05"));
        Map<String, String> accounts = accounts();
        BigDecimal adjunctGl = balance(accounts.get("adjunct")).subtract(s.balances().get("adjunct"));
        assertThat(adjunctGl).as("1396 GL movement = open adjunct movement")
                .isEqualByComparingTo(openAdjunct().subtract(s.adjunct()));
        accounts.forEach((role, id) -> assertThat(balance(id).subtract(s.balances().get(role)))
                .as("classification: " + role).isEqualByComparingTo(expected.getOrDefault(role, "0")));
    }

    static Map<String, String> moves(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    BigDecimal avg(String productId, String location) {
        return jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, location).query(BigDecimal.class).single();
    }

    BigDecimal lotValue(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity * \"unitCost\"), 0) FROM \"InventoryBatch\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ?").params(demoOrg, productId).query(BigDecimal.class)
                .single();
    }

    // ------------------------------------------------------------------ 1 reversals

    @Test
    @DisplayName("1a AVCO: post 1000 on 100 units on hand, reverse before any sale → every account back to zero")
    void reverseBeforeAnySale() {
        String p = product("AVCO", false, 0);
        receive(p, 100, 50);
        Snap s = snap();
        String v = voucher(List.of(lastGrn(p)), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "1000", "clearing", "-1000"));
        assertThat(avg(p, w1)).isEqualByComparingTo("60");
        reverse(v);
        assertBooks(s, moves());
        assertThat(avg(p, w1)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("1b AVCO: 40 sold before, 20 after; reverse → landed cost leaves stock and COGS completely")
    void reverseAfterPartialSaleAvco() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        sell(p, w1, 40);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "600", "cogs", "400", "clearing", "-1000"));
        sell(p, w1, 20);
        assertBooks(s, moves("stock", "-600", "cogs", "1600", "clearing", "-1000"));
        reverse(v);
        // Only the 20 units sold after the snapshot remain, at purchase cost: −1000 stock / +1000 COGS.
        assertBooks(s, moves("stock", "-1000", "cogs", "1000"));
        assertThat(avg(p, w1)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("1b FIFO lot: same sequence on a lot-tracked item → lot back to purchase cost")
    void reverseAfterPartialSaleLot() {
        String p = product("FIFO", true, 0);
        String grn = receive(p, 100, 50);
        sell(p, w1, 40);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "600", "cogs", "400", "clearing", "-1000"));
        assertThat(lotValue(p)).isEqualByComparingTo("3600");
        sell(p, w1, 20);
        reverse(v);
        assertBooks(s, moves("stock", "-1000", "cogs", "1000"));
        assertThat(lotValue(p)).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("1b STANDARD: 40 of 100 sold, 1000 posted → 600 to the 1396 adjunct, 400 COGS; reverse → zero")
    void reverseAfterPartialSaleStandard() {
        String p = product("STANDARD", false, 50);
        String grn = receive(p, 100, 50);
        sell(p, w1, 40);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("adjunct", "600", "cogs", "400", "clearing", "-1000"));
        reverse(v);
        assertBooks(s, moves());
    }

    @Test
    @DisplayName("1c AVCO: everything sold after posting; reverse → landed cost taken back out of COGS")
    void reverseAfterFullSale() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        sell(p, w1, 100);
        assertBooks(s, moves("stock", "-5000", "cogs", "6000", "clearing", "-1000"));
        reverse(v);
        assertBooks(s, moves("stock", "-5000", "cogs", "5000"));
    }

    @Test
    @Disabled("LC-1: AVCO landed cost ignores lineage; consumption by an open production order goes to COGS, not WIP")
    @DisplayName("2 AVCO: 30 of 100 issued to an open production order → 300 WIP, 700 stock; reverse → zero")
    void reverseAfterProductionConsumption() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        String order = productionOrder(p);
        asAdmin(() -> stock.issue(StockMove.of(p, w1, new BigDecimal(30)).moveType("production_consume")
                .source("production_order", order)));
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "700", "wip", "300", "clearing", "-1000"));
        reverse(v);
        assertBooks(s, moves());
    }

    @Test
    @Disabled("LC-1: transfer is not consumption — landed cost must follow 60 units to W2 instead of going to COGS")
    @DisplayName("3 AVCO: 60 of 100 transferred W1 → W2, then 1000 posted → 400 W1 + 600 W2, no COGS; reverse → zero")
    void reverseAfterTransfer() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        asAdmin(() -> stock.transfer(StockMove.transfer(p, w1, w2, new BigDecimal(60)).source("audit", suffix())));
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        assertBooks(s, moves("stock", "1000", "clearing", "-1000"));
        assertThat(avg(p, w1)).isEqualByComparingTo("60");
        assertThat(avg(p, w2)).isEqualByComparingTo("60");
        reverse(v);
        assertBooks(s, moves());
    }

    String productionOrder(String outputProductId) {
        String code = "PO-LCA-" + suffix();
        jdbc.sql("INSERT INTO \"ProductionOrder\" (id, \"organizationId\", \"orderCode\", \"outputProductId\","
                + " \"locationId\", \"plannedQty\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 1, now())")
                .params(UUID.randomUUID().toString(), demoOrg, code, outputProductId, w1).update();
        return code;
    }

    @Test
    @DisplayName("4 generated supplier bill nets clearing to zero; once paid, the voucher cannot be reversed")
    void reverseRefusedWhenBillPaid() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 10, 100);
        Snap s = snap();
        String ref = "LCA-INV-" + suffix();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 200, "supplierId", forwarder,
                "supplierInvoiceRef", ref)));
        JsonNode posted = approveAndPost(v);
        // The voucher credits clearing; the generated bill debits clearing and credits the forwarder's payable.
        assertThat(balance(code("2155")).subtract(s.balances().get("clearing"))).isEqualByComparingTo("0");
        String bill = posted.get("charges").get(0).get("vendorBillId").asString();
        Res pay = call("POST", "/supplier-payments", Map.of("partnerId", forwarder, "paymentDate",
                java.time.LocalDate.now().toString(), "amount", 200, "paymentMethod", "bank", "allocations",
                List.of(Map.of("documentId", bill, "amount", 200))), key());
        assertThat(pay.status()).as(pay.raw().body()).isIn(200, 201);
        assertThat(jdbc.sql("SELECT \"amountPaid\" FROM \"Document\" WHERE id = ?").param(bill)
                .query(BigDecimal.class).single()).isEqualByComparingTo("200");
        Res rev = call("POST", "/procurement/landed-cost-vouchers/" + v + "/reverse", null, key());
        assertThat(rev.status()).isEqualTo(400);
        assertThat(rev.body().get("message").asString()).contains("has payments; reverse them before reversing");
    }

    // ------------------------------------------------------------------ 5 accruals

    @Test
    @DisplayName("5 accrual 10 % below and above the actual: clearing returns 2156 and 1396 to zero either way")
    void accrualLowerAndHigherThanActual() {
        String rule = call("POST", "/procurement/landed-cost-accrual-rules", Map.of("scopeType", "supplier", "scopeId",
                vendor, "ratePct", 10), key()).body().get("id").asString();
        try {
            for (int actual : new int[] {1200, 800}) {
                String p = product("AVCO", false, 0);
                String grn = receive(p, 100, 100);
                Snap s = snap();
                Res a = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), key());
                assertThat(a.status()).as(a.raw().body()).isIn(200, 201);
                assertBooks(s, moves("adjunct", "1000", "accrual", "-1000"));
                String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", actual)));
                approveAndPost(v);
                Res c = call("POST", "/procurement/landed-cost-accruals/" + a.body().get("id").asString() + "/clear",
                        Map.of("voucherId", v), key());
                assertThat(c.status()).as(c.raw().body()).isIn(200, 201);
                assertBooks(s, moves("stock", String.valueOf(actual), "clearing", String.valueOf(-actual)));
                assertThat(avg(p, w1)).isEqualByComparingTo(String.valueOf(100 + actual / 100));
            }
            assertThat(new BigDecimal(ok(200, "GET", "/procurement/landed-cost-accruals/reconciliation", null)
                    .get("difference").asString())).isEqualByComparingTo("0");
        } finally {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + rule, null);
        }
    }

    // ------------------------------------------------------------------ 6 FX

    @Test
    @DisplayName("6 USD freight at 3,700: capitalised at the recognition rate; generated USD bill clears 2155 exactly")
    void foreignCurrencyCharge() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50_000);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100, "currencyCode", "USD",
                "exchangeRate", 3700, "supplierId", forwarder, "supplierInvoiceRef", "LCA-USD-" + suffix())));
        approveAndPost(v);
        assertThat(balance(code("2155")).subtract(s.balances().get("clearing"))).isEqualByComparingTo("0");
        assertThat(balance(mapped("stock_valuation")).subtract(s.balances().get("stock")))
                .isEqualByComparingTo("370000");
        assertThat(avg(p, w1)).isEqualByComparingTo("53700");
    }

    @Test
    @DisplayName("6 USD bill paid at 3,750: 5,000 realised FX loss, nothing to stock / COGS / WIP")
    void realisedFxOnSettlement() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50_000);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100, "currencyCode", "USD",
                "exchangeRate", 3700, "supplierId", forwarder, "supplierInvoiceRef", "LCA-FX-" + suffix())));
        JsonNode posted = approveAndPost(v);
        Snap s = snap();
        BigDecimal fx0 = balance(mapped("fx_loss")).add(balance(mapped("fx_gain")));
        String bill = posted.get("charges").get(0).get("vendorBillId").asString();
        Res pay = call("POST", "/supplier-payments", Map.of("partnerId", forwarder, "paymentDate",
                java.time.LocalDate.now().toString(),
                "amount", 100, "currencyCode", "USD", "exchangeRate", 3750, "paymentMethod", "bank", "allocations",
                List.of(Map.of("documentId", bill, "amount", 100))), key());
        assertThat(pay.status()).as(pay.raw().body()).isIn(200, 201);
        assertThat(balance(mapped("fx_loss")).add(balance(mapped("fx_gain"))).subtract(fx0))
                .as("realised FX loss 100 × 50").isEqualByComparingTo("5000");
        assertBooks(s, moves());
    }

    // ------------------------------------------------------------------ 7 concurrency, 8 period lock

    @Test
    @DisplayName("7 two concurrent posts of one voucher: exactly one succeeds, the other is 409, stock moves once")
    void concurrentPosts() throws Exception {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + v + "/approve", null);
        List<Integer> statuses = race(() -> call("POST", "/procurement/landed-cost-vouchers/" + v + "/post", null,
                key()).status());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertBooks(s, moves("stock", "1000", "clearing", "-1000"));
    }

    <T> List<T> race(Callable<T> action) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<T>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                    return action.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (CompletableFuture<T> f : runs) {
            out.add(f.get());
        }
        return out;
    }

    @Test
    @DisplayName("8 books locked through today: posting is refused and nothing moves; unlocked, it posts")
    void lockedPeriod() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + v + "/approve", null);
        jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = now() + interval '1 day' WHERE id = ?")
                .param(demoOrg).update();
        try {
            Res r = call("POST", "/procurement/landed-cost-vouchers/" + v + "/post", null, key());
            assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
            assertThat(r.body().get("message").asString().toLowerCase()).contains("lock");
            assertBooks(s, moves());
            assertThat(avg(p, w1)).isEqualByComparingTo("50");
        } finally {
            jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = NULL WHERE id = ?").param(demoOrg).update();
        }
        post(v);
        assertBooks(s, moves("stock", "1000", "clearing", "-1000"));
    }

    // ------------------------------------------------------------------ 9 duplicates, 10 layering, 11 tenancy

    @Test
    @DisplayName("9 the same supplier invoice entered twice at once: one voucher, one 409")
    void concurrentDuplicateInvoice() throws Exception {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 10, 50);
        String ref = "LCA-DUP-" + suffix();
        List<Integer> statuses = race(() -> createVoucher(List.of(grn), List.of(charge("kind", "freight", "amount", 10,
                "supplierId", forwarder, "supplierInvoiceRef", ref, "billMode", "none"))).status());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    @DisplayName("10 two vouchers on one receipt layer on top of each other: 50 + 10 + 5 = 65 per unit")
    void multipleVouchersOnOneReceipt() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        Snap s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "duty", "amount", 500))));
        assertBooks(s, moves("stock", "1500", "clearing", "-1500"));
        assertThat(avg(p, w1)).isEqualByComparingTo("65");
    }

    @Test
    @DisplayName("11 another organisation sees none of it")
    void tenantIsolation() {
        String p = product("AVCO", false, 0);
        String v = voucher(List.of(receive(p, 1, 50)), List.of(charge("kind", "freight", "amount", 1)));
        assertThat(call("GET", "/procurement/landed-cost-vouchers/" + v, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/procurement/landed-cost-vouchers/" + v + "/preview", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/procurement/landed-cost-vouchers/" + v + "/approve", null, otherToken).status())
                .isIn(403, 404);
    }

    // ------------------------------------------------------------------ VAT

    @Test
    @DisplayName("VAT: recoverable / non-recoverable on capitalised / expensed charges; input tax never twice")
    void vatCases() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 100);

        Snap s = snap();
        JsonNode a = approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000,
                "taxAmount", 180, "taxRecoverable", true))));
        assertBooks(s, moves("stock", "1000", "tax", "180", "clearing", "-1180"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(a.get("journalEntryId").asString(), mapped("tax_receivable")).query(Long.class).single())
                .as("input tax debited once").isEqualTo(1);

        s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "other", "treatment", "expense", "amount", 1000,
                "taxAmount", 180, "taxRecoverable", true))));
        assertBooks(s, moves("expense", "1000", "tax", "180", "clearing", "-1180"));

        s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "duty", "amount", 1000, "taxAmount", 180,
                "taxRecoverable", false))));
        assertBooks(s, moves("stock", "1180", "clearing", "-1180"));

        s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "other", "treatment", "expense", "amount", 1000,
                "taxAmount", 180, "taxRecoverable", false))));
        assertBooks(s, moves("expense", "1180", "clearing", "-1180"));
        assertThat(avg(p, w1)).isEqualByComparingTo("121.8");

        Res taxOnTax = createVoucher(List.of(grn), List.of(charge("kind", "other", "treatment", "recoverable_tax",
                "amount", 100, "taxAmount", 5, "taxRecoverable", true)));
        assertThat(taxOnTax.status()).isEqualTo(400);
        assertThat(taxOnTax.body().get("message").asString())
                .endsWith(": a recoverable tax charge cannot carry tax of its own");

        Res unstated = createVoucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100, "taxAmount", 18)));
        assertThat(unstated.status()).isEqualTo(400);
        assertThat(unstated.body().get("message").asString()).contains("state whether its tax is recoverable");
    }

    @Test
    @DisplayName("VAT: a tax marked non-recoverable decides the default; stating otherwise is an audited override")
    void vatDefaultFromTax() {
        String taxId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, type, rate, \"isInclusive\", \"isCompound\","
                        + " \"vatCategory\", \"isActive\", recoverability, \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'vat'::\"TaxType\", 18, false, false, 'standard', true, 'none', now())")
                .params(taxId, demoOrg, "Import VAT blocked " + suffix(), "LCA" + suffix().substring(0, 4)).update();
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 100);
        Snap s = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000, "taxAmount", 180,
                "taxId", taxId))));
        assertBooks(s, moves("stock", "1180", "clearing", "-1180"));

        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100, "taxAmount", 18, "taxId",
                taxId, "taxRecoverable", true)));
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND \"newValues\"::text LIKE"
                + " '%landed_cost_tax_override%'").param(v).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("VAT: an unreviewed (historic) charge cannot be posted, but a posted one can still be reversed")
    void unreviewedTaxGuard() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 100);
        Snap s = snap();
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000, "taxAmount", 180,
                "taxRecoverable", true)));
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + v + "/approve", null);
        unreview(v);
        Res r = call("POST", "/procurement/landed-cost-vouchers/" + v + "/post", null, key());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).endsWith(": tax recoverability is unreviewed");
        jdbc.sql("UPDATE \"LandedCostCharge\" SET \"taxRecoverable\" = true WHERE \"landedCostId\" = ?").param(v)
                .update();
        post(v);
        unreview(v);
        reverse(v);
        assertBooks(s, moves());
    }

    void unreview(String voucherId) {
        jdbc.sql("UPDATE \"LandedCostCharge\" SET \"taxRecoverable\" = NULL WHERE \"landedCostId\" = ?").param(voucherId)
                .update();
    }

    String lastGrn(String productId) {
        return jdbc.sql("SELECT g.id FROM \"GoodsReceiptNote\" g JOIN \"GoodsReceiptLine\" l ON l.\"goodsReceiptId\" ="
                + " g.id WHERE l.\"productId\" = ? ORDER BY g.\"createdAt\" DESC LIMIT 1").param(productId)
                .query(String.class).single();
    }
}
