package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finance readiness audit 2026-11-23, P1-1 (fix step A1). Fiscal period bounds are org-local wall clock (ADR-S070), so a
 * period ends at 23:59:59.999 of its last day. A date-only end used to be stored as 00:00 of that day, by
 * {@code POST/PATCH /fiscal-periods}, at onboarding and by the demo seed: postings later on the last day fell outside
 * every period, the close left their P&L open, and a closed period's last day still took postings.
 */
class PeriodBoundsIT extends AccountingFixtures {

    private static final String CASH = "1100";

    @Test
    @DisplayName("P1-1: a period created with a date-only end covers its whole last day: closed with it, then locked")
    void dateOnlyEndCoversTheLastDay() {
        Books b = freshBooks();
        ZoneId zone = ZoneId.of("Africa/Kampala");
        jdbc.sql("UPDATE \"Organization\" SET timezone = ? WHERE id = ?").params(zone.getId(), b.org()).update();
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        LocalDate first = LocalDate.now(zone).withDayOfMonth(1).minusMonths(1);
        LocalDate last = first.plusMonths(1).minusDays(1);
        String period = monthlyPeriods(b, first).getFirst();
        String revenue = accountCodeOfSection(b.org(), "revenue");
        post(b, first.plusDays(14).atTime(10, 0).atZone(zone).toInstant(), CASH, revenue, 1000);
        post(b, last.atTime(23, 30).atZone(zone).toInstant(), CASH, revenue, 500);

        okAs(b, 200, "POST", "/fiscal-periods/" + period + "/close", null);
        // The close carried the last day's sale too: revenue nets to zero over the period's whole days.
        assertThat(net(b, revenue, AccountingDay.start(first, zone), AccountingDay.endInclusive(last, zone)))
                .isEqualByComparingTo("0");
        // ... and the closed period's last day refuses postings.
        assertThatThrownBy(() -> post(b, last.atTime(23, 45).atZone(zone).toInstant(), CASH, revenue, 1))
                .hasMessageContaining("is closed; reopen or post to a different period");

        // Stored and served as the end of the last day; the onboarding period (deleted above) ended on 31 December.
        assertThat(bounds(period)).isEqualTo(first + " 00:00:00.000 / " + last + " 23:59:59.999");
        assertThat(okAs(b, 200, "GET", "/fiscal-periods/" + period, null).get("endDate").asString())
                .isEqualTo(last + "T23:59:59.999Z");
        String onboarding = jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND name = ?")
                .params(b.org(), "FY" + year).query(String.class).single();
        assertThat(bounds(onboarding)).isEqualTo(year + "-01-01 00:00:00.000 / " + year + "-12-31 23:59:59.999");
    }

    @Test
    @DisplayName("P1-1: PATCH with a date-only end stores the end of that day; a full timestamp is stored as given")
    void patchKeepsTheEndOfTheDay() {
        Books b = freshBooks();
        LocalDate first = LocalDate.now(ZoneOffset.UTC).plusYears(2).withDayOfYear(1);
        String id = okAs(b, 201, "POST", "/fiscal-periods", Map.of("name", "Future " + first, "startDate",
                first.toString(), "endDate", first.plusMonths(1).minusDays(1).toString())).get("id").asString();
        assertThat(bounds(id)).isEqualTo(first + " 00:00:00.000 / " + first.plusDays(30) + " 23:59:59.999");

        okAs(b, 200, "PATCH", "/fiscal-periods/" + id, Map.of("endDate", first.plusDays(9).toString()));
        assertThat(bounds(id)).isEqualTo(first + " 00:00:00.000 / " + first.plusDays(9) + " 23:59:59.999");

        // Re-sending the same date-only end changes nothing.
        okAs(b, 200, "PATCH", "/fiscal-periods/" + id, Map.of("endDate", first.plusDays(9).toString()));
        assertThat(bounds(id)).isEqualTo(first + " 00:00:00.000 / " + first.plusDays(9) + " 23:59:59.999");

        // A full timestamp is an explicit instant (wall clock) and is kept.
        okAs(b, 200, "PATCH", "/fiscal-periods/" + id, Map.of("endDate", first.plusDays(4) + "T12:00:00.000Z"));
        assertThat(bounds(id)).isEqualTo(first + " 00:00:00.000 / " + first.plusDays(4) + " 12:00:00.000");
    }

    /** {@code startDate / endDate} of a period as stored (wall clock, millisecond precision). */
    private String bounds(String periodId) {
        return jdbc.sql("SELECT to_char(\"startDate\", 'YYYY-MM-DD HH24:MI:SS.MS') || ' / '"
                        + " || to_char(\"endDate\", 'YYYY-MM-DD HH24:MI:SS.MS') FROM \"FiscalPeriod\" WHERE id = ?")
                .param(periodId).query(String.class).single();
    }

    /** Base net (debit − credit) of an account over posted entries dated in {@code [from, to]}. */
    private BigDecimal net(Books b, String code, Instant from, Instant to) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                        + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\""
                        + " WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND a.code = ?"
                        + " AND e.\"postingDate\" BETWEEN ? AND ?")
                .params(b.org(), code, SqlRows.ts(from), SqlRows.ts(to)).query(BigDecimal.class).single();
    }

    /** One balanced base entry through the engine at an exact instant. */
    private void post(Books b, Instant at, String debitCode, String creditCode, int amount) {
        inTenant(b.org(), b.ownerId(), () -> posting.post(PostingRequest.of("GEN", at, "Bounds " + at, "bounds_it",
                UUID.randomUUID().toString(), null, List.of(
                        PostingRequest.Line.debit(account(b.org(), debitCode), amount, "Dr"),
                        PostingRequest.Line.credit(account(b.org(), creditCode), amount, "Cr")))));
    }
}
