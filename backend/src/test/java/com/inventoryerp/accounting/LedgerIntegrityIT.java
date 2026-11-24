package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.accounting.application.LedgerIntegrityMonitor;
import com.inventoryerp.accounting.application.LedgerIntegrityMonitor.Outcome;
import com.inventoryerp.accounting.application.LedgerIntegrityService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit 2026-11-23, step A0: {@code GET /reports/accounting/integrity} and the nightly
 * {@link LedgerIntegrityMonitor}. Clean books pass. Each defect class is injected (as raw rows where the engine refuses
 * it, the way a legacy row or a bypassing write would arrive) and reported by its own check. P1-1 (a period ending at
 * 00:00 of its last day) and P1-2 (a closing entry reversed while its period stays closed) are reproduced.
 */
class LedgerIntegrityIT extends AccountingFixtures {

    private static final String CASH = "1100";

    @Autowired
    LedgerIntegrityMonitor monitor;

    /** A raw journal line: amounts as decimal strings, {@code currency} null for a base line. */
    record Raw(String accountCode, String debit, String credit, String currency, String rate, String baseDebit,
            String baseCredit) {

        static Raw base(String accountCode, String debit, String credit) {
            return new Raw(accountCode, debit, credit, null, "1", debit, credit);
        }
    }

    @Test
    @DisplayName("clean books pass every check; injected defects fail their own check only, and only for their org")
    void cleanBooksPassAndDefectsAreReported() {
        Books clean = freshBooks();
        // P1-1 at onboarding: the bootstrap period ends at 00:00 of 31 December.
        JsonNode onboarding = check(report(clean), LedgerIntegrityService.PERIOD_COVERAGE);
        assertThat(onboarding.get("ok").asBoolean()).isFalse();
        assertThat(kinds(onboarding)).containsExactly("bounds");

        LocalDate day = LocalDate.now().minusDays(3);
        fiscalYears(clean, day);
        String revenue = accountCodeOfSection(clean.org(), "revenue");
        String sale = postJe(clean, day.toString(), List.of(dr(CASH, 1000), cr(revenue, 1000)));
        String ccy = testCurrency();
        rate(clean, ccy, baseCurrency(clean.org()), day.toString(), "1.08");
        postJe(clean, "GEN", day.toString(), ccy, "1.08", null, null,
                List.of(dr(receivableCode(clean), 100).partner(partner(clean)), cr(revenue, 100)));
        inTenant(clean.org(), clean.ownerId(), () -> posting.reverse(sale, null, null));
        JsonNode cleanReport = report(clean);
        assertThat(failing(cleanReport)).as(cleanReport.toString()).isEmpty();
        assertThat(cleanReport.get("ok").asBoolean()).isTrue();
        assertThat(cleanReport.get("checks")).hasSize(LedgerIntegrityService.CHECKS.size());
        assertThat(check(cleanReport, LedgerIntegrityService.INVENTORY_GL).get("skipped").asBoolean()).isTrue();

        Books broken = freshBooks();
        fiscalYears(broken, day);
        Instant at = Instant.parse(day + "T09:00:00Z");
        String recv = receivableCode(broken);
        String rev = accountCodeOfSection(broken.org(), "revenue");
        // I1: a posted entry with one all-zero line (balanced, so the database lets it through).
        String zero = insertPosted(broken, at, null, Raw.base(CASH, "0", "0"));
        // I3: balanced in base and in EUR, but line 1 carries 120 for 100 × 1.1.
        String drift = insertPosted(broken, at, ccy, new Raw(recv, "100", "0", ccy, "1.1", "120", "0"),
                new Raw(rev, "0", "100", ccy, "1.2", "0", "120"));
        // I2: balanced in base, 100 against 90 in its own currency.
        String unbalanced = insertPosted(broken, at, ccy, new Raw(recv, "100", "0", ccy, "1.1", "110", "0"),
                new Raw(rev, "0", "90", ccy, "1.22222222", "0", "110"));
        // Not a defect: a base-only FX adjustment line (amount 0, base 16), the shape of a payment allocation's
        // realised-FX line. A restored copy of real books showed it as a false positive of I3 before amount-0 lines
        // were exempted.
        insertPosted(broken, at, ccy, new Raw(recv, "0", "0", ccy, "1", "16", "0"), Raw.base(rev, "0", "16"));
        // I7: marked reversed with no reversal entry.
        String orphan = postJe(broken, day.toString(), List.of(dr(CASH, 5), cr(rev, 5)));
        jdbc.sql("UPDATE \"JournalEntry\" SET status = 'reversed', \"updatedAt\" = now() WHERE id = ?").param(orphan)
                .update();

        JsonNode r = report(broken);
        assertThat(failing(r)).containsExactlyInAnyOrder(LedgerIntegrityService.ENTRY_BALANCED,
                LedgerIntegrityService.ENTRY_CURRENCY_BALANCED, LedgerIntegrityService.FX_LINES,
                LedgerIntegrityService.REVERSAL_LINKS);
        JsonNode balanced = check(r, LedgerIntegrityService.ENTRY_BALANCED);
        assertThat(balanced.get("failures").asLong()).isEqualTo(1);
        assertThat(balanced.get("rows").get(0).get("journalEntryId").asString()).isEqualTo(zero);
        assertThat(balanced.get("rows").get(0).get("lines").asLong()).isEqualTo(1);
        assertThat(balanced.get("rows").get(0).get("zeroLines").asLong()).isEqualTo(1);
        JsonNode currency = check(r, LedgerIntegrityService.ENTRY_CURRENCY_BALANCED);
        assertThat(currency.get("rows")).hasSize(1);
        assertThat(currency.get("rows").get(0).get("journalEntryId").asString()).isEqualTo(unbalanced);
        JsonNode fx = check(r, LedgerIntegrityService.FX_LINES);
        assertThat(fx.get("rows")).hasSize(1);
        assertThat(fx.get("rows").get(0).get("journalEntryId").asString()).isEqualTo(drift);
        assertThat(fx.get("rows").get(0).get("lineNumber").asInt()).isEqualTo(1);
        assertThat(fx.get("note").asString()).startsWith("3 foreign-currency entries have no rate snapshot");
        JsonNode reversals = check(r, LedgerIntegrityService.REVERSAL_LINKS);
        assertThat(kinds(reversals)).containsExactly("reversed_without_mirror");
        assertThat(reversals.get("rows").get(0).get("journalEntryId").asString()).isEqualTo(orphan);

        // Another organisation's report is untouched.
        assertThat(failing(report(clean))).isEmpty();
    }

    @Test
    @DisplayName("P1-1: a period ending at 00:00 leaves its last day uncovered; closing it leaves that day's P&L open")
    void midnightPeriodEndIsDetected() {
        Books b = freshBooks();
        ZoneId zone = ZoneId.of("Africa/Kampala");
        jdbc.sql("UPDATE \"Organization\" SET timezone = ? WHERE id = ?").params(zone.getId(), b.org()).update();
        deletePeriods(b);
        LocalDate first = LocalDate.now(zone).withDayOfMonth(1).minusMonths(1);
        LocalDate last = first.plusMonths(1).minusDays(1);
        // Stored the way POST /fiscal-periods, onboarding and the demo seed store a date-only end today.
        String period = insertPeriod(b, "P " + first, first + " 00:00:00", last + " 00:00:00");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        post(b, first.plusDays(14).atTime(10, 0).atZone(zone).toInstant(), CASH, revenue, 1000);
        post(b, last.atTime(23, 30).atZone(zone).toInstant(), CASH, revenue, 500);

        JsonNode r = report(b);
        JsonNode coverage = check(r, LedgerIntegrityService.PERIOD_COVERAGE);
        assertThat(kinds(coverage)).containsExactly("bounds", "uncovered_day");
        assertThat(coverage.get("rows").get(0).get("periodId").asString()).isEqualTo(period);
        assertThat(coverage.get("rows").get(1).get("localDate").asString()).isEqualTo(last.toString());
        assertThat(coverage.get("rows").get(1).get("entries").asLong()).isEqualTo(1);
        assertThat(coverage.get("failures").asLong()).isEqualTo(2);
        assertThat(check(r, LedgerIntegrityService.CLOSED_PERIODS).get("ok").asBoolean()).isTrue();

        okAs(b, 200, "POST", "/fiscal-periods/" + period + "/close", null);
        // The close carried the P&L up to 00:00 of the last day only: the 23:30 sale stays open in a closed period.
        JsonNode closed = check(report(b), LedgerIntegrityService.CLOSED_PERIODS);
        assertThat(kinds(closed)).containsExactly("pnl_not_zero");
        assertThat(closed.get("rows").get(0).get("code").asString()).isEqualTo(revenue);
        assertThat(new BigDecimal(closed.get("rows").get(0).get("net").asString())).isEqualByComparingTo("-500");
        // ... and the closed period's last day still takes postings.
        post(b, last.atTime(23, 45).atZone(zone).toInstant(), CASH, revenue, 1);
        assertThat(check(report(b), LedgerIntegrityService.PERIOD_COVERAGE).get("rows").get(1).get("entries").asLong())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("P1-2: a closing entry reversed while its period stays closed is reported")
    void reversedClosingEntryIsDetected() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        fiscalYears(b, months);
        String saleDay = months[0].plusDays(14).toString();
        postJe(b, saleDay, List.of(dr(CASH, 1000), cr(accountCodeOfSection(b.org(), "revenue"), 1000)));
        String closing = closePeriod(b, saleDay);
        assertThat(closing).isNotNull();
        JsonNode before = report(b);
        assertThat(failing(before)).as(before.toString()).isEmpty();

        // What POST /journal-entries/{id}/reverse does today: the engine mirrors the closing entry into the open period.
        inTenant(b.org(), b.ownerId(), () -> posting.reverse(closing, null, null));
        JsonNode after = report(b);
        JsonNode closed = check(after, LedgerIntegrityService.CLOSED_PERIODS);
        assertThat(kinds(closed)).containsExactly("closing_entry_reversed");
        assertThat(closed.get("rows").get(0).get("periodId").asString()).isEqualTo(periodFor(b.org(), saleDay));
        assertThat(check(after, LedgerIntegrityService.REVERSAL_LINKS).get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("I11: a tax posting that reaches a settled VAT period, and a reversed settlement entry, are reported")
    void vatSettlementDriftIsDetected() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        fiscalYears(b, months);
        LocalDate from = months[0];
        LocalDate to = from.plusMonths(1).minusDays(1);
        String day = from.plusDays(9).toString();
        String payable = mappedCode(b.org(), "tax_payable");
        String receivable = mappedCode(b.org(), "tax_receivable");
        postJe(b, day, List.of(dr(CASH, 118), cr(accountCodeOfSection(b.org(), "revenue"), 100), cr(payable, 18)));
        postJe(b, day, List.of(dr(accountCodeOfSection(b.org(), "operating_expense"), 50), dr(receivable, 9),
                cr(CASH, 59)));
        JsonNode settled = okAs(b, 200, "POST", "/tax-settlements", Map.of("from", from.toString(), "to",
                to.toString()));
        assertThat(new BigDecimal(settled.get("outputTax").asString())).isEqualByComparingTo("18");
        assertThat(new BigDecimal(settled.get("inputTax").asString())).isEqualByComparingTo("9");
        assertThat(check(report(b), LedgerIntegrityService.TAX_LOCK).get("ok").asBoolean()).isTrue();

        // A write that bypassed the VAT lock lands in the settled period.
        insertPosted(b, Instant.parse(from.plusDays(11) + "T10:00:00Z"), null, Raw.base(CASH, "10", "0"),
                Raw.base(payable, "0", "10"));
        JsonNode drift = check(report(b), LedgerIntegrityService.TAX_LOCK);
        assertThat(kinds(drift)).containsExactly("settled_figures_changed");
        assertThat(new BigDecimal(drift.get("rows").get(0).get("outputTaxNow").asString())).isEqualByComparingTo("28");
        assertThat(new BigDecimal(drift.get("rows").get(0).get("settledOutputTax").asString()))
                .isEqualByComparingTo("18");

        // The settlement's offset entry reversed through the engine, as the generic route allows today (P1-2).
        String entry = settled.get("journalEntryId").asString();
        inTenant(b.org(), b.ownerId(), () -> posting.reverse(entry, null, null));
        assertThat(kinds(check(report(b), LedgerIntegrityService.TAX_LOCK)))
                .containsExactlyInAnyOrder("settlement_entry_not_posted", "settled_figures_changed");
    }

    @Test
    @DisplayName("monitor: opens one finding and one alert per failing check, refreshes it, resolves it once it passes")
    void monitorOpensRefreshesAndResolvesFindings() {
        Books b = freshBooks();
        LocalDate today = LocalDate.now();
        fiscalYears(b, today);
        postJe(b, today.toString(), List.of(dr(CASH, 10), cr(accountCodeOfSection(b.org(), "revenue"), 10)));
        Outcome first = monitor(b);
        assertThat(first.report().ok()).as(first.report().failing().toString()).isTrue();
        assertThat(first.opened()).isEmpty();

        // Next January, outside the fiscal year: a period ending at 00:00.
        LocalDate jan = LocalDate.of(today.getYear() + 1, 1, 1);
        String period = insertPeriod(b, "P " + jan, jan + " 00:00:00", jan.plusDays(30) + " 00:00:00");
        Outcome second = monitor(b);
        assertThat(second.opened()).containsExactly(LedgerIntegrityService.PERIOD_COVERAGE);
        Map<String, Object> finding = finding(b);
        assertThat(finding.get("status")).isEqualTo("open");
        assertThat(((Number) finding.get("failures")).longValue()).isEqualTo(1);
        assertThat(String.valueOf(finding.get("sample"))).contains(period);
        assertThat(alerts(b)).isEqualTo(1);

        Outcome third = monitor(b);
        assertThat(third.opened()).isEmpty();
        assertThat(alerts(b)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"LedgerIntegrityFinding\" WHERE \"organizationId\" = ?")
                .param(b.org()).query(Long.class).single()).isEqualTo(1);

        jdbc.sql("UPDATE \"FiscalPeriod\" SET \"endDate\" = ?::timestamp WHERE id = ?")
                .params(jan.plusDays(30) + " 23:59:59.999", period).update();
        Outcome fourth = monitor(b);
        assertThat(fourth.report().ok()).isTrue();
        assertThat(fourth.resolved()).containsExactly(LedgerIntegrityService.PERIOD_COVERAGE);
        Map<String, Object> resolved = finding(b);
        assertThat(resolved.get("status")).isEqualTo("resolved");
        assertThat(resolved.get("resolvedAt")).isNotNull();
    }

    @Test
    @DisplayName("the check's wall-clock SQL agrees with AccountingDay for region zones and fixed offsets")
    void zoneSqlMatchesJavaConversions() {
        LocalDateTime wall = LocalDateTime.of(2026, 7, 1, 0, 0);
        Instant asUtcFields = wall.toInstant(ZoneOffset.UTC);
        for (String id : List.of("+03:00", "-05:00", "UTC+03:00", "Africa/Kampala", "America/New_York", "UTC")) {
            ZoneId zone = ZoneId.of(id);
            Timestamp utc = jdbc.sql("SELECT " + LedgerIntegrityService.wallToUtc("CAST(? AS timestamp)", zone))
                    .param(Timestamp.valueOf(wall)).query(Timestamp.class).single();
            assertThat(utc.toLocalDateTime().toInstant(ZoneOffset.UTC)).as(id)
                    .isEqualTo(AccountingDay.fromWallClock(asUtcFields, zone));
            Timestamp local = jdbc.sql("SELECT " + LedgerIntegrityService.utcToWall("CAST(? AS timestamp)", zone))
                    .param(Timestamp.valueOf(wall)).query(Timestamp.class).single();
            assertThat(local.toLocalDateTime().toInstant(ZoneOffset.UTC)).as(id)
                    .isEqualTo(AccountingDay.toWallClock(asUtcFields, zone));
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode report(Books b) {
        return okAs(b, 200, "GET", "/reports/accounting/integrity", null);
    }

    private static JsonNode check(JsonNode report, String key) {
        for (JsonNode c : report.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key + " in " + report);
    }

    private static List<String> kinds(JsonNode check) {
        List<String> out = new ArrayList<>();
        check.get("rows").forEach(r -> out.add(r.has("kind") ? r.get("kind").asString() : "?"));
        return out;
    }

    private static List<String> failing(JsonNode report) {
        List<String> out = new ArrayList<>();
        report.get("checks").forEach(c -> {
            if (!c.get("ok").asBoolean()) {
                out.add(c.get("key").asString());
            }
        });
        return out;
    }

    private Outcome monitor(Books b) {
        return TenantContext.run(TenantStore.organization(b.org()), monitor::check);
    }

    private Map<String, Object> finding(Books b) {
        return SqlRows.one(jdbc.sql("SELECT status, failures, sample::text AS sample, \"resolvedAt\" FROM"
                + " \"LedgerIntegrityFinding\" WHERE \"organizationId\" = ? ORDER BY \"firstSeenAt\" DESC LIMIT 1")
                .param(b.org()));
    }

    private long alerts(Books b) {
        return jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category = 'accounting'"
                + " AND payload ->> 'kind' = ?").params(b.org(), LedgerIntegrityMonitor.KIND).query(Long.class).single();
    }

    private void deletePeriods(Books b) {
        for (String id : jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .param(b.org()).query(String.class).list()) {
            okAs(b, 204, "DELETE", "/fiscal-periods/" + id, null);
        }
        // Purge the soft-deleted rows: the year-long bootstrap period would tie with January in lookups by start date.
        jdbc.sql("DELETE FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL").param(b.org())
                .update();
    }

    /** Replace the bootstrap period by monthly fiscal-year periods (ending 23:59:59.999) for the years of {@code days}. */
    private void fiscalYears(Books b, LocalDate... days) {
        deletePeriods(b);
        for (int year : new TreeSet<>(Arrays.stream(days).map(LocalDate::getYear).toList())) {
            JsonNode fy = okAs(b, 201, "POST", "/fiscal-years", Map.of("startDate", year + "-01-01", "periodicity",
                    "monthly"));
            assertThat(fy.get("periods")).hasSize(12);
        }
    }

    private String insertPeriod(Books b, String name, String start, String end) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                + " \"updatedAt\") VALUES (?, ?, ?, ?::timestamp, ?::timestamp, 'open', now())")
                .params(id, b.org(), name, start, end).update();
        return id;
    }

    /** One balanced base entry through the engine at an exact instant. */
    private void post(Books b, Instant at, String debitCode, String creditCode, int amount) {
        inTenant(b.org(), b.ownerId(), () -> posting.post(PostingRequest.of("GEN", at, "Integrity " + at,
                "integrity_it", UUID.randomUUID().toString(), null, List.of(
                        PostingRequest.Line.debit(account(b.org(), debitCode), amount, "Dr"),
                        PostingRequest.Line.credit(account(b.org(), creditCode), amount, "Cr")))));
    }

    /** A posted entry written straight to the tables in one transaction, as a legacy row or a bypassing write. */
    private String insertPosted(Books b, Instant at, String currency, Raw... lines) {
        String id = UUID.randomUUID().toString();
        String journal = jdbc.sql("SELECT id FROM \"Journal\" WHERE \"organizationId\" = ? AND code = 'GEN'")
                .param(b.org()).query(String.class).single();
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO \"JournalEntry\" (id, \"organizationId\", \"journalId\", \"entryNumber\","
                            + " \"postingDate\", status, \"currencyId\", \"sourceType\", \"postedAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, 'posted', ?, 'integrity_it', now(), now())")
                    .params(id, b.org(), journal, "IT/" + id.substring(0, 8), SqlRows.ts(at), currency).update();
            int n = 1;
            for (Raw l : lines) {
                jdbc.sql("INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", debit,"
                                + " credit, \"currencyId\", \"exchangeRate\", \"baseDebit\", \"baseCredit\", \"lineNumber\")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(UUID.randomUUID().toString(), b.org(), id, account(b.org(), l.accountCode()),
                                new BigDecimal(l.debit()), new BigDecimal(l.credit()), l.currency(),
                                new BigDecimal(l.rate()), new BigDecimal(l.baseDebit()), new BigDecimal(l.baseCredit()),
                                n++)
                        .update();
            }
        });
        return id;
    }

    /** The receivable control account (foreign lines live on receivable / payable accounts, FX-2 C). */
    private String receivableCode(Books b) {
        return jdbc.sql("SELECT a.code FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key = 'receivable' AND a.\"isPostable\" AND a.\"isActive\""
                + " AND a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(b.org()).query(String.class).single();
    }

    private String partner(Books b) {
        return okAs(b, 201, "POST", "/partners", Map.of("code", "LI-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Integrity partner", "isCustomer", true)).get("id").asString();
    }
}
