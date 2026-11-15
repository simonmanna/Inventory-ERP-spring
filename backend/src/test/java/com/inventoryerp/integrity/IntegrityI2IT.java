package com.inventoryerp.integrity;

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
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Phase I2 (revised) regression tests for the 2026-11-15 review
 * ({@code docs/audits/2026-11-15-phase-I-regression-and-gap-review.md}): each test reproduces a reviewed defect.
 */
class IntegrityI2IT extends ApiTestSupport {

    @Autowired
    StockService stock;

    String warehouse;
    String customer;
    String repToken;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        // Sales invoices move stock in the organization's oldest active warehouse; credit notes restock at the POS
        // stock location (M-2), so pin that to the same warehouse.
        warehouse = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1").param(demoOrg)
                .query(String.class).single();
        ok(200, "PUT", "/settings/pos.stockLocationId", Map.of("value", warehouse));
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "I2C-" + sfx(), "I2 customer " + sfx())
                .update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "I2 rep " + sfx(), new String[] {
                    "sales:order:read", "sales:order:write", "sales:order:confirm", "sales:order:amend"}).update();
        String email = "i2-rep-" + sfx() + "@demo.test";
        createUser(demoOrg, email, "Sales@12345", role);
        repToken = login("DEMO", email, "Sales@12345");
    }

    // ------------------------------------------------------------------ helpers

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 1, now())")
                .params(id, demoOrg, code + "-" + sfx(), code).update();
        return id;
    }

    /** Counted in {@code base}, sold by {@code salesUnit} holding {@code factor} base units; 100 on hand. */
    String packed(String name, String base, String salesUnit, int factor) {
        String p = stocked(name, 0);
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"salesUomId\" = ? WHERE id = ?").params(base, salesUnit, p)
                .update();
        ok(200, "PATCH", "/products/" + p, Map.of("units", List.of(map("uomId", salesUnit, "factor", factor,
                "isPurchase", true, "isSales", true))));
        TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(p, warehouse,
                BigDecimal.valueOf(100)).unitCost(BigDecimal.TEN)));
        return p;
    }

    /** A product with variants Red and Blue, 50 of each on hand. Returns product, Red, Blue. */
    Map<String, String> coloured(String name) {
        String p = stocked(name, 0);
        JsonNode attr = ok(201, "POST", "/variant-attributes", Map.of("name", "Colour " + sfx(), "values",
                List.of(Map.of("name", "Red"), Map.of("name", "Blue"))));
        JsonNode cfg = ok(200, "PUT", "/products/" + p + "/attribute-lines", Map.of("lines", List.of(Map.of(
                "attributeId", attr.get("id").asString(), "valueIds", List.of(attr.get("values").get(0).get("id")
                        .asString(), attr.get("values").get(1).get("id").asString())))));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("product", p);
        for (JsonNode v : cfg.get("variants")) {
            out.put(v.get("name").asString(), v.get("id").asString());
            String vid = v.get("id").asString();
            TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(p, warehouse,
                    BigDecimal.valueOf(50)).variant(vid).unitCost(BigDecimal.TEN)));
        }
        return out;
    }

    /** A confirmed sales order with one line; returns {order, line}. */
    String[] confirmedOrder(Map<String, Object> line) {
        Res created = call("POST", "/sales/orders", Map.of("partnerId", customer, "locationId", warehouse, "lines",
                List.of(line)), repToken);
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String id = created.body().get("id").asString();
        Res confirmed = call("POST", "/sales/orders/" + id + "/confirm", null, repToken);
        assertThat(confirmed.status()).as(confirmed.raw().body()).isEqualTo(200);
        String lineId = jdbc.sql("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ?").param(id).query(String.class)
                .single();
        return new String[] {id, lineId};
    }

    /** A change request raised by the rep and approved by the admin. */
    void amend(String orderId, Map<String, Object> lineChange) {
        Res cr = call("POST", "/sales/orders/" + orderId + "/change-requests", Map.of("reason", "review N2", "changes",
                Map.of("lines", List.of(lineChange))), Map.of("Authorization", "Bearer " + repToken));
        assertThat(cr.status()).as(cr.raw().body()).isIn(200, 201);
        if (!"applied".equals(cr.body().get("status").asString())) {
            ok(200, "POST", "/sales/change-requests/" + cr.body().get("id").asString() + "/approve", Map.of());
        }
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Stockable AVCO product with {@code qty} on hand at cost 10. */
    String stocked(String name, int qty) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 10, 100, now())").params(id, demoOrg, "I2P-" + sfx(), name + " " + sfx()).update();
        if (qty > 0) {
            TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(id, warehouse,
                    BigDecimal.valueOf(qty)).unitCost(BigDecimal.TEN)));
        }
        return id;
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(demoOrg, productId).query(BigDecimal.class).single();
    }

    String postedInvoice(String productId, int qty) {
        String id = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", productId, "quantity", qty, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        return id;
    }

    String creditNote(String invoiceId, String productId, int qty) {
        return ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today, "reversedDocumentId",
                invoiceId, "lines", List.of(Map.of("productId", productId, "quantity", qty, "unitPrice", 100))))
                .get("id").asString();
    }

    long journals(String sourceLike) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?")
                .params(demoOrg, sourceLike).query(Long.class).single();
    }

    String status(String documentId) {
        return jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(documentId).query(String.class)
                .single();
    }

    // ------------------------------------------------------------------ N1

    @Test
    @DisplayName("N1: an invoice a posted partial credit note corrected cannot be cancelled (no second restock/reversal)")
    void cancelAfterPartialCreditNoteIsRefused() {
        String p = stocked("Cement", 10);
        String inv = postedInvoice(p, 10);
        assertThat(onHand(p)).isEqualByComparingTo("0");
        String cn = creditNote(inv, p, 4);
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        assertThat(onHand(p)).as("4 restocked by the credit note").isEqualByComparingTo("4");
        long invoiceJournals = journals(inv);

        Res cancel = as("POST", "/invoices/" + inv + "/cancel", null);
        assertThat(cancel.status()).as(cancel.raw().body()).isEqualTo(400);
        assertThat(cancel.body().get("message").asString()).contains("has posted credit notes", "cannot be cancelled");
        assertThat(onHand(p)).as("no second restock").isEqualByComparingTo("4");
        assertThat(status(inv)).isEqualTo("posted");
        assertThat(journals(inv)).as("no reversal journal").isEqualTo(invoiceJournals);
    }

    @Test
    @DisplayName("N1: a draft credit note cannot post against an invoice cancelled meanwhile")
    void creditNoteOnACancelledInvoiceIsRefused() {
        String p = stocked("Lime", 5);
        String inv = postedInvoice(p, 2);
        String cn = creditNote(inv, p, 1);
        ok(200, "POST", "/invoices/" + inv + "/cancel", null);
        assertThat(onHand(p)).as("cancel returned the 2 issued").isEqualByComparingTo("5");
        Res post = as("POST", "/credit-notes/" + cn + "/post", null);
        assertThat(post.status()).as(post.raw().body()).isEqualTo(400);
        assertThat(post.body().get("message").asString()).startsWith("The credited invoice is cancelled");
        assertThat(onHand(p)).isEqualByComparingTo("5");
        assertThat(status(cn)).isEqualTo("draft");
    }

    @Test
    @DisplayName("N1 / A-1: two concurrent cancels return the issued stock and reverse the invoice once")
    void concurrentCancelsReverseOnce() {
        String p = stocked("Sand", 6);
        String inv = postedInvoice(p, 6);
        CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> as("POST", "/invoices/" + inv + "/cancel", null));
        CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> as("POST", "/invoices/" + inv + "/cancel", null));
        List<Integer> codes = List.of(a.join().status(), b.join().status());
        assertThat(codes).containsExactlyInAnyOrder(200, 400);
        assertThat(onHand(p)).as("returned once").isEqualByComparingTo("6");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'sales_invoice_cancel'"
                + " AND \"referenceId\" = ?").param(inv).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("A-1: two concurrent posts of one credit note restock and journal once")
    void concurrentCreditNotePostsRestockOnce() {
        String p = stocked("Gravel", 6);
        String inv = postedInvoice(p, 6);
        String cn = creditNote(inv, p, 2);
        CompletableFuture<Res> a = CompletableFuture.supplyAsync(() -> as("POST", "/credit-notes/" + cn + "/post", null));
        CompletableFuture<Res> b = CompletableFuture.supplyAsync(() -> as("POST", "/credit-notes/" + cn + "/post", null));
        List<Integer> codes = List.of(a.join().status(), b.join().status());
        assertThat(codes).as(a.join().raw().body() + " / " + b.join().raw().body()).containsExactlyInAnyOrder(200, 400);
        assertThat(onHand(p)).as("restocked once").isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'credit_note' AND"
                + " \"referenceId\" = ?").param(cn).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ N2

    @Test
    @DisplayName("N2: a price change on a part-delivered variant line splits it; the remainder keeps the variant and unit")
    void splitKeepsVariant() {
        Map<String, String> v = coloured("Paint");
        String[] so = confirmedOrder(map("productId", v.get("product"), "variantId", v.get("Red"), "quantity", 10,
                "unitPrice", 100));
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDelivered\" = 4 WHERE id = ?").param(so[1]).update();
        amend(so[0], map("lineId", so[1], "unitPrice", 90));
        Map<String, Object> rest = jdbc.sql("SELECT id, \"variantId\", quantity, \"uomId\", \"uomFactor\" FROM"
                + " \"SalesOrderLine\" WHERE \"orderId\" = ? AND id <> ?").params(so[0], so[1]).query().singleRow();
        assertThat(rest.get("variantId")).as("remainder keeps Red").isEqualTo(v.get("Red"));
        assertThat((BigDecimal) rest.get("quantity")).isEqualByComparingTo("6");
        assertThat(jdbc.sql("SELECT \"variantKey\" FROM \"StockReservation\" WHERE \"sourceId\" = ? AND status = 'active'")
                .param(rest.get("id")).query(String.class).list()).as("reserved on Red's cell").containsOnly(v.get("Red"));
    }

    @Test
    @DisplayName("N2: a product change resolves the new product's unit factor and moves the reservation")
    void productChangeResolvesNewUnit() {
        String pcs = unit("PCS");
        String box = unit("BOX");
        String big = packed("Tiles 24", pcs, box, 24);
        String small = packed("Tiles 12", pcs, box, 12);
        Object trigger = ok(200, "GET", "/sales/settings", null).get("reservationTrigger");
        ok(200, "PUT", "/sales/settings", Map.of("reservationTrigger", "confirm"));
        String[] so;
        try {
            so = confirmedOrder(map("productId", big, "quantity", 2, "uomId", box, "unitPrice", 3000));
        } finally {
            ok(200, "PUT", "/sales/settings", Map.of("reservationTrigger", trigger == null ? "none"
                    : ((JsonNode) trigger).asString()));
        }
        assertThat(jdbc.sql("SELECT quantity FROM \"StockReservation\" WHERE \"sourceId\" = ? AND status = 'active'")
                .param(so[1]).query(BigDecimal.class).single()).as("2 boxes of 24 reserved").isEqualByComparingTo("48");

        amend(so[0], map("lineId", so[1], "productId", small, "unitPrice", 2000));
        Map<String, Object> line = jdbc.sql("SELECT \"productId\", \"uomId\", \"uomFactor\" FROM \"SalesOrderLine\""
                + " WHERE id = ?").param(so[1]).query().singleRow();
        assertThat(line.get("productId")).isEqualTo(small);
        assertThat(line.get("uomId")).isEqualTo(box);
        assertThat((BigDecimal) line.get("uomFactor")).as("the new product's box holds 12").isEqualByComparingTo("12");
        List<Map<String, Object>> active = jdbc.sql("SELECT \"productId\", quantity FROM \"StockReservation\" WHERE"
                + " \"sourceId\" = ? AND status = 'active'").param(so[1]).query().listOfRows();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().get("productId")).as("old product no longer reserved").isEqualTo(small);
        assertThat((BigDecimal) active.getFirst().get("quantity")).isEqualByComparingTo("24");
    }

    // ------------------------------------------------------------------ U-5 / U-6 / M-2

    void repack(String productId, String uomId, int factor) {
        jdbc.sql("UPDATE \"ProductUom\" SET factor = ? WHERE \"productId\" = ? AND \"uomId\" = ?")
                .params(factor, productId, uomId).update();
    }

    JsonNode money(String method, String path, Object body) {
        Res r = call(method, path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    @Test
    @DisplayName("U-5: a quotation line's frozen unit factor survives into the sales order and its invoice")
    void copiedLinesKeepTheFrozenFactor() {
        String pcs = unit("PCS");
        String box = unit("BOX");
        String p = packed("Juice", pcs, box, 24);
        String qid = ok(201, "POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of(
                "productId", p, "quantity", 2, "uomId", box, "unitPrice", 2400)))).get("id").asString();
        ok(200, "POST", "/sales/quotations/" + qid + "/submit", null);
        repack(p, box, 12);
        String so = ok(200, "POST", "/sales/quotations/" + qid + "/convert", Map.of()).get("converted").get("id")
                .asString();
        assertThat(jdbc.sql("SELECT \"uomFactor\" FROM \"SalesOrderLine\" WHERE \"orderId\" = ?").param(so)
                .query(BigDecimal.class).single()).as("the quoted box of 24").isEqualByComparingTo("24");

        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        jdbc.sql("UPDATE \"SalesOrderLine\" SET \"qtyDispatched\" = 2, \"qtyDelivered\" = 2, \"qtyAccepted\" = 2,"
                + " \"qtyBillable\" = 2 WHERE \"orderId\" = ?").param(so).update();
        repack(p, box, 6);
        String inv = money("POST", "/sales/orders/" + so + "/invoices", Map.of()).get("id").asString();
        assertThat(jdbc.sql("SELECT \"uomFactor\" FROM \"DocumentLine\" WHERE \"documentId\" = ? AND"
                + " \"productId\" = ?").params(inv, p).query(BigDecimal.class).single()).as("the ordered box of 24")
                .isEqualByComparingTo("24");
    }

    @Test
    @DisplayName("U-6: a credit note restocks the invoice line's frozen unit, not today's pack size")
    void creditNoteRestocksTheInvoicedUnit() {
        String pcs = unit("PCS");
        String box = unit("BOX");
        String p = packed("Soda", pcs, box, 24);
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", p, "quantity", 2, "uomId", box, "unitPrice", 2400)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        assertThat(onHand(p)).as("2 boxes of 24 issued").isEqualByComparingTo("52");
        repack(p, box, 12);

        String named = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today,
                "reversedDocumentId", inv, "lines", List.of(Map.of("productId", p, "quantity", 1, "uomId", box,
                        "unitPrice", 2400)))).get("id").asString();
        ok(200, "POST", "/credit-notes/" + named + "/post", null);
        assertThat(onHand(p)).as("one box of 24 back").isEqualByComparingTo("76");

        String unnamed = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today,
                "reversedDocumentId", inv, "lines", List.of(Map.of("productId", p, "quantity", 1, "unitPrice", 2400))))
                .get("id").asString();
        ok(200, "POST", "/credit-notes/" + unnamed + "/post", null);
        assertThat(onHand(p)).as("a line without a unit takes the invoice's box of 24").isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("M-2: a credit note restocks where the invoice issued, at the cost it issued at")
    void creditNoteRestocksAtTheIssueLocationAndCost() {
        String p = stocked("Bricks", 10);
        String inv = postedInvoice(p, 4);
        TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(p, warehouse,
                BigDecimal.TEN).unitCost(BigDecimal.valueOf(40))));
        String shop = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'warehouse', now())").params(shop, demoOrg, "M2-" + sfx(), "M2 shop " + sfx()).update();
        ok(200, "PUT", "/settings/pos.stockLocationId", Map.of("value", shop));
        try {
            String cn = creditNote(inv, p, 2);
            ok(200, "POST", "/credit-notes/" + cn + "/post", null);
            Map<String, Object> back = jdbc.sql("SELECT \"locationId\", \"unitCost\" FROM \"InventoryLedger\" WHERE"
                    + " \"referenceType\" = 'credit_note' AND \"referenceId\" = ?").param(cn).query().singleRow();
            assertThat(back.get("locationId")).as("back where it left").isEqualTo(warehouse);
            assertThat((BigDecimal) back.get("unitCost")).as("at the issue cost, not today's average")
                    .isEqualByComparingTo("10");
        } finally {
            ok(200, "PUT", "/settings/pos.stockLocationId", Map.of("value", warehouse));
        }
    }

    Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
