package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Shared fixtures of the landed-cost accounting ITs (LC-0 audit, LC-1 lineage): own warehouses, suppliers and products
 * inside the demo organisation, and the per-step books assertion (balanced entries, tie-out unchanged, 1396 movement =
 * open adjunct movement, exact per-account classification).
 */
abstract class LandedCostFixtures extends ApiTestSupport {

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
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + id + "/submit", null);
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
        a.put("returns", code("5976"));
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

    /** Balances before a step and the journal entries that already existed (new entries are the step's). */
    record Snap(Map<String, BigDecimal> balances, BigDecimal adjunct, BigDecimal tieOut, String[] entries) {}

    /** Inventory sub-ledger minus GL for the organisation (other classes share it, so steps compare its movement). */
    BigDecimal tieOutDifference() {
        JsonNode t = ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.05", null);
        return new BigDecimal(t.get("valuationVsLedger").asString());
    }

    Snap snap() {
        Map<String, BigDecimal> b = new LinkedHashMap<>();
        accounts().forEach((role, id) -> b.put(role, balance(id)));
        String[] entries = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(String.class).list().toArray(String[]::new);
        return new Snap(b, openAdjunct(), tieOutDifference(), entries);
    }

    /**
     * After a step: every journal entry since {@code s} balances, the step leaves the inventory / GL tie-out difference
     * unchanged (other classes share the organisation, so its absolute value is theirs), the 1396 movement equals
     * the movement of this class's open adjunct, and each account moved exactly as {@code expected} (role → delta;
     * roles not named must not have moved).
     */
    void assertBooks(Snap s, Map<String, String> expected) {
        List<String> unbalanced = jdbc.sql("SELECT e.id FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                        + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? AND NOT (e.id = ANY(?::text[]))"
                        + " GROUP BY e.id HAVING sum(l.\"baseDebit\") <> sum(l.\"baseCredit\")")
                .params(demoOrg, s.entries()).query(String.class).list();
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

}
