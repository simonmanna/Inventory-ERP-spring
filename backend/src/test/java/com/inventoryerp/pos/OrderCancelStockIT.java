package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Hardening R4 (owner-approved 2026-11-23): stock of an order posted before it is billed (the deprecated order-level
 * timings) follows the order — one job per sale, and an explicit behaviour per state at cancel: pending job abandoned,
 * processing job waited for, posted / partly posted / partly returned issue returned at its original cost and lot, a
 * voided fired line issued once. After every scenario the R0 quantity checks are clean for the products involved.
 */
class OrderCancelStockIT extends ApiTestSupport {

    static final List<String> QUANTITY_CHECKS = List.of("ledger_vs_quant", "quant_vs_lots", "quant_vs_serials",
            "serial_state", "transit", "reservations");

    @Autowired
    StockService stock;

    @Autowired
    PosInvoiceService billing;

    @Autowired
    SettingResolverService resolver;

    final String pin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
    String sfx;
    String adminId;
    String warehouseId;
    String registerId;
    String sessionId;

    @BeforeAll
    void fixtures() {
        sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        warehouseId = pinPosWarehouse();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", pin));
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "OCS-" + sfx, "name", "Cancel till " + sfx))
                .get("id").asString();
        sessionId = money(200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0))
                .get("id").asString();
    }

    @AfterAll
    void cleanup() {
        for (String id : jdbc.sql("SELECT id FROM \"Order\" WHERE \"cashSessionId\" = ? AND status::text NOT IN"
                + " ('closed', 'cancelled', 'completed')").param(sessionId).query(String.class).list()) {
            keyed("POST", "/pos/orders/" + id + "/cancel", Map.of("reason", "cancel test cleanup", "overrideById",
                    adminId, "overridePin", pin));
        }
        forceCloseSessions(List.of(registerId));
    }

    // ------------------------------------------------------------------ helpers

    void asOrgRun(Runnable body) {
        TenantContext.run(TenantStore.user(demoOrg, adminId), body);
    }

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode money(int status, String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String product(String name, boolean lots) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"batchTracking\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable', true, 'AVCO', 0, 100, ?, now())").params(id, demoOrg, "OCS-"
                        + UUID.randomUUID().toString().substring(0, 8), name + " " + sfx, lots).update();
        return id;
    }

    void receive(String productId, int qty, int cost, String lot) {
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> stock.receive(StockMove.of(productId, warehouseId,
                BigDecimal.valueOf(qty)).unitCost(BigDecimal.valueOf(cost)).batch(lot)));
    }

    BigDecimal quant(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, warehouseId).query(BigDecimal.class).single();
    }

    static Map<String, Object> line(String productId, int qty) {
        return new LinkedHashMap<>(Map.of("productId", productId, "description", "Item", "quantity", qty,
                "unitPrice", 100));
    }

    @SafeVarargs
    final String order(Map<String, Object>... lines) {
        return ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1, "cashSessionId",
                sessionId, "lines", List.of(lines))).get("id").asString();
    }

    /** The order-level timing's job for the order (as the at_confirmation subscriber enqueues it). */
    String enqueue(String orderId) {
        asOrgRun(() -> billing.enqueueStockPosting(orderId, null, null, "at_confirmation"));
        return jdbc.sql("SELECT id FROM \"StockPostingJob\" WHERE \"orderId\" = ?").param(orderId).query(String.class)
                .single();
    }

    void process(String jobId) {
        asOrgRun(() -> billing.processStockPostingJob(jobId));
    }

    String jobStatus(String jobId) {
        return jdbc.sql("SELECT status FROM \"StockPostingJob\" WHERE id = ?").param(jobId).query(String.class).single();
    }

    void cancel(String orderId) {
        Res r = keyed("POST", "/pos/orders/" + orderId + "/cancel", Map.of("reason", "Guest left", "overrideById",
                adminId, "overridePin", pin));
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
    }

    String itemId(String orderId, String productId) {
        return jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"productId\" = ?")
                .params(orderId, productId).query(String.class).single();
    }

    /** Net quantity of the product's rows under one ledger reference type for this test. */
    BigDecimal moved(String productId, String referenceType) {
        return jdbc.sql("SELECT coalesce(sum(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"referenceType\" = ?").params(demoOrg, productId, referenceType)
                .query(BigDecimal.class).single();
    }

    /** The R0 quantity checks report nothing for these products. */
    void assertIntegrity(String... productIds) {
        Set<String> mine = Set.of(productIds);
        JsonNode report = ok(200, "GET", "/inventory/reports/integrity", null);
        List<String> broken = new ArrayList<>();
        for (JsonNode c : report.get("checks")) {
            if (!QUANTITY_CHECKS.contains(c.get("key").asString())) {
                continue;
            }
            for (JsonNode row : c.get("rows")) {
                JsonNode p = row.get("productId");
                if (p != null && mine.contains(p.asString())) {
                    broken.add(c.get("key").asString() + ": " + row);
                }
            }
        }
        assertThat(broken).as("integrity quantity checks for this scenario").isEmpty();
    }

    // ------------------------------------------------------------------ cancel table

    @Test
    @DisplayName("job pending: the cancel abandons it under its lock; nothing moves, a later run posts nothing")
    void pendingJobIsAbandoned() {
        String p = product("Pending", false);
        receive(p, 10, 10, null);
        String orderId = order(line(p, 2));
        String job = enqueue(orderId);
        cancel(orderId);
        assertThat(jobStatus(job)).isEqualTo("abandoned");
        assertThat(jdbc.sql("SELECT \"lastError\" FROM \"StockPostingJob\" WHERE id = ?").param(job).query(String.class)
                .single()).startsWith("Order cancelled");
        process(job);
        assertThat(jobStatus(job)).isEqualTo("abandoned");
        assertThat(quant(p)).isEqualByComparingTo("10");
        assertThat(moved(p, "pos_order")).isEqualByComparingTo("0");
        assertIntegrity(p);
    }

    @Test
    @DisplayName("posted: the cancel returns the issue at its original cost and into its original lot")
    void postedIsReturnedAtOriginalCostAndLot() {
        String plain = product("Posted", false);
        String lotted = product("Lotted", true);
        receive(plain, 10, 10, null);
        receive(lotted, 5, 10, "LA-" + sfx);
        receive(lotted, 5, 30, "LB-" + sfx);
        String orderId = order(line(plain, 2), line(lotted, 2));
        String job = enqueue(orderId);
        process(job);
        assertThat(jobStatus(job)).isEqualTo("done");
        assertThat(quant(plain)).isEqualByComparingTo("8");
        assertThat(quant(lotted)).isEqualByComparingTo("8");
        String issuedLot = jdbc.sql("SELECT \"batchId\" FROM \"InventoryLedger\" WHERE \"productId\" = ? AND"
                + " \"referenceType\" = 'pos_order'").param(lotted).query(String.class).single();
        receive(plain, 10, 40, null); // today's cost moves away from the issue's

        cancel(orderId);
        assertThat(jobStatus(job)).isEqualTo("abandoned");
        assertThat(quant(plain)).isEqualByComparingTo("20");
        assertThat(quant(lotted)).isEqualByComparingTo("10");
        List<Map<String, Object>> returns = jdbc.sql("SELECT r.\"productId\", r.\"unitCost\", i.\"unitCost\" AS issued,"
                + " r.\"batchId\", r.\"quantityChange\", r.type::text AS type FROM \"InventoryLedger\" r JOIN"
                + " \"InventoryLedger\" i ON i.id = r.\"reversesLedgerId\" WHERE r.\"referenceType\" = 'pos_order_cancel'"
                + " AND r.\"referenceId\" = ?").param(orderId).query().listOfRows();
        assertThat(returns).hasSize(2);
        for (Map<String, Object> r : returns) {
            assertThat((BigDecimal) r.get("unitCost")).as("original cost").isEqualByComparingTo((BigDecimal) r.get("issued"));
            assertThat(r.get("type")).isEqualTo("reversal_in");
            if (lotted.equals(r.get("productId"))) {
                assertThat(r.get("batchId")).as("back into the lot it left").isEqualTo(issuedLot);
            }
        }
        assertThat(moved(plain, "pos_order").add(moved(plain, "pos_order_cancel"))).isEqualByComparingTo("0");
        assertIntegrity(plain, lotted);
    }

    @Test
    @DisplayName("job processing: the cancel waits for the job row lock, then returns what the job posted")
    void processingJobIsWaitedFor() throws Exception {
        String p = product("Processing", false);
        receive(p, 10, 10, null);
        String orderId = order(line(p, 3));
        String job = enqueue(orderId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> asOrgRun(() -> new TransactionTemplate(txm)
                .executeWithoutResult(st -> {
                    jdbc.sql("SELECT id FROM \"StockPostingJob\" WHERE id = ? FOR UPDATE").param(job).query().listOfRows();
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    billing.processStockPostingJob(job); // posts inside the transaction that holds the lock
                })));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> cancelling = CompletableFuture.runAsync(() -> cancel(orderId));
        Thread.sleep(1500);
        assertThat(cancelling).as("the cancel waits for the job lock").isNotDone();
        release.countDown();
        worker.get(30, TimeUnit.SECONDS);
        cancelling.get(30, TimeUnit.SECONDS);
        assertThat(moved(p, "pos_order")).as("the job posted first").isEqualByComparingTo("-3");
        assertThat(moved(p, "pos_order_cancel")).as("then the cancel returned it").isEqualByComparingTo("3");
        assertThat(jobStatus(job)).isEqualTo("abandoned");
        assertThat(quant(p)).isEqualByComparingTo("10");
        assertIntegrity(p);
    }

    @Test
    @DisplayName("partially fulfilled: only the lines the job issued come back; the failed line never issues")
    void partiallyPostedReturnsIssuedLinesOnly() {
        String good = product("Good", false);
        String blocked = product("Blocked", false);
        receive(good, 10, 10, null);
        jdbc.sql("UPDATE \"Product\" SET \"stockPolicy\" = 'block' WHERE id = ?").param(blocked).update();
        ok(200, "PUT", "/settings/inventory.allowNegativeStock", Map.of("value", false, "scopeType", "product",
                "scopeId", blocked));
        String orderId = order(line(good, 2), line(blocked, 1));
        String job = enqueue(orderId);
        process(job);
        assertThat(jobStatus(job)).as("the blocked line failed").isEqualTo("pending");
        assertThat(quant(good)).isEqualByComparingTo("8");
        String goodItem = itemId(orderId, good);
        assertThat(jdbc.sql("SELECT ? = ANY(\"postedLineKeys\") FROM \"StockPostingJob\" WHERE id = ?")
                .params(goodItem, job).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE status = 'open' AND"
                + " payload->>'orderItemId' = ?").param(itemId(orderId, blocked)).query(Long.class).single()).isPositive();

        cancel(orderId);
        assertThat(jobStatus(job)).isEqualTo("abandoned");
        assertThat(quant(good)).isEqualByComparingTo("10");
        assertThat(quant(blocked)).isEqualByComparingTo("0");
        assertThat(moved(blocked, "pos_order")).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE status = 'open' AND"
                + " payload->>'orderItemId' = ?").param(itemId(orderId, blocked)).query(Long.class).single())
                .as("the failed line's exception is discarded with the job").isZero();
        process(job);
        assertThat(quant(blocked)).isEqualByComparingTo("0");
        assertIntegrity(good, blocked);
    }

    @Test
    @DisplayName("partially returned: a void returned part of the issue; the cancel returns only what is left")
    void partiallyReturnedThenCancelled() {
        String p = product("Partial", false);
        receive(p, 10, 10, null);
        String orderId = order(line(p, 3));
        String job = enqueue(orderId);
        process(job);
        assertThat(quant(p)).isEqualByComparingTo("7");
        String item = itemId(orderId, p);
        Res voided = keyed("DELETE", "/pos/orders/" + orderId + "/items/" + item, Map.of("reason", "Wrong size",
                "quantity", 1));
        assertThat(voided.status()).as(voided.raw().body()).isIn(200, 201);
        assertThat(moved(p, "pos_order_void")).as("the voided unit came back").isEqualByComparingTo("1");
        assertThat(quant(p)).isEqualByComparingTo("8");

        cancel(orderId);
        assertThat(moved(p, "pos_order_cancel")).as("issued − already returned").isEqualByComparingTo("2");
        assertThat(quant(p)).isEqualByComparingTo("10");
        assertIntegrity(p);
    }

    @Test
    @DisplayName("kitchen-voided line: the void returns the confirmation issue and kitchen waste issues it once")
    void kitchenVoidedLineIssuedOnce() {
        String p = product("Kitchen", false);
        receive(p, 10, 10, null);
        int n = (int) (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 900_000) + 100_000;
        String tableId = ok(201, "POST", "/pos/tables", Map.of("name", "OCS " + n, "number", n)).get("id").asString();
        ok(201, "POST", "/pos/tabs/" + tableId + "/save", Map.of("cashSessionId", sessionId, "guestCount", 1,
                "newRound", true, "lines", List.of(line(p, 2))));
        String orderId = ok(200, "GET", "/pos/tabs/" + tableId, null).get("id").asString();
        ok(201, "POST", "/pos/tabs/" + tableId + "/fire-kitchen", null);
        ok(201, "POST", "/pos/receipts/" + orderId + "/print-kot", null);
        String job = enqueue(orderId);
        process(job);
        assertThat(quant(p)).isEqualByComparingTo("8");
        String item = itemId(orderId, p);
        Res voided = keyed("DELETE", "/pos/orders/" + orderId + "/items/" + item, Map.of("reason", "Dropped",
                "quantity", 1, "overrideById", adminId, "overridePin", pin));
        assertThat(voided.status()).as(voided.raw().body()).isIn(200, 201);
        assertThat(moved(p, "pos_order_void")).isEqualByComparingTo("1");
        assertThat(moved(p, "pos_kitchen_waste")).isEqualByComparingTo("-1");
        assertThat(quant(p)).as("2 issued, the dropped one as waste — not 3").isEqualByComparingTo("8");
        assertIntegrity(p);
    }

    @Test
    @DisplayName("reopen: the jobs a cancel abandoned run again; a second cancel returns only the new issue")
    void reopenedOrderIssuesAgain() {
        String p = product("Reopen", false);
        receive(p, 10, 10, null);
        String orderId = order(line(p, 2));
        String job = enqueue(orderId);
        process(job);
        cancel(orderId);
        assertThat(quant(p)).isEqualByComparingTo("10");
        ok(201, "POST", "/pos/orders/" + orderId + "/reopen", null);
        assertThat(jobStatus(job)).isEqualTo("pending");
        process(job);
        assertThat(jobStatus(job)).isEqualTo("done");
        assertThat(quant(p)).isEqualByComparingTo("8");
        cancel(orderId);
        assertThat(quant(p)).isEqualByComparingTo("10");
        assertThat(jdbc.sql("SELECT count(DISTINCT \"reversesLedgerId\") FROM \"InventoryLedger\" WHERE"
                + " \"referenceType\" = 'pos_order_cancel' AND \"referenceId\" = ?").param(orderId).query(Long.class)
                .single()).as("each cancel returned its own issue").isEqualTo(2);
        assertIntegrity(p);
    }

    @Test
    @DisplayName("a pending job of an order cancelled before R4 is abandoned by the worker, never posted")
    void workerSkipsCancelledOrder() {
        String p = product("Legacy", false);
        receive(p, 10, 10, null);
        String orderId = order(line(p, 2));
        String job = enqueue(orderId);
        jdbc.sql("UPDATE \"Order\" SET status = 'cancelled', \"cancelReason\" = 'old cancel' WHERE id = ?")
                .param(orderId).update();
        process(job);
        assertThat(jobStatus(job)).isEqualTo("abandoned");
        assertThat(quant(p)).isEqualByComparingTo("10");
        assertIntegrity(p);
    }

    // ------------------------------------------------------------------ job key and timings

    @Test
    @DisplayName("one job per sale: sale:<orderId> whatever the trigger; an old-format job blocks a new one; the bill"
            + " takes the order's job instead of a second")
    void oneJobPerSale() {
        String p = product("Key", false);
        receive(p, 10, 10, null);
        String orderId = order(line(p, 2));
        String job = enqueue(orderId);
        assertThat(jdbc.sql("SELECT \"idempotencyKey\" FROM \"StockPostingJob\" WHERE id = ?").param(job)
                .query(String.class).single()).isEqualTo("sale:" + orderId);
        asOrgRun(() -> billing.enqueueStockPosting(orderId, null, null, "at_fulfillment_complete"));
        process(job);
        // the organisation is at_invoice: billing the order enqueues at_invoice, which must find the order's job
        JsonNode settled = money(200, "POST", "/pos/orders/" + orderId + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 200))));
        String invoiceId = settled.has("invoiceId") ? settled.get("invoiceId").asString()
                : jdbc.sql("SELECT \"invoiceId\" FROM \"Order\" WHERE id = ?").param(orderId).query(String.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockPostingJob\" WHERE \"orderId\" = ? OR \"invoiceId\" = ?")
                .params(orderId, invoiceId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"invoiceId\" FROM \"StockPostingJob\" WHERE id = ?").param(job).query(String.class)
                .single()).isEqualTo(invoiceId);
        assertThat(quant(p)).as("issued once").isEqualByComparingTo("8");

        String legacy = order(line(p, 1));
        jdbc.sql("INSERT INTO \"StockPostingJob\" (id, \"organizationId\", \"orderId\", status, \"postingTrigger\","
                + " \"idempotencyKey\") VALUES (?, ?, ?, 'pending', 'at_confirmation', ?)").params(
                        UUID.randomUUID().toString(), demoOrg, legacy, "at_confirmation:" + legacy).update();
        asOrgRun(() -> billing.enqueueStockPosting(legacy, null, null, "at_payment"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockPostingJob\" WHERE \"orderId\" = ?").param(legacy)
                .query(Long.class).single()).as("the old-format job counts").isEqualTo(1);
        cancel(legacy);
        assertIntegrity(p);
    }

    @Test
    @DisplayName("order-level timings can no longer be selected; an organisation already on one keeps it, with a"
            + " warning")
    void orderTimingsDeprecated() {
        for (String timing : List.of("at_confirmation", "at_fulfillment_complete")) {
            Res r = as("PUT", "/settings/inventory.stockPostingTiming", Map.of("value", timing));
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).startsWith("Setting 'inventory.stockPostingTiming' value '"
                    + timing + "' can no longer be selected:");
        }
        ok(200, "PUT", "/settings/inventory.stockPostingTiming", Map.of("value", "at_payment"));
        try {
            jdbc.sql("UPDATE \"Setting\" SET value = '\"at_confirmation\"'::jsonb WHERE \"organizationId\" = ? AND key ="
                    + " 'inventory.stockPostingTiming' AND \"scopeType\" = 'organization'").param(demoOrg).update();
            resolver.invalidate(demoOrg);
            String[] effective = new String[1];
            asOrgRun(() -> effective[0] = billing.stockPostingTiming());
            assertThat(effective[0]).as("a stored deprecated value keeps working").isEqualTo("at_confirmation");
            JsonNode row = null;
            for (JsonNode s : ok(200, "GET", "/settings/effective?group=inventory", null)) {
                if ("inventory.stockPostingTiming".equals(s.get("key").asString())) {
                    row = s;
                }
            }
            assertThat(row).isNotNull();
            assertThat(row.get("value").asString()).isEqualTo("at_confirmation");
            assertThat(row.get("warning").asString()).startsWith("At confirmation is deprecated");
        } finally {
            ok(200, "PUT", "/settings/inventory.stockPostingTiming", Map.of("value", "at_invoice"));
            resolver.invalidate(demoOrg);
        }
    }
}
