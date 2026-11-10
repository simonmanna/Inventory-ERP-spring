package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Post-parity POS hardening (owner-approved 2026-11-10, docs/spec/pos.md "Deviations"): PIN lockouts that hold under
 * parallel guesses and survive a rolled-back business transaction, the approved opening shortage, the race-free
 * original receipt, the report range cap and business-day bucketing.
 */
class PosHardeningIT extends ApiTestSupport {

    String role;
    String productId;
    String registerId;
    String sessionId;

    Res keyed(String token, String method, String path, Object body) {
        return call(method, path, body, Map.of("Authorization", "Bearer " + token, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    JsonNode money(String token, String method, String path, Object body) {
        Res r = keyed(token, method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    /** A new user with the admin's role and an override PIN; returns {id, bearer token}. */
    String[] staff(String prefix, String pin) {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String id = createUser(demoOrg, email, "Staff@12345", role);
        String token = login("DEMO", email, "Staff@12345");
        if (pin != null) {
            assertThat(call("POST", "/pos/override/pin", Map.of("pin", pin), token).status()).isEqualTo(201);
        }
        return new String[] {id, token};
    }

    String sale() {
        return money(adminToken, "POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(Map.of(
                "productId", productId, "description", "Tea", "quantity", 1, "unitPrice", 1000)),
                "tenders", List.of(Map.of("method", "cash", "amount", 1000)))).get("invoiceId").asString();
    }

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    @BeforeAll
    void fixtures() {
        String adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id WHERE ur.\"B\" = ? LIMIT 1")
                .param(adminId).query(String.class).single();
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = ok(201, "POST", "/products", Map.of("code", "HRD-" + suffix, "name", "Tea " + suffix,
                "productType", "service", "salesPrice", 1000)).get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "HREG-" + suffix, "name", "Hardening till"))
                .get("id").asString();
        sessionId = money(adminToken, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId,
                "openingFloat", 0)).get("id").asString();
    }

    @Test
    @DisplayName("PIN login: 15 parallel wrong PINs allow exactly 10 compares; back-office login untouched; unlock clears")
    void parallelPinGuessesLockExactly() throws Exception {
        String[] target = staff("pin", "2468");
        String[] terminal = staff("term", null);
        List<Res> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(15)) {
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                futures.add(pool.submit(() -> call("POST", "/pos/auth/pin-login",
                        Map.of("userId", target[0], "pin", "1357"), terminal[1])));
            }
            for (Future<Res> f : futures) {
                results.add(f.get());
            }
        }
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(401));
        long invalid = results.stream().filter(r -> "Invalid PIN".equals(r.body().get("message").asString())).count();
        long locked = results.stream().filter(r -> r.body().get("message").asString().startsWith("Account locked")).count();
        assertThat(invalid).isEqualTo(10);
        assertThat(locked).isEqualTo(5);

        Res correctWhileLocked = call("POST", "/pos/auth/pin-login", Map.of("userId", target[0], "pin", "2468"), terminal[1]);
        assertThat(correctWhileLocked.status()).isEqualTo(401);
        assertThat(correctWhileLocked.body().get("message").asString()).startsWith("Account locked. Try again in ");
        // Wrong PINs no longer lock the user's back-office password login.
        assertThat(jdbc.sql("SELECT \"lockedUntil\" IS NULL AND \"failedLoginCount\" = 0 FROM \"User\" WHERE id = ?")
                .param(target[0]).query(Boolean.class).single()).isTrue();

        assertThat(as("POST", "/users/" + target[0] + "/unlock", null).status()).isIn(200, 201);
        JsonNode login = ok(200, "POST", "/pos/auth/pin-login", Map.of("userId", target[0], "pin", "2468"));
        assertThat(login.get("posToken").asString()).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM \"CredentialThrottle\" WHERE subject = ?"
                + " AND (failures <> 0 OR \"lockedUntil\" IS NOT NULL)").param(target[0]).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("void with a manager PIN: failures survive the rolled-back void and lock the manager after 5")
    void voidPinLockoutSurvivesRollback() {
        String[] manager = staff("mgr", "9753");
        try {
            String invoiceId = sale();
            Map<String, Object> body = new LinkedHashMap<>(Map.of("reason", "Rang in error", "overrideById", manager[0],
                    "overridePin", "1111", "stockDisposition", "no_return", "cashSessionId", sessionId));
            for (int i = 0; i < 5; i++) {
                Res wrong = keyed(adminToken, "POST", "/pos/sales/" + invoiceId + "/void", body);
                assertThat(wrong.status()).as(wrong.raw().body()).isEqualTo(401);
                assertThat(wrong.body().get("message").asString()).isEqualTo("Invalid override PIN");
            }
            body.put("overridePin", "9753");
            Res locked = keyed(adminToken, "POST", "/pos/sales/" + invoiceId + "/void", body);
            assertThat(locked.status()).isEqualTo(401);
            assertThat(locked.body().get("message").asString()).isEqualTo(
                    "Manager overrides are temporarily locked after repeated failed attempts. Try again in a few minutes.");
            assertThat(jdbc.sql("SELECT count(*) FROM \"PosRefund\" WHERE \"invoiceId\" = ?").param(invoiceId)
                    .query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DELETE FROM \"CredentialThrottle\" WHERE subject = ?").param(manager[0]).update();
        }
    }

    @Test
    @DisplayName("opening below the drawer ledger: needs a reason, then a variance approver's PIN")
    void openingShortageNeedsApproval() {
        String cashier = login("DEMO", "sarah@demo.test", "Demo@123");
        Res fund = keyed(adminToken, "POST", "/accounts/cash-flow/deposit", Map.of("accountId", accountId("1200"),
                "counterpartAccountId", accountId("3100"), "operationType", "owner_contribution", "amount", 20000,
                "description", "Float funding"));
        assertThat(fund.status()).as(fund.raw().body()).isEqualTo(200);
        String code = "HSH-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode register = ok(201, "POST", "/cash-registers", Map.of("code", code, "name", "Shortage till"));
        String reg = register.get("id").asString();
        String drawer = register.get("defaultAccountId").asString();
        try {
            String first = money(cashier, "POST", "/cash-sessions/open", Map.of("cashRegisterId", reg, "openingFloat", 10000,
                    "openingSourceAccountId", accountId("1200"), "notes", "Morning float")).get("id").asString();
            money(cashier, "POST", "/cash-sessions/close", Map.of("sessionId", first, "closingCounted", 10000));

            Map<String, Object> open = new LinkedHashMap<>(Map.of("cashRegisterId", reg, "openingFloat", 4000,
                    "autoAdjust", true));
            Res noReason = keyed(cashier, "POST", "/cash-sessions/open", open);
            assertThat(noReason.status()).isEqualTo(400);
            assertThat(noReason.body().get("code").asString()).isEqualTo("OPENING_SHORTAGE_REASON_REQUIRED");
            open.put("adjustmentReason", "Owner took 6,000 for change overnight");
            Res noManager = keyed(cashier, "POST", "/cash-sessions/open", open);
            assertThat(noManager.status()).isEqualTo(400);
            assertThat(noManager.body().get("code").asString()).isEqualTo("MANAGER_APPROVAL_REQUIRED");
            assertThat(new BigDecimal(jdbc.sql("SELECT coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0)"
                    + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                    + " WHERE l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed')").param(drawer)
                    .query(BigDecimal.class).single().toPlainString())).isEqualByComparingTo("10000");

            open.put("approverEmail", "manager@demo.test");
            open.put("managerPin", "5555");
            JsonNode opened = money(cashier, "POST", "/cash-sessions/open", open);
            assertThat(opened.get("status").asString()).isEqualTo("open");
            assertThat(jdbc.sql("SELECT coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) FROM \"JournalLine\" l"
                    + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ?"
                    + " AND e.status::text IN ('posted', 'reversed')").param(drawer).query(BigDecimal.class).single())
                    .isEqualByComparingTo("4000");
            assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND description LIKE ?")
                    .params(demoOrg, "%Owner took 6,000 for change overnight%").query(Long.class).single()).isEqualTo(1);
        } finally {
            forceCloseSessions(List.of(reg));
        }
    }

    @Test
    @DisplayName("two simultaneous original receipt prints: exactly one prints, the other is told to reprint")
    void originalReceiptPrintsOnce() throws Exception {
        String invoiceId = sale();
        jdbc.sql("UPDATE \"Invoice\" SET \"receiptPrintCount\" = 0 WHERE id = ?").param(invoiceId).update();
        List<Res> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> keyed(adminToken, "POST", "/pos/receipts/" + invoiceId + "/print", Map.of())));
            }
            for (Future<Res> f : futures) {
                results.add(f.get());
            }
        }
        assertThat(results.stream().map(Res::status).sorted().toList()).containsExactly(201, 403);
        assertThat(jdbc.sql("SELECT \"receiptPrintCount\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("reports: a range over 366 days is refused; a late-shift sale is bucketed on its business day")
    void reportRangeAndBusinessDay() {
        assertThat(as("GET", "/pos/reports/sales-summary?fromDate=2024-01-01&toDate=2024-12-31&groupBy=day", null)
                .status()).isEqualTo(200);
        Res tooLong = as("GET", "/pos/reports/sales-summary?fromDate=2024-01-01&toDate=2025-01-01&groupBy=day", null);
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body().get("message").asString()).isEqualTo("Report range cannot exceed 366 days");

        LocalDate day = LocalDate.of(2001, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(6000));
        String invoiceId = sale();
        jdbc.sql("UPDATE \"Invoice\" SET \"businessDate\" = ?, \"issueDate\" = ?::timestamp WHERE id = ?")
                .params(java.sql.Date.valueOf(day), day.plusDays(1) + " 00:30:00", invoiceId).update();
        JsonNode summary = ok(200, "GET", "/pos/reports/sales-summary?fromDate=" + day + "&toDate=" + day
                + "&groupBy=day", null);
        assertThat(summary.get("periods")).hasSize(1);
        assertThat(summary.get("periods").get(0).get("periodKey").asString()).isEqualTo(day.toString());
        assertThat(summary.get("periods").get(0).get("orders").asLong()).isGreaterThanOrEqualTo(1);
    }
}
