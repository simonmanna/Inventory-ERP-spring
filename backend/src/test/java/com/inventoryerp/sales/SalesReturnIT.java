package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Phase S7 of ADR-S011: RMA lifecycle (request → approve → receive → inspect → close), stock dispositions (restock,
 * quarantine, scrap as waste), credit note against the order invoice without rewriting gross invoiced, refund,
 * replacement order, claim without goods lowering the billable quantity, complaints, quality certificates.
 */
class SalesReturnIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String yard;
    String quarantine;
    String blocks;
    String customer;
    SalesLedgerAssertions ledger;

    @BeforeAll
    void fixtures() {
        yard = location("Block yard " + sfx);
        quarantine = location("Quarantine " + sfx);
        blocks = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"invoicePolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable'::\"ProductType\", true, 'AVCO'::\"CostingMethod\", 1500, 2500, 'delivered', now())")
                .params(blocks, demoOrg, "RB-" + sfx, "Block 6in " + sfx).update();
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> stock.receive(StockMove.of(blocks, yard, new BigDecimal("1000")).unitCost(new BigDecimal("1500")))));
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(customer, demoOrg, "RC-" + sfx, "Return cust " + sfx)
                .update();
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
    }

    String location(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'warehouse', now())").params(id, demoOrg, "RL-" + UUID.randomUUID().toString()
                        .substring(0, 8), name).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    BigDecimal onHand(String location) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(blocks, location).query(BigDecimal.class).single();
    }

    /** Confirmed order of {@code qty} blocks, delivered (projection) and, if {@code invoice}, invoiced and posted. */
    String deliveredOrder(int qty, boolean invoice) throws Exception {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines", List.of(
                Map.of("productId", blocks, "quantity", qty)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDispatched\" = ?, \"qtyDelivered\" = ?, \"qtyAccepted\" = ?,"
                + " \"qtyBillable\" = ? WHERE \"orderId\" = ?").params(qty, qty, qty, qty, so).update();
        if (invoice) {
            String inv = money("/sales/orders/" + so + "/invoices", Map.of()).get("id").asString();
            ok(200, "POST", "/invoices/" + inv + "/post", null);
            long deadline = System.currentTimeMillis() + 15_000;
            while (line(so).get("qtyInvoiced").asDouble() < qty && System.currentTimeMillis() < deadline) {
                Thread.sleep(150);
            }
        }
        return so;
    }

    JsonNode line(String so) {
        return ok(200, "GET", "/sales/orders/" + so + "/fulfilment", null).get("lines").get(0);
    }

    String lineId(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
    }

    JsonNode rma(String so, int qty, String reason, boolean physical, String complaintId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("orderId", so, "reason", reason, "physicalReturn", physical,
                "lines", List.of(Map.of("orderLineId", lineId(so), "quantity", qty))));
        if (complaintId != null) {
            body.put("complaintId", complaintId);
        }
        return ok(201, "POST", "/sales/returns", body);
    }

    @Test
    @DisplayName("restock + credit note: stock back, gross invoiced kept, credited 5, AR ties out")
    void restockCreditNote() throws Exception {
        String so = deliveredOrder(20, true);
        assertThat(as("POST", "/sales/returns", Map.of("orderId", so, "reason", "wrong_grade", "lines", List.of(Map.of(
                "orderLineId", lineId(so), "quantity", 21)))).status()).isEqualTo(400);
        String id = rma(so, 5, "wrong_grade", true, null).get("id").asString();
        assertThat(as("POST", "/sales/returns/" + id + "/receive", Map.of()).status()).isEqualTo(400);
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        BigDecimal before = onHand(yard);
        JsonNode received = money("/sales/returns/" + id + "/receive", Map.of());
        assertThat(received.get("status").asString()).isEqualTo("received");
        assertThat(onHand(yard)).isEqualByComparingTo(before.add(new BigDecimal("5")));
        ledger.entriesBalanced("sales_return", id);
        assertThat(line(so).get("qtyCredited").asDouble()).isZero();
        money("/sales/returns/" + id + "/inspect", Map.of());
        JsonNode closed = money("/sales/returns/" + id + "/close", Map.of("outcome", "credit_note"));
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(closed.get("creditAmount").asDouble()).isEqualTo(5 * 2500);
        JsonNode l = line(so);
        assertThat(l.get("qtyInvoiced").asDouble()).isEqualTo(20);
        assertThat(l.get("qtyCredited").asDouble()).isEqualTo(5);
        assertThat(ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("qty").get("returned").asDouble())
                .isEqualTo(5);
        ledger.receivableTiesOut(customer);
        // the same goods cannot be returned twice beyond what was delivered
        assertThat(as("POST", "/sales/returns", Map.of("orderId", so, "reason", "damaged", "lines", List.of(Map.of(
                "orderLineId", lineId(so), "quantity", 16)))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("scrap disposition issues the returned goods as waste; quarantine moves them; refund outcome pays back")
    void scrapQuarantineRefund() throws Exception {
        String so = deliveredOrder(10, true);
        pay(so, 10 * 2500);
        String id = rma(so, 2, "damaged", true, null).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        BigDecimal before = onHand(yard);
        money("/sales/returns/" + id + "/receive", Map.of());
        String retLine = ok(200, "GET", "/sales/returns/" + id, null).get("lines").get(0).get("id").asString();
        money("/sales/returns/" + id + "/inspect", Map.of("lines", List.of(Map.of("lineId", retLine, "disposition",
                "scrap", "notes", "Cracked"))));
        assertThat(onHand(yard)).isEqualByComparingTo(before);
        JsonNode closed = money("/sales/returns/" + id + "/close", Map.of("outcome", "refund", "paymentMethod", "bank"));
        assertThat(closed.get("refundPaymentId").isNull()).isFalse();
        ledger.trialBalanceZero();

        String id2 = rma(so, 3, "quality_rejection", true, null).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id2 + "/approve", null);
        money("/sales/returns/" + id2 + "/receive", Map.of());
        String l2 = ok(200, "GET", "/sales/returns/" + id2, null).get("lines").get(0).get("id").asString();
        assertThat(call("POST", "/sales/returns/" + id2 + "/inspect", Map.of("lines", List.of(Map.of("lineId", l2,
                "disposition", "quarantine"))), key()).status()).isEqualTo(400);
        money("/sales/returns/" + id2 + "/inspect", Map.of("lines", List.of(Map.of("lineId", l2, "disposition",
                "quarantine", "locationId", quarantine))));
        assertThat(onHand(quarantine)).isEqualByComparingTo("3");
    }

    String invoiceOf(String so) {
        return jdbc.sql("SELECT k.\"documentId\" FROM \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id = k.\"documentId\""
                + " WHERE k.\"orderId\" = ? AND k.kind = 'regular' LIMIT 1").param(so).query(String.class).single();
    }

    void pay(String so, int amount) {
        money("/payments", Map.of("partnerId", customer, "paymentDate", LocalDate.now().toString(), "amount", amount,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", invoiceOf(so), "amount", amount))));
    }

    BigDecimal residual(String documentId) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(documentId)
                .query(BigDecimal.class).single();
    }

    BigDecimal refunded(String returnId) {
        return jdbc.sql("SELECT COALESCE((SELECT p.amount FROM \"SalesReturn\" r JOIN \"Payment\" p ON p.id ="
                + " r.\"refundPaymentId\" WHERE r.id = ?), 0)").param(returnId).query(BigDecimal.class).single();
    }

    /** Approved, received and inspected return of {@code qty}, closed with outcome refund. */
    JsonNode closeWithRefund(String so, int qty) {
        String id = rma(so, qty, "damaged", true, null).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        money("/sales/returns/" + id + "/receive", Map.of());
        money("/sales/returns/" + id + "/inspect", Map.of());
        return money("/sales/returns/" + id + "/close", Map.of("outcome", "refund", "paymentMethod", "bank"));
    }

    @Test
    @DisplayName("#4 refund on an unpaid invoice pays no cash: the credit already reduced what the customer owes")
    void refundOnUnpaidInvoice() throws Exception {
        String so = deliveredOrder(10, true);
        JsonNode closed = closeWithRefund(so, 2);
        assertThat(refunded(closed.get("id").asString())).as("no cash on top of the reduction").isZero();
        assertThat(closed.get("refundPaymentId").isNull()).isTrue();
        assertThat(residual(invoiceOf(so))).isEqualByComparingTo("20000");
        assertThat(residual(closed.get("creditNoteId").asString())).isZero();
        ledger.receivableTiesOut(customer);
    }

    @Test
    @DisplayName("#4 refund on a paid invoice pays the credit in cash once and leaves none of it open")
    void refundOnPaidInvoice() throws Exception {
        String so = deliveredOrder(10, true);
        pay(so, 25000);
        JsonNode closed = closeWithRefund(so, 10);
        assertThat(refunded(closed.get("id").asString())).isEqualByComparingTo("25000");
        assertThat(residual(closed.get("creditNoteId").asString())).as("cash paid, so no credit left to reuse").isZero();
        ledger.receivableTiesOut(customer);
    }

    @Test
    @DisplayName("#4 refund on a partly paid invoice: credit clears the balance, only the rest is paid in cash")
    void refundOnPartlyPaidInvoice() throws Exception {
        String so = deliveredOrder(10, true);
        pay(so, 22000);
        JsonNode closed = closeWithRefund(so, 2);
        assertThat(residual(invoiceOf(so))).as("3000 of the 5000 credit clears the balance").isZero();
        assertThat(refunded(closed.get("id").asString())).as("total benefit equals the credit").isEqualByComparingTo("2000");
        assertThat(residual(closed.get("creditNoteId").asString())).isZero();
        ledger.receivableTiesOut(customer);
    }

    @Test
    @DisplayName("#4 replaying a refund close with the same Idempotency-Key pays once")
    void refundReplay() throws Exception {
        String so = deliveredOrder(10, true);
        pay(so, 25000);
        String id = rma(so, 2, "damaged", true, null).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        money("/sales/returns/" + id + "/receive", Map.of());
        money("/sales/returns/" + id + "/inspect", Map.of());
        Map<String, String> h = key();
        Map<String, Object> body = Map.of("outcome", "refund", "paymentMethod", "bank");
        Res first = call("POST", "/sales/returns/" + id + "/close", body, h);
        Res replay = call("POST", "/sales/returns/" + id + "/close", body, h);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(replay.status()).as(replay.raw().body()).isEqualTo(200);
        assertThat(replay.body().get("refundPaymentId").asString()).isEqualTo(first.body().get("refundPaymentId").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"Payment\" WHERE reference = ?").param("Refund " + first.body().get(
                "number").asString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("#5 a credit note whose restock fails posts nothing; scrap disposition still posts")
    void creditNoteRestockFailure() {
        String batched = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"batchTracking\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable'::\"ProductType\", true, true, 10, 20, now())").params(batched, demoOrg, "RBT-"
                        + UUID.randomUUID().toString().substring(0, 8), "Batched " + sfx).update();
        String own = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(own, demoOrg, "RCB-" + UUID.randomUUID().toString()
                        .substring(0, 8), "Restock cust " + sfx).update();
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", own, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("productId", batched, "quantity", 1, "unitPrice", 20)))).get("id").asString();
        Res failed = as("POST", "/credit-notes/" + cn + "/post", null);
        assertThat(failed.status()).as(failed.raw().body()).isEqualTo(400);
        assertThat(failed.raw().body()).contains("scrap");
        assertThat(jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(cn).query(String.class).single())
                .isEqualTo("draft");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceId\" = ?").param(cn).query(Long.class)
                .single()).as("no journal left behind").isZero();
        ok(200, "POST", "/credit-notes/" + cn + "/post?disposition=scrap", null);
        assertThat(jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(cn).query(String.class).single())
                .isEqualTo("posted");
    }

    @Test
    @DisplayName("claim without goods on an uninvoiced order lowers the billable quantity by a decision; replacement order")
    void claimAndReplacement() throws Exception {
        String so = deliveredOrder(10, false);
        String id = rma(so, 1, "short_quantity", false, null).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        assertThat(call("POST", "/sales/returns/" + id + "/receive", Map.of(), key()).status()).isEqualTo(400);
        money("/sales/returns/" + id + "/close", Map.of("outcome", "credit_note"));
        JsonNode l = line(so);
        assertThat(l.get("qtyBillable").asDouble()).isEqualTo(9);
        assertThat(l.get("decisions").get(0).get("reason").asString()).contains("Returned on");

        String so2 = deliveredOrder(4, false);
        String id2 = rma(so2, 2, "damaged", true, null).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id2 + "/approve", null);
        money("/sales/returns/" + id2 + "/receive", Map.of());
        money("/sales/returns/" + id2 + "/inspect", Map.of());
        JsonNode closed = money("/sales/returns/" + id2 + "/close", Map.of("outcome", "replacement"));
        JsonNode rep = ok(200, "GET", "/sales/orders/" + closed.get("replacementOrderId").asString(), null);
        assertThat(rep.get("status").asString()).isEqualTo("draft");
        assertThat(rep.get("lines").get(0).get("quantity").asDouble()).isEqualTo(2);
        assertThat(rep.get("total").asDouble()).isZero();
    }

    @Test
    @DisplayName("complaint → return → resolved; quality certificate; reject / cancel")
    void complaintsAndCertificates() throws Exception {
        String so = deliveredOrder(6, false);
        JsonNode c = ok(201, "POST", "/sales/complaints", Map.of("orderId", so, "category", "quality", "description",
                "Blocks crumble"));
        assertThat(c.get("number").asString()).startsWith("CMP-");
        assertThat(as("POST", "/sales/complaints", Map.of("orderId", so, "category", "x", "description", "y")).status())
                .isEqualTo(400);
        String id = rma(so, 1, "quality_rejection", true, c.get("id").asString()).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        money("/sales/returns/" + id + "/receive", Map.of());
        money("/sales/returns/" + id + "/inspect", Map.of());
        money("/sales/returns/" + id + "/close", Map.of("outcome", "none"));
        JsonNode list = ok(200, "GET", "/sales/complaints?orderId=" + so, null);
        assertThat(list.get(0).get("status").asString()).isEqualTo("resolved");
        assertThat(list.get(0).get("returnNumber").asString()).startsWith("RMA-");
        JsonNode cert = ok(201, "POST", "/sales/quality-certificates", Map.of("orderId", so, "productId", blocks,
                "certificateNumber", "QC-" + sfx, "batchNumber", "B1", "results", Map.of("strength_mpa", 7.2)));
        assertThat(cert.get("results").get("strength_mpa").asDouble()).isEqualTo(7.2);
        assertThat(ok(200, "GET", "/sales/quality-certificates?orderId=" + so, null).size()).isEqualTo(1);
        String r2 = rma(so, 1, "other", true, null).get("id").asString();
        assertThat(ok(200, "POST", "/sales/returns/" + r2 + "/reject", Map.of("reason", "Not ours")).get("status")
                .asString()).isEqualTo("rejected");
        String r3 = rma(so, 1, "other", true, null).get("id").asString();
        assertThat(ok(200, "POST", "/sales/returns/" + r3 + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(LocalDate.now()).isNotNull();
    }
}
