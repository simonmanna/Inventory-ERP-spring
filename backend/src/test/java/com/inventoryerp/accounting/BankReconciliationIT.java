package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.web.BankReconciliationController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FIN-1: statement import, auto and manual match, exclude, unmatch, summary, authorization and tenant isolation. */
class BankReconciliationIT extends ApiTestSupport {

    String partner;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(partner, demoOrg, "B-" + partner.substring(0, 8), "Bank Rec Partner " + partner).update();
    }

    /** {@code Payment.accountId} has no foreign key, so each test owns a fresh account id and sees only its rows. */
    static String account() {
        return UUID.randomUUID().toString();
    }

    String payment(String account, String direction, int amount) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                        + " \"paymentDate\", \"paymentMethod\", \"accountId\", amount, status, \"updatedAt\") VALUES "
                        + "(?, ?, ?,"
                        + " ?::\"PaymentDirection\", ?, now(), 'bank', ?, ?, 'posted'::\"DocumentStatus\", now())")
                .params(id, demoOrg, "BR-" + id.substring(0, 12), direction, partner, account, amount).update();
        return id;
    }

    Map<String, Object> line(String ref, int amount) {
        return Map.of("postedAt", today, "externalRef", ref, "description", "Line " + ref, "amount", amount);
    }

    JsonNode importLines(String account, List<Map<String, Object>> lines) {
        return ok(200, "POST", "/bank-reconciliation/import",
                Map.of("bankAccountId", account, "currencyCode", "UGX", "lines", lines));
    }

    JsonNode lineByRef(String account, String ref) {
        for (JsonNode l : ok(200, "GET", "/bank-reconciliation/lines?bankAccountId=" + account, null).get("data")) {
            if (ref.equals(l.get("externalRef").asString())) {
                return l;
            }
        }
        throw new AssertionError("line " + ref + " not found");
    }

    JsonNode signedMatch(String account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signed", true);
        return ok(200, "POST", "/bank-reconciliation/match?bankAccountId=" + account, body);
    }

    @Test
    @DisplayName("import is idempotent on externalRef")
    void importIdempotent() {
        String account = account();
        assertThat(importLines(account, List.of(line("A1", 100), line("A2",
                -40))).get("imported").asInt()).isEqualTo(2);
        JsonNode again = importLines(account, List.of(line("A1", 100), line("A3", 5)));
        assertThat(again.get("imported").asInt()).isEqualTo(1);
        assertThat(again.get("skipped").asInt()).isEqualTo(1);
        assertThat(ok(200, "GET", "/bank-reconciliation/lines?bankAccountId=" + account, null).get("meta").get("total")
                .asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("signed auto-match: a negative line takes an outbound payment, a payment is never taken twice")
    void signedAutoMatch() {
        String account = account();
        String out = payment(account, "outbound", 70);
        String in = payment(account, "inbound", 90);
        importLines(account, List.of(line("S1", -70), line("S2", 90), line("S3", 90), line("S4", 70)));

        JsonNode run = signedMatch(account);
        assertThat(run.get("matched").asInt()).isEqualTo(2);
        assertThat(run.get("unmatched").asInt()).isEqualTo(2);
        assertThat(lineByRef(account, "S1").get("matchedPaymentId").asString()).isEqualTo(out);
        // S2 and S3 share a date and an amount: one takes the single inbound payment, the other stays open.
        long taken = List.of("S2", "S3").stream()
                .filter(ref -> "matched".equals(lineByRef(account, ref).get("status").asString())).count();
        assertThat(taken).isEqualTo(1);
        assertThat(in).isNotNull();
        // The inbound 70 does not exist: an outbound payment must not satisfy a positive line.
        assertThat(lineByRef(account, "S4").get("status").asString()).isEqualTo("unmatched");
    }

    @Test
    @DisplayName("ACC-6 (D-B5): without signed the match is still direction-aware: a negative line takes its payment")
    void unsignedMatchIsDirectionAware() {
        String account = account();
        payment(account, "outbound", 30);
        importLines(account, List.of(line("L1", -30)));
        JsonNode run = ok(200, "POST", "/bank-reconciliation/match?bankAccountId=" + account, Map.of());
        assertThat(run.get("matched").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("manual match: candidates, a wrong amount refused, a taken payment refused, unmatch frees it")
    void manualMatch() {
        String account = account();
        String pay = payment(account, "outbound", 55);
        String wrong = payment(account, "outbound", 56);
        importLines(account, List.of(line("M1", -55), line("M2", -55)));
        String first = lineByRef(account, "M1").get("id").asString();
        String second = lineByRef(account, "M2").get("id").asString();

        JsonNode candidates = ok(200, "GET", "/bank-reconciliation/lines/" + first + "/candidates", null);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("id").asString()).isEqualTo(pay);

        assertThat(as("POST", "/bank-reconciliation/lines/" + first + "/match", Map.of("paymentId", wrong)).status())
                .isEqualTo(400);
        ok(200, "POST", "/bank-reconciliation/lines/" + first + "/match", Map.of("paymentId", pay));
        assertThat(lineByRef(account, "M1").get("status").asString()).isEqualTo("matched");
        assertThat(as("POST", "/bank-reconciliation/lines/" + second + "/match", Map.of("paymentId", pay)).status())
                .isEqualTo(409);

        assertThat(as("POST", "/bank-reconciliation/unmatch/" + first, null).status()).isIn(200, 204);
        assertThat(lineByRef(account, "M1").get("status").asString()).isEqualTo("unmatched");
        ok(200, "POST", "/bank-reconciliation/lines/" + second + "/match", Map.of("paymentId", pay));
    }

    @Test
    @DisplayName("exclude needs a reason; summary splits the statement and lists payments the bank has not shown")
    void excludeAndSummary() {
        String account = account();
        String matched = payment(account, "inbound", 200);
        payment(account, "outbound", 15);
        importLines(account, List.of(line("E1", 200), line("E2", -3), line("E3", 11)));
        signedMatch(account);
        String charge = lineByRef(account, "E2").get("id").asString();

        assertThat(as("POST", "/bank-reconciliation/lines/" + charge + "/exclude", Map.of("note", "")).status())
                .isEqualTo(400);
        ok(200, "POST", "/bank-reconciliation/lines/" + charge + "/exclude", Map.of("note", "Bank charge"));
        assertThat(lineByRef(account, "E2").get("note").asString()).isEqualTo("Bank charge");

        JsonNode summary = ok(200, "GET", "/bank-reconciliation/summary?bankAccountId=" + account, null);
        assertThat(new BigDecimal(summary.get("statementTotal").asString())).isEqualByComparingTo("208");
        assertThat(new BigDecimal(summary.get("matchedTotal").asString())).isEqualByComparingTo("200");
        assertThat(new BigDecimal(summary.get("excludedTotal").asString())).isEqualByComparingTo("-3");
        assertThat(new BigDecimal(summary.get("unmatchedTotal").asString())).isEqualByComparingTo("11");
        assertThat(summary.get("unmatchedCount").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(summary.get("outstandingOutbound").asString())).isEqualByComparingTo("15");
        assertThat(new BigDecimal(summary.get("outstandingInbound").asString())).isEqualByComparingTo("0");
        assertThat(summary.get("outstandingPayments").asInt()).isEqualTo(1);

        JsonNode runs = ok(200, "GET", "/bank-reconciliation/runs?bankAccountId=" + account, null);
        assertThat(runs).hasSize(1);
        assertThat(matched).isNotNull();
    }

    @Test
    @DisplayName("lines without an account answer 400, and the status filter narrows the list")
    void lineFilters() {
        assertThat(as("GET", "/bank-reconciliation/lines", null).status()).isEqualTo(400);
        String account = account();
        payment(account, "inbound", 10);
        importLines(account, List.of(line("F1", 10), line("F2", 20)));
        signedMatch(account);
        JsonNode open = ok(200, "GET", "/bank-reconciliation/lines?status=unmatched&bankAccountId=" + account, null);
        assertThat(open.get("data")).hasSize(1);
        assertThat(open.get("data").get(0).get("externalRef").asString()).isEqualTo("F2");
    }

    @Test
    @DisplayName("tenant isolation: another organization cannot see, match or exclude a line")
    void tenantIsolation() {
        String account = account();
        String pay = payment(account, "inbound", 12);
        importLines(account, List.of(line("T1", 12)));
        String id = lineByRef(account, "T1").get("id").asString();

        assertThat(call("GET", "/bank-reconciliation/lines?bankAccountId=" + account, null,
                otherToken).body().get("data"))
                .isEmpty();
        assertThat(call("GET", "/bank-reconciliation/lines/" + id + "/candidates", null,
                otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/bank-reconciliation/lines/" + id + "/match", Map.of("paymentId", pay),
                otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/bank-reconciliation/lines/" + id + "/exclude", Map.of("note", "x"),
                otherToken).status())
                .isEqualTo(404);
        assertThat(lineByRef(account, "T1").get("status").asString()).isEqualTo("unmatched");
    }

    @Test
    @DisplayName("authorization: every bank reconciliation route answers 401 / 403")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(BankReconciliationController.class)).isEqualTo(12);
    }
}
