package com.inventoryerp.accounting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Finance readiness audit, integrity check I10 (gate G1): the fixed-asset register against the general ledger. The
 * register is the cost with revaluations, and the posted depreciation plus net impairment, of the assets held at the
 * date; the ledger is the fixed-asset cost accounts and the accumulated depreciation / impairment accounts.
 *
 * <p>Implemented by the reporting module over its {@code fa.reconciliation} report, so the check and the report measure
 * the same thing. A port here because the reporting module already depends on this one.
 */
public interface FixedAssetTieOut {

    /** Amount tolerance of each line. */
    BigDecimal TOLERANCE = new BigDecimal("0.01");

    /**
     * One line of the reconciliation, base currency.
     *
     * @param line     {@code COST} (cost with revaluations) or {@code ACC} (accumulated depreciation and impairment)
     * @param register the register's figure
     * @param gl       the balance of the line's accounts (cost debit-normal, accumulated credit-normal)
     */
    record Line(String line, String label, BigDecimal register, BigDecimal gl) {

        /** Register less ledger, as the reconciliation report shows it. */
        public BigDecimal difference() {
            return register.subtract(gl);
        }

        /** The line ties within {@link #TOLERANCE}. */
        public boolean ties() {
            return difference().abs().compareTo(TOLERANCE) <= 0;
        }
    }

    /** The cost and accumulated lines of the current organisation (tenant context) at the end of {@code asOf}. */
    List<Line> measure(LocalDate asOf);
}
