package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C1b (D-VAT-1): with {@code depositTaxPoint = receipt} a down payment is a tax invoice, and the final invoice
 * deducts it with its VAT instead of applying the deposit (which charged the advance's VAT twice and left the customer
 * owing it). The scenarios are the journal examples J1–J4 of ADR-S094 §3.3: 10 × 100.00, VAT 18 % — net 1,000.00, VAT
 * 180.00, gross 1,180.00. Every test has its own customer and its own VAT account, so balances are read per partner and
 * per account; invoices stay unpaid, so the customer's receivable must end at exactly the order's gross.
 */
class SalesDownPaymentVatIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    AccountResolverService accounts;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    final String today = LocalDate.now(ZoneId.of("Africa/Kampala")).toString();
    SalesLedgerAssertions ledger;
    String service;
    String stone;
    String yard;
    String adminId;
    String depositAccount;
    String vatAccount;
    String vat;

    @BeforeAll
    void fixtures() {
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
        depositAccount = ledger.mapped("customer_deposit");
        service = product("DPV svc " + sfx, false, "ordered");
        stone = product("DPV stone " + sfx, true, "delivered");
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "DPV-" + sfx, "name", "DPV yard " + sfx, "type",
                "warehouse")).get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> stock.receive(StockMove.of(stone, yard, new BigDecimal("100")).unitCost(
                        new BigDecimal("60")))));
    }

    @BeforeEach
    void freshVat() {
        String[] id = new String[1];
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> id[0] = accounts.ensureByCode("229" + ThreadLocalRandom.current().nextInt(
                        10000, 99999), "Output VAT DPV " + sfx, "current_liability", null)));
        vatAccount = id[0];
        vat = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"accountId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 18, ?, now())").params(vat, demoOrg, "VAT 18 DPV " + UUID.randomUUID().toString()
                        .substring(0, 8), "DV" + UUID.randomUUID().toString().substring(0, 6), vatAccount).update();
    }

    @AfterEach
    void reset() {
        ok(200, "PUT", "/sales/settings", Map.of("depositTaxPoint", "delivery"));
    }

    String product(String name, boolean track, String policy) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " ?::\"ProductType\", 100, 60, ?, ?, now())").params(id, demoOrg, "DPV-" + UUID.randomUUID().toString()
                        .substring(0, 8), name, track ? "stockable" : "service", track, policy).update();
        return id;
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "DPVC-" + UUID.randomUUID().toString()
                        .substring(0, 8), "DPV cust " + sfx).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    Res refused(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(400);
        return r;
    }

    void receiptMode() {
        ok(200, "PUT", "/sales/settings", Map.of("depositTaxPoint", "receipt"));
    }

    /** A confirmed order of {@code qty} × 100.00 with 18 % VAT. */
    JsonNode order(String c, String product, int qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", c);
        if (product.equals(stone)) {
            body.put("locationId", yard);
        }
        body.put("lines", List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 100, "taxId", vat)));
        JsonNode so = ok(201, "POST", "/sales/orders", body);
        ok(200, "POST", "/sales/orders/" + so.get("id").asString() + "/confirm", null);
        return so;
    }

    /** A posted down-payment invoice of {@code amount} (gross). */
    String downPayment(String so, int amount) {
        String id = money("/sales/orders/" + so + "/invoices", Map.of("mode", "down_payment", "amount", amount)).get("id")
                .asString();
        money("/invoices/" + id + "/post", null);
        return id;
    }

    JsonNode regular(String so) {
        return money("/sales/orders/" + so + "/invoices", Map.of("mode", "regular"));
    }

    JsonNode post(String invoiceId) {
        return money("/invoices/" + invoiceId + "/post", null);
    }

    void deliver(JsonNode so, int qty) {
        String line = so.get("lines").get(0).get("id").asString();
        JsonNode d = ok(201, "POST", "/sales/orders/" + so.get("id").asString() + "/deliveries", Map.of("vehiclePlate",
                "UDP 1", "driverName", "Okello", "lines", List.of(Map.of("lineId", line, "quantity", qty))));
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Site agent", "lines", List.of(Map.of(
                "loadLineId", d.get("lines").get(0).get("id").asString(), "deliveredQty", qty))));
    }

    Map<String, Object> deposit(String downPaymentId) {
        return jdbc.sql("SELECT id, amount, \"appliedAmount\", \"refundedAmount\", status FROM \"CustomerDeposit\" WHERE"
                + " \"documentId\" = ?").param(downPaymentId).query().singleRow();
    }

    BigDecimal vatBalance() {
        return jdbc.sql("SELECT COALESCE(sum(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                + " AND l.\"accountId\" = ?").params(demoOrg, vatAccount).query(BigDecimal.class).single();
    }

    JsonNode deductionLine(JsonNode invoice) {
        for (JsonNode l : invoice.get("lines")) {
            if (l.get("quantity").asDouble() < 0) {
                return l;
            }
        }
        return null;
    }

    /** V1–V3 of ADR-S094 §3.4 for one customer: VAT once, deposit cleared, receivable = what the customer owes. */
    void balanced(String c, String expectedVat, String expectedReceivable) {
        assertThat(vatBalance()).as("output VAT (credit) — charged once").isEqualByComparingTo(expectedVat);
        assertThat(ledger.balance("customer_deposit", c)).as("customer deposits of the customer").isEqualByComparingTo(
                "0");
        assertThat(ledger.balance("accounts_receivable", c)).as("receivable").isEqualByComparingTo(expectedReceivable);
        ledger.receivableTiesOut(c);
        ledger.trialBalanceZero();
    }

    // ------------------------------------------------------------------ J1 / J1-D: 100 % advance

    @Test
    @DisplayName("J1 (receipt): 100 % taxed down payment — the final invoice deducts it with its VAT and totals zero")
    void fullAdvanceReceipt() {
        receiptMode();
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        String dp = downPayment(so, 1180);
        assertThat(ok(200, "GET", "/invoices/" + dp, null).get("taxAmount").asDouble()).isEqualTo(180);
        Map<String, Object> d = deposit(dp);
        assertThat((BigDecimal) d.get("amount")).as("deposit = the net advance, registered at post").isEqualByComparingTo(
                "1000");

        JsonNode inv = regular(so);
        assertThat(inv.get("totalAmount").asDouble()).as("AD-17: zero-total final invoice").isZero();
        assertThat(inv.get("taxAmount").asDouble()).isZero();
        JsonNode ded = deductionLine(inv);
        assertThat(ded).isNotNull();
        assertThat(ded.get("subtotal").asDouble()).isEqualTo(-1000);
        assertThat(ded.get("taxAmount").asDouble()).isEqualTo(-180);

        JsonNode posted = post(inv.get("id").asString());
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(posted.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(posted.get("amountResidual").asDouble()).isZero();
        // ADR-S094 C4b (AD-17): the zero-total final invoice is a tax invoice, registered like the down payment
        for (String registered : List.of(dp, inv.get("id").asString())) {
            assertThat(jdbc.sql("SELECT status FROM \"FiscalDocument\" WHERE \"invoiceId\" = ? AND operation = 'issue'"
                    + " AND \"ownerKind\" = 'document'").param(registered).query(String.class).single())
                    .as("fiscal registration of " + registered).isEqualTo("succeeded");
        }
        Map<String, Object> after = deposit(dp);
        assertThat((BigDecimal) after.get("appliedAmount")).isEqualByComparingTo("1000");
        assertThat(after.get("status")).isEqualTo("closed");
        assertThat(jdbc.sql("SELECT count(*) FROM \"DepositApplication\" WHERE \"depositId\" = ? AND kind = 'deduction'"
                + " AND \"documentId\" = ? AND \"paymentId\" IS NULL").params(after.get("id"), inv.get("id").asString())
                .query(Long.class).single()).isEqualTo(1);
        // before C1b: VAT 360 and the customer owing 180 more than the order
        balanced(c, "-180", "1180");
        ledger.entriesBalanced("invoice", inv.get("id").asString());
    }

    @Test
    @DisplayName("J1-D (delivery): an untaxed 100 % advance is applied to the full final invoice as before")
    void fullAdvanceDelivery() {
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        String dp = downPayment(so, 1180);
        assertThat(ok(200, "GET", "/invoices/" + dp, null).get("taxAmount").asDouble()).isZero();
        assertThat((BigDecimal) deposit(dp).get("amount")).isEqualByComparingTo("1180");
        JsonNode inv = regular(so);
        assertThat(deductionLine(inv)).as("no deduction for an untaxed advance").isNull();
        assertThat(inv.get("totalAmount").asDouble()).isEqualTo(1180);
        post(inv.get("id").asString());
        money("/customer-deposits/" + deposit(dp).get("id") + "/apply", Map.of("documentId", inv.get("id").asString()));
        assertThat(deposit(dp).get("status")).isEqualTo("closed");
        balanced(c, "-180", "1180");
    }

    // ------------------------------------------------------------------ J2: 30 % advance

    @Test
    @DisplayName("J2 (receipt): 30 % advance — final invoice 826.00; VAT 54 + 180 − 54 = 180")
    void partialAdvanceReceipt() {
        receiptMode();
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        String dp = downPayment(so, 354);
        JsonNode inv = regular(so);
        assertThat(inv.get("totalAmount").asDouble()).isEqualTo(826);
        assertThat(deductionLine(inv).get("subtotal").asDouble()).isEqualTo(-300);
        assertThat(deductionLine(inv).get("taxAmount").asDouble()).isEqualTo(-54);
        post(inv.get("id").asString());
        assertThat(deposit(dp).get("status")).isEqualTo("closed");
        balanced(c, "-180", "1180");
    }

    // ------------------------------------------------------------------ J3: partial deliveries, pro-rata

    @Test
    @DisplayName("J3 (receipt): 50 % advance, 4 + 6 delivered — pro-rata deductions 200 + 36 then the rest 300 + 54")
    void partialDeliveriesProRata() {
        receiptMode();
        String c = customer();
        JsonNode so = order(c, stone, 10);
        String id = so.get("id").asString();
        String dp = downPayment(id, 590);

        deliver(so, 4);
        JsonNode first = regular(id);
        assertThat(first.get("totalAmount").asDouble()).as("472 − 236").isEqualTo(236);
        assertThat(deductionLine(first).get("subtotal").asDouble()).isEqualTo(-200);
        assertThat(deductionLine(first).get("taxAmount").asDouble()).isEqualTo(-36);
        post(first.get("id").asString());
        assertThat((BigDecimal) deposit(dp).get("appliedAmount")).isEqualByComparingTo("200");
        assertThat(deposit(dp).get("status")).isEqualTo("open");

        deliver(so, 6);
        JsonNode second = regular(id);
        assertThat(second.get("totalAmount").asDouble()).as("708 − 354").isEqualTo(354);
        assertThat(deductionLine(second).get("subtotal").asDouble()).isEqualTo(-300);
        assertThat(deductionLine(second).get("taxAmount").asDouble()).isEqualTo(-54);
        post(second.get("id").asString());
        assertThat((BigDecimal) deposit(dp).get("appliedAmount")).isEqualByComparingTo("500");
        assertThat(deposit(dp).get("status")).isEqualTo("closed");
        balanced(c, "-180", "1180");
    }

    // ------------------------------------------------------------------ J4: credit notes after an advance

    Map<String, Object> creditNote(String c, String dp, int net) {
        return Map.of("partnerId", c, "issueDate", today, "reversedDocumentId", dp, "lines", List.of(Map.of("accountId",
                depositAccount, "description", "Advance credited", "quantity", 1, "unitPrice", net, "taxId", vat)));
    }

    @Test
    @DisplayName("J4-A (receipt): order cancelled — a credit note on the down-payment invoice reverses deposit and VAT;"
            + " the taxed deposit is never applied or refunded directly")
    void cancelledAdvance() {
        receiptMode();
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        String dp = downPayment(so, 354);
        String depositId = (String) deposit(dp).get("id");
        assertThat(refused("/customer-deposits/" + depositId + "/refund", Map.of("paymentMethod", "cash")).raw().body())
                .contains("credit note on its down-payment invoice");
        String other = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "lines", List.of(Map.of("productId",
                service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + other + "/confirm", null);
        String plain = regular(other).get("id").asString();
        post(plain);
        assertThat(refused("/customer-deposits/" + depositId + "/apply", Map.of("documentId", plain)).raw().body())
                .contains("deducted on the final invoice");

        // only the deposit account may be credited on a down-payment invoice
        String wrong = money("/credit-notes", Map.of("partnerId", c, "issueDate", today, "reversedDocumentId", dp,
                "lines", List.of(Map.of("accountId", vatAccount, "description", "Wrong account", "quantity", 1,
                        "unitPrice", 10)))).get("id").asString();
        assertThat(refused("/credit-notes/" + wrong + "/post", null).raw().body()).contains("deposit account only");
        String untaxed = money("/credit-notes", Map.of("partnerId", c, "issueDate", today, "reversedDocumentId", dp,
                "lines", List.of(Map.of("accountId", depositAccount, "description", "No VAT", "quantity", 1,
                        "unitPrice", 10)))).get("id").asString();
        assertThat(refused("/credit-notes/" + untaxed + "/post", null).raw().body()).contains("the tax of the down payment");

        String cn = money("/credit-notes", creditNote(c, dp, 300)).get("id").asString();
        money("/credit-notes/" + cn + "/post", null);
        Map<String, Object> d = deposit(dp);
        assertThat((BigDecimal) d.get("appliedAmount")).isEqualByComparingTo("300");
        assertThat(d.get("status")).isEqualTo("closed");
        assertThat(vatBalance()).as("VAT on the advance reversed").isEqualByComparingTo("0");
        assertThat(ledger.balance("customer_deposit", c)).isEqualByComparingTo("0");
        assertThat(ledger.balance("accounts_receivable", c)).as("only the unrelated 100 invoice is owed")
                .isEqualByComparingTo("100");
        ledger.receivableTiesOut(c);

        // nothing is left to credit (the invoice-total cap answers first; J4-D shows the deposit check)
        String again = money("/credit-notes", creditNote(c, dp, 100)).get("id").asString();
        assertThat(refused("/credit-notes/" + again + "/post", null).raw().body()).contains("more than the invoice total");
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("J4-D (receipt): advance reduced 354 → 236 by a credit note; the final invoice deducts the 200 + 36 left")
    void reducedAdvance() {
        receiptMode();
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        String dp = downPayment(so, 354);
        String cn = money("/credit-notes", creditNote(c, dp, 100)).get("id").asString();
        money("/credit-notes/" + cn + "/post", null);
        assertThat((BigDecimal) deposit(dp).get("appliedAmount")).isEqualByComparingTo("100");

        JsonNode inv = regular(so);
        assertThat(inv.get("totalAmount").asDouble()).as("1,180 − 236").isEqualTo(944);
        assertThat(deductionLine(inv).get("subtotal").asDouble()).isEqualTo(-200);
        assertThat(deductionLine(inv).get("taxAmount").asDouble()).isEqualTo(-36);
        post(inv.get("id").asString());
        assertThat(deposit(dp).get("status")).isEqualTo("closed");
        // VAT 54 − 18 + 180 − 36 = 180; owed: 354 − 118 + 944 = 1,180
        balanced(c, "-180", "1180");

        // the deposit is consumed: a further credit note on the down payment is refused
        String late = money("/credit-notes", creditNote(c, dp, 50)).get("id").asString();
        assertThat(refused("/credit-notes/" + late + "/post", null).raw().body()).contains("of its deposit left");
    }

    @Test
    @DisplayName("AD-14 (receipt): advance reduced to 400 net, then 4 + 6 delivered — 400 × 400 / 1,000 = 160 (+ 29 VAT,"
            + " whole shillings), then the 240 (+ 43) left")
    void reducedAdvanceProRata() {
        receiptMode();
        String c = customer();
        JsonNode so = order(c, stone, 10);
        String id = so.get("id").asString();
        String dp = downPayment(id, 590);
        String cn = money("/credit-notes", creditNote(c, dp, 100)).get("id").asString();
        money("/credit-notes/" + cn + "/post", null);

        deliver(so, 4);
        JsonNode first = regular(id);
        assertThat(deductionLine(first).get("subtotal").asDouble()).isEqualTo(-160);
        // UGX has no decimals: the deduction's VAT rounds like any line's (28.80 → 29)
        assertThat(deductionLine(first).get("taxAmount").asDouble()).isEqualTo(-29);
        assertThat(first.get("totalAmount").asDouble()).as("472 − 189").isEqualTo(283);
        post(first.get("id").asString());

        deliver(so, 6);
        JsonNode second = regular(id);
        assertThat(deductionLine(second).get("subtotal").asDouble()).isEqualTo(-240);
        assertThat(deductionLine(second).get("taxAmount").asDouble()).isEqualTo(-43);
        assertThat(second.get("totalAmount").asDouble()).as("708 − 283").isEqualTo(425);
        post(second.get("id").asString());
        assertThat(deposit(dp).get("status")).isEqualTo("closed");
        // VAT 90 − 18 + 72 − 29 + 108 − 43 = 180; owed 472 + 283 + 425 = 1,180
        balanced(c, "-180", "1180");
    }

    // ------------------------------------------------------------------ drafts, cancellation, guards

    @Test
    @DisplayName("C1b: a discarded draft releases its deduction; cancelling a posted final invoice re-opens the deposit")
    void draftAndCancelRelease() {
        receiptMode();
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        String dp = downPayment(so, 354);
        String draft = regular(so).get("id").asString();
        money("/invoices/" + draft + "/cancel", Map.of("reason", "redo"));

        JsonNode inv = regular(so);
        assertThat(inv.get("totalAmount").asDouble()).as("the discarded draft deducted nothing").isEqualTo(826);
        post(inv.get("id").asString());
        assertThat(deposit(dp).get("status")).isEqualTo("closed");

        money("/invoices/" + inv.get("id").asString() + "/cancel", Map.of("reason", "wrong customer reference"));
        Map<String, Object> d = deposit(dp);
        assertThat(d.get("status")).isEqualTo("open");
        assertThat((BigDecimal) d.get("appliedAmount")).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"DepositApplication\" WHERE \"depositId\" = ? AND kind = 'deduction'"
                + " AND \"reversedAt\" IS NOT NULL").param(d.get("id")).query(Long.class).single()).isEqualTo(1);

        JsonNode again = regular(so);
        assertThat(again.get("totalAmount").asDouble()).isEqualTo(826);
        post(again.get("id").asString());
        balanced(c, "-180", "1180");
    }

    double commission(String salesperson) {
        for (JsonNode n : ok(200, "GET", "/sales/commissions/preview?salespersonId=" + salesperson, null)
                .get("salespeople")) {
            if (salesperson.equals(n.get("salespersonId").asString())) {
                return n.get("total").asDouble();
            }
        }
        return 0;
    }

    @Test
    @DisplayName("C1b: commission on the paid basis counts a final invoice that its down payment covered in full")
    void zeroTotalInvoiceCommission() {
        receiptMode();
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).single();
        String sp = createUser(demoOrg, "dpv-" + UUID.randomUUID() + "@demo.test", "Sales@12345", role);
        ok(201, "POST", "/sales/commissions/rules", Map.of("name", "DPV " + UUID.randomUUID(), "salespersonId", sp,
                "basis", "paid", "method", "percent_revenue", "rate", 10, "priority", 10));
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        jdbc.sql("UPDATE \"SalesOrder\" SET \"ownerId\" = ? WHERE id = ?").params(sp, so).update();
        downPayment(so, 1180);
        String inv = regular(so).get("id").asString();
        assertThat(commission(sp)).as("nothing paid yet: the down payment has no product line").isZero();
        post(inv);
        assertThat(commission(sp)).as("10 % of the 1,000 of goods, paid by the down payment").isEqualTo(100);
    }

    @Test
    @DisplayName("C1b: a regular invoice is refused while a taxed down-payment invoice of the order is still a draft")
    void draftDownPaymentBlocks() {
        receiptMode();
        String c = customer();
        String so = order(c, service, 10).get("id").asString();
        String draftDp = money("/sales/orders/" + so + "/invoices", Map.of("mode", "down_payment", "amount", 354))
                .get("id").asString();
        assertThat(refused("/sales/orders/" + so + "/invoices", Map.of("mode", "regular")).raw().body())
                .contains("still a draft");
        money("/invoices/" + draftDp + "/cancel", Map.of("reason", "not needed"));
        assertThat(regular(so).get("totalAmount").asDouble()).isEqualTo(1180);
    }
}
