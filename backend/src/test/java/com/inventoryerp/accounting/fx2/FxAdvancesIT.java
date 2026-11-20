package com.inventoryerp.accounting.fx2;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FX-2 F: advance allocation and credit-note allocation realise FX; foreign loans and foreign opening balances carry
 * their currency. AR / AP per partner ties in both currencies.
 */
class FxAdvancesIT extends AccountingFixtures {

    record Setup(Books b, String base, String ccy, String bank, String customer, String service, String ar) {}

    Setup setup() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        rate(b, ccy, base, LocalDate.now().minusDays(5).toString(), "3800");
        String bank = okAs(b, 201, "POST", "/accounts/cash-flow", Map.of("code", "12" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX2 advance bank", "accountType", "bank", "currencyId", ccy))
                .get("id").asString();
        String customer = okAs(b, 201, "POST", "/partners", Map.of("code", "A-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX2 advance customer", "isCustomer", true)).get("id").asString();
        String service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'FX2 svc', 'service',"
                        + " 100, 0, false, now())").params(service, b.org(), "FA-" + UUID.randomUUID().toString()
                        .substring(0, 6)).update();
        return new Setup(b, base, ccy, bank, customer, service, mapped(b.org(), "accounts_receivable"));
    }

    String invoice(Setup s, int amount, int rate) {
        String inv = okAs(s.b(), 200, "POST", "/invoices", Map.of("partnerId", s.customer(), "issueDate", today(),
                "currencyId", s.ccy(), "exchangeRate", rate, "lines", List.of(Map.of("productId", s.service(),
                        "quantity", 1, "unitPrice", amount)))).get("id").asString();
        okAs(s.b(), 200, "POST", "/invoices/" + inv + "/post", null);
        return inv;
    }

    BigDecimal[] arOf(Setup s) {
        return jdbc.sql("SELECT coalesce(sum(CASE WHEN upper(l.\"currencyId\") = ? THEN l.debit - l.credit END), 0),"
                        + " coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                        + " ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ? AND"
                        + " l.\"partnerId\" = ? AND e.status::text IN ('posted', 'reversed')")
                .params(s.ccy(), s.b().org(), s.ar(), s.customer())
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).single();
    }

    BigDecimal fxNet(String org) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"organizationId\" = ? AND l.\"accountId\" IN (SELECT \"accountId\" FROM \"AccountMapping\" WHERE"
                + " \"organizationId\" = ? AND key IN ('fx_gain', 'fx_loss'))").params(org, org)
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("advance $100 at 3,700 allocated to a $100 invoice at 3,800: loss 10,000, AR ties; void reverses")
    void advanceAllocation() {
        Setup s = setup();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", s.customer());
        body.put("paymentDate", today());
        body.put("amount", 100);
        body.put("paymentMethod", "bank");
        body.put("accountId", s.bank());
        body.put("currencyCode", s.ccy());
        body.put("exchangeRate", 3700);
        body.put("allowOverpayment", true);
        Res receipt = moneyAs(s.b(), "POST", "/payments", body);
        assertThat(receipt.status()).as(receipt.raw().body()).isIn(200, 201);
        String paymentId = receipt.body().get("id").asString();
        String inv = invoice(s, 100, 3800);
        BigDecimal fx0 = fxNet(s.b().org());

        Res alloc = moneyAs(s.b(), "POST", "/payments/" + paymentId + "/allocations", Map.of("allocations",
                List.of(Map.of("documentId", inv, "amount", 100))));
        assertThat(alloc.status()).as(alloc.raw().body()).isIn(200, 201);
        assertThat(fxNet(s.b().org()).subtract(fx0)).as("100 × (3,700 − 3,800)").isEqualByComparingTo("-10000");
        BigDecimal[] ar = arOf(s);
        assertThat(ar[0]).as("AR clear in the currency").isEqualByComparingTo("0");
        assertThat(ar[1]).as("AR clear in base").isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(inv)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");

        Res again = moneyAs(s.b(), "POST", "/payments/" + paymentId + "/allocations", Map.of("allocations",
                List.of(Map.of("documentId", inv, "amount", 1))));
        assertThat(again.status()).isEqualTo(400);

        Res voided = moneyAs(s.b(), "POST", "/payments/" + paymentId + "/void", Map.of("reason", "FX2 test"));
        assertThat(voided.status()).as(voided.raw().body()).isIn(200, 201);
        assertThat(fxNet(s.b().org())).as("allocation FX reversed with the payment").isEqualByComparingTo(fx0);
    }

    @Test
    @DisplayName("a $40 credit at 3,800 allocated to a $100 invoice at 3,700: gain 4,000 on allocation, AR ties")
    void creditNoteAllocation() {
        Setup s = setup();
        String inv = invoice(s, 100, 3700);
        String cn = okAs(s.b(), 200, "POST", "/credit-notes", Map.of("partnerId", s.customer(), "issueDate", today(),
                "currencyId", s.ccy(), "lines", List.of(Map.of("productId", s.service(), "quantity", 1,
                        "unitPrice", 40)))).get("id").asString();
        okAs(s.b(), 200, "POST", "/credit-notes/" + cn + "/post", null);
        BigDecimal fx0 = fxNet(s.b().org());
        okAs(s.b(), 200, "POST", "/credit-notes/" + cn + "/allocate", Map.of("documentId", inv, "amount", 40));
        BigDecimal fx = fxNet(s.b().org()).subtract(fx0);
        assertThat(fx.abs()).as("40 × |3,800 − 3,700|").isEqualByComparingTo("4000");
        BigDecimal[] ar = arOf(s);
        assertThat(ar[0]).as("60 open in the currency").isEqualByComparingTo("60");
        assertThat(ar[1]).as("at the invoice's 3,700").isEqualByComparingTo("222000");
    }

    BigDecimal[] tagged(String org, String accountId, String partnerId, String ccy) {
        return jdbc.sql("SELECT coalesce(sum(l.debit - l.credit), 0), coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0)"
                        + " FROM \"JournalLine\" l WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ? AND"
                        + " upper(l.\"currencyId\") = ? AND (?::text IS NULL OR l.\"partnerId\" = ?)")
                .params(org, accountId, ccy, partnerId, partnerId)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).single();
    }

    String loanAccount(Books b) {
        return jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.key IN ('non_current_liability', 'current_liability') AND"
                + " a.\"isPostable\" AND a.\"isActive\" AND a.\"deletedAt\" IS NULL AND a.\"isProtected\" = false"
                + " ORDER BY c.key DESC, a.code LIMIT 1").param(b.org()).query(String.class).single();
    }

    @Test
    @DisplayName("a $1,000 loan at 3,700 into a USD bank is a USD liability; repaying $400 relieves it at 3,700")
    void foreignLoan() {
        Setup s = setup();
        String loan = loanAccount(s.b());
        Res in = moneyAs(s.b(), "POST", "/accounts/cash-flow/deposit", Map.of("accountId", s.bank(),
                "counterpartAccountId", loan, "operationType", "loan_received", "amount", 1000, "description",
                "USD loan", "exchangeRate", 3700));
        assertThat(in.status()).as(in.raw().body()).isIn(200, 201);
        String equity = account(s.b().org(), accountCodeOfSection(s.b().org(), "equity"));
        Res capital = moneyAs(s.b(), "POST", "/accounts/cash-flow/deposit", Map.of("accountId", s.bank(),
                "counterpartAccountId", equity, "operationType", "owner_contribution", "amount", 1000, "description",
                "USD capital", "exchangeRate", 3800));
        assertThat(capital.status()).as(capital.raw().body()).isIn(200, 201);
        BigDecimal[] owed = tagged(s.b().org(), loan, null, s.ccy());
        assertThat(owed[0]).isEqualByComparingTo("-1000");
        assertThat(owed[1]).isEqualByComparingTo("-3700000");
        BigDecimal fx0 = fxNet(s.b().org());

        Res repay = moneyAs(s.b(), "POST", "/accounts/cash-flow/withdraw", Map.of("accountId", s.bank(),
                "counterpartAccountId", loan, "operationType", "loan_repayment", "amount", 400, "description",
                "USD loan repayment", "exchangeRate", 3800));
        assertThat(repay.status()).as(repay.raw().body()).isIn(200, 201);
        owed = tagged(s.b().org(), loan, null, s.ccy());
        assertThat(owed[0]).as("600 still owed").isEqualByComparingTo("-600");
        assertThat(owed[1]).as("at the loan's 3,700").isEqualByComparingTo("-2220000");
        assertThat(fxNet(s.b().org()).subtract(fx0)).as("cash out at 3,750 carrying, loan relieved at 3,700")
                .isEqualByComparingTo("-20000");
    }

    @Test
    @DisplayName("opening balances in USD: bank, customer and supplier carry the currency at the opening rate")
    void foreignOpeningBalances() {
        Setup s = setup();
        String supplier = okAs(s.b(), 201, "POST", "/partners", Map.of("code", "S-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX2 opening supplier", "isSupplier", true)).get("id").asString();
        Res res = moneyAs(s.b(), "POST", "/opening-balances", Map.of("date", today(), "reference", "FX2 opening",
                "accounts", List.of(Map.of("accountId", s.bank(), "debit", 500, "currencyCode", s.ccy(),
                        "exchangeRate", 3700)),
                "customers", List.of(Map.of("partnerId", s.customer(), "amount", 200, "currencyCode", s.ccy(),
                        "exchangeRate", 3700)),
                "suppliers", List.of(Map.of("partnerId", supplier, "amount", 300, "currencyCode", s.ccy(),
                        "exchangeRate", 3750))));
        assertThat(res.status()).as(res.raw().body()).isIn(200, 201);
        BigDecimal[] bank = tagged(s.b().org(), s.bank(), null, s.ccy());
        assertThat(bank[0]).isEqualByComparingTo("500");
        assertThat(bank[1]).isEqualByComparingTo("1850000");
        BigDecimal[] ar = arOf(s);
        assertThat(ar[0]).isEqualByComparingTo("200");
        assertThat(ar[1]).isEqualByComparingTo("740000");
        BigDecimal[] ap = tagged(s.b().org(), mapped(s.b().org(), "accounts_payable"), supplier, s.ccy());
        assertThat(ap[0]).isEqualByComparingTo("-300");
        assertThat(ap[1]).isEqualByComparingTo("-1125000");
    }
}
