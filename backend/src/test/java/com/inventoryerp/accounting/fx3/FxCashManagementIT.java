package com.inventoryerp.accounting.fx3;

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
 * FX-4 (ADR-S082 Outstanding): expense advances in a foreign currency, petty-cash funds held in a foreign currency and
 * banking a shift into a foreign-currency bank. Journals balance; currency buckets tie.
 */
class FxCashManagementIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String adminId;
    String bank;
    String category;
    String checkerToken;
    String ccy;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        bank = account("1200");
        String expenseAcc = account("5200");
        String equity = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'equity' AND a.\"isPostable\" AND a.\"isActive\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).single();
        String sfx = sfx();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> posting.post(PostingRequest.of("GEN", Instant.now(), "FX4 float",
                        "expense_it", sfx, "expense_it:fx4:" + sfx, List.of(Line.debit(bank, "50000000", "Float"),
                                Line.credit(equity, "50000000", "Float"))))));
        category = ok(201, "POST", "/expense-categories", Map.of("name", "FX4 travel " + sfx, "ledgerAccountId",
                expenseAcc)).get("id").asString();
        String adminRole = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\""
                + " u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "fx4-checker-" + sfx + "@demo.test";
        createUser(demoOrg, email, "Check@12345", adminRole);
        checkerToken = login("DEMO", email, "Check@12345");
        String functional = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        ccy = "Q4" + (char) ('A' + (int) (Math.random() * 26));
        while (jdbc.sql("SELECT count(*) FROM \"Currency\" WHERE code = ?").param(ccy).query(Long.class).single() > 0) {
            ccy = "Q4" + (char) ('A' + (int) (Math.random() * 26));
        }
        ok(201, "POST", "/currencies", Map.of("code", ccy, "symbol", ccy, "name", "FX4 " + ccy));
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", ccy, "toCode", functional, "asOf",
                LocalDate.now().minusDays(1).toString(), "rate", 3700));
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    JsonNode post(String token, String path, Object body) {
        Res r = call("POST", path, body, Map.of("Authorization", "Bearer " + token, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(r.status()).as(path + " " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    String foreignAccount(String type) {
        return post(adminToken, "/accounts/cash-flow", Map.of("code", "19" + sfx(), "name", "FX4 " + type + " " + sfx(),
                "accountType", type, "currencyId", ccy)).get("id").asString();
    }

    BigDecimal[] bucket(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(l.debit - l.credit), 0), coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0)"
                        + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE"
                        + " l.\"organizationId\" = ? AND l.\"accountId\" = ? AND upper(l.\"currencyId\") = ? AND"
                        + " e.status::text IN ('posted', 'reversed')").params(demoOrg, accountId, ccy)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).single();
    }

    BigDecimal[] advanceBucket() {
        String id = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1357'").param(demoOrg)
                .query(String.class).optional().orElse(null);
        return id == null ? new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO} : bucket(id);
    }

    BigDecimal fxNet() {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"organizationId\" = ? AND l.\"accountId\" IN (SELECT \"accountId\" FROM \"AccountMapping\" WHERE"
                + " \"organizationId\" = ? AND key IN ('fx_gain', 'fx_loss'))").params(demoOrg, demoOrg)
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("advance $100 paid at 3,700, a 185,000 claim consumes $50, $50 returned at 3,800: gain 5,000")
    void foreignAdvance() {
        BigDecimal[] before = advanceBucket();
        String adv = post(adminToken, "/expense-advances", Map.of("purpose", "FX4 trip", "amount", 100,
                "currencyCode", ccy)).get("id").asString();
        post(checkerToken, "/expense-advances/" + adv + "/approve", Map.of());
        JsonNode disbursed = post(checkerToken, "/expense-advances/" + adv + "/disburse", Map.of("paymentMethod",
                "BANK_TRANSFER", "accountId", bank, "exchangeRate", 3700));
        assertThat(disbursed.get("status").asString()).isEqualTo("disbursed");
        BigDecimal[] after = advanceBucket();
        assertThat(after[0].subtract(before[0])).isEqualByComparingTo("100");
        assertThat(after[1].subtract(before[1])).isEqualByComparingTo("370000");

        String claim = post(adminToken, "/expense-claims", Map.of("title", "FX4 trip costs", "advanceId", adv, "lines",
                List.of(Map.of("title", "Hotel", "amount", 185000, "expenseDate", LocalDate.now().toString(),
                        "categoryId", category, "receiptNumber", "R-" + sfx())))).get("id").asString();
        post(adminToken, "/expense-claims/" + claim + "/submit", Map.of());
        JsonNode settled = post(checkerToken, "/expense-claims/" + claim + "/approve", Map.of());
        assertThat(settled.get("status").asString()).isEqualTo("paid");
        assertThat(new BigDecimal(settled.get("advanceApplied").asString())).isEqualByComparingTo("185000");
        after = advanceBucket();
        assertThat(after[0].subtract(before[0])).as("$50 left").isEqualByComparingTo("50");
        assertThat(after[1].subtract(before[1])).as("at 3,700").isEqualByComparingTo("185000");

        BigDecimal fx0 = fxNet();
        JsonNode refunded = post(checkerToken, "/expense-advances/" + adv + "/refund", Map.of("paymentMethod",
                "BANK_TRANSFER", "accountId", bank, "exchangeRate", 3800));
        assertThat(refunded.get("status").asString()).isEqualTo("settled");
        assertThat(fxNet().subtract(fx0)).as("50 × (3,800 − 3,700)").isEqualByComparingTo("5000");
        after = advanceBucket();
        assertThat(after[0]).isEqualByComparingTo(before[0]);
        assertThat(after[1]).isEqualByComparingTo(before[1]);
    }

    @Test
    @DisplayName("an advance in one currency cannot be paid from an account held in another")
    void advanceAccountCurrencyChecked() {
        String other = jdbc.sql("SELECT a.id FROM \"Account\" a WHERE a.\"organizationId\" = ? AND a.\"currencyId\" IS"
                + " NOT NULL AND upper(a.\"currencyId\") <> ? AND upper(a.\"currencyId\") <> (SELECT \"currencyCode\" FROM"
                + " \"Organization\" WHERE id = ?) LIMIT 1").params(demoOrg, ccy, demoOrg).query(String.class).optional()
                .orElse(null);
        String adv = post(adminToken, "/expense-advances", Map.of("purpose", "FX4 wrong account", "amount", 10,
                "currencyCode", ccy)).get("id").asString();
        post(checkerToken, "/expense-advances/" + adv + "/approve", Map.of());
        if (other != null) {
            Res r = call("POST", "/expense-advances/" + adv + "/disburse", Map.of("paymentMethod", "CASH", "accountId",
                    other), Map.of("Authorization", "Bearer " + checkerToken, "Idempotency-Key",
                            UUID.randomUUID().toString()));
            assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).contains("this advance is in " + ccy);
        }
        Res unknown = call("POST", "/expense-advances", Map.of("purpose", "x", "amount", 1, "currencyCode", "ZZ9"),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(unknown.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("petty cash held in the currency: topped up at 3,700, counted 5 short at its carrying rate")
    void foreignPettyCash() {
        String fundAcc = foreignAccount("cash");
        String fund = post(adminToken, "/petty-cash-funds", Map.of("name", "FX4 float " + sfx(), "accountId", fundAcc,
                "floatAmount", 200)).get("id").asString();
        post(adminToken, "/petty-cash-funds/" + fund + "/replenish", Map.of("sourceAccountId", bank, "paymentMethod",
                "BANK_TRANSFER", "exchangeRate", 3700));
        BigDecimal[] b = bucket(fundAcc);
        assertThat(b[0]).isEqualByComparingTo("200");
        assertThat(b[1]).isEqualByComparingTo("740000");
        post(adminToken, "/petty-cash-funds/" + fund + "/counts", Map.of("countedAmount", 195));
        b = bucket(fundAcc);
        assertThat(b[0]).isEqualByComparingTo("195");
        assertThat(b[1]).as("5 × 3,700 written off").isEqualByComparingTo("721500");
    }

    @Test
    @DisplayName("banking 370,000 of shift cash into a currency bank at 3,700 credits 100 of the currency")
    void shiftBankingIntoForeignBank() {
        String fxBank = foreignAccount("bank");
        String code = "FX4-" + sfx();
        JsonNode register = ok(201, "POST", "/cash-registers", Map.of("code", code, "name", "FX4 till"));
        Res opened = call("POST", "/cash-sessions/open", Map.of("cashRegisterId", register.get("id").asString(),
                "openingFloat", 500000, "openingSourceAccountId", bank, "notes", "FX4 float"),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        String sessionId = opened.body().get("id").asString();
        post(adminToken, "/cash-sessions/" + sessionId + "/banking", Map.of("destinationAccountId", fxBank, "amount",
                370000, "bankName", "FX4 bank", "exchangeRate", 3700));
        BigDecimal[] b = bucket(fxBank);
        assertThat(b[0]).isEqualByComparingTo("100");
        assertThat(b[1]).isEqualByComparingTo("370000");
        call("POST", "/cash-sessions/close", Map.of("sessionId", sessionId, "closingCounted", 130000),
                Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
    }
}
