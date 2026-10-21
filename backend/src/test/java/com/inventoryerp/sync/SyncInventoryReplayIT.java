package com.inventoryerp.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Port of {@code pos-offline-inventory-replay.spec.ts}: offline sales reach stock exactly once — deviceSeq ordering,
 * duplicate batch delivery, a crash between invoice commit and stock posting, a re-pushed partial refund, and a
 * dependent op whose parent failed. Runs on the shared DEMO org with its own product, register and device.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncInventoryReplayIT extends ApiTestSupport {

    @Autowired
    PosInvoiceService billing;

    String token;
    String deviceId;
    String adminId;
    String water;
    String location;
    String registerId;
    String managerPin;
    final String sessionClient = "sess-" + UUID.randomUUID();
    final String saleClient = "sale-" + UUID.randomUUID();
    List<Map<String, Object>> batch;

    @BeforeAll
    void fixtures() {
        JsonNode device = ok(201, "POST", "/sync/devices/register", Map.of("name", "Replay tablet"));
        token = device.get("deviceToken").asString();
        deviceId = device.get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        location = pinPosWarehouse();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        water = ok(201, "POST", "/products", Map.of("code", "RW-" + suffix, "name", "Replay water " + suffix,
                "productType", "stockable", "trackInventory", true, "costPrice", 40, "salesPrice", 100)).get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "RREG-" + suffix, "name", "Replay till " + suffix))
                .get("id").asString();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        ok(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
        JsonNode in = push(List.of(op(900, "stock.in", Map.of("locationId", location, "approvedById", adminId,
                "approverPin", managerPin, "items", List.of(Map.of("productId", water, "quantity", 50, "unitCost", 40))))));
        assertThat(in.get("results").get(0).get("status").asString()).as(in.toString()).isEqualTo("applied");
    }

    @AfterAll
    void cleanup() {
        jdbc.sql("UPDATE \"SyncOpDeadLetter\" SET status = 'discarded' WHERE \"deviceId\" = ? AND status = 'open'")
                .param(deviceId).update();
        drain();
        forceCloseSessions(List.of(registerId));
    }

    Map<String, Object> op(int seq, String type, Map<String, Object> payload) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "op-" + deviceId + "-" + seq + "-" + type);
        o.put("deviceSeq", seq);
        o.put("type", type);
        o.put("actorUserId", adminId);
        o.put("occurredAt", Instant.now().toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(List<Map<String, Object>> ops) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", ops), Map.of("X-Device-Token", token));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body();
    }

    BigDecimal onHand() {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(water, location).query(BigDecimal.class).single();
    }

    long ledger(String type) {
        return jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ? AND type::text = ?")
                .params(water, type).query(Long.class).single();
    }

    List<String> pendingJobs() {
        return jdbc.sql("SELECT j.id FROM \"StockPostingJob\" j JOIN \"Invoice\" i ON i.id = j.\"invoiceId\" WHERE"
                + " i.\"deviceId\" = ? AND j.status::text <> 'done'").param(deviceId).query(String.class).list();
    }

    /** Run this device's pending stock-posting jobs, as the worker would. */
    void drain() {
        for (String job : pendingJobs()) {
            TenantContext.run(TenantStore.user(demoOrg, adminId), () -> billing.processStockPostingJob(job));
        }
    }

    /** Stock tie-out: on-hand equals the ledger movements of this product at this location. */
    void assertTieOut() {
        BigDecimal ledgerQty = jdbc.sql("SELECT coalesce(sum(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"productId\" = ?"
                + " AND \"locationId\" = ?").params(water, location).query(BigDecimal.class).single();
        assertThat(onHand()).isEqualByComparingTo(ledgerQty);
    }

    @Test
    @Order(1)
    @DisplayName("1. applies an offline batch pushed out of array order, in deviceSeq order")
    void outOfOrder() {
        batch = new ArrayList<>(List.of(
                op(2, "sale.checkout", Map.of("clientId", saleClient, "provisionalNumber", "R1-000001",
                        "cashSessionId", sessionClient, "lines", List.of(Map.of("productId", water, "description", "Water",
                                "quantity", 4, "unitPrice", 100)), "tenders", List.of(Map.of("method", "cash", "amount", 400)))),
                op(1, "cash_session.open", Map.of("clientId", sessionClient, "cashRegisterId", registerId,
                        "openingFloat", 0))));
        JsonNode res = push(batch);
        assertThat(res.get("results").get(0).get("status").asString()).as(res.toString()).isEqualTo("applied");
        assertThat(res.get("results").get(1).get("status").asString()).isEqualTo("applied");
        drain();
        assertThat(onHand()).isEqualByComparingTo("46");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"deviceId\" = ?").param(deviceId)
                .query(Long.class).single()).isEqualTo(1);
        assertTieOut();
    }

    @Test
    @Order(2)
    @DisplayName("2. duplicate delivery of the same batch replays without a second sale, job or movement")
    void duplicate() {
        long movements = ledger("issue");
        List<Map<String, Object>> reversed = new ArrayList<>(batch);
        java.util.Collections.reverse(reversed);
        JsonNode res = push(reversed);
        res.get("results").forEach(r -> assertThat(r.get("status").asString()).isEqualTo("replayed"));
        drain();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"deviceId\" = ?").param(deviceId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockPostingJob\" j JOIN \"Invoice\" i ON i.id = j.\"invoiceId\""
                + " WHERE i.\"deviceId\" = ?").param(deviceId).query(Long.class).single()).isEqualTo(1);
        assertThat(ledger("issue")).isEqualTo(movements);
        assertThat(onHand()).isEqualByComparingTo("46");
        assertTieOut();
    }

    @Test
    @Order(3)
    @DisplayName("3. crash between invoice commit and stock posting: replay + double processing yield one effect")
    void crash() {
        String session = jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"cashRegisterId\" = ? AND status::text = 'open'")
                .param(registerId).query(String.class).single();
        Map<String, Object> crashOp = op(3, "sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(),
                "cashSessionId", session, "lines", List.of(Map.of("productId", water, "description", "Water",
                        "quantity", 2, "unitPrice", 100)), "tenders", List.of(Map.of("method", "cash", "amount", 200))));
        push(List.of(crashOp));
        assertThat(onHand()).isEqualByComparingTo("46");
        JsonNode retry = push(List.of(crashOp));
        assertThat(retry.get("results").get(0).get("status").asString()).isEqualTo("replayed");
        List<String> jobs = pendingJobs();
        assertThat(jobs).hasSize(1);
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> billing.processStockPostingJob(jobs.getFirst()));
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> billing.processStockPostingJob(jobs.getFirst()));
        assertThat(onHand()).isEqualByComparingTo("44");
        assertThat(ledger("issue")).isEqualTo(2);
        assertTieOut();
    }

    @Test
    @Order(4)
    @DisplayName("4. offline partial refund of a synced sale restocks once, even when re-pushed")
    void refund() {
        String invoiceId = jdbc.sql("SELECT id FROM \"Invoice\" WHERE \"deviceId\" = ? AND \"provisionalNumber\" = 'R1-000001'")
                .param(deviceId).query(String.class).single();
        String lineId = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ? ORDER BY \"lineNumber\" LIMIT 1")
                .param(invoiceId).query(String.class).single();
        String session = jdbc.sql("SELECT id FROM \"CashSession\" WHERE \"cashRegisterId\" = ? AND status::text = 'open'")
                .param(registerId).query(String.class).single();
        Map<String, Object> refundOp = op(4, "sale.refund", Map.of("invoiceId", invoiceId, "reason", "one bottle leaking",
                "overrideById", adminId, "overridePin", managerPin, "cashSessionId", session,
                "lines", List.of(Map.of("lineId", lineId, "quantity", 1))));
        JsonNode first = push(List.of(refundOp));
        assertThat(first.get("results").get(0).get("status").asString()).as(first.toString()).isEqualTo("applied");
        JsonNode again = push(List.of(refundOp));
        assertThat(again.get("results").get(0).get("status").asString()).isEqualTo("replayed");
        drain();
        assertThat(onHand()).isEqualByComparingTo("45");
        assertThat(ledger("return_in")).isEqualTo(1);
        assertTieOut();
    }

    @Test
    @Order(5)
    @DisplayName("5. a dependent op whose parent failed dead-letters instead of touching stock")
    void failedParent() {
        String orphan = "sess-" + UUID.randomUUID();
        JsonNode res = push(List.of(
                op(5, "cash_session.open", Map.of("clientId", orphan, "cashRegisterId", UUID.randomUUID().toString(),
                        "openingFloat", 0)),
                op(6, "sale.checkout", Map.of("clientId", "sale-" + UUID.randomUUID(), "cashSessionId", orphan,
                        "lines", List.of(Map.of("productId", water, "description", "Water", "quantity", 1, "unitPrice", 100)),
                        "tenders", List.of(Map.of("method", "cash", "amount", 100))))));
        assertThat(res.get("results").get(0).get("status").asString()).isEqualTo("failed");
        assertThat(res.get("results").get(1).get("status").asString()).isEqualTo("failed");
        assertThat(res.get("results").get(1).get("httpStatus").asInt()).isEqualTo(424);
        drain();
        assertThat(onHand()).isEqualByComparingTo("45");
        assertTieOut();
    }
}
