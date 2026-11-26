package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Sales readiness R2–R4 (2026-11-26 re-review): required idempotency keys on money routes, settlement validation of
 * receipts and credit notes, the down-payment cap and cancel, the stale-save guard, header tenancy, fully closed-short
 * orders reaching done, and stocked goods leaving only through deliveries.
 */
class SalesReadinessR2R4IT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString();
    String service;
    String stocked;

    @BeforeAll
    void fixtures() {
        service = product("Svc R2 " + sfx, false);
        stocked = product("Goods R2 " + sfx, true);
    }

    String product(String name, boolean track) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " ?::\"ProductType\", 1000, 0, ?, 'ordered', now())").params(id, demoOrg, "R2-" + UUID.randomUUID()
                        .toString().substring(0, 8), name, track ? "stockable" : "service", track).update();
        return id;
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "R2C-" + UUID.randomUUID().toString()
                        .substring(0, 8), "R2 cust " + sfx).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Map<String, String> noKey() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put(NO_IDEMPOTENCY_KEY, "1");
        return h;
    }

    String draft(String partner, String product, int qty) {
        return ok(201, "POST", "/sales/orders", Map.of("partnerId", partner, "lines", List.of(Map.of("productId",
                product, "quantity", qty)))).get("id").asString();
    }

    String confirmed(String partner, int qty) {
        String id = draft(partner, service, qty);
        ok(200, "POST", "/sales/orders/" + id + "/confirm", null);
        return id;
    }

    String postedInvoice(String partner) {
        String so = confirmed(partner, 5);
        Res r = call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        String id = r.body().get("id").asString();
        Res p = call("POST", "/invoices/" + id + "/post", null, key());
        assertThat(p.status()).as(p.raw().body()).isEqualTo(200);
        return id;
    }

    static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(150);
        }
        assertThat(cond.getAsBoolean()).as("condition within 15 s").isTrue();
    }

    @Test
    @DisplayName("R2: money routes refuse a request without an Idempotency-Key")
    void keyRequired() {
        String so = confirmed(customer(), 2);
        Res r = call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), noKey());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.raw().body()).contains("Idempotency-Key header is required");
        assertThat(jdbc.sql("SELECT count(*) FROM \"SalesInvoiceLink\" WHERE \"orderId\" = ?").param(so)
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("R2b: a receipt cannot be allocated to another customer's invoice")
    void receiptPartnerChecked() {
        String a = customer();
        String b = customer();
        String inv = postedInvoice(a);
        Res r = call("POST", "/payments", Map.of("partnerId", b, "paymentDate", today, "amount", 1000, "paymentMethod",
                "bank", "allocations", List.of(Map.of("documentId", inv, "amount", 1000))), key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.raw().body()).contains("is not an open invoice of this partner");
        assertThat(jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(inv)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("R2b: a credit note cannot credit another customer's invoice")
    void creditNotePartnerChecked() {
        String a = customer();
        String b = customer();
        String inv = postedInvoice(a);
        Res r = call("POST", "/credit-notes", Map.of("partnerId", b, "issueDate", today, "reversedDocumentId", inv,
                "lines", List.of(Map.of("productId", service, "description", "refund", "quantity", 1, "unitPrice",
                        1000))), key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.raw().body()).contains("belongs to another customer");
    }

    @Test
    @DisplayName("R2b: down payments together cannot exceed the order; cancelling a down-payment invoice voids its deposit")
    void downPaymentCapAndCancel() throws Exception {
        String c = customer();
        String so = confirmed(c, 10);
        Res first = call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "down_payment", "percent", 60),
                key());
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res second = call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "down_payment", "percent", 50),
                key());
        assertThat(second.status()).as(second.raw().body()).isEqualTo(400);

        String dp = first.body().get("id").asString();
        assertThat(call("POST", "/invoices/" + dp + "/post", null, key()).status()).isEqualTo(200);
        await(() -> jdbc.sql("SELECT count(*) FROM \"CustomerDeposit\" WHERE \"partnerId\" = ? AND source ="
                + " 'down_payment_invoice'").param(c).query(Long.class).single() > 0);
        Res cancel = call("POST", "/invoices/" + dp + "/cancel", Map.of("reason", "customer withdrew"), key());
        assertThat(cancel.status()).as(cancel.raw().body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status::text FROM \"CustomerDeposit\" WHERE \"partnerId\" = ? AND source ="
                + " 'down_payment_invoice'").param(c).query(String.class).single()).isEqualTo("void");
    }

    @Test
    @DisplayName("R4 (SO-11): a save from an older copy of the draft is refused")
    void staleSaveRefused() {
        String c = customer();
        String so = draft(c, service, 3);
        int version = ok(200, "GET", "/sales/orders/" + so, null).get("version").asInt();
        ok(200, "PUT", "/sales/orders/" + so, Map.of("notes", "first edit", "version", version));
        Res stale = call("PUT", "/sales/orders/" + so, Map.of("notes", "second edit", "version", version), key());
        assertThat(stale.status()).as(stale.raw().body()).isEqualTo(409);
        assertThat(ok(200, "GET", "/sales/orders/" + so, null).get("notes").asString()).isEqualTo("first edit");
    }

    @Test
    @DisplayName("R4 (SO-12): header references must belong to the organisation")
    void headerTenancy() {
        Res r = call("POST", "/sales/orders", Map.of("partnerId", customer(), "locationId", UUID.randomUUID()
                .toString(), "lines", List.of(Map.of("productId", service, "quantity", 1))), key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(404);
        assertThat(r.raw().body()).contains("Location not found");
    }

    @Test
    @DisplayName("R4 (SO-7): an order closed short on every line before anything left is done")
    void closedShortReachesDone() {
        String so = confirmed(customer(), 4);
        String line = ok(200, "GET", "/sales/orders/" + so, null).get("lines").get(0).get("id").asString();
        JsonNode closed = ok(200, "POST", "/sales/orders/" + so + "/lines/" + line + "/close-short", Map.of("reason",
                "customer cancelled"));
        assertThat(closed.get("status").asString()).isEqualTo("done");
    }

    @Test
    @DisplayName("R4d (DEL-6): stocked goods are not marked delivered through the handover")
    void handoverRefusedForStockedGoods() {
        String so = draft(customer(), stocked, 2);
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        Res r = call("PATCH", "/sales/orders/" + so + "/handover", Map.of("status", "delivered", "receivedBy", "Joe"),
                key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.raw().body()).contains("leave through deliveries");
    }
}
