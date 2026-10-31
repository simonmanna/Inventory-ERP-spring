package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Actual conversion cost of one work order (ADR-S018), pure: labour hours × labour rate, machine hours × machine rate,
 * energy units × energy rate, overhead hours (machine or labour basis) × overhead rate. Amounts are rounded to 2
 * decimals HALF_UP. A kind the work order already costed through its resource (labour or machine) is left out.
 */
public final class CostAbsorptionCalculator {

    private CostAbsorptionCalculator() {}

    public record Rates(BigDecimal labour, BigDecimal machine, BigDecimal overhead, BigDecimal energy,
            String overheadBasis) {}

    public record Usage(BigDecimal labourHours, BigDecimal machineHours, BigDecimal energyUnits, boolean labourCosted,
            boolean machineCosted) {}

    public record Amount(String kind, BigDecimal basis, BigDecimal rate, BigDecimal amount) {}

    public static List<Amount> amounts(Usage u, Rates r) {
        List<Amount> out = new ArrayList<>();
        if (!u.labourCosted()) {
            add(out, "labour", u.labourHours(), r.labour());
        }
        if (!u.machineCosted()) {
            add(out, "machine", u.machineHours(), r.machine());
        }
        add(out, "utility", u.energyUnits(), r.energy());
        add(out, "overhead", "labour_hours".equals(r.overheadBasis()) ? u.labourHours() : u.machineHours(),
                r.overhead());
        return out;
    }

    private static void add(List<Amount> out, String kind, BigDecimal basis, BigDecimal rate) {
        if (basis == null || rate == null || basis.signum() <= 0 || rate.signum() <= 0) {
            return;
        }
        BigDecimal amount = basis.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() > 0) {
            out.add(new Amount(kind, basis, rate, amount));
        }
    }
}
