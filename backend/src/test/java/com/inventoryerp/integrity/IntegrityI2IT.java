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

/**
 * Phase I2 (revised) regression tests for the 2026-11-15 review
 * ({@code docs/audits/2026-11-15-phase-I-regression-and-gap-review.md}): each test reproduces a reviewed defect.
 */
class IntegrityI2IT extends ApiTestSupport {

    @Autowired
    StockService stock;

    String warehouse;
    String customer;
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
    }

    // ------------------------------------------------------------------ helpers

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

    Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
