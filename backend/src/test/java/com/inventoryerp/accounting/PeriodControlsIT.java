package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.FiscalPeriodCoverageService;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit B1 (P2-2): the period controls ({@code GET|PUT /fiscal-periods/controls}), the far-future
 * guard and next year's periods ({@link FiscalPeriodCoverageService}, run as the nightly job runs it: no user). Every
 * test works in its own fresh organization (UTC, one yearly period for the current year): a lock date on DEMO would
 * stop the other tests.
 */
class PeriodControlsIT extends AccountingFixtures {

    private static final String CASH = "1100";
    private static final String CONTROLS = "/fiscal-periods/controls";

    @Autowired
    FiscalPeriodCoverageService coverage;

    private static LocalDate utcToday() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static Map<String, Object> controls(String lock, boolean require, boolean auto, int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("booksLockDate", lock);
        m.put("requireFiscalPeriod", require);
        m.put("autoCreatePeriods", auto);
        m.put("futurePostingLimitDays", limit);
        return m;
    }

    private static String message(Res r) {
        return r.body().get("message").asString();
    }

    /** A manual entry posted through the engine at 10:00 on {@code day}. */
    private String post(Books b, LocalDate day) {
        return postManualJe(b, day.toString(), List.of(dr(CASH, 10), cr(accountCodeOfSection(b.org(), "revenue"), 10)));
    }

    /** A draft manual journal entry through {@code POST /journal-entries}. */
    private String draft(Books b, LocalDate day) {
        Map<String, Object> debit = new LinkedHashMap<>();
        debit.put("accountId", account(b.org(), CASH));
        debit.put("debit", 25);
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("accountId", account(b.org(), accountCodeOfSection(b.org(), "revenue")));
        credit.put("credit", 25);
        Res r = moneyAs(b, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", day.toString(),
                "description", "B1 draft", "lines", List.of(debit, credit)));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("id").asString();
    }

    /** A user of {@code b}'s organization whose role holds exactly {@code permissions}. */
    private String userHolding(Books b, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, b.org(), "B1-" + role, permissions).update();
        String email = "clerk-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Clerk@12345", role);
        return login(b.code(), email, "Clerk@12345");
    }

    private String storedLock(Books b) {
        return jdbc.sql("SELECT to_char(\"booksLockDate\", 'YYYY-MM-DD HH24:MI:SS.MS') FROM \"Organization\" WHERE id = ?")
                .param(b.org()).query(String.class).optional().orElse(null);
    }

    /** One coverage run for {@code b}, as the nightly job makes it (organization context, no user). */
    private FiscalPeriodCoverageService.Result coverage(Books b) {
        return TenantContext.run(TenantStore.organization(b.org()), coverage::run);
    }

    private long notifications(Books b, String action) {
        return jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ?"
                        + " AND payload->>'kind' = 'fiscal_period_coverage' AND payload->>'action' = ?")
                .params(b.org(), action).query(Long.class).single();
    }

    /** Replace the bootstrap period by a monthly fiscal year that ends with the current month; returns its end. */
    private LocalDate yearEndingThisMonth(Books b) {
        jdbc.sql("UPDATE \"FiscalPeriod\" SET \"deletedAt\" = now() WHERE \"organizationId\" = ?").param(b.org())
                .update();
        LocalDate end = utcToday().withDayOfMonth(utcToday().lengthOfMonth());
        okAs(b, 201, "POST", "/fiscal-years", Map.of("startDate", end.plusDays(1).minusYears(1).toString(), "endDate",
                end.toString(), "periodicity", "monthly", "createPeriods", true));
        return end;
    }

    @Test
    @DisplayName("GET shows the defaults: no lock, no period required, periods created ahead, ten years ahead at most")
    void defaults() {
        Books b = freshBooks();
        JsonNode c = okAs(b, 200, "GET", CONTROLS, null);
        assertThat(c.get("booksLockDate").isNull()).isTrue();
        assertThat(c.get("requireFiscalPeriod").asBoolean()).isFalse();
        assertThat(c.get("autoCreatePeriods").asBoolean()).isTrue();
        assertThat(c.get("futurePostingLimitDays").asInt()).isEqualTo(3650);
        assertThat(c.get("today").asString()).isEqualTo(utcToday().toString());
        assertThat(c.get("periodsThrough").asString()).isEqualTo(utcToday().getYear() + "-12-31");
        assertThat(c.get("openPeriodCoversToday").asBoolean()).isTrue();
        assertThat(c.get("autoCreateLeadDays").asInt()).isEqualTo(FiscalPeriodCoverageService.LEAD_DAYS);
    }

    @Test
    @DisplayName("a lock date locks its whole day, must be before today, waits for older drafts and is audited")
    void lockDate() {
        Books b = freshBooks();
        LocalDate yesterday = utcToday().minusDays(1);
        Res sameDay = as(b, "PUT", CONTROLS, controls(utcToday().toString(), false, true, 3650));
        assertThat(sameDay.status()).as(sameDay.raw().body()).isEqualTo(400);
        assertThat(message(sameDay)).isEqualTo("The books lock date must be before today (" + utcToday() + ")");

        String draft = draft(b, yesterday);
        Res waiting = as(b, "PUT", CONTROLS, controls(yesterday.toString(), false, true, 3650));
        assertThat(waiting.status()).as(waiting.raw().body()).isEqualTo(400);
        assertThat(message(waiting)).isEqualTo("1 draft journal entry is dated on or before " + yesterday
                + "; post or delete it before locking the books");
        Res deleted = as(b, "DELETE", "/journal-entries/" + draft, null);
        assertThat(deleted.status()).as(deleted.raw().body()).isBetween(200, 204);

        JsonNode locked = okAs(b, 200, "PUT", CONTROLS, controls(yesterday.toString(), false, true, 3650));
        assertThat(locked.get("booksLockDate").asString()).isEqualTo(yesterday.toString());
        assertThat(storedLock(b)).isEqualTo(yesterday + " 23:59:59.999");
        assertThatThrownBy(() -> post(b, yesterday)).isInstanceOf(ApiException.class)
                .hasMessage("Books are locked through " + yesterday + "; cannot post on " + yesterday);
        assertThat(post(b, utcToday())).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity = 'Organization'"
                + " AND \"newValues\"->>'booksLockDate' = ?").params(b.org(), yesterday.toString()).query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    @DisplayName("moving the lock date earlier or removing it needs fiscal_period:reopen; moving it later does not")
    void looseningNeedsReopen() {
        Books b = freshBooks();
        LocalDate lock = utcToday().minusDays(2);
        okAs(b, 200, "PUT", CONTROLS, controls(lock.toString(), false, true, 3650));
        String clerk = userHolding(b, Permissions.FISCAL_PERIOD__READ, Permissions.FISCAL_PERIOD__UPDATE);

        Res later = call("PUT", CONTROLS, controls(utcToday().minusDays(1).toString(), false, true, 3650), clerk);
        assertThat(later.status()).as(later.raw().body()).isEqualTo(200);
        for (String earlier : Arrays.asList(lock.toString(), null)) {
            Res r = call("PUT", CONTROLS, controls(earlier, false, true, 3650), clerk);
            assertThat(r.status()).as(r.raw().body()).isEqualTo(403);
            assertThat(message(r)).isEqualTo("Missing required permission(s): " + Permissions.FISCAL_PERIOD__REOPEN);
        }
        assertThat(storedLock(b)).isEqualTo(utcToday().minusDays(1) + " 23:59:59.999");

        JsonNode cleared = okAs(b, 200, "PUT", CONTROLS, controls(null, false, true, 3650));
        assertThat(cleared.get("booksLockDate").isNull()).isTrue();
        assertThat(storedLock(b)).isNull();
    }

    @Test
    @DisplayName("requiring a period refuses postings outside every period, and needs an open period covering today")
    void requirePeriod() {
        Books b = freshBooks();
        okAs(b, 200, "PUT", CONTROLS, controls(null, true, true, 3650));
        LocalDate uncovered = LocalDate.of(utcToday().getYear() + 1, 6, 15);
        assertThatThrownBy(() -> post(b, uncovered)).isInstanceOf(ApiException.class)
                .hasMessage("No fiscal period is defined for " + uncovered + "; posting requires an open period");
        assertThat(post(b, utcToday())).isNotNull();

        okAs(b, 200, "PUT", CONTROLS, controls(null, false, true, 3650));
        jdbc.sql("UPDATE \"FiscalPeriod\" SET \"deletedAt\" = now() WHERE \"organizationId\" = ?").param(b.org()).update();
        Res refused = as(b, "PUT", CONTROLS, controls(null, true, true, 3650));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(message(refused)).isEqualTo("No open fiscal period covers today (" + utcToday()
                + "); create one before requiring a period for every posting");
    }

    @Test
    @DisplayName("a posting further ahead than the limit is refused; the limit is a setting and 0 removes it")
    void futureLimit() {
        Books b = freshBooks();
        LocalDate decade = utcToday().plusDays(3651);
        assertThatThrownBy(() -> post(b, decade)).isInstanceOf(ApiException.class).hasMessage("Posting date " + decade
                + " is more than 3650 days after today (" + utcToday() + "); check the date");
        assertThat(post(b, utcToday().plusDays(3650))).isNotNull();

        okAs(b, 200, "PUT", CONTROLS, controls(null, false, true, 366));
        LocalDate nextYear = utcToday().plusDays(367);
        assertThatThrownBy(() -> post(b, nextYear)).isInstanceOf(ApiException.class).hasMessage("Posting date "
                + nextYear + " is more than 366 days after today (" + utcToday() + "); check the date");
        Res draft = moneyAs(b, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", nextYear.toString(),
                "description", "B1 far draft", "lines", List.of(Map.of("accountId", account(b.org(), CASH), "debit", 5),
                        Map.of("accountId", account(b.org(), "3100"), "credit", 5))));
        assertThat(draft.status()).as(draft.raw().body()).isEqualTo(400);
        assertThat(post(b, utcToday().plusDays(366))).isNotNull();

        JsonNode off = okAs(b, 200, "PUT", CONTROLS, controls(null, false, true, 0));
        assertThat(off.get("futurePostingLimitDays").asInt()).isZero();
        assertThat(post(b, decade)).isNotNull();

        Res invalid = as(b, "PUT", CONTROLS, controls(null, false, true, -1));
        assertThat(invalid.status()).as(invalid.raw().body()).isEqualTo(400);
    }

    @Test
    @DisplayName("60 days before the last period ends, next year is created once, with the year's periodicity")
    void createsNextYear() {
        Books b = freshBooks();
        LocalDate end = yearEndingThisMonth(b);
        LocalDate nextStart = end.plusDays(1);
        LocalDate nextEnd = nextStart.plusYears(1).minusDays(1);

        FiscalPeriodCoverageService.Result r = coverage(b);
        assertThat(r.outcome()).as(r.toString()).isEqualTo("created");
        Map<String, Object> year = jdbc.sql("SELECT id, \"endDate\"::text AS \"end\", periodicity FROM \"FiscalYear\""
                + " WHERE \"organizationId\" = ? AND \"startDate\" = ?").params(b.org(), Date.valueOf(nextStart))
                .query().singleRow();
        assertThat(year).containsEntry("end", nextEnd.toString()).containsEntry("periodicity", "monthly");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FiscalPeriod\" WHERE \"fiscalYearId\" = ? AND \"deletedAt\" IS NULL"
                + " AND status::text = 'open'").param(year.get("id")).query(Long.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("SELECT to_char(max(\"endDate\"), 'YYYY-MM-DD HH24:MI:SS.MS') FROM \"FiscalPeriod\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL").param(b.org()).query(String.class).single())
                .isEqualTo(nextEnd + " 23:59:59.999");
        assertThat(notifications(b, "created")).isEqualTo(1);

        assertThat(coverage(b).outcome()).isEqualTo("covered");
        assertThat(jdbc.sql("SELECT count(*) FROM \"FiscalYear\" WHERE \"organizationId\" = ?").param(b.org())
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("with automatic periods off, or when next year cannot be created, an alert is raised once a week")
    void alertsInstead() {
        Books b = freshBooks();
        LocalDate end = yearEndingThisMonth(b);
        okAs(b, 200, "PUT", CONTROLS, controls(null, false, false, 3650));

        FiscalPeriodCoverageService.Result off = coverage(b);
        assertThat(off).isEqualTo(new FiscalPeriodCoverageService.Result("alerted", "off"));
        assertThat(coverage(b).detail()).isEqualTo("(alerted this week already) off");
        assertThat(notifications(b, "missing")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FiscalYear\" WHERE \"organizationId\" = ?").param(b.org())
                .query(Long.class).single()).isEqualTo(1);

        // On again, but next year already exists without periods: the alert gives the reason.
        okAs(b, 200, "PUT", CONTROLS, controls(null, false, true, 3650));
        String next = okAs(b, 201, "POST", "/fiscal-years", Map.of("startDate", end.plusDays(1).toString(),
                "periodicity", "monthly", "createPeriods", false)).get("name").asString();
        jdbc.sql("DELETE FROM \"Notification\" WHERE \"organizationId\" = ?").param(b.org()).update();
        FiscalPeriodCoverageService.Result blocked = coverage(b);
        assertThat(blocked).isEqualTo(new FiscalPeriodCoverageService.Result("alerted",
                "The dates overlap fiscal year " + next));
        assertThat(jdbc.sql("SELECT body FROM \"Notification\" WHERE \"organizationId\" = ?").param(b.org())
                .query(String.class).single()).isEqualTo("The last fiscal period ends on " + end + ". They could not be"
                        + " created automatically: The dates overlap fiscal year " + next + ". Create them under"
                        + " Accounting → Fiscal years.");
    }
}
