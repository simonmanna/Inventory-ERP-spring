package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * R8 (review 2026-11-17): approving a draft journal entry re-runs on its stored lines the validation a direct posting
 * runs (balance, cost-centre rules, analytic dimensions, account postability), because the lines can change between
 * staging and approval. Each scenario runs in a fresh organization.
 */
class DraftPostingRevalidationIT extends AccountingFixtures {

    String checker(Books b) {
        String role = jdbc.sql("SELECT ur.\"A\" FROM \"_UserRoles\" ur WHERE ur.\"B\" = ? LIMIT 1").param(b.ownerId())
                .query(String.class).single();
        String email = "r8-checker-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Checker@12345", role);
        return login(b.code(), email, "Checker@12345");
    }

    /** Stage a manual draft (Dr expense / Cr bank) as the owner; returns its id. */
    String draft(Books b, int amount, Map<String, Object> debitExtra) {
        Map<String, Object> debit = new LinkedHashMap<>();
        debit.put("accountId", account(b.org(), accountCodeOfCategory(b.org(), "operating_expense")));
        debit.put("debit", amount);
        debit.putAll(debitExtra);
        Map<String, Object> credit = Map.of("accountId", account(b.org(), mappedCode(b.org(), "default_bank")),
                "credit", amount);
        Res r = as(b, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", LocalDate.now().toString(),
                "description", "R8 draft", "lines", List.of(debit, credit)));
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        assertThat(r.body().get("status").asString()).isEqualTo("draft");
        return r.body().get("id").asString();
    }

    String status(String entryId) {
        return jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(entryId).query(String.class)
                .single();
    }

    Res approve(Books b, String entryId) {
        return call("POST", "/journal-entries/" + entryId + "/post", null, checker(b));
    }

    @Test
    @DisplayName("[R8] an untouched draft still posts")
    void untouchedDraftPosts() {
        Books b = freshBooks();
        String id = draft(b, 120, Map.of());
        Res r = approve(b, id);
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        assertThat(status(id)).isEqualTo("posted");
    }

    @Test
    @DisplayName("[R8] a draft whose lines were unbalanced after staging is refused")
    void unbalancedDraftIsRefused() {
        Books b = freshBooks();
        String id = draft(b, 100, Map.of());
        jdbc.sql("UPDATE \"JournalLine\" SET debit = debit + 5, \"baseDebit\" = \"baseDebit\" + 5 WHERE"
                + " \"journalEntryId\" = ? AND debit > 0").param(id).update();
        Res r = approve(b, id);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).startsWith("Unbalanced entry");
        assertThat(status(id)).isEqualTo("draft");
        // Even a sub-cent base residual is refused: staging already booked the rounding line.
        jdbc.sql("UPDATE \"JournalLine\" SET debit = debit - 4.995, \"baseDebit\" = \"baseDebit\" - 4.995 WHERE"
                + " \"journalEntryId\" = ? AND debit > 0").param(id).update();
        Res cent = approve(b, id);
        assertThat(cent.status()).isEqualTo(400);
        assertThat(cent.body().get("message").asString()).startsWith("Unbalanced entry");
        assertThat(status(id)).isEqualTo("draft");
    }

    @Test
    @DisplayName("[R8] a draft line moved to an unknown or retired cost center is refused")
    void costCenterRulesAreRechecked() {
        Books b = freshBooks();
        String id = draft(b, 80, Map.of());
        jdbc.sql("UPDATE \"JournalLine\" SET \"costCenterId\" = ? WHERE \"journalEntryId\" = ? AND debit > 0")
                .params(UUID.randomUUID().toString(), id).update();
        Res r = approve(b, id);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Cost center not found");
        assertThat(status(id)).isEqualTo("draft");
    }

    @Test
    @DisplayName("[R8] a dimension value deactivated after staging is refused")
    void dimensionsAreRechecked() {
        Books b = freshBooks();
        JsonNode region = okAs(b, 201, "POST", "/analytic-dimensions", Map.of("code", "region", "name", "Region"));
        String east = okAs(b, 201, "POST", "/analytic-dimensions/" + region.get("id").asString() + "/values",
                Map.of("code", "EAST", "name", "East")).get("id").asString();
        String id = draft(b, 90, Map.of("dimensions", Map.of("region", "EAST")));
        jdbc.sql("UPDATE \"AnalyticDimensionValue\" SET \"isActive\" = false WHERE id = ?").param(east).update();
        Res r = approve(b, id);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Value EAST of dimension region is inactive");
        assertThat(status(id)).isEqualTo("draft");
    }

    @Test
    @DisplayName("[R8] an account that stopped allowing manual posting after staging is refused")
    void manualPostingIsRechecked() {
        Books b = freshBooks();
        String id = draft(b, 70, Map.of());
        String expense = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        jdbc.sql("UPDATE \"Account\" SET \"allowManualPosting\" = false WHERE id = ?").param(expense).update();
        Res r = approve(b, id);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("does not allow manual posting");
        assertThat(status(id)).isEqualTo("draft");
    }
}
