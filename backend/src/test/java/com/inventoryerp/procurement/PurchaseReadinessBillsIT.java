package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.TestApprovers;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Purchase readiness fixes, stream "bills" (owner-approved 2026-11-27): B3 two lines of one bill never consume the
 * same receipt quantity; H1 bills need a goods receipt by default and, in auto-receive mode, a later receipt absorbs
 * what a bill received; H2 duplicate supplier invoice references are blocked (normalised, serialised); H8 posting with
 * an approval override runs the approval workflow; plus PO links of another supplier and the three-way match in base
 * units / across several orders.
 */
class PurchaseReadinessBillsIT extends ApiTestSupport {

    @Autowired
    SettingResolverService settingResolver;

    String warehouse;
    String approver;
    String approver2;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PRB-" + suffix(), "name", "Bills "
                + suffix(), "type", "warehouse")).get("id").asString();
        approver = TestApprovers.secondAdmin(this);
        approver2 = TestApprovers.secondAdmin(this);
    }

    // ------------------------------------------------------------------ helpers

    JsonNode keyed(int status, String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call(method, path, body, h);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String partner() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "PRB-" + suffix(), "Bills " + suffix())
                .update();
        return id;
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable', true, 'AVCO', 0, 0, now())")
                .params(id, demoOrg, "PRB-" + suffix(), "Item " + suffix()).update();
        return id;
    }

    /** A read as the DEMO tenant (RLS tables show no rows to a plain read in CI). */
    <T> T read(Callable<T> body) {
        return TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> new TransactionTemplate(txm).execute(s -> {
                    try {
                        return body.call();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }

    BigDecimal num(String sql, Object... params) {
        return read(() -> jdbc.sql(sql).params(params).query(BigDecimal.class).single());
    }

    BigDecimal onHand(String productId) {
        return num("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?", demoOrg, productId);
    }

    /** What the AP ledger owes the supplier (credit − debit, base currency). */
    BigDecimal supplierAp(String partnerId) {
        return num("SELECT COALESCE(SUM(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\""
                + " AND m.key = 'accounts_payable' AND m.\"deletedAt\" IS NULL WHERE l.\"organizationId\" = ? AND"
                + " l.\"partnerId\" = ?", demoOrg, partnerId);
    }

    /** Net GRNI (debit − credit) on the journals of the given source documents. */
    BigDecimal grni(String... sourceIds) {
        return num("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"AccountMapping\" m ON m.\"accountId\" ="
                + " l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\" AND m.key = 'grni_accrued' AND"
                + " m.\"deletedAt\" IS NULL WHERE l.\"organizationId\" = ? AND e.status = 'posted' AND e.\"sourceId\" ="
                + " ANY(?::text[])", demoOrg, sourceIds);
    }

    BigDecimal billedOnReceipt(String grnId) {
        return num("SELECT COALESCE(SUM(\"billedQuantity\"), 0) FROM \"GoodsReceiptLine\" WHERE \"organizationId\" = ?"
                + " AND \"goodsReceiptId\" = ?", demoOrg, grnId);
    }

    void withBillWithoutReceipt(String mode, Runnable body) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'procurement.billWithoutReceipt', ?::jsonb, 'organization', now())")
                .params(id, demoOrg, "\"" + mode + "\"").update();
        settingResolver.invalidate(demoOrg);
        try {
            body.run();
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(id).update();
            settingResolver.invalidate(demoOrg);
        }
    }

    String po(String supplier, String productId, int qty, int price) {
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", productId,
                        "description", "Cement", "quantity", qty, "unitPrice", price)))).get("id").asString();
    }

    /** PO receive route (PurchaseOrdersService.receive); returns the receipt id. */
    String receive(String poId, String productId, int qty) {
        return keyed(200, "POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))))
                .get("grn").get("id").asString();
    }

    /** Draft goods receipt of a PO, then posted (GoodsReceiptsService.post); returns the receipt id. */
    String receiveByDraft(String poId, String productId, int qty) {
        String poLine = read(() -> jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"organizationId\" = ? AND"
                + " \"purchaseOrderId\" = ?").params(demoOrg, poId).query(String.class).single());
        String grn = ok(201, "POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", poId, "warehouseId",
                warehouse, "lines", List.of(Map.of("purchaseOrderLineId", poLine, "productId", productId,
                        "description", "Cement", "quantity", qty)))).get("id").asString();
        keyed(200, "PATCH", "/procurement/goods-receipts/" + grn + "/post", null);
        return grn;
    }

    String adhocReceipt(String supplier, String productId, int qty, int unitCost) {
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId", productId,
                        "description", "Cement", "quantity", qty, "unitCost", unitCost)))).get("id").asString();
    }

    String bill(String supplier, List<Map<String, Object>> lines) {
        return ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines", lines))
                .get("id").asString();
    }

    static Map<String, Object> line(String productId, int qty, int price) {
        return Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice", price);
    }

    Res postBill(String billId) {
        return call("POST", "/vendor-bills/" + billId + "/post", Map.of("override3WM", true), adminToken);
    }

    JsonNode posted(String billId) {
        Res r = postBill(billId);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    BigDecimal residual(JsonNode bill) {
        return new BigDecimal(bill.get("amountResidual").asString());
    }

    // ------------------------------------------------------------------ B3

    @Test
    @DisplayName("B3: two lines of one bill (10 + 5) against an ad-hoc receipt of 10 match 10 once; the 5 follow the mode")
    void twoLinesOneAdhocReceipt() {
        String supplier = partner();
        String item = product();
        String grn = adhocReceipt(supplier, item, 10, 100);
        String blocked = bill(supplier, List.of(line(item, 10, 100), line(item, 5, 100)));
        // default (block): the 5 nobody received are refused, not matched to the same receipt quantity again
        Res refused = postBill(blocked);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains(": 5 not received yet");
        assertThat(billedOnReceipt(grn)).isEqualByComparingTo("0");

        withBillWithoutReceipt("auto_receive", () -> {
            JsonNode bill = posted(blocked);
            assertThat(billedOnReceipt(grn)).isEqualByComparingTo("10");
            assertThat(onHand(item)).isEqualByComparingTo("15");
            assertThat(residual(bill)).isEqualByComparingTo("1500");
            assertThat(supplierAp(supplier)).isEqualByComparingTo(residual(bill));
            assertThat(grni(grn, blocked)).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("B3: two lines of one bill (10 + 5) against a PO receipt of 10: 10 vouchered once, AP = residual")
    void twoLinesOnePoReceipt() {
        String supplier = partner();
        String item = product();
        String poId = po(supplier, item, 10, 100);
        String grn = receive(poId, item, 10);
        assertThat(supplierAp(supplier)).isEqualByComparingTo("1000");
        withBillWithoutReceipt("auto_receive", () -> {
            String billId = bill(supplier, List.of(line(item, 10, 100), line(item, 5, 100)));
            JsonNode bill = posted(billId);
            assertThat(billedOnReceipt(grn)).isEqualByComparingTo("10");
            assertThat(onHand(item)).isEqualByComparingTo("15");
            assertThat(residual(bill)).isEqualByComparingTo("1500");
            assertThat(supplierAp(supplier)).isEqualByComparingTo("1500");
            assertThat(grni(grn, billId)).isEqualByComparingTo("0");
        });
    }

    // ------------------------------------------------------------------ H1

    @Test
    @DisplayName("H1: by default a bill for stock nobody received is refused (three-way match)")
    void billNeedsReceiptByDefault() {
        String supplier = partner();
        String item = product();
        Res refused = postBill(bill(supplier, List.of(line(item, 3, 10))));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("Bill line ")
                .contains(": 3 not received yet — post the goods receipt first");
        assertThat(onHand(item)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("H1: auto-receive, then the PO receipt (receive route) absorbs the billed stock: no double stock or AP")
    void receiptAbsorbsBillReceivedStock() {
        String supplier = partner();
        String item = product();
        withBillWithoutReceipt("auto_receive", () -> {
            String billId = bill(supplier, List.of(line(item, 4, 50)));
            JsonNode bill = posted(billId);
            assertThat(onHand(item)).isEqualByComparingTo("4");
            assertThat(supplierAp(supplier)).isEqualByComparingTo("200");

            // the goods arrive on the order: 4 were already received by the bill, 2 are new
            String poId = po(supplier, item, 6, 50);
            String grn = receive(poId, item, 6);
            assertThat(onHand(item)).isEqualByComparingTo("6");
            assertThat(billedOnReceipt(grn)).isEqualByComparingTo("4");
            // AP: the bill's 200 plus the receipt voucher for the 2 new units only
            assertThat(supplierAp(supplier)).isEqualByComparingTo("300");
            assertThat(residual(bill)).isEqualByComparingTo("200");

            // the bill's stock is now the receipt's: it cannot be cancelled (stock would leave twice)
            Res cancel = as("POST", "/vendor-bills/" + billId + "/cancel", Map.of("reason", "Raised in error"));
            assertThat(cancel.status()).as(cancel.raw().body()).isEqualTo(400);
            assertThat(cancel.body().get("message").asString()).contains("cannot be cancelled")
                    .contains("matched by goods receipt");
            assertThat(onHand(item)).isEqualByComparingTo("6");
        });
    }

    @Test
    @DisplayName("H1: auto-receive, then a draft goods receipt posted later absorbs the billed stock")
    void draftReceiptAbsorbsBillReceivedStock() {
        String supplier = partner();
        String item = product();
        withBillWithoutReceipt("auto_receive", () -> {
            posted(bill(supplier, List.of(line(item, 5, 40))));
            String poId = po(supplier, item, 5, 40);
            String grn = receiveByDraft(poId, item, 5);
            assertThat(onHand(item)).isEqualByComparingTo("5");
            assertThat(billedOnReceipt(grn)).isEqualByComparingTo("5");
            assertThat(supplierAp(supplier)).isEqualByComparingTo("200");
        });
        // the absorbed receipt is fully billed: a second bill for the same goods finds nothing open to match
        String again = bill(supplier, List.of(line(item, 5, 40)));
        Res refused = postBill(again);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains(": 5 not received yet");
    }

    // ------------------------------------------------------------------ H2

    String serviceBill(String supplier, String reference, int amount) {
        return ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "reference",
                reference, "lines", List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", amount))))
                .get("id").asString();
    }

    @Test
    @DisplayName("H2: the same supplier invoice reference (INV-001 / inv 001 / INV001) is blocked by default")
    void duplicateReferenceBlocked() {
        String supplier = partner();
        String tag = suffix().replaceAll("[^a-z0-9]", "");
        String first = serviceBill(supplier, "INV-" + tag, 700);
        assertThat(posted(first).has("warnings")).isFalse();
        String second = serviceBill(supplier, "inv " + tag.toUpperCase(), 800);
        Res blocked = postBill(second);
        assertThat(blocked.status()).as(blocked.raw().body()).isEqualTo(409);
        assertThat(blocked.body().get("message").asString()).startsWith("Duplicate bill: this supplier already has")
                .contains("with the same invoice reference");
        String third = serviceBill(supplier, "INV" + tag, 900);
        assertThat(postBill(third).status()).isEqualTo(409);
        assertThat(ok(200, "GET", "/procurement/bills/" + third + "/duplicates", null).findValuesAsString("id"))
                .containsExactly(first);
        // another supplier may use the same number
        assertThat(postBill(serviceBill(partner(), "INV-" + tag, 700)).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("H2: the same amount within 7 days only warns by default; the bill still posts")
    void sameAmountWarns() {
        String supplier = partner();
        posted(serviceBill(supplier, "A-" + suffix(), 1234));
        JsonNode second = posted(serviceBill(supplier, "B-" + suffix(), 1234));
        assertThat(second.get("status").asString()).isEqualTo("posted");
        assertThat(second.get("warnings").get(0).asString()).startsWith("Possible duplicate bill");
    }

    @Test
    @DisplayName("H2: two concurrent posts of the same supplier invoice — exactly one posts")
    void concurrentDuplicates() throws Exception {
        String supplier = partner();
        String ref = "RACE-" + suffix();
        List<String> drafts = List.of(serviceBill(supplier, ref, 500), serviceBill(supplier, ref.toLowerCase(), 600));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String d : drafts) {
                futures.add(pool.submit(() -> postBill(d).status()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ H8

    String workflow(int steps, int minAmount) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            list.add(Map.of("stepOrder", i, "name", "Finance " + i, "approverPermissions", List.of("approvals:decide"),
                    "requiredCount", 1));
        }
        return ok(201, "POST", "/approval-workflows", Map.of("name", "Bills H8 " + suffix(), "entityType",
                "vendor_bill", "minAmount", minAmount, "enforceDistinctApprovers", true, "steps", list))
                .get("id").asString();
    }

    String pendingRequest(String billId) {
        return read(() -> jdbc.sql("SELECT id FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\""
                + " = 'vendor_bill' AND \"entityId\" = ? AND status = 'pending' ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(demoOrg, billId).query(String.class).optional().orElse(null));
    }

    Map<String, Object> bigBill(String supplier, String reference) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("description", "Consulting", "quantity", 1, "unitPrice", 9_100_000))));
        body.put("reference", reference);
        return body;
    }

    @Test
    @DisplayName("H8: an approval override is a decision on the workflow — a two-step workflow needs both steps")
    void overrideRunsTheWorkflow() {
        String wf = workflow(2, 9_000_000);
        try {
            String billId = ok(200, "POST", "/vendor-bills", bigBill(partner(), "H8-" + suffix())).get("id").asString();
            String request = pendingRequest(billId);
            assertThat(request).isNotNull();
            Res first = call("POST", "/vendor-bills/" + billId + "/post", Map.of("overrideApproval", true), approver);
            assertThat(first.status()).as(first.raw().body()).isEqualTo(403);
            assertThat(first.body().get("message").asString()).contains("was recorded, but its approval workflow needs"
                    + " further approvers");
            // the step-1 decision survives the refusal; the request moved to step 2
            assertThat(read(() -> jdbc.sql("SELECT \"currentStep\" FROM \"ApprovalRequest\" WHERE id = ?")
                    .param(request).query(Integer.class).single())).isEqualTo(2);
            assertThat(read(() -> jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(billId)
                    .query(String.class).single())).isEqualTo("draft");
            // the same approver cannot take step 2 too (distinct approvers)
            Res again = call("POST", "/vendor-bills/" + billId + "/post", Map.of("overrideApproval", true), approver);
            assertThat(again.status()).as(again.raw().body()).isEqualTo(400);
            Res second = call("POST", "/vendor-bills/" + billId + "/post", Map.of("overrideApproval", true), approver2);
            assertThat(second.status()).as(second.raw().body()).isEqualTo(200);
            assertThat(second.body().get("status").asString()).isEqualTo("posted");
        } finally {
            ok(200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    @Test
    @DisplayName("H8: the last editor cannot approve the bill, by override or on the approvals screen")
    void lastEditorCannotApprove() {
        String wf = workflow(1, 9_000_000);
        try {
            String supplier = partner();
            String ref = "H8E-" + suffix();
            String billId = ok(200, "POST", "/vendor-bills", bigBill(supplier, ref)).get("id").asString();
            Map<String, Object> edit = bigBill(supplier, ref);
            edit.put("notes", "checked");
            Res edited = call("PATCH", "/vendor-bills/" + billId, edit, approver);
            assertThat(edited.status()).as(edited.raw().body()).isEqualTo(200);
            assertThat(edited.body().get("updatedBy").asString()).isNotBlank();
            Res override = call("POST", "/vendor-bills/" + billId + "/post", Map.of("overrideApproval", true),
                    approver);
            assertThat(override.status()).isEqualTo(403);
            assertThat(override.body().get("message").asString())
                    .isEqualTo("You cannot approve a bill you last edited — another approver must decide");

            // approved on the approvals screen by the same editor: posting asks for another approver
            Res decided = call("POST", "/approvals/" + pendingRequest(billId) + "/decide", Map.of("status",
                    "approved"), approver);
            assertThat(decided.status()).as(decided.raw().body()).isEqualTo(201);
            Res post = as("POST", "/vendor-bills/" + billId + "/post", Map.of());
            assertThat(post.status()).as(post.raw().body()).isEqualTo(403);
            assertThat(post.body().get("message").asString())
                    .startsWith("This bill was approved by the person who last edited it");
            String next = pendingRequest(billId);
            assertThat(next).isNotNull();
            assertThat(call("POST", "/approvals/" + next + "/decide", Map.of("status", "approved"), approver2)
                    .status()).isEqualTo(201);
            assertThat(as("POST", "/vendor-bills/" + billId + "/post", Map.of()).status()).isEqualTo(200);
        } finally {
            ok(200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    @Test
    @DisplayName("H8: changing the reference (or dates, branch) after approval needs approval again")
    void referenceChangeNeedsReapproval() {
        String wf = workflow(1, 9_000_000);
        try {
            String supplier = partner();
            String billId = ok(200, "POST", "/vendor-bills", bigBill(supplier, "H8R-" + suffix())).get("id")
                    .asString();
            assertThat(call("POST", "/approvals/" + pendingRequest(billId) + "/decide", Map.of("status", "approved"),
                    approver).status()).isEqualTo(201);
            ok(200, "PATCH", "/vendor-bills/" + billId, bigBill(supplier, "H8R-other-" + suffix()));
            Res post = as("POST", "/vendor-bills/" + billId + "/post", Map.of());
            assertThat(post.status()).as(post.raw().body()).isEqualTo(403);
            assertThat(post.body().get("message").asString())
                    .startsWith("This bill changed after it was approved; it needs approval again");
        } finally {
            ok(200, "DELETE", "/approval-workflows/" + wf, null);
        }
    }

    // ------------------------------------------------------------------ PO links / three-way match

    @Test
    @DisplayName("a bill cannot link a purchase order of another supplier")
    void poOfAnotherSupplier() {
        String supplier = partner();
        String other = partner();
        String item = product();
        String poId = po(other, item, 1, 10);
        Res r = as("POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "purchaseOrderIds",
                List.of(poId), "lines", List.of(line(item, 1, 10))));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("belongs to another supplier");
    }

    @Test
    @DisplayName("three-way match: one bill over two orders counts each bill line once")
    void multiOrderBill() {
        String supplier = partner();
        String item = product();
        String po1 = po(supplier, item, 5, 100);
        String po2 = po(supplier, item, 5, 100);
        receive(po1, item, 5);
        receive(po2, item, 5);
        String billId = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today,
                "purchaseOrderIds", List.of(po1, po2), "lines", List.of(line(item, 10, 100)))).get("id").asString();
        JsonNode v = ok(201, "POST", "/procurement/three-way-match/validate-bill/" + billId, null);
        assertThat(v.get("ok").asBoolean()).as(v.toString()).isTrue();
        assertThat(v.get("matches").findValuesAsString("status")).containsOnly("matched");
    }

    @Test
    @DisplayName("three-way match: a bill in boxes matches an order and receipt in pieces (base units)")
    void matchAcrossUnits() {
        String supplier = partner();
        String pcs = unit("PCS");
        String box = unit("BOX");
        String item = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, 0, ?, ?, now())")
                .params(item, demoOrg, "PRB-" + suffix(), "Water " + suffix(), pcs, pcs).update();
        ok(200, "PATCH", "/products/" + item, Map.of("units", List.of(Map.of("uomId", box, "factor", 24,
                "isPurchase", true, "isSales", true))));
        String poId = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", item,
                        "description", "Water", "quantity", 48, "unitPrice", 500, "unitOfMeasureId", pcs))))
                .get("id").asString();
        receive(poId, item, 48);
        String billId = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today,
                "purchaseOrderIds", List.of(poId), "lines", List.of(Map.of("productId", item, "description", "Water",
                        "quantity", 2, "unitPrice", 12000, "uomId", box)))).get("id").asString();
        JsonNode v = ok(201, "POST", "/procurement/three-way-match/validate-bill/" + billId, null);
        assertThat(v.get("ok").asBoolean()).as(v.toString()).isTrue();
        assertThat(v.get("matches").get(0).get("status").asString()).isEqualTo("matched");
    }

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                        + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 1, now())")
                .params(id, demoOrg, code + "-" + suffix(), code).update();
        return id;
    }
}
