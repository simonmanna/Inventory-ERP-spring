package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Hardening R5a, release gate G3 evidence: the negative-stock policy as it actually behaves (documented in
 * docs/spec/inventory.md, "Negative stock policy"). {@code inventory.allowNegativeStock} is on by default; off ("strict")
 * blocks back-office and direct issues, while a POS sale drained by its stock-posting job keeps following the item
 * stock policy only ({@code block} refuses the line, which stays on the job for the Posting Monitor). Offline issues
 * are flagged {@code offline_negative_stock} when allowed and fail (dead-lettered) when strict. Transfers never take
 * their source below zero but ignore sales reservations (the integrity report flags the over-reservation). Partial receipts and a recall of a transit transfer conserve
 * quantities (the integrity report stays green).
 */
class NegativeStockPolicyIT extends InventoryAuditSupport {

    @Autowired
    PosInvoiceService billing;

    @AfterEach
    void permissive() {
        orgSetting("inventory.allowNegativeStock", "true");
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A closed POS order of {@code qty} × product and its pending stock-posting job (as checkout enqueues it). */
    String posJob(String productId, int qty) {
        String orderId = insert("Order", Map.of("organizationId", org, "orderNumber", "ORD-NS-" + sfx(), "status",
                "closed", "updatedAt", now()));
        insert("OrderItem", Map.of("organizationId", org, "orderId", orderId, "productId", productId, "description",
                "line", "quantity", qty, "lineNumber", 1, "updatedAt", now()));
        return insert("StockPostingJob", Map.of("organizationId", org, "orderId", orderId, "invoiceId", orderId,
                "invoiceNumber", "INV-" + orderId, "status", "pending", "idempotencyKey", "at_invoice:" + orderId));
    }

    Map<String, Object> jobRow(String jobId) {
        return jdbc.sql("SELECT status, \"lastError\", attempts FROM \"StockPostingJob\" WHERE id = ?").param(jobId)
                .query().singleRow();
    }

    /** Two terminals draining their sale of the same last unit at the same time. */
    List<Map<String, Object>> twoTerminals(String productId) throws Exception {
        String first = posJob(productId, 1);
        String second = posJob(productId, 1);
        List<Callable<String>> runs = List.of(first, second).stream().map(id -> (Callable<String>) () -> {
            TenantContext.run(TenantStore.user(org, auditUserId), () -> billing.processStockPostingJob(id));
            return id;
        }).toList();
        parallel(runs);
        return List.of(jobRow(first), jobRow(second));
    }

    static long countStatus(List<Map<String, Object>> jobs, String status) {
        return jobs.stream().filter(j -> status.equals(j.get("status"))).count();
    }

    JsonNode integrity() {
        return okA(200, "GET", "/inventory/reports/integrity", null);
    }

    void assertIntegrityGreen(String step) {
        assertIntegrityFailing(step);
    }

    /** The integrity report's failing checks are exactly {@code keys}; returns the report. */
    JsonNode assertIntegrityFailing(String step, String... keys) {
        JsonNode r = integrity();
        List<String> keysFailing = new java.util.ArrayList<>();
        r.get("checks").forEach(c -> {
            if (!c.get("ok").asBoolean()) {
                keysFailing.add(c.get("key").asString());
            }
        });
        if (keys.length > 0) {
            assertThat(keysFailing).as(step + ": " + r).containsExactly(keys);
            return r;
        }
        List<String> failing = new java.util.ArrayList<>();
        r.get("checks").forEach(c -> {
            if (!c.get("ok").asBoolean()) {
                failing.add(c.get("key").asString() + " " + c.get("rows"));
            }
        });
        assertThat(failing).as(step).isEmpty();
        return r;
    }

    @Test
    @DisplayName("(a) two terminals sell the last unit, permissive: both sales post, on-hand -1, the ledger chain holds")
    void lastUnitPermissive() throws Exception {
        String p = product("LAST-P");
        receive(p, 1, 10);
        List<Map<String, Object>> jobs = twoTerminals(p);
        assertThat(countStatus(jobs, "done")).as(jobs.toString()).isEqualTo(2);
        assertThat(onHand(p, main)).isEqualByComparingTo("-1");
        assertThat(ledgerSum(p, main)).isEqualByComparingTo("-1");
        assertThat(quantInvariantBreaks()).isEmpty();
    }

    @Test
    @DisplayName("(a) strict org setting, item policy warn (default): a POS drain is not blocked — both post, -1")
    void lastUnitStrictWarnStillPosts() throws Exception {
        orgSetting("inventory.allowNegativeStock", "false");
        String p = product("LAST-W");
        receive(p, 1, 10);
        List<Map<String, Object>> jobs = twoTerminals(p);
        assertThat(countStatus(jobs, "done")).as(jobs.toString()).isEqualTo(2);
        assertThat(onHand(p, main)).isEqualByComparingTo("-1");
        assertThat(quantInvariantBreaks()).isEmpty();
        // the same strict setting refuses a back-office issue of a unit that is not there
        Res direct = api("POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 1, null))));
        assertThat(direct.status()).isEqualTo(400);
        assertThat(direct.body().get("message").asString()).startsWith("Insufficient stock");
    }

    @Test
    @DisplayName("(a) strict + item policy block: one sale posts, the other's line stays on its job (Posting Monitor)")
    void lastUnitStrictBlock() throws Exception {
        orgSetting("inventory.allowNegativeStock", "false");
        String p = product("LAST-B", Map.of("stockPolicy", "block"));
        receive(p, 1, 10);
        List<Map<String, Object>> jobs = twoTerminals(p);
        assertThat(countStatus(jobs, "done")).as(jobs.toString()).isEqualTo(1);
        Map<String, Object> refused = jobs.stream().filter(j -> !"done".equals(j.get("status"))).findFirst().orElseThrow();
        assertThat(refused.get("status")).isEqualTo("pending");
        assertThat((String) refused.get("lastError")).contains("need review").contains("Insufficient stock");
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        assertThat(quantInvariantBreaks()).isEmpty();
        JsonNode monitor = okA(200, "GET", "/pos/posting-monitor/counts", null);
        assertThat(monitor.toString()).isNotEmpty();
    }

    // ------------------------------------------------------------------ (b) offline

    String device;
    String deviceToken;
    int seq;

    void register() {
        if (device == null) {
            JsonNode d = okA(201, "POST", "/sync/devices/register", Map.of("name", "NS tablet " + sfx(), "platform",
                    "web"));
            device = d.get("id").asString();
            deviceToken = d.get("deviceToken").asString();
        }
    }

    JsonNode pushStockOut(String productId, int qty, Instant occurredAt) {
        register();
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("opId", UUID.randomUUID().toString());
        op.put("deviceSeq", ++seq);
        op.put("type", "stock.out");
        op.put("actorUserId", auditUserId);
        op.put("occurredAt", occurredAt.toString());
        op.put("payload", Map.of("locationId", main, "responsibleById", auditUserId, "approvedById", auditUserId,
                "items", List.of(Map.of("productId", productId, "quantity", qty))));
        Res r = call("POST", "/sync/push", Map.of("deviceId", device, "ops", List.of(op)), Map.of("X-Device-Token",
                deviceToken));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results").get(0);
    }

    long negativeFlags(String productId) {
        return count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind ="
                + " 'offline_negative_stock' AND \"productId\" = ?", org, productId);
    }

    @Test
    @DisplayName("(b) offline sale synced after the stock was sold online: permissive posts and flags it")
    void offlineAfterConsumedPermissive() {
        String p = product("OFF-P");
        receive(p, 1, 10);
        Instant soldOffline = Instant.now().minusSeconds(600);
        okA(200, "POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 1, null))));
        JsonNode r = pushStockOut(p, 1, soldOffline);
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        assertThat(onHand(p, main)).isEqualByComparingTo("-1");
        assertThat(negativeFlags(p)).isEqualTo(1);
        assertThat(quantInvariantBreaks()).isEmpty();
    }

    @Test
    @DisplayName("(b) offline sale synced after the stock was sold online: strict refuses it (failed op, no stock move)")
    void offlineAfterConsumedStrict() {
        orgSetting("inventory.allowNegativeStock", "false");
        String p = product("OFF-S");
        receive(p, 1, 10);
        okA(200, "POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 1, null))));
        JsonNode r = pushStockOut(p, 1, Instant.now().minusSeconds(600));
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("failed");
        assertThat(r.get("httpStatus").asInt()).isEqualTo(400);
        assertThat(r.get("code").asString()).isEqualTo("BUSINESS_RULE");
        assertThat(r.get("retryable").asBoolean()).isFalse();
        assertThat(r.get("error").asString()).startsWith("Insufficient stock");
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        assertThat(negativeFlags(p)).isZero();
    }

    // ------------------------------------------------------------------ (c), (d) transfers

    String transitTransfer(String productId, int qty) {
        String id = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt,
                "mode", "transit", "responsibleById", auditUserId, "approvedById", auditUserId, "items",
                List.of(Map.of("productId", productId, "qtyRequested", qty)))).get("id").asString();
        okA(200, "POST", "/inventory/transfers/" + id + "/approve", null);
        return id;
    }

    BigDecimal reserved(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockReservation\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ? AND status = 'active'").params(org, productId, main)
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("(c) a transfer dispatch ignores sales reservations (on-hand is its limit, not ATP); the integrity"
            + " report flags the over-reservation")
    void dispatchOverReservations() {
        String p = product("RES");
        receive(p, 5, 4);
        String so = "SO-" + sfx();
        okA(201, "POST", "/inventory/reservations", Map.of("productId", p, "locationId", main, "quantity", 4,
                "sourceType", "manual", "sourceId", so));
        String id = transitTransfer(p, 3);
        JsonNode dispatched = okA(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        assertThat(dispatched.get("status").asString()).isEqualTo("in_transit");
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
        assertThat(reserved(p)).isEqualByComparingTo("4"); // over-reserved by 2: visible, not prevented
        JsonNode r = assertIntegrityFailing("after an over-reserved dispatch", "reservations");
        JsonNode row = null;
        for (JsonNode c : r.get("checks")) {
            if ("reservations".equals(c.get("key").asString())) {
                row = c.get("rows").get(0);
            }
        }
        assertThat(row.get("kind").asString()).isEqualTo("over_reserved");
        assertThat(row.get("productId").asString()).isEqualTo(p);
        assertThat(num(row.get("excess"))).isEqualByComparingTo("2");

        // on-hand is still a hard limit: a dispatch beyond it is refused even when negative stock is allowed
        String beyond = transitTransfer(p, 3);
        Res refused = api("POST", "/inventory/transfers/" + beyond + "/dispatch", null);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("Insufficient stock at source");
        assertThat(onHand(p, main)).isEqualByComparingTo("2");
        okA(201, "POST", "/inventory/reservations/release", Map.of("sourceType", "manual", "sourceId", so));
        assertIntegrityGreen("reservation released");
    }

    @Test
    @DisplayName("(d) partial receipts, damage, short and a recall of a transit transfer conserve quantities")
    void partialReceiptsConserve() {
        String p = product("TRN");
        receive(p, 10, 3);
        String id = transitTransfer(p, 8);
        String itemId = okA(200, "POST", "/inventory/transfers/" + id + "/dispatch", null).get("items").get(0)
                .get("id").asString();
        assertIntegrityGreen("dispatched");
        assertThat(onHand(p, main)).isEqualByComparingTo("2");

        JsonNode first = okA(200, "POST", "/inventory/transfers/" + id + "/receive", Map.of("lines",
                List.of(Map.of("itemId", itemId, "received", 3))));
        assertThat(first.get("status").asString()).isEqualTo("partially_received");
        assertIntegrityGreen("received 3");

        JsonNode second = okA(200, "POST", "/inventory/transfers/" + id + "/receive", Map.of("lines",
                List.of(Map.of("itemId", itemId, "received", 1, "damaged", 1, "short", 1)), "lossReason", "damaged in"
                        + " transit", "approvedById", auditUserId));
        assertThat(second.get("status").asString()).isEqualTo("partially_received");
        assertIntegrityGreen("received 1, damaged 1, short 1");
        assertThat(onHand(p, alt)).isEqualByComparingTo("4");

        JsonNode recalled = okA(200, "POST", "/inventory/transfers/" + id + "/recall", Map.of("reason", "rest back"));
        assertThat(recalled.get("status").asString()).isIn("completed", "recalled", "partially_recalled");
        assertIntegrityGreen("recalled the rest");
        // 10 in = 2 left + 2 recalled at the source, 4 received at the destination, 2 damaged / short written off
        assertThat(onHand(p, main)).isEqualByComparingTo("4");
        assertThat(onHand(p, alt)).isEqualByComparingTo("4");
        assertThat(quantInvariantBreaks()).isEmpty();
    }

    @Test
    @DisplayName("(e) a receipt into negative stock at a new cost: the cost correction is a value-only ledger row, so"
            + " valuation, sub-ledger and GL stay equal")
    void receiptIntoNegativeStockCorrectionIsLedgered() {
        String p = product("NEG-COST");
        issue(p, main, 3, null); // sold short at the zero average (no value, no GL)
        assertThat(onHand(p, main)).isEqualByComparingTo("-3");
        receive(p, 1, 100); // one unit at 100: the three short units are re-costed at 100 (Dr COGS 300 / Cr stock)
        assertThat(onHand(p, main)).isEqualByComparingTo("-2");

        Map<String, Object> row = jdbc.sql("SELECT \"quantityChange\", \"totalValue\", \"qtyBefore\", \"balanceAfter\""
                + " FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND type = 'cost_adjustment'")
                .params(org, p).query().singleRow();
        assertThat((BigDecimal) row.get("quantityChange")).isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("totalValue")).isEqualByComparingTo("-300");
        assertThat((BigDecimal) row.get("qtyBefore")).isEqualByComparingTo("-2");
        assertThat((BigDecimal) row.get("balanceAfter")).isEqualByComparingTo("-2");
        assertThat(ledgerSum(p, main)).isEqualByComparingTo("-2");
        assertIntegrityGreen("receipt into negative stock at a new cost");
    }
}
