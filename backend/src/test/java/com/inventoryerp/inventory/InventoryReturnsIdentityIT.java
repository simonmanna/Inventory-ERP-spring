package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.IssueSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnFromSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnLine;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.rental.application.RentalLocationConfigService;
import com.inventoryerp.rental.application.RentalPostingService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inventory hardening R1 (gate G1): a return restores the identity of what was issued — the same lot row (no new lot),
 * expiry, serial, location and cost of the original movement, named by {@code reversesLedgerId} — never more than was
 * issued; a credit note without an issue to return against is refused; and a fee / charge line never moves stock.
 */
class InventoryReturnsIdentityIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    StockReversalService reversals;

    @Autowired
    RentalPostingService rentalPosting;

    @Autowired
    RentalLocationConfigService rentalLocations;

    @Autowired
    PosInvoiceService posInvoices;

    String warehouse;
    String customer;
    String adminId;
    final String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        warehouse = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1").param(demoOrg)
                .query(String.class).single();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "R1C-" + sfx(), "R1 customer " + sfx())
                .update();
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    <T> T inTx(Supplier<T> body) {
        return TenantContext.run(TenantStore.user(demoOrg, adminId),
                () -> new TransactionTemplate(txm).execute(s -> body.get()));
    }

    String product(String name, boolean lots, boolean serials) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"batchTracking\", \"expiryTracking\","
                + " \"serialTracking\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 10, 100, ?, ?, ?,"
                + " now())").params(id, demoOrg, "R1P-" + sfx(), name + " " + sfx(), lots, lots, serials).update();
        return id;
    }

    /** Lot product with lot A (3 @ 10, expiring first) and lot B (5 @ 20) in the warehouse. Returns product, A, B. */
    String[] lotProduct() {
        String p = product("Vaccine", true, false);
        String a = "A-" + sfx();
        String b = "B-" + sfx();
        inTx(() -> stock.receive(StockMove.of(p, warehouse, BigDecimal.valueOf(3)).unitCost(BigDecimal.TEN).batch(a)
                .expiry("2031-01-31T00:00:00.000Z")));
        inTx(() -> stock.receive(StockMove.of(p, warehouse, BigDecimal.valueOf(5)).unitCost(BigDecimal.valueOf(20))
                .batch(b).expiry("2031-06-30T00:00:00.000Z")));
        return new String[] {p, a, b};
    }

    BigDecimal lotQty(String productId, String batchNumber) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"batchNumber\" = ?").params(demoOrg, productId, batchNumber)
                .query(BigDecimal.class).single();
    }

    long lots(String productId) {
        return jdbc.sql("SELECT count(*) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                .params(demoOrg, productId).query(Long.class).single();
    }

    String postedInvoice(String productId, int qty) {
        String id = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", productId, "quantity", qty, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        return id;
    }

    String creditNote(String invoiceId, String productId, int qty) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("partnerId", customer);
        body.put("issueDate", today);
        if (invoiceId != null) {
            body.put("reversedDocumentId", invoiceId);
        }
        body.put("lines", List.of(Map.of("productId", productId, "quantity", qty, "unitPrice", 100)));
        return ok(200, "POST", "/credit-notes", body).get("id").asString();
    }

    List<Map<String, Object>> rows(String referenceType, String referenceId) {
        return jdbc.sql("SELECT r.\"locationId\", r.\"unitCost\", r.\"quantityChange\", r.\"batchId\", r.\"serialId\","
                + " r.\"reversesLedgerId\", i.\"batchId\" AS \"issueBatch\", i.\"unitCost\" AS \"issueCost\","
                + " i.\"locationId\" AS \"issueLocation\", i.\"serialId\" AS \"issueSerial\" FROM \"InventoryLedger\" r"
                + " LEFT JOIN \"InventoryLedger\" i ON i.id = r.\"reversesLedgerId\" WHERE r.\"organizationId\" = ? AND"
                + " r.\"referenceType\" = ? AND r.\"referenceId\" = ? ORDER BY r.\"createdAt\", r.id")
                .params(demoOrg, referenceType, referenceId).query().listOfRows();
    }

    static void assertSameIdentity(List<Map<String, Object>> back) {
        assertThat(back).isNotEmpty().allSatisfy(r -> {
            assertThat(r.get("reversesLedgerId")).as("names the original row").isNotNull();
            assertThat(r.get("batchId")).as("the very lot row it left").isEqualTo(r.get("issueBatch"));
            assertThat(r.get("locationId")).isEqualTo(r.get("issueLocation"));
            assertThat((BigDecimal) r.get("unitCost")).isEqualByComparingTo((BigDecimal) r.get("issueCost"));
        });
    }

    // ------------------------------------------------------------------ P1-1 returns keep identity

    @Test
    @DisplayName("invoice cancel restores the same lots (no new lot) at their cost and location, linked to the issue")
    void invoiceCancelRestoresTheSameLots() {
        String[] p = lotProduct();
        String inv = postedInvoice(p[0], 5);
        assertThat(lotQty(p[0], p[1])).as("FEFO took lot A first").isEqualByComparingTo("0");
        assertThat(lotQty(p[0], p[2])).isEqualByComparingTo("3");
        ok(200, "POST", "/invoices/" + inv + "/cancel", Map.of("reason", "Raised in error"));
        assertThat(lots(p[0])).as("no new lot row").isEqualTo(2);
        assertThat(lotQty(p[0], p[1])).isEqualByComparingTo("3");
        assertThat(lotQty(p[0], p[2])).isEqualByComparingTo("5");
        List<Map<String, Object>> back = rows("sales_invoice_cancel", inv);
        assertThat(back).hasSize(2);
        assertSameIdentity(back);
        assertThat(back.stream().map(r -> ((BigDecimal) r.get("unitCost")).stripTrailingZeros().toPlainString()))
                .containsExactlyInAnyOrder("10", "20");
    }

    @Test
    @DisplayName("a partial credit note returns across two lots in issue order; returning more than issued is refused")
    void partialReturnAcrossLotsAndOverReturnRefused() {
        String[] p = lotProduct();
        String inv = postedInvoice(p[0], 5);
        String cn = creditNote(inv, p[0], 4);
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        assertThat(lots(p[0])).isEqualTo(2);
        assertThat(lotQty(p[0], p[1])).as("all 3 of lot A back").isEqualByComparingTo("3");
        assertThat(lotQty(p[0], p[2])).as("1 of the 2 taken from lot B back").isEqualByComparingTo("4");
        assertSameIdentity(rows("credit_note", cn));

        // 1 unit of the issue is still out: asking for 2 is refused, 1 is accepted
        assertThatThrownBy(() -> inTx(() -> reversals.returnFromSource(new ReturnFromSource(
                IssueSource.of("sales_invoice", inv), List.of(ReturnLine.of(p[0], null, BigDecimal.TWO)), "r1_test",
                inv, null, null, null, null, true, null))))
                .isInstanceOf(ApiException.class).hasMessage("Cannot return more than was issued");
        List<StockReversalService.ReturnedRow> last = inTx(() -> reversals.returnFromSource(new ReturnFromSource(
                IssueSource.of("sales_invoice", inv), List.of(ReturnLine.of(p[0], null, BigDecimal.ONE)), "r1_test",
                inv, null, null, null, null, true, null)));
        assertThat(last).singleElement().satisfies(r -> {
            assertThat(r.batchNumber()).isEqualTo(p[2]);
            assertThat(r.unitCost()).isEqualByComparingTo("20");
        });
        assertThat(lotQty(p[0], p[2])).isEqualByComparingTo("5");
        assertThat(lots(p[0])).isEqualTo(2);
    }

    @Test
    @DisplayName("a credit-note reversal takes back the restocked lot at the cost it came back at; the issue is"
            + " returnable again")
    void creditNoteReversalTakesBackTheRestockedLot() {
        String[] p = lotProduct();
        String inv = postedInvoice(p[0], 4);
        String cn = creditNote(inv, p[0], 4);
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        Res reversed = as("POST", "/credit-notes/" + cn + "/reverse", Map.of("reason", "Credited in error"));
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        assertThat(lotQty(p[0], p[1])).isEqualByComparingTo("0");
        assertThat(lotQty(p[0], p[2])).isEqualByComparingTo("4");
        List<Map<String, Object>> out = rows("credit_note_cancel", cn);
        assertThat(out).isNotEmpty().allSatisfy(r -> {
            assertThat(r.get("reversesLedgerId")).isNotNull();
            assertThat(r.get("batchId")).isEqualTo(r.get("issueBatch"));
            assertThat((BigDecimal) r.get("unitCost")).isEqualByComparingTo((BigDecimal) r.get("issueCost"));
        });
        // the whole issue is out again, so the invoice cancel can bring all 4 back
        List<StockReversalService.ReturnedRow> again = inTx(() -> reversals.returnFromSource(new ReturnFromSource(
                IssueSource.of("sales_invoice", inv), null, "r1_test", inv, null, null, null, null, true, null)));
        assertThat(again.stream().map(StockReversalService.ReturnedRow::quantity).reduce(BigDecimal.ZERO,
                BigDecimal::add)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a serial unit sold on an invoice comes back as the same serial, in stock, named on the return row")
    void serialComesBackAsTheSameUnit() {
        String p = product("Drill", false, true);
        String s1 = "SN-" + sfx();
        String s2 = "SN-" + sfx();
        inTx(() -> stock.receive(StockMove.of(p, warehouse, BigDecimal.ONE).unitCost(BigDecimal.valueOf(300))
                .serials(List.of(s1))));
        inTx(() -> stock.receive(StockMove.of(p, warehouse, BigDecimal.ONE).unitCost(BigDecimal.valueOf(350))
                .serials(List.of(s2))));
        String inv = postedInvoice(p, 1);
        Map<String, Object> issued = jdbc.sql("SELECT \"serialNumber\", status::text AS status FROM \"InventorySerial\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND status::text = 'issued'").params(demoOrg, p)
                .query().singleRow();
        String cn = creditNote(inv, p, 1);
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        assertThat(jdbc.sql("SELECT status::text FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\""
                + " = ? AND \"serialNumber\" = ?").params(demoOrg, p, issued.get("serialNumber")).query(String.class)
                .single()).isEqualTo("in_stock");
        List<Map<String, Object>> back = rows("credit_note", cn);
        assertThat(back).singleElement().satisfies(r -> {
            assertThat(r.get("serialId")).as("the serial is named on the row").isEqualTo(r.get("issueSerial"));
            assertThat((BigDecimal) r.get("unitCost")).isEqualByComparingTo((BigDecimal) r.get("issueCost"));
        });
    }

    @Test
    @DisplayName("a move returned against its ledger code (production consume) restores the lot without GL")
    void ledgerCodeReturnRestoresTheLot() {
        String[] p = lotProduct();
        String code = inTx(() -> stock.issue(StockMove.of(p[0], warehouse, BigDecimal.valueOf(4))
                .source("production_order", "MO-R1-" + sfx()).skipGl())).ledgerCode();
        long journals = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        List<StockReversalService.ReturnedRow> back = inTx(() -> reversals.returnFromSource(new ReturnFromSource(
                IssueSource.ledgerCodes(List.of(code)), null, "production_order_cancel", "MO-R1", null, null, null,
                null, false, "receipt")));
        assertThat(back).hasSize(2);
        assertThat(lots(p[0])).isEqualTo(2);
        assertThat(lotQty(p[0], p[1])).isEqualByComparingTo("3");
        assertThat(lotQty(p[0], p[2])).isEqualByComparingTo("5");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single()).as("the caller owns the GL").isEqualTo(journals);
        // a second full return finds nothing left to bring back
        assertThat(inTx(() -> reversals.returnFromSource(new ReturnFromSource(IssueSource.ledgerCodes(List.of(code)),
                null, "production_order_cancel", "MO-R1", null, null, null, null, false, "receipt")))).isEmpty();
    }

    // ------------------------------------------------------------------ P1-2 credit note without an issue

    @Test
    @DisplayName("a restocking credit note that credits no invoice is refused instead of guessing location and cost")
    void creditNoteWithoutIssueRowsIsRefused() {
        String p = product("Loose", false, false);
        String cn = creditNote(null, p, 1);
        Res post = as("POST", "/credit-notes/" + cn + "/post", null);
        assertThat(post.status()).as(post.raw().body()).isEqualTo(400);
        assertThat(post.body().get("message").asString()).contains("Cannot restock", "credits no invoice",
                "disposition scrap");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ?").param(p)
                .query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ P0-1 fee lines never move stock

    @Test
    @DisplayName("permanent rule: a fee line naming a stock-tracked product never moves stock")
    void feeLineNeverMovesStock() {
        String p = product("Rented generator", false, false);
        inTx(() -> stock.receive(StockMove.of(p, warehouse, BigDecimal.valueOf(5)).unitCost(BigDecimal.TEN)));
        ok(200, "PUT", "/settings/pos.stockLocationId", Map.of("value", warehouse));
        Map<String, Object> fee = inTx(() -> rentalPosting.createFeeInvoice("agr-" + sfx(), customer, null, "late",
                List.of(new RentalPostingService.FeeLine(p, "Late fee", 1, 50, "late")), "cash"));
        String invoiceId = (String) fee.get("invoiceId");
        for (String job : jdbc.sql("SELECT id FROM \"StockPostingJob\" WHERE \"organizationId\" = ? AND (\"invoiceId\" = ?"
                + " OR \"orderId\" = ?)").params(demoOrg, invoiceId, fee.get("orderId")).query(String.class).list()) {
            TenantContext.run(TenantStore.user(demoOrg, adminId), () -> posInvoices.processStockPostingJob(job));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"quantityChange\" < 0").params(demoOrg, p).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(p)
                .query(BigDecimal.class).single()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("rental setup creates the four fee products as non-stock services; a missing one is refused")
    void feeProductsAreServicesAndMissingIsRefused() {
        for (String org : List.of(otherOrg, demoOrg)) {
            TenantContext.run(TenantStore.organization(org), () -> new TransactionTemplate(txm)
                    .executeWithoutResult(s -> rentalLocations.ensureForOrg(org)));
        }
        TenantContext.run(TenantStore.organization(otherOrg), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> rentalLocations.ensureForOrg(otherOrg))); // idempotent
        List<Map<String, Object>> fees = jdbc.sql("SELECT code, \"productType\"::text AS t, \"trackInventory\" FROM"
                + " \"Product\" WHERE \"organizationId\" = ? AND code IN ('RENT-FEE', 'RENT-EXTEND', 'RENT-LATE',"
                + " 'RENT-DAMAGE')").param(otherOrg).query().listOfRows();
        assertThat(fees).hasSize(4).allSatisfy(f -> {
            assertThat(f.get("t")).isEqualTo("service");
            assertThat(f.get("trackInventory")).isEqualTo(false);
        });
        String id = jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND code = 'RENT-DAMAGE'")
                .param(demoOrg).query(String.class).single();
        boolean active = jdbc.sql("SELECT \"isActive\" FROM \"Product\" WHERE id = ?").param(id).query(Boolean.class)
                .single();
        jdbc.sql("UPDATE \"Product\" SET \"isActive\" = false WHERE id = ?").param(id).update();
        try {
            assertThatThrownBy(() -> TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> rentalPosting.requireFeeProduct("RENT-DAMAGE")))
                    .isInstanceOf(ApiException.class).hasMessage("Rental fee product RENT-DAMAGE is not configured");
        } finally {
            jdbc.sql("UPDATE \"Product\" SET \"isActive\" = ? WHERE id = ?").params(active, id).update();
        }
    }
}
