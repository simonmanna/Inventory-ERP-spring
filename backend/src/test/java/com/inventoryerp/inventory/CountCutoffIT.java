package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR 1b (count spec, T11 / X-1a..b, T12 / S1 S4 S7, freeze): a count compares with the book at its cut-off by
 * effective time; a submit first drains the location's sale stock postings; a sale made before the count but posted
 * after it gets exactly one linked count correction; a frozen location refuses manual moves but never sales; one draft
 * count per location; an adjustment waits while a count is open. Concurrency through {@code race} (40P01 fails).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CountCutoffIT extends InventoryAuditSupport {

    /** Reference type of a late count correction ({@code StockService.LATE_COUNT_CORRECTION}). */
    static final String LATE = "inventory_count_late_correction";

    @Autowired
    PosInvoiceService billing;

    String managerPin;

    String deviceToken;
    String deviceId;
    String sessionClient;
    int seq;

    /** A failed test must not leave a draft that blocks the next one (one draft per location). */
    @org.junit.jupiter.api.AfterEach
    void cancelDrafts() {
        asOrgRun(() -> jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'cancelled' WHERE \"organizationId\" = ?"
                + " AND status = 'draft'").param(org).update());
    }

    @BeforeAll
    void till() {
        JsonNode d = okA(201, "POST", "/sync/devices/register", Map.of("name", "Count tablet", "platform", "web"));
        deviceToken = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        String register = okA(201, "POST", "/cash-registers", Map.of("code", "CREG-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "Count till")).get("id").asString();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        okA(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
        sessionClient = "sess-" + UUID.randomUUID();
        JsonNode open = push(op("cash_session.open", Map.of("clientId", sessionClient, "cashRegisterId", register,
                "openingFloat", 0), Instant.now()));
        assertThat(open.get("status").asString()).as(open.toString()).isEqualTo("applied");
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> op(String type, Map<String, Object> payload, Instant occurredAt) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "op-" + UUID.randomUUID());
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", auditUserId);
        o.put("occurredAt", occurredAt.toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(Map<String, Object> op) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(op)),
                Map.of("X-Device-Token", deviceToken));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results").get(0);
    }

    /** An offline sale op of {@code qty} of {@code product}, made at {@code at} (not pushed). */
    Map<String, Object> saleOp(String product, int qty, Instant at) {
        return op("sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(), "cashSessionId", sessionClient,
                "lines", List.of(Map.of("productId", product, "description", "Item", "quantity", qty, "unitPrice", 100)),
                "tenders", List.of(Map.of("method", "cash", "amount", 100 * qty))), at);
    }

    /** Push an offline sale; returns its stock posting job id (left pending, as the worker is off in tests). */
    String sale(Map<String, Object> op) {
        JsonNode r = push(op);
        assertThat(r.get("status").asString()).as(r.toString()).isIn("applied", "replayed");
        String invoiceId = r.get("mapping").get("invoiceId").asString();
        return asOrg(() -> jdbc.sql("SELECT id FROM \"StockPostingJob\" WHERE \"organizationId\" = ? AND \"invoiceId\" = ?")
                .params(org, invoiceId).query(String.class).single());
    }

    /** A stockable product with a sales price (a POS sale of a zero-priced product posts a zero journal). */
    String sellable(String code) {
        return product(code, Map.of("salesPrice", 100, "costPrice", 5));
    }

    void runJob(String job) {
        TenantContext.run(TenantStore.user(org, auditUserId), () -> billing.processStockPostingJob(job));
    }

    String jobStatus(String job) {
        return asOrg(() -> jdbc.sql("SELECT status FROM \"StockPostingJob\" WHERE id = ?").param(job)
                .query(String.class).single());
    }

    JsonNode start(String loc, String type, List<String> products, boolean freeze) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locationId", loc);
        body.put("countType", type);
        body.put("scopeProductIds", products);
        if (freeze) {
            body.put("freezeLocation", true);
        }
        return okA(201, "POST", "/inventory/counts/start", body);
    }

    void save(JsonNode session, String product, int counted) {
        String lineId = null;
        for (JsonNode l : session.get("lines")) {
            if (product.equals(l.get("productId").asString())) {
                lineId = l.get("id").asString();
            }
        }
        okA(200, "PATCH", "/inventory/counts/" + session.get("id").asString() + "/draft", Map.of("lines",
                List.of(Map.of("lineId", lineId, "countedQty", counted, "reason", "counted"))));
    }

    JsonNode submit(JsonNode session) {
        return okA(200, "POST", "/inventory/counts/" + session.get("id").asString() + "/submit", Map.of());
    }

    long corrections(String product) {
        return asOrg(() -> jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"referenceType\" = ?").params(org, product, LATE)
                .query(Long.class).single());
    }

    long countAdjustments(String product) {
        return asOrg(() -> jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"referenceType\" = 'stock_adjustment' AND \"quantityChange\" <> 0")
                .params(org, product).query(Long.class).single());
    }

    static void pause() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ X-1a / X-1b

    @Test
    @DisplayName("X-1a (D1): a sale made during the count but still queued is drained at submit — no phantom loss")
    void submitDrainsQueuedSale() {
        String p = sellable("CC-DRAIN");
        receive(p, 10, 5);
        JsonNode session = start(main, "cycle", List.of(p), false);
        pause();
        String job = sale(saleOp(p, 1, Instant.now())); // sold before the shelf was counted; job still queued
        pause();
        save(session, p, 9);
        JsonNode submitted = submit(session);
        assertThat(jobStatus(job)).as("drained by the submit").isEqualTo("done");
        runJob(job); // the worker coming later finds nothing to do
        assertThat(onHand(p, main)).as("9 on the shelf, 9 in the book").isEqualByComparingTo("9");
        assertThat(countAdjustments(p)).as("no variance: the sale explains the difference").isZero();
        assertThat(submitted.get("status").asString()).isEqualTo("submitted");
        assertThat(quantInvariantBreaks()).isEmpty();
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("X-1a: a sale posting at the location that keeps failing blocks the submit (409) until it is resolved")
    void failedSaleBlocksSubmit() {
        // Sold in a sales unit that does not convert yet: its stock posting fails and is retried (SyncIT decision 1).
        String sack = insert("UnitOfMeasure", Map.of("organizationId", org, "code", "SACK-" + UUID.randomUUID()
                .toString().substring(0, 6), "name", "Sack", "category", "unit", "factor", 1, "ratio", 1,
                "roundingPrecision", 1, "updatedAt", now()));
        String p = product("CC-FAIL", Map.of("salesPrice", 100, "costPrice", 5, "salesUomId", sack));
        receive(p, 10, 5);
        JsonNode session = start(main, "cycle", List.of(p), false);
        String job = sale(saleOp(p, 1, Instant.now()));
        save(session, p, 9);
        Res refused = api("POST", "/inventory/counts/" + session.get("id").asString() + "/submit", Map.of());
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(409);
        assertThat(refused.body().get("message").asString()).isEqualTo("1 sale stock posting(s) at this location"
                + " are not done; resolve them in the Posting Monitor, then submit the count again");
        assertThat(jobStatus(job)).isIn("pending", "failed");
        assertThat(onHand(p, main)).isEqualByComparingTo("10");
        // The unit is configured (one sack = one unit): the next submit drains the sale and posts no variance.
        okA(200, "PATCH", "/products/" + p, Map.of("units", List.of(Map.of("uomId", sack, "factor", 1, "isPurchase",
                false, "isSales", true))));
        JsonNode ok = submit(session);
        assertThat(ok.get("status").asString()).isEqualTo("submitted");
        assertThat(jobStatus(job)).isEqualTo("done");
        assertThat(onHand(p, main)).as("10 received, 1 sold, 9 counted").isEqualByComparingTo("9");
        assertThat(countAdjustments(p)).isZero();
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("X-1b + R2: a sale made before the count, synced after it, gets exactly one correction — even pushed"
            + " and posted twice in parallel")
    void lateSaleCorrectedExactlyOnce() throws Exception {
        String p = sellable("CC-LATE");
        receive(p, 10, 5);
        Instant soldAt = Instant.now();
        pause();
        JsonNode session = start(main, "cycle", List.of(p), false);
        save(session, p, 9); // one already gone, sold on an offline till
        submit(session);
        assertThat(onHand(p, main)).isEqualByComparingTo("9");

        Map<String, Object> late = saleOp(p, 1, soldAt);
        List<Raced<String>> pushes = race(Duration.ofSeconds(60), List.<Callable<String>>of(() -> sale(late),
                () -> sale(late)));
        String job = pushes.stream().filter(Raced::ok).findFirst().orElseThrow().value();
        race(Duration.ofSeconds(60), List.<Callable<Void>>of(() -> {
            runJob(job);
            return null;
        }, () -> {
            runJob(job);
            return null;
        }));
        runJob(job);
        assertThat(jobStatus(job)).isEqualTo("done");
        assertThat(onHand(p, main)).as("sale −1, correction +1: the counted 9 stands").isEqualByComparingTo("9");
        assertThat(corrections(p)).as("exactly one correction").isEqualTo(1);
        JsonNode count = okA(200, "GET", "/inventory/counts/" + session.get("id").asString(), null);
        JsonNode lateRows = count.get("lateTransactions");
        assertThat(lateRows).as(String.valueOf(lateRows)).hasSize(1);
        assertThat(lateRows.get(0).get("status").asString()).isEqualTo("corrected");
        assertThat(quantInvariantBreaks()).isEmpty();
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("X-1a: a sale made after the count cut-off and posted after submit is not corrected (case D)")
    void saleAfterCutoffNotCorrected() {
        String p = sellable("CC-AFTER");
        receive(p, 10, 5);
        JsonNode session = start(main, "cycle", List.of(p), false);
        save(session, p, 10);
        pause();
        Map<String, Object> after = saleOp(p, 2, Instant.now());
        submit(session);
        runJob(sale(after));
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        assertThat(corrections(p)).isZero();
        JsonNode lateRows = okA(200, "GET", "/inventory/counts/" + session.get("id").asString(), null)
                .get("lateTransactions");
        assertThat(lateRows).as(String.valueOf(lateRows)).hasSize(1);
        assertThat(lateRows.get(0).get("status").asString()).isEqualTo("after_cutoff");
        assertIntegrityGreen();
    }

    // ------------------------------------------------------------------ freeze

    @Test
    @DisplayName("freeze: a frozen location refuses manual moves (409) but not sales; the count's own posting passes")
    void freezeRefusesManualMoves() {
        String p = sellable("CC-FRZ");
        receive(p, 10, 5);
        receive(p, alt, 10, 5, null);
        JsonNode session = start(main, "spot", List.of(p), true);
        Res manual = api("POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 1, null))));
        assertThat(manual.status()).as(manual.raw().body()).isEqualTo(409);
        assertThat(manual.body().get("message").asString()).startsWith("Location Main Store is frozen for count ");
        okA(200, "POST", "/inventory/direct-stock/out", direct(alt, List.of(item(p, 1, null))));
        runJob(sale(saleOp(p, 1, Instant.now()))); // sales are never blocked
        assertThat(onHand(p, main)).isEqualByComparingTo("9");
        save(session, p, 8);
        submit(session);
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        okA(200, "POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 1, null))));
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("R2: count submits on a frozen and a non-frozen location race sales and manual moves — no deadlock,"
            + " book consistent")
    void submitsRaceSales() throws Exception {
        String p = sellable("CC-RACE");
        receive(p, 20, 5);
        receive(p, alt, 20, 5, null);
        JsonNode frozen = start(main, "spot", List.of(p), true);
        JsonNode open = start(alt, "spot", List.of(p), false);
        save(frozen, p, 20);
        save(open, p, 20);
        pause();
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> submit(frozen));
        tasks.add(() -> submit(open));
        for (String loc : List.of(main, alt, main, alt)) {
            tasks.add(() -> issue(p, loc, 1, m -> m.source("pos_invoice", "race-" + UUID.randomUUID())));
        }
        tasks.add(() -> {
            Res r = api("POST", "/inventory/direct-stock/out", direct(alt, List.of(item(p, 1, null))));
            assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
            return r;
        });
        List<Raced<Object>> out = race(Duration.ofSeconds(90), tasks);
        for (Raced<Object> r : out) {
            assertThat(r.ok()).as(r.message()).isTrue();
        }
        assertThat(onHand(p, main)).as("20 counted, 2 sold after").isEqualByComparingTo("18");
        assertThat(onHand(p, alt)).as("20 counted, 2 sold and 1 moved after").isEqualByComparingTo("17");
        assertThat(quantInvariantBreaks()).isEmpty();
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ T12

    @Test
    @DisplayName("S4 + R2: two drafts raced on one location — one count opens, the other is refused (409)")
    void oneDraftPerLocation() throws Exception {
        String p = sellable("CC-S4");
        String loc = location("S4-" + UUID.randomUUID().toString().substring(0, 4), "S4 store", "warehouse");
        receive(p, loc, 5, 5, null);
        List<Raced<Res>> out = race(Duration.ofSeconds(60), List.<Callable<Res>>of(
                () -> api("POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot",
                        "scopeProductIds", List.of(p))),
                () -> api("POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "closing"))));
        List<Integer> statuses = out.stream().map(r -> r.value().status()).sorted().toList();
        assertThat(statuses).as(out.toString()).containsExactly(201, 409);
        long drafts = asOrg(() -> jdbc.sql("SELECT count(*) FROM \"InventoryCountSession\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND status = 'draft'").params(org, loc).query(Long.class).single());
        assertThat(drafts).isEqualTo(1);

        // S4: an adjustment is not approved while the count is open.
        String adj = okA(200, "POST", "/inventory/adjustments", Map.of("locationId", loc, "responsibleById",
                auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p, "qtyActual", 3))))
                .get("id").asString();
        Res refused = api("POST", "/inventory/adjustments/" + adj + "/approve", Map.of());
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(409);
        assertThat(refused.body().get("message").asString()).contains("has an open count");
    }

    @Test
    @DisplayName("S1: the default count type is cycle; an opening count only on a location with no history, once")
    void openingCountGated() {
        String p = sellable("CC-S1");
        String used = location("S1U-" + UUID.randomUUID().toString().substring(0, 4), "Used store", "warehouse");
        receive(p, used, 5, 5, null);
        Res noType = api("POST", "/inventory/counts/start", Map.of("locationId", used));
        assertThat(noType.status()).isEqualTo(400);
        assertThat(noType.body().get("message").asString()).isEqualTo("A cycle count needs a scope: pick categories or"
                + " products.");
        JsonNode cycle = okA(201, "POST", "/inventory/counts/start", Map.of("locationId", used, "scopeProductIds",
                List.of(p)));
        assertThat(cycle.get("countType").asString()).isEqualTo("cycle");
        okA(201, "POST", "/inventory/counts/" + cycle.get("id").asString() + "/cancel", Map.of());
        Res opening = api("POST", "/inventory/counts/start", Map.of("locationId", used, "countType", "opening"));
        assertThat(opening.status()).isEqualTo(400);
        assertThat(opening.body().get("message").asString()).startsWith("An opening count is only for a location with no"
                + " stock history");

        String fresh = location("S1F-" + UUID.randomUUID().toString().substring(0, 4), "Fresh store", "warehouse");
        JsonNode first = start(fresh, "opening", List.of(p), false);
        save(first, p, 4);
        submit(first);
        Res again = api("POST", "/inventory/counts/start", Map.of("locationId", fresh, "countType", "opening"));
        assertThat(again.status()).isEqualTo(400);
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("M-1 / X-1c: a device count is measured at the device time; a later move is carried forward by"
            + " effective time")
    void deviceCountAtDeviceTime() {
        String p = sellable("CC-DEV");
        receive(p, 10, 5);
        Instant countedAt = Instant.now();
        pause();
        receive(p, 5, 5);
        JsonNode r = push(op("stock.count", Map.of("locationId", main, "lines", List.of(Map.of("productId", p,
                "countedQty", 8))), countedAt));
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        assertThat(onHand(p, main)).as("8 counted when 10 were on hand, 5 received later").isEqualByComparingTo("13");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("late correction: the correction row links the count variance row and the count line")
    void correctionLinks() {
        String p = sellable("CC-LINK");
        receive(p, 10, 5);
        Instant movedAt = Instant.now();
        pause();
        JsonNode session = start(main, "spot", List.of(p), false);
        save(session, p, 7);
        submit(session);
        // a late offline stock-out of 3 that happened before the count (M-1: it moves at the device time)
        JsonNode out = push(op("stock.out", Map.of("locationId", main, "responsibleById", auditUserId, "approvedById",
                auditUserId, "approverPin", managerPin, "items", List.of(Map.of("productId", p, "quantity", 3))),
                movedAt));
        assertThat(out.get("status").asString()).as(out.toString()).isEqualTo("applied");
        assertThat(onHand(p, main)).isEqualByComparingTo("7");
        Map<String, Object> k = asOrg(() -> jdbc.sql("SELECT k.\"quantityChange\", k.\"reversesLedgerId\", k.\"sourceLineId\","
                + " v.\"referenceType\" AS vtype FROM \"InventoryLedger\" k JOIN \"InventoryLedger\" v ON v.id ="
                + " k.\"reversesLedgerId\" WHERE k.\"organizationId\" = ? AND k.\"productId\" = ? AND k.\"referenceType\" = ?")
                .params(org, p, LATE).query().singleRow());
        assertThat((BigDecimal) k.get("quantityChange")).isEqualByComparingTo("3");
        assertThat(k.get("vtype")).isEqualTo("stock_adjustment");
        assertThat(k.get("sourceLineId")).isEqualTo(session.get("lines").get(0).get("id").asString());
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("E1: a late correction whose journal cannot post is left listed; the late movement itself still applies")
    void unpostableCorrectionKeepsTheMovement() {
        String p = sellable("CC-E1");
        receive(p, 10, 5);
        Instant movedAt = Instant.now();
        pause();
        JsonNode session = start(main, "spot", List.of(p), false);
        save(session, p, 7);
        submit(session);
        // The product's count gains post to an account that accepts no postings (a group account).
        String group = asOrg(() -> jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND NOT"
                + " \"isPostable\" ORDER BY code LIMIT 1").param(org).query(String.class).single());
        asOrgRun(() -> {
            jdbc.sql("INSERT INTO \"InventoryPostingRule\" (id, \"organizationId\", \"movementType\", \"lineIndex\","
                    + " \"debitOrCredit\", \"accountSource\", \"accountMappingKey\", \"productId\", \"updatedAt\") VALUES"
                    + " (?, ?, 'ADJUSTMENT_GAIN', 0, 'debit', 'account_mapping', 'stock_valuation', ?, now())")
                    .params(java.util.UUID.randomUUID().toString(), org, p).update();
            jdbc.sql("INSERT INTO \"InventoryPostingRule\" (id, \"organizationId\", \"movementType\", \"lineIndex\","
                    + " \"debitOrCredit\", \"accountSource\", \"literalAccountId\", \"productId\", \"updatedAt\") VALUES"
                    + " (?, ?, 'ADJUSTMENT_GAIN', 1, 'credit', 'literal', ?, ?, now())")
                    .params(java.util.UUID.randomUUID().toString(), org, group, p).update();
        });
        JsonNode out = push(op("stock.out", Map.of("locationId", main, "responsibleById", auditUserId, "approvedById",
                auditUserId, "approverPin", managerPin, "items", List.of(Map.of("productId", p, "quantity", 3))),
                movedAt));
        assertThat(out.get("status").asString()).as("the late stock-out applies: " + out).isEqualTo("applied");
        assertThat(onHand(p, main)).as("no correction: 7 counted − 3").isEqualByComparingTo("4");
        assertThat(asOrg(() -> count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"referenceType\" = ?", org, p, LATE))).isZero();
        asOrgRun(() -> jdbc.sql("DELETE FROM \"InventoryPostingRule\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                .params(org, p).update());
        assertGlTieOut();
    }
}
