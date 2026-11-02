package com.inventoryerp.hr.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Payroll engine v2 (HP-2, ADR-S048): one employee, one period, decimal maths, no I/O. Order of work:
 *
 * <ol>
 *   <li>Proration factor from the employment window and unpaid days, on the organisation's basis (calendar days,
 *       working days or a fixed 30-day month).
 *   <li>Earnings: basic (or hours × hourly rate), overtime, recurring elements (fixed, % of basic, formula), one-off
 *       inputs, retro arrears, then % of gross elements. Lateness is a deduction that also lowers taxable pay.
 *   <li>Element deductions and employer contributions; statutory pack lines on gross / taxable / pensionable pay.
 *   <li>Loans and advances, then deduction inputs, only while net pay stays positive.
 * </ol>
 *
 * Every line is rounded to the pay scale ({@code HALF_UP}); totals are sums of rounded lines, so the journal balances
 * to the cent.
 */
public final class HrPayrollCalculator {

    static final MathContext MC = MathContext.DECIMAL64;

    /** A one-off input (earning or deduction) queued for the employee. */
    public record Input(String id, String kind, String name, BigDecimal amount, boolean taxable) {}

    /** A loan or advance instalment candidate. */
    public record Recovery(String id, String type, String code, BigDecimal planned, BigDecimal balance) {}

    /** Everything about one employee and period. Money per period unless named monthly. */
    public record Context(
            LocalDate periodStart, LocalDate periodEnd, int periodsPerYear,
            String prorationBasis, int scale, BigDecimal hoursPerDay, Set<DayOfWeek> weekend,
            Predicate<LocalDate> holiday,
            LocalDate hireDate, LocalDate terminationDate,
            BigDecimal basicMonthly, boolean hourlyPaid, BigDecimal hourlyRate, BigDecimal workedHours,
            BigDecimal overtimeWeightedHours, BigDecimal overtimeHours,
            BigDecimal unpaidDays, BigDecimal lateMinutes, boolean lateDeduction,
            List<HrCompensationService.Element> elements, List<Input> inputs, BigDecimal retroArrears,
            String statutoryPack, List<HrStatutory.Rate> rates, String nationality,
            List<Recovery> recoveries) {}

    /** One result line. */
    public record Line(String code, String name, String kind, String category, String statutoryCode,
            BigDecimal amount, BigDecimal base, BigDecimal rate, BigDecimal quantity, boolean taxable,
            String glAccountId, String payableKey, String fallbackPayableKey, String expenseKey, String sourceId,
            int sortOrder) {}

    /** The result for one employee. */
    public record Result(List<Line> lines, BigDecimal basic, BigDecimal overtimePay, BigDecimal gross,
            BigDecimal taxableGross, BigDecimal tax, BigDecimal pension, BigDecimal socialSecurity,
            BigDecimal otherDeductions, BigDecimal loanDeduction, BigDecimal advanceDeduction,
            BigDecimal inputDeductions, BigDecimal totalDeductions, BigDecimal netPay, BigDecimal employerCost,
            BigDecimal prorationFactor, BigDecimal daysPaid, List<String> usedInputIds) {}

    private HrPayrollCalculator() {}

    public static Result calculate(Context c) {
        int scale = c.scale();
        List<Line> lines = new ArrayList<>();
        BigDecimal periodBasic = c.basicMonthly().multiply(BigDecimal.valueOf(12), MC)
                .divide(BigDecimal.valueOf(c.periodsPerYear()), MC);

        // 1. proration
        LocalDate from = c.hireDate() != null && c.hireDate().isAfter(c.periodStart()) ? c.hireDate()
                : c.periodStart();
        LocalDate to = c.terminationDate() != null && c.terminationDate().isBefore(c.periodEnd())
                ? c.terminationDate() : c.periodEnd();
        BigDecimal calendarDays = BigDecimal.valueOf(ChronoUnit.DAYS.between(c.periodStart(), c.periodEnd()) + 1);
        BigDecimal employedCalendar = to.isBefore(from) ? BigDecimal.ZERO
                : BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1);
        BigDecimal workingDays = BigDecimal.valueOf(workingDays(c.periodStart(), c.periodEnd(), c));
        BigDecimal employedWorking = to.isBefore(from) ? BigDecimal.ZERO
                : BigDecimal.valueOf(workingDays(from, to, c));
        BigDecimal unpaid = nz(c.unpaidDays());
        BigDecimal divisor;
        BigDecimal paidDays;
        switch (c.prorationBasis()) {
            case "WORKING_DAYS" -> {
                divisor = workingDays;
                paidDays = employedWorking.subtract(unpaid);
            }
            case "FIXED_30" -> {
                divisor = BigDecimal.valueOf(30);
                BigDecimal employed = employedCalendar.compareTo(calendarDays) >= 0 ? divisor
                        : employedCalendar.min(divisor);
                paidDays = employed.subtract(unpaid);
            }
            default -> {
                divisor = calendarDays;
                paidDays = employedCalendar.subtract(unpaid);
            }
        }
        paidDays = paidDays.max(BigDecimal.ZERO).min(divisor);
        BigDecimal factor = divisor.signum() == 0 ? BigDecimal.ZERO : paidDays.divide(divisor, MC);
        int workdaysPerWeek = Math.max(1, 7 - c.weekend().size());
        // Daily and hourly rates from the monthly basic: days a month on the basis, hours from the day length.
        BigDecimal monthlyDays = switch (c.prorationBasis()) {
            case "FIXED_30" -> BigDecimal.valueOf(30);
            case "WORKING_DAYS" -> BigDecimal.valueOf(workdaysPerWeek * 52L).divide(BigDecimal.valueOf(12), MC);
            default -> BigDecimal.valueOf(365).divide(BigDecimal.valueOf(12), MC);
        };
        BigDecimal dailyRate = c.basicMonthly().divide(monthlyDays, MC);
        BigDecimal hourlyRate = c.hourlyRate() != null && c.hourlyRate().signum() > 0 ? c.hourlyRate()
                : c.basicMonthly().multiply(BigDecimal.valueOf(12), MC)
                        .divide(BigDecimal.valueOf(52L * workdaysPerWeek).multiply(c.hoursPerDay(), MC), MC);

        // 2. earnings
        BigDecimal basic;
        if (c.hourlyPaid()) {
            basic = r(nz(c.workedHours()).multiply(hourlyRate, MC), scale);
            lines.add(line("BASIC", "Basic pay (hours)", "EARNING", "BASIC", basic, hourlyRate, null,
                    nz(c.workedHours()), true, 0));
        } else {
            basic = r(periodBasic.multiply(factor, MC), scale);
            lines.add(line("BASIC", "Basic salary", "EARNING", "BASIC", basic, periodBasic, factor, paidDays, true,
                    0));
        }
        BigDecimal overtimePay = r(nz(c.overtimeWeightedHours()).multiply(hourlyRate, MC), scale);
        if (overtimePay.signum() > 0) {
            lines.add(line("OVERTIME", "Overtime", "EARNING", "OVERTIME", overtimePay, hourlyRate, null,
                    nz(c.overtimeHours()), true, 10));
        }
        Map<String, BigDecimal> vars = new HashMap<>();
        vars.put("BASIC", basic);
        vars.put("DAYS_IN_PERIOD", calendarDays);
        vars.put("DAYS_PAID", paidDays);
        vars.put("WORKING_DAYS", workingDays);
        vars.put("WORKING_DAYS_PAID", employedWorking.subtract(unpaid).max(BigDecimal.ZERO));
        vars.put("HOURS", nz(c.workedHours()));
        vars.put("OT_HOURS", nz(c.overtimeHours()));
        vars.put("HOURLY_RATE", hourlyRate);
        vars.put("DAILY_RATE", dailyRate);
        vars.put("PRORATION", factor);
        long months = c.hireDate() == null ? 0 : ChronoUnit.MONTHS.between(c.hireDate(), c.periodEnd());
        vars.put("MONTHS_OF_SERVICE", BigDecimal.valueOf(Math.max(0, months)));
        vars.put("YEARS_OF_SERVICE", BigDecimal.valueOf(Math.max(0, months)).divide(BigDecimal.valueOf(12), MC));

        BigDecimal pensionable = basic;
        List<HrCompensationService.Element> grossPct = new ArrayList<>();
        for (HrCompensationService.Element e : c.elements()) {
            if (!"EARNING".equals(e.kind())) {
                continue;
            }
            if ("PERCENT_OF_GROSS".equals(e.calcMethod())) {
                grossPct.add(e);
                continue;
            }
            BigDecimal amount = r(value(e, basic, null, vars, factor), scale);
            if (amount.signum() != 0) {
                lines.add(elementLine(e, amount));
                if (e.pensionable()) {
                    pensionable = pensionable.add(amount);
                }
            }
        }
        List<String> used = new ArrayList<>();
        for (Input in : c.inputs()) {
            if ("earning".equals(in.kind())) {
                BigDecimal amount = r(in.amount(), scale);
                lines.add(new Line("INPUT", in.name(), "EARNING", "INPUT", null, amount, null, null, null,
                        in.taxable(), null, null, null, null, in.id(), 50));
                used.add(in.id());
            }
        }
        if (c.retroArrears() != null && c.retroArrears().signum() != 0) {
            BigDecimal amount = r(c.retroArrears(), scale);
            lines.add(line("RETRO", "Salary arrears", "EARNING", "RETRO", amount, null, null, null, true, 60));
            pensionable = pensionable.add(amount);
        }
        BigDecimal grossSoFar = sum(lines, "EARNING", null);
        vars.put("GROSS", grossSoFar);
        for (HrCompensationService.Element e : grossPct) {
            BigDecimal amount = r(value(e, basic, grossSoFar, vars, factor), scale);
            if (amount.signum() != 0) {
                lines.add(elementLine(e, amount));
                if (e.pensionable()) {
                    pensionable = pensionable.add(amount);
                }
            }
        }
        BigDecimal gross = sum(lines, "EARNING", null);
        BigDecimal taxable = sumTaxable(lines);
        BigDecimal late = BigDecimal.ZERO;
        if (c.lateDeduction() && nz(c.lateMinutes()).signum() > 0) {
            late = r(c.lateMinutes().divide(BigDecimal.valueOf(60), MC).multiply(hourlyRate, MC), scale).min(gross);
            if (late.signum() > 0) {
                lines.add(line("LATE", "Lateness", "DEDUCTION", "LATE", late, hourlyRate, null, c.lateMinutes(),
                        false, 200));
                taxable = taxable.subtract(late).max(BigDecimal.ZERO);
            }
        }
        vars.put("GROSS", gross);
        vars.put("TAXABLE_GROSS", taxable);

        // 3. element deductions / employer contributions, statutory pack
        for (HrCompensationService.Element e : c.elements()) {
            if (!"DEDUCTION".equals(e.kind()) && !"EMPLOYER_CONTRIBUTION".equals(e.kind())) {
                continue;
            }
            BigDecimal amount = r(value(e, basic, gross, vars, factor), scale);
            if (amount.signum() > 0) {
                lines.add(elementLine(e, amount));
            }
        }
        for (HrStatutory.Line s : HrStatutory.compute(c.statutoryPack(), c.rates(),
                new HrStatutory.Input(gross, taxable, pensionable, c.periodsPerYear(), c.periodEnd(),
                        c.nationality()))) {
            BigDecimal amount = r(s.amount(), scale);
            if (amount.signum() > 0) {
                lines.add(new Line(s.code(), s.name(), s.kind(), s.category(), s.code(), amount, s.base(), s.rate(),
                        null, false, null, s.payableKey(), s.fallbackPayableKey(), s.expenseKey(), null,
                        "TAX".equals(s.category()) ? 300 : 310));
            }
        }

        // 4. recoveries and deduction inputs, while net stays positive
        BigDecimal available = gross.subtract(sum(lines, "DEDUCTION", null));
        BigDecimal loans = BigDecimal.ZERO;
        BigDecimal advances = BigDecimal.ZERO;
        for (Recovery rec : c.recoveries()) {
            if (available.signum() <= 0) {
                break;
            }
            BigDecimal amount = r(rec.planned().min(rec.balance()).min(available), scale);
            if (amount.signum() <= 0) {
                continue;
            }
            available = available.subtract(amount);
            boolean loan = "LOAN".equals(rec.type());
            if (loan) {
                loans = loans.add(amount);
            } else {
                advances = advances.add(amount);
            }
            lines.add(new Line(rec.type(), (loan ? "Loan " : "Advance ") + rec.code(), "DEDUCTION", rec.type(), null,
                    amount, rec.balance(), null, null, false, null, null, null, null, rec.id(), 400));
        }
        BigDecimal inputDed = BigDecimal.ZERO;
        for (Input in : c.inputs()) {
            if (!"deduction".equals(in.kind())) {
                continue;
            }
            BigDecimal amount = r(in.amount(), scale);
            if (amount.compareTo(available) > 0) {
                continue;
            }
            available = available.subtract(amount);
            inputDed = inputDed.add(amount);
            used.add(in.id());
            lines.add(new Line("INPUT_DEDUCTION", in.name(), "DEDUCTION", "INPUT_DEDUCTION", null, amount, null, null,
                    null, false, null, null, null, null, in.id(), 450));
        }

        BigDecimal tax = sumCategory(lines, "TAX");
        BigDecimal pension = BigDecimal.ZERO;
        BigDecimal social = BigDecimal.ZERO;
        for (Line l : lines) {
            if ("DEDUCTION".equals(l.kind()) && "STATUTORY".equals(l.category())) {
                if ("pension_payable".equals(l.payableKey())) {
                    pension = pension.add(l.amount());
                } else {
                    social = social.add(l.amount());
                }
            }
        }
        BigDecimal totalDed = sum(lines, "DEDUCTION", null);
        BigDecimal other = totalDed.subtract(tax).subtract(pension).subtract(social).subtract(loans)
                .subtract(advances).subtract(inputDed);
        BigDecimal employer = sum(lines, "EMPLOYER_CONTRIBUTION", null);
        lines.sort(java.util.Comparator.comparingInt(Line::sortOrder));
        return new Result(lines, basic, overtimePay, gross, taxable, tax, pension, social, other, loans, advances,
                inputDed, totalDed, gross.subtract(totalDed), employer, factor, paidDays, used);
    }

    /** Working days between two dates inclusive: weekend days and holidays excluded. */
    static long workingDays(LocalDate from, LocalDate to, Context c) {
        return workingDays(from, to, c.weekend(), c.holiday());
    }

    public static long workingDays(LocalDate from, LocalDate to, Set<DayOfWeek> weekend,
            Predicate<LocalDate> holiday) {
        long n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!weekend.contains(d.getDayOfWeek()) && !holiday.test(d)) {
                n++;
            }
        }
        return n;
    }

    private static BigDecimal value(HrCompensationService.Element e, BigDecimal basic, BigDecimal gross,
            Map<String, BigDecimal> vars, BigDecimal factor) {
        BigDecimal pct = e.rate() == null ? BigDecimal.ZERO : e.rate().divide(BigDecimal.valueOf(100), MC);
        return switch (e.calcMethod()) {
            case "PERCENT_OF_BASIC" -> basic.multiply(pct, MC);
            case "PERCENT_OF_GROSS" -> nz(gross).multiply(pct, MC);
            case "FORMULA" -> HrFormula.eval(e.formula(), vars);
            default -> {
                BigDecimal amount = nz(e.amount());
                yield e.prorated() ? amount.multiply(factor, MC) : amount;
            }
        };
    }

    private static Line elementLine(HrCompensationService.Element e, BigDecimal amount) {
        return new Line(e.code(), e.name(), e.kind(), "ELEMENT", null, amount, null, e.rate(), null,
                "EARNING".equals(e.kind()) && e.taxable(), e.glAccountId(), null, null, null, e.elementId(),
                20 + e.sortOrder());
    }

    private static Line line(String code, String name, String kind, String category, BigDecimal amount,
            BigDecimal base, BigDecimal rate, BigDecimal qty, boolean taxable, int sort) {
        return new Line(code, name, kind, category, null, amount, base, rate, qty, taxable, null, null, null, null,
                null, sort);
    }

    private static BigDecimal sum(List<Line> lines, String kind, String category) {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.kind().equals(kind) && (category == null || category.equals(l.category()))) {
                s = s.add(l.amount());
            }
        }
        return s;
    }

    private static BigDecimal sumCategory(List<Line> lines, String category) {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) {
            if ("DEDUCTION".equals(l.kind()) && category.equals(l.category())) {
                s = s.add(l.amount());
            }
        }
        return s;
    }

    private static BigDecimal sumTaxable(List<Line> lines) {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) {
            if ("EARNING".equals(l.kind()) && l.taxable()) {
                s = s.add(l.amount());
            }
        }
        return s;
    }

    static BigDecimal r(BigDecimal v, int scale) {
        return v.setScale(scale, RoundingMode.HALF_UP);
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
