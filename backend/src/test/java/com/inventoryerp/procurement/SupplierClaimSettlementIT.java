package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * R11: settling an accepted supplier claim posts a supplier credit — Dr the supplier's AP (tagged with the partner) /
 * Cr the claimed lines' counter account and the input VAT they recovered — and lowers the claimed bill's open amount.
 * Before R11 it drafted an outbound debit note (Dr AR / Cr revenue) and never touched the bill.
 *
 * <p>Owner decision (claims without quantities): the settlement is classified — price variance, expense correction,
 * VAT adjustment, or stock adjustment, the last only with quantity lines verified by a second user. Only a stock
 * adjustment may move stock (through the stock engine's return-to-supplier issue) or GRNI.
 */
class SupplierClaimSettlementIT extends AccountingFixtures {

    String vat;
    String checkerId;
    String checkerToken;

    @BeforeAll
    void fixtures() {
        vat = ok(201, "POST", "/taxes", Map.of("name", "R11 VAT " + UUID.randomUUID().toString().substring(0, 6),
                "rate", 18, "type", "vat", "vatCategory", "standard")).get("id").asString();
        String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\" u"
                + " ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "claim-verifier-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        checkerId = createUser(demoOrg, email, "Verifier@12345", role);
        checkerToken = login("DEMO", email, "Verifier@12345");
    }

    BigDecimal residual(String documentId) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(documentId)
                .query(BigDecimal.class).single();
    }

    String paymentStatus(String documentId) {
        return jdbc.sql("SELECT \"paymentStatus\"::text FROM \"Document\" WHERE id = ?").param(documentId)
                .query(String.class).single();
    }

    /** Net (debit − credit) per account code of one entry. */
    Map<String, BigDecimal> netByCode(String entryId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> l : lines(entryId)) {
            out.merge((String) l.get("code"), ((BigDecimal) l.get("debit")).subtract((BigDecimal) l.get("credit")),
                    BigDecimal::add);
        }
        return out;
    }

    /** AP debit of the entry on lines tagged with {@code partnerId}. */
    BigDecimal apDebit(String entryId, String partnerId) {
        return jdbc.sql("SELECT coalesce(sum(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"AccountMapping\" m"
                + " ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\" AND m.key ="
                + " 'accounts_payable' AND m.\"deletedAt\" IS NULL WHERE l.\"journalEntryId\" = ? AND"
                + " l.\"partnerId\" = ?")
                .params(entryId, partnerId).query(BigDecimal.class).single();
    }

    String claim(String supplier, String type, Object amount, Map<String, String> links) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "type", type, "amount", amount,
                "description", "R11 " + type));
        body.putAll(links);
        String id = ok(201, "POST", "/procurement/claims", body).get("id").asString();
        ok(200, "POST", "/procurement/claims/" + id + "/submit", null);
        ok(200, "POST", "/procurement/claims/" + id + "/accept", Map.of("response", "agreed"));
        return id;
    }

    JsonNode settle(String claimId) {
        return demoMoney("POST", "/procurement/claims/" + claimId + "/settle", null);
    }

    JsonNode settleAs(String claimId, String classification) {
        return demoMoney("POST", "/procurement/claims/" + claimId + "/settle", Map.of("classification", classification));
    }

    Res trySettle(String claimId, String classification) {
        return call("POST", "/procurement/claims/" + claimId + "/settle", classification == null ? null
                : Map.of("classification", classification), demoKey());
    }

    String warehouse() {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "CLQ-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "Claim store", "type", "warehouse")).get("id").asString();
    }

    /** A credit PO of 10 × 100 at 18 % for a stock product, received in full; returns the receipt id. */
    String receivedPo(String supplier, String product, String warehouse, Map<String, String> out) {
        String poId = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "currencyCode", baseCurrency(demoOrg), "lines", List.of(Map.of(
                        "productId", product, "description", "Cement", "quantity", 10, "unitPrice", 100, "taxRate",
                        18)))).get("id").asString();
        out.put("poId", poId);
        return demoMoney("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", 10))))
                .get("grn").get("id").asString();
    }

    BigDecimal onHand(String product) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(demoOrg, product).query(BigDecimal.class).single();
    }

    /** The first receipt line offered for the claim. */
    String receiptLine(String claimId) {
        JsonNode lines = ok(200, "GET", "/procurement/claims/" + claimId + "/receipt-lines", null);
        assertThat(lines).isNotEmpty();
        return lines.get(0).get("goodsReceiptLineId").asString();
    }

    /** Record a quantity line (admin) and have the second user verify it; returns the line id. */
    String verifiedLine(String claimId, String receiptLineId, Object qty, String stockAction) {
        JsonNode claim = ok(200, "POST", "/procurement/claims/" + claimId + "/quantity-lines", Map.of(
                "goodsReceiptLineId", receiptLineId, "quantity", qty, "stockAction", stockAction));
        JsonNode lines = claim.get("quantityLines");
        String lineId = lines.get(lines.size() - 1).get("id").asString();
        Res v = call("POST", "/procurement/claims/" + claimId + "/quantity-lines/" + lineId + "/verify", null,
                checkerToken);
        assertThat(v.status()).as(v.raw().body()).isEqualTo(200);
        return lineId;
    }

    String postedServiceBill(String supplier) {
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today(), "lines",
                List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", 100, "taxId", vat)))).get("id")
                .asString();
        ok(200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        assertThat(residual(bill)).as("bill incl. 18% VAT").isEqualByComparingTo("118");
        return bill;
    }

    @Test
    @DisplayName("price claim against a taxed service bill: Dr AP 47.20 (partner) / Cr expense 40 / Cr input VAT 7.20;"
            + " bill residual 118 → 70.80; AP sub-ledger reconciles; replay posts once")
    void priceClaimAgainstBill() {
        String supplier = demoPartner("R11 haulier", true);
        String bill = postedServiceBill(supplier);
        String id = claim(supplier, "price", 40, Map.of("vendorBillId", bill));

        JsonNode settled = settle(id);
        assertThat(settled.get("status").asString()).isEqualTo("settled");
        assertThat(settled.path("debitNoteId").asString(null)).as("no outbound debit note any more").isNull();
        String entryId = settled.get("journalEntryId").asString();
        assertThat(settled.get("journalEntryNumber").asString()).startsWith("PURCH/");

        Map<String, BigDecimal> net = netByCode(entryId);
        assertThat(apDebit(entryId, supplier)).as("AP debited, tagged with the supplier").isEqualByComparingTo("47.2");
        assertThat(net.get(mappedCode(demoOrg, "default_expense"))).as("expense the bill debited")
                .isEqualByComparingTo("-40");
        assertThat(net.get(mappedCode(demoOrg, "tax_receivable"))).as("input VAT reversed")
                .isEqualByComparingTo("-7.2");
        assertThat(net).as("no revenue / AR legs").doesNotContainKeys(mappedCode(demoOrg, "sales_revenue"),
                mappedCode(demoOrg, "accounts_receivable"));
        assertThat(residual(bill)).isEqualByComparingTo("70.8");
        assertThat(paymentStatus(bill)).isEqualTo("partial");
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, List.of(entryId));
        LedgerInvariants.partnerControlReconciles(jdbc, demoOrg, supplier, false);

        JsonNode replay = settle(id);
        assertThat(replay.get("journalEntryId").asString()).isEqualTo(entryId);
        LedgerInvariants.postingComplete(jdbc, demoOrg, "supplier_claim", id, 1, false);
        assertThat(residual(bill)).as("replay does not lower the bill twice").isEqualByComparingTo("70.8");
        assertThat(jdbc.sql("SELECT count(*) FROM \"DebitNote\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?")
                .params(demoOrg, supplier).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a claim larger than the bill's open amount settles the bill and leaves a supplier credit on AP")
    void claimAboveResidual() {
        String supplier = demoPartner("R11 excess", true);
        String bill = postedServiceBill(supplier);
        // Gap fix h03-F7: claims are capped at the bill's value (100 net), so the open amount is lowered by a payment
        // (118 → 68) rather than by a first claim.
        String payment = demoMoney("POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today(),
                "amount", 50, "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", bill, "amount",
                        50)))).get("id").asString();
        assertThat(residual(bill)).isEqualByComparingTo("68");
        String second = claim(supplier, "damage", 100, Map.of("vendorBillId", bill));
        String entryId = settle(second).get("journalEntryId").asString();

        assertThat(apDebit(entryId, supplier)).isEqualByComparingTo("118");
        assertThat(residual(bill)).as("never below zero").isEqualByComparingTo("0");
        assertThat(paymentStatus(bill)).isEqualTo("paid");
        BigDecimal apNet = netForSources(demoOrg, mapped(demoOrg, "accounts_payable"), List.of(bill, payment, second));
        assertThat(apNet).as("bill 118 Cr − payment 50 Dr − credit 118 Dr = 50 supplier credit (debit balance)")
                .isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("quality claim on a receipt of stock goods, no quantities: price variance by default — Dr AP / Cr PPV +"
            + " voucher VAT; stock quantity, stock account and GRNI untouched")
    void qualityClaimOnReceipt() {
        String supplier = demoPartner("R11 cement", true);
        String product = demoProduct("R11 cement bag", true, 0, 0);
        String warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "R11-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "R11 store", "type", "warehouse")).get("id").asString();
        String poId = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "paymentType", "credit", "currencyCode", baseCurrency(demoOrg), "lines", List.of(Map.of(
                        "productId", product, "description", "Cement", "quantity", 10, "unitPrice", 100, "taxRate",
                        18)))).get("id").asString();
        String grnId = demoMoney("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(Map.of("productId", product, "description", "Cement", "quantity", 10))))
                .get("grn").get("id").asString();
        String id = claim(supplier, "quality", 200, Map.of("purchaseOrderId", poId, "goodsReceiptId", grnId));

        JsonNode settled = settle(id);
        assertThat(settled.get("classification").asString()).isEqualTo("price_variance");
        String entryId = settled.get("journalEntryId").asString();
        Map<String, BigDecimal> net = netByCode(entryId);
        assertThat(apDebit(entryId, supplier)).isEqualByComparingTo("236");
        assertThat(net.get(mappedCode(demoOrg, "purchase_price_variance"))).as("no quantities: a price concession")
                .isEqualByComparingTo("-200");
        assertThat(net).as("no write-off without verified quantities").doesNotContainKey(mappedCode(demoOrg,
                "stock_adjustment_expense"));
        assertThat(onHand(product)).as("stock quantity unchanged").isEqualByComparingTo("10");
        assertThat(net.get(mappedCode(demoOrg, "tax_receivable"))).as("voucher VAT at 18 % reversed")
                .isEqualByComparingTo("-36");
        assertThat(net).as("stock and GRNI are not credited").doesNotContainKeys(mappedCode(demoOrg,
                "stock_valuation"), mappedCode(demoOrg, "grni_accrued"));
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, List.of(entryId));
    }

    @Test
    @DisplayName("a claim against a draft bill is refused until the bill is posted; nothing is posted")
    void draftBillRefused() {
        String supplier = demoPartner("R11 draft", true);
        String bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today(), "lines",
                List.of(Map.of("description", "Haulage", "quantity", 1, "unitPrice", 100)))).get("id").asString();
        String id = claim(supplier, "price", 10, Map.of("vendorBillId", bill));
        Res r = call("POST", "/procurement/claims/" + id + "/settle", null, demoKey());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("before settling a claim");
        assertThat(ok(200, "GET", "/procurement/claims/" + id, null).get("status").asString()).isEqualTo("accepted");
        LedgerInvariants.postingComplete(jdbc, demoOrg, "supplier_claim", id, 0, false);
    }

    @Test
    @DisplayName("stock adjustment needs verified quantities: refused without lines, over the received quantity, before"
            + " verification and when the creator verifies; nothing posts, stock unchanged")
    void stockAdjustmentGuards() {
        String supplier = demoPartner("CLQ guard", true);
        String product = demoProduct("CLQ guard bag", true, 0, 0);
        Map<String, String> po = new LinkedHashMap<>();
        String grnId = receivedPo(supplier, product, warehouse(), po);
        String id = claim(supplier, "shortage", 200, Map.of("purchaseOrderId", po.get("poId"), "goodsReceiptId", grnId));

        Res none = trySettle(id, "stock_adjustment");
        assertThat(none.status()).as(none.raw().body()).isEqualTo(400);
        assertThat(none.body().get("message").asString()).contains("needs quantity lines");

        String line = receiptLine(id);
        Res over = as("POST", "/procurement/claims/" + id + "/quantity-lines", Map.of("goodsReceiptLineId", line,
                "quantity", 11));
        assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).contains("still unclaimed");

        JsonNode withLine = ok(200, "POST", "/procurement/claims/" + id + "/quantity-lines", Map.of(
                "goodsReceiptLineId", line, "quantity", 2));
        String lineId = withLine.get("quantityLines").get(0).get("id").asString();
        assertThat(withLine.get("quantityLines").get(0).get("verified").asBoolean()).isFalse();
        assertThat(withLine.get("quantityLines").get(0).get("unitCost").asString()).as("receipt cost").isEqualTo("100");

        Res unverified = trySettle(id, null);
        assertThat(unverified.status()).as("lines present → stock adjustment by default; unverified → refused")
                .isEqualTo(400);
        assertThat(unverified.body().get("message").asString()).contains("not verified");

        Res self = as("POST", "/procurement/claims/" + id + "/quantity-lines/" + lineId + "/verify", null);
        assertThat(self.status()).as("the claim's creator cannot verify").isEqualTo(403);

        Res priceAsStock = trySettle(claim(supplier, "price", 10, Map.of("goodsReceiptId", grnId)), "stock_adjustment");
        assertThat(priceAsStock.status()).isEqualTo(400);
        assertThat(priceAsStock.body().get("message").asString()).contains("not a price claim");

        LedgerInvariants.postingComplete(jdbc, demoOrg, "supplier_claim", id, 0, false);
        assertThat(onHand(product)).isEqualByComparingTo("10");
        assertThat(ok(200, "GET", "/procurement/claims/" + id, null).get("status").asString()).isEqualTo("accepted");
    }

    @Test
    @DisplayName("stock adjustment on a vouchered PO receipt: verified 2 × 100 leave stock through the engine's"
            + " return-to-supplier issue — Dr AP 236 / Cr stock 200 / Cr VAT 36; no GRNI; stock 10 → 8; replay once")
    void stockAdjustmentLeavesStock() {
        String supplier = demoPartner("CLQ shortage", true);
        String product = demoProduct("CLQ shortage bag", true, 0, 0);
        Map<String, String> po = new LinkedHashMap<>();
        String grnId = receivedPo(supplier, product, warehouse(), po);
        String id = claim(supplier, "shortage", 200, Map.of("purchaseOrderId", po.get("poId"), "goodsReceiptId", grnId));
        String lineId = verifiedLine(id, receiptLine(id), 2, "issue");

        JsonNode settled = settle(id);
        assertThat(settled.get("classification").asString()).isEqualTo("stock_adjustment");
        assertThat(settled.get("settledAmount").asString()).isEqualTo("200");
        JsonNode line = settled.get("quantityLines").get(0);
        assertThat(line.get("verifiedBy").asString()).isEqualTo(checkerId);
        assertThat(line.get("issuedValue").asString()).isEqualTo("200");
        String entryId = settled.get("journalEntryId").asString();
        Map<String, BigDecimal> net = netByCode(entryId);
        assertThat(apDebit(entryId, supplier)).isEqualByComparingTo("236");
        assertThat(net.get(mappedCode(demoOrg, "stock_valuation"))).as("stock leaves at the engine value")
                .isEqualByComparingTo("-200");
        assertThat(net.get(mappedCode(demoOrg, "tax_receivable"))).isEqualByComparingTo("-36");
        assertThat(net).as("vouchered receipt: AP, not GRNI").doesNotContainKey(mappedCode(demoOrg, "grni_accrued"));
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, List.of(entryId));
        assertThat(onHand(product)).isEqualByComparingTo("8");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'supplier_claim' AND \"referenceId\" = ? AND type::text = 'return_to_supplier'"
                + " AND \"sourceLineId\" = ?").params(demoOrg, id, lineId).query(Long.class).single()).isEqualTo(1L);

        JsonNode replay = settle(id);
        assertThat(replay.get("journalEntryId").asString()).isEqualTo(entryId);
        LedgerInvariants.postingComplete(jdbc, demoOrg, "supplier_claim", id, 1, false);
        assertThat(onHand(product)).as("replay does not issue twice").isEqualByComparingTo("8");

        // Gap fix h03-F7: within what the receipt (1000) leaves open after the settled 200.
        JsonNode lines = ok(200, "GET", "/procurement/claims/" + claim(supplier, "damage", 100, Map.of(
                "goodsReceiptId", grnId)) + "/receipt-lines", null);
        assertThat(lines.get(0).get("claimable").asString()).as("10 received − 2 claimed").isEqualTo("8");
    }

    @Test
    @DisplayName("stock adjustment agreed above the verified goods: 2 × 100 leave stock, the other 50 agreed is a price"
            + " concession — Dr AP 295 / Cr stock 200 / Cr PPV 50 / Cr VAT 45; settled for the full 250")
    void stockAdjustmentCreditsAgreedRemainder() {
        String supplier = demoPartner("CLQ remainder", true);
        String product = demoProduct("CLQ remainder bag", true, 0, 0);
        Map<String, String> po = new LinkedHashMap<>();
        String grnId = receivedPo(supplier, product, warehouse(), po);
        String id = claim(supplier, "shortage", 250, Map.of("purchaseOrderId", po.get("poId"), "goodsReceiptId", grnId));
        verifiedLine(id, receiptLine(id), 2, "issue");

        JsonNode settled = settleAs(id, "stock_adjustment");
        assertThat(settled.get("settledAmount").asString()).isEqualTo("250");
        String entryId = settled.get("journalEntryId").asString();
        Map<String, BigDecimal> net = netByCode(entryId);
        assertThat(apDebit(entryId, supplier)).isEqualByComparingTo("295");
        assertThat(net.get(mappedCode(demoOrg, "stock_valuation"))).isEqualByComparingTo("-200");
        assertThat(net.get(mappedCode(demoOrg, "purchase_price_variance"))).isEqualByComparingTo("-50");
        assertThat(net.get(mappedCode(demoOrg, "tax_receivable"))).isEqualByComparingTo("-45");
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, List.of(entryId));
        assertThat(onHand(product)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("stock adjustment on an ad-hoc receipt not yet billed: Dr GRNI 150 / Cr stock 150, no AP; the receipt"
            + " line's open quantity drops by 3 so a later bill cannot clear it again")
    void stockAdjustmentAgainstOpenGrni() {
        String supplier = demoPartner("CLQ blocks", true);
        String product = demoProduct("CLQ block", true, 0, 0);
        String grnId = ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier,
                "warehouseId", warehouse(), "receivedAt", Instant.now().toString(), "lines", List.of(Map.of(
                        "productId", product, "description", "Blocks", "quantity", 10, "unitCost", 50))))
                .get("id").asString();
        String id = claim(supplier, "damage", 150, Map.of("goodsReceiptId", grnId));
        String receiptLineId = receiptLine(id);
        verifiedLine(id, receiptLineId, 3, "issue");

        JsonNode settled = settleAs(id, "stock_adjustment");
        String entryId = settled.get("journalEntryId").asString();
        Map<String, BigDecimal> net = netByCode(entryId);
        assertThat(net.get(mappedCode(demoOrg, "grni_accrued"))).as("GRNI relieved for exactly 3 × 50")
                .isEqualByComparingTo("150");
        assertThat(net.get(mappedCode(demoOrg, "stock_valuation"))).isEqualByComparingTo("-150");
        assertThat(apDebit(entryId, supplier)).as("nothing billed: no payable to reduce").isEqualByComparingTo("0");
        assertThat(settled.get("quantityLines").get(0).get("grniQuantity").asString()).isEqualTo("3");
        assertThat(jdbc.sql("SELECT \"billedQuantity\" FROM \"GoodsReceiptLine\" WHERE id = ?").param(receiptLineId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("3");
        assertThat(onHand(product)).isEqualByComparingTo("7");
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, List.of(entryId));
    }

    @Test
    @DisplayName("verified quantity already written off: Cr stock adjustment expense 100 + VAT 18 / Dr AP 118; no stock"
            + " move")
    void stockAdjustmentWrittenOff() {
        String supplier = demoPartner("CLQ wet", true);
        String product = demoProduct("CLQ wet bag", true, 0, 0);
        Map<String, String> po = new LinkedHashMap<>();
        String grnId = receivedPo(supplier, product, warehouse(), po);
        String id = claim(supplier, "damage", 100, Map.of("goodsReceiptId", grnId));
        verifiedLine(id, receiptLine(id), 1, "written_off");

        String entryId = settle(id).get("journalEntryId").asString();
        Map<String, BigDecimal> net = netByCode(entryId);
        assertThat(apDebit(entryId, supplier)).isEqualByComparingTo("118");
        assertThat(net.get(mappedCode(demoOrg, "stock_adjustment_expense"))).isEqualByComparingTo("-100");
        assertThat(net).doesNotContainKeys(mappedCode(demoOrg, "stock_valuation"), mappedCode(demoOrg,
                "grni_accrued"));
        assertThat(onHand(product)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("VAT adjustment: Dr AP 18 / Cr input VAT 18 only, bill residual 118 → 100; a further VAT claim above"
            + " the bill's remaining VAT is refused; not for a damage claim")
    void vatAdjustment() {
        String supplier = demoPartner("CLQ vat", true);
        String bill = postedServiceBill(supplier);
        String id = claim(supplier, "price", 18, Map.of("vendorBillId", bill));
        JsonNode settled = settleAs(id, "vat_adjustment");
        assertThat(settled.get("settledTax").asString()).isEqualTo("18");
        String entryId = settled.get("journalEntryId").asString();
        Map<String, BigDecimal> net = netByCode(entryId);
        assertThat(apDebit(entryId, supplier)).isEqualByComparingTo("18");
        assertThat(net.get(mappedCode(demoOrg, "tax_receivable"))).isEqualByComparingTo("-18");
        assertThat(net).as("only VAT").doesNotContainKey(mappedCode(demoOrg, "default_expense"));
        assertThat(residual(bill)).isEqualByComparingTo("100");

        Res more = trySettle(claim(supplier, "price", 1, Map.of("vendorBillId", bill)), "vat_adjustment");
        assertThat(more.status()).isEqualTo(400);
        assertThat(more.body().get("message").asString()).contains("exceeds the input VAT left");
        Res damage = trySettle(claim(supplier, "damage", 1, Map.of("vendorBillId", bill)), "vat_adjustment");
        assertThat(damage.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("expense correction is refused on a document of stock lines only; a claim posting that would hit the"
            + " stock or GRNI account without verified quantities is refused")
    void noStockOrGrniWithoutQuantities() {
        String supplier = demoPartner("CLQ expense", true);
        String product = demoProduct("CLQ expense bag", true, 0, 0);
        Map<String, String> po = new LinkedHashMap<>();
        String grnId = receivedPo(supplier, product, warehouse(), po);
        String onStock = claim(supplier, "quality", 50, Map.of("goodsReceiptId", grnId));
        Res refused = trySettle(onStock, "expense_correction");
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("only stock lines");

        // A non-stock bill line booked (wrongly) to the stock account: the claim must not credit inventory.
        String bill = postedServiceBill(supplier);
        jdbc.sql("UPDATE \"DocumentLine\" SET \"accountId\" = ? WHERE \"documentId\" = ?")
                .params(mapped(demoOrg, "stock_valuation"), bill).update();
        String id = claim(supplier, "other", 50, Map.of("vendorBillId", bill));
        Res guarded = trySettle(id, "expense_correction");
        assertThat(guarded.status()).as(guarded.raw().body()).isEqualTo(400);
        assertThat(guarded.body().get("message").asString()).contains("cannot post to inventory or GRNI");
        LedgerInvariants.postingComplete(jdbc, demoOrg, "supplier_claim", id, 0, false);
        assertThat(residual(bill)).isEqualByComparingTo("118");
        assertThat(onHand(product)).isEqualByComparingTo("10");
    }
}
