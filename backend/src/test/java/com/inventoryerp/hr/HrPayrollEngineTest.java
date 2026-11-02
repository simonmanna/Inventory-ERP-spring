package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.hr.application.HrCompensationService;
import com.inventoryerp.hr.application.HrFormula;
import com.inventoryerp.hr.application.HrPayrollCalculator;
import com.inventoryerp.hr.application.HrStatutory;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** HP-2 / HP-3 pure maths: formulas, statutory packs (Uganda, UAE) and the decimal payroll calculator. */
class HrPayrollEngineTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static final List<HrStatutory.Rate> UG = List.of(
            new HrStatutory.Rate("UG", "PAYE", "PAYE", Map.of("bands", List.of(
                    Map.of("upTo", 235000, "rate", 0), Map.of("upTo", 335000, "rate", 0.10),
                    Map.of("upTo", 410000, "rate", 0.20), new java.util.HashMap<>(Map.of("rate", 0.30))),
                    "surchargeThreshold", 10000000, "surchargeRate", 0.10, "payableKey", "paye_payable")),
            new HrStatutory.Rate("UG", "NSSF", "NSSF", Map.of("employeeRate", 0.05, "employerRate", 0.10,
                    "payableKey", "social_security_payable")),
            new HrStatutory.Rate("UG", "LST", "LST", Map.of("bands", List.of(Map.of("upTo", 100000, "annual", 0),
                    Map.of("upTo", 1000000, "annual", 90000), new java.util.HashMap<>(Map.of("annual", 100000))),
                    "months", List.of(7, 8, 9, 10), "payableKey", "local_service_tax_payable")));

    static BigDecimal amount(List<HrStatutory.Line> lines, String code, String kind) {
        return lines.stream().filter(l -> l.code().equals(code) && l.kind().equals(kind)).map(HrStatutory.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Nested
    class Formula {
        @Test
        @DisplayName("arithmetic, functions and comparisons over whitelisted variables")
        void evaluates() {
            Map<String, BigDecimal> v = Map.of("BASIC", d("1000000"), "DAYS_PAID", d("15"), "DAYS_IN_PERIOD", d("30"));
            assertThat(HrFormula.eval("BASIC * 0.1 + 500", v)).isEqualByComparingTo("100500");
            assertThat(HrFormula.eval("ROUND(BASIC * DAYS_PAID / DAYS_IN_PERIOD / 3, 0)", v))
                    .isEqualByComparingTo("166667");
            assertThat(HrFormula.eval("IF(BASIC >= 500000, MIN(BASIC * 0.2, 150000), 0)", v))
                    .isEqualByComparingTo("150000");
            assertThat(HrFormula.eval("-(2 - 5)", v)).isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("unknown variables, bad syntax and division by zero are reported, not executed")
        void rejects() {
            assertThat(HrFormula.validate("BASIC * 0.1")).isNull();
            assertThat(HrFormula.validate("SALARY * 2")).contains("Unknown variable");
            assertThat(HrFormula.validate("BASIC *")).isNotNull();
            assertThat(HrFormula.validate("Runtime.exec(1)")).isNotNull();
            assertThatThrownBy(() -> HrFormula.eval("BASIC / 0", Map.of("BASIC", BigDecimal.ONE)))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class Uganda {
        @Test
        @DisplayName("PAYE resident bands: 1,000,000 a month → 202,000; NSSF 5 % / 10 %")
        void payeAndNssf() {
            List<HrStatutory.Line> l = HrStatutory.compute("UG", UG, new HrStatutory.Input(d("1000000"), d("1000000"),
                    d("1000000"), 12, LocalDate.of(2026, 3, 31), "UG"));
            assertThat(amount(l, "PAYE", "DEDUCTION")).isEqualByComparingTo("202000");
            assertThat(amount(l, "NSSF", "DEDUCTION")).isEqualByComparingTo("50000");
            assertThat(amount(l, "NSSF", "EMPLOYER_CONTRIBUTION")).isEqualByComparingTo("100000");
            assertThat(amount(l, "LST", "DEDUCTION")).isZero();
        }

        @Test
        @DisplayName("above 10 M the 10 % surcharge applies; below 235,000 no PAYE")
        void surchargeAndThreshold() {
            List<HrStatutory.Line> high = HrStatutory.compute("UG", UG, new HrStatutory.Input(d("12000000"),
                    d("12000000"), d("12000000"), 12, LocalDate.of(2026, 3, 31), null));
            // 10,000 + 15,000 + 30 % × 11,590,000 + 10 % × 2,000,000
            assertThat(amount(high, "PAYE", "DEDUCTION")).isEqualByComparingTo("3702000");
            List<HrStatutory.Line> low = HrStatutory.compute("UG", UG, new HrStatutory.Input(d("200000"), d("200000"),
                    d("200000"), 12, LocalDate.of(2026, 3, 31), null));
            assertThat(amount(low, "PAYE", "DEDUCTION")).isZero();
        }

        @Test
        @DisplayName("Local Service Tax: a quarter of the annual band in July–October only")
        void localServiceTax() {
            HrStatutory.Input july = new HrStatutory.Input(d("1000000"), d("1000000"), d("1000000"), 12,
                    LocalDate.of(2026, 7, 31), null);
            assertThat(amount(HrStatutory.compute("UG", UG, july), "LST", "DEDUCTION")).isEqualByComparingTo("22500");
        }

        @Test
        @DisplayName("weekly pay: monthly bands applied to the monthly equivalent and scaled back")
        void weekly() {
            BigDecimal weekly = d("1000000").multiply(d("12")).divide(d("52"), java.math.MathContext.DECIMAL64);
            List<HrStatutory.Line> l = HrStatutory.compute("UG", UG, new HrStatutory.Input(weekly, weekly, weekly, 52,
                    LocalDate.of(2026, 3, 6), null));
            assertThat(amount(l, "PAYE", "DEDUCTION").multiply(d("52")).divide(d("12"),
                    java.math.MathContext.DECIMAL64).setScale(0, java.math.RoundingMode.HALF_UP))
                    .isEqualByComparingTo("202000");
        }
    }

    @Nested
    class Uae {
        static final Map<String, Object> GPSSA = Map.of("nationalities", List.of("AE", "UAE"), "employeeRate", 0.11,
                "employerRate", 0.125, "governmentRate", 0.025, "minBase", 1000, "maxBase", 100000,
                "payableKey", "pension_payable");
        static final Map<String, Object> EOSB = Map.of("daysFirst5Years", 21, "daysAfter5Years", 30,
                "minServiceYears", 1, "capYearsOfWage", 2, "dailyDivisor", 30);

        @Test
        @DisplayName("GPSSA for UAE nationals only, capped base; no income tax")
        void gpssa() {
            List<HrStatutory.Rate> rates = List.of(new HrStatutory.Rate("AE", "GPSSA", "GPSSA", GPSSA));
            List<HrStatutory.Line> national = HrStatutory.compute("AE", rates, new HrStatutory.Input(d("30000"),
                    d("30000"), d("20000"), 12, LocalDate.of(2026, 3, 31), "uae"));
            assertThat(amount(national, "GPSSA", "DEDUCTION")).isEqualByComparingTo("2200");
            assertThat(amount(national, "GPSSA", "EMPLOYER_CONTRIBUTION")).isEqualByComparingTo("2500");
            assertThat(amount(national, "GPSSA", "INFO")).isEqualByComparingTo("500");
            List<HrStatutory.Line> capped = HrStatutory.compute("AE", rates, new HrStatutory.Input(d("200000"),
                    d("200000"), d("150000"), 12, LocalDate.of(2026, 3, 31), "AE"));
            assertThat(amount(capped, "GPSSA", "DEDUCTION")).isEqualByComparingTo("11000");
            assertThat(HrStatutory.compute("AE", rates, new HrStatutory.Input(d("30000"), d("30000"), d("20000"), 12,
                    LocalDate.of(2026, 3, 31), "IN"))).isEmpty();
        }

        @Test
        @DisplayName("end-of-service gratuity: 21 days a year to five years, 30 after, two years' cap, none under a year")
        void gratuity() {
            assertThat(HrStatutory.gratuity(EOSB, null, d("9000"), LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 30))
                    .setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("6300");
            assertThat(HrStatutory.gratuity(EOSB, null, d("9000"), LocalDate.of(2020, 1, 1),
                    LocalDate.of(2020, 6, 30))).isZero();
            BigDecimal seven = HrStatutory.gratuity(EOSB, null, d("9000"), LocalDate.of(2015, 1, 1),
                    LocalDate.of(2021, 12, 29));
            // 2,555 days = 7 years: 5 × 21 + 2 × 30 = 165 days × 300 a day.
            assertThat(seven.setScale(0, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("49500");
            BigDecimal capped = HrStatutory.gratuity(EOSB, null, d("9000"), LocalDate.of(1990, 1, 1),
                    LocalDate.of(2025, 12, 31));
            assertThat(capped).isEqualByComparingTo("216000");
            assertThat(HrStatutory.gratuity(null, d("15"), d("3000000"), LocalDate.of(2024, 1, 1),
                    LocalDate.of(2025, 12, 30)).setScale(0, java.math.RoundingMode.HALF_UP))
                    .isEqualByComparingTo("3000000");
        }
    }

    @Nested
    class Calculator {
        HrPayrollCalculator.Context ctx(String basis, BigDecimal basic, LocalDate hire, BigDecimal unpaid,
                List<HrCompensationService.Element> elements, List<HrPayrollCalculator.Input> inputs,
                List<HrPayrollCalculator.Recovery> recoveries, String pack) {
            return new HrPayrollCalculator.Context(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 12, basis, 0,
                    d("8"), Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), x -> false, hire, null, basic, false, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, unpaid, BigDecimal.ZERO, false, elements, inputs,
                    null, pack, "UG".equals(pack) ? UG : List.of(), "UG", recoveries);
        }

        HrCompensationService.Element element(String code, String kind, String method, String amount, String rate,
                String formula, boolean prorated) {
            return new HrCompensationService.Element(code, code, code, kind, method,
                    amount == null ? null : d(amount), rate == null ? null : d(rate), formula, true, false, prorated,
                    null, 100, "employee");
        }

        @Test
        @DisplayName("a joiner on 17 March (calendar days) is paid 15/31 of basic and of prorated elements")
        void joinerProrated() {
            HrPayrollCalculator.Result r = HrPayrollCalculator.calculate(ctx("CALENDAR_DAYS", d("3100000"),
                    LocalDate.of(2026, 3, 17), null, List.of(element("HOUSING", "EARNING", "FIXED", "310000", null, null,
                            true), element("TRANSPORT", "EARNING", "FIXED", "50000", null, null, false)), List.of(),
                    List.of(), "NONE"));
            assertThat(r.basic()).isEqualByComparingTo("1500000");
            assertThat(r.gross()).isEqualByComparingTo("1700000");
            assertThat(r.lines().stream().filter(l -> l.code().equals("HOUSING")).findFirst().orElseThrow().amount())
                    .isEqualByComparingTo("150000");
            assertThat(r.lines().stream().filter(l -> l.code().equals("TRANSPORT")).findFirst().orElseThrow().amount())
                    .isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("unpaid leave on working days: 2 of 22 days off pays 20/22 of basic")
        void unpaidWorkingDays() {
            HrPayrollCalculator.Result r = HrPayrollCalculator.calculate(ctx("WORKING_DAYS", d("2200000"), null,
                    d("2"), List.of(), List.of(), List.of(), "NONE"));
            assertThat(r.basic()).isEqualByComparingTo("2000000");
            assertThat(r.daysPaid()).isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("fixed-30 month: a full month is 30 days whatever its length")
        void fixed30() {
            HrPayrollCalculator.Result r = HrPayrollCalculator.calculate(ctx("FIXED_30", d("3000000"), null, d("3"),
                    List.of(), List.of(), List.of(), "NONE"));
            assertThat(r.basic()).isEqualByComparingTo("2700000");
        }

        @Test
        @DisplayName("Uganda payslip: gross, PAYE, NSSF, employer NSSF, loan limited by net, net = gross − deductions")
        void ugandaPayslip() {
            HrPayrollCalculator.Result r = HrPayrollCalculator.calculate(ctx("CALENDAR_DAYS", d("800000"), null, null,
                    List.of(element("HOUSING", "EARNING", "FORMULA", null, null, "BASIC * 0.25", false)),
                    List.of(new HrPayrollCalculator.Input("i1", "earning", "Bonus", d("100000"), true),
                            new HrPayrollCalculator.Input("i2", "deduction", "Fine", d("5000000"), false)),
                    List.of(new HrPayrollCalculator.Recovery("l1", "LOAN", "LN-1", d("2000000"), d("9000000"))), "UG"));
            assertThat(r.gross()).isEqualByComparingTo("1100000");
            // PAYE on 1,100,000: 25,000 + 30 % × 690,000 = 232,000
            assertThat(r.tax()).isEqualByComparingTo("232000");
            assertThat(r.socialSecurity()).isEqualByComparingTo("55000");
            assertThat(r.employerCost()).isEqualByComparingTo("110000");
            // The loan takes what is left; the 5 M fine does not fit and waits.
            assertThat(r.loanDeduction()).isEqualByComparingTo("813000");
            assertThat(r.netPay()).isZero();
            assertThat(r.usedInputIds()).containsExactly("i1");
            assertThat(r.netPay()).isEqualByComparingTo(r.gross().subtract(r.totalDeductions()));
        }
    }
}
