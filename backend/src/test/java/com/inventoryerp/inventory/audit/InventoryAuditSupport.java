package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountingSeedContributor;
import com.inventoryerp.core.application.CoreSeedContributor;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.SeedContributor;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Shared bootstrap of the ported Nest inventory audit suites ({@code test/integration/audit/_harness.ts}): one isolated
 * organisation per suite with the seeded chart of accounts, journals, account mappings and fiscal years, two
 * warehouses ({@code MAIN}, {@code ALT}, {@code pos.stockLocationId} = MAIN), an administrator with every permission,
 * and the measuring helpers of the harness (on-hand, ledger sum, open lots, account balance). Isolation keeps the GL
 * balances and the tie-out of a suite free of what other suites post into DEMO.
 */
public abstract class InventoryAuditSupport extends ApiTestSupport {

    @Autowired
    protected StockService stock;

    @Autowired
    protected SettingResolverService settingResolver;

    @Autowired
    protected ObjectProvider<FlywayConnectionDetails> ownerDetails;

    /** The suite's own organisation. */
    protected String org;
    protected String orgCode;
    protected String auditUserId;
    protected String auditToken;
    protected String main;
    protected String alt;
    protected String categoryId;
    protected String uomId;

    /** One isolated audit organisation. */
    protected record AuditOrg(String id, String code, String userId, String token, String main, String alt,
            String categoryId, String uomId) {}

    @BeforeAll
    void auditOrg() {
        AuditOrg o = newOrg();
        org = o.id();
        orgCode = o.code();
        auditUserId = o.userId();
        auditToken = o.token();
        main = o.main();
        alt = o.alt();
        categoryId = o.categoryId();
        uomId = o.uomId();
    }

    /** Create an isolated organisation (Nest {@code createAuditOrg}). */
    protected AuditOrg newOrg() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String code = "AUD-" + tag;
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Organization\" (id, code, name, \"currencyCode\", \"updatedAt\") VALUES (?, ?, ?, 'UGX',"
                + " now())").params(id, code, "Audit " + tag).update();
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, type, rate, \"updatedAt\") VALUES (?, ?,"
                + " 'No Tax', 'NOTAX', 'vat', 0, now())").params(UUID.randomUUID().toString(), id).update();
        String category = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, 'General',"
                + " now())").params(category, id).update();
        int year = Year.now().getValue();
        for (int y = year - 1; y <= year + 1; y++) {
            jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                            + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'open', now())")
                    .params(UUID.randomUUID().toString(), id, "FY" + y, Timestamp.valueOf(LocalDateTime.of(y, 1, 1, 0, 0)),
                            Timestamp.valueOf(LocalDateTime.of(y, 12, 31, 23, 59, 59)))
                    .update();
        }
        new TransactionTemplate(txm).executeWithoutResult(st -> seedContributors.stream()
                .filter(c -> c instanceof CoreSeedContributor || c instanceof AccountingSeedContributor)
                .sorted(Comparator.comparingInt(SeedContributor::order))
                .forEach(c -> c.seed(id)));
        String uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'").param(id)
                .query(String.class).optional().orElse(null);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, 'Administrator', true, ?, now())")
                .params(role, id, Permissions.ALL.toArray(String[]::new)).update();
        String email = "audit-" + tag + "@audit.test";
        String user = createUser(id, email, "Audit@12345", role);
        String token = login(code, email, "Audit@12345");
        String mainLoc = location(id, "MAIN", "Main Store", "warehouse");
        String altLoc = location(id, "ALT", "Second Store", "warehouse");
        // Production policy: an organisation with more than one warehouse names its POS stock location.
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'pos.stockLocationId', ?::jsonb, 'organization', now())")
                .params(UUID.randomUUID().toString(), id, "\"" + mainLoc + "\"").update();
        settingResolver.invalidate(id);
        return new AuditOrg(id, code, user, token, mainLoc, altLoc, category, uom);
    }

    // ------------------------------------------------------------------ fixtures

    protected String location(String code, String name, String type) {
        return location(org, code, name, type);
    }

    protected String location(String organizationId, String code, String name, String type) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"isActive\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, true, now())").params(id, organizationId, code, name, type).update();
        return id;
    }

    /** Insert a row with the given columns (enum values travel as text: the test URL uses stringtype=unspecified). */
    protected String insert(String table, Map<String, Object> cols) {
        String id = cols.containsKey("id") ? (String) cols.get("id") : UUID.randomUUID().toString();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", id);
        all.putAll(cols);
        String names = String.join(", ", all.keySet().stream().map(k -> "\"" + k + "\"").toList());
        String marks = String.join(", ", all.keySet().stream().map(k -> "?").toList());
        jdbc.sql("INSERT INTO \"" + table + "\" (" + names + ") VALUES (" + marks + ")").params(all.values().stream()
                .map(v -> v instanceof List<?> l ? l.toArray(String[]::new) : v).toList()).update();
        return id;
    }

    protected static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    /** A stockable AVCO product of the audit org (Nest {@code makeProduct}); {@code extra} overrides columns. */
    protected String product(String code, Map<String, Object> extra) {
        return product(new AuditOrg(org, orgCode, auditUserId, auditToken, main, alt, categoryId, uomId), code, extra);
    }

    /** A stockable AVCO product of organisation {@code o}. */
    protected String product(AuditOrg o, String code, Map<String, Object> extra) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", o.id());
        cols.put("code", code + "-" + UUID.randomUUID().toString().substring(0, 6));
        cols.put("name", code);
        cols.put("productType", "stockable");
        cols.put("trackInventory", true);
        cols.put("costingMethod", "AVCO");
        cols.put("costPrice", 0);
        cols.put("salesPrice", 0);
        cols.put("categoryId", o.categoryId());
        cols.put("uomId", o.uomId());
        cols.put("updatedAt", now());
        cols.putAll(extra);
        return insert("Product", cols);
    }

    protected String product(String code) {
        return product(code, Map.of());
    }

    /** An organisation-scope setting (JSON literal), with the resolver cache dropped. */
    protected void orgSetting(String key, String jsonValue) {
        jdbc.sql("DELETE FROM \"Setting\" WHERE \"organizationId\" = ? AND key = ? AND \"scopeType\" = 'organization'")
                .params(org, key).update();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?::jsonb, 'organization', now())").params(UUID.randomUUID().toString(), org, key, jsonValue)
                .update();
        settingResolver.invalidate(org);
    }

    /** A product-scope setting (Nest {@code setting(productId, key, value)}). */
    protected void productSetting(String productId, String key, String jsonValue) {
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"scopeId\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?::jsonb, 'product', ?, now())")
                .params(UUID.randomUUID().toString(), org, key, jsonValue, productId).update();
        settingResolver.invalidate(org);
    }

    // ------------------------------------------------------------------ calls

    /** Call as the audit org administrator. */
    protected Res api(String method, String path, Object body) {
        return call(method, path, body, auditToken);
    }

    /** Audit-org call that must answer {@code status}. */
    protected JsonNode okA(int status, String method, String path, Object body) {
        Res r = api(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    /** Run in the audit org's tenant scope, inside one transaction (RLS scope set like a request). */
    protected <T> T asOrg(Supplier<T> body) {
        return TenantContext.run(TenantStore.user(org, auditUserId),
                () -> new TransactionTemplate(txm).execute(st -> body.get()));
    }

    protected void asOrgRun(Runnable body) {
        asOrg(() -> {
            body.run();
            return null;
        });
    }

    /** Nest {@code receiveForDocument} with a plain GL context (Dr Stock Valuation / Cr GRNI). */
    protected StockMove.Received receive(String productId, String loc, Object qty, Object cost,
            Consumer<StockMove> extra) {
        StockMove m = StockMove.of(productId, loc, dec(qty)).unitCost(dec(cost));
        if (extra != null) {
            extra.accept(m);
        }
        return asOrg(() -> stock.receiveForDocument(m, new GlReceiptContext("audit_seed", "seed-" + UUID.randomUUID(),
                Instant.now(), null)));
    }

    protected StockMove.Received receive(String productId, Object qty, Object cost) {
        return receive(productId, main, qty, cost, null);
    }

    /** Nest {@code stock.issue} with an audit source. */
    protected StockMove.Issued issue(String productId, String loc, Object qty, Consumer<StockMove> extra) {
        StockMove m = StockMove.of(productId, loc, dec(qty)).source("audit_sale", "sale-" + UUID.randomUUID());
        if (extra != null) {
            extra.accept(m);
        }
        return asOrg(() -> stock.issue(m));
    }

    protected Map<String, Object> direct(String loc, List<Map<String, Object>> items) {
        return Map.of("locationId", loc, "responsibleById", auditUserId, "approvedById", auditUserId, "items", items);
    }

    protected static Map<String, Object> item(String productId, Object qty, Object unitCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        if (unitCost != null) {
            m.put("unitCost", unitCost);
        }
        return m;
    }

    // ------------------------------------------------------------------ measures

    protected static BigDecimal dec(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    protected static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    protected BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                + " \"variantKey\" = '' AND \"locationId\" = ?").params(org, productId, loc).query(BigDecimal.class)
                .optional().orElse(BigDecimal.ZERO);
    }

    protected BigDecimal ledgerSum(String productId, String loc) {
        return jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"locationId\" = ?").params(org, productId, loc).query(BigDecimal.class)
                .single();
    }

    /** Σ of the open, active lots of a quant. */
    protected BigDecimal lotSum(String productId, String loc) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ? AND \"isActive\" = true AND quantity > 0")
                .params(org, productId, loc).query(BigDecimal.class).single();
    }

    protected String account(String mappingKey) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(org, mappingKey).query(String.class).single();
    }

    /** Net debit of a mapped account over the audit org's non-draft entries (Nest {@code accountBalance}). */
    protected BigDecimal balance(String mappingKey) {
        return jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                        + " ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND l.\"accountId\" = ?"
                        + " AND e.status::text <> 'draft'")
                .params(org, account(mappingKey)).query(BigDecimal.class).single();
    }

    protected long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    protected long journalEntries() {
        return count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?", org);
    }

    protected long ledgerRows(String productId) {
        return count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?", org,
                productId);
    }

    protected long stockEvents(String productId) {
        return count("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" LIKE 'stock.%' AND payload->>'productId'"
                + " = ?", productId);
    }

    /**
     * Every quant of the audit org: Σ ledger = on-hand, open lots = max(0, on-hand) for lot-tracked products, and each
     * row's qtyBefore is the previous row's balanceAfter (rows in ledger-code sequence). Returns the breaks found.
     */
    protected List<String> quantInvariantBreaks() {
        List<String> breaks = new ArrayList<>();
        for (Map<String, Object> q : jdbc.sql("SELECT s.\"productId\", s.\"locationId\", s.quantity, p.\"batchTracking\""
                + " FROM \"StockItem\" s JOIN \"Product\" p ON p.id = s.\"productId\" WHERE s.\"organizationId\" = ?"
                + " AND s.\"variantKey\" = ''").param(org).query().listOfRows()) {
            String p = (String) q.get("productId");
            String loc = (String) q.get("locationId");
            BigDecimal qty = (BigDecimal) q.get("quantity");
            if (ledgerSum(p, loc).compareTo(qty) != 0) {
                breaks.add("ledger sum " + ledgerSum(p, loc) + " ≠ on-hand " + qty + " for " + p + "@" + loc);
            }
            if (Boolean.TRUE.equals(q.get("batchTracking")) && lotSum(p, loc).compareTo(qty.max(BigDecimal.ZERO)) != 0) {
                breaks.add("open lots " + lotSum(p, loc) + " ≠ max(0, " + qty + ") for " + p + "@" + loc);
            }
            List<Map<String, Object>> rows = new ArrayList<>(jdbc.sql("SELECT \"ledgerCode\", \"qtyBefore\","
                            + " \"balanceAfter\", \"quantityChange\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                            + " AND \"productId\" = ? AND \"locationId\" = ? AND \"quantityChange\" <> 0")
                    .params(org, p, loc).query().listOfRows());
            rows.sort(Comparator.comparingLong(r -> seqOf(r.get("ledgerCode"))));
            for (int i = 1; i < rows.size(); i++) {
                if (((BigDecimal) rows.get(i).get("qtyBefore")).compareTo((BigDecimal) rows.get(i - 1)
                        .get("balanceAfter")) != 0) {
                    breaks.add("chain break at " + rows.get(i).get("ledgerCode") + " for " + p + "@" + loc);
                }
            }
        }
        return breaks;
    }

    static long seqOf(Object ledgerCode) {
        String digits = String.valueOf(ledgerCode).replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits.length() > 18 ? digits.substring(digits.length() - 18)
                : digits);
    }

    // ------------------------------------------------------------------ integrity and GL asserts (IPR T04)

    /** The audit org's integrity report ({@code GET /inventory/reports/integrity}). */
    protected JsonNode integrityReport() {
        return okA(200, "GET", "/inventory/reports/integrity", null);
    }

    /** Keys (with rows) of the failing checks in {@code section} ({@code checks} or {@code reportOnlyChecks}). */
    protected static List<String> failingChecks(JsonNode report, String section) {
        List<String> failing = new ArrayList<>();
        JsonNode checks = report.get(section);
        if (checks != null) {
            for (JsonNode c : checks) {
                if (!c.get("ok").asBoolean()) {
                    failing.add(c.get("key").asString() + " " + c.get("rows"));
                }
            }
        }
        return failing;
    }

    /** The check {@code key} of the report (searched in both sections). */
    protected static JsonNode integrityCheck(JsonNode report, String key) {
        for (String section : List.of("checks", "reportOnlyChecks")) {
            for (JsonNode c : report.get(section)) {
                if (key.equals(c.get("key").asString())) {
                    return c;
                }
            }
        }
        throw new AssertionError("no integrity check " + key);
    }

    /** G1 / G2 evidence: every check of the organisation's integrity report passes (report-only checks not judged). */
    protected void assertIntegrityGreen() {
        assertThat(failingChecks(integrityReport(), "checks")).as("integrity checks").isEmpty();
    }

    /**
     * {@link #assertIntegrityGreen()} plus the report-only checks (IPR Phase 0), except {@code allowedReportOnly} (for
     * example {@code feature_risk}, which every organisation that keeps stock fails while a P2 is open).
     */
    protected void assertIntegrityGreen(boolean includeReportOnly, String... allowedReportOnly) {
        JsonNode report = integrityReport();
        assertThat(failingChecks(report, "checks")).as("integrity checks").isEmpty();
        if (includeReportOnly) {
            List<String> allowed = List.of(allowedReportOnly);
            assertThat(failingChecks(report, "reportOnlyChecks").stream()
                    .filter(f -> !allowed.contains(f.substring(0, f.indexOf(' ')))).toList())
                    .as("report-only integrity checks").isEmpty();
        }
    }

    /**
     * Inventory ties to the GL: the organisation tie-out ({@code GET /reports/inventory/gl-tieout}, tolerance 0.01)
     * is within tolerance, and the per-account base-currency tie-out of the integrity report passes.
     */
    protected void assertGlTieOut() {
        JsonNode t = okA(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.01", null);
        assertThat(t.get("withinTolerance").asBoolean()).as("GL tie-out " + t).isTrue();
        JsonNode byAccount = integrityCheck(integrityReport(), "gl_tieout_by_account");
        assertThat(byAccount.get("ok").asBoolean()).as("GL tie-out by account " + byAccount).isTrue();
    }

    // ------------------------------------------------------------------ concurrency (IPR T04)

    /** Outcome of one racer: its result, or the exception it threw. */
    protected record Raced<T>(T value, Throwable error) {

        public boolean ok() {
            return error == null;
        }

        /** The error message (or class name), null when the racer succeeded. */
        public String message() {
            return error == null ? null : error.getMessage() != null ? error.getMessage() : error.toString();
        }
    }

    /**
     * Start every task at the same instant (one latch; the pattern of {@code InventoryAuditConcurrencyIT}) and collect
     * each outcome without failing on the first error. Keep the number of tasks at or below 10 so the waiting
     * transactions never exhaust the connection pool. Fails when a task does not finish within {@code timeout}
     * (a deadlock the database did not break), or when any task died of a deadlock (SQLSTATE 40P01).
     */
    protected <T> List<Raced<T>> race(java.time.Duration timeout, List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Raced<T>>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Raced<>(t.call(), null);
                    } catch (Throwable e) {
                        return new Raced<T>(null, e);
                    }
                }));
            }
            assertThat(ready.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("racers ready").isTrue();
            go.countDown();
            long deadline = System.nanoTime() + timeout.toNanos();
            List<Raced<T>> out = new ArrayList<>();
            for (Future<Raced<T>> f : futures) {
                long left = Math.max(1, deadline - System.nanoTime());
                out.add(f.get(left, java.util.concurrent.TimeUnit.NANOSECONDS));
            }
            for (Raced<T> r : out) {
                assertThat(isDeadlock(r.error())).as("deadlock (40P01): " + r.message()).isFalse();
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** True when {@code e} (or a cause) is a PostgreSQL deadlock, SQLSTATE 40P01. */
    protected static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException s && "40P01".equals(s.getSQLState())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains("deadlock detected")) {
                return true;
            }
        }
        return false;
    }

    /** Run every task at once; returns their results in order. */
    protected <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return t.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ fault injection

    /**
     * Crash injection without mocks: a trigger (created by the schema owner) on {@code table} that raises when the
     * row's {@code column} equals {@code value} — and, when {@code whenColumn} is set, its {@code whenColumn} equals
     * {@code whenValue}. The failure lands inside the application's own transaction, at that exact write.
     */
    protected AutoCloseable injectFault(String table, String timing, String column, String value, String whenColumn,
            String whenValue) {
        String name = "audit_fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String args = "'" + column + "', '" + value + "'" + (whenColumn == null ? ""
                : ", '" + whenColumn + "', '" + whenValue + "'");
        owner("CREATE OR REPLACE FUNCTION audit_fault_inject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " IF to_jsonb(NEW)->>TG_ARGV[0] = TG_ARGV[1] AND (TG_NARGS < 4 OR to_jsonb(NEW)->>TG_ARGV[2] ="
                + " TG_ARGV[3]) THEN RAISE EXCEPTION 'AUDIT: injected crash on %', TG_TABLE_NAME; END IF;"
                + " RETURN NEW; END $$",
                "CREATE TRIGGER " + name + " BEFORE " + timing + " ON \"" + table + "\" FOR EACH ROW EXECUTE FUNCTION"
                        + " audit_fault_inject(" + args + ")");
        return () -> owner("DROP TRIGGER IF EXISTS " + name + " ON \"" + table + "\"");
    }

    /** Statements run as the schema owner (DDL the application role may not run). */
    protected void owner(String... statements) {
        FlywayConnectionDetails o = ownerDetails.getObject();
        try (Connection c = DriverManager.getConnection(o.getJdbcUrl(), o.getUsername(), o.getPassword());
                Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
