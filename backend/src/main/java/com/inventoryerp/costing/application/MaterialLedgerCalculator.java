package com.inventoryerp.costing.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Periodic actual costing of one standard-costed product (ADR-S030 §1), pure. The variance pool — what was carried in
 * from the previous run plus the variances booked in the period — is spread over the quantity available in the period
 * (opening + receipts): the share of the quantity still on hand stays in inventory, the rest belongs to consumption.
 * Amounts are rounded to 2 decimals; the consumption share takes the rounding so the two always add up to the pool.
 */
public final class MaterialLedgerCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /** Inputs of one product: quantities, the standard unit cost and the variances (positive = unfavourable). */
    public record Input(BigDecimal openingQty, BigDecimal carriedVariance, BigDecimal receiptQty,
            BigDecimal periodVariance, BigDecimal consumedQty, BigDecimal standardCost) {}

    /**
     * Result: ending quantity, pool, shares, the periodic actual unit cost and the change to post ({@code toInventory −
     * carriedVariance}).
     */
    public record Result(BigDecimal endingQty, BigDecimal pool, BigDecimal toInventory, BigDecimal toConsumption,
            BigDecimal periodicUnitCost, BigDecimal delta) {}

    private MaterialLedgerCalculator() {}

    public static Result compute(Input in) {
        BigDecimal opening = nz(in.openingQty());
        BigDecimal receipts = nz(in.receiptQty());
        BigDecimal consumed = nz(in.consumedQty());
        BigDecimal carried = nz(in.carriedVariance()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pool = carried.add(nz(in.periodVariance())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal available = opening.add(receipts);
        BigDecimal ending = available.subtract(consumed);
        BigDecimal standard = nz(in.standardCost());
        BigDecimal toInventory;
        BigDecimal unit;
        if (available.signum() <= 0 || ending.signum() <= 0) {
            toInventory = BigDecimal.ZERO.setScale(2);
            unit = available.signum() > 0 ? standard.add(pool.divide(available, MC)) : standard;
        } else {
            BigDecimal share = ending.min(available).divide(available, MC);
            toInventory = pool.multiply(share, MC).setScale(2, RoundingMode.HALF_UP);
            unit = standard.add(pool.divide(available, MC));
        }
        BigDecimal toConsumption = pool.subtract(toInventory);
        return new Result(ending, pool, toInventory, toConsumption, unit.setScale(6, RoundingMode.HALF_UP),
                toInventory.subtract(carried));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
