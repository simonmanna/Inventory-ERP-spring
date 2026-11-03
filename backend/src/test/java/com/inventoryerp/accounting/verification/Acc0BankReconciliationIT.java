package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("[B5][owner:accounting][ACC-6] statement proof: closing + outstanding = ledger + unrecorded; lock freezes")
    void statementProofAndLock() {
        Books b = freshBooks();
        String bank = account(b.org(), mappedCode(b.org(), "default_bank"));
        String revenue = accountCodeOfSection(b.org(), "revenue");
        String day = java.time.LocalDate.now().minusDays(2).toString();
        postJe(b, day, List.of(JeLine.dr(mappedCode(b.org(), "default_bank"), 500), JeLine.cr(revenue, 500)));
        String partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Bank rec customer', true, now())").params(partner, b.org(), "BR-" + partner.substring(0, 6))
                .update();
        for (int amount : new int[] {300, 200}) {
            jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                            + " \"paymentDate\", \"paymentMethod\", \"accountId\", amount, status, \"updatedAt\") VALUES (?, ?,"
                            + " ?, 'inbound'::\"PaymentDirection\", ?, ?::timestamp, 'bank', ?, ?, 'posted'::\"DocumentStatus\","
                            + " now())")
                    .params(UUID.randomUUID().toString(), b.org(), "BR-" + amount + "-" + UUID.randomUUID().toString()
                            .substring(0, 6), partner, day + " 10:00:00", bank, amount).update();
        }
        // The bank shows the 300 receipt and a 15 charge not yet in the books; the 200 receipt is not on the statement.
        okAs(b, 200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", bank, "currencyCode", "UGX",
                "lines", List.of(Map.of("postedAt", day, "externalRef", "R300", "description", "Receipt", "amount", 300),
                        Map.of("postedAt", day, "externalRef", "FEE", "description", "Charge", "amount", -15))));
        okAs(b, 200, "POST", "/bank-reconciliation/match?bankAccountId=" + bank, Map.of());
        JsonNode s = okAs(b, 200, "GET", "/bank-reconciliation/statement?bankAccountId=" + bank + "&asOf=" + day
                + "&closingBalance=285", null);
        // 285 + 200 outstanding = 500 ledger + (−15) unrecorded
        assertThat(new java.math.BigDecimal(s.get("difference").asString())).isEqualByComparingTo("0");
        assertThat(s.get("reconciled").asBoolean()).isTrue();
        Res wrong = as(b, "POST", "/bank-reconciliation/statement", Map.of("bankAccountId", bank, "asOf", day,
                "closingBalance", 290));
        assertThat(wrong.status()).as(wrong.raw().body()).isEqualTo(400);
        assertThat(wrong.body().get("message").asString()).startsWith("Statement does not reconcile: difference 5");
        Res lock = moneyAs(b, "POST", "/bank-reconciliation/statement", Map.of("bankAccountId", bank, "asOf", day,
                "closingBalance", 285));
        assertThat(lock.status()).as(lock.raw().body()).isLessThan(300);
        String matchedLine = jdbc.sql("SELECT id FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND"
                + " status = 'matched'").param(b.org()).query(String.class).single();
        assertThat(as(b, "POST", "/bank-reconciliation/unmatch/" + matchedLine, null).status()).as("unmatch locked")
                .isEqualTo(400);
        Res late = as(b, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", bank, "currencyCode", "UGX",
                "lines", List.of(Map.of("postedAt", day, "externalRef", "LATE", "description", "Late", "amount", 1))));
        assertThat(late.status()).as("import into a locked statement").isEqualTo(400);
    }
}
