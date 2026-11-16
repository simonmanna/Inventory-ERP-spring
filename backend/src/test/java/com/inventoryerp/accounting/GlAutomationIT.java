package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.DeferralService;
import com.inventoryerp.accounting.application.RecurringJournalService;
import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.web.BankMatchingController;
import com.inventoryerp.accounting.web.DeferralController;
import com.inventoryerp.accounting.web.RecurringEntryController;
import com.inventoryerp.fixedasset.web.AssetImpairmentController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S073 GL automation: recurring journal entries, deferral schedules, bank matching rules with grouped / partial
 * matches, and fixed-asset impairment. Each ledger scenario runs in a fresh organization.
 */
class GlAutomationIT extends AccountingFixtures {

    @Autowired
    RecurringJournalService recurring;

    @Autowired
    DeferralService deferrals;

    // ------------------------------------------------------------------ helpers

    Map<String, Object> template(Books b, String frequency, LocalDate start, String mode, int amount) {
        String expense = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        String bank = account(b.org(), mappedCode(b.org(), "default_bank"));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "Rent " + UUID.randomUUID().toString().substring(0, 6));
        body.put("frequency", frequency);
        body.put("startDate", start.toString());
        body.put("mode", mode);
        body.put("lines", List.of(Map.of("accountId", expense, "debit", amount, "description", "Rent"),
                Map.of("accountId", bank, "credit", amount, "description", "Rent paid")));
        return body;
    }

    long recurringEntries(String org, String templateId, String status) {
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'recurring_entry' AND \"sourceId\" = ? AND status::text = ?").params(org, templateId, status)
                .query(Long.class).single();
    }

    BigDecimal net(String org, String accountId, String sourceType) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ?"
                + " AND e.status::text IN ('posted', 'reversed') AND e.\"sourceType\" = ?").params(org, accountId,
                sourceType)
                .query(BigDecimal.class).single();
    }

    String secondAdmin(Books b) {
        String role = jdbc.sql("SELECT ur.\"A\" FROM \"_UserRoles\" ur WHERE ur.\"B\" = ? LIMIT 1").param(b.ownerId())
                .query(String.class).single();
        String email = "checker-" + UUID.randomUUID().toString().substring(0, 8) + "@" + b.code() + ".test";
        createUser(b.org(), email, "Checker@12345", role);
        return login(b.code(), email, "Checker@12345");
    }

    // ------------------------------------------------------------------ recurring entries

    @Test
    @DisplayName("[ADR-S073] recurring: the daily job catches up due occurrences once; a second run posts nothing")
    void recurringJobIsIdempotent() {
        Books b = freshBooks();
        monthlyPeriods(b, lastAndThisMonth());
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(2);
        long expected = java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1;
        String id = okAs(b, 201, "POST", "/recurring-entries", template(b, "daily", start, "post", 75)).get("id")
                .asString();
        Map<String, Object> first = inTenant(b.org(), null, () -> recurring.runDue(today));
        assertThat(first.get("generated")).isEqualTo((int) expected);
        Map<String, Object> second = inTenant(b.org(), null, () -> recurring.runDue(today));
        assertThat(second.get("generated")).as("second job run").isEqualTo(0);
        assertThat(recurringEntries(b.org(), id, "posted")).isEqualTo(expected);
        JsonNode t = okAs(b, 200, "GET", "/recurring-entries/" + id, null);
        assertThat(t.get("nextRunDate").asString()).isEqualTo(today.plusDays(1).toString());
        assertThat(t.get("runCount").asInt()).isEqualTo((int) expected);
        // A manual run of an occurrence the job already made replays it.
        JsonNode replay = okAs(b, 200, "POST", "/recurring-entries/" + id + "/run", Map.of("date", today.toString()));
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(recurringEntries(b.org(), id, "posted")).isEqualTo(expected);
        assertThat(jdbc.sql("SELECT count(DISTINCT \"postingKey\") FROM \"JournalEntry\" WHERE \"sourceId\" = ?")
                .param(id).query(Long.class).single()).isEqualTo(expected);
        assertThat(okAs(b, 200, "GET", "/recurring-entries/" + id + "/runs", null)).hasSize((int) expected);
    }

    @Test
    @DisplayName("[ADR-S073] recurring: draft mode stages a draft the template author cannot approve")
    void recurringDraftIsMakerChecker() {
        Books b = freshBooks();
        String id = okAs(b, 201, "POST", "/recurring-entries", template(b, "monthly", LocalDate.now(), "draft", 40))
                .get("id").asString();
        JsonNode run = okAs(b, 200, "POST", "/recurring-entries/" + id + "/run", Map.of());
        assertThat(run.get("status").asString()).isEqualTo("draft");
        String entry = run.get("journalEntryId").asString();
        assertThat(recurringEntries(b.org(), id, "draft")).isEqualTo(1);
        Res self = as(b, "POST", "/journal-entries/" + entry + "/post", null);
        assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
        Res checker = call("POST", "/journal-entries/" + entry + "/post", null, secondAdmin(b));
        assertThat(checker.status()).as(checker.raw().body()).isLessThan(300);
        assertThat(recurringEntries(b.org(), id, "posted")).isEqualTo(1);
        JsonNode t = okAs(b, 200, "GET", "/recurring-entries/" + id, null);
        assertThat(t.get("nextRunDate").asString()).isEqualTo(LocalDate.now().plusMonths(1).toString());
    }

    @Test
    @DisplayName("[ADR-S073] recurring: a closed period refuses the occurrence; the job records it and does not advance")
    void recurringRespectsClosedPeriods() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        monthlyPeriods(b, months);
        LocalDate lastMonthDay = months[0].plusDays(9);
        closePeriod(b, lastMonthDay.toString());
        String id = okAs(b, 201, "POST", "/recurring-entries", template(b, "monthly", lastMonthDay, "post", 30))
                .get("id").asString();
        Res manual = as(b, "POST", "/recurring-entries/" + id + "/run", Map.of("date", lastMonthDay.toString()));
        assertThat(manual.status()).isEqualTo(400);
        assertThat(manual.body().get("message").asString()).contains("closed");
        Map<String, Object> job = inTenant(b.org(), null, () -> recurring.runDue(LocalDate.now()));
        assertThat(job.get("failed")).isEqualTo(1);
        assertThat(recurringEntries(b.org(), id, "posted")).isZero();
        JsonNode t = okAs(b, 200, "GET", "/recurring-entries/" + id, null);
        assertThat(t.get("nextRunDate").asString()).isEqualTo(lastMonthDay.toString());
        JsonNode runs = okAs(b, 200, "GET", "/recurring-entries/" + id + "/runs", null);
        assertThat(runs.get(0).get("status").asString()).isEqualTo("failed");
        // Paused templates are skipped; resuming skips what was missed.
        okAs(b, 200, "POST", "/recurring-entries/" + id + "/pause", null);
        assertThat(inTenant(b.org(), null, () -> recurring.runDue(LocalDate.now())).get("templates")).isEqualTo(0);
        JsonNode resumed = okAs(b, 200, "POST", "/recurring-entries/" + id + "/resume", null);
        assertThat(LocalDate.parse(resumed.get("nextRunDate").asString())).isAfterOrEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("[ADR-S073] recurring: unbalanced or foreign-account templates are refused")
    void recurringValidation() {
        Books b = freshBooks();
        Map<String, Object> body = template(b, "monthly", LocalDate.now(), "post", 10);
        body.put("lines", List.of(Map.of("accountId", account(b.org(), mappedCode(b.org(), "default_bank")),
                "debit", 10), Map.of("accountId", account(b.org(), mappedCode(b.org(), "default_cash")), "credit", 9)));
        Res r = as(b, "POST", "/recurring-entries", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).startsWith("Unbalanced entry");
        Map<String, Object> foreign = template(b, "monthly", LocalDate.now(), "post", 10);
        foreign.put("lines", List.of(Map.of("accountId", mapped(demoOrg, "default_bank"), "debit", 10),
                Map.of("accountId", account(b.org(), mappedCode(b.org(), "default_cash")), "credit", 10)));
        assertThat(as(b, "POST", "/recurring-entries", foreign).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ deferrals

    @Test
    @DisplayName("[ADR-S073] deferral from an invoice line: reclass, monthly releases once, report ties to the GL")
    void deferralFromLine() {
        Books b = freshBooks();
        LocalDate m0 = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        monthlyPeriods(b, m0, m0.plusMonths(1), m0.plusMonths(2));
        String revenueCode = accountCodeOfSection(b.org(), "revenue");
        String revenue = account(b.org(), revenueCode);
        String entry = postJe(b, m0.toString(), List.of(JeLine.dr(mappedCode(b.org(), "default_bank"), 1200),
                JeLine.cr(revenueCode, 1200)));
        String lineId = jdbc.sql("SELECT id FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND credit > 0")
                .param(entry).query(String.class).single();
        JsonNode s = okAs(b, 201, "POST", "/deferrals/from-journal-line", Map.of("journalLineId", lineId,
                "startDate", m0.toString(), "months", 12));
        String id = s.get("id").asString();
        assertThat(s.get("kind").asString()).isEqualTo("revenue");
        assertThat(s.get("releases")).hasSize(12);
        assertThat(net(b.org(), revenue, "deferral")).as("revenue moved out").isEqualByComparingTo("1200");
        Res again = as(b, "POST", "/deferrals/from-journal-line", Map.of("journalLineId", lineId, "startDate",
                m0.toString(), "months", 12));
        assertThat(again.status()).isEqualTo(409);

        String asOf = m0.plusMonths(2).minusDays(1).toString();
        JsonNode run = okAs(b, 200, "POST", "/deferrals/run", Map.of("asOf", asOf));
        assertThat(run.get("released").asInt()).isEqualTo(2);
        assertThat(okAs(b, 200, "POST", "/deferrals/run", Map.of("asOf", asOf)).get("released").asInt())
                .as("second run").isZero();
        assertThat(inTenant(b.org(), null, () -> deferrals.releaseDue(LocalDate.parse(asOf))).get("released"))
                .as("job run").isEqualTo(0);
        assertThat(net(b.org(), revenue, "deferral_release")).isEqualByComparingTo("-200");

        JsonNode report = okAs(b, 200, "GET", "/deferrals/report?asOf=" + asOf, null);
        JsonNode acc = report.get("accounts").get(0);
        assertThat(new BigDecimal(acc.get("scheduleRemaining").asString())).isEqualByComparingTo("1000");
        assertThat(new BigDecimal(acc.get("difference").asString())).as("register = GL").isEqualByComparingTo("0");

        JsonNode cancelled = okAs(b, 200, "POST", "/deferrals/" + id + "/cancel", Map.of("reason", "contract ended"));
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(okAs(b, 200, "POST", "/deferrals/run", Map.of("asOf", LocalDate.now().toString()))
                .get("released").asInt()).as("nothing after cancel").isZero();
    }

    @Test
    @DisplayName("[ADR-S073] prepaid expense schedule: daily method, closed period refused")
    void prepaidScheduleAndClosedPeriod() {
        Books b = freshBooks();
        LocalDate[] months = lastAndThisMonth();
        monthlyPeriods(b, months);
        String expense = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        closePeriod(b, months[0].plusDays(3).toString());
        JsonNode s = okAs(b, 201, "POST", "/deferrals", Map.of("name", "Insurance", "kind", "expense",
                "targetAccountId", expense, "totalAmount", 600, "startDate", months[0].toString(), "months", 2,
                "method", "daily"));
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode r : s.get("releases")) {
            total = total.add(new BigDecimal(r.get("amount").asString()));
        }
        assertThat(total).isEqualByComparingTo("600");
        Res refused = as(b, "POST", "/deferrals/" + s.get("id").asString() + "/release",
                Map.of("asOf", months[0].plusMonths(1).minusDays(1).toString()));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("closed");
        Res wrongTarget = as(b, "POST", "/deferrals", Map.of("name", "Bad", "kind", "expense", "targetAccountId",
                account(b.org(), mappedCode(b.org(), "default_bank")), "totalAmount", 10, "startDate",
                months[1].toString(), "months", 1));
        assertThat(wrongTarget.status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ bank rules and grouped matches

    String payment(String org, String partner, String account, String direction, int amount) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                        + " \"paymentDate\", \"paymentMethod\", \"accountId\", amount, status, \"updatedAt\") VALUES (?, ?, ?,"
                        + " ?::\"PaymentDirection\", ?, now(), 'bank', ?, ?, 'posted'::\"DocumentStatus\", now())")
                .params(id, org, "GL-" + id.substring(0, 12), direction, partner, account, amount).update();
        return id;
    }

    String lineId(String org, String account, String ref) {
        return jdbc.sql("SELECT id FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"bankAccountId\" = ? AND"
                + " \"externalRef\" = ?").params(org, account, ref).query(String.class).single();
    }

    String lineStatus(String id) {
        return jdbc.sql("SELECT status FROM \"BankStatementLine\" WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    @DisplayName("[ADR-S073] bank rules: a charge rule posts the fee on import; a payment rule matches; undo reverses")
    void bankRules() {
        Books b = freshBooks();
        String bank = account(b.org(), mappedCode(b.org(), "default_bank"));
        String fees = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        String partner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Rule customer', true, now())").params(partner, b.org(), "RC-" + partner.substring(0, 6))
                .update();
        String pay = payment(b.org(), partner, bank, "inbound", 410);
        okAs(b, 201, "POST", "/bank-reconciliation/rules", Map.of("bankAccountId", bank, "name", "Bank charges",
                "pattern", "charge", "direction", "outbound", "maxAmount", 100, "action", "post_entry",
                "counterAccountId", fees));
        okAs(b, 201, "POST", "/bank-reconciliation/rules", Map.of("bankAccountId", bank, "name", "Invoice transfers",
                "matchType", "regex", "pattern", "INV-\\d+", "direction", "inbound", "action", "match_payment",
                "priority", 5));
        Res bad = as(b, "POST", "/bank-reconciliation/rules", Map.of("bankAccountId", bank, "name", "Broken",
                "matchType", "regex", "pattern", "(", "action", "match_payment"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(okAs(b, 200, "GET", "/bank-reconciliation/rules?bankAccountId=" + bank, null)).hasSize(2);

        String day = LocalDate.now().toString();
        okAs(b, 200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", bank, "currencyCode", "USD",
                "lines", List.of(Map.of("postedAt", day, "externalRef", "FEE-1", "description", "Monthly CHARGE",
                                "amount", -25),
                        Map.of("postedAt", day, "externalRef", "TR-1", "description", "Transfer INV-77", "amount", 410),
                        Map.of("postedAt", day, "externalRef", "BIG", "description", "Large charge", "amount", -500))));
        String fee = lineId(b.org(), bank, "FEE-1");
        assertThat(lineStatus(fee)).isEqualTo("matched");
        assertThat(net(b.org(), fees, "bank_statement_line")).isEqualByComparingTo("25");
        assertThat(net(b.org(), bank, "bank_statement_line")).isEqualByComparingTo("-25");
        assertThat(jdbc.sql("SELECT \"matchedPaymentId\" FROM \"BankStatementLine\" WHERE id = ?")
                .param(lineId(b.org(), bank, "TR-1")).query(String.class).single()).isEqualTo(pay);
        assertThat(lineStatus(lineId(b.org(), bank, "BIG"))).as("above the rule's max").isEqualTo("unmatched");
        JsonNode again = okAs(b, 200, "POST", "/bank-reconciliation/rules/apply?bankAccountId=" + bank, null);
        assertThat(again.get("posted").asInt()).isZero();

        // Unmatching a rule-posted line reverses its entry, so the ledger no longer holds the charge.
        call("POST", "/bank-reconciliation/unmatch/" + fee, null, b.token());
        assertThat(lineStatus(fee)).isEqualTo("unmatched");
        assertThat(net(b.org(), bank, "bank_statement_line").add(net(b.org(), bank, "reversal")))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("[ADR-S073] grouped and partial matches; outstanding nets allocations; a locked statement refuses")
    void groupedAndPartialMatches() {
        String account = UUID.randomUUID().toString();
        String partner = demoPartner("GL grouped", false);
        String p100 = payment(demoOrg, partner, account, "inbound", 100);
        String p200 = payment(demoOrg, partner, account, "inbound", 200);
        String p500 = payment(demoOrg, partner, account, "inbound", 500);
        String p80 = payment(demoOrg, partner, account, "inbound", 80);
        String out = payment(demoOrg, partner, account, "outbound", 300);
        String day = LocalDate.now().toString();
        ok(200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", account, "currencyCode", "USD",
                "lines", List.of(Map.of("postedAt", day, "externalRef", "G300", "description", "Two receipts", "amount",
                                300),
                        Map.of("postedAt", day, "externalRef", "S200", "description", "Part 1", "amount", 200),
                        Map.of("postedAt", day, "externalRef", "S300", "description", "Part 2", "amount", 300),
                        Map.of("postedAt", day, "externalRef", "P50", "description", "Partial", "amount", 50))));
        String g300 = lineId(demoOrg, account, "G300");
        JsonNode one = ok(200, "POST", "/bank-reconciliation/lines/" + g300 + "/allocate", Map.of("allocations",
                List.of(Map.of("paymentId", p100), Map.of("paymentId", p200))));
        assertThat(one.get("status").asString()).isEqualTo("matched");
        Res wrongDirection = as("POST", "/bank-reconciliation/lines/" + lineId(demoOrg, account, "P50") + "/allocate",
                Map.of("allocations", List.of(Map.of("paymentId", out))));
        assertThat(wrongDirection.status()).isEqualTo(400);
        JsonNode many = ok(200, "POST", "/bank-reconciliation/payments/" + p500 + "/allocate", Map.of("lineIds",
                List.of(lineId(demoOrg, account, "S200"), lineId(demoOrg, account, "S300"))));
        assertThat(many.get("lines")).allSatisfy(l -> assertThat(l.get("status").asString()).isEqualTo("matched"));
        assertThat(many.get("openAmount").asString()).isEqualTo("0");
        Res taken = as("POST", "/bank-reconciliation/lines/" + lineId(demoOrg, account, "P50") + "/allocate",
                Map.of("allocations", List.of(Map.of("paymentId", p100))));
        assertThat(taken.status()).isEqualTo(409);
        // Partial: the 80 payment covers the 50 line, 30 stays outstanding.
        JsonNode partial = ok(200, "POST", "/bank-reconciliation/lines/" + lineId(demoOrg, account, "P50")
                + "/allocate", Map.of("allocations", List.of(Map.of("paymentId", p80, "amount", 50))));
        assertThat(partial.get("status").asString()).isEqualTo("matched");
        JsonNode summary = ok(200, "GET", "/bank-reconciliation/summary?bankAccountId=" + account, null);
        assertThat(new BigDecimal(summary.get("outstandingInbound").asString())).isEqualByComparingTo("30");
        JsonNode statement = ok(200, "GET", "/bank-reconciliation/statement?bankAccountId=" + account + "&asOf=" + day
                + "&closingBalance=850", null);
        // Outstanding book items net the allocations: 30 left on the 80 receipt, the 300 outbound payment unmatched.
        assertThat(new BigDecimal(statement.get("outstandingBook").asString())).isEqualByComparingTo("-270");
        assertThat(new BigDecimal(statement.get("unrecordedStatement").asString())).isEqualByComparingTo("0");

        // Lock rule: a line on or before a locked statement cannot be allocated.
        String locked = UUID.randomUUID().toString();
        String lp = payment(demoOrg, partner, locked, "inbound", 70);
        String old = LocalDate.now().minusDays(5).toString();
        ok(200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", locked, "currencyCode", "USD",
                "lines", List.of(Map.of("postedAt", old, "externalRef", "L70", "description", "Old", "amount", 70))));
        Res lock = as("POST", "/bank-reconciliation/statement", Map.of("bankAccountId", locked, "asOf",
                LocalDate.now().minusDays(3).toString(), "closingBalance", 70));
        assertThat(lock.status()).as(lock.raw().body()).isLessThan(300);
        Res refused = as("POST", "/bank-reconciliation/lines/" + lineId(demoOrg, locked, "L70") + "/allocate",
                Map.of("allocations", List.of(Map.of("paymentId", lp))));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("locked through");
    }

    // ------------------------------------------------------------------ impairment

    @Test
    @DisplayName("[ADR-S073] impairment, re-based depreciation, capped reversal, disposal clears accumulated impairment")
    void impairmentLifecycle() {
        String cat = ok(201, "POST", "/asset-categories", Map.of("name", "IMP " + UUID.randomUUID().toString()
                .substring(0, 6), "depreciationMethod", "straight_line")).get("id").asString();
        String id = ok(201, "POST", "/fixed-assets", Map.of("name", "Impaired crusher " + UUID.randomUUID().toString()
                .substring(0, 6), "purchaseCost", 1200, "salvageValue", 0, "usefulLife", 12, "categoryId", cat,
                "purchaseDate", LocalDate.now().toString())).get("id").asString();
        ok(201, "POST", "/fixed-assets/" + id + "/acquisition", Map.of("vendor", "IMP vendor"));
        for (String period : List.of("2026-01", "2026-02")) {
            ok(201, "POST", "/fixed-assets/" + id + "/depreciation/run", Map.of("period", period, "assetId", id,
                    "postEntries", true));
        }
        Res notBelow = as("POST", "/fixed-assets/" + id + "/impairments", Map.of("recoverableAmount", 1000));
        assertThat(notBelow.status()).isEqualTo(400);
        JsonNode imp = ok(201, "POST", "/fixed-assets/" + id + "/impairments", Map.of("recoverableAmount", 600,
                "reason", "Market drop"));
        assertThat(imp.get("amount").asString()).isEqualTo("400");
        assertThat(imp.get("remainingLifeMonths").asInt()).isEqualTo(10);
        assertThat(net(demoOrg, mapped(demoOrg, "asset_impairment_loss"), "asset_impairment").signum()).isPositive();

        ok(201, "POST", "/fixed-assets/" + id + "/depreciation/run", Map.of("period", "2026-03", "assetId", id,
                "postEntries", true));
        BigDecimal march = jdbc.sql("SELECT \"depreciationAmount\" FROM \"AssetDepreciation\" WHERE \"assetId\" = ? AND"
                + " period = '2026-03'").param(id).query(BigDecimal.class).single();
        assertThat(march).as("600 over the remaining 10 months").isEqualByComparingTo("60");

        JsonNode rev = ok(201, "POST", "/fixed-assets/" + id + "/impairments/reversal", Map.of("recoverableAmount",
                1000));
        // Without impairment the carrying amount would be 1200 − 3 × 100 = 900; carrying 540 → reversal capped at 360.
        assertThat(rev.get("amount").asString()).isEqualTo("360");
        assertThat(rev.get("carryingAfter").asString()).isEqualTo("900");
        JsonNode summary = ok(200, "GET", "/fixed-assets/" + id + "/impairments", null);
        assertThat(summary.get("accumulatedImpairment").asString()).isEqualTo("40");
        assertThat(summary.get("impairments")).hasSize(2);

        ok(201, "POST", "/fixed-assets/" + id + "/disposal", Map.of("disposalMethod", "sold", "disposalValue", 950));
        List<String> entries = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND (\"sourceId\" ="
                + " ? OR \"sourceId\" IN (SELECT id FROM \"AssetDepreciation\" WHERE \"assetId\" = ?))")
                .params(demoOrg, id, id).query(String.class).list();
        assertThat(netOn(mapped(demoOrg, "accumulated_impairment"), entries)).as("impairment cleared")
                .isEqualByComparingTo("0");
        assertThat(netOn(mapped(demoOrg, "accumulated_depreciation"), entries)).isEqualByComparingTo("0");
        // NBV = 1200 − 260 − 40 = 900; proceeds 950 → gain 50 (credit).
        assertThat(netOn(mapped(demoOrg, "asset_gain_loss"), List.of(jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE"
                + " \"sourceType\" = 'asset_disposal' AND \"sourceId\" = ?").param(id).query(String.class).single())))
                .isEqualByComparingTo("-50");
        Res afterDisposal = as("POST", "/fixed-assets/" + id + "/impairments", Map.of("recoverableAmount", 1));
        assertThat(afterDisposal.status()).isEqualTo(400);
    }

    BigDecimal netOn(String accountId, List<String> entries) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = ANY(?::text[]) AND l.\"accountId\" = ?").params(entries.toArray(String[]::new),
                accountId).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ authorization and tenancy

    @Test
    @DisplayName("[ADR-S073] every new route answers 401 anonymous and 403 without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(RecurringEntryController.class, DeferralController.class,
                BankMatchingController.class, AssetImpairmentController.class)).isGreaterThanOrEqualTo(25);
    }

    @Test
    @DisplayName("[ADR-S073] another organization sees none of these records (404)")
    void tenantIsolation() {
        Books b = freshBooks();
        String template = okAs(b, 201, "POST", "/recurring-entries", template(b, "monthly", LocalDate.now(), "post", 5))
                .get("id").asString();
        String expense = account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"));
        String deferral = okAs(b, 201, "POST", "/deferrals", Map.of("name", "Iso", "kind", "expense",
                "targetAccountId", expense, "totalAmount", 10, "startDate", LocalDate.now().toString(), "months", 1))
                .get("id").asString();
        String rule = okAs(b, 201, "POST", "/bank-reconciliation/rules", Map.of("bankAccountId", "x", "name", "Iso",
                "action", "match_payment")).get("id").asString();
        assertThat(call("GET", "/recurring-entries/" + template, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/recurring-entries/" + template + "/run", Map.of(), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/deferrals/" + deferral, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/deferrals/" + deferral + "/cancel", Map.of(), otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/bank-reconciliation/rules/" + rule, Map.of("name", "x"), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/recurring-entries", null, otherToken).body().toString()).doesNotContain(template);
        String asset = ok(201, "POST", "/fixed-assets", Map.of("name", "Iso asset", "purchaseCost", 100,
                "salvageValue", 0, "usefulLife", 12, "purchaseDate", LocalDate.now().toString())).get("id").asString();
        assertThat(call("GET", "/fixed-assets/" + asset + "/impairments", null, otherToken).status()).isIn(403, 404);
    }
}
