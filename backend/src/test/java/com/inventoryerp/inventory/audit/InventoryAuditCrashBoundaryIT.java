package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockPostingService.IssuePosting;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Crash boundaries and period lock: {@code inv-audit-crash-boundaries.spec.ts} (H3, H8, crash D/E),
 * {@code inv-audit-period-lock.spec.ts} (H9) and the drain cases of {@code inv-audit-pos-lifecycle.spec.ts} (INV-004 /
 * INV-005 / INV-028 / INV-INVARIANT-25, on hand-built billed orders + posting jobs like the crash spec). Failures are
 * injected without mocks: a schema-owner trigger raises at one exact write inside the application's transaction (a
 * later document line, the GL entry, the job completion). Nothing may half-commit — quantities, ledger rows, journal
 * entries and {@code stock.*} events are all unchanged — and the retry converges on exactly one outcome.
 */
class InventoryAuditCrashBoundaryIT extends InventoryAuditSupport {

    @Autowired
    PosInvoiceService billing;

    @Autowired
    StockPostingService stockPosting;

    String stocked(String code, int qty, int cost) {
        String p = product(code, Map.of("costPrice", cost));
        receive(p, qty, cost);
        return p;
    }

    /** A billed order plus its already-claimed (stale) posting job (Nest {@code makeJob}). Returns the job id. */
    String job(List<Map<String, Object>> lines) {
        String orderId = insert("Order", Map.of("organizationId", org, "orderNumber", "AUD-" + UUID.randomUUID(),
                "status", "closed", "updatedAt", now()));
        int n = 0;
        for (Map<String, Object> l : lines) {
            Map<String, Object> item = new LinkedHashMap<>(Map.of("organizationId", org, "orderId", orderId,
                    "description", "line-" + (++n), "lineNumber", n, "updatedAt", now()));
            item.putAll(l);
            insert("OrderItem", item);
        }
        return insert("StockPostingJob", Map.of("organizationId", org, "orderId", orderId, "invoiceId", orderId,
                "invoiceNumber", "INV-" + orderId, "status", "processing", "idempotencyKey", "at_invoice:" + orderId,
                "claimToken", "audit-stale", "claimedAt", Timestamp(Instant.now().minusSeconds(120))));
    }

    static java.sql.Timestamp Timestamp(Instant i) {
        return java.sql.Timestamp.from(i);
    }

    String menuItem(String name) {
        return insert("MenuItem", Map.of("organizationId", org, "name", name + " " + UUID.randomUUID(),
                "isInventoryTracked", true, "updatedAt", now()));
    }

    void recipe(String menuItemId, String productId, int qty) {
        insert("MenuProduct", Map.of("organizationId", org, "menuItemId", menuItemId, "productId", productId,
                "quantity", qty));
    }

    /** The worker's call: tenant scope only, no surrounding transaction (the job opens its own). */
    void process(String jobId) {
        TenantContext.run(TenantStore.organization(org), () -> billing.processStockPostingJob(jobId));
    }

    Map<String, Object> jobRow(String jobId) {
        return jdbc.sql("SELECT status::text AS status, \"lastError\", \"invoiceId\" FROM \"StockPostingJob\" WHERE id = ?")
                .param(jobId).query().singleRow();
    }

    long issueRows(String productId) {
        return count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                + " type = 'issue'", org, productId);
    }

    // ------------------------------------------------------------------ inv-audit-crash-boundaries.spec.ts

    @Test
    @DisplayName("H3: a repeated COGS posting for the same source replays one journal entry (posting key)")
    void repeatedIssuePostingReplays() {
        String p = stocked("H3-COGS", 100, 10);
        String source = "H3-FIXED-SOURCE-" + UUID.randomUUID();
        IssuePosting posting = new IssuePosting(p, main, null, new BigDecimal("5"), Instant.now(), "pos_invoice", source,
                null, "audit duplicate probe", "STOCK_OUT", null);
        asOrg(() -> stockPosting.postIssue(posting));
        asOrg(() -> stockPosting.postIssue(posting));
        List<String> keys = jdbc.sql("SELECT \"postingKey\" FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'pos_invoice' AND \"sourceId\" = ?").params(org, source).query(String.class).list();
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst()).isNotBlank();
    }

    @Test
    @DisplayName("H8: a job with a failing line is not done; once the cause is fixed the retry relieves the stock")
    void failingLineStaysRetryable() {
        String good = stocked("H8-GOOD", 100, 10);
        String broken = menuItem("H8 Recipe-less Item");
        String jobId = job(List.of(Map.of("productId", good, "quantity", 5), Map.of("menuItemId", broken, "quantity", 3)));
        process(jobId);
        assertThat(count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND \"menuItemId\" = ?",
                org, broken)).isPositive();
        assertThat(jobRow(jobId).get("status")).isNotEqualTo("done");
        assertThat(onHand(good, main)).isEqualByComparingTo("95");

        // H8-B: give the item a recipe — the cause is fixed — and run the worker again.
        String probe = menuItem("H8 Retry Probe");
        String retryJob = job(List.of(Map.of("menuItemId", probe, "quantity", 2)));
        process(retryJob);
        assertThat(jobRow(retryJob).get("status")).isNotEqualTo("done");
        String ingredient = stocked("H8-ING", 50, 4);
        recipe(probe, ingredient, 3);
        process(retryJob);
        assertThat(onHand(ingredient, main)).isEqualByComparingTo("44");
        assertThat(jobRow(retryJob).get("status")).isEqualTo("done");
        // the good line of the first job was posted once and is not posted again by a retry
        process(jobId);
        assertThat(onHand(good, main)).isEqualByComparingTo("95");
        assertThat(issueRows(good)).isEqualTo(1);
    }

    @Test
    @DisplayName("Crash D/E: a failure at job completion keeps the job pending; the retry never issues twice")
    void crashAtJobCompletion() throws Exception {
        String p = stocked("CRASH-DE", 100, 10);
        String jobId = job(List.of(Map.of("productId", p, "quantity", 9)));
        String invoiceId = (String) jobRow(jobId).get("invoiceId");
        try (AutoCloseable fault = injectFault("StockPostingJob", "UPDATE", "id", jobId, "status", "done")) {
            process(jobId);
        }
        // Each line posts in its own transaction (ADR-S072): a line that posted stays posted, the job stays
        // retryable and records it, so the retry posts only what is missing.
        Map<String, Object> crashed = jobRow(jobId);
        assertThat(crashed.get("status")).isEqualTo("pending");
        assertThat(String.valueOf(crashed.get("lastError"))).contains("AUDIT: injected crash");
        assertThat(issueRows(p)).isLessThanOrEqualTo(1);

        process(jobId);
        assertThat(onHand(p, main)).isEqualByComparingTo("91");
        assertThat(issueRows(p)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?", org,
                invoiceId)).isEqualTo(1);
        assertThat(jobRow(jobId).get("status")).isEqualTo("done");
    }

    // ------------------------------------------------------------------ document crash boundaries

    /** Of two products, the one the engine posts last (lines run in productId order, deviation 3). */
    static String later(String a, String b) {
        return a.compareTo(b) > 0 ? a : b;
    }

    @Test
    @DisplayName("stock-out: a failing later line leaves no stock, ledger, journal or event behind; the retry posts once")
    void stockOutLaterLineCrash() throws Exception {
        String a = stocked("CB-SO-A", 10, 2);
        String b = stocked("CB-SO-B", 10, 3);
        JsonNode doc = okA(200, "POST", "/inventory/stock-outs", Map.of("locationId", main, "responsibleById",
                auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", a, "qty", 2),
                        Map.of("productId", b, "qty", 3))));
        String id = doc.get("id").asString();
        String code = doc.get("outCode").asString();
        long ledgerA = ledgerRows(a);
        long ledgerB = ledgerRows(b);
        long entries = journalEntries();
        long events = stockEvents(a) + stockEvents(b);
        try (AutoCloseable fault = injectFault("InventoryLedger", "INSERT", "productId", later(a, b), null, null)) {
            assertThat(api("POST", "/inventory/stock-outs/" + id + "/approve", null).status()).isGreaterThanOrEqualTo(400);
        }
        assertThat(onHand(a, main)).isEqualByComparingTo("10");
        assertThat(onHand(b, main)).isEqualByComparingTo("10");
        assertThat(ledgerRows(a)).isEqualTo(ledgerA);
        assertThat(ledgerRows(b)).isEqualTo(ledgerB);
        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(stockEvents(a) + stockEvents(b)).isEqualTo(events);
        assertThat(jdbc.sql("SELECT status::text FROM \"StockOut\" WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo(doc.get("status").asString());

        okA(200, "POST", "/inventory/stock-outs/" + id + "/approve", null);
        assertThat(onHand(a, main)).isEqualByComparingTo("8");
        assertThat(onHand(b, main)).isEqualByComparingTo("7");
        assertThat(count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?", org,
                code)).isPositive();
    }

    @Test
    @DisplayName("transfer: a failure on the receiving leg keeps both locations and the ledger untouched")
    void transferReceivingLegCrash() throws Exception {
        String p = stocked("CB-TRF", 12, 5);
        JsonNode doc = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt,
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                        "qtyRequested", 4))));
        String id = doc.get("id").asString();
        long ledger = ledgerRows(p);
        long events = stockEvents(p);
        try (AutoCloseable fault = injectFault("InventoryLedger", "INSERT", "type", "transfer_in", "productId", p)) {
            assertThat(api("POST", "/inventory/transfers/" + id + "/approve", null).status()).isGreaterThanOrEqualTo(400);
        }
        assertThat(onHand(p, main)).isEqualByComparingTo("12");
        assertThat(onHand(p, alt)).isEqualByComparingTo("0");
        assertThat(ledgerRows(p)).isEqualTo(ledger);
        assertThat(stockEvents(p)).isEqualTo(events);
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        assertThat(onHand(p, alt)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("direct stock-in: a GL failure after the ledger rows rolls everything back; the same key then posts")
    void directStockInGlCrash() throws Exception {
        String a = product("CB-DSI-A");
        String b = product("CB-DSI-B");
        Map<String, String> headers = Map.of("Authorization", "Bearer " + auditToken, "Idempotency-Key",
                UUID.randomUUID().toString());
        Map<String, Object> body = direct(main, List.of(item(a, 3, 7), item(b, 2, 11)));
        long entries = journalEntries();
        try (AutoCloseable fault = injectFault("JournalEntry", "INSERT", "organizationId", org, "sourceType",
                "direct_stock_in")) {
            assertThat(call("POST", "/inventory/direct-stock/in", body, headers).status()).isGreaterThanOrEqualTo(400);
        }
        assertThat(onHand(a, main)).isEqualByComparingTo("0");
        assertThat(onHand(b, main)).isEqualByComparingTo("0");
        assertThat(ledgerRows(a) + ledgerRows(b)).isZero();
        assertThat(journalEntries()).isEqualTo(entries);
        assertThat(stockEvents(a) + stockEvents(b)).isZero();
        // A request that failed and committed nothing releases its key (IdempotencyService), so the same key
        // retries: it posts once, and a replay with that key moves nothing more.
        Res retry = call("POST", "/inventory/direct-stock/in", body, headers);
        assertThat(retry.status()).as(retry.raw().body()).isEqualTo(200);
        Map<String, String> fresh = headers;
        long posted = journalEntries();
        assertThat(posted).isGreaterThan(entries);
        assertThat(call("POST", "/inventory/direct-stock/in", body, fresh).status()).isEqualTo(200);
        assertThat(onHand(a, main)).isEqualByComparingTo("3");
        assertThat(onHand(b, main)).isEqualByComparingTo("2");
        assertThat(journalEntries()).isEqualTo(posted);
    }

    @Test
    @DisplayName("count submit: a failure on a later line leaves the count in draft and stock unchanged")
    void countSubmitCrash() throws Exception {
        String loc = location("CB-CNT-" + UUID.randomUUID().toString().substring(0, 6), "Crash count", "warehouse");
        String a = product("CB-CNT-A", Map.of("costPrice", 2));
        String b = product("CB-CNT-B", Map.of("costPrice", 2));
        receive(a, loc, 10, 2, null);
        receive(b, loc, 10, 2, null);
        JsonNode session = okA(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot",
                "scopeProductIds", List.of(a, b)));
        String id = session.get("id").asString();
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (JsonNode l : session.get("lines")) {
            lines.add(Map.of("lineId", l.get("id").asString(), "countedQty", 6, "reason", "breakage"));
        }
        okA(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", lines));
        try (AutoCloseable fault = injectFault("InventoryLedger", "INSERT", "productId", later(a, b), null, null)) {
            assertThat(api("POST", "/inventory/counts/" + id + "/submit", Map.of()).status()).isGreaterThanOrEqualTo(400);
        }
        assertThat(onHand(a, loc)).isEqualByComparingTo("10");
        assertThat(onHand(b, loc)).isEqualByComparingTo("10");
        assertThat(jdbc.sql("SELECT status::text FROM \"InventoryCountSession\" WHERE id = ?").param(id)
                .query(String.class).single()).isEqualTo("draft");
        assertThat(okA(200, "POST", "/inventory/counts/" + id + "/submit", Map.of()).get("status").asString())
                .isEqualTo("submitted");
        assertThat(onHand(a, loc)).isEqualByComparingTo("6");
        assertThat(onHand(b, loc)).isEqualByComparingTo("6");
    }

    // ------------------------------------------------------------------ inv-audit-period-lock.spec.ts (H9)

    void lockBooksThroughTomorrow(boolean locked) {
        jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = " + (locked ? "now() + interval '1 day'" : "NULL")
                + " WHERE id = ?").param(org).update();
    }

    @Test
    @DisplayName("H9: valued and zero-value issues and an unvalued receipt are all refused in a locked period")
    void periodLockProtectsStock() {
        String valued = stocked("LOCK-VALUED", 100, 10);
        String zero = product("LOCK-ZEROCOST");
        receive(zero, 100, 0);
        String bare = product("LOCK-BARE-RECEIPT");
        lockBooksThroughTomorrow(true);
        try {
            assertThatThrownBy(() -> issue(valued, main, 5, null)).hasMessageContaining("locked");
            assertThat(onHand(valued, main)).isEqualByComparingTo("100");

            long ledger = ledgerRows(zero);
            long entries = journalEntries();
            assertThatThrownBy(() -> issue(zero, main, 30, null)).hasMessageContaining("locked");
            assertThat(onHand(zero, main)).isEqualByComparingTo("100");
            assertThat(ledgerRows(zero)).isEqualTo(ledger);
            assertThat(journalEntries()).isEqualTo(entries);

            assertThatThrownBy(() -> asOrg(() -> stock.receive(StockMove.of(bare, main, new BigDecimal("40"))
                    .unitCost(new BigDecimal("3")).source("audit_bare", "bare-1")))).hasMessageContaining("locked");
            assertThat(onHand(bare, main)).isEqualByComparingTo("0");
        } finally {
            lockBooksThroughTomorrow(false);
        }
    }

    // ------------------------------------------------------------------ inv-audit-pos-lifecycle.spec.ts

    @Test
    @DisplayName("INV-004/005/028/INVARIANT-25: the drain relieves products and recipes once, posts COGS, reconciles")
    void posDrainLifecycle() {
        // INV-004 / INV-007: a stockable product
        String direct = stocked("SELL-DIRECT", 100, 10);
        BigDecimal cogs0 = balance("cogs");
        String directJob = job(List.of(Map.of("productId", direct, "quantity", 4)));
        process(directJob);
        assertThat(onHand(direct, main)).isEqualByComparingTo("96");
        assertThat(balance("cogs").subtract(cogs0)).isEqualByComparingTo("40");
        assertThat(jobRow(directJob).get("status")).isEqualTo("done");
        assertThat(jobRow(directJob).get("lastError")).isNull();
        assertThat(count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND \"productId\" = ?",
                org, direct)).isZero();

        // INV-005: a recipe menu item explodes its BOM once
        String bun = stocked("SELL-BUN", 200, 2);
        String patty = stocked("SELL-PATTY", 200, 15);
        String burger = menuItem("Audit Burger");
        recipe(burger, bun, 1);
        recipe(burger, patty, 2);
        BigDecimal cogs1 = balance("cogs");
        String burgerJob = job(List.of(Map.of("menuItemId", burger, "quantity", 10)));
        process(burgerJob);
        assertThat(onHand(bun, main)).isEqualByComparingTo("190");
        assertThat(onHand(patty, main)).isEqualByComparingTo("180");
        assertThat(balance("cogs").subtract(cogs1)).isEqualByComparingTo("320");

        // INV-028: re-running the drain does not consume a second time
        String idem = stocked("SELL-IDEMP", 50, 8);
        String idemJob = job(List.of(Map.of("productId", idem, "quantity", 6)));
        process(idemJob);
        process(idemJob);
        process(idemJob);
        assertThat(onHand(idem, main)).isEqualByComparingTo("44");
        assertThat(issueRows(idem)).isEqualTo(1);

        // INV-INVARIANT-25: units sold per product = inventory consumed by the drain, independently aggregated
        for (String jobId : List.of(directJob, idemJob)) {
            String invoiceId = (String) jobRow(jobId).get("invoiceId");
            for (Map<String, Object> sold : jdbc.sql("SELECT \"productId\", SUM(quantity) AS qty FROM \"OrderItem\" WHERE"
                    + " \"orderId\" = ? AND \"productId\" IS NOT NULL GROUP BY \"productId\"").param(invoiceId).query()
                    .listOfRows()) {
                BigDecimal consumed = jdbc.sql("SELECT COALESCE(-SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\""
                        + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"referenceId\" = ? AND type = 'issue'")
                        .params(org, sold.get("productId"), invoiceId).query(BigDecimal.class).single();
                assertThat(consumed).isEqualByComparingTo((BigDecimal) sold.get("qty"));
            }
        }
    }
}
