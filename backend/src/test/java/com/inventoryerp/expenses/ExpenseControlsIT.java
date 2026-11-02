package com.inventoryerp.expenses;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
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
 * EX-1 / EX-2 (ADR-S052): category controls, tax + allocation lines + dimensions in the accrual, currency check,
 * employee-paid expenses, manual approval / return / resubmit, spending limits, GL budget check, payer separation of
 * duties and settings.
 */
class ExpenseControlsIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String adminId;
    String bank;
    String expenseAcc;
    String supplier;
    String checkerToken;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        bank = account("1200");
        expenseAcc = account("5200");
        String equity = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'equity' AND a.\"isPostable\" AND a.\"isActive\" AND"
                + " a.\"deletedAt\" IS NULL ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).single();
        String sfx = sfx();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> posting.post(PostingRequest.of("GEN", Instant.now(), "Controls float",
                        "expense_it", sfx, "expense_it:controls:" + sfx, List.of(Line.debit(bank, "1000000", "Float"),
                                Line.credit(equity, "1000000", "Float"))))));
        supplier = ok(201, "POST", "/partners", Map.of("code", "EXC-" + sfx, "name", "Controls supplier " + sfx,
                "isSupplier", true)).get("id").asString();
        String adminRole = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id JOIN \"User\""
                + " u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1").query(String.class).single();
        String email = "exp-checker-" + sfx + "@demo.test";
        createUser(demoOrg, email, "Check@12345", adminRole);
        checkerToken = login("DEMO", email, "Check@12345");
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    Res money(String token, String path, Object body) {
        return call("POST", path, body, Map.of("Authorization", "Bearer " + token, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    JsonNode okMoney(String token, String path, Object body) {
        Res r = money(token, path, body);
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        return r.body();
    }

    String category(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Ctl " + sfx(), "ledgerAccountId", expenseAcc));
        body.putAll(extra);
        return ok(201, "POST", "/expense-categories", body).get("id").asString();
    }

    Map<String, Object> credit(String categoryId, int amount, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Ctl " + sfx(), "amount", amount, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CREDIT", "categoryId", categoryId, "supplierId", supplier));
        body.putAll(extra);
        return body;
    }

    List<Map<String, Object>> accrualLines(String expenseId) {
        return jdbc.sql("SELECT a.code, l.debit, l.credit, l.\"costCenterId\", l.\"branchId\", l.\"partnerId\" FROM"
                        + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON"
                        + " a.id = l.\"accountId\" WHERE e.\"sourceType\" = 'expense_accrual' AND e.\"sourceId\" = ? AND"
                        + " e.status = 'posted' ORDER BY l.\"lineNumber\"").param(expenseId).query().listOfRows();
    }

    @Test
    @DisplayName("category controls round-trip; code is unique; bad budgetControl is a 400")
    void categoryControls() {
        String code = "TRV-" + sfx();
        JsonNode cat = ok(201, "POST", "/expense-categories", Map.of("name", "Travel " + code, "code", code,
                "receiptRequired", true, "approvalThreshold", 500, "reimbursable", false, "budgetControl", "warn",
                "canCapitalise", true));
        assertThat(cat.get("code").asString()).isEqualTo(code);
        assertThat(cat.get("receiptRequired").asBoolean()).isTrue();
        assertThat(cat.get("budgetControl").asString()).isEqualTo("warn");
        assertThat(cat.get("reimbursable").asBoolean()).isFalse();
        assertThat(as("POST", "/expense-categories", Map.of("name", "Dup " + code, "code", code.toLowerCase()))
                .status()).isEqualTo(409);
        assertThat(as("POST", "/expense-categories", Map.of("name", "Bad " + code, "budgetControl", "maybe"))
                .status()).isEqualTo(400);
        JsonNode patched = ok(200, "PATCH", "/expense-categories/" + cat.get("id").asString(),
                Map.of("receiptRequired", false, "code", ""));
        assertThat(patched.get("receiptRequired").asBoolean()).isFalse();
        assertThat(patched.get("code").isNull()).isTrue();
    }

    @Test
    @DisplayName("tax + percent allocations + branch accrue Dr split net / Dr input tax / Cr AP")
    void taxAndAllocations() {
        String cc1 = ok(201, "POST", "/cost-centers", Map.of("code", "EXA-" + sfx(), "name", "Plant A")).get("id")
                .asString();
        String cc2 = ok(201, "POST", "/cost-centers", Map.of("code", "EXB-" + sfx(), "name", "Plant B")).get("id")
                .asString();
        String cat = category(Map.of());
        JsonNode exp = okMoney(adminToken, "/expenses", credit(cat, 118, Map.of("taxAmount", 18, "receiptNumber",
                "INV-77", "dueDate", LocalDate.now().plusDays(30).toString(), "allocations", List.of(
                        Map.of("percent", 60, "costCenterId", cc1), Map.of("percent", 40, "costCenterId", cc2)))));
        assertThat(exp.get("status").asString()).isEqualTo("APPROVED");
        String id = exp.get("id").asString();
        JsonNode full = ok(200, "GET", "/expenses/" + id, null);
        assertThat(full.get("taxAmount").asDouble()).isEqualTo(18);
        assertThat(full.get("netAmount").asDouble()).isEqualTo(100);
        assertThat(full.get("receiptNumber").asString()).isEqualTo("INV-77");
        assertThat(full.get("allocations")).hasSize(2);
        assertThat(full.get("allocations").get(0).get("amount").asDouble()).isEqualTo(60);

        List<Map<String, Object>> lines = accrualLines(id);
        assertThat(lines).hasSize(4);
        assertThat(lines.stream().filter(l -> cc1.equals(l.get("costCenterId"))).findFirst().orElseThrow().get("debit"))
                .isEqualTo(new BigDecimal("60.000000"));
        assertThat(lines.stream().filter(l -> cc2.equals(l.get("costCenterId"))).findFirst().orElseThrow().get("debit"))
                .isEqualTo(new BigDecimal("40.000000"));
        BigDecimal debits = lines.stream().map(l -> (BigDecimal) l.get("debit")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo("118");

        // amount allocations that do not add up to the net are refused
        Res bad = money(adminToken, "/expenses", credit(cat, 100, Map.of("allocations", List.of(
                Map.of("amount", 30, "costCenterId", cc1), Map.of("amount", 30, "costCenterId", cc2)))));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.raw().body()).contains("Allocation lines must add up to the net amount 100");
    }

    @Test
    @DisplayName("receipt required, currency product, employee-paid CREDIT accrues to reimbursement payable")
    void categoryRulesAndEmployeePaid() {
        String cat = category(Map.of("receiptRequired", true));
        Res noReceipt = money(adminToken, "/expenses", credit(cat, 50, Map.of()));
        assertThat(noReceipt.status()).isEqualTo(400);
        assertThat(noReceipt.raw().body()).contains("requires a receipt");

        Res wrongFx = money(adminToken, "/expenses", credit(cat, 36000, Map.of("receiptNumber", "R1", "currencyCode",
                "USD", "foreignAmount", 10, "exchangeRate", 3700)));
        assertThat(wrongFx.status()).isEqualTo(400);
        assertThat(wrongFx.raw().body()).contains("must equal foreignAmount");
        okMoney(adminToken, "/expenses", credit(cat, 37000, Map.of("receiptNumber", "R2", "currencyCode", "USD",
                "foreignAmount", 10, "exchangeRate", 3700)));

        Map<String, Object> cashEmployee = credit(cat, 20, Map.of("receiptNumber", "R3", "paidBy", "EMPLOYEE",
                "paymentType", "CASH", "paymentMethod", "CASH", "accountId", bank));
        assertThat(money(adminToken, "/expenses", cashEmployee).status()).isEqualTo(400);

        JsonNode emp = okMoney(adminToken, "/expenses", credit(cat, 25, Map.of("receiptNumber", "R4", "paidBy",
                "EMPLOYEE")));
        assertThat(emp.get("claimantId").asString()).isEqualTo(adminId);
        Map<String, Object> cr = accrualLines(emp.get("id").asString()).stream()
                .filter(l -> ((BigDecimal) l.get("credit")).signum() > 0).findFirst().orElseThrow();
        assertThat(cr.get("code")).isEqualTo("2189");
        assertThat(cr.get("partnerId")).isNull();

        String nonReimb = category(Map.of("reimbursable", false));
        assertThat(money(adminToken, "/expenses", credit(nonReimb, 10, Map.of("paidBy", "EMPLOYEE"))).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("approval-required category: stays DRAFT, returned, edited, resubmitted, approved by someone else")
    void manualApprovalReturnResubmit() {
        String cat = category(Map.of("approvalRequired", true));
        JsonNode exp = okMoney(adminToken, "/expenses", credit(cat, 80, Map.of()));
        String id = exp.get("id").asString();
        assertThat(exp.get("status").asString()).isEqualTo("DRAFT");
        assertThat(accrualLines(id)).isEmpty();

        Res selfReturn = call("POST", "/expenses/" + id + "/return", Map.of("reason", "x"), adminToken);
        assertThat(selfReturn.status()).isEqualTo(403);
        Res returned = call("POST", "/expenses/" + id + "/return", Map.of("reason", "Attach the invoice"), checkerToken);
        assertThat(returned.status()).as(returned.raw().body()).isLessThan(300);
        assertThat(returned.body().get("status").asString()).isEqualTo("RETURNED");

        ok(200, "PATCH", "/expenses/" + id, Map.of("amount", 85, "receiptNumber", "INV-9"));
        JsonNode resubmitted = okMoney(adminToken, "/expenses/" + id + "/submit", Map.of());
        assertThat(resubmitted.get("status").asString()).isEqualTo("DRAFT");

        JsonNode approved = okMoney(checkerToken, "/expenses/" + id + "/approve", Map.of("approvalNotes", "ok"));
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(accrualLines(id)).hasSize(2);

        // cash expense in an approval-required category is refused
        Map<String, Object> cash = credit(cat, 10, Map.of("paymentType", "CASH", "paymentMethod", "CASH",
                "accountId", bank));
        assertThat(money(adminToken, "/expenses", cash).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("spending limits: per-expense block, monthly warn")
    void limits() {
        String cat = category(Map.of());
        JsonNode limit = ok(201, "POST", "/expense-limits", Map.of("name", "Cap " + sfx(), "categoryId", cat,
                "amount", 500));
        Res over = money(adminToken, "/expenses", credit(cat, 600, Map.of()));
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.body().get("code").asString()).isEqualTo("EXPENSE_LIMIT_EXCEEDED");
        okMoney(adminToken, "/expenses", credit(cat, 400, Map.of()));
        ok(200, "PATCH", "/expense-limits/" + limit.get("id").asString(), Map.of("period", "monthly", "action", "warn",
                "amount", 500));
        okMoney(adminToken, "/expenses", credit(cat, 400, Map.of()));
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'ExpenseLimit' AND \"entityId\" = ?")
                .param(limit.get("id").asString()).query(Long.class).single()).isGreaterThanOrEqualTo(2);
        assertThat(call("POST", "/expense-limits", Map.of("name", "x", "amount", 1), weakToken()).status())
                .isEqualTo(403);
        call("DELETE", "/expense-limits/" + limit.get("id").asString(), null, adminToken);
    }

    @Test
    @DisplayName("GL budget block: an expense that takes the account's month over budget is refused")
    void budgetCheck() {
        String category5200 = jdbc.sql("SELECT \"categoryId\" FROM \"Account\" WHERE id = ?").param(expenseAcc)
                .query(String.class).single();
        String acc = ok(201, "POST", "/accounts", Map.of("code", "6" + (int) (Math.random() * 900000 + 100000), "name",
                "Budgeted " + sfx(), "categoryId", category5200)).get("id").asString();
        String cat = ok(201, "POST", "/expense-categories", Map.of("name", "Budgeted " + sfx(), "ledgerAccountId", acc,
                "budgetControl", "block")).get("id").asString();
        YearMonth month = YearMonth.now();
        BigDecimal actual = jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND e.status IN"
                + " ('posted', 'reversed') AND e.\"postingDate\" >= ?::timestamp").params(acc,
                month.atDay(1).toString()).query(BigDecimal.class).single();
        JsonNode budget = ok(201, "POST", "/budgets", Map.of("name", "Exp budget " + sfx(), "fiscalYear",
                month.getYear(), "startDate", month.atDay(1).toString(), "endDate", month.atEndOfMonth().toString(),
                "lines", List.of(Map.of("accountId", acc, "period", month.toString(), "amount",
                        actual.add(new BigDecimal("100"))))));
        okMoney(adminToken, "/budgets/" + budget.get("id").asString() + "/approve", Map.of());
        Res over = money(adminToken, "/expenses", credit(cat, 150, Map.of()));
        assertThat(over.status()).as(over.raw().body()).isEqualTo(409);
        assertThat(over.body().get("code").asString()).isEqualTo("EXPENSE_BUDGET_EXCEEDED");
        okMoney(adminToken, "/expenses", credit(cat, 60, Map.of()));
    }

    @Test
    @DisplayName("payer SoD: with the expense_payment rule on, the raiser cannot pay; someone else can")
    void payerSegregation() {
        String cat = category(Map.of());
        String id = okMoney(adminToken, "/expenses", credit(cat, 40, Map.of())).get("id").asString();
        ok(200, "PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.EXPENSE_PAYMENT,
                Map.of("isActive", true));
        try {
            Map<String, Object> pay = Map.of("paymentMethod", "BANK_TRANSFER", "accountId", bank);
            assertThat(money(adminToken, "/expenses/" + id + "/pay", pay).status()).isEqualTo(403);
            JsonNode paid = okMoney(checkerToken, "/expenses/" + id + "/pay", pay);
            assertThat(paid.get("paymentStatus").asString()).isEqualTo("PAID");
        } finally {
            ok(200, "PUT", "/approval-controls/sod-rules/" + SegregationOfDutiesService.EXPENSE_PAYMENT,
                    Map.of("isActive", false));
        }
    }

    @Test
    @DisplayName("settings: defaults, update, 403 without expense:approve")
    void settings() {
        JsonNode s = ok(200, "GET", "/expense-settings", null);
        assertThat(s.get("notifyOnDecision").asBoolean()).isTrue();
        JsonNode updated = ok(200, "PUT", "/expense-settings", Map.of("recurringReminderDays", 5));
        assertThat(updated.get("recurringReminderDays").asInt()).isEqualTo(5);
        assertThat(updated.get("notifyOnDecision").asBoolean()).isTrue();
        assertThat(call("PUT", "/expense-settings", Map.of("notifyOnPayment", false), weakToken()).status())
                .isEqualTo(403);
        assertThat(as("PUT", "/expense-settings", Map.of("recurringReminderDays", 99)).status()).isEqualTo(400);
    }
}
