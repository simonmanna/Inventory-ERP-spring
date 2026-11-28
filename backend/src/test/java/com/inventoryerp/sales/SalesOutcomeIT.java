package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C2, §4.4 / §4.5: the financial outcome of a sale (FO), the completion rule (CS), zero-value sales (AD-19) and
 * keeping credit on the customer's account. Everything through the HTTP API.
 */
class SalesOutcomeIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    PlatformTransactionManager txm;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String item;
    String yard;
    String customer;
    String adminId;
    String checkerToken;
    String readerToken;

    String user(String name, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, name + " " + sfx, permissions).update();
        String email = name + "-" + sfx + "@demo.test";
        createUser(demoOrg, email, "Outcome@12345", role);
        return login("DEMO", email, "Outcome@12345");
    }

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        item = ok(201, "POST", "/products", Map.of("code", "OUT-" + sfx, "name", "Outcome item " + sfx, "productType",
                "stockable", "trackInventory", true, "salesPrice", 100, "costPrice", 30)).get("id").asString();
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "OUY-" + sfx, "name", "Outcome yard " + sfx,
                "type", "warehouse")).get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "OUC-" + sfx, "Outcome cust " + sfx)
                .update();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(item, yard, new BigDecimal("2000"))
                        .unitCost(new BigDecimal("30")))));
        checkerToken = user("outcome-checker", "sales:return:read", "sales:return:approve", "sales:order:read",
                "sales:order:read_all", "sales:order:approve");
        readerToken = user("outcome-reader", "sales:order:read", "sales:order:read_all");
    }

    // ------------------------------------------------------------------ helpers

    static String today() {
        return LocalDate.now(ZoneId.of("Africa/Kampala")).toString();
    }

    JsonNode money(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", path, body, h);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    Res attempt(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", path, body, h);
    }

    String order(int qty, int price) {
        return orderFor(customer, qty, price);
    }

    String orderFor(String partner, int qty, int price) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", partner, "locationId", yard, "paymentMode",
                "credit", "lines", List.of(Map.of("productId", item, "quantity", qty, "unitPrice", price)))).get("id")
                .asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        return so;
    }

    /** A confirmed order that went through approval when its rules ask for it (a price-0 line does). */
    String approvedOrder(int qty, int price) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines",
                List.of(Map.of("productId", item, "quantity", qty, "unitPrice", price)))).get("id").asString();
        if ("required".equals(sale(so).get("approvalStatus").asString())) {
            ok(200, "POST", "/sales/orders/" + so + "/submit-approval", null);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Authorization", "Bearer " + checkerToken);
            h.put("Idempotency-Key", UUID.randomUUID().toString());
            Res approved = call("POST", "/sales/orders/" + so + "/approve", Map.of(), h);
            assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        }
        if ("draft".equals(sale(so).get("status").asString())) {
            ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        }
        return so;
    }

    JsonNode sale(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null);
    }

    String lineId(String so) {
        return sale(so).get("lines").get(0).get("id").asString();
    }

    JsonNode line(String so) {
        return ok(200, "GET", "/sales/orders/" + so + "/fulfilment", null).get("lines").get(0);
    }

    void deliver(String so, int qty) {
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAO 1", "lines",
                List.of(Map.of("lineId", lineId(so), "quantity", qty))));
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", qty))));
        drainOutboxFully();
    }

    String invoice(String so) {
        String inv = money("/sales/orders/" + so + "/invoices", Map.of()).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        drainOutboxFully();
        return inv;
    }

    String pay(String inv, int amount, String method) {
        String id = money("/payments", Map.of("partnerId", customer, "paymentDate", today(), "amount", amount,
                "paymentMethod", method, "allocations", List.of(Map.of("documentId", inv, "amount", amount))))
                .get("id").asString();
        drainOutboxFully();
        return id;
    }

    JsonNode returned(String so, int qty, String outcome) {
        String r = ok(201, "POST", "/sales/returns", Map.of("orderId", so, "reason", "damaged", "lines",
                List.of(Map.of("orderLineId", lineId(so), "quantity", qty)))).get("id").asString();
        ok(200, "POST", "/sales/returns/" + r + "/approve", null);
        money("/sales/returns/" + r + "/receive", Map.of());
        money("/sales/returns/" + r + "/inspect", Map.of());
        JsonNode closed = money("/sales/returns/" + r + "/close", Map.of("outcome", outcome));
        drainOutboxFully();
        return closed;
    }

    static double num(JsonNode n, String field) {
        return n.get(field).asDouble();
    }

    // ------------------------------------------------------------------ FO: financial outcome

    @Test
    @DisplayName("FO-1 the outcome follows receipts: unpaid → partially paid → paid and settled; payment never holds"
            + " an order back; the stored copy follows the receipt events")
    void paymentFollowsReceipts() {
        String so = order(10, 100);
        deliver(so, 10);
        String inv = invoice(so);
        JsonNode s = sale(so);
        assertThat(s.get("status").asString()).isEqualTo("done");
        assertThat(s.get("paymentStatus").asString()).isEqualTo("unpaid");
        assertThat(num(s, "amountInvoiced")).isEqualTo(1000);
        assertThat(num(s, "amountOutstanding")).isEqualTo(1000);
        assertThat(s.get("settlementStatus").asString()).isEqualTo("open");

        pay(inv, 400, "bank");
        s = sale(so);
        assertThat(s.get("paymentStatus").asString()).isEqualTo("partially_paid");
        assertThat(num(s, "amountReceived")).isEqualTo(400);
        assertThat(num(s, "amountOutstanding")).isEqualTo(600);
        assertThat(ok(200, "GET", "/sales/orders?partnerId=" + customer + "&paymentStatus=partially_paid", null)
                .toString()).as("stored by the payment.allocated event").contains(so);

        pay(inv, 600, "bank");
        s = sale(so);
        assertThat(s.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(s.get("settlementStatus").asString()).isEqualTo("settled");
        assertThat(ok(200, "GET", "/sales/orders?partnerId=" + customer + "&settlementStatus=settled", null)
                .toString()).contains(so);
    }

    @Test
    @DisplayName("FO-2 a credit after full payment leaves a credit balance; keeping it as customer credit settles the"
            + " sale (permission, audit, once); a refund goes back over the original tender (AD-9)")
    void creditBalanceKeptOrRefunded() {
        String so = order(10, 100);
        deliver(so, 10);
        pay(invoice(so), 1000, "bank");
        returned(so, 2, "credit_note");
        JsonNode s = sale(so);
        assertThat(s.get("paymentStatus").asString()).isEqualTo("credit_balance");
        assertThat(s.get("creditStatus").asString()).isEqualTo("partial");
        assertThat(num(s, "amountOutstanding")).isEqualTo(-200);
        assertThat(num(s, "openCredit")).isEqualTo(200);
        assertThat(s.get("actions").toString()).contains("keep_credit");

        assertThat(call("POST", "/sales/orders/" + so + "/keep-credit", Map.of("reason", "Next order"), readerToken)
                .status()).isEqualTo(403);
        assertThat(as("POST", "/sales/orders/" + so + "/keep-credit", Map.of()).status()).isEqualTo(400);
        JsonNode kept = ok(200, "POST", "/sales/orders/" + so + "/keep-credit", Map.of("reason", "Next order"));
        assertThat(kept.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(kept.get("settlementStatus").asString()).isEqualTo("settled");
        assertThat(num(kept, "amountRetainedAsCredit")).isEqualTo(200);
        assertThat(num(kept, "amountReceived")).isEqualTo(1000);
        assertThat(kept.get("actions").toString()).doesNotContain("keep_credit");
        assertThat(as("POST", "/sales/orders/" + so + "/keep-credit", Map.of("reason", "again")).body().get("message")
                .asString()).isEqualTo("This sale has no open credit to keep");
        long audited = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> new TransactionTemplate(
                txm).execute(t -> jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND"
                        + " \"newValues\"->>'creditKept' IS NOT NULL").param(so).query(Long.class).single()));
        assertThat(audited).isEqualTo(1);

        String so2 = order(10, 100);
        deliver(so2, 10);
        pay(invoice(so2), 1000, "cash");
        JsonNode closed = returned(so2, 2, "refund");
        assertThat(ok(200, "GET", "/payments/" + closed.get("refundPaymentId").asString(), null).get("paymentMethod")
                .asString()).as("AD-9: refunded over the tender the invoice was paid with").isEqualTo("cash");
        JsonNode s2 = sale(so2);
        assertThat(s2.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(num(s2, "amountRefunded")).isEqualTo(200);
        assertThat(num(s2, "amountReceived")).isEqualTo(1000);
        assertThat(s2.get("settlementStatus").asString()).isEqualTo("settled");
    }

    @Test
    @DisplayName("FO-3 / CS-1 fully returned and credited before payment: credited, nothing billable, still done")
    void fullyCreditedBeforePayment() {
        String so = order(10, 100);
        deliver(so, 10);
        invoice(so);
        returned(so, 10, "credit_note");
        JsonNode s = sale(so);
        assertThat(s.get("paymentStatus").asString()).isEqualTo("credited");
        assertThat(s.get("creditStatus").asString()).isEqualTo("full");
        assertThat(s.get("settlementStatus").asString()).isEqualTo("settled");
        assertThat(s.get("status").asString()).isEqualTo("done");
        JsonNode l = line(so);
        assertThat(num(l, "qtyCredited")).isEqualTo(10);
        assertThat(num(l, "qtyReturnCredited")).isEqualTo(10);
        assertThat(num(l, "qtyBillable")).isZero();
    }

    @Test
    @DisplayName("FO-4 / CS-3 a 100% value credit refunded: refunded, quantities untouched; a 'return' credit on a sale"
            + " invoice is refused (returned goods go through a sales return)")
    void valueCreditRefunded() {
        String so = order(10, 100);
        deliver(so, 10);
        String inv = invoice(so);
        pay(inv, 1000, "bank");
        assertThat(attempt("/sales/credit-notes", Map.of("partnerId", customer, "invoiceId", inv, "type", "return",
                "reason", "Back", "lines", List.of(Map.of("description", "Back", "quantity", 1, "unitPrice", 100))))
                .body().get("message").asString()).isEqualTo("Goods returned on a sales order are credited through a"
                        + " sales return, so the stock and the order quantities follow");
        String cn = money("/sales/credit-notes", Map.of("partnerId", customer, "invoiceId", inv, "type", "goodwill",
                "reason", "Goodwill", "lines", List.of(Map.of("description", "Goodwill", "quantity", 1, "unitPrice",
                        1000)))).get("id").asString();
        money("/sales/credit-notes/" + cn + "/refund", Map.of("paymentMethod", "bank"));
        drainOutboxFully();
        JsonNode s = sale(so);
        assertThat(s.get("paymentStatus").asString()).isEqualTo("refunded");
        assertThat(s.get("creditStatus").asString()).isEqualTo("full");
        assertThat(num(s, "amountRefunded")).isEqualTo(1000);
        assertThat(num(line(so), "qtyCredited")).as("a value credit carries no quantity").isZero();
        String kind = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> new TransactionTemplate(
                txm).execute(t -> jdbc.sql("SELECT data->>'creditKind' FROM \"Document\" WHERE id = ?").param(cn)
                        .query(String.class).single()));
        assertThat(kind).isEqualTo("value");
    }

    @Test
    @DisplayName("FO-5 / CS-4 a zero-value sale (AD-19) is done when delivered, charges nothing and cannot be invoiced")
    void zeroValueSale() {
        String so = approvedOrder(5, 0);
        deliver(so, 5);
        JsonNode s = sale(so);
        assertThat(s.get("status").asString()).isEqualTo("done");
        assertThat(s.get("paymentStatus").asString()).isEqualTo("no_charge");
        assertThat(s.get("settlementStatus").asString()).isEqualTo("settled");
        assertThat(num(line(so), "qtyBillable")).isZero();
        assertThat(attempt("/sales/orders/" + so + "/invoices", Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("FO-6 a bad-debt write-off is not a credit: written off, settled, nothing received")
    void badDebtWriteOff() {
        String so = order(10, 100);
        deliver(so, 10);
        String inv = invoice(so);
        String badDebt = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\" AND a.\"isActive\""
                + " AND a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).single();
        money("/sales/invoices/" + inv + "/write-off", Map.of("reason", "Customer closed", "amount", 1000, "accountId",
                badDebt));
        drainOutboxFully();
        JsonNode s = sale(so);
        assertThat(s.get("paymentStatus").asString()).isEqualTo("written_off");
        assertThat(s.get("creditStatus").asString()).isEqualTo("none");
        assertThat(num(s, "amountWrittenOff")).isEqualTo(1000);
        assertThat(num(s, "amountReceived")).isZero();
        assertThat(s.get("settlementStatus").asString()).isEqualTo("settled");
    }

    // ------------------------------------------------------------------ CS: completion

    @Test
    @DisplayName("CS returned and credited before invoicing: no longer billable, the order invoices the rest and is done")
    void returnBeforeInvoicing() {
        String so = order(10, 100);
        deliver(so, 10);
        returned(so, 2, "credit_note");
        JsonNode l = line(so);
        assertThat(num(l, "qtyBillable")).isEqualTo(8);
        assertThat(num(l, "qtyReturnCredited")).isEqualTo(2);
        assertThat(l.get("decisions").get(0).get("reason").asString()).startsWith("Returned on ");
        invoice(so);
        JsonNode s = sale(so);
        assertThat(num(s, "amountInvoiced")).isEqualTo(800);
        assertThat(s.get("status").asString()).isEqualTo("done");

        String so2 = order(10, 100);
        deliver(so2, 10);
        returned(so2, 2, "replacement");
        assertThat(num(line(so2), "qtyBillable")).as("goods replaced stay billable").isEqualTo(10);
    }

    @Test
    @DisplayName("a return credited after a partial invoice: the rest is invoiced net of the credit, posts, reconciles"
            + " clean and the order is done")
    void remainderAfterCreditedReturn() {
        String so = order(10, 100);
        deliver(so, 5);
        invoice(so);
        returned(so, 2, "credit_note");
        assertThat(num(line(so), "qtyBillable")).isEqualTo(3);
        deliver(so, 5);
        JsonNode l = line(so);
        assertThat(num(l, "qtyBillable")).as("10 delivered − 2 returned and credited").isEqualTo(8);
        assertThat(num(l, "qtyCredited")).isEqualTo(2);
        invoice(so);
        l = line(so);
        assertThat(num(l, "qtyInvoiced")).isEqualTo(10);
        JsonNode s = sale(so);
        assertThat(num(s, "amountInvoiced")).isEqualTo(1000);
        assertThat(num(s, "amountCredited")).isEqualTo(200);
        assertThat(s.get("status").asString()).isEqualTo("done");
        JsonNode recon = money("/sales/billing/reconcile", null);
        for (JsonNode f : recon.get("findings")) {
            assertThat(so.equals(f.path("orderId").asString()) && "over_invoiced".equals(f.path("rule").asString()))
                    .as(f.toString()).isFalse();
        }
    }

    @Test
    @DisplayName("credit exposure: goods returned and credited before invoicing are settled, not undelivered; the rest"
            + " is delivered and not yet invoiced, until it is invoiced")
    void exposureAfterReturn() {
        String partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(partner, demoOrg, "OUX-" + sfx, "Exposure cust " + sfx)
                .update();
        String so = orderFor(partner, 10, 100);
        deliver(so, 10);
        returned(so, 2, "credit_note");
        JsonNode e = ok(200, "GET", "/credit/partners/" + partner + "/exposure", null).get("components");
        assertThat(e.get("undelivered").asDouble()).isZero();
        assertThat(e.get("deliveredUninvoiced").asDouble()).isEqualTo(800);
        invoice(so);
        e = ok(200, "GET", "/credit/partners/" + partner + "/exposure", null).get("components");
        assertThat(e.get("deliveredUninvoiced").asDouble()).isZero();
    }

    @Test
    @DisplayName("CS an open return holds the order back until it is settled, rejected or cancelled")
    void openReturnHoldsCompletion() {
        String so = order(10, 100);
        deliver(so, 10);
        String r = ok(201, "POST", "/sales/returns", Map.of("orderId", so, "reason", "damaged", "lines",
                List.of(Map.of("orderLineId", lineId(so), "quantity", 1)))).get("id").asString();
        invoice(so);
        assertThat(sale(so).get("status").asString()).isEqualTo("confirmed");
        ok(200, "POST", "/sales/returns/" + r + "/reject", Map.of("reason", "Not ours"));
        assertThat(sale(so).get("status").asString()).isEqualTo("done");
    }

    @Test
    @DisplayName("CS-2 a write-off claim (AD-20) needs its own permission and another approver, then leaves the"
            + " billable quantity; the order completes on the rest")
    void writeOffClaim() {
        String so = order(10, 100);
        deliver(so, 10);
        String writer = user("outcome-writer", "sales:return:read", "sales:return:write", "sales:order:read",
                "sales:order:read_all");
        Map<String, Object> claim = Map.of("orderId", so, "reason", "short_quantity", "physicalReturn", false, "lines",
                List.of(Map.of("orderLineId", lineId(so), "quantity", 2)));
        Res refused = call("POST", "/sales/returns", claim, writer);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().get("message").asString())
                .isEqualTo("Crediting goods without a return needs the write-off permission");
        String r = ok(201, "POST", "/sales/returns", claim).get("id").asString();
        assertThat(as("POST", "/sales/returns/" + r + "/approve", null).body().get("message").asString())
                .isEqualTo("You cannot approve your own claim");
        assertThat(call("POST", "/sales/returns/" + r + "/approve", null, checkerToken).status()).isIn(200, 201);
        money("/sales/returns/" + r + "/close", Map.of("outcome", "credit_note"));
        JsonNode l = line(so);
        assertThat(num(l, "qtyWrittenOff")).isEqualTo(2);
        assertThat(num(l, "qtyBillable")).isEqualTo(8);
        assertThat(l.get("decisions").get(0).get("reason").asString()).startsWith("Written off on ");
        invoice(so);
        assertThat(sale(so).get("status").asString()).isEqualTo("done");
    }

    @Test
    @DisplayName("CS-5 / CS-6 an advance left over after closing short holds the order back until it is refunded;"
            + " an order holding an advance cannot be cancelled")
    void advanceHoldsCompletion() {
        String so = order(10, 100);
        String dep = money("/sales/orders/" + so + "/deposits", Map.of("amount", 1000, "paymentMethod", "bank"))
                .get("id").asString();
        Res cancel = attempt("/sales/orders/" + so + "/cancel", Map.of("reason", "Changed mind"));
        assertThat(cancel.status()).isEqualTo(400);
        assertThat(cancel.body().get("message").asString()).isEqualTo("Refund or apply the deposits of this order first");

        deliver(so, 6);
        money("/sales/orders/" + so + "/lines/" + lineId(so) + "/close-short", Map.of("reason", "Customer took 6"));
        String inv = invoice(so);
        money("/sales/orders/" + so + "/apply-deposits", Map.of("documentId", inv));
        JsonNode s = sale(so);
        assertThat(num(s, "advanceHeld")).isEqualTo(400);
        assertThat(s.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(s.get("status").asString()).as("an advance is still held").isEqualTo("confirmed");
        assertThat(s.get("settlementStatus").asString()).isEqualTo("open");

        money("/customer-deposits/" + dep + "/refund", Map.of("amount", 400, "paymentMethod", "bank", "reason",
                "Closed short"));
        s = sale(so);
        assertThat(num(s, "advanceHeld")).isZero();
        assertThat(s.get("status").asString()).isEqualTo("done");
        assertThat(s.get("settlementStatus").asString()).isEqualTo("settled");
    }
}
