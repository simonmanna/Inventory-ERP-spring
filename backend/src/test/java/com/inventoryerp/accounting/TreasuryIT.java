package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Treasury on Testcontainers Postgres: idempotent deposits / transfers, cash registers with their own drawer
 * account, and the cash-session simulation (open with funded float → pay-in / manager-approved pay-out → close
 * with variance → variance review → banking → reconcile) checked against the GL.
 */
class TreasuryIT extends ApiTestSupport {

    String cashier;
    String manager;

    @BeforeAll
    void staff() {
        cashier = login("DEMO", "sarah@demo.test", "Demo@123");
        manager = login("DEMO", "manager@demo.test", "Demo@123");
    }

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    BigDecimal ledger(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                        + " ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed')")
                .param(accountId).query(BigDecimal.class).single();
    }

    Res keyed(String token, String method, String path, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        if (key != null) {
            h.put("Idempotency-Key", key);
        }
        return call(method, path, body, h);
    }

    @Test
    @DisplayName("deposit + transfer: Idempotency-Key required, replayed, funds and drawer guards")
    void depositAndTransfer() {
        Map<String, Object> deposit = Map.of("accountId", accountId("1200"), "counterpartAccountId", accountId("3100"),
                "operationType", "owner_contribution", "amount", 1000000, "description", "Seed capital");
        Res noKey = keyed(adminToken, "POST", "/accounts/cash-flow/deposit", deposit, null);
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString())
                .isEqualTo("An Idempotency-Key header is required for this operation so a retry can never charge twice.");
        String key = UUID.randomUUID().toString();
        Res first = keyed(adminToken, "POST", "/accounts/cash-flow/deposit", deposit, key);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res replay = keyed(adminToken, "POST", "/accounts/cash-flow/deposit", deposit, key);
        assertThat(replay.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(first.body().get("dimensions").get("treasuryOperation").asString()).isEqualTo("owner_contribution");

        Res wrongKind = keyed(adminToken, "POST", "/accounts/cash-flow/withdraw", Map.of("accountId", accountId("1200"),
                "counterpartAccountId", accountId("4100"), "operationType", "owner_drawing", "amount", 10,
                "description", "x"), UUID.randomUUID().toString());
        assertThat(wrongKind.body().get("message").asString())
                .isEqualTo("Owner drawing must use a equity account; 4100 is revenue");

        Res transfer = keyed(adminToken, "POST", "/treasury/transfer", Map.of("fromAccountId", accountId("1200"),
                "toAccountId", accountId("BANK-DEFAULT"), "amount", 200000, "date", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()),
                UUID.randomUUID().toString());
        assertThat(transfer.status()).as(transfer.raw().body()).isEqualTo(200);
        assertThat(transfer.body().get("sourceType").asString()).isEqualTo("treasury_transfer");
        Res tooMuch = keyed(adminToken, "POST", "/treasury/transfer", Map.of("fromAccountId", accountId("MOMO-AIRTEL"),
                "toAccountId", accountId("1200"), "amount", 5, "date", LocalDate.now(ZoneOffset.UTC).toString()), UUID.randomUUID().toString());
        assertThat(tooMuch.body().get("message").asString()).isEqualTo("Airtel Money has insufficient funds (available 0.00)");
        Res drawer = keyed(adminToken, "POST", "/treasury/transfer", Map.of("fromAccountId", accountId("1200"),
                "toAccountId", accountId("1100"), "amount", 5, "date", LocalDate.now(ZoneOffset.UTC).toString()), UUID.randomUUID().toString());
        assertThat(drawer.body().get("message").asString()).isEqualTo("A transfer cannot use the drawer account of register MAIN-REG."
                + " Record it from that register's shift (pay-in, pay-out or banking).");

        JsonNode accounts = ok(200, "GET", "/accounts/cash-flow", null);
        assertThat(accounts.findValuesAsString("code")).contains("1200", "BANK-DEFAULT");
        JsonNode overview = ok(200, "GET", "/accounts/cash-flow/overview", null);
        assertThat(overview.has("totalAvailableBookBalance")).isTrue();
        JsonNode activity = ok(200, "GET", "/accounts/cash-flow/activity?categories=transfers,deposits", null);
        assertThat(activity.get("total").asInt()).isGreaterThanOrEqualTo(2);
        JsonNode report = ok(200, "GET", "/accounts/cash-flow/report?groupBy=month", null);
        assertThat(report.get("summary").get("movementCount").asInt()).isGreaterThan(0);
        JsonNode tx = ok(200, "GET", "/accounts/cash-flow/" + accountId("1200") + "/transactions", null);
        assertThat(tx.get("data").get(0).has("runningBalance")).isTrue();
        assertThat(ok(200, "GET", "/accounts/cash-flow/transactions?type=transfer", null).get("data").get(0).get("type").asString())
                .isEqualTo("transfer");
        assertThat(ok(200, "GET", "/accounts/cash-flow/operation-types", null).get("deposit")).isNotEmpty();
        assertThat(ok(200, "GET", "/cash-sessions/tender-settlements/sources", null).has("destinations")).isTrue();
        Res badQuery = as("GET", "/accounts/cash-flow/activity?direction=sideways&bogus=1", null);
        assertThat(badQuery.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("cash session: open (funded) → pay-in → approved pay-out → close with variance → review → bank → reconcile")
    void cashSessionLifecycle() {
        // Fund the bank so the float can be drawn from it.
        Res fund = keyed(adminToken, "POST", "/accounts/cash-flow/deposit", Map.of("accountId", accountId("1200"),
                "counterpartAccountId", accountId("3100"), "operationType", "owner_contribution", "amount", 500000,
                "description", "Float funding"), UUID.randomUUID().toString());
        assertThat(fund.status()).as(fund.raw().body()).isEqualTo(200);

        String code = "REG-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode register = ok(201, "POST", "/cash-registers", Map.of("code", code, "name", "Bar till"));
        String registerId = register.get("id").asString();
        String drawer = register.get("defaultAccountId").asString();
        assertThat(register.get("defaultAccount").get("code").asString()).isEqualTo("DRW-" + code);
        assertThat(ok(200, "GET", "/cash-registers/" + registerId + "/drawer-balance", null).get("ledger").asString()).isEqualTo("0");

        Res unfunded = keyed(cashier, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 50000),
                UUID.randomUUID().toString());
        assertThat(unfunded.body().get("message").asString()).isEqualTo("Choose the source of added float and enter its reason");
        Res opened = keyed(cashier, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 50000,
                "openingSourceAccountId", accountId("1200"), "notes", "Morning float"), UUID.randomUUID().toString());
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        String sessionId = opened.body().get("id").asString();
        assertThat(opened.body().get("status").asString()).isEqualTo("open");
        assertThat(ledger(drawer)).isEqualByComparingTo("50000");
        Res dupe = keyed(cashier, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 50000,
                "openingSourceAccountId", accountId("1200")), UUID.randomUUID().toString());
        assertThat(dupe.body().get("message").asString()).isEqualTo("A session is already open on register " + code);

        Res payIn = keyed(cashier, "POST", "/cash-sessions/movement", Map.of("sessionId", sessionId, "movementType", "pay_in",
                "amount", 10000, "reason", "Change top-up", "counterpartAccountId", accountId("3100")), UUID.randomUUID().toString());
        assertThat(payIn.status()).as(payIn.raw().body()).isEqualTo(200);
        Map<String, Object> payOut = new LinkedHashMap<>(Map.of("sessionId", sessionId, "movementType", "pay_out", "amount", 5000,
                "reason", "Ice delivery", "counterpartAccountId", accountId("5200")));
        Res unapproved = keyed(cashier, "POST", "/cash-sessions/movement", payOut, UUID.randomUUID().toString());
        assertThat(unapproved.status()).isEqualTo(400);
        assertThat(unapproved.body().get("code").asString()).isEqualTo("MANAGER_APPROVAL_REQUIRED");
        payOut.put("approverEmail", "manager@demo.test");
        payOut.put("managerPin", "0000");
        assertThat(keyed(cashier, "POST", "/cash-sessions/movement", payOut, UUID.randomUUID().toString()).body().get("message")
                .asString()).isEqualTo("Invalid manager PIN");
        payOut.put("managerPin", "5555");
        Res approved = keyed(cashier, "POST", "/cash-sessions/movement", payOut, UUID.randomUUID().toString());
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(200);
        assertThat(call("GET", "/cash-sessions/" + sessionId + "/expected", null, cashier).body().get("expectedCash").asString())
                .isEqualTo("55000");
        JsonNode movements = call("GET", "/cash-sessions/" + sessionId + "/movements", null, cashier).body();
        assertThat(movements.get("movements")).hasSize(2);
        assertThat(movements.get("movements").get(1).get("runningTotal").asString()).isEqualTo("55000");
        assertThat(call("GET", "/cash-sessions/open?registerId=" + registerId, null, cashier).body().get("id").asString())
                .isEqualTo(sessionId);

        Res noReason = keyed(cashier, "POST", "/cash-sessions/close", Map.of("sessionId", sessionId, "closingCounted", 54000),
                UUID.randomUUID().toString());
        assertThat(noReason.status()).as(noReason.raw().body()).isEqualTo(400);
        assertThat(noReason.body().get("code").asString()).isEqualTo("CASH_VARIANCE_REASON_REQUIRED");
        Res closed = keyed(cashier, "POST", "/cash-sessions/close", Map.of("sessionId", sessionId, "closingCounted", 54000,
                "varianceReason", "Miscounted change"), UUID.randomUUID().toString());
        assertThat(closed.status()).as(closed.raw().body()).isEqualTo(200);
        assertThat(closed.body().get("status").asString()).isEqualTo("closed");
        assertThat(closed.body().get("varianceStatus").asString()).isEqualTo("pending_review");
        assertThat(new BigDecimal(closed.body().get("closingDifference").asString())).isEqualByComparingTo("-1000");
        assertThat(ledger(drawer)).isEqualByComparingTo("54000");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PosReportSnapshot\" WHERE \"cashSessionId\" = ? AND kind = 'z'")
                .param(sessionId).query(Long.class).single()).isEqualTo(1);

        Res own = keyed(cashier, "PATCH", "/cash-sessions/" + sessionId + "/reconcile", Map.of(), null);
        assertThat(own.status()).isEqualTo(403);
        Res pending = keyed(manager, "PATCH", "/cash-sessions/" + sessionId + "/reconcile", Map.of(), null);
        assertThat(pending.body().get("message").asString())
                .isEqualTo("Session has an unresolved variance. Approve or explain the variance before reconciling.");
        Res review = call("PATCH", "/cash-sessions/" + sessionId + "/variance", Map.of("reason", "Counted twice", "status", "approved"),
                manager);
        assertThat(review.status()).as(review.raw().body()).isEqualTo(200);
        assertThat(review.body().get("varianceReason").asString()).contains("Miscounted change | Review (");

        Res banking = keyed(manager, "POST", "/cash-sessions/" + sessionId + "/banking", Map.of("destinationAccountId",
                accountId("1200"), "amount", 20000, "bankName", "Stanbic"), UUID.randomUUID().toString());
        assertThat(banking.status()).as(banking.raw().body()).isEqualTo(200);
        assertThat(banking.body().has("journalEntryId")).isTrue();
        assertThat(ledger(drawer)).isEqualByComparingTo("34000");

        Res reconciled = keyed(manager, "PATCH", "/cash-sessions/" + sessionId + "/reconcile", Map.of("notes", "ok"), null);
        assertThat(reconciled.status()).as(reconciled.raw().body()).isEqualTo(200);
        assertThat(reconciled.body().get("status").asString()).isEqualTo("reconciled");

        JsonNode history = ok(200, "GET", "/cash-sessions/history?registerId=" + registerId, null);
        assertThat(history.get("data").get(0).get("movementCount").asInt()).isEqualTo(2);
        JsonNode daily = ok(200, "GET", "/cash-sessions/report/daily?date=" + LocalDate.now(ZoneOffset.UTC), null);
        assertThat(daily.has("totals")).isTrue();
        JsonNode one = ok(200, "GET", "/cash-sessions/" + sessionId, null);
        assertThat(one.get("cashRegister").get("code").asString()).isEqualTo(code);

        BigDecimal debits = jdbc.sql("SELECT coalesce(sum(\"baseDebit\"), 0) - coalesce(sum(\"baseCredit\"), 0) FROM \"JournalLine\""
                + " WHERE \"organizationId\" = ?").param(demoOrg).query(BigDecimal.class).single();
        assertThat(debits).isEqualByComparingTo("0");
    }
}
