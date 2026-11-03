package com.inventoryerp.accounting.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ACC-0 accounting test foundation (docs/accounting/ACCOUNTING-TEST-STRATEGY.md). Reuses the one cached
 * {@link ApiTestSupport} Spring context; isolation is per organization, not per context:
 * <ul>
 *   <li>{@link #freshBooks()} bootstraps a brand-new organization (own CoA, journals, mappings, open year-long
 *       period, owner login) — used by every GL / report / FX / close test so no other suite's postings interfere;</li>
 *   <li>document flows that need warehouses, POS or approvals run in the shared DEMO org and scope every assertion to
 *       the partners, products and source documents the test created ({@link #demoPartner}, {@link #demoProduct}).</li>
 * </ul>
 */
public abstract class AccountingFixtures extends ApiTestSupport {

    @Autowired
    protected PostingService posting;

    /** One isolated organization: id, code, owner token and owner user id. */
    public record Books(String org, String code, String token, String ownerId) {}

    /** One journal line for {@link #postJe}: debit or credit in the entry currency, optional partner/branch/dims. */
    public record JeLine(String accountCode, BigDecimal debit, BigDecimal credit, String partnerId, String branchId,
            Map<String, Object> dimensions) {

        public static JeLine dr(String code, Object amount) {
            return new JeLine(code, new BigDecimal(String.valueOf(amount)), BigDecimal.ZERO, null, null, null);
        }

        public static JeLine cr(String code, Object amount) {
            return new JeLine(code, BigDecimal.ZERO, new BigDecimal(String.valueOf(amount)), null, null, null);
        }

        public JeLine partner(String id) {
            return new JeLine(accountCode, debit, credit, id, branchId, dimensions);
        }

        public JeLine branch(String id) {
            return new JeLine(accountCode, debit, credit, partnerId, id, dimensions);
        }
    }

    // ------------------------------------------------------------------ isolated books

    /** A fresh organization through the public bootstrap route, with a retained-earnings mapping guaranteed. */
    protected Books freshBooks() {
        String code = "acc0-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "owner@" + code + ".test";
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "ACC-0 " + code, "adminEmail", email, "adminFirstName", "Owner", "adminPassword", "Owner@12345"),
                (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String ownerId = boot.body().get("adminUser").get("id").asString();
        Books b = new Books(org, code, login(code, email, "Owner@12345"), ownerId);
        if (jdbc.sql("SELECT count(*) FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = 'retained_earnings'"
                + " AND \"deletedAt\" IS NULL").param(org).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\") VALUES"
                    + " (?, ?, 'retained_earnings', ?, now())").params(UUID.randomUUID().toString(), org,
                    account(org, "3100")).update();
        }
        return b;
    }

    protected String baseCurrency(String org) {
        return jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org).query(String.class)
                .optional().orElse("USD");
    }

    protected String account(String org, String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL")
                .params(org, code).query(String.class).single();
    }

    protected String mapped(String org, String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(org, key).query(String.class).single();
    }

    protected String mappedCode(String org, String key) {
        return jdbc.sql("SELECT a.code FROM \"AccountMapping\" m JOIN \"Account\" a ON a.id = m.\"accountId\" WHERE"
                + " m.\"organizationId\" = ? AND m.key = ? AND m.\"deletedAt\" IS NULL").params(org, key)
                .query(String.class).single();
    }

    /** First postable active account of a category key (e.g. {@code revenue}, {@code cogs}). */
    protected String accountCodeOfCategory(String org, String categoryKey) {
        return jdbc.sql("SELECT a.code FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key = ? AND a.\"isPostable\" AND a.\"isActive\" AND a.\"deletedAt\""
                + " IS NULL ORDER BY a.code LIMIT 1").params(org, categoryKey).query(String.class).single();
    }

    /** First postable active account whose category carries {@code reportSection}. */
    protected String accountCodeOfSection(String org, String section) {
        return jdbc.sql("SELECT a.code FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND coalesce(a.\"reportSection\"::text, c.\"reportSection\"::text) = ?"
                + " AND a.\"isPostable\" AND a.\"isActive\" AND a.\"deletedAt\" IS NULL AND a.\"isProtected\" = false"
                + " ORDER BY a.code LIMIT 1").params(org, section).query(String.class).single();
    }

    // ------------------------------------------------------------------ tenant-scoped service calls

    protected <T> T inTenant(String org, String userId, Supplier<T> work) {
        AtomicReference<T> out = new AtomicReference<>();
        TenantContext.run(TenantStore.user(org, userId), () -> out.set(new TransactionTemplate(txm).execute(s ->
                work.get())));
        return out.get();
    }

    protected void inTenant(String org, String userId, Runnable work) {
        TenantContext.run(TenantStore.user(org, userId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> work.run()));
    }

    /**
     * Post one entry through {@link PostingService} (the single GL writer) — multi-currency, branch, dimensions and
     * posting date supported. Returns the journal entry id.
     */
    protected String postJe(Books b, String journal, String date, String currency, Object rate, String sourceType,
            String postingKey, List<JeLine> lines) {
        List<PostingRequest.Line> out = new ArrayList<>();
        for (JeLine l : lines) {
            out.add(new PostingRequest.Line(account(b.org(), l.accountCode()),
                    l.debit().signum() == 0 ? null : l.debit(), l.credit().signum() == 0 ? null : l.credit(),
                    l.partnerId(), l.accountCode(), l.branchId(), null, l.dimensions()));
        }
        Instant at = Instant.parse(date + "T10:00:00Z");
        String sourceId = UUID.randomUUID().toString();
        Map<String, Object> entry = inTenant(b.org(), b.ownerId(), () -> posting.post(new PostingRequest(journal, at,
                "ACC-0 " + date, currency, rate, sourceType == null ? "acc0" : sourceType, sourceId, null, postingKey,
                null, null, null, out)));
        return (String) entry.get("id");
    }

    protected String postJe(Books b, String date, List<JeLine> lines) {
        return postJe(b, "GEN", date, null, null, null, null, lines);
    }

    // ------------------------------------------------------------------ periods and reports

    protected Res as(Books b, String method, String path, Object body) {
        return call(method, path, body, b.token());
    }

    protected JsonNode okAs(Books b, int status, String method, String path, Object body) {
        Res r = as(b, method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    protected Res moneyAs(Books b, String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + b.token());
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    /** The fiscal period that contains {@code date}. */
    protected String periodFor(String org, String date) {
        return jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"startDate\" <= ?::timestamp"
                + " AND \"endDate\" >= ?::timestamp ORDER BY \"startDate\" DESC LIMIT 1")
                .params(org, date + " 12:00:00", date + " 12:00:00").query(String.class).single();
    }

    /**
     * Close the period holding {@code date} (asserting the close checklist gates let it through) and return the
     * closing entry id, or {@code null} when nothing needed closing.
     */
    protected String closePeriod(Books b, String date) {
        String periodId = periodFor(b.org(), date);
        okAs(b, 200, "POST", "/fiscal-periods/" + periodId + "/close", null);
        assertThat(jdbc.sql("SELECT status::text FROM \"FiscalPeriod\" WHERE id = ?").param(periodId).query(String.class)
                .single()).isEqualTo("closed");
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" = 'period_close'"
                + " AND \"sourceId\" = ? AND status = 'posted' ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(b.org(), periodId).query(String.class).optional().orElse(null);
    }

    /**
     * Replace a fresh org's bootstrap year-long period (must have no postings yet) by one open monthly period per
     * {@code months} entry (first day of each month). Returns the period ids in order.
     */
    protected List<String> monthlyPeriods(Books b, LocalDate... months) {
        for (String id : jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?").param(b.org())
                .query(String.class).list()) {
            okAs(b, 204, "DELETE", "/fiscal-periods/" + id, null);
        }
        List<String> ids = new ArrayList<>();
        for (LocalDate m : months) {
            LocalDate start = m.withDayOfMonth(1);
            LocalDate end = start.plusMonths(1).minusDays(1);
            ids.add(okAs(b, 201, "POST", "/fiscal-periods", Map.of("name", "P " + start, "startDate", start.toString(),
                    "endDate", end.toString())).get("id").asString());
        }
        return ids;
    }

    /** First day of the previous and of the current month. */
    protected static LocalDate[] lastAndThisMonth() {
        LocalDate now = LocalDate.now().withDayOfMonth(1);
        return new LocalDate[] {now.minusMonths(1), now};
    }

    protected JsonNode pnl(Books b, String from, String to) {
        return okAs(b, 200, "GET", "/reports/accounting/profit-and-loss?from=" + from + "&to=" + to, null);
    }

    protected JsonNode trialBalance(Books b, String from, String to) {
        return okAs(b, 200, "GET", "/reports/accounting/trial-balance?from=" + from + "&to=" + to, null);
    }

    protected JsonNode balanceSheet(Books b, String asOf) {
        return okAs(b, 200, "GET", "/reports/accounting/balance-sheet?asOf=" + asOf, null);
    }

    public enum TbView { PRE_CLOSING, POST_CLOSING }

    /**
     * Trial balance per account code computed straight from posted base amounts up to {@code asOf}. PRE_CLOSING drops
     * the period/fiscal-year closing entries (the engine has no such view yet — ACC-1); POST_CLOSING keeps them.
     */
    protected Map<String, BigDecimal> tbAsOf(String org, String asOf, TbView view) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        String closing = view == TbView.PRE_CLOSING
                ? " AND coalesce(e.\"sourceType\", '') NOT IN ('period_close', 'fiscal_year_close')" : "";
        for (Map<String, Object> r : jdbc.sql("SELECT a.code, sum(l.\"baseDebit\" - l.\"baseCredit\") AS net FROM"
                        + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON"
                        + " a.id = l.\"accountId\" WHERE l.\"organizationId\" = ? AND e.status IN ('posted', 'reversed')"
                        + " AND e.\"postingDate\" <= ?::timestamp" + closing + " GROUP BY a.code ORDER BY a.code")
                .params(org, asOf + " 23:59:59").query().listOfRows()) {
            out.put((String) r.get("code"), (BigDecimal) r.get("net"));
        }
        return out;
    }

    // ------------------------------------------------------------------ currencies

    private static final java.util.concurrent.atomic.AtomicInteger CCY = new java.util.concurrent.atomic.AtomicInteger();

    /** A unique test-only currency code (Q + 2 letters); the Currency table is global, so never reuse real codes. */
    protected String testCurrency() {
        while (true) {
            int n = (int) (Math.random() * 676) + CCY.incrementAndGet();
            String code = "Q" + (char) ('A' + (n / 26) % 26) + (char) ('A' + n % 26);
            if (jdbc.sql("SELECT count(*) FROM \"Currency\" WHERE code = ?").param(code).query(Long.class).single() == 0) {
                ok(201, "POST", "/currencies", Map.of("code", code, "symbol", code, "name", "ACC-0 " + code));
                return code;
            }
        }
    }

    /** Record a rate {@code from → to} as of {@code date} through the API, as {@code b}'s owner. */
    protected Res rate(Books b, String from, String to, String date, String rate) {
        return as(b, "POST", "/currencies/rates", Map.of("fromCode", from, "toCode", to, "asOf", date, "rate",
                new BigDecimal(rate)));
    }

    /** The rate {@code b}'s organization resolves for {@code from → to} at {@code date}. */
    protected BigDecimal resolvedRate(Books b, String from, String to, String date) {
        return new BigDecimal(okAs(b, 200, "GET", "/currencies/rate?fromCode=" + from + "&toCode=" + to + "&asOf="
                + date, null).get("rate").asString());
    }

    // ------------------------------------------------------------------ DEMO-org document helpers

    protected String today() {
        return LocalDate.now().toString();
    }

    protected String demoAdminId() {
        return jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
    }

    protected String demoPartner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "A0-" + UUID.randomUUID().toString().substring(0, 8), name + " "
                        + UUID.randomUUID().toString().substring(0, 4), supplier, !supplier).update();
        return id;
    }

    /** A DEMO product; {@code stock} = tracked stockable with {@code cost}, else a service. */
    protected String demoProduct(String name, boolean stock, int price, int cost) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "A0-" + UUID.randomUUID().toString().substring(0, 6), name,
                        stock ? "stockable" : "service", price, cost, stock).update();
        return id;
    }

    protected Map<String, String> demoKey() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    protected JsonNode demoMoney(String method, String path, Object body) {
        Res r = call(method, path, body, demoKey());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    /** Posted journal lines (code, debit, credit, base, currency, partner) of one entry, debits first. */
    protected List<Map<String, Object>> lines(String entryId) {
        return jdbc.sql("SELECT a.code, l.debit, l.credit, l.\"baseDebit\", l.\"baseCredit\", l.\"currencyId\","
                + " l.\"partnerId\" FROM \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE"
                + " l.\"journalEntryId\" = ? ORDER BY l.debit DESC, a.code").param(entryId).query().listOfRows();
    }

    /** Net base (debit − credit) of {@code accountId} over every entry of the given source documents. */
    protected BigDecimal netForSources(String org, String accountId, List<String> sourceIds) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND"
                        + " l.\"accountId\" = ? AND (e.\"sourceId\" = ANY(?::text[]) OR e.\"reversalOfId\" IN (SELECT id"
                        + " FROM \"JournalEntry\" WHERE \"sourceId\" = ANY(?::text[])))")
                .params(org, accountId, sourceIds.toArray(String[]::new), sourceIds.toArray(String[]::new))
                .query(BigDecimal.class).single();
    }
}
