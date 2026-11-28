package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C3 (§5): the branch cash sale as a Sale of type {@code cash}, through the HTTP API. Drafted from the branch
 * profile; tender lines captured and verified; one confirm transaction (F1) that hands the goods over at the counter,
 * posts the invoice, one receipt per tender and the stock issue, and queues the fiscal document. Exact journals per
 * customer (no 1215 / 2216 / deposit movement, AD-6), payment rules (PM), handover (PH), atomicity (FI, TP-1),
 * concurrency (CC) and authorization.
 */
class SalesCashSaleIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    AccountResolverService accounts;

    @Autowired
    Flyway flyway;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    SalesLedgerAssertions ledger;
    String adminId;
    String income;
    String category;
    String cashAccount;
    String mtnAccount;
    String bankAccount;
    String cardAccount;
    String walkIn;
    String branch;
    String location;
    String profile;
    String segBranch;
    String segLocation;
    String goods;
    String vat;
    String vatAccount;

    @BeforeAll
    void fixtures() {
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        // A category with its own income account, so revenue per customer is exact.
        String revenueCategory = jdbc.sql("SELECT \"categoryId\" FROM \"Account\" WHERE id = ?")
                .param(ledger.mapped("sales_revenue")).query(String.class).single();
        income = ok(201, "POST", "/accounts", Map.of("code", "4" + rnd(), "name", "Counter sales " + sfx, "categoryId",
                revenueCategory)).get("id").asString();
        category = ok(201, "POST", "/product-categories", Map.of("name", "Counter " + sfx, "incomeAccountId", income))
                .get("id").asString();
        inTenant(() -> {
            cashAccount = accounts.ensureByCode("10" + rnd(), "Counter cash " + sfx, "cash", null);
            mtnAccount = accounts.ensureByCode("10" + rnd(), "Counter MTN " + sfx, "mobile_money", null);
            bankAccount = accounts.ensureByCode("10" + rnd(), "Counter bank " + sfx, "bank", null);
            cardAccount = accounts.ensureByCode("10" + rnd(), "Counter card clearing " + sfx, "bank", null);
        });
        walkIn = partner("Walk-in " + sfx, null);
        branch = branch("Counter " + sfx);
        location = location("Counter floor " + sfx, branch);
        segBranch = branch("Segregated counter " + sfx);
        segLocation = location("Segregated floor " + sfx, segBranch);
        goods = product("Cement " + sfx, "warn");
        receive(goods, location, 100_000);
        receive(goods, segLocation, 10_000);
        profile = profile(branch, location, false);
        profile(segBranch, segLocation, true);
    }

    @BeforeEach
    void freshVat() {
        String[] id = new String[1];
        inTenant(() -> id[0] = accounts.ensureByCode("229" + rnd(), "Output VAT CS " + sfx, "current_liability", null));
        vatAccount = id[0];
        vat = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"accountId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 18, ?, now())").params(vat, demoOrg, "VAT 18 CS " + UUID.randomUUID().toString()
                        .substring(0, 8), "CS" + UUID.randomUUID().toString().substring(0, 6), vatAccount).update();
    }

    // ------------------------------------------------------------------ fixtures

    static String rnd() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    void inTenant(Runnable r) {
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> r.run()));
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

    String partner(String name, String tin) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"taxNumber\", phone,"
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, ?, '0772000111', now())").params(id, demoOrg, "CSC-"
                        + UUID.randomUUID().toString().substring(0, 8), name, tin).update();
        return id;
    }

    String customer() {
        return partner("Counter customer " + UUID.randomUUID().toString().substring(0, 6), null);
    }

    String product(String name, String stockPolicy) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"categoryId\", \"stockPolicy\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable', 100, 60, true, ?, ?::\"PosStockPolicy\", now())").params(id, demoOrg, "CSP-"
                        + UUID.randomUUID().toString().substring(0, 8), name, category, stockPolicy).update();
        return id;
    }

    void receive(String product, String loc, int qty) {
        inTenant(() -> stock.receive(StockMove.of(product, loc, new BigDecimal(qty)).unitCost(new BigDecimal("60"))));
    }

    String profile(String b, String loc, boolean segregation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branchId", b);
        body.put("locationId", loc);
        body.put("cashAccountId", cashAccount);
        body.put("momoMtnAccountId", mtnAccount);
        body.put("bankClearingAccountId", bankAccount);
        body.put("walkInPartnerId", walkIn);
        body.put("numberPrefix", (segregation ? "S" : "C") + sfx.substring(0, 4).toUpperCase().replaceAll("[^A-Z0-9]",
                "X"));
        body.put("requireSegregation", segregation);
        return ok(201, "POST", "/sales/branch-profiles", body).get("id").asString();
    }

    /** A user holding only {@code permissions}, working at {@code branchId} (null: none); returns the token. */
    String user(String name, String branchId, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, name + " " + sfx, permissions).update();
        String email = name + "-" + sfx + "@demo.test";
        String id = createUser(demoOrg, email, "Counter@12345", role);
        if (branchId != null) {
            jdbc.sql("UPDATE \"User\" SET \"defaultBranchId\" = ? WHERE id = ?").params(branchId, id).update();
        }
        return login("DEMO", email, "Counter@12345");
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Map<String, Object> saleBody(String b, String customer, String product, int qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleType", "cash");
        body.put("branchId", b);
        if (customer != null) {
            body.put("partnerId", customer);
        }
        body.put("lines", List.of(Map.of("productId", product, "quantity", qty, "unitPrice", 100, "taxId", vat)));
        return body;
    }

    /** A draft cash sale of {@code qty} × goods at 100 + VAT 18 %. */
    String draft(String b, String customer, int qty) {
        return ok(201, "POST", "/sales/orders", saleBody(b, customer, goods, qty)).get("id").asString();
    }

    JsonNode sale(String id) {
        return ok(200, "GET", "/sales/orders/" + id, null);
    }

    List<String> tenderIds(String id) {
        return jdbc.sql("SELECT id FROM \"SalesOrderPayment\" WHERE \"orderId\" = ?").param(id).query(String.class).list();
    }

    /** Capture a tender as {@code token}; returns the new tender line's id. */
    String pay(String token, String id, Map<String, Object> body) {
        List<String> before = tenderIds(id);
        Res r = call("POST", "/sales/orders/" + id + "/payments", body, token);
        assertThat(r.status()).as("payment → " + r.raw().body()).isLessThan(300);
        List<String> after = new ArrayList<>(tenderIds(id));
        after.removeAll(before);
        assertThat(after).hasSize(1);
        return after.getFirst();
    }

    String pay(String id, Map<String, Object> body) {
        return pay(adminToken, id, body);
    }

    Res payAttempt(String id, Map<String, Object> body) {
        return as("POST", "/sales/orders/" + id + "/payments", body);
    }

    static Map<String, Object> handover() {
        return Map.of("handover", Map.of("affirmed", true, "receivedByName", "Okello John", "receivedByPhone",
                "0772123456", "notes", "Loaded at the counter"));
    }

    Res confirm(String token, String id, Object body) {
        return call("POST", "/sales/orders/" + id + "/confirm", body, key(token));
    }

    JsonNode confirmed(String id) {
        Res r = confirm(adminToken, id, handover());
        assertThat(r.status()).as("confirm → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    /** A draft of {@code qty} paid in full in cash, ready to confirm. */
    String paidDraft(String customer, int qty) {
        String id = draft(branch, customer, qty);
        pay(id, Map.of("method", "cash", "amount", sale(id).get("total").decimalValue()));
        return id;
    }

    static String message(Res r) {
        JsonNode m = r.body() == null ? null : r.body().get("message");
        return m == null ? null : m.isArray() ? m.get(0).asString() : m.asString();
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    BigDecimal onHand() {
        return (BigDecimal) ledger.stock(goods, location).get("qty");
    }

    /** Posted balance (debit − credit) of an account, for one customer when {@code partner} is given. */
    BigDecimal balance(String account, String partner) {
        return jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed') AND l.\"accountId\" = ? AND (?::text IS NULL OR l.\"partnerId\" = ?)")
                .params(demoOrg, account, partner, partner).query(BigDecimal.class).single();
    }

    /** Net (debit − credit) per account of one journal entry. */
    Map<String, BigDecimal> net(String entryId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.sql("SELECT \"accountId\", SUM(debit - credit) AS n FROM \"JournalLine\" WHERE \"journalEntryId\" = ? GROUP BY"
                + " \"accountId\" HAVING SUM(debit - credit) <> 0").param(entryId).query((rs, i) -> out.put(rs.getString(1),
                        rs.getBigDecimal(2))).list();
        return out;
    }

    String invoiceOf(String id) {
        List<String> ids = jdbc.sql("SELECT id FROM \"Document\" WHERE \"organizationId\" = ? AND \"sourceId\" = ? AND"
                + " \"documentType\"::text = 'sales_invoice'").params(demoOrg, id).query(String.class).list();
        assertThat(ids).as("invoices of sale %s", id).hasSize(1);
        return ids.getFirst();
    }

    List<String> loadsOf(String id) {
        return jdbc.sql("SELECT l.id FROM \"FleetLoad\" l JOIN \"FleetDeliveryOrder\" d ON d.id = l.\"deliveryOrderId\""
                + " WHERE d.\"organizationId\" = ? AND d.\"sourceType\" = 'sales_order' AND d.\"sourceId\" = ?")
                .params(demoOrg, id).query(String.class).list();
    }

    long invoiceCounter() {
        return count("SELECT COALESCE(SUM(last), 0) FROM \"GaplessCounter\" WHERE \"organizationId\" = ? AND key LIKE"
                + " 'invoice:%'", demoOrg);
    }

    // ------------------------------------------------------------------ F1

    @Test
    void cashPickupPostsStockInvoiceAndReceiptsInOneConfirm() {
        String c = partner("Counter buyer " + sfx, "1000123456");
        String id = draft(branch, c, 10);
        JsonNode d = sale(id);
        assertThat(d.get("saleType").asString()).isEqualTo("cash");
        assertThat(d.get("number").asString()).startsWith("CSS-C");
        assertThat(d.get("total").decimalValue()).isEqualByComparingTo("1180");
        assertThat(d.get("balanceDue").decimalValue()).isEqualByComparingTo("1180");
        assertThat(d.get("customerName").asString()).isEqualTo("Counter buyer " + sfx);
        assertThat(d.get("customerTin").asString()).isEqualTo("1000123456");
        assertThat(d.get("locationId").asString()).isEqualTo(location);
        assertThat(d.get("actions").toString()).contains("add_payment", "confirm");
        String cash = pay(id, Map.of("method", "cash", "amount", 1000, "amountTendered", 1050));
        String momo = pay(id, Map.of("method", "mobile_money", "provider", "mtn", "amount", 180, "reference",
                "MP" + sfx));
        assertThat(sale(id).get("balanceDue").decimalValue()).isEqualByComparingTo("0");
        BigDecimal before = onHand();

        Map<String, String> headers = key(adminToken);
        Res r = call("POST", "/sales/orders/" + id + "/confirm", handover(), headers);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode done = r.body();
        assertThat(done.get("status").asString()).isEqualTo("done");
        assertThat(done.get("deliveryStatus").asString()).isEqualTo("full");
        assertThat(done.get("invoiceStatus").asString()).isEqualTo("invoiced");
        assertThat(done.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(done.get("settlementStatus").asString()).isEqualTo("settled");
        assertThat(done.get("fiscalStatus").asString()).isEqualTo("issued");
        assertThat(done.get("balanceDue").decimalValue()).isEqualByComparingTo("0");

        // invoice: posted and paid, one only; Dr AR 1,180 / Cr revenue 1,000 / Cr VAT 180
        String invoice = invoiceOf(id);
        Map<String, Object> doc = jdbc.sql("SELECT status::text AS status, \"paymentStatus\"::text AS ps, \"totalAmount\","
                + " \"amountResidual\" FROM \"Document\" WHERE id = ?").param(invoice).query().singleRow();
        assertThat(doc.get("ps")).isEqualTo("paid");
        assertThat((BigDecimal) doc.get("totalAmount")).isEqualByComparingTo("1180");
        assertThat((BigDecimal) doc.get("amountResidual")).isEqualByComparingTo("0");
        List<String> invoiceEntries = ledger.entries("invoice", invoice);
        assertThat(invoiceEntries).hasSize(1);
        String ar = ledger.mapped("accounts_receivable");
        assertThat(net(invoiceEntries.getFirst())).containsOnlyKeys(ar, income, vatAccount);
        assertThat(net(invoiceEntries.getFirst()).get(ar)).isEqualByComparingTo("1180");
        assertThat(net(invoiceEntries.getFirst()).get(income)).isEqualByComparingTo("-1000");
        assertThat(net(invoiceEntries.getFirst()).get(vatAccount)).isEqualByComparingTo("-180");

        // one receipt per tender: Dr tender / Cr AR; change is recorded on the tender, never posted
        List<Map<String, Object>> receipts = jdbc.sql("SELECT p.id, p.amount, p.\"paymentMethod\", p.\"accountId\","
                + " p.reference, p.\"journalEntryId\", t.id AS tender, t.\"changeGiven\", t.\"amountTendered\", t.status"
                + " FROM \"SalesOrderPayment\" t JOIN \"Payment\" p ON p.id = t.\"paymentId\" WHERE t.\"orderId\" = ?"
                + " ORDER BY p.amount DESC").param(id).query().listOfRows();
        assertThat(receipts).hasSize(2);
        Map<String, Object> cashReceipt = receipts.get(0);
        assertThat(cashReceipt.get("tender")).isEqualTo(cash);
        assertThat((BigDecimal) cashReceipt.get("amount")).isEqualByComparingTo("1000");
        assertThat(cashReceipt.get("paymentMethod")).isEqualTo("cash");
        assertThat((BigDecimal) cashReceipt.get("amountTendered")).isEqualByComparingTo("1050");
        assertThat((BigDecimal) cashReceipt.get("changeGiven")).isEqualByComparingTo("50");
        assertThat(cashReceipt.get("status")).isEqualTo("posted");
        assertThat(net((String) cashReceipt.get("journalEntryId"))).containsOnlyKeys(cashAccount, ar);
        assertThat(net((String) cashReceipt.get("journalEntryId")).get(cashAccount)).isEqualByComparingTo("1000");
        Map<String, Object> momoReceipt = receipts.get(1);
        assertThat(momoReceipt.get("tender")).isEqualTo(momo);
        assertThat(momoReceipt.get("paymentMethod")).isEqualTo("mobile_money");
        assertThat(momoReceipt.get("reference")).isEqualTo("MP" + sfx);
        assertThat(net((String) momoReceipt.get("journalEntryId")).get(mtnAccount)).isEqualByComparingTo("180");
        assertThat(net((String) momoReceipt.get("journalEntryId")).get(ar)).isEqualByComparingTo("-180");

        // stock left the profile's location once, at cost (COGS 600)
        assertThat(before.subtract(onHand())).isEqualByComparingTo("10");
        List<String> loads = loadsOf(id);
        assertThat(loads).hasSize(1);
        Map<String, Object> load = jdbc.sql("SELECT \"deliveryMethod\", \"approvalRequired\", \"locationId\" FROM"
                + " \"FleetLoad\" WHERE id = ?").param(loads.getFirst()).query().singleRow();
        assertThat(load.get("deliveryMethod")).isEqualTo("customer_collect");
        assertThat(load.get("approvalRequired")).isEqualTo(false);
        assertThat(load.get("locationId")).isEqualTo(location);
        BigDecimal cogs = jdbc.sql("SELECT COALESCE(SUM(l.debit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE e.\"sourceType\" = 'fleet_trip' AND e.\"sourceId\" = ?")
                .param(loads.getFirst()).query(BigDecimal.class).single();
        assertThat(cogs).isEqualByComparingTo("600");
        Map<String, Object> pod = jdbc.sql("SELECT \"receiverName\", \"receiverPhone\" FROM \"FleetProofOfDelivery\" WHERE"
                + " \"loadId\" = ?").param(loads.getFirst()).query().singleRow();
        assertThat(pod.get("receiverName")).isEqualTo("Okello John");
        assertThat(pod.get("receiverPhone")).isEqualTo("0772123456");

        // customer balances: settled, revenue and VAT once, nothing on the legacy accrual / deposit accounts (AD-6)
        assertThat(balance(ar, c)).isEqualByComparingTo("0");
        ledger.receivableTiesOut(c);
        assertThat(balance(income, c)).isEqualByComparingTo("-1000");
        assertThat(balance(vatAccount, null)).isEqualByComparingTo("-180");
        for (String k : List.of("unbilled_receivable", "customer_deposit", "output_tax_deferred")) {
            List<String> account = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND"
                    + " key = ?").params(demoOrg, k).query(String.class).list();
            if (!account.isEmpty()) {
                assertThat(count("SELECT count(*) FROM \"JournalLine\" WHERE \"accountId\" = ? AND \"partnerId\" = ?",
                        account.getFirst(), c)).as("lines on %s", k).isZero();
            }
        }

        // prepaid credit decisions (OD-6), fiscal document, timeline
        assertThat(count("SELECT count(*) FROM \"CreditDecision\" WHERE \"documentId\" = ? AND checkpoint = 'order_confirm'"
                + " AND result = 'pass' AND reasons[1] LIKE 'Prepaid%'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"CreditDecision\" WHERE \"documentId\" = ? AND checkpoint = 'trip_dispatch'"
                + " AND result = 'pass' AND reasons[1] LIKE 'Prepaid%'", loads.getFirst())).isEqualTo(1);
        Map<String, Object> fiscal = jdbc.sql("SELECT operation, status, \"invoiceId\", \"requestRef\" FROM"
                + " \"FiscalDocument\" WHERE \"salesOrderId\" = ?").param(id).query().singleRow();
        assertThat(fiscal.get("operation")).isEqualTo("issue");
        assertThat(fiscal.get("status")).isEqualTo("succeeded");
        assertThat(fiscal.get("invoiceId")).isEqualTo(invoice);
        assertThat((String) fiscal.get("requestRef")).startsWith("so:" + id + ":issue:");
        List<String> actions = new ArrayList<>();
        ok(200, "GET", "/sales/orders/" + id + "/events", null).forEach(e -> actions.add(e.get("action").asString()));
        assertThat(actions).startsWith("create", "payment_verified", "payment_verified").contains("confirm");

        // TP-1: every row the confirm inserted was written by one transaction
        List<String> xmins = jdbc.sql("SELECT DISTINCT x FROM (SELECT xmin::text AS x FROM \"JournalEntry\" WHERE id IN"
                + " (SELECT \"journalEntryId\" FROM \"Payment\" p JOIN \"SalesOrderPayment\" t ON t.\"paymentId\" = p.id"
                + " WHERE t.\"orderId\" = ?) OR (\"sourceType\" = 'invoice' AND \"sourceId\" = ?) OR (\"sourceType\" ="
                + " 'fleet_trip' AND \"sourceId\" = ?) UNION ALL SELECT xmin::text FROM \"PaymentAllocation\" WHERE"
                + " \"documentId\" = ? UNION ALL SELECT xmin::text FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ?"
                + " UNION ALL SELECT xmin::text FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action = 'confirm'"
                + " UNION ALL SELECT xmin::text FROM \"CreditDecision\" WHERE \"documentId\" IN (?, ?) UNION ALL SELECT"
                + " xmin::text FROM \"FleetProofOfDelivery\" WHERE \"loadId\" = ? UNION ALL SELECT xmin::text FROM"
                + " \"SalesOrderPayment\" WHERE \"orderId\" = ?) t").params(id, invoice, loads.getFirst(), invoice, id, id,
                        id, loads.getFirst(), loads.getFirst(), id).query(String.class).list();
        assertThat(xmins).as("transactions that wrote the confirm").hasSize(1);

        // the same request again replays: one invoice, two receipts
        Res replay = call("POST", "/sales/orders/" + id + "/confirm", handover(), headers);
        assertThat(replay.status()).as(replay.raw().body()).isEqualTo(200);
        invoiceOf(id);
        assertThat(count("SELECT count(*) FROM \"Payment\" WHERE \"partnerId\" = ?", c)).isEqualTo(2);
    }

    @Test
    void walkInDraftTakesTheProfileDefaults() {
        Map<String, Object> body = saleBody(branch, null, goods, 2);
        body.put("customerName", "ABC Construction");
        body.put("customerTin", "1000999888");
        body.put("customerPhone", "0700111222");
        body.put("paymentMode", "credit");
        body.put("deliveryMethod", "delivery");
        body.put("locationId", segLocation);
        JsonNode s = ok(201, "POST", "/sales/orders", body);
        String id = s.get("id").asString();
        assertThat(s.get("partnerId").asString()).isEqualTo(walkIn);
        assertThat(s.get("customerName").asString()).isEqualTo("ABC Construction");
        assertThat(s.get("customerTin").asString()).isEqualTo("1000999888");
        assertThat(s.get("customerPhone").asString()).isEqualTo("0700111222");
        assertThat(s.get("paymentMode").asString()).isEqualTo("cash");
        assertThat(s.get("deliveryMethod").asString()).isEqualTo("collect");
        assertThat(s.get("locationId").asString()).isEqualTo(location);
        assertThat(s.get("branchId").asString()).isEqualTo(branch);
        assertThat(s.get("profileId").asString()).isEqualTo(profile);
        assertThat(s.get("status").asString()).isEqualTo("draft");

        Map<String, Object> bad = saleBody(branch, null, goods, 1);
        bad.put("saleType", "barter");
        assertThat(message(as("POST", "/sales/orders", bad))).isEqualTo("saleType must be one of: cash, order");
        Map<String, Object> usd = saleBody(branch, null, goods, 1);
        usd.put("currencyCode", "USD");
        Res foreign = as("POST", "/sales/orders", usd);
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(message(foreign)).isEqualTo("Cash sales are recorded in the organization currency");

        Res type = as("PUT", "/sales/orders/" + id, Map.of("saleType", "order"));
        assertThat(message(type)).isEqualTo("The type of a sale cannot change");
        Res moved = as("PUT", "/sales/orders/" + id, Map.of("branchId", segBranch));
        assertThat(message(moved)).isEqualTo("The branch of a cash sale cannot change");
        // a named customer brings their identity; the location stays the profile's
        String c = partner("Named buyer " + sfx, "1000555444");
        JsonNode named = ok(200, "PUT", "/sales/orders/" + id, Map.of("partnerId", c, "locationId", segLocation));
        assertThat(named.get("customerName").asString()).isEqualTo("Named buyer " + sfx);
        assertThat(named.get("customerTin").asString()).isEqualTo("1000555444");
        assertThat(named.get("locationId").asString()).isEqualTo(location);

        ok(200, "DELETE", "/sales/orders/" + id, null);
        assertThat(count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action = 'discard'", id))
                .isEqualTo(1);
    }

    @Test
    void approvalNeverConfirmsACashSale() {
        // AP-8: the approval is only a gate; the cash sale is confirmed with its handover
        String approver = user("cs-approver", null, Permissions.SALES__CASH_SALE_READ,
                Permissions.SALES__CASH_SALE_READ_ALL, Permissions.SALES__CASH_SALE_APPROVE);
        String orderApprover = user("cs-order-approver", null, Permissions.SALES__CASH_SALE_READ,
                Permissions.SALES__CASH_SALE_READ_ALL, Permissions.SALES__ORDER_APPROVE);
        ok(200, "PUT", "/sales/settings", Map.of("orderApprovalAlways", true));
        try {
            String id = paidDraft(customer(), 2);
            assertThat(sale(id).get("approvalStatus").asString()).isEqualTo("required");
            assertThat(message(confirm(adminToken, id, handover())))
                    .isEqualTo("This sale needs approval: submit it for approval first");
            ok(200, "POST", "/sales/orders/" + id + "/submit-approval", null);
            Res wrongType = call("POST", "/sales/orders/" + id + "/approve", Map.of(), key(orderApprover));
            assertThat(wrongType.status()).isEqualTo(403);
            assertThat(message(wrongType)).isEqualTo("Missing required permission(s): sales:cash_sale:approve");
            Res approved = call("POST", "/sales/orders/" + id + "/approve", Map.of(), key(approver));
            assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
            assertThat(sale(id).get("status").asString()).isEqualTo("draft");
            assertThat(sale(id).get("approvalStatus").asString()).isEqualTo("approved");
            assertThat(confirmed(id).get("status").asString()).isEqualTo("done");
        } finally {
            ok(200, "PUT", "/sales/settings", Map.of("orderApprovalAlways", false));
        }
    }

    // ------------------------------------------------------------------ payment model (PM)

    @Test
    void tenderRules() {
        String c = customer();
        String id = draft(branch, c, 10);
        Res over = payAttempt(id, Map.of("method", "cash", "amount", 1200));
        assertThat(message(over)).isEqualTo("Payments (1200) would exceed the sale total (1180); the balance due is 1180");
        assertThat(message(payAttempt(id, Map.of("method", "mobile_money", "provider", "mtn", "amount", 100))))
                .isEqualTo("reference should not be empty for mobile money payments");
        assertThat(message(payAttempt(id, Map.of("method", "bank", "amount", 100, "reference", "TT1",
                "amountTendered", 200)))).isEqualTo("amountTendered applies to cash only");
        assertThat(message(payAttempt(id, Map.of("method", "cash", "amount", 100, "amountTendered", 50))))
                .isEqualTo("amountTendered must not be less than amount");
        assertThat(message(payAttempt(id, Map.of("method", "mobile_money", "provider", "airtel", "amount", 100,
                "reference", "A1")))).isEqualTo("This branch does not accept mobile money (airtel)");
        assertThat(message(payAttempt(id, Map.of("method", "cheque", "amount", 100))))
                .isEqualTo("method must be one of: bank, card, cash, mobile_money");
        assertThat(message(payAttempt(id, Map.of("method", "cash", "amount", 100, "accountId", bankAccount))))
                .isEqualTo("property accountId should not exist");

        // PM-8: a reference is recorded once
        String ref = "BK" + sfx;
        pay(id, Map.of("method", "bank", "amount", 500, "reference", ref));
        Res dup = payAttempt(draft(branch, c, 1), Map.of("method", "bank", "amount", 100, "reference", ref));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(message(dup)).isEqualTo("Payment reference " + ref + " is already recorded");

        // PM-1: not paid in full
        Res under = confirm(adminToken, id, handover());
        assertThat(message(under)).isEqualTo("The sale is not fully paid: 500 of 1180");
        assertThat(sale(id).get("status").asString()).isEqualTo("draft");

        // a captured line can be removed while the sale is a draft
        String extra = pay(id, Map.of("method", "cash", "amount", 100));
        assertThat(sale(id).get("balanceDue").decimalValue()).isEqualByComparingTo("580");
        ok(200, "DELETE", "/sales/orders/" + id + "/payments/" + extra, null);
        assertThat(sale(id).get("balanceDue").decimalValue()).isEqualByComparingTo("680");

        // PM-6 (card through the card_clearing mapping): cash + bank + card, one receipt each
        boolean added = count("SELECT count(*) FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'card_clearing'", demoOrg) == 0;
        if (added) {
            jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\") VALUES"
                    + " (?, ?, 'card_clearing', ?, now())").params(UUID.randomUUID().toString(), demoOrg, cardAccount)
                    .update();
        }
        try {
            Res noRef = payAttempt(id, Map.of("method", "card", "amount", 300));
            assertThat(message(noRef)).isEqualTo("reference should not be empty for card payments");
            pay(id, Map.of("method", "card", "amount", 300, "reference", "AUTH" + sfx));
            pay(id, Map.of("method", "cash", "amount", 380, "amountTendered", 400));
            confirmed(id);
            assertThat(count("SELECT count(*) FROM \"SalesOrderPayment\" WHERE \"orderId\" = ? AND status = 'posted' AND"
                    + " \"paymentId\" IS NOT NULL", id)).isEqualTo(3);
            assertThat(count("SELECT count(*) FROM \"Payment\" WHERE \"partnerId\" = ? AND \"paymentMethod\" = 'card'",
                    c)).isEqualTo(1);
            assertThat(balance(ledger.mapped("accounts_receivable"), c)).isEqualByComparingTo("0");
        } finally {
            if (added) {
                jdbc.sql("DELETE FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = 'card_clearing'")
                        .param(demoOrg).update();
            }
        }
        // after confirm the tenders are frozen
        assertThat(message(payAttempt(id, Map.of("method", "cash", "amount", 1))))
                .isEqualTo("The payments of a confirmed cash sale cannot change");
    }

    @Test
    void segregationNeedsAnotherVerifier() {
        String cashier = user("cs-cashier", null, Permissions.SALES__CASH_SALE_READ, Permissions.SALES__CASH_SALE_CREATE,
                Permissions.SALES__CASH_SALE_CONFIRM);
        String verifier = user("cs-verifier", segBranch, Permissions.SALES__CASH_SALE_READ,
                Permissions.SALES__CASH_SALE_VERIFY_PAYMENT);
        Res created = call("POST", "/sales/orders", saleBody(segBranch, customer(), goods, 10), cashier);
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String id = created.body().get("id").asString();
        String cash = pay(cashier, id, Map.of("method", "cash", "amount", 1000));
        String wrong = pay(cashier, id, Map.of("method", "mobile_money", "provider", "mtn", "amount", 180,
                "reference", "SGX" + sfx));
        assertThat(jdbc.sql("SELECT DISTINCT \"verificationStatus\" FROM \"SalesOrderPayment\" WHERE \"orderId\" = ?")
                .param(id).query(String.class).list()).containsExactly("pending");
        // pending lines count toward the total: nothing more can be captured until one fails
        assertThat(message(call("POST", "/sales/orders/" + id + "/payments", Map.of("method", "cash", "amount", 1),
                cashier))).isEqualTo("Payments (1181) would exceed the sale total (1180); the balance due is 0");

        // PM-2: unverified tenders block the confirm
        Res pending = confirm(cashier, id, handover());
        assertThat(message(pending)).startsWith("Every payment must be verified before the sale is confirmed (")
                .endsWith(" is pending)");
        // the collector cannot verify; a cashier cannot verify mobile money at all
        Res self = call("POST", "/sales/orders/" + id + "/payments/" + cash + "/verify", Map.of(), cashier);
        assertThat(self.status()).isEqualTo(403);
        assertThat(message(self)).isEqualTo("Segregation of duties: the collector cannot verify the payment");
        Res momoByCashier = call("POST", "/sales/orders/" + id + "/payments/" + wrong + "/verify", Map.of(), cashier);
        assertThat(momoByCashier.status()).isEqualTo(403);
        assertThat(message(momoByCashier)).isEqualTo("Verifying mobile money needs sales:cash_sale:verify_payment");

        // the branch's verifier sees the sale (working branch) and verifies; a failed line stays out of the total
        assertThat(call("GET", "/sales/orders/" + id, null, verifier).status()).isEqualTo(200);
        assertThat(call("POST", "/sales/orders/" + id + "/payments/" + cash + "/verify", Map.of(), verifier).status())
                .isEqualTo(200);
        assertThat(call("POST", "/sales/orders/" + id + "/payments/" + wrong + "/verify", Map.of("status", "failed",
                "note", "Not on the statement"), verifier).status()).isEqualTo(200);
        // the failed line stays as evidence, out of the total; the customer pays again
        String momo = pay(cashier, id, Map.of("method", "mobile_money", "provider", "mtn", "amount", 180, "reference",
                "SG" + sfx));
        assertThat(call("POST", "/sales/orders/" + id + "/payments/" + momo + "/verify", Map.of("status", "verified"),
                verifier).status()).isEqualTo(200);
        Res again = call("POST", "/sales/orders/" + id + "/payments/" + cash + "/verify", Map.of(), verifier);
        assertThat(message(again)).isEqualTo("Only a pending payment can be verified or failed (this one is verified)");
        assertThat(jdbc.sql("SELECT \"verificationSource\" FROM \"SalesOrderPayment\" WHERE id = ?").param(cash)
                .query(String.class).single()).isEqualTo("verifier");

        // PM-3: the failed line is left out; the cashier (not the verifier) confirms
        Res ok = confirm(cashier, id, handover());
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.body().get("status").asString()).isEqualTo("done");
        assertThat(count("SELECT count(*) FROM \"SalesOrderPayment\" WHERE \"orderId\" = ? AND status = 'posted'", id))
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM \"SalesOrderPayment\" WHERE id = ?").param(wrong).query(String.class)
                .single()).isEqualTo("captured");
    }

    // ------------------------------------------------------------------ handover (PH)

    @Test
    void handoverIsProvenBeforeTheConfirm() {
        String id = paidDraft(customer(), 10);
        String line = sale(id).get("lines").get(0).get("id").asString();
        BigDecimal before = onHand();
        assertThat(message(confirm(adminToken, id, null)))
                .startsWith("handover should not be empty: a cash sale is confirmed when the customer receives the goods");
        assertThat(message(confirm(adminToken, id, Map.of("handover", Map.of("receivedByName", "Okello")))))
                .isEqualTo("Confirm that the customer has received these goods (handover.affirmed)");
        assertThat(message(confirm(adminToken, id, Map.of("handover", Map.of("affirmed", true, "receivedByName", " ")))))
                .isEqualTo("handover.receivedByName should not be empty");
        Res partial = confirm(adminToken, id, Map.of("handover", Map.of("affirmed", true, "receivedByName", "Okello",
                "lines", List.of(Map.of("lineId", line, "quantity", 4)))));
        assertThat(message(partial)).isEqualTo("A cash sale hands every line over in full at the counter: Cement " + sfx
                + " is 4 of 10");
        assertThat(message(confirm(adminToken, id, Map.of("handover", Map.of("affirmed", true, "receivedByName",
                "Okello", "lines", List.of()))))).isEqualTo("A cash sale hands every line over in full at the counter:"
                        + " Cement " + sfx + " is missing from the handover");
        assertThat(message(confirm(adminToken, id, Map.of("handover", Map.of("affirmed", true, "receivedByName",
                "Okello", "colour", "red"))))).isEqualTo("property colour should not exist");
        // nothing moved
        assertThat(sale(id).get("status").asString()).isEqualTo("draft");
        assertThat(loadsOf(id)).isEmpty();
        assertThat(onHand()).isEqualByComparingTo(before);

        Res full = confirm(adminToken, id, Map.of("handover", Map.of("affirmed", true, "receivedByName", "Okello",
                "lines", List.of(Map.of("lineId", line, "quantity", 10)))));
        assertThat(full.status()).as(full.raw().body()).isEqualTo(200);
        assertThat(before.subtract(onHand())).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ atomicity (FI)

    void owner(String... sql) throws Exception {
        try (Connection c = flyway.getConfiguration().getDataSource().getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    /** Raise inside the confirm when a row matching {@code condition} is inserted into {@code table}. */
    void inject(String table, String condition) throws Exception {
        // a sequence is not transactional: it proves the fault fired although its transaction rolled back
        owner("CREATE SEQUENCE IF NOT EXISTS public.it_cash_sale_fault_hits",
                "GRANT USAGE, SELECT ON SEQUENCE public.it_cash_sale_fault_hits TO PUBLIC",
                "CREATE OR REPLACE FUNCTION public.it_cash_sale_fault() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN IF "
                + condition + " THEN PERFORM nextval('public.it_cash_sale_fault_hits'); RAISE EXCEPTION"
                + " 'injected fault on %', TG_TABLE_NAME; END IF; RETURN NEW; END $f$",
                "CREATE TRIGGER it_cash_sale_fault BEFORE INSERT ON \"" + table + "\" FOR EACH ROW EXECUTE FUNCTION"
                        + " public.it_cash_sale_fault()");
    }

    void clearFault(String table) throws Exception {
        owner("DROP TRIGGER IF EXISTS it_cash_sale_fault ON \"" + table + "\"",
                "DROP FUNCTION IF EXISTS public.it_cash_sale_fault()");
    }

    long hits() {
        return count("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM public.it_cash_sale_fault_hits");
    }

    @Test
    void anyFailureInTheConfirmRollsEverythingBack() throws Exception {
        String c = customer();
        String id = draft(branch, c, 10);
        pay(id, Map.of("method", "cash", "amount", 1000));
        pay(id, Map.of("method", "mobile_money", "provider", "mtn", "amount", 180, "reference", "FI" + sfx));
        String saleSql = "'" + id + "'";
        Map<String, String> faults = new LinkedHashMap<>();
        faults.put("FleetLoad", "(to_jsonb(NEW)->>'deliveryOrderId') IN (SELECT id FROM \"FleetDeliveryOrder\" WHERE"
                + " \"sourceId\" = " + saleSql + ")");
        faults.put("JournalEntry", "(to_jsonb(NEW)->>'sourceType') = 'invoice' AND (to_jsonb(NEW)->>'sourceId') IN"
                + " (SELECT id FROM \"Document\" WHERE \"sourceId\" = " + saleSql + ")");
        faults.put("PaymentAllocation", "(to_jsonb(NEW)->>'documentId') IN (SELECT id FROM \"Document\" WHERE"
                + " \"sourceId\" = " + saleSql + ") AND EXISTS (SELECT 1 FROM \"PaymentAllocation\" a WHERE"
                + " a.\"documentId\" = (to_jsonb(NEW)->>'documentId'))");
        faults.put("FiscalDocument", "(to_jsonb(NEW)->>'salesOrderId') = " + saleSql);
        faults.put("SalesOrderEvent", "(to_jsonb(NEW)->>'orderId') = " + saleSql + " AND (to_jsonb(NEW)->>'action') ="
                + " 'confirm'");
        int version = sale(id).get("version").asInt();
        BigDecimal before = onHand();
        long counter = invoiceCounter();
        Map<String, String> headers = null;
        for (Map.Entry<String, String> f : faults.entrySet()) {
            inject(f.getKey(), f.getValue());
            long fired = hits();
            headers = key(adminToken);
            Res r;
            try {
                r = call("POST", "/sales/orders/" + id + "/confirm", handover(), headers);
            } finally {
                clearFault(f.getKey());
            }
            assertThat(r.status()).as("fault on %s → %s", f.getKey(), r.raw().body()).isEqualTo(500);
            assertThat(hits()).as("fault on %s fired", f.getKey()).isEqualTo(fired + 1);
            JsonNode s = sale(id);
            assertThat(s.get("status").asString()).as(f.getKey()).isEqualTo("draft");
            assertThat(s.get("version").asInt()).as(f.getKey()).isEqualTo(version);
            assertThat(loadsOf(id)).as(f.getKey()).isEmpty();
            assertThat(count("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"sourceId\" = ?", id)).as(f.getKey())
                    .isZero();
            assertThat(count("SELECT count(*) FROM \"Document\" WHERE \"sourceId\" = ?", id)).as(f.getKey()).isZero();
            assertThat(count("SELECT count(*) FROM \"Payment\" WHERE \"partnerId\" = ?", c)).as(f.getKey()).isZero();
            assertThat(count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ?", id)).as(f.getKey())
                    .isZero();
            assertThat(count("SELECT count(*) FROM \"CreditDecision\" WHERE \"documentId\" = ?", id)).as(f.getKey())
                    .isZero();
            assertThat(count("SELECT count(*) FROM \"SalesOrderEvent\" WHERE \"orderId\" = ? AND action = 'confirm'", id))
                    .as(f.getKey()).isZero();
            assertThat(count("SELECT count(*) FROM \"SalesOrderPayment\" WHERE \"orderId\" = ? AND status = 'captured'"
                    + " AND \"paymentId\" IS NULL", id)).as(f.getKey()).isEqualTo(2);
            assertThat(onHand()).as(f.getKey()).isEqualByComparingTo(before);
            assertThat(invoiceCounter()).as("gap-free counter after a fault on %s", f.getKey()).isEqualTo(counter);
        }
        // the last request, retried with its key once the fault is gone, runs once
        Res retry = call("POST", "/sales/orders/" + id + "/confirm", handover(), headers);
        assertThat(retry.status()).as(retry.raw().body()).isEqualTo(200);
        assertThat(retry.body().get("status").asString()).isEqualTo("done");
        invoiceOf(id);
        assertThat(invoiceCounter()).isEqualTo(counter + 1);
        assertThat(count("SELECT count(*) FROM \"Payment\" WHERE \"partnerId\" = ?", c)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ concurrency (CC)

    @Test
    void parallelConfirmsOfOneSaleConfirmOnce() throws Exception {
        String id = paidDraft(customer(), 5);
        List<Res> results = parallel(List.of(() -> confirm(adminToken, id, handover()),
                () -> confirm(adminToken, id, handover())));
        assertThat(results.stream().map(Res::status).sorted().toList()).containsExactly(200, 400);
        Res refused = results.stream().filter(r -> r.status() == 400).findFirst().orElseThrow();
        assertThat(message(refused)).isEqualTo("Only draft orders can be confirmed");
        invoiceOf(id);
        assertThat(loadsOf(id)).hasSize(1);
    }

    @Test
    void parallelConfirmsTakeContiguousInvoiceNumbers() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(paidDraft(customer(), 1 + i));
        }
        List<Callable<Res>> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(() -> confirm(adminToken, id, handover()));
        }
        assertThat(parallel(calls)).allSatisfy(r -> assertThat(r.status()).as(r.raw().body()).isEqualTo(200));
        List<Long> numbers = new ArrayList<>();
        for (String id : ids) {
            String number = jdbc.sql("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ?").param(invoiceOf(id))
                    .query(String.class).single();
            numbers.add(Long.parseLong(number.replaceAll("^.*\\D", "")));
        }
        List<Long> sorted = numbers.stream().sorted().toList();
        assertThat(sorted.getLast() - sorted.getFirst()).as("invoice numbers %s", sorted).isEqualTo(5);
        assertThat(sorted).doesNotHaveDuplicates();
    }

    List<Res> parallel(List<Callable<Res>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Res>> futures = new ArrayList<>();
            for (Callable<Res> c : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return c.call();
                }));
            }
            start.countDown();
            List<Res> out = new ArrayList<>();
            for (Future<Res> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shortStockRefusesTheConfirmAndPostsNothing() {
        // CC-7 follows the inventory policy, as every issue does: negative stock off for this product (item policy
        // block) refuses the confirm; where the organization allows negative stock the sale goes through (legacy rule)
        String scarce = product("Scarce " + sfx, "block");
        receive(scarce, location, 5);
        ok(200, "PUT", "/settings/inventory.allowNegativeStock", Map.of("value", false, "scopeType", "product",
                "scopeId", scarce));
        String c = customer();
        String id = ok(201, "POST", "/sales/orders", saleBody(branch, c, scarce, 10)).get("id").asString();
        pay(id, Map.of("method", "cash", "amount", 1180));
        long counter = invoiceCounter();
        Res r = confirm(adminToken, id, handover());
        assertThat(r.status()).isEqualTo(400);
        assertThat(message(r)).isEqualTo("Insufficient stock for Scarce " + sfx + ": on hand 5, requested 10.");
        assertThat(sale(id).get("status").asString()).isEqualTo("draft");
        assertThat(loadsOf(id)).isEmpty();
        assertThat(count("SELECT count(*) FROM \"Payment\" WHERE \"partnerId\" = ?", c)).isZero();
        assertThat(invoiceCounter()).isEqualTo(counter);
        assertThat((BigDecimal) ledger.stock(scarce, location).get("qty")).isEqualByComparingTo("5");
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void eachTypeNeedsItsOwnPermissions() {
        String clerk = user("cs-clerk", null, Permissions.SALES__ORDER_READ, Permissions.SALES__ORDER_WRITE,
                Permissions.SALES__ORDER_CONFIRM);
        String teller = user("cs-teller", null, Permissions.SALES__CASH_SALE_READ, Permissions.SALES__CASH_SALE_CREATE);
        String c = customer();

        Res cashByClerk = call("POST", "/sales/orders", saleBody(branch, c, goods, 1), clerk);
        assertThat(cashByClerk.status()).isEqualTo(403);
        assertThat(message(cashByClerk)).isEqualTo("Missing required permission(s): sales:cash_sale:create");
        Map<String, Object> order = new LinkedHashMap<>(saleBody(branch, c, goods, 1));
        order.remove("saleType");
        Res orderByTeller = call("POST", "/sales/orders", order, teller);
        assertThat(orderByTeller.status()).isEqualTo(403);
        assertThat(message(orderByTeller)).isEqualTo("Missing required permission(s): sales:order:write");

        String cash = paidDraft(c, 1);
        String so = ok(201, "POST", "/sales/orders", order).get("id").asString();
        // confirm: the route needs one of the two permissions, then the type decides
        Res noConfirm = call("POST", "/sales/orders/" + cash + "/confirm", handover(), key(teller));
        assertThat(noConfirm.status()).isEqualTo(403);
        // a clerk who cannot read cash sales does not find one; one who can is refused by type
        assertThat(call("POST", "/sales/orders/" + cash + "/confirm", handover(), key(clerk)).status()).isEqualTo(404);
        String orderConfirmer = user("cs-order-confirmer", null, Permissions.SALES__ORDER_READ,
                Permissions.SALES__ORDER_CONFIRM, Permissions.SALES__CASH_SALE_READ, Permissions.SALES__CASH_SALE_READ_ALL);
        Res clerkOnCash = call("POST", "/sales/orders/" + cash + "/confirm", handover(), key(orderConfirmer));
        assertThat(clerkOnCash.status()).isEqualTo(403);
        assertThat(message(clerkOnCash)).isEqualTo("Missing required permission(s): sales:cash_sale:confirm");
        String cashier = user("cs-confirmer", null, Permissions.SALES__CASH_SALE_READ_ALL,
                Permissions.SALES__CASH_SALE_READ, Permissions.SALES__ORDER_READ, Permissions.SALES__ORDER_READ_ALL,
                Permissions.SALES__CASH_SALE_CONFIRM);
        Res cashierOnOrder = call("POST", "/sales/orders/" + so + "/confirm", null, key(cashier));
        assertThat(cashierOnOrder.status()).isEqualTo(403);
        assertThat(message(cashierOnOrder)).isEqualTo("Missing required permission(s): sales:order:confirm");
        // an order's edits need the order permission; tenders need the cash-sale one
        Res tellerEdit = call("PUT", "/sales/orders/" + so, Map.of("notes", "x"), teller);
        assertThat(tellerEdit.status()).isIn(403, 404);
        Res clerkTender = call("POST", "/sales/orders/" + cash + "/payments", Map.of("method", "cash", "amount", 1),
                clerk);
        assertThat(clerkTender.status()).isEqualTo(403);
        // missing Idempotency-Key on the confirm
        Map<String, String> noKey = new LinkedHashMap<>();
        noKey.put("Authorization", "Bearer " + adminToken);
        noKey.put(NO_IDEMPOTENCY_KEY, "1");
        assertThat(call("POST", "/sales/orders/" + cash + "/confirm", handover(), noKey).status()).isEqualTo(400);

        // visibility: the teller sees its own cash sales only, the clerk orders only
        Res own = call("POST", "/sales/orders", saleBody(branch, c, goods, 1), teller);
        assertThat(own.status()).as(own.raw().body()).isEqualTo(201);
        JsonNode tellerList = call("GET", "/sales/orders?partnerId=" + c, null, teller).body();
        List<String> seen = new ArrayList<>();
        tellerList.get("data").forEach(o -> seen.add(o.get("id").asString()));
        assertThat(seen).containsExactly(own.body().get("id").asString());
        assertThat(call("GET", "/sales/orders/" + so, null, teller).status()).isEqualTo(404);
        assertThat(call("GET", "/sales/orders/" + cash, null, clerk).status()).isEqualTo(404);
        JsonNode cashOnly = ok(200, "GET", "/sales/orders?saleType=cash&partnerId=" + c, null);
        List<String> listed = new ArrayList<>();
        cashOnly.get("data").forEach(o -> listed.add(o.get("id").asString()));
        assertThat(listed).contains(cash).doesNotContain(so);
    }
}
