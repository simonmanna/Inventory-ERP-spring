package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finance readiness audit B5: deleting a cash or bank account ({@code DELETE /accounts/cash-flow/:id}) makes the checks
 * of {@code DELETE /accounts/:id}, so an account with postings is never soft-deleted out of the reports.
 */
class CashAccountDeleteIT extends ApiTestSupport {

    String create() {
        String code = "B5" + UUID.randomUUID().toString().substring(0, 6);
        return ok(201, "POST", "/accounts/cash-flow", Map.of("code", code, "name", "Delete test " + code,
                "accountType", "bank")).get("id").asString();
    }

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    Map<String, Object> state(String id) {
        return jdbc.sql("SELECT \"deletedAt\" IS NULL AS live, \"isActive\" FROM \"Account\" WHERE id = ?").param(id)
                .query().singleRow();
    }

    @Test
    @DisplayName("an unused cash or bank account is deleted as before")
    void unusedAccountIsDeleted() {
        String id = create();
        Res r = call("DELETE", "/accounts/cash-flow/" + id, null, adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(204);
        assertThat(state(id)).containsEntry("live", false).containsEntry("isActive", false);
    }

    @Test
    @DisplayName("an account with journal lines is refused with the account delete message and stays on the books")
    void accountWithPostingsIsKept() {
        String id = create();
        Res deposit = call("POST", "/accounts/cash-flow/deposit", Map.of("accountId", id, "counterpartAccountId",
                accountId("3100"), "operationType", "owner_contribution", "amount", 2500, "description",
                "B5 delete guard"), Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(deposit.status()).as(deposit.raw().body()).isEqualTo(200);

        Res r = call("DELETE", "/accounts/cash-flow/" + id, null, adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo(
                "Account has journal entries and cannot be deleted. Deprecate it instead so history is preserved.");
        assertThat(state(id)).containsEntry("live", true).containsEntry("isActive", true);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"accountId\" = ?").param(id).query(Long.class)
                .single()).isPositive();
    }

    @Test
    @DisplayName("a mapped account is refused with the mapping message")
    void mappedAccountIsKept() {
        String id = create();
        jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, now())").params(UUID.randomUUID().toString(), demoOrg,
                "b5_test_" + id.substring(0, 8), id).update();
        Res r = call("DELETE", "/accounts/cash-flow/" + id, null, adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).startsWith("Account is used by account mapping 'b5_test_")
                .endsWith("'. Reassign the mapping first.");
        assertThat(state(id)).containsEntry("live", true);
    }
}
