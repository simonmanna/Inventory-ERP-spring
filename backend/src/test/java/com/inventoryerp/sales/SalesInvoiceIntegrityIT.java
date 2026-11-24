package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Sales readiness R1 (B1): an invoice generated from a sales order cannot be edited away from the order, and the post
 * guard refuses a draft whose lines no longer match the order lines they are linked to (each tamper is forced by SQL to
 * prove the guard on its own). A generated draft can be discarded, which makes the order invoiceable again.
 */
class SalesInvoiceIntegrityIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String product;
    String other;

    @BeforeAll
    void fixtures() {
        product = product("Blocks R1 " + sfx);
        other = product("Other R1 " + sfx);
    }

    String product(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'service'::\"ProductType\", 1000, 0, false, 'ordered', now())").params(id, demoOrg, "R1-"
                        + UUID.randomUUID().toString().substring(0, 8), name).update();
        return id;
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "R1C-" + UUID.randomUUID().toString()
                        .substring(0, 8), "R1 cust " + sfx).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    String order(String partner, int qty) {
        String id = ok(201, "POST", "/sales/orders", Map.of("partnerId", partner, "lines", List.of(Map.of("productId",
                product, "quantity", qty)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + id + "/confirm", null);
        return id;
    }

    JsonNode draftInvoice(String so, Map<String, Object> body) {
        Res r = call("POST", "/sales/orders/" + so + "/invoices", body, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String lineOf(String invoiceId) {
        return jdbc.sql("SELECT id FROM \"DocumentLine\" WHERE \"documentId\" = ? ORDER BY \"lineNumber\" LIMIT 1")
                .param(invoiceId).query(String.class).single();
    }

    Res post(String invoiceId) {
        return call("POST", "/invoices/" + invoiceId + "/post", null, key());
    }

    @Test
    @DisplayName("a sales-order draft cannot be edited; it can be discarded and the order invoiced again")
    void editRefusedDiscardReinvoice() {
        String c = customer();
        String so = order(c, 10);
        JsonNode inv = draftInvoice(so, Map.of("mode", "regular"));
        String id = inv.get("id").asString();
        Res edit = call("PATCH", "/invoices/" + id, Map.of("partnerId", c, "issueDate", inv.get("issueDate").asString(),
                "lines", List.of(Map.of("productId", product, "description", "more", "quantity", 50, "unitPrice", 1000))),
                key());
        assertThat(edit.status()).as(edit.raw().body()).isEqualTo(400);
        assertThat(edit.raw().body()).contains("generated from sales order");
        assertThat(jdbc.sql("SELECT quantity FROM \"DocumentLine\" WHERE \"documentId\" = ?").param(id)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("10");

        // nothing left to invoice while the draft stands; discarding it releases the quantity
        assertThat(call("POST", "/sales/orders/" + so + "/invoices", Map.of("mode", "regular"), key()).status())
                .isEqualTo(400);
        Res discard = call("POST", "/invoices/" + id + "/cancel", Map.of("reason", "wrong date"), key());
        assertThat(discard.status()).as(discard.raw().body()).isEqualTo(200);
        assertThat(discard.body().get("status").asString()).isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'invoice' AND \"sourceId\" = ?")
                .param(id).query(Long.class).single()).isZero();
        String again = draftInvoice(so, Map.of("mode", "regular")).get("id").asString();
        Res posted = post(again);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
    }

    void tampered(String what, Consumer<String> tamper) {
        String so = order(customer(), 10);
        String id = draftInvoice(so, Map.of("mode", "regular")).get("id").asString();
        tamper.accept(id);
        Res r = post(id);
        assertThat(r.status()).as(what + ": " + r.raw().body()).isEqualTo(409);
        assertThat(r.raw().body()).as(what).contains("cancel this draft");
        assertThat(jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(id).query(String.class).single())
                .as(what).isEqualTo("draft");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'invoice' AND \"sourceId\" = ?")
                .param(id).query(Long.class).single()).as(what).isZero();
    }

    @Test
    @DisplayName("the post guard refuses a draft whose lines drifted from the order lines they are linked to")
    void postGuardRefusesTamperedLines() {
        String tax = jdbc.sql("SELECT id FROM \"Tax\" WHERE \"organizationId\" = ? ORDER BY id LIMIT 1").param(demoOrg)
                .query(String.class).optional().orElse(null);
        tampered("quantity", id -> jdbc.sql("UPDATE \"DocumentLine\" SET quantity = 50, total = 50000, subtotal = 50000"
                + " WHERE id = ?").param(lineOf(id)).update());
        tampered("price", id -> jdbc.sql("UPDATE \"DocumentLine\" SET \"unitPrice\" = 5000, total = 50000, subtotal ="
                + " 50000 WHERE id = ?").param(lineOf(id)).update());
        tampered("product", id -> jdbc.sql("UPDATE \"DocumentLine\" SET \"productId\" = ? WHERE id = ?").params(other,
                lineOf(id)).update());
        tampered("discount", id -> jdbc.sql("UPDATE \"DocumentLine\" SET \"discountPercent\" = 50 WHERE id = ?")
                .param(lineOf(id)).update());
        if (tax != null) {
            tampered("tax", id -> jdbc.sql("UPDATE \"DocumentLine\" SET \"taxId\" = ? WHERE id = ?").params(tax,
                    lineOf(id)).update());
        }
        tampered("extra line", id -> jdbc.sql("INSERT INTO \"DocumentLine\" (id, \"organizationId\", \"documentId\","
                + " \"productId\", description, quantity, \"unitPrice\", subtotal, total, \"lineNumber\") VALUES (?, ?, ?,"
                + " ?, 'extra', 5, 1000, 5000, 5000, 99)").params(UUID.randomUUID().toString(), demoOrg, id, product)
                .update());
    }

    @Test
    @DisplayName("a down-payment draft that no longer matches its recorded down payment does not post")
    void downPaymentTamperRefused() {
        String so = order(customer(), 10);
        String id = draftInvoice(so, Map.of("mode", "down_payment", "amount", 3000)).get("id").asString();
        jdbc.sql("UPDATE \"DocumentLine\" SET \"unitPrice\" = 9000, subtotal = 9000, total = 9000 WHERE \"documentId\" = ?")
                .param(id).update();
        jdbc.sql("UPDATE \"Document\" SET \"totalAmount\" = 9000, subtotal = 9000, \"amountResidual\" = 9000 WHERE id = ?")
                .param(id).update();
        Res r = post(id);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(409);
        assertThat(r.raw().body()).contains("down payment");
    }
}
