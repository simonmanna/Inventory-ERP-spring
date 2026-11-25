package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.LedgerIntegrityService;
import com.inventoryerp.accounting.application.PostingKeyReplayGuard;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit A6 (P2-12): a posting key reused for another posting. {@code PostingService} replays the
 * posted entry of a key it has seen; a caller reusing the key for different lines lost its posting without a trace.
 */
class PostingKeyReuseIT extends AccountingFixtures {

    private static final String CASH = "1100";

    @Autowired
    AccountResolverService accounts;

    @Test
    @DisplayName("a key replayed with other lines returns its entry, is recorded and listed by I13; refused under refuse")
    void replayWithOtherLines() {
        Books b = freshBooks();
        String day = today();
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String key = "a6:" + UUID.randomUUID();

        String first = post(b, key, day, CASH, revenue, 100);
        assertThat(post(b, key, day, CASH, revenue, 100)).as("a true retry replays").isEqualTo(first);
        assertThat(conflicts(b)).isZero();
        assertThat(check(report(b), LedgerIntegrityService.POSTING_KEY_REUSE).get("ok").asBoolean()).isTrue();

        assertThat(post(b, key, day, CASH, revenue, 120)).as("warn: the posted entry comes back").isEqualTo(first);
        assertThat(entries(b, key)).isEqualTo(1);
        Map<String, Object> conflict = jdbc.sql("SELECT \"journalEntryId\", \"pairCount\", pairs::text AS pairs,"
                + " \"resolvedAt\" FROM \"PostingKeyConflict\" WHERE \"organizationId\" = ? AND \"postingKey\" = ?")
                .params(b.org(), key).query().singleRow();
        assertThat(conflict.get("journalEntryId")).isEqualTo(first);
        assertThat(((Number) conflict.get("pairCount")).intValue()).as("cash and revenue").isEqualTo(2);
        assertThat(String.valueOf(conflict.get("pairs"))).contains("\"stored\": \"100\"", "\"requested\": \"120\"");

        JsonNode i13 = check(report(b), LedgerIntegrityService.POSTING_KEY_REUSE);
        assertThat(i13.get("ok").asBoolean()).isFalse();
        assertThat(i13.get("failures").asLong()).isEqualTo(1);
        assertThat(i13.get("rows").get(0).get("postingKey").asString()).isEqualTo(key);
        assertThat(i13.get("rows").get(0).get("journalEntryId").asString()).isEqualTo(first);

        okAs(b, 200, "PUT", "/settings/" + PostingKeyReplayGuard.SETTING, Map.of("value", "refuse"));
        assertThat(post(b, key, day, CASH, revenue, 100)).as("refuse: a true retry still replays").isEqualTo(first);
        String number = jdbc.sql("SELECT \"entryNumber\" FROM \"JournalEntry\" WHERE id = ?").param(first)
                .query(String.class).single();
        assertThatThrownBy(() -> post(b, key, day, CASH, revenue, 130)).isInstanceOf(ApiException.class)
                .hasMessage("Posting key " + key + " was already used by journal entry " + number
                        + " with different lines");
        assertThat(conflicts(b)).as("nothing more recorded: the posting was refused").isEqualTo(1);

        jdbc.sql("UPDATE \"PostingKeyConflict\" SET \"resolvedAt\" = now(), resolution = 'reviewed' WHERE"
                + " \"organizationId\" = ?").param(b.org()).update();
        assertThat(check(report(b), LedgerIntegrityService.POSTING_KEY_REUSE).get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("gratuity: a second run on the same date posts its own entry (the key named only the date)")
    void gratuitySecondRunPosts() {
        Books b = freshBooks();
        inTenant(b.org(), b.ownerId(), () -> {
            accounts.ensureByCode("2995", "Gratuity provision", "current_liability", "gratuity_provision");
            accounts.ensureByCode("6995", "Salaries", "operating_expense", "salary_expense");
        });
        okAs(b, 200, "PUT", "/hr/payroll/settings", Map.of("gratuityDaysPerYear", 21));
        String hired = LocalDate.now().minusYears(3).toString();
        employee(b, "Ada", hired);
        String asOf = today();

        JsonNode first = okAs(b, 201, "POST", "/hr/separations/gratuity/accrue", Map.of("asOf", asOf));
        assertThat(first.get("employees").asInt()).isEqualTo(1);
        String firstEntry = first.get("journalEntryId").asString();

        String grace = employee(b, "Grace", hired);
        JsonNode second = okAs(b, 201, "POST", "/hr/separations/gratuity/accrue", Map.of("asOf", asOf));
        assertThat(second.get("employees").asInt()).as("only the new employee").isEqualTo(1);
        String secondEntry = second.get("journalEntryId").asString();
        assertThat(secondEntry).as("its own entry, not a replay of the first").isNotEqualTo(firstEntry);
        assertThat(jdbc.sql("SELECT \"journalEntryId\" FROM \"HrGratuityProvision\" WHERE \"employeeId\" = ?")
                .param(grace).query(String.class).single()).isEqualTo(secondEntry);

        BigDecimal provisions = jdbc.sql("SELECT coalesce(sum(movement), 0) FROM \"HrGratuityProvision\" WHERE"
                + " \"organizationId\" = ?").param(b.org()).query(BigDecimal.class).single();
        BigDecimal ledger = jdbc.sql("SELECT coalesce(sum(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                + " e.\"sourceType\" = 'hr_gratuity' AND l.\"accountId\" = ?").params(b.org(), mapped(b.org(),
                "gratuity_provision")).query(BigDecimal.class).single();
        assertThat(ledger).as("the ledger holds both runs' movement").isEqualByComparingTo(provisions);
        assertThat(conflicts(b)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String post(Books b, String key, String day, String debit, String credit, int amount) {
        return postJe(b, "GEN", day, null, null, null, key, List.of(dr(debit, amount), cr(credit, amount)));
    }

    private long entries(Books b, String key) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" = ?")
                .params(b.org(), key).query(Long.class).single();
    }

    private long conflicts(Books b) {
        return jdbc.sql("SELECT count(*) FROM \"PostingKeyConflict\" WHERE \"organizationId\" = ?").param(b.org())
                .query(Long.class).single();
    }

    private String employee(Books b, String name, String hireDate) {
        return okAs(b, 201, "POST", "/hr/employees", Map.of("firstName", name, "lastName", "A6", "hireDate", hireDate,
                "baseSalary", 1_500_000)).get("id").asString();
    }

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
}
