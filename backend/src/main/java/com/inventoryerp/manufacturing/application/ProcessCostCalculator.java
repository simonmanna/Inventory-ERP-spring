package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process costing for one process and period (ADR-S008 §2): equivalent units and cost per equivalent unit by cost
 * element (transferred-in, material, conversion = labour + overhead), then the cost of units completed, closing WIP
 * and abnormal loss. Normal loss carries no equivalent units (its cost is absorbed by the good output); abnormal
 * loss is costed like output at its own stage of completion and written off.
 *
 * <ul>
 *   <li><b>Weighted average</b>: opening WIP cost is pooled with the period's costs; EU = completed + closing WIP ×
 *       % + abnormal × %.</li>
 *   <li><b>FIFO</b>: only the period's costs are spread; EU = opening WIP × (1 − % done) + started-and-completed +
 *       closing WIP × % + abnormal × %; units completed carry the opening WIP cost first.</li>
 * </ul>
 * Physical units must balance: opening + started = completed + closing + normal loss + abnormal loss. Money is
 * 6 dp HALF_UP; closing WIP takes the rounding remainder so completed + closing + abnormal = total cost exactly.
 */
public final class ProcessCostCalculator {

    private static final int SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Units and completion percentages (0–100) of the period, and its costs by element. */
    public record Input(String method, BigDecimal openingUnits, BigDecimal openingMaterialPct,
            BigDecimal openingConversionPct, BigDecimal openingTransferredInCost, BigDecimal openingMaterialCost,
            BigDecimal openingConversionCost, BigDecimal unitsStarted, BigDecimal unitsCompleted,
            BigDecimal closingUnits, BigDecimal closingMaterialPct, BigDecimal closingConversionPct,
            BigDecimal normalLossUnits, BigDecimal abnormalLossUnits, BigDecimal abnormalMaterialPct,
            BigDecimal abnormalConversionPct, BigDecimal transferredInCost, BigDecimal materialCost,
            BigDecimal conversionCost) {}

    /** Result: EU, cost per EU and the three cost destinations. */
    public record Result(Map<String, BigDecimal> equivalentUnits, Map<String, BigDecimal> costPerUnit,
            BigDecimal totalCost, BigDecimal completedCost, BigDecimal closingWipCost, BigDecimal abnormalLossCost,
            BigDecimal unitCost, Map<String, BigDecimal> closingWipByElement) {

        /** JSON-friendly view. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("equivalentUnits", plain(equivalentUnits));
            m.put("costPerEquivalentUnit", plain(costPerUnit));
            m.put("totalCost", completedCost.add(closingWipCost).add(abnormalLossCost).toPlainString());
            m.put("completedCost", completedCost.toPlainString());
            m.put("closingWipCost", closingWipCost.toPlainString());
            m.put("abnormalLossCost", abnormalLossCost.toPlainString());
            m.put("unitCost", unitCost.toPlainString());
            m.put("closingWipByElement", plain(closingWipByElement));
            return m;
        }

        private static Map<String, String> plain(Map<String, BigDecimal> in) {
            Map<String, String> out = new LinkedHashMap<>();
            in.forEach((k, v) -> out.put(k, v.stripTrailingZeros().toPlainString()));
            return out;
        }
    }

    private ProcessCostCalculator() {}

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal pct(BigDecimal v) {
        BigDecimal p = z(v);
        if (p.signum() < 0 || p.compareTo(HUNDRED) > 0) {
            throw ApiException.badRequest("Completion percentages must be between 0 and 100");
        }
        return p.divide(HUNDRED, 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal per(BigDecimal cost, BigDecimal eu) {
        return eu.signum() == 0 ? BigDecimal.ZERO : cost.divide(eu, 10, RoundingMode.HALF_UP);
    }

    public static Result compute(Input in) {
        boolean fifo = "fifo".equals(in.method());
        BigDecimal open = z(in.openingUnits());
        BigDecimal started = z(in.unitsStarted());
        BigDecimal done = z(in.unitsCompleted());
        BigDecimal close = z(in.closingUnits());
        BigDecimal normal = z(in.normalLossUnits());
        BigDecimal abnormal = z(in.abnormalLossUnits());
        for (BigDecimal u : new BigDecimal[] {open, started, done, close, normal, abnormal}) {
            if (u.signum() < 0) {
                throw ApiException.badRequest("Unit quantities must not be negative");
            }
        }
        BigDecimal in1 = open.add(started);
        BigDecimal out1 = done.add(close).add(normal).add(abnormal);
        if (in1.compareTo(out1) != 0) {
            throw ApiException.badRequest("Units do not balance: opening " + open.stripTrailingZeros().toPlainString()
                    + " + started " + started.stripTrailingZeros().toPlainString() + " ≠ completed "
                    + done.stripTrailingZeros().toPlainString() + " + closing " + close.stripTrailingZeros().toPlainString()
                    + " + normal loss " + normal.stripTrailingZeros().toPlainString() + " + abnormal loss "
                    + abnormal.stripTrailingZeros().toPlainString());
        }
        if (fifo && done.compareTo(open) < 0) {
            throw ApiException.badRequest("FIFO process costing needs the opening WIP to be completed first"
                    + " (completed < opening units)");
        }
        BigDecimal om = pct(in.openingMaterialPct());
        BigDecimal oc = pct(in.openingConversionPct());
        BigDecimal cm = pct(in.closingMaterialPct());
        BigDecimal cc = pct(in.closingConversionPct());
        BigDecimal am = pct(in.abnormalMaterialPct() == null ? HUNDRED : in.abnormalMaterialPct());
        BigDecimal ac = pct(in.abnormalConversionPct() == null ? HUNDRED : in.abnormalConversionPct());

        BigDecimal openT = z(in.openingTransferredInCost());
        BigDecimal openM = z(in.openingMaterialCost());
        BigDecimal openC = z(in.openingConversionCost());
        BigDecimal addT = z(in.transferredInCost());
        BigDecimal addM = z(in.materialCost());
        BigDecimal addC = z(in.conversionCost());
        BigDecimal total = openT.add(openM).add(openC).add(addT).add(addM).add(addC);

        BigDecimal startedCompleted = done.subtract(open);
        BigDecimal euT;
        BigDecimal euM;
        BigDecimal euC;
        if (fifo) {
            euT = startedCompleted.add(close).add(abnormal);
            euM = open.multiply(BigDecimal.ONE.subtract(om)).add(startedCompleted).add(close.multiply(cm))
                    .add(abnormal.multiply(am));
            euC = open.multiply(BigDecimal.ONE.subtract(oc)).add(startedCompleted).add(close.multiply(cc))
                    .add(abnormal.multiply(ac));
        } else {
            euT = done.add(close).add(abnormal);
            euM = done.add(close.multiply(cm)).add(abnormal.multiply(am));
            euC = done.add(close.multiply(cc)).add(abnormal.multiply(ac));
        }
        BigDecimal pT = per(fifo ? addT : openT.add(addT), euT);
        BigDecimal pM = per(fifo ? addM : openM.add(addM), euM);
        BigDecimal pC = per(fifo ? addC : openC.add(addC), euC);
        BigDecimal pAll = pT.add(pM).add(pC);

        BigDecimal abnormalCost = money(abnormal.multiply(pT).add(abnormal.multiply(am).multiply(pM))
                .add(abnormal.multiply(ac).multiply(pC)));
        BigDecimal completedCost;
        if (fifo) {
            BigDecimal finishOpening = open.multiply(BigDecimal.ONE.subtract(om)).multiply(pM)
                    .add(open.multiply(BigDecimal.ONE.subtract(oc)).multiply(pC));
            completedCost = money(openT.add(openM).add(openC).add(finishOpening).add(startedCompleted.multiply(pAll)));
        } else {
            completedCost = money(done.multiply(pAll));
        }
        BigDecimal closingT = money(close.multiply(pT));
        BigDecimal closingM = money(close.multiply(cm).multiply(pM));
        BigDecimal closingC = money(close.multiply(cc).multiply(pC));
        // Closing WIP absorbs rounding so the three destinations equal the total cost exactly.
        BigDecimal closingWip = money(total).subtract(completedCost).subtract(abnormalCost);
        BigDecimal roundingDiff = closingWip.subtract(closingT.add(closingM).add(closingC));
        closingC = closingC.add(roundingDiff);
        if (close.signum() == 0 && closingWip.signum() != 0) {
            // No closing WIP: a rounding residue belongs to the output.
            completedCost = completedCost.add(closingWip);
            closingWip = BigDecimal.ZERO;
            closingT = BigDecimal.ZERO;
            closingM = BigDecimal.ZERO;
            closingC = BigDecimal.ZERO;
        }
        BigDecimal unitCost = done.signum() == 0 ? BigDecimal.ZERO : completedCost.divide(done, SCALE,
                RoundingMode.HALF_UP);

        Map<String, BigDecimal> eu = new LinkedHashMap<>();
        eu.put("transferredIn", euT.setScale(6, RoundingMode.HALF_UP));
        eu.put("material", euM.setScale(6, RoundingMode.HALF_UP));
        eu.put("conversion", euC.setScale(6, RoundingMode.HALF_UP));
        Map<String, BigDecimal> cpu = new LinkedHashMap<>();
        cpu.put("transferredIn", pT.setScale(6, RoundingMode.HALF_UP));
        cpu.put("material", pM.setScale(6, RoundingMode.HALF_UP));
        cpu.put("conversion", pC.setScale(6, RoundingMode.HALF_UP));
        cpu.put("total", pAll.setScale(6, RoundingMode.HALF_UP));
        Map<String, BigDecimal> wip = new LinkedHashMap<>();
        wip.put("transferredIn", closingT);
        wip.put("material", closingM);
        wip.put("conversion", closingC);
        return new Result(eu, cpu, money(total), completedCost, closingWip, abnormalCost, unitCost, wip);
    }
}
