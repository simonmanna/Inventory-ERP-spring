package com.inventoryerp.accounting;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finance readiness audit B5 (journals): who may discard a draft and its audit row, no reversal dated before its
 * original, and a 400 for a balance sheet without a valid {@code asOf}.
 */
class JournalRulesB5IT extends AccountingFixtures {

    private static final String CASH = "1100";

    private String userHolding(Books b, String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, b.org(), "B5-" + role, permissions).update();
        String email = "clerk-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Clerk@12345", role);
        return login(b.code(), email, "Clerk@12345");
    }

    private Map<String, String> auth(String token, boolean key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        if (key) {
            h.put("Idempotency-Key", UUID.randomUUID().toString());
        }
        return h;
    }

    private String draftAs(Books b, String token) {
        Map<String, Object> debit = new LinkedHashMap<>();
        debit.put("accountId", account(b.org(), CASH));
        debit.put("debit", 25);
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("accountId", account(b.org(), accountCodeOfSection(b.org(), "revenue")));
        credit.put("credit", 25);
        Res r = call("POST", "/journal-entries", Map.of("journalCode", "GEN", "date", LocalDate.now(ZoneOffset.UTC)
                .toString(), "description", "B5 draft", "lines", List.of(debit, credit)), auth(token, true));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body().get("id").asString();
    }

    private long rows(String id) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    @DisplayName("B5: a draft is discarded by its author or a poster only, and the discard is audited")
    void discardRules() {
        Books b = freshBooks();
        String maker = userHolding(b, "journal_entry:create", "journal_entry:read", "account:read");
        String other = userHolding(b, "journal_entry:create", "journal_entry:read", "account:read");

        String mine = draftAs(b, maker);
        Res refused = call("DELETE", "/journal-entries/" + mine, null, auth(other, false));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(403);
        assertThat(refused.body().get("message").asString()).contains("Only the author of draft");
        assertThat(rows(mine)).isEqualTo(1);

        assertThat(call("DELETE", "/journal-entries/" + mine, null, auth(maker, false)).status()).isEqualTo(204);
        assertThat(rows(mine)).isZero();

        String again = draftAs(b, maker);
        assertThat(as(b, "DELETE", "/journal-entries/" + again, null).status()).as("the owner can post entries")
                .isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'JournalEntry' AND action = 'delete'"
                + " AND \"entityId\" IN (?, ?) AND \"oldValues\"->>'description' = 'B5 draft'").params(mine, again)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("B5: a reversal cannot be dated before the entry it reverses; the same day or later is fine")
    void reversalNotBeforeOriginal() {
        Books b = freshBooks();
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String entry = postManualJe(b, day.toString(), List.of(dr(CASH, 10), cr(revenue, 10)));
        Res early = as(b, "POST", "/journal-entries/" + entry + "/reverse", Map.of("date", day.minusDays(1).toString()));
        assertThat(early.status()).as(early.raw().body()).isEqualTo(400);
        assertThat(early.body().get("message").asString()).startsWith("A reversal cannot be dated before the entry it"
                + " reverses");
        Res same = as(b, "POST", "/journal-entries/" + entry + "/reverse", Map.of("date", day.toString()));
        assertThat(same.status()).as(same.raw().body()).isIn(200, 201);
    }

    @Test
    @DisplayName("B5: the balance sheet answers 400 without a valid asOf (was 500)")
    void balanceSheetAsOf() {
        Books b = freshBooks();
        for (String path : List.of("/reports/accounting/balance-sheet", "/reports/accounting/balance-sheet/detailed")) {
            Res none = as(b, "GET", path, null);
            assertThat(none.status()).as(path).isEqualTo(400);
            assertThat(none.body().get("message").asString()).isEqualTo("asOf is required (YYYY-MM-DD)");
            Res bad = as(b, "GET", path + "?asOf=yesterday", null);
            assertThat(bad.status()).as(path).isEqualTo(400);
            assertThat(as(b, "GET", path + "?asOf=" + LocalDate.now(ZoneOffset.UTC), null).status()).isEqualTo(200);
        }
    }
}
