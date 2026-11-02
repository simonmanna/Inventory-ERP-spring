package com.inventoryerp.expenses;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * EX-3 (ADR-S052): employee claims (submit, approve by someone else, accrue to reimbursement payable, partial and
 * final reimbursement, return and resubmit) and cash advances (approve, disburse, settle through a claim, top-up,
 * refund).
 */
class ExpenseClaimsIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String adminId;
    String bank;
    String category;
    String checkerToken;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        bank = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1200'").param(demoOrg)
                .query(String.class).single();
        String expenseAcc = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '5200'")
                .param(demoOrg).query(String.class).single();
        String equity = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'equity' AND a.\"isPostable\" AND a.\"isActive\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).single();
        String sfx = sfx();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> posting.post(PostingRequest.of("GEN", Instant.now(), "Claims float",
                        "expense_it", sfx, "expense_it:claims:" + sfx, List.of(Line.debit(bank, "1000000", "Float"),
                                Line.credit(equity, "1000000", "Float"))))));
        category = ok(201, "POST", "/expense-categories", Map.of("name", "Travel " + sfx, "ledgerAccountId", expenseAcc))
                .get("id").asString();
        String adminRole = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\""
                + " u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "claim-checker-" + sfx + "@demo.test";
        createUser(demoOrg, email, "Check@12345", adminRole);
        checkerToken = login("DEMO", email, "Check@12345");
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    JsonNode post(String token, String path, Object body) {
        Res r = call("POST", path, body, Map.of("Authorization", "Bearer " + token, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(r.status()).as(path + " " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    Map<String, Object> line(String title, int amount) {
        return Map.of("title", title, "amount", amount, "expenseDate", LocalDate.now().toString(), "categoryId",
                category, "receiptNumber", "R-" + sfx());
    }

    BigDecimal balance(String code) {
        return jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"Account\" a ON a.id ="
                + " l.\"accountId\" JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE a.\"organizationId\" = ?"
                + " AND a.code = ? AND e.status IN ('posted', 'reversed')").params(demoOrg, code).query(BigDecimal.class)
                .single();
    }

    @Test
    @DisplayName("claim: submit, claimant cannot approve, approve accrues to 2189, partial then final reimbursement")
    void claimLifecycle() {
        JsonNode claim = post(adminToken, "/expense-claims", Map.of("title", "Kampala trip", "lines",
                List.of(line("Taxi", 40), line("Hotel", 60))));
        String id = claim.get("id").asString();
        assertThat(claim.get("status").asString()).isEqualTo("draft");
        assertThat(claim.get("totalAmount").asDouble()).isEqualTo(100);
        assertThat(claim.get("lines")).hasSize(2);
        String lineId = claim.get("lines").get(0).get("id").asString();
        assertThat(call("POST", "/expenses/" + lineId + "/approve", Map.of(), checkerToken).status()).isEqualTo(400);

        assertThat(post(adminToken, "/expense-claims/" + id + "/submit", Map.of()).get("status").asString())
                .isEqualTo("submitted");
        assertThat(call("POST", "/expense-claims/" + id + "/approve", Map.of(), adminToken).status()).isEqualTo(403);
        BigDecimal payableBefore = balance("2189");
        JsonNode approved = post(checkerToken, "/expense-claims/" + id + "/approve", Map.of("notes", "ok"));
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(approved.get("amountDue").asDouble()).isEqualTo(100);
        assertThat(balance("2189").subtract(payableBefore)).isEqualByComparingTo("-100");
        approved.get("lines").forEach(l -> assertThat(l.get("status").asString()).isEqualTo("APPROVED"));

        JsonNode part = post(checkerToken, "/expense-claims/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER",
                "accountId", bank, "amount", 50));
        assertThat(part.get("status").asString()).isEqualTo("partially_paid");
        assertThat(part.get("amountDue").asDouble()).isEqualTo(50);
        JsonNode paid = post(checkerToken, "/expense-claims/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER",
                "accountId", bank));
        assertThat(paid.get("status").asString()).isEqualTo("paid");
        paid.get("lines").forEach(l -> assertThat(l.get("paymentStatus").asString()).isEqualTo("PAID"));
        assertThat(balance("2189")).isEqualByComparingTo(payableBefore);
    }

    @Test
    @DisplayName("claim: returned for correction, lines replaced, resubmitted and approved")
    void returnAndResubmit() {
        String id = post(adminToken, "/expense-claims", Map.of("title", "Site visit", "lines",
                List.of(line("Fuel", 30)))).get("id").asString();
        post(adminToken, "/expense-claims/" + id + "/submit", Map.of());
        JsonNode returned = post(checkerToken, "/expense-claims/" + id + "/return", Map.of("reason", "Split fuel"));
        assertThat(returned.get("status").asString()).isEqualTo("returned");
        ok(200, "PATCH", "/expense-claims/" + id, Map.of("lines", List.of(line("Fuel out", 15), line("Fuel back", 15))));
        post(adminToken, "/expense-claims/" + id + "/submit", Map.of());
        JsonNode approved = post(checkerToken, "/expense-claims/" + id + "/approve", Map.of());
        assertThat(approved.get("lines")).hasSize(2);
        assertThat(approved.get("totalAmount").asDouble()).isEqualTo(30);
    }

    @Test
    @DisplayName("advance: approve, disburse, settle a smaller claim, refund the rest; a larger claim is topped up")
    void advances() {
        BigDecimal advanceBefore = balance("1357");
        String adv = post(adminToken, "/expense-advances", Map.of("purpose", "Field trip", "amount", 100)).get("id")
                .asString();
        assertThat(call("POST", "/expense-advances/" + adv + "/approve", Map.of(), adminToken).status()).isEqualTo(403);
        assertThat(call("POST", "/expense-advances/" + adv + "/approve", Map.of(), weakToken()).status()).isEqualTo(403);
        post(checkerToken, "/expense-advances/" + adv + "/approve", Map.of());
        JsonNode disbursed = post(checkerToken, "/expense-advances/" + adv + "/disburse", Map.of("paymentMethod",
                "CASH", "accountId", bank));
        assertThat(disbursed.get("status").asString()).isEqualTo("disbursed");
        assertThat(disbursed.get("outstanding").asDouble()).isEqualTo(100);
        assertThat(balance("1357").subtract(advanceBefore)).isEqualByComparingTo("100");

        String claim = post(adminToken, "/expense-claims", Map.of("title", "Field trip costs", "advanceId", adv,
                "lines", List.of(line("Meals", 70)))).get("id").asString();
        post(adminToken, "/expense-claims/" + claim + "/submit", Map.of());
        JsonNode settled = post(checkerToken, "/expense-claims/" + claim + "/approve", Map.of());
        assertThat(settled.get("status").asString()).isEqualTo("paid");
        assertThat(settled.get("advanceApplied").asDouble()).isEqualTo(70);
        assertThat(settled.get("advance").get("outstanding").asDouble()).isEqualTo(30);

        JsonNode refunded = post(checkerToken, "/expense-advances/" + adv + "/refund", Map.of("paymentMethod", "CASH",
                "accountId", bank));
        assertThat(refunded.get("status").asString()).isEqualTo("settled");
        assertThat(refunded.get("movements")).hasSize(3);
        assertThat(balance("1357")).isEqualByComparingTo(advanceBefore);

        // a claim larger than the advance: the advance is used up, the rest is reimbursed
        String adv2 = post(adminToken, "/expense-advances", Map.of("purpose", "Second trip", "amount", 100)).get("id")
                .asString();
        post(checkerToken, "/expense-advances/" + adv2 + "/approve", Map.of());
        post(checkerToken, "/expense-advances/" + adv2 + "/disburse", Map.of("paymentMethod", "CASH", "accountId", bank));
        String claim2 = post(adminToken, "/expense-claims", Map.of("title", "Second trip costs", "advanceId", adv2,
                "lines", List.of(line("Hotel", 90), line("Meals", 40)))).get("id").asString();
        post(adminToken, "/expense-claims/" + claim2 + "/submit", Map.of());
        JsonNode topUp = post(checkerToken, "/expense-claims/" + claim2 + "/approve", Map.of());
        assertThat(topUp.get("status").asString()).isEqualTo("approved");
        assertThat(topUp.get("amountDue").asDouble()).isEqualTo(30);
        assertThat(topUp.get("advance").get("status").asString()).isEqualTo("settled");
        assertThat(post(checkerToken, "/expense-claims/" + claim2 + "/pay", Map.of("paymentMethod", "CASH",
                "accountId", bank)).get("status").asString()).isEqualTo("paid");
    }
}
