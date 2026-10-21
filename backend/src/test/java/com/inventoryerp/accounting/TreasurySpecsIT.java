package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.CashSessionService;
import com.inventoryerp.accounting.application.CashSessionService.BankDepositInput;
import com.inventoryerp.accounting.application.CashSessionService.CloseInput;
import com.inventoryerp.accounting.application.CashSessionService.MovementInput;
import com.inventoryerp.accounting.application.PosPaymentMethodService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * Ports of {@code pos-payment-method.service.spec.ts}, {@code cash-session.service.spec.ts} (input guards that fire
 * before any DB work) and the remaining cases of {@code period-close.service.spec.ts} / {@code posting.service.spec.ts}
 * not already pinned by {@link AccountingIT}.
 */
class TreasurySpecsIT extends ApiTestSupport {

    @Autowired
    CashSessionService sessions;

    @Autowired
    PosPaymentMethodService paymentMethods;

    @Autowired
    PostingService posting;

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    <T> T asCashier(Supplier<T> body) {
        String userId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        return TenantContext.run(TenantContext.TenantStore.user(demoOrg, userId), body);
    }

    static String code() {
        return "pm-" + UUID.randomUUID().toString().substring(0, 8);
    }

    Res createMethod(String kind, String accountId, Boolean trackInShift) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code());
        body.put("label", "Method");
        body.put("kind", kind);
        if (accountId != null) {
            body.put("accountId", accountId);
        }
        if (trackInShift != null) {
            body.put("trackInShift", trackInShift);
        }
        return as("POST", "/accounts/pos-payment-methods", body);
    }

    @Test
    @DisplayName("payment methods: account binding follows the tender posting rules")
    void paymentMethodBinding() {
        jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\") VALUES (?, ?, 'card_clearing', ?, now())"
                        + " ON CONFLICT (\"organizationId\", key) DO UPDATE SET \"accountId\" = EXCLUDED.\"accountId\"")
                .params(UUID.randomUUID().toString(), demoOrg, accountId("1900")).update();

        Res wallet = createMethod("mobile_money", accountId("MOMO-MTN"), null);
        assertThat(wallet.status()).as(wallet.raw().body()).isEqualTo(201);
        assertThat(wallet.body().get("accountId").asString()).isEqualTo(accountId("MOMO-MTN"));
        assertThat(wallet.body().get("kind").asString()).isEqualTo("mobile_money");

        assertThat(createMethod("mobile_money", accountId("BANK-DEFAULT"), null).body().get("message").asString())
                .isEqualTo("Bank Account 1 is not a Mobile Money account");
        assertThat(createMethod("card", accountId("1450"), null).body().get("message").asString())
                .contains("card_clearing");
        assertThat(createMethod("card", accountId("1900"), null).body().get("accountId").asString()).isEqualTo(accountId("1900"));

        JsonNode cash = createMethod("cash", accountId("MOMO-MTN"), true).body();
        assertThat(cash.get("accountId").isNull()).isTrue();
        assertThat(cash.get("trackInShift").asBoolean()).isFalse();
        assertThat(createMethod("bank", null, null).body().get("message").asString()).contains("needs a receiving account");

        assertThatThrownBy(() -> TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> paymentMethods.create(
                new PosPaymentMethodService.Upsert("m1", "Method", "crypto", null, accountId("MOMO-MTN"), null, null, null, null, null,
                        Set.of("code", "label", "kind", "accountId")))))
                .hasMessageStartingWith("Payment kind must be one of");
        Map<String, Object> dupe = Map.of("code", wallet.body().get("code").asString(), "label", "Again", "kind", "mobile_money",
                "accountId", accountId("MOMO-MTN"));
        assertThat(as("POST", "/accounts/pos-payment-methods", dupe).body().get("message").asString())
                .isEqualTo("A payment method with this code already exists");
    }

    static MovementInput movement(String type, Object amount, String reason, String counterpart) {
        return new MovementInput(counterpart, null, type, new BigDecimal(amount.toString()), reason, null, null, null, null);
    }

    static ApiException apiError(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected an ApiException");
    }

    @Test
    @DisplayName("cash session input guards fire before the transaction; a negative adjustment gets through")
    void cashSessionGuards() {
        assertThat(apiError(() -> asCashier(() -> sessions.recordMovement(null, movement("sale", 50, "Forged sale", "x"))))
                .getMessage()).containsIgnoringCase("movement type must be");
        assertThat(apiError(() -> asCashier(() -> sessions.recordMovement(null, movement("pay_in", 0, null, null))))
                .getMessage()).containsIgnoringCase("cannot be zero");
        assertThat(apiError(() -> asCashier(() -> sessions.recordMovement(null, movement("pay_in", -50, null, null))))
                .getMessage()).containsIgnoringCase("must be positive");
        assertThat(apiError(() -> asCashier(() -> sessions.recordMovement(null, movement("pay_out", -50, null, null))))
                .getMessage()).containsIgnoringCase("must be positive");
        // Reaches the transaction: the admin has no open shift.
        ApiException reached = apiError(() -> asCashier(() -> sessions.recordMovement(null,
                movement("adjustment", -50, "Count correction", accountId("5400")))));
        assertThat(reached.status()).as(reached.getMessage()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reached.getMessage()).isEqualTo("No open cash session");

        assertThat(apiError(() -> asCashier(() -> sessions.close(close(new BigDecimal("-1"), null), false))).getMessage())
                .containsIgnoringCase("cannot be negative");
        assertThat(apiError(() -> asCashier(() -> sessions.close(close(new BigDecimal("100"), Map.of("50", 1)), false))).getMessage())
                .containsIgnoringCase("denomination total");
        ApiException deposit = apiError(() -> asCashier(() -> sessions.recordBankDeposit("sess-1",
                new BankDepositInput(accountId("BANK-DEFAULT"), new BigDecimal("100"), "Stanbic", null, null, null))));
        assertThat(deposit.getMessage()).isEqualTo("Cash session not found");
    }

    static CloseInput close(BigDecimal counted, Map<String, Object> denomination) {
        return new CloseInput(null, null, null, counted, null, null, null, null, null, null, null, denomination, null, null);
    }

    @Test
    @DisplayName("reopen of an OPEN period is a no-op; a posted postingKey replays; keyless reversal keeps no key")
    void postingAndPeriodEdges() {
        String periodId = jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND status::text = 'open'"
                + " AND \"startDate\" <= now() AND \"endDate\" >= now() LIMIT 1").param(demoOrg).query(String.class).single();
        assertThat(ok(200, "POST", "/fiscal-periods/" + periodId + "/reopen", null).get("reopened").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT status::text FROM \"FiscalPeriod\" WHERE id = ?").param(periodId).query(String.class).single())
                .isEqualTo("open");

        String key = "spec:" + UUID.randomUUID();
        List<PostingRequest.Line> lines = List.of(PostingRequest.Line.debit(accountId("BANK-DEFAULT"), 10, null),
                PostingRequest.Line.credit(accountId("3100"), 10, null));
        Map<String, Object> first = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> posting.post(PostingRequest.of("GEN", Instant.now(), "Keyed", "spec", null, key, lines)));
        Map<String, Object> replay = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> posting.post(PostingRequest.of("GEN", Instant.now(), "Keyed", "spec", null, key, lines)));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));

        Map<String, Object> keyless = TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> posting.post(PostingRequest.of("GEN", Instant.now(), "Keyless", "spec", null, null, lines)));
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg),
                () -> posting.reverse((String) keyless.get("id"), null, null));
        assertThat(jdbc.sql("SELECT \"postingKey\" FROM \"JournalEntry\" WHERE id = ?").param(keyless.get("id"))
                .query(String.class).optional()).isEmpty();
    }
}
