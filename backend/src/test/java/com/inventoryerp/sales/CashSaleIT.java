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
        // unverified money never posts
        assertThat(call("POST", "/sales/cash-sales/" + id + "/confirm", null, key()).status()).isEqualTo(400);
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
        assertThat(call("POST", "/sales/cash-sales/" + id + "/confirm", null, key()).status()).isEqualTo(400);
        verify(id, momo);
        assertThat(call("POST", "/sales/cash-sales/" + id + "/confirm", null, key()).status()).isEqualTo(400);
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
                    "amount", 500, "reference", "BT-" + sfx));
            JsonNode last = m.get("payments").get(m.get("payments").size() - 1);
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
        JsonNode timeline = ok(200, "GET", "/sales/cash-sales/" + id + "/timeline", null);
        assertThat(timeline.findValuesAsString("action")).contains("delivery_dispatched", "delivery_delivered",
                "delivery_update");
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
    @DisplayName("T29/T30: tenant isolation, 403 per route, Idempotency-Key required on money routes")
    void isolationAndAuthorization() {
        String id = draft(null, 1);
        assertThat(call("GET", "/sales/cash-sales/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(as("POST", "/sales/cash-sales/" + id + "/confirm", null).status()).isEqualTo(400);
        assertThat(assertAuthorizationMatrix(CashSaleController.Submissions.class, CashSaleController.Profiles.class))
                .isGreaterThan(20);
    }
}
