package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.sales.application.FiscalAdapters;
import com.inventoryerp.sales.web.CashSaleController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Branch cash sales → head-office invoicing (ADR-S056, docs/spec/cash-sales.md §10): showroom confirm posts stock
 * (P1), verified payments as cash-sale deposits (P2) and the revenue / VAT accrual (P3) exactly once; head office
 * return / reject / approve never post; the invoice reclassifies the accrual to AR and applies the linked deposits
 * (P4, P5); invoice cancel (P7), replacement invoice and void (P6); fiscal lifecycle; controls and scope.
 */
class CashSaleIT extends ApiTestSupport {

    @Autowired
    AccountResolverService accounts;

    @Autowired
    FiscalAdapters.Mock mockFiscal;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    SalesLedgerAssertions ledger;
    String branch;
    String location;
    String otherBranch;
    String otherLocation;
    String cashAccount;
    String mtnAccount;
    String bankAccount;
    String walkIn;
    String vat;
    String goods;
    String profile;

    @BeforeAll
    void fixtures() {
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(s -> {
            cashAccount = accounts.ensureByCode("10" + num(), "Showroom cash " + sfx, "cash", null);
            mtnAccount = accounts.ensureByCode("10" + num(), "Showroom MTN " + sfx, "mobile_money", null);
            bankAccount = accounts.ensureByCode("10" + num(), "Showroom bank clearing " + sfx, "bank", null);
        }));
        branch = branch("Kampala showroom " + sfx);
        location = location("Kampala floor " + sfx, branch);
        otherBranch = branch("Entebbe showroom " + sfx);
        otherLocation = location("Entebbe floor " + sfx, otherBranch);
        walkIn = partner("Walk-in " + sfx);
        vat = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"updatedAt\") VALUES (?, ?, ?, ?, 18,"
                + " now())").params(vat, demoOrg, "VAT 18 " + sfx, "V" + sfx).update();
        goods = product("Cement " + sfx, 35_000, 21_000);
        stock(goods, location, 1000, 21_000);
        profile = ok(201, "POST", "/sales/branch-profiles", Map.of("branchId", branch, "locationId", location,
                "cashAccountId", cashAccount, "momoMtnAccountId", mtnAccount, "bankClearingAccountId", bankAccount,
                "walkInPartnerId", walkIn, "numberPrefix", "K" + sfx.substring(0, 4).replaceAll("[^A-Z0-9]", "X")))
                .get("id").asString();
    }

    String num() {
        return String.valueOf(1000 + (int) (Math.random() * 8999));
    }

    String branch(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(id, demoOrg, "B-" + UUID.randomUUID().toString().substring(0, 8), name).update();
        return id;
    }

    String location(String name, String branchId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"branchId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'store', ?, now())").params(id, demoOrg, "L-" + UUID.randomUUID().toString()
                        .substring(0, 8), name, branchId).update();
        return id;
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES (?, ?,"
                + " ?, ?, true, now())").params(id, demoOrg, "CS-" + UUID.randomUUID().toString().substring(0, 8), name)
                .update();
        return id;
    }

    String product(String name, int price, int cost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable'::\"ProductType\", ?,"
                + " ?, true, now())").params(id, demoOrg, "CP-" + UUID.randomUUID().toString().substring(0, 8), name, price,
                        cost).update();
        return id;
    }

    void stock(String product, String loc, int qty, int cost) {
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity,"
                + " \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(UUID.randomUUID()
                        .toString(), demoOrg, product, loc, qty, cost).update();
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body, int status) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    /** A draft of {@code qty} × goods (VAT 18 % on top) for {@code partner} (null: walk-in). */
    String draft(String partner, int qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branchId", branch);
        if (partner != null) {
            body.put("partnerId", partner);
        }
        body.put("customerName", "ABC Construction");
        body.put("customerTin", "1000123456");
        body.put("lines", List.of(Map.of("productId", goods, "quantity", qty, "unitPrice", 35_000, "taxId", vat)));
        return ok(201, "POST", "/sales/cash-sales", body).get("id").asString();
    }

    String pay(String id, String method, String provider, double amount, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        if (provider != null) {
            body.put("provider", provider);
        }
        body.put("amount", amount);
        if (reference != null) {
            body.put("reference", reference);
        }
        JsonNode s = ok(201, "POST", "/sales/cash-sales/" + id + "/payments", body);
        JsonNode last = s.get("payments").get(s.get("payments").size() - 1);
        return last.get("id").asString();
    }

    void verify(String id, String paymentId) {
        ok(200, "POST", "/sales/cash-sales/" + id + "/payments/" + paymentId + "/verify", Map.of("status", "verified"));
    }

    /** Confirmed sale of {@code qty} paid fully in cash. */
    String confirmedCashSale(String partner, int qty) {
        String id = draft(partner, qty);
        double total = ok(200, "GET", "/sales/cash-sales/" + id, null).get("grandTotal").asDouble();
        verify(id, pay(id, "cash", null, total, null));
        money("/sales/cash-sales/" + id + "/confirm", null, 200);
        return id;
    }

    String approved(String partner, int qty) {
        String id = confirmedCashSale(partner, qty);
        ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/approve", null);
        return id;
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT COALESCE(sum(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND"
                + " l.\"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class).single();
    }

    BigDecimal partnerBalance(String key, String partnerId) {
        return ledger.balance(key, partnerId);
    }

    long glRows(String id) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND (\"sourceId\" = ? OR"
                + " \"postingKey\" LIKE ?)").params(demoOrg, id, "css:" + id + "%").query(Long.class).single();
    }

    BigDecimal onHand(String product, String loc) {
        return (BigDecimal) ledger.stock(product, loc).get("qty");
    }

    BigDecimal unbilled(String partner) {
        return partnerBalance("unbilled_receivable", partner);
    }

    /** Revenue / VAT credited for one submission (accrual) minus reversals. */
    BigDecimal revenueOf(String id) {
        String rev = ledger.mapped("sales_revenue");
        return jdbc.sql("SELECT COALESCE(sum(l.credit - l.debit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND l.\"accountId\" = ? AND (e.\"sourceId\" = ?"
                + " OR e.\"reversalOfId\" IN (SELECT id FROM \"JournalEntry\" WHERE \"sourceId\" = ?) OR e.\"sourceId\" IN"
                + " (SELECT \"invoiceId\" FROM \"SalesSubmissionInvoice\" WHERE \"submissionId\" = ?))").params(demoOrg, rev,
                        id, id, id).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("T1/T4: cash sale end to end — revenue, VAT, COGS, stock and cash exactly once; AR, deposit, unbilled 0")
    void cashSaleEndToEnd() {
        String c = partner("ABC Construction " + sfx);
        BigDecimal cashBefore = balance(cashAccount);
        BigDecimal stockBefore = onHand(goods, location);
        String id = draft(c, 10);
        JsonNode d = ok(200, "GET", "/sales/cash-sales/" + id, null);
        assertThat(d.get("number").asString()).startsWith("CSS-");
        assertThat(d.get("subtotal").asDouble()).isEqualTo(350_000);
        assertThat(d.get("taxTotal").asDouble()).isEqualTo(63_000);
        assertThat(d.get("grandTotal").asDouble()).isEqualTo(413_000);
        assertThat(d.get("customerTin").asString()).isEqualTo("1000123456");
        String p = pay(id, "cash", null, 413_000, null);
        // the collector's capture is the verification (no segregation on this branch); nothing posts before confirm
        JsonNode captured = ok(200, "GET", "/sales/cash-sales/" + id, null).get("payments").get(0);
        assertThat(captured.get("verificationStatus").asString()).isEqualTo("verified");
        assertThat(captured.get("verificationSource").asString()).isEqualTo("collector");
        assertThat(glRows(id)).isZero();
        verify(id, p);
        JsonNode conf = money("/sales/cash-sales/" + id + "/confirm", null, 200);
        assertThat(conf.get("executionStatus").asString()).isEqualTo("confirmed");
        assertThat(onHand(goods, location)).isEqualByComparingTo(stockBefore.subtract(BigDecimal.TEN));
        assertThat(balance(cashAccount).subtract(cashBefore)).isEqualByComparingTo("413000");
        assertThat(unbilled(c)).isEqualByComparingTo("413000");
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo("-413000");
        assertThat(revenueOf(id)).isEqualByComparingTo("350000");
        // frozen after confirm
        assertThat(as("PATCH", "/sales/cash-sales/" + id, Map.of("remarks", "x")).status()).isEqualTo(400);
        // head office: submit / review / approve post nothing
        long rows = glRows(id);
        ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/approve", null);
        assertThat(glRows(id)).isEqualTo(rows);
        JsonNode inv = money("/sales/cash-sales/" + id + "/generate-invoice", null, 200);
        assertThat(inv.get("processingStatus").asString()).isEqualTo("invoiced");
        String invoiceId = inv.get("currentInvoiceId").asString();
        JsonNode doc = ok(200, "GET", "/invoices/" + invoiceId, null);
        assertThat(doc.get("totalAmount").asDouble()).isEqualTo(413_000);
        assertThat(doc.get("amountResidual").asDouble()).isZero();
        assertThat(doc.get("branchId").asString()).isEqualTo(branch);
        // the invoice only reclassified: no new revenue, no new stock movement, no new cash
        assertThat(revenueOf(id)).isEqualByComparingTo("350000");
        assertThat(onHand(goods, location)).isEqualByComparingTo(stockBefore.subtract(BigDecimal.TEN));
        assertThat(balance(cashAccount).subtract(cashBefore)).isEqualByComparingTo("413000");
        assertThat(unbilled(c)).isEqualByComparingTo("0");
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo("0");
        ledger.receivableTiesOut(c);
        assertThat(partnerBalance("accounts_receivable", c)).isEqualByComparingTo("0");
        ledger.trialBalanceZero();
        // a second invoice is refused
        assertThat(call("POST", "/sales/cash-sales/" + id + "/generate-invoice", null, key()).status()).isEqualTo(400);
        List<String> actions = new ArrayList<>();
        ok(200, "GET", "/sales/cash-sales/" + id + "/timeline", null).forEach(e -> actions.add(e.get("action").asString()));
        assertThat(actions).containsSubsequence("create", "payment_verified", "confirm", "submit", "start_review",
                "approve", "generate_invoice");
    }

    @Test
    @DisplayName("T2/T3/T8: mixed cash + MTN + bank; unverified mobile money / bank block confirm with no GL rows")
    void mixedPaymentsAndVerification() {
        BigDecimal mtnBefore = balance(mtnAccount);
        BigDecimal bankBefore = balance(bankAccount);
        String id = draft(null, 5);
        // 5 × 35,000 × 1.18 = 206,500
        String cash = pay(id, "cash", null, 100_000, null);
        String momo = pay(id, "mobile_money", "mtn", 56_500, "MTN-" + sfx + "-1");
        String bank = pay(id, "bank", null, 50_000, "BNK-" + sfx + "-1");
        verify(id, cash);
        verify(id, momo);
        assertThat(glRows(id)).isZero();
        verify(id, bank);
        money("/sales/cash-sales/" + id + "/confirm", null, 200);
        assertThat(balance(mtnAccount).subtract(mtnBefore)).isEqualByComparingTo("56500");
        assertThat(balance(bankAccount).subtract(bankBefore)).isEqualByComparingTo("50000");
        JsonNode s = ok(200, "GET", "/sales/cash-sales/" + id, null);
        assertThat(s.get("deposits").size()).isEqualTo(3);
        // reference reuse and a branch without the method
        String other = draft(null, 1);
        assertThat(as("POST", "/sales/cash-sales/" + other + "/payments", Map.of("method", "mobile_money", "provider",
                "mtn", "amount", 1000, "reference", "MTN-" + sfx + "-1")).status()).isEqualTo(409);
        assertThat(as("POST", "/sales/cash-sales/" + other + "/payments", Map.of("method", "mobile_money", "provider",
                "airtel", "amount", 1000, "reference", "AIR-1")).status()).isEqualTo(400);
        assertThat(as("POST", "/sales/cash-sales/" + other + "/payments", Map.of("method", "card", "amount", 1000))
                .status()).isEqualTo(400);
        // a client-sent tender account is refused (never trusted)
        assertThat(as("POST", "/sales/cash-sales/" + other + "/payments", Map.of("method", "cash", "amount", 1000,
                "accountId", bankAccount)).status()).isEqualTo(201);
        JsonNode o = ok(200, "GET", "/sales/cash-sales/" + other, null);
        assertThat(o.get("payments").get(0).get("accountId").asString()).isEqualTo(cashAccount);
        // a payment mode picked at the counter decides method, provider label and receiving account
        String modeId = ok(201, "POST", "/accounts/pos-payment-methods", Map.of("code", "cs_bank_" + sfx.toLowerCase(),
                "label", "Bank transfer " + sfx, "kind", "bank", "accountId", bankAccount, "requiresReference", true))
                .get("id").asString();
        try {
            JsonNode modes = ok(200, "GET", "/sales/cash-sales/payment-modes", null);
            assertThat(modes.findValuesAsString("id")).contains(modeId);
            assertThat(modes.findValuesAsString("kind")).doesNotContain("card", "store_credit");
            JsonNode m = ok(201, "POST", "/sales/cash-sales/" + other + "/payments", Map.of("paymentModeId", modeId,
                    "amount", 500, "reference", "BT-" + sfx, "paymentDate", "2026-11-10", "payerName", "Jane Payer",
                    "payerAccount", "0123456789", "notes", "Deposit slip at the counter"));
            JsonNode last = m.get("payments").get(m.get("payments").size() - 1);
            assertThat(last.get("paymentDate").asString()).isEqualTo("2026-11-10");
            assertThat(last.get("payerName").asString()).isEqualTo("Jane Payer");
            assertThat(last.get("payerAccount").asString()).isEqualTo("0123456789");
            assertThat(last.get("notes").asString()).isEqualTo("Deposit slip at the counter");
            assertThat(last.get("attachments").size()).isZero();
            assertThat(last.get("verificationStatus").asString()).isEqualTo("verified");
            assertThat(as("POST", "/sales/cash-sales/" + other + "/payments", Map.of("paymentModeId", modeId,
                    "amount", 500, "reference", "BT2-" + sfx, "attachmentIds", List.of("nope"))).status())
                    .isEqualTo(400);
            assertThat(last.get("method").asString()).isEqualTo("bank");
            assertThat(last.get("accountId").asString()).isEqualTo(bankAccount);
            assertThat(last.get("paymentModeLabel").asString()).isEqualTo("Bank transfer " + sfx);
            assertThat(as("POST", "/sales/cash-sales/" + other + "/payments", Map.of("paymentModeId", "nope",
                    "amount", 500)).status()).isEqualTo(400);
        } finally {
            as("DELETE", "/accounts/pos-payment-methods/" + modeId, null);
        }
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("Payments: never above the sale total, no future payment date, removal on the timeline")
    void paymentCaptureGuards() {
        String id = draft(null, 1); // 35,000 × 1.18 = 41,300
        String path = "/sales/cash-sales/" + id + "/payments";
        Res over = as("POST", path, Map.of("method", "cash", "amount", 50_000));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString())
                .isEqualTo("Payments (50000) would exceed the sale total (41300); the balance due is 41300");
        String first = pay(id, "cash", null, 40_000, null);
        assertThat(as("POST", path, Map.of("method", "cash", "amount", 1_301)).body().get("message").asString())
                .endsWith("the balance due is 1300");
        Res future = as("POST", path, Map.of("method", "cash", "amount", 1_000, "paymentDate",
                LocalDate.now().plusDays(3).toString()));
        assertThat(future.status()).isEqualTo(400);
        assertThat(future.body().get("message").asString()).isEqualTo("paymentDate must not be in the future");
        assertThat(ok(200, "DELETE", path + "/" + first, null).get("payments").size()).isZero();
        assertThat(as("DELETE", path + "/" + first, null).status()).isEqualTo(404);
        pay(id, "cash", null, 41_300, null);
        money("/sales/cash-sales/" + id + "/confirm", null, 200);
        List<String> actions = new ArrayList<>();
        ok(200, "GET", "/sales/cash-sales/" + id + "/timeline", null).forEach(e -> actions.add(e.get("action").asString()));
        assertThat(actions).containsSubsequence("payment_verified", "payment_removed", "payment_verified", "confirm");
    }

    @Test
    @DisplayName("T7/T9: insufficient stock at the showroom location → 400, nothing posted; payments ≠ total → 400")
    void insufficientStockAndTotals() {
        // strict org (negative stock off) + item policy "block" → 400, nothing posted
        ok(200, "PUT", "/costing/settings", Map.of("allowNegativeStock", false));
        jdbc.sql("UPDATE \"Product\" SET \"stockPolicy\" = 'block'::\"PosStockPolicy\" WHERE id = ?").param(goods).update();
        try {
            String id = draft(null, 100_000);
            JsonNode s = ok(200, "GET", "/sales/cash-sales/" + id, null);
            verify(id, pay(id, "cash", null, s.get("grandTotal").asDouble(), null));
            Res r = call("POST", "/sales/cash-sales/" + id + "/confirm", null, key());
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).contains("Insufficient stock");
            assertThat(glRows(id)).isZero();
        } finally {
            ok(200, "PUT", "/costing/settings", Map.of("allowNegativeStock", true));
            jdbc.sql("UPDATE \"Product\" SET \"stockPolicy\" = 'warn'::\"PosStockPolicy\" WHERE id = ?").param(goods)
                    .update();
        }
        // negative stock allowed (the default): selling above on hand confirms and the location goes negative
        String tiles = product("Tiles " + sfx, 10_000, 6_000);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branchId", branch);
        body.put("lines", List.of(Map.of("productId", tiles, "quantity", 3, "unitPrice", 10_000)));
        String neg = ok(201, "POST", "/sales/cash-sales", body).get("id").asString();
        verify(neg, pay(neg, "cash", null, 30_000, null));
        money("/sales/cash-sales/" + neg + "/confirm", null, 200);
        assertThat(jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(tiles, location).query(BigDecimal.class).single()).isEqualByComparingTo("-3");
        String short_ = draft(null, 1);
        verify(short_, pay(short_, "cash", null, 1000, null));
        assertThat(call("POST", "/sales/cash-sales/" + short_ + "/confirm", null, key()).body().get("message")
                .asString()).contains("must equal the sale total");
    }

    @Test
    @DisplayName("Delivery & fulfilment: details on a draft, dispatch needs confirm, delivered is final, timeline")
    void deliveryAndFulfilment() {
        String d = draft(null, 1);
        String path = "/sales/cash-sales/" + d + "/delivery";
        JsonNode s = ok(200, "PATCH", path, Map.of("deliveryMethod", "delivery", "deliveryAddress", "Plot 12, Ntinda",
                "deliveryContactPhone", "0772000000"));
        assertThat(s.get("deliveryStatus").asString()).isEqualTo("pending");
        assertThat(s.get("deliveryAddress").asString()).isEqualTo("Plot 12, Ntinda");
        assertThat(as("PATCH", path, Map.of("deliveryStatus", "dispatched")).status()).isEqualTo(400);
        assertThat(as("PATCH", path, Map.of("deliveryStatus", "scheduled")).status()).isEqualTo(400);
        assertThat(as("PATCH", path, Map.of("deliveryMethod", "drone")).status()).isEqualTo(400);
        assertThat(as("PATCH", path, Map.of("grandTotal", 1)).status()).isEqualTo(400);
        ok(200, "PATCH", path, Map.of("deliveryStatus", "scheduled", "deliveryScheduledDate", "2026-11-10"));
        assertThat(ok(200, "PATCH", path, Map.of("deliveryDeadline", "2026-11-12")).get("deliveryDeadline").asString())
                .startsWith("2026-11-12");
        assertThat(as("PATCH", path, Map.of("deliveryDeadline", "soon")).body().get("message").asString())
                .isEqualTo("deliveryDeadline must be a valid ISO 8601 date string");
        JsonNode site = ok(200, "PATCH", path, Map.of("deliveryLandmark", "Behind Ntinda market",
                "deliveryLoadingSite", "Showroom yard", "deliveryScope", "partial", "deliveryOffloadingEquipment", "yes"));
        assertThat(site.get("deliveryLandmark").asString()).isEqualTo("Behind Ntinda market");
        assertThat(site.get("deliveryLoadingSite").asString()).isEqualTo("Showroom yard");
        assertThat(site.get("deliveryScope").asString()).isEqualTo("partial");
        assertThat(site.get("deliveryOffloadingEquipment").asString()).isEqualTo("yes");
        assertThat(as("PATCH", path, Map.of("deliveryScope", "half")).status()).isEqualTo(400);
        assertThat(as("PATCH", path, Map.of("deliveryOffloadingEquipment", "maybe")).status()).isEqualTo(400);

        String id = confirmedCashSale(null, 1);
        String p2 = "/sales/cash-sales/" + id + "/delivery";
        assertThat(as("PATCH", p2, Map.of("deliveryMethod", "delivery", "deliveryStatus", "dispatched")).status())
                .isEqualTo(400);
        ok(200, "PATCH", p2, Map.of("deliveryMethod", "delivery", "deliveryAddress", "Kololo", "deliveryStatus",
                "dispatched", "deliveryDriver", "Okello", "deliveryVehicle", "UBA 123X"));
        JsonNode done = ok(200, "PATCH", p2, Map.of("deliveryStatus", "delivered", "deliveryReceivedBy", "Site manager"));
        assertThat(done.get("deliveredAt").isNull()).isFalse();
        assertThat(as("PATCH", p2, Map.of("deliveryStatus", "failed")).status()).isEqualTo(400);
        ok(200, "PATCH", p2, Map.of("deliveryNotes", "Signed delivery note filed"));
        assertThat(as("PATCH", p2, Map.of("deliveryDeadline", "2026-12-01")).status()).isEqualTo(400);
        JsonNode sale = ok(200, "GET", "/sales/cash-sales/" + id, null);
        assertThat(sale.get("deliveryNoteNumber").asString()).isEqualTo("DN/" + sale.get("number").asString());
        assertThat(sale.get("returns").size()).isZero();
        assertThat(sale.get("lines").get(0).get("returnedQty").asDouble()).isZero();
        java.net.http.HttpResponse<byte[]> note = downloadBytes("/sales/cash-sales/" + id + "/delivery-note");
        assertThat(note.statusCode()).isEqualTo(200);
        assertThat(new String(note.body(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(ok(200, "GET", "/dms/records/cash_sale_delivery/" + id + "/count", null).get("count").asLong())
                .isZero();
        assertThat(as("GET", "/dms/records/cash_sale_delivery/" + UUID.randomUUID() + "/attachments", null).status())
                .isEqualTo(404);
        JsonNode timeline = ok(200, "GET", "/sales/cash-sales/" + id + "/timeline", null);
        assertThat(timeline.findValuesAsString("action")).contains("delivery_dispatched", "delivery_delivered",
                "delivery_update");
    }

    @Test
    @DisplayName("Approve & invoice in one step: invoice posted and paid with the sale's payment details, delivery copied"
            + " and kept in sync")
    void approveAndInvoiceCarriesPaymentAndDelivery() {
        String c = partner("Direct Invoice " + sfx);
        String id = draft(c, 2); // 2 × 35,000 × 1.18 = 82,600
        ok(201, "POST", "/sales/cash-sales/" + id + "/payments", Map.of("method", "cash", "amount", 50_000,
                "payerName", "Peter Cash"));
        ok(201, "POST", "/sales/cash-sales/" + id + "/payments", Map.of("method", "mobile_money", "provider", "mtn",
                "amount", 32_600, "reference", "MTN-DI-" + sfx, "payerName", "Jane Momo", "payerAccount", "0772123456"));
        money("/sales/cash-sales/" + id + "/confirm", null, 200);
        ok(200, "PATCH", "/sales/cash-sales/" + id + "/delivery", Map.of("deliveryMethod", "delivery",
                "deliveryAddress", "Plot 4, Bugolobi", "deliveryStatus", "dispatched", "deliveryDriver", "Okello",
                "deliveryVehicle", "UBB 456Y", "deliveryLandmark", "Opposite the mosque"));
        JsonNode submitted = ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        assertThat(submitted.get("actions").toString()).contains("approve_and_invoice");
        // a branch with segregation of duties keeps approve and invoice apart; nothing changes
        jdbc.sql("UPDATE \"BranchSalesProfile\" SET \"requireSegregation\" = true WHERE id = ?").param(profile).update();
        try {
            assertThat(ok(200, "GET", "/sales/cash-sales/" + id, null).get("actions").toString())
                    .doesNotContain("approve_and_invoice");
            assertThat(call("POST", "/sales/cash-sales/" + id + "/approve-and-invoice", null, key()).status())
                    .isEqualTo(403);
            assertThat(ok(200, "GET", "/sales/cash-sales/" + id, null).get("processingStatus").asString())
                    .isEqualTo("submitted");
        } finally {
            jdbc.sql("UPDATE \"BranchSalesProfile\" SET \"requireSegregation\" = false WHERE id = ?").param(profile)
                    .update();
        }
        // straight from submitted: review started, approved, invoiced, paid — one call
        JsonNode done = money("/sales/cash-sales/" + id + "/approve-and-invoice", null, 200);
        assertThat(done.get("processingStatus").asString()).isEqualTo("invoiced");
        assertThat(done.get("approvedById").isNull()).isFalse();
        String invoiceId = done.get("currentInvoiceId").asString();
        String number = done.get("number").asString();
        List<String> actions = new ArrayList<>();
        ok(200, "GET", "/sales/cash-sales/" + id + "/timeline", null).forEach(e -> actions.add(e.get("action").asString()));
        assertThat(actions).containsSubsequence("submit", "start_review", "approve", "generate_invoice");
        // a second click is refused: the sale is no longer awaiting approval, still one invoice
        assertThat(call("POST", "/sales/cash-sales/" + id + "/approve-and-invoice", null, key()).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM \"SalesSubmissionInvoice\" WHERE \"submissionId\" = ?").param(id)
                .query(Long.class).single()).isEqualTo(1);

        // the invoice is paid, and each payment carries the sale's tender, reference, payer and branch
        JsonNode doc = ok(200, "GET", "/invoices/" + invoiceId, null);
        assertThat(doc.get("amountResidual").asDouble()).isZero();
        assertThat(doc.get("deliveryAddress").asString()).isEqualTo("Plot 4, Bugolobi");
        List<Map<String, Object>> pays = jdbc.sql("SELECT p.reference, p.\"branchId\", a.amount FROM \"PaymentAllocation\" a"
                + " JOIN \"Payment\" p ON p.id = a.\"paymentId\" WHERE a.\"documentId\" = ? ORDER BY a.amount DESC")
                .param(invoiceId).query().listOfRows();
        assertThat(pays).hasSize(2);
        assertThat(pays.get(0).get("reference").toString()).startsWith("Cash · Peter Cash · " + number + " · DEP-");
        assertThat(pays.get(1).get("reference").toString())
                .startsWith("MTN Mobile money · ref MTN-DI-" + sfx + " · Jane Momo · " + number + " · DEP-");
        assertThat(pays).allSatisfy(p -> assertThat(p.get("branchId")).isEqualTo(branch));
        JsonNode src = ok(200, "GET", "/sales/invoices/" + invoiceId + "/cash-sale", null);
        assertThat(src.get("number").asString()).isEqualTo(number);
        assertThat(src.get("payments").size()).isEqualTo(2);
        assertThat(src.get("payments").get(1).get("payerAccount").asString()).isEqualTo("0772123456");
        assertThat(src.get("payments").get(1).get("depositNumber").asString()).startsWith("DEP-");
        String plain = jdbc.sql("SELECT id FROM \"Document\" WHERE \"organizationId\" = ? AND \"documentType\" ="
                + " 'sales_invoice' AND \"sourceType\" IS DISTINCT FROM 'cash_sale' LIMIT 1").param(demoOrg)
                .query(String.class).optional().orElse(UUID.randomUUID().toString());
        assertThat(as("GET", "/sales/invoices/" + plain + "/cash-sale", null).status()).isEqualTo(404);

        // the invoice's delivery is the sale's delivery; it is edited on the sale and follows it
        String handover = "/sales/invoices/" + invoiceId + "/handover";
        JsonNode h = ok(200, "GET", handover, null);
        assertThat(h.get("saved").asBoolean()).isTrue();
        assertThat(h.get("method").asString()).isEqualTo("delivery");
        assertThat(h.get("status").asString()).isEqualTo("dispatched");
        assertThat(h.get("address").asString()).isEqualTo("Plot 4, Bugolobi");
        assertThat(h.get("driver").asString()).isEqualTo("Okello");
        assertThat(h.get("vehicle").asString()).isEqualTo("UBB 456Y");
        assertThat(h.get("landmark").asString()).isEqualTo("Opposite the mosque");
        assertThat(h.get("locationId").asString()).isEqualTo(location);
        assertThat(h.get("contactName").asString()).isEqualTo("ABC Construction");
        assertThat(h.get("cashSaleNumber").asString()).isEqualTo(number);
        Res edit = as("PATCH", handover, Map.of("driver", "Someone else"));
        assertThat(edit.status()).isEqualTo(400);
        assertThat(edit.body().get("message").asString())
                .isEqualTo("The delivery of this invoice is managed on cash sale " + number);
        // a change on the sale reaches the invoice handover and the invoice's own delivery address and date
        ok(200, "PATCH", "/sales/cash-sales/" + id + "/delivery", Map.of("deliveryAddress", "Plot 9, Naguru",
                "deliveryScheduledDate", "2026-11-30"));
        JsonNode moved = ok(200, "GET", "/invoices/" + invoiceId, null);
        assertThat(moved.get("deliveryAddress").asString()).isEqualTo("Plot 9, Naguru");
        assertThat(moved.get("deliveryDate").asString()).startsWith("2026-11-30");
        assertThat(ok(200, "GET", handover, null).get("address").asString()).isEqualTo("Plot 9, Naguru");
        ok(200, "PATCH", "/sales/cash-sales/" + id + "/delivery", Map.of("deliveryStatus", "delivered",
                "deliveryReceivedBy", "Site foreman"));
        JsonNode delivered = ok(200, "GET", handover, null);
        assertThat(delivered.get("status").asString()).isEqualTo("delivered");
        assertThat(delivered.get("receivedBy").asString()).isEqualTo("Site foreman");
        assertThat(delivered.get("deliveredAt").isNull()).isFalse();
        assertThat(delivered.get("lines").get(0).get("handedOverQty").asDouble()).isEqualTo(2);

        // cancel → the replacement invoice gets the delivery and the payment details again
        money("/sales/cash-sales/" + id + "/cancel-invoice", Map.of("reason", "Wrong TIN"), 200);
        String second = money("/sales/cash-sales/" + id + "/generate-invoice", null, 200).get("currentInvoiceId")
                .asString();
        assertThat(ok(200, "GET", "/sales/invoices/" + second + "/handover", null).get("status").asString())
                .isEqualTo("delivered");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PaymentAllocation\" a JOIN \"Payment\" p ON p.id = a.\"paymentId\""
                + " WHERE a.\"documentId\" = ? AND p.reference LIKE ?").params(second, "%" + number + "%")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(partnerBalance("accounts_receivable", c)).isEqualByComparingTo("0");
        assertThat(unbilled(c)).isEqualByComparingTo("0");
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("T14/T15/T16: return → amend TIN → resubmit; reject keeps stock and GL; reopen → invoice")
    void returnRejectReopen() {
        String c = partner("Returned cust " + sfx);
        String id = confirmedCashSale(c, 2);
        long rows = glRows(id);
        BigDecimal stockAfterConfirm = onHand(goods, location);
        ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        assertThat(as("POST", "/sales/cash-sales/" + id + "/return", Map.of()).status()).isEqualTo(400);
        ok(200, "POST", "/sales/cash-sales/" + id + "/return", Map.of("reason", "TIN missing a digit"));
        assertThat(as("POST", "/sales/cash-sales/" + id + "/amend", Map.of("unitPrice", 1)).status()).isEqualTo(400);
        ok(200, "POST", "/sales/cash-sales/" + id + "/amend", Map.of("customerTin", "1000999999"));
        assertThat(glRows(id)).isEqualTo(rows);
        long amendments = jdbc.sql("SELECT count(*) FROM \"SalesSubmissionEvent\" WHERE \"submissionId\" = ? AND action ="
                + " 'amendment' AND field = 'customerTin' AND \"oldValue\" = '1000123456' AND \"newValue\" = '1000999999'")
                .param(id).query(Long.class).single();
        assertThat(amendments).as(jdbc.sql("SELECT action || ':' || COALESCE(field, '') || ':' || COALESCE(\"oldValue\", '')"
                + " || ':' || COALESCE(\"newValue\", '') FROM \"SalesSubmissionEvent\" WHERE \"submissionId\" = ?").param(id)
                .query(String.class).list().toString()).isEqualTo(1);
        ok(200, "POST", "/sales/cash-sales/" + id + "/resubmit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/reject", Map.of("reason", "Wrong customer"));
        assertThat(glRows(id)).isEqualTo(rows);
        assertThat(onHand(goods, location)).isEqualByComparingTo(stockAfterConfirm);
        JsonNode dash = ok(200, "GET", "/sales/cash-sales/dashboard?scope=head_office", null);
        boolean listed = false;
        for (JsonNode e : dash.get("exceptions")) {
            if (e.get("id").asString().equals(id)) {
                listed = true;
                assertThat(e.get("ownerUserId").isNull()).isFalse();
                assertThat(e.get("ageDays").asLong()).isZero();
            }
        }
        assertThat(listed).isTrue();
        ok(200, "POST", "/sales/cash-sales/" + id + "/reopen", Map.of("reason", "Customer confirmed"));
        ok(200, "POST", "/sales/cash-sales/" + id + "/approve", null);
        money("/sales/cash-sales/" + id + "/generate-invoice", null, 200);
        assertThat(unbilled(c)).isEqualByComparingTo("0");
        ledger.receivableTiesOut(c);
    }

    @Test
    @DisplayName("T17: two concurrent generate-invoice requests → exactly one invoice")
    void concurrentInvoice() throws Exception {
        String id = approved(partner("Concurrent " + sfx), 1);
        Callable<Integer> task = () -> call("POST", "/sales/cash-sales/" + id + "/generate-invoice", null, key()).status();
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(task);
            Future<Integer> b = pool.submit(task);
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdown();
        }
        long invoices = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE \"sourceType\" = 'cash_sale' AND \"sourceId\" = ?")
                .param(id).query(Long.class).single();
        assertThat(invoices).isEqualTo(1);
    }

    @Test
    @DisplayName("T19/T13: cancel invoice → replacement reuses the accrual; idempotent confirm replay posts once")
    void cancelAndReplaceInvoice() {
        String c = partner("Replace " + sfx);
        String id = draft(c, 3);
        double total = ok(200, "GET", "/sales/cash-sales/" + id, null).get("grandTotal").asDouble();
        verify(id, pay(id, "cash", null, total, null));
        Map<String, String> h = key();
        assertThat(call("POST", "/sales/cash-sales/" + id + "/confirm", null, h).status()).isEqualTo(200);
        long rows = glRows(id);
        assertThat(call("POST", "/sales/cash-sales/" + id + "/confirm", null, h).status()).isEqualTo(200);
        assertThat(glRows(id)).isEqualTo(rows);
        ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/approve", null);
        String first = money("/sales/cash-sales/" + id + "/generate-invoice", null, 200).get("currentInvoiceId").asString();
        assertThat(call("POST", "/sales/cash-sales/" + id + "/cancel-invoice", Map.of(), key()).status()).isEqualTo(400);
        JsonNode back = money("/sales/cash-sales/" + id + "/cancel-invoice", Map.of("reason", "Wrong address"), 200);
        assertThat(back.get("processingStatus").asString()).isEqualTo("approved");
        assertThat(ok(200, "GET", "/invoices/" + first, null).get("status").asString()).isEqualTo("cancelled");
        // accrual and deposit back in force
        assertThat(unbilled(c)).isEqualByComparingTo(BigDecimal.valueOf(total));
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo(BigDecimal.valueOf(-total));
        ledger.receivableTiesOut(c);
        String second = money("/sales/cash-sales/" + id + "/generate-invoice", null, 200).get("currentInvoiceId")
                .asString();
        assertThat(second).isNotEqualTo(first);
        JsonNode s = ok(200, "GET", "/sales/cash-sales/" + id, null);
        assertThat(s.get("invoices").size()).isEqualTo(2);
        assertThat(s.get("invoices").get(0).get("status").asString()).isEqualTo("cancelled");
        assertThat(s.get("invoices").get(1).get("status").asString()).isEqualTo("active");
        assertThat(revenueOf(id)).isEqualByComparingTo("105000");
        assertThat(unbilled(c)).isEqualByComparingTo("0");
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo("0");
        assertThat(partnerBalance("accounts_receivable", c)).isEqualByComparingTo("0");
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("T21: void (goods back) — accrual reversed, stock back at cost, deposit refunded or kept as advance")
    void voidSale() {
        String c = partner("Void " + sfx);
        BigDecimal stockBefore = onHand(goods, location);
        BigDecimal cashBefore = balance(cashAccount);
        String id = confirmedCashSale(c, 4);
        assertThat(call("POST", "/sales/cash-sales/" + id + "/void", Map.of("reason", "Keyed twice"), key()).status())
                .isEqualTo(400);
        money("/sales/cash-sales/" + id + "/void", Map.of("reason", "Keyed twice", "goodsReturned", true), 200);
        assertThat(onHand(goods, location)).isEqualByComparingTo(stockBefore);
        JsonNode voided = ok(200, "GET", "/sales/cash-sales/" + id, null);
        assertThat(voided.get("returns").get(0).get("kind").asString()).isEqualTo("void");
        assertThat(voided.get("returns").get(0).get("reason").asString()).isEqualTo("Keyed twice");
        assertThat(voided.get("lines").get(0).get("returnedQty").asDouble()).isEqualTo(4);
        assertThat(unbilled(c)).isEqualByComparingTo("0");
        assertThat(revenueOf(id)).isEqualByComparingTo("0");
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo("0");
        assertThat(balance(cashAccount)).isEqualByComparingTo(cashBefore);
        // keep: the money stays as a documented advance of the customer
        String kept = confirmedCashSale(c, 1);
        money("/sales/cash-sales/" + kept + "/void", Map.of("reason", "Customer will collect later", "goodsReturned",
                true, "depositAction", "keep"), 200);
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo("-41300");
        String dep = jdbc.sql("SELECT source FROM \"CustomerDeposit\" WHERE \"cashSaleId\" = ?").param(kept)
                .query(String.class).single();
        assertThat(dep).isEqualTo("receipt");
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("R1 P1-2: a credit note on a cash-sale invoice restocks against the cash sale's issue (location, cost)")
    void creditNoteOnCashSaleInvoiceRestocksAtTheSaleIssue() {
        String c = partner("CN cash " + sfx);
        String id = approved(c, 3);
        String invoiceId = money("/sales/cash-sales/" + id + "/generate-invoice", null, 200).get("currentInvoiceId")
                .asString();
        Map<String, Object> issue = jdbc.sql("SELECT id, \"locationId\", \"unitCost\" FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"referenceType\" = 'cash_sale' AND \"referenceId\" = ? AND"
                + " \"quantityChange\" < 0").params(demoOrg, id).query().singleRow();
        BigDecimal before = onHand(goods, location);
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", c, "issueDate", LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString(),
                "reversedDocumentId", invoiceId, "lines", List.of(Map.of("productId", goods, "quantity", 2,
                        "unitPrice", 35_000, "taxId", vat)))).get("id").asString();
        Res posted = as("POST", "/credit-notes/" + cn + "/post", null);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        Map<String, Object> back = jdbc.sql("SELECT \"locationId\", \"unitCost\", \"reversesLedgerId\","
                + " \"quantityChange\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\" ="
                + " 'credit_note' AND \"referenceId\" = ?").params(demoOrg, cn).query().singleRow();
        assertThat(back.get("locationId")).as("back at the cash sale's floor").isEqualTo(location);
        assertThat((BigDecimal) back.get("unitCost")).isEqualByComparingTo((BigDecimal) issue.get("unitCost"));
        assertThat(back.get("reversesLedgerId")).isEqualTo(issue.get("id"));
        assertThat((BigDecimal) back.get("quantityChange")).isEqualByComparingTo("2");
        assertThat(onHand(goods, location)).isEqualByComparingTo(before.add(BigDecimal.TWO));
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("R1 P1-1: a cash-sale void names the issue rows it brings back")
    void voidLinksTheIssueRows() {
        String id = confirmedCashSale(partner("Void link " + sfx), 2);
        money("/sales/cash-sales/" + id + "/void", Map.of("reason", "Keyed twice", "goodsReturned", true), 200);
        List<Map<String, Object>> rows = jdbc.sql("SELECT r.\"locationId\", r.\"unitCost\", i.\"unitCost\" AS"
                + " \"issueCost\", i.\"locationId\" AS \"issueLocation\" FROM \"InventoryLedger\" r JOIN \"InventoryLedger\" i"
                + " ON i.id = r.\"reversesLedgerId\" WHERE r.\"organizationId\" = ? AND r.\"referenceType\" ="
                + " 'cash_sale_void' AND r.\"referenceId\" = ?").params(demoOrg, id).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("locationId")).isEqualTo(rows.getFirst().get("issueLocation"));
        assertThat((BigDecimal) rows.getFirst().get("unitCost"))
                .isEqualByComparingTo((BigDecimal) rows.getFirst().get("issueCost"));
    }

    @Test
    @DisplayName("T22/T23: a cash-sale deposit settles only its own invoice; no refund / void via /customer-deposits")
    void depositIsolation() {
        String id = confirmedCashSale(null, 1);
        String dep = jdbc.sql("SELECT id FROM \"CustomerDeposit\" WHERE \"cashSaleId\" = ?").param(id).query(String.class)
                .single();
        // another posted invoice of the same walk-in customer
        String inv = as("POST", "/invoices", Map.of("partnerId", walkIn, "issueDate", java.time.LocalDate.now()
                .toString(), "lines", List.of(Map.of("description", "Service", "quantity", 1, "unitPrice", 1000))))
                .body().get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        Res apply = call("POST", "/customer-deposits/" + dep + "/apply", Map.of("documentId", inv), key());
        assertThat(apply.status()).isEqualTo(400);
        assertThat(apply.body().get("message").asString()).contains("only the invoice of its own cash sale");
        assertThat(call("POST", "/customer-deposits/" + dep + "/refund", Map.of("reason", "x"), key()).status())
                .isEqualTo(400);
        assertThat(call("POST", "/customer-deposits/" + dep + "/void", Map.of("reason", "x"), key()).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("T11/T12: profile validation — wrong account nature, foreign location, missing tender; drift at confirm")
    void profileValidation() {
        String revenue = ledger.mapped("sales_revenue");
        String b2 = branch("Jinja " + sfx);
        String l2 = location("Jinja floor " + sfx, b2);
        Res incomeAsTender = as("POST", "/sales/branch-profiles", Map.of("branchId", b2, "locationId", l2,
                "cashAccountId", revenue, "numberPrefix", "JJ" + sfx.substring(0, 2).replaceAll("[^A-Z0-9]", "X")));
        assertThat(incomeAsTender.status()).isEqualTo(400);
        assertThat(incomeAsTender.body().get("message").asString()).contains("cash account");
        assertThat(as("POST", "/sales/branch-profiles", Map.of("branchId", b2, "locationId", location, "cashAccountId",
                cashAccount, "numberPrefix", "JJ1")).status()).isEqualTo(400);
        assertThat(as("POST", "/sales/branch-profiles", Map.of("branchId", b2, "locationId", l2, "cashAccountId",
                cashAccount, "numberPrefix", "JJ3", "invoicingMode", "self")).status()).isEqualTo(400);
        // basic profile: branch + stock location only; the prefix comes from the branch, payments use payment modes
        JsonNode basic = ok(201, "POST", "/sales/branch-profiles", Map.of("branchId", b2, "locationId", l2));
        assertThat(basic.get("numberPrefix").asString()).matches("[A-Z0-9]{2,8}");
        // drift: the tender account is deactivated after the profile was saved
        String id = draft(null, 1);
        verify(id, pay(id, "cash", null, 41_300, null));
        jdbc.sql("UPDATE \"Account\" SET \"isActive\" = false WHERE id = ?").param(cashAccount).update();
        try {
            Res r = call("POST", "/sales/cash-sales/" + id + "/confirm", null, key());
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).contains("inactive");
            assertThat(glRows(id)).isZero();
        } finally {
            jdbc.sql("UPDATE \"Account\" SET \"isActive\" = true WHERE id = ?").param(cashAccount).update();
        }
    }

    @Test
    @DisplayName("T24/T25/T26: fiscal — timeout then accepted = one document; cancel failure blocks reversal; TIN locked")
    void fiscalLifecycle() {
        String b3 = branch("Mbarara " + sfx);
        String l3 = location("Mbarara floor " + sfx, b3);
        stock(goods, l3, 100, 21_000);
        ok(201, "POST", "/sales/branch-profiles", Map.of("branchId", b3, "locationId", l3, "cashAccountId", cashAccount,
                "walkInPartnerId", walkIn, "numberPrefix", "MB" + sfx.substring(0, 2).replaceAll("[^A-Z0-9]", "X"),
                "fiscalTrigger", "at_confirm", "fiscalProvider", "mock"));
        mockFiscal.script("timeout_accepted");
        String id = ok(201, "POST", "/sales/cash-sales", Map.of("branchId", b3, "lines", List.of(Map.of("productId",
                goods, "quantity", 1, "unitPrice", 35_000, "taxId", vat)))).get("id").asString();
        verify(id, pay(id, "cash", null, 41_300, null));
        assertThat(money("/sales/cash-sales/" + id + "/confirm", null, 200).get("fiscalStatus").asString())
                .isEqualTo("pending");
        JsonNode first = ok(200, "POST", "/sales/cash-sales/" + id + "/fiscal/retry", null);
        assertThat(first.get("fiscalDocuments").get(0).get("status").asString()).isEqualTo("unknown");
        JsonNode second = ok(200, "POST", "/sales/cash-sales/" + id + "/fiscal/retry", null);
        assertThat(second.get("fiscalStatus").asString()).isEqualTo("issued");
        assertThat(second.get("fiscalDocuments").size()).isEqualTo(1);
        assertThat(second.get("fiscalDocuments").get(0).get("fiscalNumber").isNull()).isFalse();
        // TIN locked while the fiscal document is issued
        ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/return", Map.of("reason", "TIN"));
        assertThat(as("POST", "/sales/cash-sales/" + id + "/amend", Map.of("customerTin", "1001")).status())
                .isEqualTo(409);
        ok(200, "POST", "/sales/cash-sales/" + id + "/amend", Map.of("remarks", "Checked"));
        ok(200, "POST", "/sales/cash-sales/" + id + "/fiscal/correct", Map.of("customerTin", "1001", "reason", "TIN"));
        JsonNode corrected = ok(200, "POST", "/sales/cash-sales/" + id + "/fiscal/retry", null);
        assertThat(corrected.get("customerTin").asString()).isEqualTo("1001");
        // void: fiscal cancel rejected → 409, nothing reversed; then it succeeds and the reversal happens once
        long rows = glRows(id);
        mockFiscal.script("reject");
        Res blocked = call("POST", "/sales/cash-sales/" + id + "/void", Map.of("reason", "Back on shelf", "goodsReturned",
                true), key());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(glRows(id)).isEqualTo(rows);
        assertThat(ok(200, "GET", "/sales/cash-sales/" + id, null).get("fiscalStatus").asString())
                .isEqualTo("cancellation_pending");
        // the provider rejects the cancellation: still pending, still nothing reversed
        ok(200, "POST", "/sales/cash-sales/" + id + "/fiscal/retry", null);
        assertThat(ok(200, "GET", "/sales/cash-sales/" + id, null).get("fiscalStatus").asString())
                .isEqualTo("cancellation_pending");
        assertThat(glRows(id)).isEqualTo(rows);
        // next attempt is accepted: the void goes through, once
        money("/sales/cash-sales/" + id + "/void", Map.of("reason", "Back on shelf", "goodsReturned", true), 200);
        assertThat(revenueOf(id)).isEqualByComparingTo("0");
        long cancels = jdbc.sql("SELECT count(*) FROM \"FiscalDocument\" WHERE \"submissionId\" = ? AND operation ="
                + " 'cancel' AND status = 'succeeded'").param(id).query(Long.class).single();
        assertThat(cancels).isEqualTo(1);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("T27/T28: segregation on; restricted showroom user stays in its branch; branch override → 403")
    void segregationAndScope() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\") VALUES (?,"
                + " ?, ?, false, ?, now())").params(role, demoOrg, "Showroom-" + sfx, new String[] {
                    Permissions.SALES__CASH_SALE_READ, Permissions.SALES__CASH_SALE_CREATE,
                    Permissions.SALES__CASH_SALE_CONFIRM, Permissions.SALES__CASH_SALE_SUBMIT }).update();
        String email = "showroom-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String userId = createUser(demoOrg, email, "Show@12345", role);
        jdbc.sql("INSERT INTO \"UserDataScope\" (id, \"organizationId\", \"userId\", \"branchId\") VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, userId, branch).update();
        String token = login("DEMO", email, "Show@12345");
        Map<String, Object> body = Map.of("lines", List.of(Map.of("productId", goods, "quantity", 1, "unitPrice", 35_000,
                "taxId", vat)));
        Res mine = call("POST", "/sales/cash-sales", body, token);
        assertThat(mine.status()).isEqualTo(201);
        assertThat(mine.body().get("branchId").asString()).isEqualTo(branch);
        Res other = call("POST", "/sales/cash-sales", Map.of("branchId", otherBranch, "lines", body.get("lines")), token);
        assertThat(other.status()).isEqualTo(403);
        // admin's submission in another branch is invisible to the showroom user
        ok(201, "POST", "/sales/branch-profiles", Map.of("branchId", otherBranch, "locationId", otherLocation,
                "cashAccountId", cashAccount, "walkInPartnerId", walkIn, "numberPrefix", "E" + sfx.substring(0, 3)
                        .replaceAll("[^A-Z0-9]", "X")));
        String foreign = ok(201, "POST", "/sales/cash-sales", Map.of("branchId", otherBranch, "lines", body.get("lines")))
                .get("id").asString();
        assertThat(call("GET", "/sales/cash-sales/" + foreign, null, token).status()).isEqualTo(404);
        // mobile money cannot be verified by the showroom role
        String id = mine.body().get("id").asString();
        Res p = call("POST", "/sales/cash-sales/" + id + "/payments", Map.of("method", "mobile_money", "provider", "mtn",
                "amount", 41_300, "reference", "MTN-SEG-" + sfx), token);
        String pid = p.body().get("payments").get(0).get("id").asString();
        assertThat(call("POST", "/sales/cash-sales/" + id + "/payments/" + pid + "/verify", Map.of(), token).status())
                .isEqualTo(403);
        // segregation: the collector cannot verify; the confirmer cannot approve
        jdbc.sql("UPDATE \"BranchSalesProfile\" SET \"requireSegregation\" = true WHERE id = ?").param(profile).update();
        try {
            String own = ok(201, "POST", "/sales/cash-sales", Map.of("branchId", branch, "lines", body.get("lines")))
                    .get("id").asString();
            String cash = pay(own, "cash", null, 41_300, null);
            assertThat(ok(200, "GET", "/sales/cash-sales/" + own, null).get("payments").get(0).get("verificationStatus")
                    .asString()).isEqualTo("pending");
            Res seg = as("POST", "/sales/cash-sales/" + own + "/payments/" + cash + "/verify", Map.of());
            assertThat(seg.status()).as(seg.raw().body() + " profile=" + jdbc.sql("SELECT \"requireSegregation\"::text"
                    + " FROM \"BranchSalesProfile\" WHERE id = ?").param(profile).query(String.class).single() + " sub="
                    + jdbc.sql("SELECT \"profileId\" FROM \"SalesSubmission\" WHERE id = ?").param(own).query(String.class)
                    .single() + " expected=" + profile).isEqualTo(403);
        } finally {
            jdbc.sql("UPDATE \"BranchSalesProfile\" SET \"requireSegregation\" = false WHERE id = ?").param(profile)
                    .update();
        }
    }

    @Test
    @DisplayName("ADR-S066: granite sold by the metre — size decides quantity, stock, Excel form and the invoice")
    void soldBySize() throws Exception {
        String granite = product("Granite kerb " + sfx, 20_000, 12_000);
        stock(granite, location, 100, 12_000);
        ok(200, "PUT", "/products/" + granite + "/sizing", Map.of("sizingMode", "length"));
        BigDecimal before = onHand(granite, location);
        JsonNode d = ok(201, "POST", "/sales/cash-sales", Map.of("branchId", branch, "lines", List.of(Map.of(
                "productId", granite, "pieces", 4, "length", 3, "unitPrice", 20_000, "taxId", vat))));
        String id = d.get("id").asString();
        JsonNode l = d.get("lines").get(0);
        assertThat(l.get("quantity").asDouble()).isEqualTo(12);
        assertThat(l.get("pieces").asDouble()).isEqualTo(4);
        assertThat(l.get("length").asDouble()).isEqualTo(3);
        assertThat(l.get("sizingUnit").asString()).isEqualTo("m");
        // 12 m × 20,000 × 1.18
        assertThat(d.get("grandTotal").asDouble()).isEqualTo(283_200);
        // The printable form also downloads as a PDF.
        java.net.http.HttpResponse<byte[]> pdf = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://localhost:" + port + "/api/v1/sales/cash-sales/" + id + "/pdf"))
                .header("Authorization", "Bearer " + adminToken).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(pdf.headers().firstValue("Content-Disposition").orElse("")).contains(".pdf");
        assertThat(new String(pdf.body(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(as("POST", "/sales/cash-sales", Map.of("branchId", branch, "lines", List.of(Map.of("productId",
                granite, "pieces", 4, "length", 0)))).status()).isEqualTo(400);
        pay(id, "cash", null, 283_200, null);
        money("/sales/cash-sales/" + id + "/confirm", null, 200);
        assertThat(onHand(granite, location)).isEqualByComparingTo(before.subtract(BigDecimal.valueOf(12)));
        ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/approve", null);
        String invoiceId = money("/sales/cash-sales/" + id + "/generate-invoice", null, 200).get("currentInvoiceId")
                .asString();
        JsonNode il = ok(200, "GET", "/invoices/" + invoiceId, null).get("lines").get(0);
        assertThat(il.get("quantity").asDouble()).isEqualTo(12);
        assertThat(il.get("pieces").asDouble()).isEqualTo(4);
        assertThat(il.get("length").asDouble()).isEqualTo(3);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("Branch cash sales reports: takings, tenders, register, products, processing agree with the sales")
    void branchCashSalesReports() {
        String c = partner("Report Buyer " + sfx);
        String a = confirmedCashSale(c, 2); // 70 000 + 12 600 VAT
        String b = approved(c, 3); // 105 000 + 18 900 VAT
        money("/sales/cash-sales/" + b + "/generate-invoice", null, 200);
        String d = draft(c, 1); // a draft never counts as takings
        String q = "from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now() + "&branchId=" + branch
                + "&partnerId=" + c;

        JsonNode sum = ok(200, "GET", "/sales/report-center/run/branch-cash-summary?" + q, null);
        assertThat(sum.get("rows").size()).isEqualTo(1);
        JsonNode row = sum.get("rows").get(0);
        assertThat(row.get("sales").asInt()).isEqualTo(2);
        assertThat(row.get("net").asDouble()).isEqualTo(175_000);
        assertThat(row.get("tax").asDouble()).isEqualTo(31_500);
        assertThat(row.get("total").asDouble()).isEqualTo(206_500);
        assertThat(row.get("t_cash").asDouble()).isEqualTo(206_500);
        assertThat(row.get("_drill").get("report").asString()).isEqualTo("branch-cash-daily");

        JsonNode daily = ok(200, "GET", "/sales/report-center/run/branch-cash-daily?" + q, null);
        assertThat(daily.get("totals").get("total").asDouble()).isEqualTo(206_500);
        assertThat(daily.get("totals").get("b_" + branch).asDouble()).isEqualTo(206_500);

        JsonNode tenders = ok(200, "GET", "/sales/report-center/run/branch-cash-tenders?" + q, null);
        assertThat(tenders.get("totals").get("amount").asDouble()).isEqualTo(206_500);

        JsonNode reg = ok(200, "GET", "/sales/report-center/run/branch-cash-register?" + q, null);
        assertThat(reg.get("rows").size()).isEqualTo(3);
        JsonNode drafts = ok(200, "GET", "/sales/report-center/run/branch-cash-register?" + q + "&status=draft", null);
        assertThat(drafts.get("rows").size()).isEqualTo(1);
        assertThat(drafts.get("rows").get(0).get("_route").asString()).isEqualTo("/sales/cash-sales/" + d);
        JsonNode inv = ok(200, "GET", "/sales/report-center/run/branch-cash-register?" + q + "&status=invoiced", null);
        assertThat(inv.get("rows").size()).isEqualTo(1);
        assertThat(inv.get("rows").get(0).get("invoice").isNull()).isFalse();
        assertThat(as("GET", "/sales/report-center/run/branch-cash-register?" + q + "&status=nope", null).status())
                .isEqualTo(400);

        JsonNode prod = ok(200, "GET", "/sales/report-center/run/branch-cash-products?" + q, null);
        assertThat(prod.get("totals").get("quantity").asDouble()).isEqualTo(5);
        assertThat(prod.get("totals").get("net").asDouble()).isEqualTo(175_000);
        assertThat(prod.get("totals").get("cost").asDouble()).isEqualTo(105_000);

        JsonNode proc = ok(200, "GET", "/sales/report-center/run/branch-cash-processing?" + q, null);
        JsonNode p = proc.get("rows").get(0);
        assertThat(p.get("not_submitted").asInt()).isEqualTo(1);
        assertThat(p.get("invoiced").asInt()).isEqualTo(1);
        assertThat(p.get("awaiting").asDouble()).isEqualTo(82_600);

        for (String k : List.of("branch-cash-salespeople", "branch-cash-exceptions")) {
            assertThat(ok(200, "GET", "/sales/report-center/run/" + k + "?" + q, null).get("key").asString())
                    .isEqualTo(k);
        }
        assertThat(a).isNotNull();
    }

    @Test
    @DisplayName("T29/T30: tenant isolation, 403 per route, Idempotency-Key required on money routes")
    void isolationAndAuthorization() {
        String id = draft(null, 1);
        assertThat(call("GET", "/sales/cash-sales/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(as("POST", "/sales/cash-sales/" + id + "/confirm", null).status()).isEqualTo(400);
        assertThat(assertAuthorizationMatrix(CashSaleController.Submissions.class, CashSaleController.Profiles.class,
                CashSaleController.InvoiceSource.class))
                .isGreaterThan(20);
    }

    @Test
    @DisplayName("PLM sell rule (ADR-S086 §6): a product not released for sale cannot be put on a cash sale; lines the"
            + " draft already had stay editable after the product is retired")
    void plmSellRule() {
        String design = product("Prototype block " + UUID.randomUUID(), 35_000, 21_000);
        ok(201, "POST", "/manufacturing/plm/items", Map.of("productId", design, "lifecycleState", "design"));
        String code = jdbc.sql("SELECT code FROM \"Product\" WHERE id = ?").param(design).query(String.class).single();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branchId", branch);
        body.put("customerName", "ABC Construction");
        body.put("lines", List.of(Map.of("productId", design, "quantity", 1, "unitPrice", 35_000, "taxId", vat)));
        Res refused = as("POST", "/sales/cash-sales", body);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).isEqualTo("Product " + code + " is in design and cannot"
                + " be sold.");

        String released = product("Released block " + UUID.randomUUID(), 35_000, 21_000);
        stock(released, location, 10, 21_000);
        String item = ok(201, "POST", "/manufacturing/plm/items", Map.of("productId", released, "lifecycleState",
                "released")).get("id").asString();
        body.put("lines", List.of(Map.of("productId", released, "quantity", 1, "unitPrice", 35_000, "taxId", vat)));
        String id = ok(201, "POST", "/sales/cash-sales", body).get("id").asString();
        // Adding the unreleased product to the draft is refused; the draft's own line stays editable once retired.
        assertThat(as("PATCH", "/sales/cash-sales/" + id, Map.of("lines", List.of(Map.of("productId", released,
                "quantity", 1, "unitPrice", 35_000, "taxId", vat), Map.of("productId", design, "quantity", 1,
                "unitPrice", 35_000, "taxId", vat)))).status()).isEqualTo(400);
        jdbc.sql("UPDATE \"PlmItem\" SET \"lifecycleState\" = 'obsolete' WHERE id = ?").param(item).update();
        ok(200, "PATCH", "/sales/cash-sales/" + id, Map.of("lines", List.of(Map.of("productId", released, "quantity",
                2, "unitPrice", 35_000, "taxId", vat))));
    }

    // ------------------------------------------------------------------ CS-5 (sales readiness R5)

    String liability(String name) {
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        String[] id = new String[1];
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> id[0] = accounts.ensureByCode("22" + num(), name + " " + sfx, "current_liability", null)));
        return id[0];
    }

    String tax(String name, int rate, String accountId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"accountId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, name + " " + sfx, name.substring(0, 2) + num(),
                        rate, accountId).update();
        return id;
    }

    @Test
    @DisplayName("T5: several tax categories — VAT credited per tax account once; the invoice reclassifies, no second VAT")
    void severalTaxCategories() {
        String vatAccount = liability("Output VAT");
        String exciseAccount = liability("Excise duty");
        String vat18 = tax("VAT18", 18, vatAccount);
        String excise10 = tax("EX10", 10, exciseAccount);
        String tiles = product("Tiles " + sfx, 10_000, 6_000);
        String sand = product("Sand " + sfx, 20_000, 12_000);
        stock(tiles, location, 100, 6_000);
        stock(sand, location, 100, 12_000);
        String c = partner("Multi-tax buyer " + sfx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branchId", branch);
        body.put("partnerId", c);
        body.put("customerName", "Multi-tax buyer");
        body.put("lines", List.of(
                Map.of("productId", goods, "quantity", 2, "unitPrice", 35_000, "taxId", vat18),
                Map.of("productId", tiles, "quantity", 5, "unitPrice", 10_000, "taxId", excise10),
                Map.of("productId", sand, "quantity", 1, "unitPrice", 20_000)));
        String id = ok(201, "POST", "/sales/cash-sales", body).get("id").asString();
        JsonNode d = ok(200, "GET", "/sales/cash-sales/" + id, null);
        assertThat(d.get("subtotal").asDouble()).isEqualTo(140_000);
        assertThat(d.get("taxTotal").asDouble()).isEqualTo(17_600);
        assertThat(d.get("grandTotal").asDouble()).isEqualTo(157_600);
        verify(id, pay(id, "cash", null, 157_600, null));
        money("/sales/cash-sales/" + id + "/confirm", null, 200);
        // P3 accrual: each tax account carries its own tax, revenue the net
        assertThat(balance(vatAccount)).isEqualByComparingTo("-12600");
        assertThat(balance(exciseAccount)).isEqualByComparingTo("-5000");
        assertThat(revenueOf(id)).isEqualByComparingTo("140000");
        assertThat(unbilled(c)).isEqualByComparingTo("157600");
        ok(200, "POST", "/sales/cash-sales/" + id + "/submit", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/start-review", null);
        ok(200, "POST", "/sales/cash-sales/" + id + "/approve", null);
        String invoiceId = money("/sales/cash-sales/" + id + "/generate-invoice", null, 200).get("currentInvoiceId")
                .asString();
        JsonNode inv = ok(200, "GET", "/invoices/" + invoiceId, null);
        assertThat(inv.get("totalAmount").asDouble()).isEqualTo(157_600);
        assertThat(inv.get("amountResidual").asDouble()).isZero();
        // the invoice only reclassified: tax accounts, revenue unchanged; unbilled, deposit and AR back to 0
        assertThat(balance(vatAccount)).isEqualByComparingTo("-12600");
        assertThat(balance(exciseAccount)).isEqualByComparingTo("-5000");
        assertThat(revenueOf(id)).isEqualByComparingTo("140000");
        assertThat(unbilled(c)).isEqualByComparingTo("0");
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo("0");
        assertThat(partnerBalance("accounts_receivable", c)).isEqualByComparingTo("0");
        ledger.receivableTiesOut(c);
        ledger.trialBalanceZero();
    }

    BigDecimal depositSubledger(String partner) {
        return jdbc.sql("SELECT COALESCE(sum(amount - \"appliedAmount\" - \"refundedAmount\"), 0) FROM"
                + " \"CustomerDeposit\" WHERE \"partnerId\" = ? AND status = 'open'").param(partner)
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("T31: partial deposit apply + refund — deposit subledger = GL, invoice residual right, over-refund refused")
    void partialDepositApplyAndRefund() {
        String c = partner("Advance payer " + sfx);
        String dep = money("/customer-deposits", Map.of("partnerId", c, "amount", 500_000, "paymentMethod", "cash"), 200)
                .get("id").asString();
        assertThat(partnerBalance("customer_deposit", c)).isEqualByComparingTo("-500000");
        String invoice = ok(200, "POST", "/invoices", Map.of("partnerId", c, "issueDate", LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString(),
                "lines", List.of(Map.of("description", "Site works", "quantity", 1, "unitPrice", 300_000)))).get("id")
                .asString();
        money("/invoices/" + invoice + "/post", null, 200);
        JsonNode applied = money("/customer-deposits/" + dep + "/apply", Map.of("documentId", invoice, "amount", 200_000),
                200);
        assertThat(applied.get("status").asString()).isEqualTo("open");
        assertThat(ok(200, "GET", "/invoices/" + invoice, null).get("amountResidual").asDouble()).isEqualTo(100_000);
        JsonNode refunded = money("/customer-deposits/" + dep + "/refund", Map.of("amount", 100_000, "paymentMethod",
                "cash", "reason", "Over-paid"), 200);
        assertThat(refunded.get("available").asDouble()).isEqualTo(200_000);
        assertThat(depositSubledger(c)).isEqualByComparingTo("200000");
        assertThat(partnerBalance("customer_deposit", c).negate()).isEqualByComparingTo(depositSubledger(c));
        // more than is left is refused, and nothing moves
        assertThat(call("POST", "/customer-deposits/" + dep + "/refund", Map.of("amount", 200_001, "paymentMethod",
                "cash"), key()).status()).isEqualTo(400);
        assertThat(call("POST", "/customer-deposits/" + dep + "/apply", Map.of("documentId", invoice, "amount", 100_001),
                key()).status()).isEqualTo(400);
        assertThat(depositSubledger(c)).isEqualByComparingTo("200000");
        assertThat(partnerBalance("customer_deposit", c).negate()).isEqualByComparingTo("200000");
        // the rest settles the invoice exactly
        money("/customer-deposits/" + dep + "/apply", Map.of("documentId", invoice, "amount", 100_000), 200);
        assertThat(ok(200, "GET", "/invoices/" + invoice, null).get("amountResidual").asDouble()).isZero();
        assertThat(partnerBalance("customer_deposit", c).negate()).isEqualByComparingTo(depositSubledger(c));
        assertThat(depositSubledger(c)).isEqualByComparingTo("100000");
        ledger.receivableTiesOut(c);
        ledger.trialBalanceZero();
    }
}
