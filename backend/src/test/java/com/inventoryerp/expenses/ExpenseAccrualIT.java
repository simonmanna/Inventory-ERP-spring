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
 * ADR-S051 accrual recognition: an approved CREDIT expense posts Dr expense / Cr payable (supplier as partner),
 * payments — full or partial — clear the payable, edits re-post the accrual, void and delete reverse it, and an
 * expense approved before ADR-S051 (no accrual) still debits the expense when paid.
 */
class ExpenseAccrualIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String adminId;
    String bank;
    String expenseAcc;
    String payable;
    String category;
    String supplier;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        bank = account("1200");
        expenseAcc = account("5200");
        payable = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'accounts_payable'").param(demoOrg).query(String.class).single();
        String equity = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'equity' AND a.\"isPostable\" AND a.\"isActive\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).single();
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        inTenant(() -> posting.post(PostingRequest.of("GEN", Instant.now(), "Expense accrual float", "expense_it", sfx,
                "expense_it:float:" + sfx, List.of(Line.debit(bank, "1000000", "Float"),
                        Line.credit(equity, "1000000", "Float")))));
        category = ok(201, "POST", "/expense-categories", Map.of("name", "Accrual " + sfx, "ledgerAccountId",
                expenseAcc)).get("id").asString();
        supplier = ok(201, "POST", "/partners", Map.of("code", "EXA-" + sfx, "name", "Accrual supplier " + sfx,
                "isSupplier", true)).get("id").asString();
    }

    void inTenant(Runnable r) {
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> r.run()));
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    Res money(String path, Object body) {
        return call("POST", path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    JsonNode okMoney(String path, Object body) {
        Res r = money(path, body);
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        return r.body();
    }

    String creditExpense(int amount) {
        return okMoney("/expenses", Map.of("title", "Crusher spares " + UUID.randomUUID().toString().substring(0, 4),
                "amount", amount, "expenseDate", LocalDate.now().toString(), "paymentType", "CREDIT", "categoryId",
                category, "supplierId", supplier)).get("id").asString();
    }

    List<Map<String, Object>> accruals(String expenseId) {
        return jdbc.sql("SELECT id, status::text AS status FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = 'expense_accrual' AND \"sourceId\" = ? ORDER BY \"createdAt\"")
                .params(demoOrg, expenseId).query().listOfRows();
    }

    /** Net (debit − credit) on {@code accountId} over the expense's accrual, payment and reversal entries. */
    BigDecimal net(String accountId, String expenseId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                        + " e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND (e.\"sourceId\" = ? OR e.\"reversalOfId\""
                        + " IN (SELECT id FROM \"JournalEntry\" WHERE \"sourceId\" = ?))")
                .params(accountId, expenseId, expenseId).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("approved CREDIT expense accrues Dr expense / Cr payable; partial + final payments clear the payable")
    void accrualAndPartialPayments() {
        String id = creditExpense(100);
        List<Map<String, Object>> acc = accruals(id);
        assertThat(acc).hasSize(1);
        String entry = (String) acc.getFirst().get("id");
        Map<String, Object> cr = jdbc.sql("SELECT \"accountId\", \"partnerId\", credit FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ? AND credit > 0").param(entry).query().singleRow();
        assertThat(cr.get("accountId")).isEqualTo(payable);
        assertThat(cr.get("partnerId")).isEqualTo(supplier);
        assertThat(net(expenseAcc, id)).isEqualByComparingTo("100");
        assertThat(net(payable, id)).isEqualByComparingTo("-100");

        JsonNode part = okMoney("/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank,
                "amount", 30));
        assertThat(part.get("paymentStatus").asString()).isEqualTo("PARTIALLY_PAID");
        assertThat(part.get("amountPaid").asInt()).isEqualTo(30);
        assertThat(net(payable, id)).isEqualByComparingTo("-70");
        assertThat(net(bank, id)).isEqualByComparingTo("-30");
        assertThat(net(expenseAcc, id)).as("payment does not touch the expense").isEqualByComparingTo("100");

        Res over = money("/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank,
                "amount", 80));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString())
                .isEqualTo("Payment amount 80 exceeds the outstanding balance of 70");
        Res zero = money("/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank,
                "amount", 0));
        assertThat(zero.status()).isEqualTo(400);

        JsonNode rest = okMoney("/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank));
        assertThat(rest.get("paymentStatus").asString()).isEqualTo("PAID");
        assertThat(rest.get("amountPaid").asInt()).isEqualTo(100);
        assertThat(rest.get("payments").size()).isEqualTo(2);
        assertThat(net(payable, id)).isEqualByComparingTo("0");
        assertThat(net(bank, id)).isEqualByComparingTo("-100");

        JsonNode voided = okMoney("/expenses/" + id + "/void", Map.of("voidReason", "Duplicate"));
        assertThat(voided.get("status").asString()).isEqualTo("VOID");
        assertThat(accruals(id).getFirst().get("status")).isEqualTo("reversed");
        for (String a : List.of(expenseAcc, payable, bank)) {
            assertThat(net(a, id)).as(a).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("editing an approved unpaid expense re-posts its accrual; deleting it reverses the accrual")
    void editAndDelete() {
        String id = creditExpense(120);
        assertThat(ok(200, "PATCH", "/expenses/" + id, Map.of("amount", 150)).get("amount").asInt()).isEqualTo(150);
        List<Map<String, Object>> acc = accruals(id);
        assertThat(acc).extracting(m -> m.get("status")).containsExactly("reversed", "posted");
        assertThat(net(expenseAcc, id)).isEqualByComparingTo("150");
        assertThat(net(payable, id)).isEqualByComparingTo("-150");
        assertThat(ok(200, "PATCH", "/expenses/" + id, Map.of("notes", "No GL effect")).get("notes").asString())
                .isEqualTo("No GL effect");
        assertThat(accruals(id)).hasSize(2);

        assertThat(as("DELETE", "/expenses/" + id, null).status()).isLessThan(300);
        assertThat(accruals(id)).extracting(m -> m.get("status")).containsOnly("reversed");
        assertThat(net(expenseAcc, id)).isEqualByComparingTo("0");
        assertThat(net(payable, id)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an expense approved before ADR-S051 (no accrual) still debits the expense when paid")
    void legacyCashBasis() {
        String id = creditExpense(40);
        String entry = (String) accruals(id).getFirst().get("id");
        inTenant(() -> posting.reverse(entry, null, "simulate a pre-ADR-S051 expense"));
        okMoney("/expenses/" + id + "/pay", Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank));
        String payment = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'expense_payment' AND"
                + " \"sourceId\" = ?").param(id).query(String.class).single();
        assertThat(jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND debit > 0")
                .param(payment).query(String.class).single()).isEqualTo(expenseAcc);
    }

    @Test
    @DisplayName("a CASH expense posts Dr expense / Cr bank at once and never accrues")
    void cashNeverAccrues() {
        String id = okMoney("/expenses", Map.of("title", "Courier", "amount", 15, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CASH", "paymentMethod", "BANK_TRANSFER", "accountId", bank,
                "categoryId", category)).get("id").asString();
        assertThat(accruals(id)).isEmpty();
        assertThat(net(expenseAcc, id)).isEqualByComparingTo("15");
        assertThat(net(bank, id)).isEqualByComparingTo("-15");
    }
}
