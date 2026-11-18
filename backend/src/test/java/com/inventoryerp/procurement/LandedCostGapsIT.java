package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.BooksGuard;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Readiness gaps h-05 (landed cost) and h-06 F13, stream gF: one liability per supplier invoice between ordinary and
 * generated bills (F1), linked bills checked (F3), separation of duties on approval and overrides (F4), credit vouchers
 * bound to their original (F5), legacy post serialised with a receipt reversal (F6), STANDARD reversal back to where the
 * release went (F7), reconciliation of partly cleared accruals (F8), variant and lot matching (F10, F11), idempotency
 * keys (F12), customs links (F14), a read-only reconciliation (F15), the receipt screen's voucher payload (F2) and the
 * reversal reason of a legacy landed cost (h-06 F13).
 */
@ExtendWith(BooksGuard.class)
class LandedCostGapsIT extends LandedCostFixtures {

    @Autowired
    DataSource dataSource;

    @Autowired
    CostLineageService lineage;

    // ------------------------------------------------------------------ helpers

    String userWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Gaps " + role, permissions).update();
        String email = "lc-gaps-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Controls@12345", role);
        return login("DEMO", email, "Controls@12345");
    }

    static Map<String, String> keyFor(String token) {
        return Map.of("Authorization", "Bearer " + token, "Idempotency-Key", UUID.randomUUID().toString());
    }

    void sod(String pair, boolean on) {
        ok(200, "PUT", "/approval-controls/sod-rules/" + pair, Map.of("isActive", on));
    }

    String vouchers(String id, String action) {
        return "/procurement/landed-cost-vouchers/" + id + "/" + action;
    }

    /** The landed-cost clearing account (2155), created by a throwaway voucher on a fresh database. */
    String clearing() {
        String id = code("2155");
        if (id == null) {
            String p = product("AVCO", false, 0);
            approveAndPost(voucher(List.of(receive(p, 1, 1)), List.of(charge("kind", "other", "amount", 1))));
            id = code("2155");
        }
        return id;
    }

    /** An ordinary supplier bill (draft) of one line booked to {@code accountId}. */
    String enterBill(String partnerId, String reference, String accountId, int amount) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("accountId", accountId);
        line.put("description", "Freight");
        line.put("quantity", 1);
        line.put("unitPrice", amount);
        Res r = call("POST", "/vendor-bills", Map.of("partnerId", partnerId, "issueDate", LocalDate.now().toString(),
                "reference", reference, "lines", List.of(line)), key());
        assertThat(r.status()).as(r.raw().body()).isBetween(200, 201);
        return r.body().get("id").asString();
    }

    Res postBill(String id) {
        return call("POST", "/vendor-bills/" + id + "/post", Map.of(), key());
    }

    String postedBill(String partnerId, String reference, String accountId, int amount) {
        String id = enterBill(partnerId, reference, accountId, amount);
        Res r = postBill(id);
        assertThat(r.status()).as(r.raw().body()).isBetween(200, 201);
        return id;
    }

    String receiveLot(String productId, int qty, int unitCost, String lot) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "Goods");
        line.put("quantity", qty);
        line.put("unitCost", unitCost);
        line.put("batchNumber", lot);
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", vendor, "warehouseId", w1,
                "receivedAt", Instant.now().toString(), "lines", List.of(line))).get("id").asString();
    }

    String legacy(String grn, int amount) {
        return ok(200, "POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId",
                mapped("accounts_payable"), "charges", List.of(Map.of("kind", "freight", "amount", amount))))
                .get("id").asString();
    }

    String credit(String voucherId, int amount) {
        String chargeId = ok(200, "GET", "/procurement/landed-cost-vouchers/" + voucherId, null).get("charges").get(0)
                .get("id").asString();
        Res r = call("POST", vouchers(voucherId, "credit"), Map.of("charges", List.of(Map.of("chargeId", chargeId,
                "amount", amount, "supplierInvoiceRef", "CN-" + suffix()))), key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body().get("id").asString();
    }

    long audits(String entityId, String field, String value) {
        return jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND \"entityId\" = ? AND"
                + " \"newValues\"->>? = ?").params(demoOrg, entityId, field, value).query(Long.class).single();
    }

    void status(String landedCostId, String status) {
        jdbc.sql("UPDATE \"LandedCost\" SET status = ? WHERE id = ?").params(status, landedCostId).update();
    }

    String voucherStatus(String id) {
        return jdbc.sql("SELECT status FROM \"LandedCost\" WHERE id = ?").param(id).query(String.class).single();
    }

    // ------------------------------------------------------------------ F1 one liability per supplier invoice

    @Test
    @DisplayName("h05-F1: an invoice already billed in payables cannot be generated again by a voucher")
    void generatedBillAfterOrdinaryBill() {
        String supplier = partner("F1 carrier");
        String ref = "FR-77-" + suffix();
        postedBill(supplier, ref, accounts().get("expense"), 500);
        String grn = receive(product("AVCO", false, 0), 10, 50);
        Res r = createVoucher(List.of(grn), List.of(charge("kind", "freight", "amount", 500, "supplierId", supplier,
                "supplierInvoiceRef", ref, "billMode", "generate")));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(409);
        assertThat(r.raw().body()).contains("is already entered as supplier bill")
                .contains("link that bill to the charge instead of generating another");
    }

    @Test
    @DisplayName("h05-F1: an ordinary bill for an invoice a voucher bills itself is refused, before and after posting")
    void ordinaryBillAfterGeneratingVoucher() {
        String supplier = partner("F1 forwarder");
        String ref = "INV-AB-12-" + suffix();
        String grn = receive(product("AVCO", false, 0), 10, 50);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 300, "supplierId", supplier,
                "supplierInvoiceRef", ref, "billMode", "generate")));
        // Spelled differently: strict matching (the default) still sees the same invoice.
        String bill = enterBill(supplier, ref.toLowerCase().replace('-', ' '), accounts().get("expense"), 300);
        Res draft = postBill(bill);
        assertThat(draft.status()).as(draft.raw().body()).isEqualTo(409);
        assertThat(draft.raw().body()).contains("which raises its own supplier bill");

        approveAndPost(v);
        Res posted = postBill(bill);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(409);
        assertThat(posted.raw().body()).contains("is already billed by landed cost").contains("cancel this bill");
    }

    // ------------------------------------------------------------------ F3 linked bills

    @Test
    @DisplayName("h05-F3: a linked bill must debit clearing, in the charge's currency, never linked beyond its amount")
    void linkedBillsAreChecked() {
        String clearing = clearing();
        String grn = receive(product("AVCO", false, 0), 100, 100);
        String supplier = partner("F3 customs");

        String expenseBill = postedBill(supplier, "EXP-" + suffix(), accounts().get("expense"), 500);
        Res toExpense = createVoucher(List.of(grn), List.of(charge("kind", "duty", "amount", 500, "billMode", "link",
                "vendorBillId", expenseBill)));
        assertThat(toExpense.status()).as(toExpense.raw().body()).isEqualTo(400);
        assertThat(toExpense.raw().body()).contains("books nothing to landed cost clearing");

        String bill = postedBill(supplier, "ASM-" + suffix(), clearing, 5000);
        Res otherCredit = createVoucher(List.of(grn), List.of(charge("kind", "duty", "amount", 100, "billMode", "link",
                "vendorBillId", bill, "creditAccountId", mapped("accounts_payable"))));
        assertThat(otherCredit.status()).as(otherCredit.raw().body()).isEqualTo(400);
        assertThat(otherCredit.raw().body()).contains("a linked supplier bill is cleared through landed cost clearing");

        String base = jdbc.sql("SELECT upper(coalesce(\"currencyCode\", '')) FROM \"Organization\" WHERE id = ?")
                .param(demoOrg).query(String.class).single();
        Res currency = createVoucher(List.of(grn), List.of(charge("kind", "duty", "amount", 100, "billMode", "link",
                "vendorBillId", bill, "currencyCode", "USD".equals(base) ? "EUR" : "USD", "exchangeRate", 2)));
        assertThat(currency.status()).as(currency.raw().body()).isEqualTo(400);
        assertThat(currency.raw().body()).contains("link a bill in the charge's currency");

        voucher(List.of(grn), List.of(charge("kind", "duty", "amount", 3000, "billMode", "link", "vendorBillId", bill)));
        voucher(List.of(grn), List.of(charge("kind", "duty", "amount", 2000, "billMode", "link", "vendorBillId", bill)));
        Res beyond = createVoucher(List.of(grn), List.of(charge("kind", "duty", "amount", 1, "billMode", "link",
                "vendorBillId", bill)));
        assertThat(beyond.status()).as(beyond.raw().body()).isEqualTo(400);
        assertThat(beyond.raw().body()).contains("books 5000 to landed cost clearing but its landed cost charges total"
                + " 5001");
    }

    @Test
    @DisplayName("h05-F3: a linked bill goes through the duplicate registry like any other charge")
    void linkedBillMeetsRegistry() {
        String clearing = clearing();
        String grn = receive(product("AVCO", false, 0), 10, 100);
        String supplier = partner("F3 agent");
        String ref = "AG-" + suffix();
        String first = voucher(List.of(grn), List.of(charge("kind", "handling", "amount", 100, "supplierId", supplier,
                "supplierInvoiceRef", ref, "billMode", "none")));
        String bill = postedBill(supplier, ref, clearing, 100);
        Res linked = createVoucher(List.of(grn), List.of(charge("kind", "handling", "amount", 100, "billMode", "link",
                "vendorBillId", bill)));
        assertThat(linked.status()).as(linked.raw().body()).isEqualTo(409);
        assertThat(linked.raw().body()).contains("is already on landed cost");
        ok(200, "POST", vouchers(first, "cancel"), null);
        assertThat(createVoucher(List.of(grn), List.of(charge("kind", "handling", "amount", 100, "billMode", "link",
                "vendorBillId", bill))).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ F4 separation of duties

    @Test
    @DisplayName("h05-F4: with the landed cost pair on, the maker cannot approve the voucher; another approver can")
    void approvalSeparation() {
        String grn = receive(product("AVCO", false, 0), 10, 50);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100)));
        ok(200, "POST", vouchers(v, "submit"), null);
        sod(SegregationOfDutiesService.LANDED_COST_APPROVAL, true);
        try {
            Res byMaker = call("POST", vouchers(v, "approve"), null, adminToken);
            assertThat(byMaker.status()).as(byMaker.raw().body()).isEqualTo(403);
            assertThat(byMaker.raw().body()).contains("Separation of duties").contains("(Landed cost approval)");
            Res byOther = call("POST", vouchers(v, "approve"), null, userWith("landed_cost:read", "landed_cost:approve"));
            assertThat(byOther.status()).as(byOther.raw().body()).isEqualTo(200);
            assertThat(byOther.body().get("status").asString()).isEqualTo("approved");
        } finally {
            sod(SegregationOfDutiesService.LANDED_COST_APPROVAL, false);
        }
    }

    @Test
    @DisplayName("h05-F4: a voucher that raises supplier bills also answers to the supplier bill approval pair")
    void generatedBillSeparation() {
        String grn = receive(product("AVCO", false, 0), 10, 50);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100, "supplierId", forwarder,
                "supplierInvoiceRef", "SB-" + suffix(), "billMode", "generate")));
        ok(200, "POST", vouchers(v, "submit"), null);
        sod(SegregationOfDutiesService.SUPPLIER_BILL_APPROVAL, true);
        try {
            Res byMaker = call("POST", vouchers(v, "approve"), null, adminToken);
            assertThat(byMaker.status()).as(byMaker.raw().body()).isEqualTo(403);
            assertThat(byMaker.raw().body()).contains("(Supplier bill approval)");
        } finally {
            sod(SegregationOfDutiesService.SUPPLIER_BILL_APPROVAL, false);
        }
        ok(200, "POST", vouchers(v, "cancel"), null);
    }

    @Test
    @DisplayName("h05-F4: whoever approved a duplicate override cannot use it; someone else can")
    void overrideSeparation() {
        String grn = receive(product("AVCO", false, 0), 10, 50);
        String ref = "DUP-" + suffix();
        voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100, "supplierId", forwarder,
                "supplierInvoiceRef", ref, "billMode", "none")));
        Res o = call("POST", "/procurement/invoice-duplicate-overrides", Map.of("supplierId", forwarder,
                "supplierInvoiceRef", ref, "reason", "Second leg of a split shipment"), key());
        assertThat(o.status()).as(o.raw().body()).isIn(200, 201);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsReceiptIds", List.of(grn));
        body.put("charges", List.of(charge("kind", "freight", "amount", 100, "supplierId", forwarder,
                "supplierInvoiceRef", ref, "billMode", "none", "duplicateOverrideId", o.body().get("id").asString())));
        sod(SegregationOfDutiesService.LANDED_COST_APPROVAL, true);
        try {
            Res mine = call("POST", "/procurement/landed-cost-vouchers", body, key());
            assertThat(mine.status()).as(mine.raw().body()).isEqualTo(403);
            assertThat(mine.raw().body()).contains("cannot also use it on a charge");
            Res theirs = call("POST", "/procurement/landed-cost-vouchers", body,
                    keyFor(userWith("landed_cost:read", "landed_cost:create")));
            assertThat(theirs.status()).as(theirs.raw().body()).isEqualTo(200);
        } finally {
            sod(SegregationOfDutiesService.LANDED_COST_APPROVAL, false);
        }
    }

    // ------------------------------------------------------------------ F5 credit vouchers

    @Test
    @DisplayName("h05-F5: reversing a voucher cancels its draft credit; a stale credit cannot be submitted, approved or posted")
    void creditFollowsItsOriginal() {
        String grn = receive(product("AVCO", false, 0), 100, 50);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        String c = credit(v, 200);
        reverse(v);
        assertThat(voucherStatus(c)).isEqualTo("cancelled");
        assertThat(audits(c, "kind", "landed_cost_voucher_cancelled")).isEqualTo(1);

        // A credit left from before the fix (not cancelled with its original) is stopped at every step.
        try {
            status(c, "draft");
            Res submit = call("POST", vouchers(c, "submit"), null, adminToken);
            assertThat(submit.status()).as(submit.raw().body()).isEqualTo(400);
            assertThat(submit.raw().body()).contains("cannot be submitted while its original voucher")
                    .contains("is reversed; cancel the credit voucher");
            status(c, "submitted");
            Res approve = call("POST", vouchers(c, "approve"), null, adminToken);
            assertThat(approve.status()).as(approve.raw().body()).isEqualTo(400);
            assertThat(approve.raw().body()).contains("cannot be approved while its original voucher");
            status(c, "approved");
            Res post = call("POST", vouchers(c, "post"), null, key());
            assertThat(post.status()).as(post.raw().body()).isEqualTo(400);
            assertThat(post.raw().body()).contains("cannot be posted while its original voucher");
        } finally {
            status(c, "cancelled");
        }
    }

    // ------------------------------------------------------------------ F6 legacy post vs receipt reversal

    @Test
    @DisplayName("h05-F6: a legacy post waits while a receipt reversal holds the receipt, then finds its draft cancelled")
    void legacyPostWaitsForReceiptReversal() throws Exception {
        String grn = receive(product("AVCO", false, 0), 10, 50);
        String lc = legacy(grn, 100);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // What GoodsReceiptsService.reverse does first: lock the receipt row.
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM \"GoodsReceiptNote\" WHERE id = ? FOR"
                        + " UPDATE")) {
                    ps.setString(1, grn);
                    ps.executeQuery().close();
                }
                CompletableFuture<Res> posting = CompletableFuture.supplyAsync(() -> call("POST",
                        "/procurement/landed-costs/" + lc + "/post", null, key()));
                Thread.sleep(1500);
                assertThat(posting.isDone()).as("the legacy post waits for the receipt lock").isFalse();
                // ... then cancels the receipt's draft landed costs and commits.
                try (PreparedStatement ps = c.prepareStatement("UPDATE \"LandedCost\" SET status = 'cancelled' WHERE"
                        + " \"goodsReceiptId\" = ? AND status = 'draft'")) {
                    ps.setString(1, grn);
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                }
                c.commit();
                Res r = posting.get(60, TimeUnit.SECONDS);
                assertThat(r.status()).as(r.raw().body()).isEqualTo(409);
                assertThat(r.raw().body()).contains("Landed cost is not a draft");
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        }
        assertThat(voucherStatus(lc)).isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT \"journalEntryId\" FROM \"LandedCost\" WHERE id = ?").param(lc).query(String.class)
                .optional().orElse(null)).isNull();
    }

    // ------------------------------------------------------------------ F7 STANDARD reversal after a release

    @Test
    @DisplayName("h05-F7: STANDARD, 10 of 100 wasted and released to scrap; reverse → the 100 comes back from scrap, not COGS")
    void standardReversalCreditsTheReleaseAccount() {
        String p = product("STANDARD", false, 50);
        String grn = receive(p, 100, 50);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        asAdmin(() -> stock.issue(StockMove.of(p, w1, BigDecimal.TEN).moveType("waste").source("audit", suffix())));
        asAdmin(() -> lineage.release());
        Snap s = snap();
        reverse(v);
        assertBooks(s, moves("adjunct", "-900", "scrap", "-100", "clearing", "1000"));
    }

    // ------------------------------------------------------------------ F8 reconciliation of accruals

    @Test
    @DisplayName("h05-F8: after a partial clear the 2156 sub-ledger moves with the GL (400 still open)")
    void reconciliationCountsPartlyClearedAccruals() {
        Res created = call("POST", "/procurement/landed-cost-accrual-rules", Map.of("scopeType", "supplier", "scopeId",
                vendor, "ratePct", 10), key());
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String rule = created.body().get("id").asString();
        try {
            String grn = receive(product("AVCO", false, 0), 100, 100);
            BigDecimal[] before = accrualRow();
            Res a = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), key());
            assertThat(a.status()).as(a.raw().body()).isIn(200, 201);
            String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 600)));
            approveAndPost(v);
            Res c = call("POST", "/procurement/landed-cost-accruals/" + a.body().get("id").asString() + "/clear",
                    Map.of("voucherId", v), key());
            assertThat(c.status()).as(c.raw().body()).isIn(200, 201);
            BigDecimal[] after = accrualRow();
            assertThat(after[0].subtract(before[0])).as("2156 sub-ledger").isEqualByComparingTo("400");
            assertThat(after[1].subtract(before[1])).as("2156 GL").isEqualByComparingTo("400");
        } finally {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + rule, null);
        }
    }

    BigDecimal[] accrualRow() {
        for (JsonNode row : ok(200, "GET", "/procurement/landed-cost-reports/reconciliation", null).get("rows")) {
            if ("2156".equals(row.get("key").asString())) {
                return new BigDecimal[] {new BigDecimal(row.get("subledger").asString()),
                        new BigDecimal(row.get("gl").asString())};
            }
        }
        throw new AssertionError("no 2156 row");
    }

    // ------------------------------------------------------------------ F10 / F11 matching

    @Test
    @DisplayName("h05-F10: two variants on one receipt: a charge on the second line lands on that line's variant")
    void variantsMatchTheirOwnLines() {
        String p = product("AVCO", false, 0);
        String a = "lcg-a-" + UUID.randomUUID();
        String b = "lcg-b-" + UUID.randomUUID();
        for (String v : List.of(a, b)) {
            jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, \"combinationKey\","
                    + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, now())").params(v, demoOrg, p, "V " + v, v).update();
        }
        // Line 1 is variant B, line 2 variant A; the receipt writes its stock rows in lock order (A first).
        List<Map<String, Object>> lines = List.of(
                Map.of("productId", p, "variantId", b, "description", "B", "quantity", 10, "unitCost", 100),
                Map.of("productId", p, "variantId", a, "description", "A", "quantity", 10, "unitCost", 100));
        String grn = ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", vendor, "warehouseId", w1,
                "receivedAt", Instant.now().toString(), "lines", lines)).get("id").asString();
        String lineB = jdbc.sql("SELECT id FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ? AND \"variantId\" = ?")
                .params(grn, b).query(String.class).single();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000, "appliesToLineIds",
                List.of(lineB)))));
        assertThat(variantAvg(p, b)).isEqualByComparingTo("200");
        assertThat(variantAvg(p, a)).isEqualByComparingTo("100");
    }

    BigDecimal variantAvg(String productId, String variantId) {
        return jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND \"variantId\" = ?"
                + " AND \"locationId\" = ?").params(productId, variantId, w1).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("h05-F11: one lot number on two receipts, 80 sold → the first receipt's charge is 200 stock / 800 COGS")
    void lotSharedByTwoReceipts() {
        String p = product("FIFO", true, 0);
        String lot = "LOT-" + suffix();
        String first = receiveLot(p, 100, 50, lot);
        receiveLot(p, 100, 50, lot);
        sell(p, w1, 80);
        Snap s = snap();
        approveAndPost(voucher(List.of(first), List.of(charge("kind", "freight", "amount", 1000))));
        assertBooks(s, moves("stock", "200", "cogs", "800", "clearing", "-1000"));
    }

    // ------------------------------------------------------------------ F12 idempotency keys

    @Test
    @DisplayName("h05-F12: post, reverse, credit and duplicate override refuse a request without an Idempotency-Key")
    void moneyRoutesNeedAKey() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> creditBody = Map.of("charges", List.of(Map.of("chargeId", id, "amount", 1)));
        Map<String, Object> overrideBody = Map.of("supplierId", forwarder, "supplierInvoiceRef", "X-" + suffix(),
                "reason", "test");
        for (Object[] route : List.of(new Object[] {vouchers(id, "post"), null},
                new Object[] {vouchers(id, "reverse"), null}, new Object[] {vouchers(id, "credit"), creditBody},
                new Object[] {"/procurement/invoice-duplicate-overrides", overrideBody})) {
            Res r = call("POST", (String) route[0], route[1], adminToken);
            assertThat(r.status()).as(route[0] + " " + r.raw().body()).isEqualTo(400);
            assertThat(r.raw().body()).contains("Idempotency-Key header is required");
        }
    }

    // ------------------------------------------------------------------ F14 customs links

    @Test
    @DisplayName("h05-F14: a customs link is checked, audited, never overwritten, and follows an edited draft")
    void customsLinks() {
        String hs = "84" + (10 + (int) (Math.random() * 89)) + "." + suffix().substring(0, 4);
        Res t = call("POST", "/procurement/customs/tariffs", Map.of("importCountry", "TZ", "effectiveFrom",
                LocalDate.now().minusDays(1).toString(), "hsCode", hs, "dutyType", "ad_valorem", "adValoremPct", 10,
                "valuationBasis", "cif"), key());
        assertThat(t.status()).as(t.raw().body()).isIn(200, 201);
        String[] calcs = new String[2];
        BigDecimal total = null;
        for (int i = 0; i < 2; i++) {
            Res c = call("POST", "/procurement/customs/calculations", Map.of("importCountry", "TZ", "declarationDate",
                    LocalDate.now().toString(), "hsCode", hs, "fobValue", 900, "freight", 60, "insurance", 40), key());
            assertThat(c.status()).as(c.raw().body()).isIn(200, 201);
            calcs[i] = c.body().get("id").asString();
            total = new BigDecimal(c.body().get("totalAmount").asString());
        }
        assertThat(total.signum()).isPositive();
        BigDecimal dutyAmount = total.multiply(new BigDecimal("1.5"));
        String grn = receive(product("AVCO", false, 0), 10, 100);
        List<Map<String, Object>> charges = List.of(charge("kind", "duty", "amount", dutyAmount),
                charge("kind", "freight", "amount", 50));
        String v = voucher(List.of(grn), charges);
        JsonNode lines = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v, null).get("charges");
        String duty = lines.get(0).get("id").asString();
        String freight = lines.get(1).get("id").asString();

        Res linked = call("POST", "/procurement/customs/calculations/" + calcs[0] + "/link", Map.of("chargeId", duty),
                adminToken);
        assertThat(linked.status()).as(linked.raw().body()).isIn(200, 201);
        assertThat(audits(calcs[0], "landedCostChargeId", duty)).isEqualTo(1);
        Res moved = call("POST", "/procurement/customs/calculations/" + calcs[0] + "/link", Map.of("chargeId", freight),
                adminToken);
        assertThat(moved.status()).as(moved.raw().body()).isEqualTo(409);
        assertThat(moved.raw().body()).contains("is already linked to charge 1 of landed cost");
        Res over = call("POST", "/procurement/customs/calculations/" + calcs[1] + "/link", Map.of("chargeId", duty),
                adminToken);
        assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
        assertThat(over.raw().body()).contains("more than the charge's");
        assertThat(call("POST", "/procurement/customs/calculations/" + calcs[1] + "/link", Map.of("chargeId",
                UUID.randomUUID().toString()), adminToken).status()).isEqualTo(404);

        // Editing the draft rewrites its charges: the link follows charge 1 while it still fits.
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("goodsReceiptIds", List.of(grn));
        edit.put("charges", charges);
        ok(200, "PUT", "/procurement/landed-cost-vouchers/" + v, edit);
        String duty2 = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v, null).get("charges").get(0).get("id")
                .asString();
        assertThat(duty2).isNotEqualTo(duty);
        assertThat(linkOf(calcs[0])).isEqualTo(duty2);
        edit.put("charges", List.of(charge("kind", "duty", "amount", total.divide(BigDecimal.TWO)),
                charge("kind", "freight", "amount", 50)));
        ok(200, "PUT", "/procurement/landed-cost-vouchers/" + v, edit);
        assertThat(linkOf(calcs[0])).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND \"entityId\" = ? AND"
                + " \"newValues\"->>'outcome' LIKE 'unlinked: voucher edited%'").params(demoOrg, calcs[0])
                .query(Long.class).single()).isEqualTo(1);

        // A cancelled voucher's charge takes no link.
        String duty3 = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v, null).get("charges").get(0).get("id")
                .asString();
        ok(200, "POST", vouchers(v, "cancel"), null);
        Res dead = call("POST", "/procurement/customs/calculations/" + calcs[0] + "/link", Map.of("chargeId", duty3),
                adminToken);
        assertThat(dead.status()).as(dead.raw().body()).isEqualTo(400);
        assertThat(dead.raw().body()).contains("is not a live charge");
    }

    String linkOf(String calcId) {
        return jdbc.sql("SELECT \"landedCostChargeId\" FROM \"CustomsCalculation\" WHERE id = ?").param(calcId)
                .query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ F15 read-only reconciliation

    @Test
    @DisplayName("h05-F15: the reconciliation only reads: another organisation gets its report and no new accounts")
    void reconciliationOnlyReads() {
        String sql = "SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ? AND code IN ('2155', '2156', '1396')";
        long before = jdbc.sql(sql).param(otherOrg).query(Long.class).single();
        Res r = call("GET", "/procurement/landed-cost-reports/reconciliation", null, otherToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().get("rows")).isNotEmpty();
        assertThat(jdbc.sql(sql).param(otherOrg).query(Long.class).single()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ F2 / h-06 F13 receipt screen

    @Test
    @DisplayName("h05-F2: the receipt screen's voucher (refilled AVCO pool, 60 of 100 sold) → 400 stock / 600 COGS")
    void receiptScreenVoucher() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        sell(p, w1, 60);
        receive(p, 100, 50);
        Snap s = snap();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsReceiptIds", List.of(grn));
        body.put("notes", "From the receipt screen");
        body.put("charges", List.of(Map.of("kind", "freight", "description", "Truck", "amount", 1000,
                "allocationMethod", "value", "billMode", "none")));
        Res r = call("POST", "/procurement/landed-cost-vouchers", body, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        approveAndPost(r.body().get("id").asString());
        assertBooks(s, moves("stock", "400", "cogs", "600", "clearing", "-1000"));
    }

    @Test
    @DisplayName("h06-F13: a posted legacy landed cost is reversed with a reason kept in the audit trail")
    void legacyReversalWithReason() {
        String grn = receive(product("AVCO", false, 0), 10, 50);
        String lc = legacy(grn, 100);
        Res posted = call("POST", "/procurement/landed-costs/" + lc + "/post", null, key());
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        Res tooLong = call("POST", "/procurement/landed-costs/" + lc + "/reverse", Map.of("reason", "x".repeat(501)),
                key());
        assertThat(tooLong.status()).as(tooLong.raw().body()).isEqualTo(400);
        String reason = "Freight billed to the wrong receipt " + suffix();
        Res rev = call("POST", "/procurement/landed-costs/" + lc + "/reverse", Map.of("reason", reason), key());
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(rev.body().get("status").asString()).isEqualTo("reversed");
        assertThat(audits(lc, "reason", reason)).isEqualTo(1);
    }
}
