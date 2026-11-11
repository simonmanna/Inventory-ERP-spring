package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PeriodCloseService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.application.PosStockPostingService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Async stock posting (port of {@code test/integration/stock-posting-job.spec.ts}): F-COGS exception for a
 * recipe-less tracked menu item, exactly-once issue under a reclaim race, the period-close guard, and the recipe
 * snapshot taken at enqueue time, drained by the shift-close hook.
 */
class PosStockPostingIT extends ApiTestSupport {

    @Autowired
    PosInvoiceService billing;

    @Autowired
    PosStockPostingService monitor;

    @Autowired
    StockService stock;

    @Autowired
    PeriodCloseService periodClose;

    String warehouseId;
    String sessionId;
    String registerId;

    @org.junit.jupiter.api.AfterAll
    void cleanup() {
        forceCloseSessions(List.of(registerId));
    }

    <T> T asOrg(Supplier<T> body) {
        return TenantContext.run(TenantStore.organization(demoOrg), body);
    }

    void asOrgRun(Runnable body) {
        TenantContext.run(TenantStore.organization(demoOrg), body);
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(demoOrg, productId).query(BigDecimal.class).single();
    }

    @BeforeAll
    void fixtures() {
        warehouseId = pinPosWarehouse();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "SREG-" + suffix, "name", "Stock till"))
                .get("id").asString();
        Res opened = call("POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        sessionId = opened.body().get("id").asString();
    }

    String stockProduct(String code) {
        String id = ok(201, "POST", "/products", Map.of("code", code, "name", code, "productType", "stockable",
                "trackInventory", true, "costingMethod", "AVCO", "costPrice", 0, "salesPrice", 0)).get("id").asString();
        asOrg(() -> stock.receive(StockMove.of(id, warehouseId, BigDecimal.valueOf(100)).unitCost(BigDecimal.TEN)));
        return id;
    }

    String insert(String table, Map<String, Object> cols) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", id);
        all.putAll(cols);
        String names = String.join(", ", all.keySet().stream().map(k -> "\"" + k + "\"").toList());
        String marks = String.join(", ", all.keySet().stream().map(k -> "?").toList());
        jdbc.sql("INSERT INTO \"" + table + "\" (" + names + ") VALUES (" + marks + ")").params(all.values().stream()
                .map(v -> v instanceof List<?> l ? l.toArray(String[]::new) : v).toList()).update();
        return id;
    }

    /** A billed order + its already-claimed (stale) posting job. */
    String job(String productId, String menuItemId, int qty) {
        String orderId = insert("Order", Map.of("organizationId", demoOrg, "orderNumber", "ORD-T-" + UUID.randomUUID(),
                "status", "closed", "updatedAt", java.sql.Timestamp.from(java.time.Instant.now())));
        Map<String, Object> item = new LinkedHashMap<>(Map.of("organizationId", demoOrg, "orderId", orderId,
                "description", "line", "quantity", qty, "lineNumber", 1,
                "updatedAt", java.sql.Timestamp.from(java.time.Instant.now())));
        if (productId != null) {
            item.put("productId", productId);
        }
        if (menuItemId != null) {
            item.put("menuItemId", menuItemId);
        }
        insert("OrderItem", item);
        return insert("StockPostingJob", Map.of("organizationId", demoOrg, "orderId", orderId, "invoiceId", orderId,
                "invoiceNumber", "INV-" + orderId, "status", "processing", "idempotencyKey", "at_invoice:" + orderId,
                "claimToken", "stale-token", "claimedAt",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(120))));
    }

    /** The drained sale's COGS entry date and ledger notes for a job whose order was created at {@code saleAt}. */
    Map<String, Object> drainSaleAt(java.time.Instant saleAt) {
        String productId = stockProduct("DATED-" + UUID.randomUUID().toString().substring(0, 8));
        String jobId = job(productId, null, 2);
        String orderId = jdbc.sql("SELECT \"orderId\" FROM \"StockPostingJob\" WHERE id = ?").param(jobId)
                .query(String.class).single();
        jdbc.sql("UPDATE \"Order\" SET \"createdAt\" = ? WHERE id = ?")
                .params(java.sql.Timestamp.valueOf(saleAt.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()), orderId)
                .update();
        asOrgRun(() -> billing.processStockPostingJob(jobId));
        assertThat(jdbc.sql("SELECT status FROM \"StockPostingJob\" WHERE id = ?").param(jobId).query(String.class)
                .single()).isEqualTo("done");
        Map<String, Object> out = new LinkedHashMap<>(jdbc.sql("SELECT \"postingDate\" FROM \"JournalEntry\" WHERE"
                + " \"organizationId\" = ? AND \"sourceId\" = ? AND status <> 'draft' ORDER BY \"createdAt\" LIMIT 1")
                .params(demoOrg, orderId).query().singleRow());
        out.put("notes", jdbc.sql("SELECT notes FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"referenceId\" = ?").params(demoOrg, productId, orderId)
                .query(String.class).single());
        return out;
    }

    static java.time.Instant utc(Object ts) {
        return ((java.sql.Timestamp) ts).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

    @Test
    @DisplayName("a drained sale's stock and COGS are dated at the sale, not at drain time")
    void cogsDatedAtSale() {
        java.time.Instant saleAt = java.time.Instant.now().minus(java.time.Duration.ofHours(30))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Map<String, Object> posted = drainSaleAt(saleAt);
        assertThat(utc(posted.get("postingDate"))).isEqualTo(saleAt);
        assertThat(posted.get("notes") == null ? "" : posted.get("notes").toString()).doesNotContain("closed");
    }

    @Test
    @DisplayName("a sale dated in a closed period drains at today's date, noted on the ledger row")
    void cogsOfClosedPeriodSalePostsToday() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                        + " \"updatedAt\") VALUES (?, ?, ?, '1999-03-01', '1999-03-31 23:59:59.999', 'closed', now())")
                .params(UUID.randomUUID().toString(), demoOrg, "Closed 1999-03 " + suffix).update();
        java.time.Instant saleAt = java.time.Instant.parse("1999-03-15T10:00:00Z");
        java.time.Instant before = java.time.Instant.now().minusSeconds(5);
        Map<String, Object> posted = drainSaleAt(saleAt);
        assertThat(utc(posted.get("postingDate"))).isAfter(before);
        assertThat(posted.get("notes").toString()).contains("the sale date 1999-03-15 is in a closed or locked period");
    }

    @Test
    @DisplayName("F-COGS: a tracked menu item without a recipe raises one InventoryException and leaves the job pending")
    void recipeLessMenuItem() {
        String menuItemId = insert("MenuItem", Map.of("organizationId", demoOrg, "name", "Recipe-less Latte "
                + UUID.randomUUID(), "isInventoryTracked", true, "updatedAt",
                java.sql.Timestamp.from(java.time.Instant.now())));
        String jobId = job(null, menuItemId, 2);
        asOrgRun(() -> billing.processStockPostingJob(jobId));
        Map<String, Object> ex = jdbc.sql("SELECT reason FROM \"InventoryException\" WHERE \"organizationId\" = ?"
                + " AND \"menuItemId\" = ?").params(demoOrg, menuItemId).query().singleRow();
        assertThat(ex.get("reason").toString().toLowerCase()).contains("no recipe");
        Map<String, Object> done = jdbc.sql("SELECT status, \"lastError\" FROM \"StockPostingJob\" WHERE id = ?")
                .param(jobId).query().singleRow();
        assertThat(done.get("status")).isEqualTo("pending");
        assertThat(done.get("lastError").toString()).contains("need review");
        asOrgRun(() -> billing.processStockPostingJob(jobId));
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND \"menuItemId\" = ?")
                .params(demoOrg, menuItemId).query(Long.class).single()).isEqualTo(1);
        JsonNode counts = ok(200, "GET", "/pos/posting-monitor/counts", null);
        assertThat(counts.get("exceptions").get("open").asInt()).isPositive();
        JsonNode exceptions = ok(200, "GET", "/pos/posting-monitor/exceptions", null);
        String exceptionId = null;
        for (JsonNode e : exceptions) {
            if (menuItemId.equals(e.get("menuItemId").asString(null))) {
                exceptionId = e.get("id").asString();
            }
        }
        assertThat(exceptionId).isNotNull();
        ok(201, "POST", "/pos/posting-monitor/exceptions/" + exceptionId + "/assign", Map.of("assignedToId",
                jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single()));
        JsonNode resolved = ok(201, "POST", "/pos/posting-monitor/exceptions/" + exceptionId + "/resolve",
                Map.of("status", "resolved", "note", " Manual adjustment posted "));
        assertThat(resolved.get("status").asString()).isEqualTo("resolved");
        assertThat(as("POST", "/pos/posting-monitor/exceptions/" + exceptionId + "/resolve", Map.of("status",
                "discarded", "note", "again")).body().get("message").asString()).isEqualTo("Already resolved");
    }

    @Test
    @DisplayName("exactly once: two workers processing the same job issue stock once")
    void reclaimRace() {
        String productId = stockProduct("SPJ-RECLAIM-" + UUID.randomUUID().toString().substring(0, 6));
        String jobId = job(productId, null, 5);
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> asOrgRun(() -> billing.processStockPostingJob(jobId)));
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> asOrgRun(() -> billing.processStockPostingJob(jobId)));
        CompletableFuture.allOf(a, b).join();
        assertThat(onHand(productId)).isEqualByComparingTo("95");
        assertThat(jdbc.sql("SELECT status FROM \"StockPostingJob\" WHERE id = ?").param(jobId).query(String.class)
                .single()).isEqualTo("done");
        asOrgRun(() -> billing.processStockPostingJob(jobId));
        assertThat(onHand(productId)).isEqualByComparingTo("95");
        Res retryDone = as("POST", "/pos/posting-monitor/jobs/" + jobId + "/retry", null);
        assertThat(retryDone.body().get("message").asString())
                .isEqualTo("Only failed jobs can be retried (this one is done)");
    }

    @Test
    @DisplayName("a fiscal period cannot close while stock-posting jobs are queued in it")
    void periodCloseGuard() {
        String periodId = insert("FiscalPeriod", Map.of("organizationId", demoOrg, "name", "FY-GUARD-" + UUID.randomUUID(),
                "startDate", java.sql.Timestamp.valueOf("2001-01-01 00:00:00"),
                "endDate", java.sql.Timestamp.valueOf("2001-12-31 23:59:59"),
                "updatedAt", java.sql.Timestamp.from(java.time.Instant.now())));
        insert("StockPostingJob", Map.of("organizationId", demoOrg, "invoiceId", "guard-" + UUID.randomUUID(),
                "invoiceNumber", "INV-GUARD", "status", "pending", "createdAt",
                java.sql.Timestamp.valueOf("2001-06-15 00:00:00"), "idempotencyKey", "at_invoice:guard-" + UUID.randomUUID()));
        assertThatThrownBy(() -> asOrg(() -> periodClose.close(periodId))).hasMessageContaining("stock-posting job");
    }

    @Test
    @DisplayName("checkout snapshots the recipe; the shift drain posts it once")
    void recipeSnapshotAndDrain() {
        String ingredient = stockProduct("ING-" + UUID.randomUUID().toString().substring(0, 6));
        JsonNode item = ok(201, "POST", "/pos/menu/items", Map.of("name", "Recipe Burger " + UUID.randomUUID(),
                "basePrice", 5000, "ingredients", List.of(Map.of("productId", ingredient, "quantity", 10))));
        String menuItemId = item.get("id").asString();
        Res sale = call("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(Map.of(
                "menuItemId", menuItemId, "description", "Burger", "quantity", 2, "unitPrice", 5000)),
                "tenders", List.of(Map.of("method", "cash", "amount", 10000))),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(sale.status()).as(sale.raw().body()).isEqualTo(200);
        String invoiceId = sale.body().get("invoiceId").asString();
        Map<String, Object> job = jdbc.sql("SELECT id, status, \"recipeSnapshot\"::text AS snap FROM \"StockPostingJob\""
                + " WHERE \"invoiceId\" = ?").param(invoiceId).query().singleRow();
        assertThat(job.get("snap").toString()).contains(ingredient);
        // Recipe change after the sale must not change what this sale consumes.
        jdbc.sql("UPDATE \"MenuProduct\" SET quantity = 99 WHERE \"menuItemId\" = ?").param(menuItemId).update();
        if (!"done".equals(job.get("status"))) {
            asOrgRun(() -> monitor.drainSession(sessionId));
        }
        assertThat(jdbc.sql("SELECT status FROM \"StockPostingJob\" WHERE id = ?").param(job.get("id"))
                .query(String.class).single()).isEqualTo("done");
        assertThat(onHand(ingredient)).isEqualByComparingTo("80");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InvoiceItemRecipeIngredient\" WHERE \"invoiceId\" = ? AND \"productId\" = ?")
                .params(invoiceId, ingredient).query(Long.class).single()).isPositive();
        ok(200, "GET", "/pos/posting-monitor/jobs?status=done", null);
    }
}
