package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Review R5 / R9: statement lines match movements already in the ledger without a Payment row (treasury transfers,
 * expenses, petty-cash top-ups, payroll), a post_entry rule never books such a movement a second time, and payments
 * compare in the bank account's currency (foreign payments into a functional account, withholding).
 */
class BankLedgerMatchIT extends AccountingFixtures {

    final String day = LocalDate.now().minusDays(1).toString();

    String bankCode(Books b) {
        return mappedCode(b.org(), "default_bank");
    }

    /** The bank account's line of an entry. */
    String bankLine(Books b, String entryId) {
        return jdbc.sql("SELECT id FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(entryId, account(b.org(), bankCode(b))).query(String.class).single();
    }

    String lineId(Books b, String ref) {
        return jdbc.sql("SELECT id FROM \"BankStatementLine\" WHERE \"organizationId\" = ? AND \"externalRef\" = ?")
                .params(b.org(), ref).query(String.class).single();
    }

    Map<String, Object> line(Books b, String ref) {
        return jdbc.sql("SELECT status, \"matchedPaymentId\", \"matchedJournalLineId\", \"journalEntryId\" FROM"
                + " \"BankStatementLine\" WHERE id = ?").param(lineId(b, ref)).query().singleRow();
    }

    void importLines(Books b, List<Map<String, Object>> lines) {
        okAs(b, 200, "POST", "/bank-reconciliation/import", Map.of("bankAccountId", account(b.org(), bankCode(b)),
                "currencyCode", baseCurrency(b.org()), "lines", lines));
    }

    Map<String, Object> st(String ref, String description, Object amount) {
        return Map.of("postedAt", day, "externalRef", ref, "description", description, "amount", amount);
    }

    JsonNode autoMatch(Books b) {
        return okAs(b, 200, "POST", "/bank-reconciliation/match?bankAccountId=" + account(b.org(), bankCode(b)),
                Map.of());
    }

    String partner(Books b) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\") VALUES"
                + " (?, ?, ?, 'R9 customer', true, now())").params(id, b.org(), "R9-" + id.substring(0, 6)).update();
        return id;
    }

    @Test
    @DisplayName("[R5] auto-match pairs lines with unreconciled GL lines on the bank; never twice; statement reconciles")
    void autoMatchJournalLines() {
        Books b = freshBooks();
        String expense = postJe(b, day, List.of(JeLine.dr(accountCodeOfCategory(b.org(), "operating_expense"), 80),
                JeLine.cr(bankCode(b), 80)));
        String transferIn = postJe(b, day, List.of(JeLine.dr(bankCode(b), 500), JeLine.cr("3100", 500)));
        importLines(b, List.of(st("EXP", "Card expense", -80), st("TRF", "Transfer in", 500)));

        JsonNode run = autoMatch(b);
        assertThat(run.get("matched").asInt()).isEqualTo(2);
        assertThat(line(b, "EXP").get("matchedJournalLineId")).isEqualTo(bankLine(b, expense));
        assertThat(line(b, "TRF").get("matchedJournalLineId")).isEqualTo(bankLine(b, transferIn));
        JsonNode listed = okAs(b, 200, "GET", "/bank-reconciliation/lines?bankAccountId=" + account(b.org(),
                bankCode(b)) + "&status=matched", null).get("data");
        assertThat(listed).allSatisfy(l -> assertThat(l.get("matchedEntryNumber").isNull()).isFalse());

        // GL-matched lines are in both the ledger and the statement: 420 closing reconciles with no adjustment.
        JsonNode s = okAs(b, 200, "GET", "/bank-reconciliation/statement?bankAccountId=" + account(b.org(),
                bankCode(b)) + "&asOf=" + day + "&closingBalance=420", null);
        assertThat(new BigDecimal(s.get("ledgerBalance").asString())).isEqualByComparingTo("420");
        assertThat(new BigDecimal(s.get("unrecordedStatement").asString())).isEqualByComparingTo("0");
        assertThat(s.get("reconciled").asBoolean()).isTrue();

        // a GL line matched in an earlier run is never taken again; unmatch frees it
        importLines(b, List.of(st("EXP2", "Card expense again", -80)));
        assertThat(autoMatch(b).get("matched").asInt()).isZero();
        assertThat(line(b, "EXP2").get("status")).isEqualTo("unmatched");
        assertThat(as(b, "POST", "/bank-reconciliation/unmatch/" + lineId(b, "EXP"), null).status()).isLessThan(300);
        assertThat(line(b, "EXP").get("matchedJournalLineId")).isNull();
        // two unmatched −80 lines, one free −80 GL line: exactly one of them takes it
        assertThat(autoMatch(b).get("matched").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("[R5] a post_entry rule matches a movement already journalled instead of posting it again")
    void postEntryRuleDoesNotDoubleBook() {
        Books b = freshBooks();
        String bank = account(b.org(), bankCode(b));
        okAs(b, 201, "POST", "/bank-reconciliation/rules", Map.of("bankAccountId", bank, "name", "Transfers out",
                "pattern", "transfer", "direction", "outbound", "action", "post_entry", "counterAccountId",
                account(b.org(), accountCodeOfCategory(b.org(), "operating_expense"))));
        // a petty-cash top-up already booked through treasury: Dr cash / Cr bank
        String topUp = postJe(b, day, List.of(JeLine.dr("1100", 300), JeLine.cr(bankCode(b), 300)));
        importLines(b, List.of(st("TOP", "Transfer to petty cash", -300), st("FEE", "Transfer fee", -7)));

        Map<String, Object> top = line(b, "TOP");
        assertThat(top.get("status")).isEqualTo("matched");
        assertThat(top.get("matchedJournalLineId")).isEqualTo(bankLine(b, topUp));
        assertThat(top.get("journalEntryId")).as("nothing posted for the top-up").isNull();
        // the fee has nothing behind it in the books, so the rule still posts it
        assertThat(line(b, "FEE").get("journalEntryId")).isNotNull();
        BigDecimal ruleBank = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM"
                + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND"
                + " e.\"sourceType\" = 'bank_statement_line'").param(bank).query(BigDecimal.class).single();
        assertThat(ruleBank).as("only the fee was booked by the rule").isEqualByComparingTo("-7");
    }

    @Test
    @DisplayName("[R9] payments compare in the account's currency: foreign payment into a functional bank, withholding")
    void paymentsCompareInAccountCurrency() {
        Books b = freshBooks();
        String bank = account(b.org(), bankCode(b));
        String partner = partner(b);
        String foreign = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                        + " \"paymentDate\", \"paymentMethod\", \"accountId\", amount, \"currencyId\", \"exchangeRate\", status,"
                        + " \"updatedAt\") VALUES (?, ?, ?, 'inbound'::\"PaymentDirection\", ?, ?::timestamp, 'bank', ?, 100,"
                        + " 'QZZ', 3700, 'posted'::\"DocumentStatus\", now())")
                .params(foreign, b.org(), "R9-F-" + foreign.substring(0, 6), partner, day + " 10:00:00", bank).update();
        String withheld = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Payment\" (id, \"organizationId\", \"paymentNumber\", direction, \"partnerId\","
                        + " \"paymentDate\", \"paymentMethod\", \"accountId\", amount, \"withholdingAmount\", status,"
                        + " \"updatedAt\") VALUES (?, ?, ?, 'inbound'::\"PaymentDirection\", ?, ?::timestamp, 'bank', ?, 1000,"
                        + " 60, 'posted'::\"DocumentStatus\", now())")
                .params(withheld, b.org(), "R9-W-" + withheld.substring(0, 6), partner, day + " 10:00:00", bank)
                .update();
        importLines(b, List.of(st("USD", "USD receipt", 370000), st("WHT", "Receipt net of WHT", 940)));
        assertThat(autoMatch(b).get("matched").asInt()).isEqualTo(2);
        assertThat(line(b, "USD").get("matchedPaymentId")).isEqualTo(foreign);
        assertThat(line(b, "WHT").get("matchedPaymentId")).isEqualTo(withheld);
        JsonNode summary = okAs(b, 200, "GET", "/bank-reconciliation/summary?bankAccountId=" + bank, null);
        assertThat(new BigDecimal(summary.get("outstandingInbound").asString())).isEqualByComparingTo("0");
    }
}
