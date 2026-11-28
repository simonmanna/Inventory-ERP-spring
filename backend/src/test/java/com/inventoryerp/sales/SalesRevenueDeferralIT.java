package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesRevenueDeferralService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C2b (§3.6; AD-2 = A, AD-16 = dispatch): revenue of goods invoiced before they leave is held as a contract
 * liability and released as they are dispatched (DF-1…DF-5, invoice cancel, FX, authorization). Everything through the
 * HTTP API; each scenario has its own customer, so balances by partner are exact.
 */
class SalesRevenueDeferralIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    AccountResolverService accounts;

    @Autowired
    SalesRevenueDeferralService deferrals;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    SalesLedgerAssertions ledger;
    String adminId;
    String income;
    String goods;
    String stone;
    String yard;
    String vat;
    String vatAccount;

    @BeforeAll
    void fixtures() {
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        // A category with its own income account: the deferral must move revenue on the account the invoice used.
        String revenueCategory = jdbc.sql("SELECT \"categoryId\" FROM \"Account\" WHERE id = ?")
                .param(ledger.mapped("sales_revenue")).query(String.class).single();
        income = ok(201, "POST", "/accounts", Map.of("code", "4" + ThreadLocalRandom.current().nextInt(100000, 999999),
                "name", "Deferral sales " + sfx, "categoryId", revenueCategory)).get("id").asString();
        String category = ok(201, "POST", "/product-categories", Map.of("name", "Deferral " + sfx, "incomeAccountId",
                income)).get("id").asString();
        goods = product("Deferral goods " + sfx, "ordered", category);
        stone = product("Deferral stone " + sfx, "delivered", category);
        yard = ok(201, "POST", "/inventory/locations", Map.of("code", "DFY-" + sfx, "name", "Deferral yard " + sfx,
                "type", "warehouse")).get("id").asString();
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> {
                    stock.receive(StockMove.of(goods, yard, new BigDecimal("1000")).unitCost(new BigDecimal("60")));
                    stock.receive(StockMove.of(stone, yard, new BigDecimal("1000")).unitCost(new BigDecimal("60")));
                }));
    }

    @BeforeEach
    void freshVat() {
        String[] id = new String[1];
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> id[0] = accounts.ensureByCode("229" + ThreadLocalRandom.current().nextInt(
                        10000, 99999), "Output VAT DF " + sfx, "current_liability", null)));
        vatAccount = id[0];
        vat = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"accountId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 18, ?, now())").params(vat, demoOrg, "VAT 18 DF " + UUID.randomUUID().toString()
                        .substring(0, 8), "DF" + UUID.randomUUID().toString().substring(0, 6), vatAccount).update();
    }

    // ------------------------------------------------------------------ helpers

    String product(String name, String policy, String category) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"categoryId\", \"updatedAt\") VALUES (?, ?, ?,"
                + " ?, 'stockable', 100, 60, true, ?, ?, now())").params(id, demoOrg, "DF-" + UUID.randomUUID().toString()
                        .substring(0, 8), name, policy, category).update();
        return id;
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "DFC-" + UUID.randomUUID().toString()
                        .substring(0, 8), "DF cust " + sfx).update();
        return id;
    }

    String user(String name, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, name + " " + sfx, permissions).update();
        String email = name + "-" + sfx + "@demo.test";
        createUser(demoOrg, email, "Deferral@12345", role);
        return login("DEMO", email, "Deferral@12345");
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    Res attempt(String path, Object body) {
        return call("POST", path, body, key(adminToken));
    }

    /** A confirmed credit order of {@code qty} × 100.00 + 18 % VAT. */
    String order(String c, String product, int qty) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "locationId", yard, "paymentMode", "credit",
                "lines", List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 100, "taxId", vat))))
                .get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        return so;
    }

    String invoice(String so) {
        String id = money("/sales/orders/" + so + "/invoices", Map.of("mode", "regular")).get("id").asString();
        money("/invoices/" + id + "/post", null);
        drainOutboxFully();
        return id;
    }

    JsonNode trip(String so, int qty) {
        return ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UDF 1", "driverName",
                "Okello", "lines", List.of(Map.of("lineId", lineId(so), "quantity", qty))));
    }

    void issue(JsonNode trip) {
        money("/sales/deliveries/" + trip.get("id").asString() + "/issue", null);
        drainOutboxFully();
    }

    void pod(JsonNode trip, int delivered, int rejected) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("loadLineId", trip.get("lines").get(0).get("id").asString());
        line.put("deliveredQty", delivered);
        if (rejected > 0) {
            line.put("rejectedQty", rejected);
            line.put("reason", "customer_rejected");
        }
        money("/sales/deliveries/" + trip.get("id").asString() + "/confirm", Map.of("receiverName", "Site agent",
                "lines", List.of(line)));
        drainOutboxFully();
    }

    JsonNode sale(String so) {
        return ok(200, "GET", "/sales/orders/" + so, null);
    }

    String lineId(String so) {
        return sale(so).get("lines").get(0).get("id").asString();
    }

    double qtyDeferred(String so) {
        return sale(so).get("lines").get(0).get("qtyDeferred").asDouble();
    }

    double revenueDeferred(String so) {
        return sale(so).get("revenueDeferred").asDouble();
    }

    /** Posted balance (debit − credit, functional currency) of an account for one customer. */
    BigDecimal balance(String account, String partner) {
        return jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed') AND l.\"accountId\" = ? AND l.\"partnerId\" = ?").params(demoOrg, account,
                        partner).query(BigDecimal.class).single();
    }

    BigDecimal revenue(String partner) {
        return balance(income, partner);
    }

    BigDecimal liability(String partner) {
        return balance(ledger.mapped("unearned_revenue"), partner);
    }

    BigDecimal vatBalance() {
        return jdbc.sql("SELECT COALESCE(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed') AND l.\"accountId\" = ?").params(demoOrg, vatAccount).query(BigDecimal.class)
                .single();
    }

    int entries(String sourceType, String so) {
        return ledger.entries(sourceType, so).size();
    }

    void resync(String so) {
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> deferrals.resync(so, "test")));
    }

    // ------------------------------------------------------------------ DF

    @Test
    @DisplayName("DF-1 an invoice posted before dispatch defers its revenue to the contract liability; tax and the"
            + " receivable are booked at once")
    void invoiceBeforeDispatchDefers() {
        String c = customer();
        String so = order(c, goods, 10);
        String inv = invoice(so);

        assertThat(qtyDeferred(so)).isEqualTo(10);
        assertThat(sale(so).get("lines").get(0).get("amountDeferred").asDouble()).isEqualTo(1000);
        assertThat(revenueDeferred(so)).isEqualTo(1000);
        assertThat(revenue(c)).as("revenue on the category's income account").isEqualByComparingTo("0");
        assertThat(liability(c)).isEqualByComparingTo("-1000");
        assertThat(vatBalance()).isEqualByComparingTo("-180");
        assertThat(ledger.balance("accounts_receivable", c)).isEqualByComparingTo("1180");
        assertThat(entries("sales_revenue_deferral", so)).isEqualTo(1);
        ledger.entriesBalanced("sales_revenue_deferral", so);
        Map<String, Object> m = jdbc.sql("SELECT m.kind, m.quantity, m.amount, m.reason, m.\"sourceType\", m.\"sourceId\","
                + " m.\"journalEntryId\" FROM \"SalesRevenueMovement\" m JOIN \"SalesRevenueDeferral\" r ON r.id ="
                + " m.\"deferralId\" WHERE r.\"orderId\" = ?").param(so).query().singleRow();
        assertThat(m).containsEntry("kind", "defer").containsEntry("reason", "invoice_posted")
                .containsEntry("sourceType", "invoice").containsEntry("sourceId", inv);
        assertThat((BigDecimal) m.get("quantity")).isEqualByComparingTo("10");
        assertThat((BigDecimal) m.get("amount")).isEqualByComparingTo("1000");
        assertThat(m.get("journalEntryId")).isNotNull();
        // the entry carries the invoice's date
        Instant invoiceDate = jdbc.sql("SELECT e.\"postingDate\" FROM \"JournalEntry\" e JOIN \"Document\" d ON"
                + " d.\"journalEntryId\" = e.id WHERE d.id = ?").param(inv).query(java.sql.Timestamp.class).single()
                .toInstant();
        Instant deferralDate = jdbc.sql("SELECT \"postingDate\" FROM \"JournalEntry\" WHERE id = ?")
                .param(m.get("journalEntryId")).query(java.sql.Timestamp.class).single().toInstant();
        assertThat(deferralDate).isEqualTo(invoiceDate);

        resync(so);
        assertThat(entries("sales_revenue_deferral", so)).as("a re-sync posts nothing more").isEqualTo(1);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("DF-2 each dispatch releases the revenue of what left, once; the proof of delivery moves nothing")
    void releasePerDispatch() {
        String c = customer();
        String so = order(c, goods, 10);
        invoice(so);

        JsonNode first = trip(so, 4);
        issue(first);
        assertThat(qtyDeferred(so)).isEqualTo(6);
        assertThat(revenue(c)).isEqualByComparingTo("-400");
        assertThat(liability(c)).isEqualByComparingTo("-600");
        assertThat(entries("sales_revenue_release", so)).isEqualTo(1);
        pod(first, 4, 0);
        assertThat(qtyDeferred(so)).isEqualTo(6);
        assertThat(entries("sales_revenue_release", so)).as("a full delivery at the POD releases nothing more")
                .isEqualTo(1);

        JsonNode second = trip(so, 6);
        issue(second);
        assertThat(qtyDeferred(so)).isEqualTo(0);
        assertThat(revenueDeferred(so)).isEqualTo(0);
        assertThat(revenue(c)).isEqualByComparingTo("-1000");
        assertThat(liability(c)).isEqualByComparingTo("0");
        assertThat(entries("sales_revenue_release", so)).isEqualTo(2);
        pod(second, 6, 0);
        assertThat(sale(so).get("status").asString()).isEqualTo("done");

        resync(so);
        assertThat(entries("sales_revenue_release", so)).isEqualTo(2);
        assertThat(entries("sales_revenue_deferral", so)).isEqualTo(1);
        ledger.entriesBalanced("sales_revenue_release", so);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("DF-3 goods brought back defer their revenue again: rejected at the delivery, or the whole load")
    void bringBackDefersAgain() {
        String c = customer();
        String so = order(c, goods, 10);
        invoice(so);
        JsonNode t = trip(so, 10);
        issue(t);
        assertThat(qtyDeferred(so)).isEqualTo(0);
        assertThat(revenue(c)).isEqualByComparingTo("-1000");
        pod(t, 7, 3);
        assertThat(qtyDeferred(so)).isEqualTo(3);
        assertThat(revenueDeferred(so)).isEqualTo(300);
        assertThat(revenue(c)).isEqualByComparingTo("-700");
        assertThat(liability(c)).isEqualByComparingTo("-300");
        assertThat(entries("sales_revenue_deferral", so)).isEqualTo(2);

        String c2 = customer();
        String so2 = order(c2, goods, 5);
        invoice(so2);
        JsonNode t2 = trip(so2, 5);
        issue(t2);
        assertThat(revenue(c2)).isEqualByComparingTo("-500");
        money("/sales/deliveries/" + t2.get("id").asString() + "/return", Map.of("reason", "Site closed, load brought"
                + " back"));
        drainOutboxFully();
        assertThat(qtyDeferred(so2)).isEqualTo(5);
        assertThat(revenue(c2)).isEqualByComparingTo("0");
        assertThat(liability(c2)).isEqualByComparingTo("-500");
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("DF-4 credit-undelivered: posted quantity credit notes released against the contract liability, then"
            + " the line closes short and the sale is done")
    void creditUndelivered() {
        String c = customer();
        String so = order(c, goods, 10);
        String inv = invoice(so);
        JsonNode t = trip(so, 6);
        issue(t);
        pod(t, 6, 0);
        String line = lineId(so);
        String path = "/sales/orders/" + so + "/lines/" + line + "/credit-undelivered";
        assertThat(qtyDeferred(so)).isEqualTo(4);

        Map<String, String> noKey = new LinkedHashMap<>();
        noKey.put("Authorization", "Bearer " + adminToken);
        Res missing = call("POST", path, Map.of("reason", "Customer cancelled the rest"), noKey);
        assertThat(missing.status()).as(missing.raw().body()).isEqualTo(400);
        Res blank = attempt(path, Map.of("reason", " "));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.raw().body()).contains("reason should not be empty");

        JsonNode out = money(path, Map.of("reason", "Customer cancelled the rest"));
        drainOutboxFully();
        assertThat(out.get("lines").get(0).get("closedShortAt").isNull()).isFalse();
        JsonNode s = sale(so);
        assertThat(s.get("lines").get(0).get("qtyDeferred").asDouble()).isEqualTo(0);
        assertThat(s.get("lines").get(0).get("qtyCredited").asDouble()).isEqualTo(4);
        assertThat(s.get("status").asString()).isEqualTo("done");
        Map<String, Object> cn = jdbc.sql("SELECT d.status::text AS status, d.\"reversedDocumentId\", d.\"totalAmount\","
                + " d.data ->> 'creditKind' AS kind, k.quantity FROM \"SalesInvoiceLink\" k JOIN \"Document\" d ON d.id ="
                + " k.\"documentId\" WHERE k.\"orderId\" = ? AND k.kind = 'credit_note'").param(so).query().singleRow();
        assertThat(cn).containsEntry("status", "posted").containsEntry("reversedDocumentId", inv)
                .containsEntry("kind", "quantity");
        assertThat((BigDecimal) cn.get("totalAmount")).isEqualByComparingTo("472");
        assertThat((BigDecimal) cn.get("quantity")).isEqualByComparingTo("4");
        // net: Dr contract liability 400 + Dr VAT 72 / Cr receivable 472; revenue keeps only what was delivered
        assertThat(revenue(c)).isEqualByComparingTo("-600");
        assertThat(liability(c)).isEqualByComparingTo("0");
        assertThat(vatBalance()).isEqualByComparingTo("-108");
        assertThat(ledger.balance("accounts_receivable", c)).isEqualByComparingTo("708");
        ledger.receivableTiesOut(c);
        ledger.trialBalanceZero();

        Res again = attempt(path, Map.of("reason", "again"));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.raw().body()).as("the sale is done").contains("Only confirmed orders can be closed short");
    }

    @Test
    @DisplayName("DF-5 closing short a line whose invoiced goods were not delivered is refused while revenue is"
            + " deferred; an uninvoiced remainder closes short as before")
    void closeShortRefusedWhileDeferred() {
        String c = customer();
        String so = order(c, goods, 10);
        invoice(so);
        JsonNode t = trip(so, 6);
        issue(t);
        pod(t, 6, 0);
        Res refused = attempt("/sales/orders/" + so + "/lines/" + lineId(so) + "/close-short", Map.of("reason",
                "Rest not needed"));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("4 invoiced but not delivered");
        assertThat(sale(so).get("lines").get(0).get("closedShortAt").isNull()).isTrue();
        assertThat(qtyDeferred(so)).isEqualTo(4);

        String c2 = customer();
        String so2 = order(c2, goods, 10);
        JsonNode t2 = trip(so2, 6);
        issue(t2);
        pod(t2, 6, 0);
        Res closed = attempt("/sales/orders/" + so2 + "/lines/" + lineId(so2) + "/close-short", Map.of("reason",
                "Rest not needed"));
        assertThat(closed.status()).as(closed.raw().body()).isEqualTo(200);
    }

    @Test
    @DisplayName("an order delivered first and invoiced after defers nothing")
    void deliveredThenInvoicedDefersNothing() {
        String c = customer();
        String so = order(c, stone, 5);
        JsonNode t = trip(so, 5);
        issue(t);
        pod(t, 5, 0);
        invoice(so);
        assertThat(revenueDeferred(so)).isEqualTo(0);
        assertThat(entries("sales_revenue_deferral", so)).isZero();
        assertThat(revenue(c)).isEqualByComparingTo("-500");
    }

    @Test
    @DisplayName("cancelling the invoice drops the revenue it deferred, on the date the ledger reverses the invoice")
    void invoiceCancelReleases() {
        String c = customer();
        String so = order(c, goods, 10);
        String inv = invoice(so);
        assertThat(qtyDeferred(so)).isEqualTo(10);
        money("/invoices/" + inv + "/cancel", Map.of("reason", "Wrong customer"));
        drainOutboxFully();
        assertThat(qtyDeferred(so)).isEqualTo(0);
        assertThat(revenue(c)).isEqualByComparingTo("0");
        assertThat(liability(c)).isEqualByComparingTo("0");
        assertThat(ledger.balance("accounts_receivable", c)).isEqualByComparingTo("0");
        assertThat(vatBalance()).isEqualByComparingTo("0");
        List<String> release = ledger.entries("sales_revenue_release", so);
        assertThat(release).hasSize(1);
        Instant invoiceDate = jdbc.sql("SELECT e.\"postingDate\" FROM \"JournalEntry\" e JOIN \"Document\" d ON"
                + " d.\"journalEntryId\" = e.id WHERE d.id = ?").param(inv).query(java.sql.Timestamp.class).single()
                .toInstant();
        assertThat(jdbc.sql("SELECT \"postingDate\" FROM \"JournalEntry\" WHERE id = ?").param(release.getFirst())
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(invoiceDate);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("a foreign-currency invoice defers and releases at its own rate, never revalued")
    void foreignCurrencyAtInvoiceRate() {
        String c = customer();
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "locationId", yard, "paymentMode", "credit",
                "currencyCode", "USD", "exchangeRate", 3700, "lines", List.of(Map.of("productId", goods, "quantity", 4,
                        "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        invoice(so);
        assertThat(revenueDeferred(so)).isEqualTo(4 * 100 * 3700);
        assertThat(liability(c)).isEqualByComparingTo("-1480000");
        assertThat(revenue(c)).isEqualByComparingTo("0");
        JsonNode t = trip(so, 4);
        issue(t);
        assertThat(revenueDeferred(so)).isEqualTo(0);
        assertThat(liability(c)).isEqualByComparingTo("0");
        assertThat(revenue(c)).isEqualByComparingTo("-1480000");
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("credit-undelivered needs both the close-short and the credit-note permission")
    void creditUndeliveredAuthorization() {
        String onlyClose = user("df-close", "sales:order:read", "sales:order:read_all", "sales:order:close_short");
        String onlyCredit = user("df-credit", "sales:order:read", "sales:order:read_all", "sales:credit_note:write");
        for (String token : List.of(onlyClose, onlyCredit)) {
            Res r = call("POST", "/sales/orders/" + UUID.randomUUID() + "/lines/" + UUID.randomUUID()
                    + "/credit-undelivered", Map.of("reason", "x"), key(token));
            assertThat(r.status()).as(r.raw().body()).isEqualTo(403);
        }
    }
}
