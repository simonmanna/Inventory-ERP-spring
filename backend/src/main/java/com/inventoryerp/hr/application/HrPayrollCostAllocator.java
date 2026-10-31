package com.inventoryerp.hr.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits payroll cost across cost centres (HR-5), free of the database. Every employee's shares sum exactly to the
 * employee's cost, and the whole split sums exactly to the journal's salary debit, so the entry stays balanced however
 * the percentages round.
 */
public final class HrPayrollCostAllocator {

    private static final int SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal(100);

    private HrPayrollCostAllocator() {}

    /** One cost centre's share of a target's pay. */
    public record Rule(String costCenterId, BigDecimal percent) {}

    /** An employee's cost in the run. */
    public record Cost(String employeeId, String departmentId, String branchId, BigDecimal amount) {}

    /** The part of an employee's cost charged to one cost centre ({@code costCenterId} null: unallocated). */
    public record Share(String employeeId, String departmentId, String branchId, String costCenterId,
            BigDecimal amount) {
        Share plus(BigDecimal more) {
            return new Share(employeeId, departmentId, branchId, costCenterId, amount.add(more));
        }
    }

    /** One salary debit of the journal. */
    public record Debit(String costCenterId, String branchId, BigDecimal amount) {}

    /** The employee's own rules, else the department's, else one unallocated share. */
    public static List<Share> split(List<Cost> costs, Map<String, List<Rule>> byEmployee,
            Map<String, List<Rule>> byDepartment, BigDecimal total) {
        List<Share> shares = new ArrayList<>();
        for (Cost c : costs) {
            List<Rule> rules = byEmployee.get(c.employeeId());
            if (rules == null && c.departmentId() != null) {
                rules = byDepartment.get(c.departmentId());
            }
            if (rules == null) {
                rules = List.of();
            }
            if (rules.isEmpty()) {
                shares.add(new Share(c.employeeId(), c.departmentId(), c.branchId(), null, c.amount()));
                continue;
            }
            BigDecimal left = c.amount();
            for (int i = 0; i < rules.size(); i++) {
                Rule r = rules.get(i);
                BigDecimal part = i == rules.size() - 1 ? left
                        : c.amount().multiply(r.percent()).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
                left = left.subtract(part);
                shares.add(new Share(c.employeeId(), c.departmentId(), c.branchId(), r.costCenterId(), part));
            }
        }
        return settle(shares, total);
    }

    /** Puts any rounding difference against the journal total on the largest share. */
    private static List<Share> settle(List<Share> shares, BigDecimal total) {
        if (shares.isEmpty()) {
            return shares;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Share s : shares) {
            sum = sum.add(s.amount());
        }
        BigDecimal difference = total.subtract(sum);
        if (difference.signum() == 0) {
            return shares;
        }
        Share largest = shares.stream().max(Comparator.comparing(Share::amount)).orElseThrow();
        List<Share> out = new ArrayList<>(shares);
        out.set(shares.indexOf(largest), largest.plus(difference));
        return out;
    }

    /** Journal debits: one per cost centre and branch, in first-seen order. */
    public static List<Debit> debits(List<Share> shares) {
        Map<List<String>, BigDecimal> grouped = new LinkedHashMap<>();
        for (Share s : shares) {
            List<String> key = new ArrayList<>();
            key.add(s.costCenterId());
            key.add(s.branchId());
            grouped.merge(key, s.amount(), BigDecimal::add);
        }
        List<Debit> out = new ArrayList<>();
        grouped.forEach((key, amount) -> {
            if (amount.signum() > 0) {
                out.add(new Debit(key.get(0), key.get(1), amount));
            }
        });
        return out;
    }
}
