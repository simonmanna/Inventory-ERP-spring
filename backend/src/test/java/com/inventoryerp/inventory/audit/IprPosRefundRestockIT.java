package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IPR 1d SW-1 / X-5 (engine stream E2): a POS refund restocks through the engine's linked return — against the issue
 * rows of the refunded line, net of what a line void already brought back, never more than was issued — and the R2
 * race of a refund against an order cancel.
 */
class IprPosRefundRestockIT extends InventoryAuditSupport {

    @Autowired
    PosInvoiceService billing;

    String pin;
    String sessionId;

    @BeforeAll
    void till() {
        pin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        okA(201, "POST", "/pos/override/pin", Map.of("pin", pin));
        String register = okA(201, "POST", "/cash-registers", Map.of("code", "RREG-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "Refund till")).get("id").asString();
        sessionId = keyed(200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", register, "openingFloat", 0))
                .get("id").asString();
        // Order-timed posting (as an organisation configured before R4): the order's job issues before the invoice.
        orgSetting("inventory.stockPostingTiming", "\"at_fulfillment_complete\"");
    }

    Res keyedRes(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode keyed(int status, String method, String path, Object body) {
        Res r = keyedRes(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    /** An open order of {@code qty} × {@code product}, its stock issued at order time by its job. */
    String issuedOrder(String product, int qty) {
        String orderId = okA(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1,
                "cashSessionId", sessionId, "lines", List.of(new LinkedHashMap<>(Map.of("productId", product,
                        "description", "Item", "quantity", qty, "unitPrice", 100))))).get("id").asString();
        asOrgRun(() -> billing.enqueueStockPosting(orderId, null, null, "at_fulfillment_complete"));
        String job = asOrg(() -> jdbc.sql("SELECT id FROM \"StockPostingJob\" WHERE \"organizationId\" = ? AND"
                + " \"orderId\" = ?").params(org, orderId).query(String.class).single());
        TenantContext.run(TenantStore.user(org, auditUserId), () -> billing.processStockPostingJob(job));
        return orderId;
    }

    String settle(String orderId, int amount) {
        JsonNode settled = keyed(200, "POST", "/pos/orders/" + orderId + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", amount))));
        return settled.has("invoiceId") ? settled.get("invoiceId").asString()
                : asOrg(() -> jdbc.sql("SELECT \"invoiceId\" FROM \"Order\" WHERE id = ?").param(orderId)
                        .query(String.class).single());
    }

    Map<String, Object> refundBody(String invoiceId, int qty) {
        String lineId = asOrg(() -> jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(String.class).single());
        return Map.of("reason", "Returned", "overrideById", auditUserId, "overridePin", pin, "stockDisposition",
                "restock", "cashSessionId", sessionId, "lines", List.of(Map.of("lineId", lineId, "quantity", qty)));
    }

    @Test
    @DisplayName("SW-1: void 1 of 3 issued at order time, refund the 2 billed — 2 come back, not 3; rows linked")
    void voidThenRefundRestocksOnce() {
        String p = product("SW1-VOID", Map.of("salesPrice", 100, "costPrice", 5));
        receive(p, main, 10, 5, null);
        String orderId = issuedOrder(p, 3);
        assertThat(onHand(p, main)).isEqualByComparingTo("7");
        String item = asOrg(() -> jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ?").param(orderId)
                .query(String.class).single());
        keyed(200, "DELETE", "/pos/orders/" + orderId + "/items/" + item, Map.of("reason", "Wrong item",
                "quantity", 1));
        assertThat(onHand(p, main)).as("the void returned its unit").isEqualByComparingTo("8");
        String invoiceId = settle(orderId, 200);
        keyed(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(invoiceId, 2));
        assertThat(onHand(p, main)).as("the refund returned the 2 billed units only").isEqualByComparingTo("10");
        assertThat(asOrg(() -> count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'pos_refund' AND \"reversesLedgerId\" IS NULL", org))).as("refund rows linked")
                .isZero();
        assertThat(asOrg(() -> count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'pos_refund' AND \"productId\" = ?", org, p))).isEqualTo(1);
        assertIntegrityGreen(true, "feature_risk", "negative_quants");
        assertGlTieOut();
    }

    @Test
    @DisplayName("X-5: a refunded line's lot-tracked component comes back into the lot it left")
    void refundRestoresLot() {
        String p = product("X5-LOT", Map.of("salesPrice", 100, "costPrice", 5, "batchTracking", true));
        receive(p, main, 4, 5, m -> m.batch("LOT-A"));
        String orderId = issuedOrder(p, 2);
        String invoiceId = settle(orderId, 200);
        keyed(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(invoiceId, 2));
        assertThat(onHand(p, main)).isEqualByComparingTo("4");
        assertThat(lotSum(p, main)).isEqualByComparingTo("4");
        assertThat(asOrg(() -> count("SELECT count(*) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?", org, p))).as("no RET- lot opened").isEqualTo(1);
        assertIntegrityGreen(true, "feature_risk", "negative_quants");
        assertGlTieOut();
    }

    @Test
    @DisplayName("R2: a refund racing the cancel of its (billed) order — the cancel is refused, stock restocked once")
    void refundRacesOrderCancel() throws Exception {
        String p = product("R2-RFC", Map.of("salesPrice", 100, "costPrice", 5));
        receive(p, main, 5, 5, null);
        String orderId = issuedOrder(p, 2);
        String invoiceId = settle(orderId, 200);
        Map<String, Object> refund = refundBody(invoiceId, 2);
        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> keyedRes("POST", "/pos/invoices/" + invoiceId + "/refund", refund).status());
        tasks.add(() -> keyedRes("POST", "/pos/orders/" + orderId + "/cancel", Map.of("reason", "race",
                "overrideById", auditUserId, "overridePin", pin)).status());
        tasks.add(() -> keyedRes("POST", "/pos/invoices/" + invoiceId + "/refund", refund).status());
        List<Raced<Integer>> out = race(Duration.ofSeconds(90), tasks);
        assertThat(out.get(1).value()).as("a billed order cannot be cancelled").isNotEqualTo(200).isNotEqualTo(201);
        assertThat(out.stream().filter(r -> r.value() != null && r.value() == 200).count()).as(out.toString())
                .isEqualTo(1);
        assertThat(onHand(p, main)).as("restocked once").isEqualByComparingTo("5");
        assertIntegrityGreen(true, "feature_risk", "negative_quants");
        assertGlTieOut();
    }
}
