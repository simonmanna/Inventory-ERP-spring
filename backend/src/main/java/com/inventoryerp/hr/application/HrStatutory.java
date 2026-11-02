package com.inventoryerp.hr.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Statutory packs (HP-3, ADR-S048): pure functions over effective-dated rate rows ({@code HrStatutoryRate.params}).
 * Amounts in and out are per pay period; monthly bands are applied to the monthly equivalent
 * ({@code amount × periodsPerYear / 12}) and scaled back. Results are unrounded; the engine rounds each line.
 *
 * <ul>
 *   <li>UG: PAYE (resident monthly bands + 10 % surcharge above 10 M), NSSF 5 % employee / 10 % employer on gross,
 *       Local Service Tax (annual amount by monthly gross band, in four instalments July–October).
 *   <li>AE: no income tax; GPSSA pension for UAE nationals (11 % employee, 12.5 % employer, 2.5 % government shown
 *       for information) on the pensionable salary between the floor and cap; end-of-service gratuity.
 * </ul>
 */
public final class HrStatutory {

    static final MathContext MC = MathContext.DECIMAL64;
    static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    /** One rate row: pack, code, name, parameters. */
    public record Rate(String packCode, String code, String name, Map<String, Object> params) {}

    /** What a pack needs about one employee and period. */
    public record Input(BigDecimal gross, BigDecimal taxableGross, BigDecimal pensionableBase, int periodsPerYear,
            LocalDate periodEnd, String nationality) {}

    /** One statutory result line. kind: DEDUCTION (employee), EMPLOYER_CONTRIBUTION, INFO. */
    public record Line(String code, String name, String kind, String category, BigDecimal amount, BigDecimal base,
            BigDecimal rate, String payableKey, String fallbackPayableKey, String expenseKey) {}

    private HrStatutory() {}

    public static List<Line> compute(String pack, List<Rate> rates, Input in) {
        List<Line> out = new ArrayList<>();
        if (pack == null || "NONE".equals(pack)) {
            return out;
        }
        BigDecimal toMonthly = BigDecimal.valueOf(in.periodsPerYear()).divide(TWELVE, MC);
        for (Rate r : rates) {
            Map<String, Object> p = r.params();
            switch (pack + ":" + r.code()) {
                case "UG:PAYE" -> {
                    BigDecimal monthly = in.taxableGross().multiply(toMonthly, MC);
                    BigDecimal tax = progressive(list(p.get("bands")), monthly);
                    BigDecimal threshold = num(p.get("surchargeThreshold"));
                    if (threshold != null && monthly.compareTo(threshold) > 0) {
                        tax = tax.add(monthly.subtract(threshold).multiply(num(p.get("surchargeRate")), MC));
                    }
                    BigDecimal periodic = tax.divide(toMonthly, MC);
                    if (periodic.signum() > 0) {
                        out.add(new Line("PAYE", r.name(), "DEDUCTION", "TAX", periodic, in.taxableGross(), null,
                                str(p.get("payableKey")), null, null));
                    }
                }
                case "UG:NSSF" -> {
                    BigDecimal base = in.gross();
                    add(out, "NSSF", r.name() + " (employee)", "DEDUCTION", base, num(p.get("employeeRate")), p);
                    add(out, "NSSF", r.name() + " (employer)", "EMPLOYER_CONTRIBUTION", base,
                            num(p.get("employerRate")), p);
                }
                case "UG:LST" -> {
                    if (!list(p.get("months")).contains(in.periodEnd().getMonthValue())) {
                        break;
                    }
                    BigDecimal monthly = in.gross().multiply(toMonthly, MC);
                    BigDecimal annual = BigDecimal.ZERO;
                    for (Object o : list(p.get("bands"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> b = (Map<String, Object>) o;
                        BigDecimal upTo = num(b.get("upTo"));
                        if (upTo == null || monthly.compareTo(upTo) <= 0) {
                            annual = num(b.get("annual"));
                            break;
                        }
                    }
                    int instalments = list(p.get("months")).size();
                    BigDecimal periodic = annual.divide(BigDecimal.valueOf(instalments), MC).divide(toMonthly, MC);
                    if (periodic.signum() > 0) {
                        out.add(new Line("LST", r.name(), "DEDUCTION", "TAX", periodic, in.gross(), null,
                                str(p.get("payableKey")), str(p.get("fallbackPayableKey")), null));
                    }
                }
                case "AE:GPSSA" -> {
                    String nat = in.nationality() == null ? "" : in.nationality().trim().toUpperCase(Locale.ROOT);
                    if (!list(p.get("nationalities")).contains(nat)) {
                        break;
                    }
                    BigDecimal monthly = in.pensionableBase().multiply(toMonthly, MC);
                    BigDecimal min = num(p.get("minBase"));
                    BigDecimal max = num(p.get("maxBase"));
                    if (min != null && monthly.compareTo(min) < 0) {
                        monthly = min;
                    }
                    if (max != null && monthly.compareTo(max) > 0) {
                        monthly = max;
                    }
                    BigDecimal base = monthly.divide(toMonthly, MC);
                    add(out, "GPSSA", r.name() + " (employee)", "DEDUCTION", base, num(p.get("employeeRate")), p);
                    add(out, "GPSSA", r.name() + " (employer)", "EMPLOYER_CONTRIBUTION", base,
                            num(p.get("employerRate")), p);
                    if (num(p.get("governmentRate")) != null) {
                        out.add(new Line("GPSSA", r.name() + " (government)", "INFO", "STATUTORY",
                                base.multiply(num(p.get("governmentRate")), MC), base, num(p.get("governmentRate")),
                                null, null, null));
                    }
                }
                default -> {
                    // EOSB and other non-periodic rules are not payroll lines.
                }
            }
        }
        return out;
    }

    private static void add(List<Line> out, String code, String name, String kind, BigDecimal base, BigDecimal rate,
            Map<String, Object> p) {
        if (rate == null || base.signum() <= 0) {
            return;
        }
        BigDecimal amount = base.multiply(rate, MC);
        out.add(new Line(code, name, kind, "STATUTORY", amount, base, rate, str(p.get("payableKey")),
                str(p.get("fallbackPayableKey")), "EMPLOYER_CONTRIBUTION".equals(kind) ? str(p.get("expenseKey"))
                        : null));
    }

    /** Sum of (band ∩ amount) × rate over {@code [{upTo, rate}]} bands in ascending order; {@code upTo: null} = ∞. */
    public static BigDecimal progressive(List<Object> bands, BigDecimal amount) {
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal lower = BigDecimal.ZERO;
        for (Object o : bands) {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) o;
            BigDecimal upper = num(b.get("upTo"));
            BigDecimal top = upper == null ? amount : amount.min(upper);
            if (top.compareTo(lower) > 0) {
                tax = tax.add(top.subtract(lower).multiply(num(b.get("rate")), MC));
            }
            if (upper == null || amount.compareTo(upper) <= 0) {
                break;
            }
            lower = upper;
        }
        return tax;
    }

    /**
     * End-of-service gratuity on the last basic monthly salary. AE (Decree-Law 33/2021 art. 51): nothing under the
     * minimum service, 21 days' basic per year for the first five years and 30 days after, pro rata for part years,
     * capped at two years' basic. Contractual (other packs): {@code daysPerYear} days per year, no cap.
     */
    public static BigDecimal gratuity(Map<String, Object> eosb, BigDecimal contractualDaysPerYear,
            BigDecimal basicMonthly, LocalDate hire, LocalDate end) {
        if (hire == null || end == null || basicMonthly == null || !end.isAfter(hire)) {
            return BigDecimal.ZERO;
        }
        BigDecimal years = BigDecimal.valueOf(ChronoUnit.DAYS.between(hire, end) + 1)
                .divide(BigDecimal.valueOf(365), MC);
        if (eosb == null) {
            if (contractualDaysPerYear == null || contractualDaysPerYear.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return basicMonthly.divide(BigDecimal.valueOf(30), MC).multiply(contractualDaysPerYear, MC)
                    .multiply(years, MC);
        }
        BigDecimal minYears = numOr(eosb.get("minServiceYears"), BigDecimal.ONE);
        if (years.compareTo(minYears) < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal five = BigDecimal.valueOf(5);
        BigDecimal first = years.min(five);
        BigDecimal after = years.subtract(five).max(BigDecimal.ZERO);
        BigDecimal days = first.multiply(num(eosb.get("daysFirst5Years")), MC)
                .add(after.multiply(num(eosb.get("daysAfter5Years")), MC));
        BigDecimal daily = basicMonthly.divide(numOr(eosb.get("dailyDivisor"), BigDecimal.valueOf(30)), MC);
        BigDecimal amount = daily.multiply(days, MC);
        BigDecimal capYears = num(eosb.get("capYearsOfWage"));
        if (capYears != null) {
            amount = amount.min(basicMonthly.multiply(TWELVE, MC).multiply(capYears, MC));
        }
        return amount;
    }

    public static BigDecimal round(BigDecimal v, int scale) {
        return v == null ? BigDecimal.ZERO : v.setScale(scale, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v) {
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(o instanceof Number n ? (Object) n.intValue() : o);
            }
            return out;
        }
        return List.of();
    }

    static BigDecimal num(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    static BigDecimal numOr(Object v, BigDecimal fallback) {
        BigDecimal n = num(v);
        return n == null ? fallback : n;
    }

    static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
