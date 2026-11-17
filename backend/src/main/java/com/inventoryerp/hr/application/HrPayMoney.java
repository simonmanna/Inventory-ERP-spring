package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Currency-aware payroll rounding (owner decision, 2026-11-17): payroll maths runs at full {@link BigDecimal}
 * precision, and every amount that leaves the calculation (each earning, deduction and employer contribution line of
 * a payroll item) is rounded <b>once</b>, {@code HALF_UP}, to the pay scale when the run's items are stored. Gross,
 * total deductions and net pay are sums of those stored lines (net = Σ earnings − Σ deductions, never rounded on its
 * own), so an item always foots. Payslips, bank payment lines / files and the approval journal then read the same
 * stored values and never round again: in a UGX organisation (0 decimals) every one of them is whole shillings and
 * Σ payslip net = Σ bank lines = the net-pay payable credit, with no rounding line in the journal.
 *
 * <p>The scale comes from {@link HrPayrollSetupService#payScale()}: the functional currency's
 * {@code Currency.decimalPlaces} ({@link #currencyScale}), or the saved {@code roundingScale} when that is coarser.
 */
final class HrPayMoney {

    /** The ledger's scale (numeric(20, 6) columns); a currency is never treated as finer than this. */
    static final int LEDGER_SCALE = 6;

    private HrPayMoney() {}

    /** The one payroll rounding: {@code v} at {@code scale} decimals, {@code HALF_UP} ({@link Money#round}). */
    static BigDecimal round(BigDecimal v, int scale) {
        return Money.round(v == null ? BigDecimal.ZERO : v, scale);
    }

    /** {@code true} when {@code v} carries no more than {@code scale} decimals (it was stored at the pay scale). */
    static boolean onScale(BigDecimal v, int scale) {
        return v == null || v.stripTrailingZeros().scale() <= scale;
    }

    /**
     * A stored amount as plain text for a payment file or payslip: padded to {@code scale} decimals when it fits,
     * otherwise as stored (a run calculated before currency rounding is history and is shown, not re-rounded).
     */
    static String plain(BigDecimal v, int scale) {
        BigDecimal value = v == null ? BigDecimal.ZERO : v;
        return value.setScale(Math.max(scale, Math.max(0, value.stripTrailingZeros().scale()))).toPlainString();
    }

    /**
     * Decimal places of the organisation's functional currency ({@code Organization.currencyCode}): the
     * {@code Currency.decimalPlaces} row (what {@code ForeignCash.assertDecimals} and document VAT rounding use), else
     * the ISO default fraction digits of the code, else 2. Never more than the ledger scale.
     */
    static int currencyScale(JdbcClient jdbc, String organizationId) {
        String code = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(organizationId)
                .query(String.class).optional().orElse(null);
        if (code == null || code.isBlank()) {
            return 2;
        }
        Integer places = jdbc.sql("SELECT \"decimalPlaces\" FROM \"Currency\" WHERE upper(code) = upper(?)")
                .param(code.trim()).query(Integer.class).optional().orElse(null);
        if (places == null) {
            try {
                places = Currency.getInstance(code.trim().toUpperCase()).getDefaultFractionDigits();
            } catch (IllegalArgumentException e) {
                places = 2;
            }
        }
        return places < 0 ? 2 : Math.min(places, LEDGER_SCALE);
    }
}
