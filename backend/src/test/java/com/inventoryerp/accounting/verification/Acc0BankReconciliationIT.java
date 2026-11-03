package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ACC-0 reproduction: bank auto-match (finding B5). The web screen sends {@code signed: true}
 * ({@code web/src/features/accounting/bank-reconciliation.ts:176}); the API default is the unsigned Nest mode.
 */
class Acc0BankReconciliationIT extends AccountingFixtures {

    String partner;

    @BeforeAll
    void fixtures() {
        partner = demoPartner("ACC0 bank partner", true);
    }

    String payment(String account, String direction, int amount) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                        + " \"paymentDate\", \"paymentMethod\", \"accountId\", amount, status, \"updatedAt\") VALUES (?, ?, ?,"
                        + " ?::\"PaymentDirection\", ?, now(), 'bank', ?, ?, 'posted'::\"DocumentStatus\", now())")
                .params(id, demoOrg, "A0-" + id.substring(0, 12), direction, partner, account, amount).update();
        return id;
    }

    void importLines(String account, List<Map<String, Object>> lines) {
        ok(200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", account, "currencyCode", "UGX",
                "lines", lines));
    }

    Map<String, Object> line(String ref, int amount) {
        return Map.of("postedAt", today(), "externalRef", ref, "description", "ACC0 " + ref, "amount", amount);
    }

    JsonNode unsignedMatch(String account) {
        return ok(200, "POST", "/bank-reconciliation/match?bankAccountId=" + account, Map.of());
    }

    List<String> matchedPayments(String account) {
        return jdbc.sql("SELECT \"matchedPaymentId\" FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND"
                + " \"bankAccountId\" = ? AND status = 'matched'").params(demoOrg, account).query(String.class).list();
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B5][owner:accounting][ACC-6] API auto-match pairs an outgoing statement line with its payment")
    void outgoingLineMatches() {
        String account = UUID.randomUUID().toString();
        String out = payment(account, "outbound", 500);
        importLines(account, List.of(line("OUT-1", -500)));
        JsonNode run = unsignedMatch(account);
        assertThat(run.get("matched").asInt()).as("outgoing −500 vs outbound payment 500").isEqualTo(1);
        assertThat(matchedPayments(account)).containsExactly(out);
    }

    @Test
    @Tag("acc-known-defect")
    @DisplayName("[B5][owner:accounting][ACC-6] a payment matched in an earlier run is never matched again")
    void noCrossRunDuplicate() {
        String account = UUID.randomUUID().toString();
        payment(account, "inbound", 90);
        importLines(account, List.of(line("IN-1", 90)));
        assertThat(unsignedMatch(account).get("matched").asInt()).isEqualTo(1);
        importLines(account, List.of(line("IN-2", 90)));
        JsonNode second = unsignedMatch(account);
        assertThat(second.get("matched").asInt()).as("second run with only an already-matched payment").isZero();
        assertThat(matchedPayments(account)).as("one payment, one statement line").hasSize(1);
    }

    @Test
    @DisplayName("[B5][owner:accounting][ACC-6] signed mode (the web screen) handles both cases correctly")
    void signedModeIsCorrect() {
        String account = UUID.randomUUID().toString();
        payment(account, "outbound", 500);
        payment(account, "inbound", 90);
        importLines(account, List.of(line("S-OUT", -500), line("S-IN", 90), line("S-IN2", 90)));
        JsonNode run = ok(200, "POST", "/bank-reconciliation/match?bankAccountId=" + account, Map.of("signed", true));
        assertThat(run.get("matched").asInt()).isEqualTo(2);
        assertThat(matchedPayments(account)).hasSize(2).doesNotHaveDuplicates();
    }
}
