package com.inventoryerp.hr.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Owner decision, currency-aware payroll rounding: full-precision maths, each payroll line rounded once (HALF_UP) to
 * the pay scale, totals and net derived from the rounded lines, and nothing downstream (payslip, bank file) rounds
 * again. Pure unit test (package-private helpers), no Spring context.
 */
class HrPayrollCurrencyRoundingTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static final List<HrStatutory.Rate> UG = List.of(
            new HrStatutory.Rate("UG", "PAYE", "PAYE", Map.of("bands", List.of(
                    Map.of("upTo", 235000, "rate", 0), Map.of("upTo", 335000, "rate", 0.10),
                    Map.of("upTo", 410000, "rate", 0.20), new java.util.HashMap<>(Map.of("rate", 0.30))),
                    "surchargeThreshold", 10000000, "surchargeRate", 0.10, "payableKey", "paye_payable")),
            new HrStatutory.Rate("UG", "NSSF", "NSSF", Map.of("employeeRate", 0.05, "employerRate", 0.10,
                    "payableKey", "social_security_payable")));

    /** A joiner on 17 March (15/31 of the month) on 1,000,001 with a 7.5 % of basic allowance and the UG pack. */
    static HrPayrollCalculator.Result joiner(int scale) {
        HrCompensationService.Element allowance = new HrCompensationService.Element("HSE", "HSE", "Housing",
                "EARNING", "PERCENT_OF_BASIC", null, d("7.5"), null, true, false, false, null, 100, "employee");
        HrCompensationService.Element sacco = new HrCompensationService.Element("SACCO", "SACCO", "SACCO",
                "DEDUCTION", "PERCENT_OF_GROSS", null, d("1.25"), null, false, false, false, "acc-2240", 110,
                "employee");
        return HrPayrollCalculator.calculate(new HrPayrollCalculator.Context(LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), 12, "CALENDAR_DAYS", scale, d("8"),
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), x -> false, LocalDate.of(2026, 3, 17), null,
                d("1000001"), false, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO,
                false, List.of(allowance, sacco),
                List.of(new HrPayrollCalculator.Input("i1", "deduction", "Fine", d("1500.50"), false)), null, "UG", UG,
                "UG", List.of(new HrPayrollCalculator.Recovery("l1", "LOAN", "LN-1", d("333333.333333"),
                        d("1000000")))));
    }

    static BigDecimal sum(HrPayrollCalculator.Result r, String kind) {
        return r.lines().stream().filter(l -> l.kind().equals(kind)).map(HrPayrollCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static void assertFoots(HrPayrollCalculator.Result r, int scale) {
        for (HrPayrollCalculator.Line l : r.lines()) {
            assertThat(HrPayMoney.onScale(l.amount(), scale)).as(l.code() + " " + l.amount() + " at scale " + scale)
                    .isTrue();
        }
        assertThat(r.gross()).isEqualByComparingTo(sum(r, "EARNING"));
        assertThat(r.totalDeductions()).isEqualByComparingTo(sum(r, "DEDUCTION"));
        assertThat(r.employerCost()).isEqualByComparingTo(sum(r, "EMPLOYER_CONTRIBUTION"));
        assertThat(r.netPay()).as("net derived from the rounded parts")
                .isEqualByComparingTo(sum(r, "EARNING").subtract(sum(r, "DEDUCTION")));
        assertThat(HrPayMoney.onScale(r.netPay(), scale)).isTrue();
    }

    @Test
    @DisplayName("UGX (0 decimals): prorated basic 483,871.45… and % lines are whole shillings; net foots")
    void ugxWholeShillings() {
        HrPayrollCalculator.Result r = joiner(0);
        assertFoots(r, 0);
        // 1,000,001 × 15 / 31 = 483,871.4516… → 483,871; housing 7.5 % = 36,290.325 → 36,290.
        assertThat(r.basic()).isEqualByComparingTo("483871");
        assertThat(r.gross()).isEqualByComparingTo("520161");
        // The 1,500.50 fine is recovered as 1,501 (its payroll line), the loan instalment 333,333.333333 as 333,333.
        assertThat(r.inputDeductions()).isEqualByComparingTo("1501");
        assertThat(r.loanDeduction()).isEqualByComparingTo("333333");
    }

    @Test
    @DisplayName("2-decimal currency: the same employee is paid to the cent, every line ≤ 2 dp, net foots")
    void twoDecimals() {
        HrPayrollCalculator.Result r = joiner(2);
        assertFoots(r, 2);
        assertThat(r.basic()).isEqualByComparingTo("483871.45");
        // housing 7.5 % of 483,871.45 = 36,290.35875 → 36,290.36
        assertThat(r.gross()).isEqualByComparingTo("520161.81");
        assertThat(r.inputDeductions()).isEqualByComparingTo("1500.50");
        assertThat(r.loanDeduction()).isEqualByComparingTo("333333.33");
    }

    @Test
    @DisplayName("the one rounding is HALF_UP; stored amounts are written to files and payslips without re-rounding")
    void helpers() {
        assertThat(HrPayMoney.round(d("2.5"), 0)).isEqualByComparingTo("3");
        assertThat(HrPayMoney.round(d("75000.075"), 2)).isEqualByComparingTo("75000.08");
        assertThat(HrPayMoney.round(d("1500.4999"), 0)).isEqualByComparingTo("1500");
        assertThat(HrPayMoney.onScale(d("638417.000000"), 0)).isTrue();
        assertThat(HrPayMoney.onScale(d("638417.53"), 0)).isFalse();
        assertThat(HrPayMoney.onScale(d("638417.530000"), 2)).isTrue();
        assertThat(HrPayMoney.plain(d("638417.000000"), 0)).isEqualTo("638417");
        assertThat(HrPayMoney.plain(d("638417.5"), 2)).isEqualTo("638417.50");
        // A history run stored at 6 dp is shown as stored, never rounded to a different figure.
        assertThat(HrPayMoney.plain(d("1000.333333"), 0)).isEqualTo("1000.333333");
        // The payslip formatter keeps every stored decimal (locale-neutral check: digits only).
        assertThat(HrPayslipPdf.money(d("1234567.000000"), 0).replaceAll("\\D", "")).isEqualTo("1234567");
        assertThat(HrPayslipPdf.money(d("0.5"), 0).replaceAll("\\D", "")).isEqualTo("05");
    }
}
