package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.InventoryIntegrityService;
import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityCheck;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR T09 (R7, failed stock-posting job recovery; the rehearsal script is {@code scripts/ipr/rehearse-failed-job.md}).
 * A sale with one good line and one forced failing line (an inventory-tracked menu item without a recipe) goes through
 * a runner that dies mid-drain (its claim left behind, the good line already committed), the stale-claim takeover, the
 * failure after the last attempt, and then either a Posting Monitor retry after the cause is fixed or an abandon. Every
 * line is issued exactly once (on-hand, issue ledger rows), the line's exception closes when the line posts, and the
 * integrity checks find nothing on the products involved. Test-only: the engine is driven through its public entry
 * points and the monitor routes.
 */
class PosStockPostingRecoveryIT extends ApiTestSupport {

    @Autowired
    PosInvoiceService billing;

    @Autowired
    InventoryIntegrityService integrity;

    String warehouseId;
    String adminId;

    @BeforeAll
    void fixtures() {
        warehouseId = pinPosWarehouse();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    <T> T asOrg(Supplier<T> body) {
        return TenantContext.run(TenantStore.organization(demoOrg), body);
    }

    void drain(String jobId) {
        TenantContext.run(TenantStore.organization(demoOrg), () -> billing.processStockPostingJob(jobId));
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    String stockProduct(String code) {
        String id = ok(201, "POST", "/products", Map.of("code", code, "name", code, "productType", "stockable",
                "trackInventory", true, "costingMethod", "AVCO", "costPrice", 0, "salesPrice", 0)).get("id").asString();
        // Through the document route, so the receipt is valued and posted to the GL like real stock (the GL integrity
        // checks then judge the sale's COGS, not a fixture shortcut).
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", warehouseId, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", id, "quantity", 100, "unitCost", 10))));
        return id;
    }

    String insert(String table, Map<String, Object> cols) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", id);
        all.putAll(cols);
        String names = String.join(", ", all.keySet().stream().map(k -> "\"" + k + "\"").toList());
        String marks = String.join(", ", all.keySet().stream().map(k -> "?").toList());
        jdbc.sql("INSERT INTO \"" + table + "\" (" + names + ") VALUES (" + marks + ")")
                .params(List.copyOf(all.values()))
                .update();
        return id;
    }

    static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    /** An inventory-tracked menu item without a recipe: its line fails on every attempt until a recipe is added. */
    String recipeLessMenuItem() {
        return insert("MenuItem", Map.of("organizationId", demoOrg, "name", "Recovery latte " + suffix(),
                "isInventoryTracked", true, "updatedAt", now()));
    }

    /** A billed order (product line 1, menu line 2) and its posting job, still pending. */
    String job(String productId, int productQty, String menuItemId, int menuQty, int maxAttempts) {
        String orderId = insert("Order", Map.of("organizationId", demoOrg, "orderNumber", "ORD-R7-" + UUID.randomUUID(),
                "status", "closed", "updatedAt", now()));
        insert("OrderItem", Map.of("organizationId", demoOrg, "orderId", orderId, "description", "good line",
                "productId", productId, "quantity", productQty, "lineNumber", 1, "updatedAt", now()));
        insert("OrderItem", Map.of("organizationId", demoOrg, "orderId", orderId, "description", "failing line",
                "menuItemId", menuItemId, "quantity", menuQty, "lineNumber", 2, "updatedAt", now()));
        return insert("StockPostingJob", Map.of("organizationId", demoOrg, "orderId", orderId, "invoiceId", orderId,
                "invoiceNumber", "INV-R7-" + orderId.substring(0, 8), "status", "pending", "maxAttempts", maxAttempts,
                "idempotencyKey", "at_invoice:" + orderId));
    }

    Map<String, Object> jobRow(String jobId) {
        return jdbc.sql("SELECT status, attempts, \"claimToken\", \"postedLineKeys\"::text AS keys FROM"
                + " \"StockPostingJob\" WHERE id = ?").param(jobId).query().singleRow();
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(demoOrg, productId).query(BigDecimal.class).single();
    }

    long issueRows(String productId) {
        return jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"quantityChange\" < 0").params(demoOrg, productId).query(Long.class).single();
    }

    List<String> lineExceptions(String menuItemId) {
        return jdbc.sql("SELECT status::text FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                + " \"menuItemId\" = ? ORDER BY \"createdAt\"").params(demoOrg, menuItemId).query(String.class).list();
    }

    /** A runner claimed the job and died: its token stays, the claim ages past the takeover threshold. */
    void crashedRunner(String jobId, long claimAgeSeconds) {
        jdbc.sql("UPDATE \"StockPostingJob\" SET status = 'processing', \"claimToken\" = 'dead-runner',"
                + " \"claimedAt\" = ?"
                + " WHERE id = ?").params(Timestamp.from(Instant.now().minusSeconds(claimAgeSeconds)), jobId).update();
    }

    /** No integrity check (DEMO may carry other suites' findings) lists any of {@code productIds}. */
    void assertIntegrityCleanFor(String... productIds) {
        for (IntegrityCheck c : asOrg(() -> integrity.run(null)).failing()) {
            String rows = String.valueOf(c.rows());
            for (String p : productIds) {
                assertThat(rows).as("integrity check " + c.key() + " lists " + p).doesNotContain(p);
            }
        }
    }

    @Test
    @DisplayName("crash mid-drain + failing line: takeover, failed, fixed, monitor retry; every line issued once")
    void crashThenRetry() {
        String good = stockProduct("R7-GOOD-" + suffix());
        String ingredient = stockProduct("R7-ING-" + suffix());
        String menu = recipeLessMenuItem();
        String jobId = job(good, 5, menu, 2, 2);

        // Attempt 1: the good line commits with its key, the menu line fails and opens one exception.
        drain(jobId);
        assertThat(jobRow(jobId)).containsEntry("status", "pending").containsEntry("attempts", 1);
        assertThat(onHand(good)).isEqualByComparingTo("95");
        assertThat(lineExceptions(menu)).containsExactly("open");

        // A second runner claims the job and dies mid-drain. While its claim is fresh nobody takes over.
        crashedRunner(jobId, 5);
        drain(jobId);
        assertThat(jobRow(jobId)).containsEntry("status", "processing").containsEntry("claimToken", "dead-runner");

        // Once the claim is stale the next drain takes over: the good line is not issued again; the last attempt fails.
        crashedRunner(jobId, 120);
        drain(jobId);
        assertThat(jobRow(jobId)).containsEntry("status", "failed").containsEntry("attempts", 2);
        assertThat(onHand(good)).isEqualByComparingTo("95");
        assertThat(issueRows(good)).isEqualTo(1);
        assertThat(lineExceptions(menu)).containsExactly("open");

        // The operator retries it from the Posting Monitor: back to pending, attempts reset.
        Res early = as("POST", "/pos/posting-monitor/jobs/" + jobId + "/retry", null);
        assertThat(early.status()).as(early.raw().body()).isEqualTo(201);
        assertThat(early.body().get("status").asString()).isEqualTo("pending");

        // The operator fixes the cause (adds the recipe), then the next drain posts only the missing line.
        insert("MenuProduct", Map.of("organizationId", demoOrg, "menuItemId", menu, "productId", ingredient,
                "quantity", BigDecimal.ONE));
        drain(jobId);
        assertThat(jobRow(jobId)).containsEntry("status", "done");
        assertThat(onHand(good)).isEqualByComparingTo("95");
        assertThat(onHand(ingredient)).isEqualByComparingTo("98");
        assertThat(issueRows(good)).isEqualTo(1);
        assertThat(issueRows(ingredient)).isEqualTo(1);
        assertThat(lineExceptions(menu)).as("closed by the posting of its line").containsExactly("resolved");

        // Replays change nothing.
        drain(jobId);
        Res again = as("POST", "/pos/posting-monitor/jobs/" + jobId + "/retry", null);
        assertThat(again.status()).isEqualTo(400);
        assertThat(onHand(good)).isEqualByComparingTo("95");
        assertThat(onHand(ingredient)).isEqualByComparingTo("98");
        assertIntegrityCleanFor(good, ingredient);
    }

    @Test
    @DisplayName("abandon from the Posting Monitor: never claimed again, posted lines stay posted once")
    void failedThenAbandoned() {
        String good = stockProduct("R7-AB-" + suffix());
        String ingredient = stockProduct("R7-ABI-" + suffix());
        String menu = recipeLessMenuItem();
        String jobId = job(good, 3, menu, 1, 1);

        drain(jobId);
        assertThat(jobRow(jobId)).containsEntry("status", "failed");
        assertThat(onHand(good)).isEqualByComparingTo("97");

        JsonNode abandoned = ok(201, "POST", "/pos/posting-monitor/jobs/" + jobId + "/abandon", Map.of("note",
                "R7 rehearsal: stock corrected by adjustment"));
        assertThat(abandoned.get("status").asString()).isEqualTo("abandoned");
        assertThat(abandoned.get("exceptionsDiscarded").asInt()).isEqualTo(1);
        assertThat(lineExceptions(menu)).containsExactly("discarded");

        // Even with the cause fixed, an abandoned job is never claimed or run again.
        insert("MenuProduct", Map.of("organizationId", demoOrg, "menuItemId", menu, "productId", ingredient,
                "quantity", BigDecimal.ONE));
        drain(jobId);
        assertThat(jobRow(jobId)).containsEntry("status", "abandoned");
        assertThat(onHand(good)).isEqualByComparingTo("97");
        assertThat(onHand(ingredient)).isEqualByComparingTo("100");
        assertThat(issueRows(good)).isEqualTo(1);
        assertThat(issueRows(ingredient)).isZero();
        assertIntegrityCleanFor(good, ingredient);
    }
}
