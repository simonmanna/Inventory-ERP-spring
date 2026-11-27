package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.application.PurchaseOrdersService;
import com.inventoryerp.procurement.application.PurchaseRequestsService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Purchase readiness fixes, receipts stream (owner-approved 2026-11-27): H9a (a receipt is reversed only while its goods
 * are still on hand), H9b (reversal unwinds quality control and incoming inspection), H10a (purchase request line
 * values), H10b (purchase order line tax against the Tax master), and receipt-line resolution against the order.
 */
class PurchaseReadinessReceiptsIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    StockReservationService reservations;

    @Autowired
    PurchaseRequestsService requests;

    @Autowired
    PurchaseOrdersService orders;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String adminId;
    String warehouse;
    String supplier;
    String vat18;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        ok(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PRR-" + next(), "name", "Receipts yard "
                + next(), "type", "warehouse")).get("id").asString();
        supplier = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(supplier, demoOrg, "PRS-" + next(), "Receipts supplier "
                        + next()).update();
        vat18 = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, type, rate, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'vat', 18, now())").params(vat18, demoOrg, "PR VAT 18 " + sfx, "PRV" + sfx).update();
    }

    // ------------------------------------------------------------------ helpers

    String next() {
        return sfx + "-" + (++seq);
    }

    <T> T inTx(Supplier<T> s) {
        return TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId),
                () -> new TransactionTemplate(txm).execute(st -> s.get()));
    }

    String product(String policy) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"stockPolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable', true, 'AVCO', 10, 15, ?::\"PosStockPolicy\", now())")
                .params(id, demoOrg, "PRP-" + next(), "Receipt good " + next(), policy).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res money(String method, String path, Object body) {
        return call(method, path, body, key());
    }

    String message(Res r) {
        return r.body() == null || r.body().get("message") == null ? r.raw().body() : r.body().get("message").asString();
    }

    BigDecimal onHand(String productId, String loc) {
        return inTx(() -> jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(productId, loc).query(BigDecimal.class).single());
    }

    String adhocReceipt(String productId, int qty) {
        Res r = money("POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Receipt good", "quantity", qty,
                        "unitCost", 10))));
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        assertThat(r.body().get("status").asString()).isEqualTo("posted");
        return r.body().get("id").asString();
    }

    Res reverse(String grn) {
        return money("PATCH", "/procurement/goods-receipts/" + grn + "/reverse", Map.of("reason", "readiness test"));
    }

    void issue(String productId, int qty, boolean strict) {
        inTx(() -> stock.issue(StockMove.of(productId, warehouse, BigDecimal.valueOf(qty)).source("test_issue", UUID.randomUUID().toString())
                .requireAvailable(strict)));
    }

    String purchaseOrder(String productId, int qty, Map<String, Object> extra) {
        Map<String, Object> line = new LinkedHashMap<>(Map.of("productId", productId, "description", "Receipt good",
                "quantity", qty, "unitPrice", 10));
        line.putAll(extra);
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(line))).get("id").asString();
    }

    Res receivePo(String poId, String productId, int qty) {
        String poLine = jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?").param(poId)
                .query(String.class).single();
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("purchaseOrderLineId", poLine, "productId", productId, "description",
                        "Receipt good", "quantity", qty))));
    }

    // ------------------------------------------------------------------ H9a

    @Test
    @DisplayName("H9a: a receipt whose goods were sold is not reversed; one whose goods are all there is")
    void reversalNeedsGoodsOnHand() {
        String good = product("warn");
        String grn = adhocReceipt(good, 100);
        issue(good, 80, false);
        Res refused = reverse(grn);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        String receiptNumber = jdbc.sql("SELECT \"receiptNumber\" FROM \"GoodsReceiptNote\" WHERE id = ?").param(grn)
                .query(String.class).optional().orElse(null);
        assertThat(message(refused)).startsWith("Receipt ").contains(" cannot be reversed: only 20 of 100 units of ")
                .endsWith("Return the remaining goods to the supplier with a debit note instead.");
        assertThat(onHand(good, warehouse)).as("nothing moved").isEqualByComparingTo("20");
        JsonNode still = ok(200, "GET", "/procurement/goods-receipts/" + grn, null);
        assertThat(still.get("status").asString()).isEqualTo("posted");
        if (receiptNumber != null) {
            assertThat(message(refused)).startsWith("Receipt " + receiptNumber + " cannot be reversed");
        }

        String good2 = product("warn");
        String grn2 = adhocReceipt(good2, 100);
        Res reversed = reverse(grn2);
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        assertThat(reversed.body().get("status").asString()).isEqualTo("reversed");
        assertThat(onHand(good2, warehouse)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("H9a: reserved stock is not taken back by a reversal")
    void reversalRespectsReservations() {
        String good = product("warn");
        String grn = adhocReceipt(good, 50);
        inTx(() -> reservations.reserve(new StockReservationService.Reserve(good, null, warehouse,
                new BigDecimal("30"), "test_reservation", next(), "readiness test")));
        Res refused = reverse(grn);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(message(refused)).contains("only 20 of 50 units of ").contains("(30 of what is there is reserved)");
        assertThat(onHand(good, warehouse)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("H9a: an issue racing a reversal never drives the stock negative")
    void concurrentIssueAndReversal() throws Exception {
        for (int round = 0; round < 3; round++) {
            String good = product("block");
            String grn = adhocReceipt(good, 100);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> issuing = () -> {
                    start.await();
                    try {
                        issue(good, 80, true);
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                };
                Callable<Boolean> reversing = () -> {
                    start.await();
                    return reverse(grn).status() == 200;
                };
                Future<Boolean> a = pool.submit(issuing);
                Future<Boolean> b = pool.submit(reversing);
                boolean issued = a.get();
                boolean reversed = b.get();
                assertThat(issued && reversed).as("both cannot win").isFalse();
                assertThat(issued || reversed).as("one wins").isTrue();
                BigDecimal left = onHand(good, warehouse);
                assertThat(left.signum()).as("never negative: " + left).isGreaterThanOrEqualTo(0);
                assertThat(left).isEqualByComparingTo(issued ? "20" : "0");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ H9b

    String quarantineLocation() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'QC-QUARANTINE'")
                .param(demoOrg).query(String.class).single();
    }

    JsonNode receiptChecks(String grn) {
        return ok(200, "GET", "/quality-control/checks?sourceType=goods_receipt&sourceId=" + grn + "&pageSize=100", null)
                .get("data");
    }

    @Test
    @DisplayName("H9b: reversing a receipt in quarantine cancels its check and takes the goods out of quarantine")
    void reversalUnwindsQuarantine() {
        String sand = product("warn");
        ok(201, "POST", "/quality-control/control-points", Map.of("title", "PR quarantine " + next(), "operation",
                "receipt", "checkType", "pass_fail", "productIds", List.of(sand), "controlPer", "product",
                "receiptMode", "quarantine_on_receipt"));
        String po = purchaseOrder(sand, 30, Map.of());
        Res received = receivePo(po, sand, 10);
        assertThat(received.status()).as(received.raw().body()).isEqualTo(200);
        String grn = received.body().get("grn").get("id").asString();
        String quarantine = quarantineLocation();
        assertThat(onHand(sand, quarantine)).isEqualByComparingTo("10");
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("0");

        Res reversed = reverse(grn);
        assertThat(reversed.status()).as(reversed.raw().body()).isEqualTo(200);
        assertThat(onHand(sand, quarantine)).as("quarantined goods went back with the receipt").isEqualByComparingTo("0");
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("0");
        JsonNode check = receiptChecks(grn).get(0);
        assertThat(check.get("status").asString()).isEqualTo("cancelled");
        assertThat(new BigDecimal(check.get("quarantinedQty").asString())).isEqualByComparingTo("0");
        assertThat(as("POST", "/quality-control/checks/" + check.get("id").asString() + "/execute",
                Map.of("result", "passed")).status()).as("a cancelled check cannot be decided").isEqualTo(409);

        // A decided check that kept goods in quarantine: the reversal is refused, nothing moves.
        Res second = receivePo(po, sand, 10);
        String grn2 = second.body().get("grn").get("id").asString();
        String check2 = receiptChecks(grn2).get(0).get("id").asString();
        ok(200, "POST", "/quality-control/checks/" + check2 + "/execute", Map.of("result", "failed", "failedQty", 4));
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("6");
        Res refused = reverse(grn2);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(message(refused)).contains(" cannot be reversed: quality check ")
                .contains("holds 4 in quarantine after its decision");
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("6");
        assertThat(onHand(sand, quarantine)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("H9b: reversal cancels a pending inspection; a reversed receipt's inspection cannot be completed")
    void reversalCancelsInspection() {
        String cement = product("warn");
        String grn = adhocReceipt(cement, 10);
        String insp = ok(200, "POST", "/procurement/goods-receipts/" + grn + "/inspection", null).get("id").asString();
        Res rv = reverse(grn);
        assertThat(rv.status()).as(rv.raw().body()).isEqualTo(200);
        JsonNode cancelled = ok(200, "GET", "/procurement/inspections/" + insp, null);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("cancelReason").asString()).startsWith("Goods receipt reversed: ");
        Res late = money("POST", "/procurement/inspections/" + insp + "/complete", null);
        assertThat(late.status()).isEqualTo(400);

        // A reversed receipt whose inspection is still pending (reversed before this fix): completing is refused.
        inTx(() -> jdbc.sql("UPDATE \"IncomingInspection\" SET status = 'pending' WHERE id = ?").param(insp).update());
        Res refused = money("POST", "/procurement/inspections/" + insp + "/complete", null);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(message(refused)).endsWith(" was reversed: its inspection cannot be recorded or completed; cancel the"
                + " inspection instead");
        assertThat(inTx(() -> jdbc.sql("SELECT count(*) FROM \"SupplierClaim\" WHERE \"goodsReceiptId\" = ?").param(grn)
                .query(Long.class).single())).isZero();
    }

    // ------------------------------------------------------------------ H10a

    @Test
    @DisplayName("H10a: purchase request lines need a positive quantity and a non-negative price, on every path")
    void requestLineValues() {
        for (Object[] bad : new Object[][] {{-1, 100}, {0, 100}, {5, -10}}) {
            Res r = as("POST", "/procurement/purchase-requests", Map.of("description", "bad " + next(), "lines",
                    List.of(Map.of("description", "Cement", "quantity", bad[0], "estimatedUnitPrice", bad[1]))));
            assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        }
        Res ok = as("POST", "/procurement/purchase-requests", Map.of("description", "free sample " + next(), "lines",
                List.of(Map.of("description", "Cement", "quantity", 2, "estimatedUnitPrice", 0))));
        assertThat(ok.status()).as("a zero price is a valid request").isEqualTo(201);
        // A service path (replenishment, imports) bypasses the DTO: the service refuses too.
        try {
            inTx(() -> requests.create(new PurchaseRequestsService.CreateInput(null, null, "service " + next(), null,
                    List.of(new PurchaseRequestsService.LineInput(null, "Cement", new BigDecimal("-3"), null,
                            new BigDecimal("100"), null)), null)));
            throw new AssertionError("negative request line accepted");
        } catch (ApiException e) {
            assertThat(e.getMessage()).isEqualTo("Line \"Cement\": quantity must be greater than 0");
        }
    }

    // ------------------------------------------------------------------ H10b

    @Test
    @DisplayName("H10b: PO line tax is 0..100 and follows the Tax master")
    void orderLineTax() {
        String good = product("warn");
        Map<String, Object> base = Map.of("partnerId", supplier, "warehouseId", warehouse, "paymentType", "credit",
                "currencyCode", "UGX");
        for (Object rate : List.of(-5, 150)) {
            Map<String, Object> body = new LinkedHashMap<>(base);
            body.put("lines", List.of(Map.of("productId", good, "description", "Taxed", "quantity", 1, "unitPrice", 100,
                    "taxRate", rate)));
            assertThat(as("POST", "/procurement/purchase-orders", body).status()).isEqualTo(400);
        }
        Map<String, Object> mismatch = new LinkedHashMap<>(base);
        mismatch.put("lines", List.of(Map.of("productId", good, "description", "Taxed", "quantity", 1, "unitPrice", 100,
                "taxId", vat18, "taxRate", 5)));
        Res refused = as("POST", "/procurement/purchase-orders", mismatch);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(message(refused)).isEqualTo("Line \"Taxed\": tax rate 5% does not match tax PR VAT 18 " + sfx
                + " (18%); leave the rate empty to use the tax's rate");

        String po = purchaseOrder(good, 10, Map.of("taxId", vat18));
        assertThat(inTx(() -> jdbc.sql("SELECT \"taxRate\" FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?")
                .param(po).query(BigDecimal.class).single())).as("the master rate").isEqualByComparingTo("18");
        JsonNode order = ok(200, "GET", "/procurement/purchase-orders/" + po, null);
        assertThat(new BigDecimal(order.get("taxAmount").asString())).isEqualByComparingTo("18");

        // A service path (agreement release, RFQ award) is checked too.
        try {
            inTx(() -> orders.create(new PurchaseOrdersService.CreateInput(supplier, null, null, null, "UGX", null,
                    "credit", null, null, warehouse, null, List.of(new PurchaseOrdersService.LineInput(good, "Taxed",
                            BigDecimal.ONE, null, new BigDecimal("100"), null, new BigDecimal("-18"), null)), null)));
            throw new AssertionError("negative tax accepted");
        } catch (ApiException e) {
            assertThat(e.getMessage()).isEqualTo("Line \"Taxed\": taxRate must be between 0 and 100");
        }
        try {
            inTx(() -> orders.create(new PurchaseOrdersService.CreateInput(supplier, null, null, null, "UGX", null,
                    "credit", null, null, warehouse, null, List.of(new PurchaseOrdersService.LineInput(good, "Taxed",
                            BigDecimal.ZERO, null, new BigDecimal("100"), null, null, null)), null)));
            throw new AssertionError("zero quantity accepted");
        } catch (ApiException e) {
            assertThat(e.getMessage()).isEqualTo("Line \"Taxed\": quantity must be greater than 0");
        }
    }

    // ------------------------------------------------------------------ receipt-line resolution

    @Test
    @DisplayName("a product-only line of a PO receipt names its order line and carries the order's input VAT")
    void productOnlyLineResolvesOrderLine() {
        String good = product("warn");
        String po = purchaseOrder(good, 10, Map.of("taxId", vat18));
        String poLine = jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?").param(po)
                .query(String.class).single();
        String grn = ok(201, "POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", po, "warehouseId",
                warehouse, "lines", List.of(Map.of("productId", good, "description", "Receipt good", "quantity", 10))))
                .get("id").asString();
        JsonNode draft = ok(200, "GET", "/procurement/goods-receipts/" + grn, null);
        assertThat(draft.get("lines").get(0).get("purchaseOrderLineId").asString()).isEqualTo(poLine);
        Res posted = money("PATCH", "/procurement/goods-receipts/" + grn + "/post", null);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        // Stock entry (Dr stock / Cr GRNI 100) + voucher (Dr GRNI 100 + Dr input VAT 18 / Cr AP 118).
        BigDecimal credits = inTx(() -> jdbc.sql("SELECT COALESCE(SUM(l.credit), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"sourceType\" = 'goods_receipt' AND"
                + " e.\"sourceId\" = ? AND e.status = 'posted'").param(grn).query(BigDecimal.class).single());
        assertThat(credits).isEqualByComparingTo("218");
    }

    @Test
    @DisplayName("a receipt line naming a different variant than the order line is refused")
    void receiptVariantMustMatchOrder() {
        String good = product("warn");
        jdbc.sql("UPDATE \"Product\" SET \"hasVariants\" = true WHERE id = ?").param(good).update();
        List<String> variants = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String v = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, \"combinationKey\","
                    + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, now())").params(v, demoOrg, good, "PRV " + next(), v)
                    .update();
            variants.add(v);
        }
        String po = purchaseOrder(good, 10, Map.of("variantId", variants.get(0)));
        Res refused = as("POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", po, "warehouseId", warehouse,
                "lines", List.of(Map.of("productId", good, "description", "Receipt good", "quantity", 5, "variantId",
                        variants.get(1)))));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(message(refused)).isEqualTo("Line \"Receipt good\" names a different variant than the purchase order"
                + " line it receives; receive the ordered variant or add the other variant to the order first");
        Res same = as("POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", po, "warehouseId", warehouse,
                "lines", List.of(Map.of("productId", good, "description", "Receipt good", "quantity", 5, "variantId",
                        variants.get(0)))));
        assertThat(same.status()).as(same.raw().body()).isEqualTo(201);
    }
}
