package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Centralized tax engine (port of {@code tax-calculation.service.ts}, ADR-010): exclusive, inclusive and compound
 * output taxes for one line amount; withholding-type taxes are partitioned out and never affect net/gross.
 */
@Service
public class TaxCalculationService {

    /** One tax applied to a line. */
    public record TaxLike(String id, BigDecimal rate, boolean isInclusive, boolean isCompound, String type) {}

    /** Per-tax amount. */
    public record TaxAmount(String taxId, BigDecimal amount) {}

    /** Line result. */
    public record LineTaxResult(BigDecimal net, BigDecimal taxTotal, BigDecimal gross, List<TaxAmount> breakdown,
            BigDecimal withholdingTotal, List<TaxAmount> withholdingBreakdown) {}

    /** decimal.js {@code toDecimalPlaces(dp)} (ROUND_HALF_UP), as {@code money.round}. */
    static BigDecimal round(BigDecimal v, int dp) {
        return v.setScale(dp, RoundingMode.HALF_UP);
    }

    /**
     * {@code scale}: currency decimal places; when given each output-tax amount is rounded to it and an inclusive
     * price keeps the shelf price as gross (net absorbs the rounding). Null keeps the legacy 6 dp behaviour.
     */
    public LineTaxResult computeLine(BigDecimal amount, List<TaxLike> taxes, Integer scale) {
        List<TaxLike> output = new ArrayList<>();
        List<TaxLike> withholding = new ArrayList<>();
        for (TaxLike t : taxes == null ? List.<TaxLike>of() : taxes) {
            if ("withholding".equals(t.type())) {
                withholding.add(t);
            } else {
                output.add(t);
            }
        }
        boolean inclusive = output.stream().anyMatch(TaxLike::isInclusive);
        BigDecimal net;
        if (output.isEmpty()) {
            net = round(amount, 6);
        } else if (inclusive) {
            BigDecimal totalRate = Money.sum(output.stream().map(TaxLike::rate).toList());
            net = Money.div(amount, BigDecimal.ONE.add(Money.div(totalRate, BigDecimal.valueOf(100))));
        } else {
            net = amount;
        }
        BigDecimal taxTotal = Money.ZERO;
        List<TaxAmount> breakdown = new ArrayList<>();
        for (TaxLike t : output) {
            BigDecimal base = t.isCompound() ? net.add(taxTotal) : net;
            BigDecimal amt = round(base.multiply(Money.div(t.rate(), BigDecimal.valueOf(100))), scale != null ? scale : 6);
            taxTotal = taxTotal.add(amt);
            breakdown.add(new TaxAmount(t.id(), amt));
        }
        taxTotal = round(taxTotal, 6);
        net = scale != null && inclusive ? round(amount, 6).subtract(taxTotal) : round(net, 6);
        BigDecimal whTotal = Money.ZERO;
        List<TaxAmount> whBreakdown = new ArrayList<>();
        for (TaxLike t : withholding) {
            // ADR-S071: withholding rounds to the currency like output tax (it is deducted from a payable).
            BigDecimal amt = round(net.multiply(Money.div(t.rate(), BigDecimal.valueOf(100))), scale != null ? scale : 6);
            whTotal = whTotal.add(amt);
            whBreakdown.add(new TaxAmount(t.id(), amt));
        }
        whTotal = round(whTotal, 6);
        return new LineTaxResult(net, taxTotal, net.add(taxTotal), breakdown, whTotal, whBreakdown);
    }
}
