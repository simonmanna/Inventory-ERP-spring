package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Owner decision, currency-aware payroll rounding (engine v1, end to end): full-precision maths, every payroll line
 * rounded once to the functional currency's decimals when the run is calculated, and payslips, bank payment
 * instructions / file and the approval journal all reading those stored values. For a UGX organisation (0 decimals)
 * with fractional intermediates (7.5 % allowance, 5 % pension, 7 % PAYE band, a 1/3 loan instalment, fractional
 * inputs) every line is whole, Σ payslip net = Σ bank lines = the net-pay payable credit, and the journal balances
 * with no rounding line; the same scenario in a 2-decimal currency (USD) is paid to the cent.
 */
class HrPayrollCurrencyRoundingIT extends ApiTestSupport {

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /**
     * Other employees, components and PAYE tables parked, settings removed (engine v1, no maker-checker), the DEMO
     * organisation's currency set to {@code currency}; everything restored afterwards (shared DEMO org).
     */
    void isolated(String currency, Consumer<String> body) {
        String s = suffix();
        String originalCurrency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        List<String> parked = jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        List<String> components = jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class)
                .list();
        List<String> taxTables = jdbc.sql("UPDATE \"HrTaxTable\" SET \"isActive\" = false WHERE \"organizationId\" = ?"
                + " AND \"isActive\" = true RETURNING id").param(demoOrg).query(String.class).list();
        jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        jdbc.sql("UPDATE \"Organization\" SET \"currencyCode\" = ? WHERE id = ?").params(currency, demoOrg).update();
        try {
            body.accept(s);
        } finally {
            jdbc.sql("UPDATE \"Organization\" SET \"currencyCode\" = ? WHERE id = ?").params(originalCurrency, demoOrg)
                    .update();
            jdbc.sql("DELETE FROM \"HrPayrollSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
            jdbc.sql("UPDATE \"HrPayrollInput\" SET status = 'cancelled' WHERE \"organizationId\" = ? AND status ="
                    + " 'pending' AND \"employeeId\" IN (SELECT id FROM \"HrEmployee\" WHERE \"firstName\" LIKE ?)")
                    .params(demoOrg, "%" + s).update();
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND \"firstName\""
                    + " LIKE ?").params(demoOrg, "%" + s).update();
            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND code LIKE"
                    + " ?").params(demoOrg, "%" + s).update();
            jdbc.sql("UPDATE \"HrTaxTable\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND code LIKE ?")
                    .params(demoOrg, "%" + s).update();
            jdbc.sql("UPDATE \"HrEmployee\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(parked.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrPayrollComponent\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(components.toArray(String[]::new)).update();
            jdbc.sql("UPDATE \"HrTaxTable\" SET \"isActive\" = true WHERE id = ANY(?)")
                    .param(taxTables.toArray(String[]::new)).update();
        }
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static boolean onScale(BigDecimal v, int scale) {
        return v.stripTrailingZeros().scale() <= scale;
    }

    /** Credits of the entry on the account mapped to {@code key}. */
    BigDecimal credited(String entryId, String key) {
        return jdbc.sql("SELECT coalesce(sum(l.credit), 0) FROM \"JournalLine\" l JOIN \"AccountMapping\" m ON"
                + " m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\" AND m.key = ? AND"
                + " m.\"deletedAt\" IS NULL WHERE l.\"journalEntryId\" = ?").params(key, entryId)
                .query(BigDecimal.class).single();
    }

    /** GET a file route as admin without parsing the body as JSON. */
    String file(String path) {
        try {
            java.net.http.HttpResponse<String> r = http.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Authorization", "Bearer " + adminToken).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.ISO_8859_1));
            assertThat(r.statusCode()).as(path + " → " + r.body()).isEqualTo(200);
            return r.body();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Expected item amounts of the scenario at one currency scale. */
    record Expected(int scale, String base, String allowance, String earningInput, String gross, String pension,
            String tax, String loan, String deductionInput, String totalDeductions, String net) {}

    /**
     * One employee on 1,000,001 a month: Housing 7.5 % (75,000.075), PENSION 5 % (50,000.05), a non-taxable earning
     * input of 2,500.40, a deduction input of 1,500.50 credited to a liability, a 1,000,000 loan over 3 instalments
     * (333,333.333333) and PAYE 7 % above 3,000,000 a year. Calculate, approve, generate the bank payment and file.
     */
    void scenario(String s, String month, Expected x) {
        int scale = x.scale();
        String emp = ok(201, "POST", "/hr/employees", Map.of("firstName", "Round" + s, "baseSalary", 1000001,
                "bankName", "Stanbic", "bankAccountNumber", "77" + s)).get("id").asString();
        ok(201, "POST", "/hr/payroll/components", Map.of("code", "hse" + s, "name", "Housing " + s, "componentType",
                "ALLOWANCE", "calcMethod", "PERCENTAGE", "rate", 7.5));
        ok(201, "POST", "/hr/payroll/components", Map.of("code", "pension" + s, "name", "Pension " + s,
                "componentType", "DEDUCTION", "calcMethod", "PERCENTAGE", "rate", 5));
        ok(201, "POST", "/hr/payroll/tax-tables", Map.of("code", "paye" + s, "name", "PAYE " + s, "effectiveFrom",
                "2020-01-01", "brackets", List.of(Map.of("fromAmount", 0, "toAmount", 3000000, "rate", 0),
                        Map.of("fromAmount", 3000000, "rate", 0.07))));
        ok(201, "POST", "/hr/loans", Map.of("employeeId", emp, "principal", 1000000, "installmentsTotal", 3));
        ok(201, "POST", "/hr/payroll/inputs", Map.of("employeeId", emp, "name", "Meal refund", "amount", 2500.40,
                "isTaxable", false));
        String liability = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2240'")
                .param(demoOrg).query(String.class).single();
        assertThat(as("POST", "/hr/payroll/inputs", Map.of("employeeId", emp, "name", "Fine " + s, "kind",
                "deduction", "amount", 1500.50)).status()).isEqualTo(400);
        ok(201, "POST", "/hr/payroll/inputs", Map.of("employeeId", emp, "name", "Fine " + s, "kind", "deduction",
                "amount", 1500.50, "creditAccountId", liability));

        int year = 2100 - (int) (Math.random() * 50);
        String periodId = ok(201, "POST", "/hr/payroll/periods", Map.of("startDate", year + "-" + month + "-01",
                "endDate", year + "-" + month + "-28", "periodCode", "PRD-RND" + s)).get("id").asString();
        String runId = ok(201, "POST", "/hr/payroll/runs", Map.of("periodId", periodId)).get("id").asString();
        JsonNode item = ok(201, "POST", "/hr/payroll/runs/" + runId + "/calculate", null).get("items").get(0);

        // Each line rounded once (HALF_UP); totals and net are sums of the rounded lines.
        assertThat(dec(item.get("baseSalary"))).isEqualByComparingTo(x.base());
        assertThat(dec(item.get("allowancesTotal"))).isEqualByComparingTo(
                new BigDecimal(x.allowance()).add(new BigDecimal(x.earningInput())));
        assertThat(dec(item.get("grossPay"))).isEqualByComparingTo(x.gross());
        assertThat(dec(item.get("pensionAmount"))).isEqualByComparingTo(x.pension());
        assertThat(dec(item.get("taxAmount"))).isEqualByComparingTo(x.tax());
        assertThat(dec(item.get("loanDeduction"))).isEqualByComparingTo(x.loan());
        assertThat(dec(item.get("totalDeductions"))).isEqualByComparingTo(x.totalDeductions());
        assertThat(dec(item.get("netPay"))).isEqualByComparingTo(x.net());
        String itemId = item.get("id").asString();
        List<BigDecimal> allowances = jdbc.sql("SELECT amount FROM \"HrPayrollAllowance\" WHERE \"itemId\" = ?")
                .param(itemId).query(BigDecimal.class).list();
        List<BigDecimal> deductions = jdbc.sql("SELECT amount FROM \"HrPayrollDeduction\" WHERE \"itemId\" = ?")
                .param(itemId).query(BigDecimal.class).list();
        assertThat(allowances).containsExactlyInAnyOrder(new BigDecimal(x.allowance()).setScale(6),
                new BigDecimal(x.earningInput()).setScale(6));
        assertThat(deductions).allMatch(a -> onScale(a, scale), "every payslip deduction at " + scale + " dp");
        assertThat(deductions.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("payslip deductions foot to total deductions").isEqualByComparingTo(x.totalDeductions());
        assertThat(deductions).anyMatch(a -> a.compareTo(new BigDecimal(x.deductionInput())) == 0);
        assertThat(dec(item.get("grossPay")).subtract(dec(item.get("totalDeductions"))))
                .as("net = Σ rounded earnings − Σ rounded deductions").isEqualByComparingTo(dec(item.get("netPay")));

        JsonNode approved = ok(201, "POST", "/hr/payroll/runs/" + runId + "/approve", null);
        String journal = approved.get("journalEntryId").asString();
        Map<String, Object> sums = jdbc.sql("SELECT sum(debit) AS d, sum(credit) AS c, count(*) FILTER (WHERE"
                + " debit <> round(debit, " + scale + ") OR credit <> round(credit, " + scale + ")) AS off,"
                + " count(*) FILTER (WHERE description ILIKE '%round%') AS rounding FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ?").param(journal).query().singleRow();
        assertThat((BigDecimal) sums.get("d")).as("balanced").isEqualByComparingTo((BigDecimal) sums.get("c"));
        assertThat((BigDecimal) sums.get("d")).as("Dr salaries = gross").isEqualByComparingTo(x.gross());
        assertThat(((Number) sums.get("off")).longValue()).as("journal lines at the currency scale").isZero();
        assertThat(((Number) sums.get("rounding")).longValue()).as("no rounding line").isZero();
        BigDecimal netCredit = credited(journal, "net_pay_payable");
        assertThat(netCredit).isEqualByComparingTo(x.net());
        assertThat(jdbc.sql("SELECT coalesce(sum(credit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND"
                + " \"accountId\" = ?").params(journal, liability).query(BigDecimal.class).single())
                .as("deduction input credited at its payroll line amount").isEqualByComparingTo(x.deductionInput());

        // Payslip net (Σ over the run's payslips) = bank instructions = net-pay payable credit.
        BigDecimal payslipNet = jdbc.sql("SELECT coalesce(sum(i.\"netPay\"), 0) FROM \"HrPayslip\" p JOIN"
                + " \"HrPayrollItem\" i ON i.id = p.\"itemId\" WHERE i.\"runId\" = ? AND p.\"deletedAt\" IS NULL")
                .param(runId).query(BigDecimal.class).single();
        assertThat(payslipNet).isEqualByComparingTo(netCredit);
        JsonNode bank = ok(201, "POST", "/hr/bank-payments/generate", Map.of("runId", runId));
        BigDecimal lines = BigDecimal.ZERO;
        for (JsonNode l : bank.get("lines")) {
            assertThat(onScale(dec(l.get("amount")), scale)).as("bank line at " + scale + " dp").isTrue();
            lines = lines.add(dec(l.get("amount")));
        }
        assertThat(lines).isEqualByComparingTo(netCredit);
        assertThat(dec(bank.get("totalAmount"))).isEqualByComparingTo(netCredit);
        String csv = file("/hr/payroll/runs/" + runId + "/bank-file?format=CSV");
        assertThat(csv).contains("," + new BigDecimal(x.net()).setScale(scale, RoundingMode.UNNECESSARY)
                .toPlainString() + ",");
        String payslipId = approved.get("items").get(0).get("payslip").get("id").asString();
        assertThat(file("/hr/payslips/" + payslipId + "/pdf")).startsWith("%PDF");

        ok(201, "POST", "/hr/payroll/runs/" + runId + "/reverse", Map.of("reason", "test cleanup"));
    }

    @Test
    @DisplayName("UGX (0 decimals): whole payslip lines; Σ payslip net = Σ bank lines = net-pay credit; no rounding"
            + " line")
    void ugxWholeShillings() {
        isolated("UGX", s -> scenario(s, "07", new Expected(0,
                "1000001", "75000", "2500", "1077501", "50000",
                // (1,025,001 × 12 − 3,000,000) × 7 % / 12 = 54,250.07 → 54,250
                "54250", "333333", "1501",
                // 54,250 + 50,000 + 333,333 + 1,501
                "439084", "638417")));
    }

    @Test
    @DisplayName("USD (2 decimals): the same scenario is paid to the cent and reconciles the same way")
    void twoDecimalCurrency() {
        isolated("USD", s -> scenario(s, "08", new Expected(2,
                "1000001.00", "75000.08", "2500.40", "1077501.48", "50000.05",
                // (1,025,001.03 × 12 − 3,000,000) × 7 % / 12 = 54,250.0721 → 54,250.07
                "54250.07", "333333.33", "1500.50",
                "439083.95", "638417.53")));
    }
}
