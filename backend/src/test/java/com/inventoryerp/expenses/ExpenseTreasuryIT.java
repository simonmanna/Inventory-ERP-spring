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
 * EX-4 / EX-5 / EX-6 (ADR-S052): petty cash (replenish to float, custodian cap, count with short posted, statement),
 * prepaid release schedule, period-end accrual with auto-reversal, recurring expenses with reminders, expense reports.
 */
class ExpenseTreasuryIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    String adminId;
    String bank;
    String expenseAcc;
    String category;

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
                .executeWithoutResult(s -> posting.post(PostingRequest.of("GEN", Instant.now(), "Treasury float",
                        "expense_it", sfx, "expense_it:treasury:" + sfx, List.of(Line.debit(bank, "1000000", "Float"),
                                Line.credit(equity, "1000000", "Float"))))));
        category = ok(201, "POST", "/expense-categories", Map.of("name", "Office " + sfx, "ledgerAccountId", expenseAcc))
                .get("id").asString();
    }

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    JsonNode post(String path, Object body) {
        Res r = call("POST", path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(r.status()).as(path + " " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    Res postRaw(String path, Object body) {
        return call("POST", path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    BigDecimal net(String accountId, String sourceType, String sourceId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND e.\"sourceType\" = ? AND e.\"sourceId\" = ?")
                .params(accountId, sourceType, sourceId).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("petty cash: replenish to float, cap per payment, count short posts to 5400, statement")
    void pettyCash() {
        String pettyCategory = jdbc.sql("SELECT \"categoryId\" FROM \"Account\" WHERE \"organizationId\" = ? AND code ="
                + " 'PETTY'").param(demoOrg).query(String.class).single();
        String fundAcc = ok(201, "POST", "/accounts", Map.of("code", "PC" + (int) (Math.random() * 90000 + 10000),
                "name", "Site petty cash " + sfx(), "categoryId", pettyCategory)).get("id").asString();
        JsonNode fund = ok(201, "POST", "/petty-cash-funds", Map.of("name", "Site " + sfx(), "accountId", fundAcc,
                "custodianId", adminId, "floatAmount", 500, "maxPayment", 200));
        String id = fund.get("id").asString();
        assertThat(fund.get("replenishmentDue").asDouble()).isEqualTo(500);
        assertThat(as("POST", "/petty-cash-funds", Map.of("name", "dup", "accountId", fundAcc, "floatAmount", 1))
                .status()).isEqualTo(409);

        JsonNode topped = post("/petty-cash-funds/" + id + "/replenish", Map.of("sourceAccountId", bank));
        assertThat(topped.get("balance").asDouble()).isEqualTo(500);
        assertThat(postRaw("/petty-cash-funds/" + id + "/replenish", Map.of("sourceAccountId", bank)).status())
                .isEqualTo(400);

        Map<String, Object> cash = new HashMap<>(Map.of("title", "Tea " + sfx(), "amount", 150, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CASH", "paymentMethod", "CASH", "accountId", fundAcc,
                "categoryId", category));
        post("/expenses", cash);
        cash.put("amount", 250);
        Res capped = postRaw("/expenses", cash);
        assertThat(capped.status()).isEqualTo(400);
        assertThat(capped.raw().body()).contains("pays at most 200");

        JsonNode counted = post("/petty-cash-funds/" + id + "/counts", Map.of("countedAmount", 340, "notes", "EOD"));
        assertThat(counted.get("balance").asDouble()).isEqualTo(340);
        assertThat(counted.get("lastCount").get("difference").asDouble()).isEqualTo(-10);
        assertThat(net(account("5400"), "petty_cash_count", id)).isEqualByComparingTo("10");
        assertThat(counted.get("replenishmentDue").asDouble()).isEqualTo(160);

        JsonNode st = ok(200, "GET", "/petty-cash-funds/" + id + "/statement", null);
        assertThat(st.get("openingBalance").asDouble()).isEqualTo(0);
        assertThat(st.get("lines")).hasSize(3);
        assertThat(st.get("closingBalance").asDouble()).isEqualTo(340);
        assertThat(call("POST", "/petty-cash-funds", Map.of("name", "x", "accountId", fundAcc, "floatAmount", 1),
                weakToken()).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("prepaid: 3-month insurance debits 1385, releases one month at a time, void reverses releases")
    void prepaid() {
        YearMonth start = YearMonth.now().minusMonths(2);
        JsonNode exp = post("/expenses", Map.of("title", "Insurance " + sfx(), "amount", 300, "expenseDate",
                LocalDate.now().toString(), "paymentType", "CASH", "paymentMethod", "BANK_TRANSFER", "accountId", bank,
                "categoryId", category, "prepaidMonths", 3, "prepaidStart", start.atDay(1).toString()));
        String id = exp.get("id").asString();
        String prepaid = account("1385");
        assertThat(net(prepaid, "expense_payment", id)).isEqualByComparingTo("300");

        JsonNode run = post("/expense-schedules/run", Map.of("asOf", start.plusMonths(1).atDay(1).toString()));
        assertThat(run.get("errors")).isEmpty();
        String schedule = jdbc.sql("SELECT id FROM \"ExpensePrepayment\" WHERE \"expenseId\" = ?").param(id)
                .query(String.class).single();
        JsonNode sched = ok(200, "GET", "/expense-prepayments/" + schedule, null);
        assertThat(sched.get("releases")).hasSize(2);
        assertThat(sched.get("remaining").asDouble()).isEqualTo(100);
        assertThat(net(expenseAcc, "expense_prepaid_release", id)).isEqualByComparingTo("200");

        post("/expense-schedules/run", Map.of("asOf", LocalDate.now().toString()));
        assertThat(ok(200, "GET", "/expense-prepayments/" + schedule, null).get("status").asString())
                .isEqualTo("completed");
        post("/expenses/" + id + "/void", Map.of("voidReason", "wrong policy"));
        BigDecimal releasedNet = jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND e.\"reversalOfId\" IN"
                + " (SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'expense_prepaid_release' AND \"sourceId\" = ?)")
                .params(expenseAcc, id).query(BigDecimal.class).single();
        assertThat(releasedNet).isEqualByComparingTo("-300");
    }

    @Test
    @DisplayName("period-end accrual posts Dr expense / Cr 2158 and reverses on its reversal date")
    void periodAccrual() {
        LocalDate monthEnd = YearMonth.now().minusMonths(1).atEndOfMonth();
        JsonNode acc = post("/expense-accruals", Map.of("title", "Electricity " + sfx(), "amount", 120, "categoryId",
                category, "accrualDate", monthEnd.toString()));
        assertThat(acc.get("status").asString()).isEqualTo("posted");
        assertThat(acc.get("reversalDate").asString()).startsWith(monthEnd.plusDays(1).toString());
        String id = acc.get("id").asString();
        assertThat(net(account("2158"), "expense_period_accrual", id)).isEqualByComparingTo("-120");
        JsonNode run = post("/expense-schedules/run", Map.of("asOf", LocalDate.now().toString()));
        assertThat(run.get("accrualsReversed").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode list = ok(200, "GET", "/expense-accruals?status=reversed", null);
        assertThat(list.findValuesAsString("id")).contains(id);
    }

    @Test
    @DisplayName("recurring: catch-up runs raise CREDIT expenses, schedule advances, reminder sent before next run")
    void recurring() {
        LocalDate first = LocalDate.now().minusMonths(1);
        JsonNode rec = ok(201, "POST", "/expense-recurrences", Map.of("name", "Rent " + sfx(), "title", "Office rent",
                "amount", 1000, "categoryId", category, "frequency", "monthly", "nextRunDate", first.toString(),
                "reminderDays", 40, "dueDays", 10));
        String id = rec.get("id").asString();
        JsonNode run = post("/expense-schedules/run", Map.of("asOf", LocalDate.now().toString()));
        assertThat(run.get("errors")).isEmpty();
        JsonNode after = ok(200, "GET", "/expense-recurrences/" + id, null);
        assertThat(after.get("runCount").asInt()).isEqualTo(2);
        assertThat(after.get("expenses")).hasSize(2);
        assertThat(after.get("nextRunDate").asString()).startsWith(first.plusMonths(2).toString());
        assertThat(after.get("lastReminderFor").asString()).startsWith(first.plusMonths(2).toString());
        JsonNode generated = ok(200, "GET", "/expenses/" + after.get("expenses").get(0).get("id").asString(), null);
        assertThat(generated.get("paymentType").asString()).isEqualTo("CREDIT");
        assertThat(generated.get("dueDate").isNull()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND title LIKE 'Recurring expense"
                + " due%'").param(adminId).query(Long.class).single()).isGreaterThanOrEqualTo(1);
        ok(200, "POST", "/expense-recurrences/" + id + "/pause", null);
        assertThat(ok(200, "GET", "/expense-recurrences/" + id, null).get("status").asString()).isEqualTo("paused");
    }

    @Test
    @DisplayName("reports: summary by category / cost centre / month, trend, pipeline, schedules; bad groupBy 400")
    void reports() {
        post("/expenses", Map.of("title", "Report " + sfx(), "amount", 70, "expenseDate", LocalDate.now().toString(),
                "paymentType", "CREDIT", "categoryId", category));
        JsonNode byCat = ok(200, "GET", "/expense-reports/summary?groupBy=category", null);
        assertThat(byCat.get("rows").findValuesAsString("key")).contains(category);
        assertThat(ok(200, "GET", "/expense-reports/summary?groupBy=costCenter", null).get("rows")).isNotEmpty();
        assertThat(ok(200, "GET", "/expense-reports/summary?groupBy=month", null).get("rows")).isNotEmpty();
        assertThat(as("GET", "/expense-reports/summary?groupBy=planet", null).status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/expense-reports/trend?months=6", null).get("months")).hasSize(6);
        JsonNode pipeline = ok(200, "GET", "/expense-reports/pipeline", null);
        assertThat(pipeline.get("pendingPayment").get("count").asInt()).isGreaterThan(0);
        assertThat(ok(200, "GET", "/expense-reports/schedules", null).has("prepaidRemaining")).isTrue();
        ok(200, "GET", "/expense-reports/reimbursements", null);
        ok(200, "GET", "/expense-reports/advances", null);
        ok(200, "GET", "/expense-reports/petty-cash", null);
        ok(200, "GET", "/expense-reports/capital-purchases", null);
        assertThat(call("GET", "/expense-reports/summary", null, weakToken()).status()).isEqualTo(403);
    }
}
