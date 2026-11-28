package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fixtures of the ADR-S094 C3 cash-sale ITs: a counter branch with its profile, accounts, goods in stock and a fresh
 * VAT per test; HTTP helpers for drafts, tenders and the confirm; ledger reads; and failure injection by triggers
 * installed as the schema owner (no new Spring context).
 */
abstract class CashSaleTestBase extends ApiTestSupport {

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

    /** {@code body} in a transaction of the DEMO organization, as the admin; returns its result. */
    <T> T tenantTx(java.util.function.Supplier<T> body) {
        return TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .execute(s -> body.get()));
    }

    /** {@code body} as the admin of the DEMO organization, outside any transaction (as a job or a route runs it). */
    <T> T asTenant(java.util.function.Supplier<T> body) {
        return TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), body);
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
        return profile(b, loc, segregation, Map.of());
    }

    /** A branch profile with {@code extra} settings (e.g. {@code fiscalProvider}). */
    String profile(String b, String loc, boolean segregation, Map<String, Object> extra) {
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
        body.putAll(extra);
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

    // ------------------------------------------------------------------ failure injection and concurrency

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
}
