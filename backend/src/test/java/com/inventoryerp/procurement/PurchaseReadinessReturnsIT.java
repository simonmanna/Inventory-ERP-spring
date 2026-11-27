package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.TestApprovers;
import com.inventoryerp.procurement.application.LandedCostService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Purchase readiness fixes, stream "returns" (owner-approved 2026-11-27): the legacy per-receipt landed cost is retired
 * (B4), a return to vendor against a bill is limited by what the bill billed and what was already returned or claimed
 * (H3, MEDIUM #5), and a supplier claim and a return cannot credit the same received quantity or value twice (H4).
 *
 * <p>The AVCO landed-cost scenario through the voucher engine (receive 100, sell 60, receive 100, 1000 freight on the
 * first receipt → 400 stock / 600 COGS) is {@code LandedCostGapsIT.receiptScreenVoucher}, the payload the goods-receipt
 * screen now opens.
 */
class PurchaseReadinessReturnsIT extends ApiTestSupport {

    String warehouse;
    String checker;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PRR-" + suffix(), "name", "Returns "
                + suffix(), "type", "warehouse")).get("id").asString();
        checker = TestApprovers.secondAdmin(this);
    }

    // ------------------------------------------------------------------ helpers

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "PRR-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String stockProduct() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable'::\"ProductType\", true, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "PRR-" + suffix(), "Item " + suffix()).update();
        return id;
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(productId)
                .query(BigDecimal.class).single();
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res keyed(String method, String path, Object body) {
        return call(method, path, body, key(adminToken));
    }

    JsonNode money(String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    /** Order {@code qty} × 100 of the product and receive it all; returns the receipt id. */
    String orderAndReceive(String supplier, String productId, int qty) {
        String poId = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "lines", List.of(Map.of("productId", productId, "description",
                        "Cement", "quantity", qty, "unitPrice", 100)))).get("id").asString();
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))))
                .get("grn").get("id").asString();
    }

    String adhoc(String supplier, String productId, int qty) {
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId", productId,
                        "description", "Cement", "quantity", qty, "unitCost", 100)))).get("id").asString();
    }

    String postedBill(String supplier, String productId, int qty) {
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice", 100))))
                .get("id").asString();
        ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of("override3WM", true, "overrideApproval", true));
        return bill;
    }

    Res createReturn(String supplier, String billId, String productId, int qty) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("direction", "inbound", "partnerId", supplier, "reason",
                "returned_goods", "locationId", warehouse, "lines", List.of(Map.of("productId", productId,
                        "description", "Cement", "quantity", qty, "unitPrice", 100))));
        if (billId != null) {
            body.put("documentId", billId);
        }
        return as("POST", "/procurement/debit-notes", body);
    }

    String draftReturn(String supplier, String billId, String productId, int qty) {
        Res r = createReturn(supplier, billId, productId, qty);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("id").asString();
    }

    Res postReturn(String noteId) {
        return keyed("PATCH", "/procurement/debit-notes/" + noteId + "/post", null);
    }

    String claim(String supplier, String type, int amount, Map<String, String> links) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "type", type, "amount", amount,
                "description", "PRR " + type));
        body.putAll(links);
        String id = ok(201, "POST", "/procurement/claims", body).get("id").asString();
        ok(200, "POST", "/procurement/claims/" + id + "/submit", null);
        return id;
    }

    String receiptLineOf(String grnId) {
        return jdbc.sql("SELECT id FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ? ORDER BY \"lineNumber\""
                + " LIMIT 1").param(grnId).query(String.class).single();
    }

    Res addLine(String claimId, String receiptLineId, int qty) {
        return as("POST", "/procurement/claims/" + claimId + "/quantity-lines", Map.of("goodsReceiptLineId",
                receiptLineId, "quantity", qty));
    }

    void verifyLast(String claimId, JsonNode claimBody) {
        JsonNode lines = claimBody.get("quantityLines");
        String lineId = lines.get(lines.size() - 1).get("id").asString();
        Res v = call("POST", "/procurement/claims/" + claimId + "/quantity-lines/" + lineId + "/verify", null, checker);
        assertThat(v.status()).as(v.raw().body()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ B4 legacy landed cost retired

    @Test
    @DisplayName("B4: creating or posting a per-receipt landed cost is refused and points to vouchers; an old draft"
            + " can still be cancelled; the history stays readable")
    void legacyLandedCostRetired() {
        String supplier = partner("B4");
        String product = stockProduct();
        String grn = adhoc(supplier, product, 10);
        String ap = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'accounts_payable' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();

        Res create = keyed("POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId", ap,
                "charges", List.of(Map.of("kind", "freight", "amount", 100))));
        assertThat(create.status()).as(create.raw().body()).isEqualTo(400);
        assertThat(create.body().get("message").asString()).isEqualTo(LandedCostService.CREATE_RETIRED)
                .contains("/procurement/landed-cost-vouchers");

        // A draft saved before the retirement: posting it is refused, cancelling it still works.
        String draft = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LandedCost\" (id, \"organizationId\", code, \"goodsReceiptId\", \"allocationMethod\","
                + " \"creditAccountId\", date, \"totalAmount\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, 'value', ?,"
                + " now(), 100, now(), now())").params(draft, demoOrg, "LC-PRR-" + suffix(), grn, ap).update();
        Res post = keyed("POST", "/procurement/landed-costs/" + draft + "/post", null);
        assertThat(post.status()).as(post.raw().body()).isEqualTo(400);
        assertThat(post.body().get("message").asString()).isEqualTo(LandedCostService.POST_RETIRED);
        assertThat(jdbc.sql("SELECT status FROM \"LandedCost\" WHERE id = ?").param(draft).query(String.class).single())
                .isEqualTo("draft");
        assertThat(onHand(product)).isEqualByComparingTo("10");
        assertThat(money("POST", "/procurement/landed-costs/" + draft + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
        assertThat(ok(200, "GET", "/procurement/landed-costs?goodsReceiptId=" + grn, null)).hasSize(1);
    }

    // ------------------------------------------------------------------ H3 return against a bill

    @Test
    @DisplayName("H3: a second return of the same 10 against a 10-unit bill is refused; after the first is reversed it"
            + " posts")
    void doubleReturnAgainstBillRefused() {
        String supplier = partner("H3 double");
        String product = stockProduct();
        orderAndReceive(supplier, product, 10);
        String bill = postedBill(supplier, product, 10);

        String first = draftReturn(supplier, bill, product, 10);
        String second = draftReturn(supplier, bill, product, 10);
        Res ok1 = postReturn(first);
        assertThat(ok1.status()).as(ok1.raw().body()).isEqualTo(200);
        assertThat(onHand(product)).isEqualByComparingTo("0");

        Res refused = postReturn(second);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("Only 0 of Cement can still be returned against"
                + " supplier bill").contains("it billed 10, earlier returns against it took 10")
                .endsWith("this return asks for 10");
        assertThat(jdbc.sql("SELECT status FROM \"DebitNote\" WHERE id = ?").param(second).query(String.class).single())
                .as("rolled back to draft").isEqualTo("draft");

        money("PATCH", "/procurement/debit-notes/" + first + "/reverse", Map.of("reason", "H3 goods came back"));
        assertThat(onHand(product)).isEqualByComparingTo("10");
        Res again = postReturn(second);
        assertThat(again.status()).as("a reversed return no longer counts: " + again.raw().body()).isEqualTo(200);
        assertThat(onHand(product)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("H3: a return against a bill cannot send back a product the bill does not bill")
    void wrongProductAgainstBillRefused() {
        String supplier = partner("H3 product");
        String billed = stockProduct();
        String other = stockProduct();
        orderAndReceive(supplier, billed, 10);
        adhoc(supplier, other, 10);
        String bill = postedBill(supplier, billed, 10);

        Res r = createReturn(supplier, bill, other, 2);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).startsWith("Supplier bill ")
                .endsWith(" does not bill Cement; a return against a bill can only send back goods that bill billed");
        assertThat(onHand(other)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("H3: two concurrent returns of the whole bill quantity — one posts, the other is refused")
    void concurrentReturnsOnePasses() throws Exception {
        String supplier = partner("H3 race");
        String product = stockProduct();
        orderAndReceive(supplier, product, 10);
        String bill = postedBill(supplier, product, 10);
        String a = draftReturn(supplier, bill, product, 10);
        String b = draftReturn(supplier, bill, product, 10);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Res>> futures = new ArrayList<>();
            for (String id : List.of(a, b)) {
                futures.add(pool.submit(() -> postReturn(id)));
            }
            int ok = 0;
            int refused = 0;
            for (Future<Res> f : futures) {
                Res r = f.get();
                if (r.status() == 200) {
                    ok++;
                } else {
                    assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
                    refused++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(refused).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(onHand(product)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"DebitNote\" WHERE id IN (?, ?) AND status = 'posted'").params(a, b)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("MEDIUM #5: a return naming no bill takes only open unbilled receipt quantity; billed goods must name"
            + " their bill, goods never received are refused")
    void returnWithoutBillCapped() {
        String supplier = partner("M5 billed");
        String product = stockProduct();
        orderAndReceive(supplier, product, 10);
        postedBill(supplier, product, 10);
        Res billed = postReturn(draftReturn(supplier, null, product, 5));
        assertThat(billed.status()).as(billed.raw().body()).isEqualTo(400);
        assertThat(billed.body().get("message").asString()).startsWith("Only 0 of Cement are open on unbilled receipts"
                + " from this supplier (this return asks for 5); the rest is already billed — name the supplier bill");
        assertThat(onHand(product)).isEqualByComparingTo("10");

        String other = partner("M5 unbilled");
        String goods = stockProduct();
        adhoc(other, goods, 5);
        Res beyond = postReturn(draftReturn(other, null, goods, 8));
        assertThat(beyond.status()).as(beyond.raw().body()).isEqualTo(400);
        assertThat(beyond.body().get("message").asString()).isEqualTo("Only 5 of Cement received from this supplier are"
                + " still open to return; this return asks for 8");
        Res within = postReturn(draftReturn(other, null, goods, 5));
        assertThat(within.status()).as(within.raw().body()).isEqualTo(200);
        assertThat(onHand(goods)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ H4 claim vs return

    @Test
    @DisplayName("H4 return then claim: quantity returned (without a bill, or against the bill of the receipt) cannot"
            + " be claimed again")
    void returnThenClaim() {
        String supplier = partner("H4 rc");
        String product = stockProduct();
        String grn = adhoc(supplier, product, 10);
        assertThat(postReturn(draftReturn(supplier, null, product, 4)).status()).isEqualTo(200);
        String claimId = claim(supplier, "damage", 1000, Map.of("goodsReceiptId", grn));
        Res over = addLine(claimId, receiptLineOf(grn), 7);
        assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).contains("Only 6 of 10 received")
                .contains("(4 already returned to the supplier); requested 7");
        assertThat(addLine(claimId, receiptLineOf(grn), 6).status()).isEqualTo(200);

        String supplier2 = partner("H4 rc bill");
        String product2 = stockProduct();
        String grn2 = orderAndReceive(supplier2, product2, 10);
        String bill = postedBill(supplier2, product2, 10);
        assertThat(postReturn(draftReturn(supplier2, bill, product2, 10)).status()).isEqualTo(200);
        String claim2 = claim(supplier2, "damage", 100, Map.of("goodsReceiptId", grn2));
        Res none = addLine(claim2, receiptLineOf(grn2), 1);
        assertThat(none.status()).as(none.raw().body()).isEqualTo(400);
        assertThat(none.body().get("message").asString()).contains("Only 0 of 10 received")
                .contains("10 already returned to the supplier");
    }

    @Test
    @DisplayName("H4 claim then return: verified claim quantity limits a return against the bill; an unverified claim"
            + " overtaken by a return cannot settle")
    void claimThenReturn() {
        String supplier = partner("H4 cr");
        String product = stockProduct();
        String grn = orderAndReceive(supplier, product, 10);
        String bill = postedBill(supplier, product, 10);
        String claimId = claim(supplier, "damage", 400, Map.of("goodsReceiptId", grn));
        Res line = addLine(claimId, receiptLineOf(grn), 4);
        assertThat(line.status()).as(line.raw().body()).isEqualTo(200);
        verifyLast(claimId, line.body());

        Res all = postReturn(draftReturn(supplier, bill, product, 10));
        assertThat(all.status()).as(all.raw().body()).isEqualTo(400);
        assertThat(all.body().get("message").asString()).startsWith("Only 6 of Cement can still be returned")
                .contains("supplier claims or returns without a bill already cover 4 of the received goods");
        assertThat(postReturn(draftReturn(supplier, bill, product, 6)).status()).isEqualTo(200);

        // Unverified claim, then a return of everything: the claim can no longer be accepted (value) nor settled.
        String supplier2 = partner("H4 cr late");
        String product2 = stockProduct();
        String grn2 = orderAndReceive(supplier2, product2, 10);
        String bill2 = postedBill(supplier2, product2, 10);
        String claim2 = claim(supplier2, "damage", 300, Map.of("goodsReceiptId", grn2));
        Res pending = addLine(claim2, receiptLineOf(grn2), 3);
        assertThat(pending.status()).as(pending.raw().body()).isEqualTo(200);
        assertThat(postReturn(draftReturn(supplier2, bill2, product2, 10)).status()).isEqualTo(200);
        verifyLast(claim2, pending.body());
        Res accept = as("POST", "/procurement/claims/" + claim2 + "/accept", Map.of("amount", 300, "response", "ok"));
        assertThat(accept.status()).as(accept.raw().body()).isEqualTo(400);
        assertThat(accept.body().get("message").asString()).contains("(0 of 1000; returns to the supplier already"
                + " credited 1000)");
        assertThat(onHand(product2)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("H4 value: a claim on a bill is capped by the bill less what returns against it credited; a claim"
            + " naming no document cannot be accepted")
    void claimValueCapAndDocument() {
        String supplier = partner("H4 value");
        String product = stockProduct();
        orderAndReceive(supplier, product, 10);
        String bill = postedBill(supplier, product, 10);
        assertThat(postReturn(draftReturn(supplier, bill, product, 6)).status()).isEqualTo(200);
        String claimId = claim(supplier, "price", 500, Map.of("vendorBillId", bill));
        Res over = as("POST", "/procurement/claims/" + claimId + "/accept", Map.of("response", "agreed"));
        assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).contains("(400 of 1000; returns to the supplier already"
                + " credited 600)");
        assertThat(as("POST", "/procurement/claims/" + claimId + "/accept", Map.of("amount", 400, "response", "ok"))
                .status()).isEqualTo(200);

        String loose = claim(supplier, "price", 50, Map.of());
        Res noDoc = as("POST", "/procurement/claims/" + loose + "/accept", Map.of("response", "agreed"));
        assertThat(noDoc.status()).as(noDoc.raw().body()).isEqualTo(400);
        assertThat(noDoc.body().get("message").asString()).endsWith("names no supplier bill, goods receipt or purchase"
                + " order; a claim credits the supplier only against the document it is about, which caps the amount"
                + " — raise the claim on that document");
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("Debit-note post requires an Idempotency-Key; the same key replays without posting twice")
    void debitNotePostNeedsKey() {
        String supplier = partner("Key");
        String product = stockProduct();
        adhoc(supplier, product, 5);
        String note = draftReturn(supplier, null, product, 2);
        Map<String, String> noKey = new LinkedHashMap<>(Map.of("Authorization", "Bearer " + adminToken,
                NO_IDEMPOTENCY_KEY, "1"));
        Res without = call("PATCH", "/procurement/debit-notes/" + note + "/post", null, noKey);
        assertThat(without.status()).as(without.raw().body()).isEqualTo(400);
        Map<String, String> h = key(adminToken);
        Res first = call("PATCH", "/procurement/debit-notes/" + note + "/post", null, h);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = call("PATCH", "/procurement/debit-notes/" + note + "/post", null, h);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(onHand(product)).isEqualByComparingTo("3");
    }
}
