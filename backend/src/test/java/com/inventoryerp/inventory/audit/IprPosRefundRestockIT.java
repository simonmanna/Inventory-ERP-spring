package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.IssueSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnFromSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnLine;
import com.inventoryerp.pos.application.PosInvoiceService;
import java.math.BigDecimal;
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

    @Autowired
    StockReversalService reversals;

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

    // ------------------------------------------------------------------ IPR wave 3 (E3)

    String openOrder(String product, int qty) {
        return okA(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1, "cashSessionId",
                sessionId, "lines", List.of(new LinkedHashMap<>(Map.of("productId", product, "description", "Item",
                        "quantity", qty, "unitPrice", 100))))).get("id").asString();
    }

    @Test
    @DisplayName("X-8: billed under at_payment, timing switched to at_invoice, then paid — the sale still issues once")
    void timingChangeStillIssues() throws Exception {
        String p = product("X8-TIME", Map.of("salesPrice", 100, "costPrice", 5));
        receive(p, main, 10, 5, null);
        orgSetting("inventory.stockPostingTiming", "\"at_payment\"");
        try {
            String orderId = openOrder(p, 3);
            Res billed = keyedRes("POST", "/pos/orders/" + orderId + "/invoice", Map.of("cashSessionId", sessionId));
            assertThat(billed.status()).as(billed.raw().body()).isIn(200, 201);
            String invoiceId = asOrg(() -> jdbc.sql("SELECT \"invoiceId\" FROM \"Order\" WHERE id = ?").param(orderId)
                    .query(String.class).single());
            String jobs = "SELECT count(*) FROM \"StockPostingJob\" WHERE \"organizationId\" = ? AND \"orderId\" = ?";
            assertThat(asOrg(() -> count(jobs, org, orderId))).as("at_payment: no job at billing").isZero();
            orgSetting("inventory.stockPostingTiming", "\"at_invoice\"");
            keyed(200, "POST", "/pos/invoices/" + invoiceId + "/payments", Map.of("cashSessionId", sessionId,
                    "tenders", List.of(Map.of("method", "cash", "amount", 300))));
            long deadline = System.currentTimeMillis() + 20_000;
            while (asOrg(() -> count(jobs, org, orderId)) == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(250);
            }
            assertThat(asOrg(() -> count(jobs, org, orderId))).as("the closed sale got its one job").isEqualTo(1);
            String job = asOrg(() -> jdbc.sql("SELECT id FROM \"StockPostingJob\" WHERE \"organizationId\" = ? AND"
                    + " \"orderId\" = ?").params(org, orderId).query(String.class).single());
            TenantContext.run(TenantStore.user(org, auditUserId), () -> billing.processStockPostingJob(job));
            assertThat(onHand(p, main)).isEqualByComparingTo("7");
            assertIntegrityGreen(true, "feature_risk", "negative_quants");
            assertGlTieOut();
        } finally {
            orgSetting("inventory.stockPostingTiming", "\"at_fulfillment_complete\"");
        }
    }

    @Test
    @DisplayName("1d pre-R4: a proportional refund restock is capped by what the sale still has out (net of a void)")
    void preR4RestockCapped() {
        // Its own organisation: the pre-R4 path writes unlinked refund rows, which unlinked_restocks reports for an
        // organisation that also has linked ones (the other tests of this class).
        AuditOrg o = newOrg();
        String[] saved = {org, auditToken, auditUserId, main, pin, sessionId};
        org = o.id();
        auditToken = o.token();
        auditUserId = o.userId();
        main = o.main();
        till();
        String p = product(o, "R4-CAP", Map.of("salesPrice", 100, "costPrice", 5));
        receive(p, main, 10, 5, null);
        orgSetting("inventory.stockPostingTiming", "\"manual\"");
        try {
            String orderId = openOrder(p, 2);
            // A line issued before R4 (no source line), 3 units, of which a void brought 1 back, linked.
            StockMove.Issued out = issue(p, main, 3, m -> m.source("pos_order", orderId));
            asOrgRun(() -> reversals.returnFromSource(new ReturnFromSource(IssueSource.ledgerCodes(List.of(
                    out.ledgerCode())), List.of(ReturnLine.of(p, null, BigDecimal.ONE)), "pos_order_void", orderId,
                    null, null, null, null, true, "return_in")));
            assertThat(onHand(p, main)).isEqualByComparingTo("8");
            String invoiceId = settle(orderId, 200);
            keyed(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(invoiceId, 2));
            assertThat(onHand(p, main)).as("2 still out come back, not 3 × 2 / 2").isEqualByComparingTo("10");
            assertIntegrityGreen(true, "feature_risk", "negative_quants");
            assertGlTieOut();
        } finally {
            org = saved[0];
            auditToken = saved[1];
            auditUserId = saved[2];
            main = saved[3];
            pin = saved[4];
            sessionId = saved[5];
        }
    }

    Map<String, Object> refundAs(String invoiceId, int qty, String disposition) {
        Map<String, Object> body = new LinkedHashMap<>(refundBody(invoiceId, qty));
        body.put("stockDisposition", disposition);
        return body;
    }

    @Test
    @DisplayName("review BLOCKER: refunds after a no_return refund restock only their share of what was issued")
    void noReturnThenRestockDoesNotOverRestock() {
        String p = product("RV-NORET", Map.of("salesPrice", 100, "costPrice", 5));
        receive(p, main, 20, 5, null);
        String orderId = issuedOrder(p, 10);
        assertThat(onHand(p, main)).isEqualByComparingTo("10");
        String invoiceId = settle(orderId, 1000);
        keyed(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundAs(invoiceId, 4, "no_return"));
        assertThat(onHand(p, main)).as("no_return restocks nothing").isEqualByComparingTo("10");
        keyed(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundAs(invoiceId, 3, "restock"));
        assertThat(onHand(p, main)).as("3 of 10 issued, not 10 × 3 / 6").isEqualByComparingTo("13");
        keyed(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundAs(invoiceId, 3, "restock"));
        assertThat(onHand(p, main)).as("the last 3, not everything still returnable").isEqualByComparingTo("16");
        assertIntegrityGreen(true, "feature_risk", "negative_quants");
        assertGlTieOut();
    }
}
