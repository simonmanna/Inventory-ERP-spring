package com.inventoryerp.accounting.verification;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S070: the accounting day is the organisation-local calendar day. An organisation on Africa/Kampala (UTC+3)
 * books a sale at 22:30 UTC on 31 January as a 1 February entry everywhere: period control, entry numbering, trial
 * balance, P&amp;L, general ledger and the period close. Date-only postings land on their date in any zone.
 */
class AccountingDayIT extends AccountingFixtures {

    private static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private void zone(Books b, ZoneId zone) {
        jdbc.sql("UPDATE \"Organization\" SET timezone = ? WHERE id = ?").params(zone.getId(), b.org()).update();
    }

    /** A whole-day period (as fiscal years create them): start 00:00, end 23:59:59.999 (org-local wall clock). */
    private String period(Books b, LocalDate start, LocalDate end, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?::\"FiscalPeriodStatus\", now())")
                .params(id, b.org(), "P " + start + " " + id.substring(0, 4), Timestamp.valueOf(start.atStartOfDay()),
                        Timestamp.valueOf(end.atTime(23, 59, 59, 999_000_000)), status)
                .update();
        return id;
    }

    private void status(String periodId, String status) {
        jdbc.sql("UPDATE \"FiscalPeriod\" SET status = ?::\"FiscalPeriodStatus\" WHERE id = ?").params(status, periodId)
                .update();
    }

    /** Post at an exact instant through the single GL writer; returns the rendered entry. */
    private Map<String, Object> postAt(Books b, Instant at, List<JeLine> lines) {
        List<PostingRequest.Line> out = new ArrayList<>();
        for (JeLine l : lines) {
            out.add(new PostingRequest.Line(account(b.org(), l.accountCode()),
                    l.debit().signum() == 0 ? null : l.debit(), l.credit().signum() == 0 ? null : l.credit(), null,
                    l.accountCode(), null, null, null));
        }
        return inTenant(b.org(), b.ownerId(), () -> posting.post(PostingRequest.of("GEN", at, "tz " + at, "acc_tz",
                UUID.randomUUID().toString(), null, out)));
    }

    private String refusal(Books b, Instant at, List<JeLine> lines) {
        try {
            postAt(b, at, lines);
            return null;
        } catch (com.inventoryerp.shared.web.ApiException e) {
            return e.getMessage();
        }
    }

    private static BigDecimal tbBalance(JsonNode tb, String code) {
        for (JsonNode r : tb.get("rows")) {
            if (code.equals(r.get("code").asString())) {
                return new BigDecimal(r.get("balance").asString());
            }
        }
        return BigDecimal.ZERO;
    }

    private Instant postingDate(String entryId) {
        return jdbc.sql("SELECT \"postingDate\" FROM \"JournalEntry\" WHERE id = ?").param(entryId)
                .query(Timestamp.class).single().toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("[ADR-S070] Kampala: 22:30 UTC on the last day of M is an M+1 entry in reports and period control")
    void boundaryPostingBelongsToNextLocalDay() {
        Books b = freshBooks();
        zone(b, KAMPALA);
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        int y = LocalDate.now().getYear() - 1;
        String jan = period(b, LocalDate.of(y, 1, 1), LocalDate.of(y, 1, 31), "open");
        String feb = period(b, LocalDate.of(y, 2, 1), LocalDate.of(y, 2, 28), "open");

        postAt(b, Instant.parse(y + "-01-15T09:00:00Z"), List.of(dr(cash, 100), cr(revenue, 100)));
        Instant boundary = Instant.parse(y + "-01-31T22:30:00Z"); // 01:30 on 1 February in Kampala
        String boundaryId = (String) postAt(b, boundary, List.of(dr(cash, 50), cr(revenue, 50))).get("id");
        assertThat(postingDate(boundaryId)).as("real timestamps are stored unchanged").isEqualTo(boundary);

        // Reports: in February, not in January (bare dates are Kampala days).
        String febFrom = y + "-02-01";
        String febTo = y + "-02-28";
        assertThat(tbBalance(trialBalance(b, febFrom, febTo), revenue)).as("February trial balance")
                .isEqualByComparingTo("-50");
        assertThat(tbBalance(trialBalance(b, y + "-01-01", febFrom), revenue)).as("January trial balance")
                .isEqualByComparingTo("-100");
        assertThat(new BigDecimal(pnl(b, febFrom, febTo).get("revenue").asString())).as("February P&L")
                .isEqualByComparingTo("50");
        assertThat(new BigDecimal(pnl(b, y + "-01-01", febFrom).get("revenue").asString())).as("January P&L")
                .isEqualByComparingTo("100");
        JsonNode glFeb = okAs(b, 200, "GET", "/reports/accounting/general-ledger?from=" + febFrom + "&to=" + febTo,
                null);
        assertThat(glFeb.get("meta").get("total").asLong()).as("February general ledger lines").isEqualTo(2);
        JsonNode glJan = okAs(b, 200, "GET", "/reports/accounting/general-ledger?from=" + y + "-01-01&to=" + febFrom,
                null);
        assertThat(glJan.get("meta").get("total").asLong()).as("January general ledger lines").isEqualTo(2);

        // Period close of January nets January only (the boundary entry is February's).
        String closing = closePeriod(b, y + "-01-15");
        assertThat(closing).isNotNull();
        BigDecimal closedRevenue = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = ? AND l.\"accountId\" = ?").params(closing, account(b.org(), revenue))
                .query(BigDecimal.class).single();
        assertThat(closedRevenue).as("January closing zeroes January revenue only").isEqualByComparingTo("100");
        Instant closingDate = postingDate(closing);
        assertThat(AccountingDay.of(closingDate, KAMPALA)).as("closing entry on the last local day of January")
                .isEqualTo(LocalDate.of(y, 1, 31));

        // January closed, February open: the boundary instant is accepted, a late-January one refused.
        assertThat(refusal(b, boundary, List.of(dr(cash, 5), cr(revenue, 5)))).as("accepted into open February")
                .isNull();
        assertThat(refusal(b, Instant.parse(y + "-01-31T20:30:00Z"), List.of(dr(cash, 5), cr(revenue, 5))))
                .as("23:30 on 31 January in Kampala is January").contains("is closed");

        // January open, February closed: the boundary instant is refused.
        okAs(b, 200, "POST", "/fiscal-periods/" + jan + "/reopen", null);
        status(feb, "closed");
        assertThat(refusal(b, boundary, List.of(dr(cash, 5), cr(revenue, 5)))).as("refused: February is closed")
                .contains("is closed");
        assertThat(refusal(b, Instant.parse(y + "-01-31T20:30:00Z"), List.of(dr(cash, 5), cr(revenue, 5))))
                .as("late January accepted again").isNull();
    }

    @Test
    @DisplayName("[ADR-S070] Kampala: entry-number year follows the local year at the year boundary")
    void entryNumberYearIsLocal() {
        Books b = freshBooks();
        zone(b, KAMPALA);
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        int y = LocalDate.now().getYear() - 1;
        Map<String, Object> e = postAt(b, Instant.parse(y + "-12-31T22:30:00Z"), List.of(dr(cash, 10), cr(revenue, 10)));
        assertThat((String) e.get("entryNumber")).startsWith("GEN/" + (y + 1) + "/");
        Map<String, Object> before = postAt(b, Instant.parse(y + "-12-31T20:30:00Z"),
                List.of(dr(cash, 10), cr(revenue, 10)));
        assertThat((String) before.get("entryNumber")).startsWith("GEN/" + y + "/");
    }

    @Test
    @DisplayName("[ADR-S070] date-only postings land on their date in a positive and a negative offset zone")
    void dateOnlyPostingsLandOnTheirDate() {
        for (ZoneId zone : List.of(KAMPALA, NEW_YORK)) {
            Books b = freshBooks();
            zone(b, zone);
            String cash = mappedCode(b.org(), "default_cash");
            String revenue = accountCodeOfSection(b.org(), "revenue");
            LocalDate d = LocalDate.now().minusDays(20);
            // Callers build a date-only posting as UTC midnight of the date.
            String id = (String) postAt(b, d.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    List.of(dr(cash, 70), cr(revenue, 70))).get("id");
            Instant stored = postingDate(id);
            assertThat(AccountingDay.of(stored, zone)).as(zone + " accounting day").isEqualTo(d);
            assertThat(stored).as(zone + " local noon").isEqualTo(d.atTime(12, 0).atZone(zone).toInstant());
            assertThat(new BigDecimal(pnl(b, d.toString(), d.plusDays(1).toString()).get("revenue").asString()))
                    .as(zone + " P&L of day D").isEqualByComparingTo("70");
            assertThat(new BigDecimal(pnl(b, d.minusDays(1).toString(), d.toString()).get("revenue").asString()))
                    .as(zone + " P&L of day D-1").isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("[ADR-S070] UTC organisations keep UTC days and unchanged instants")
    void utcUnchanged() {
        Books b = freshBooks();
        String cash = mappedCode(b.org(), "default_cash");
        String revenue = accountCodeOfSection(b.org(), "revenue");
        LocalDate d = LocalDate.now().minusDays(20);
        Instant midnight = d.atStartOfDay(ZoneOffset.UTC).toInstant();
        String id = (String) postAt(b, midnight, List.of(dr(cash, 30), cr(revenue, 30))).get("id");
        assertThat(postingDate(id)).isEqualTo(midnight);
        assertThat(LocalDateTime.ofInstant(midnight, ZoneOffset.UTC).toLocalDate()).isEqualTo(d);
        assertThat(AccountingDay.normalize(midnight, ZoneOffset.UTC)).isEqualTo(midnight);
        assertThat(AccountingDay.parse(d.toString(), ZoneId.of("UTC"))).isEqualTo(midnight);
    }
}
