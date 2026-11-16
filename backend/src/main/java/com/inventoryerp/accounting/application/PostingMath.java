package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure double-entry math (port of {@code posting.math.ts}). */
public final class PostingMath {

    private PostingMath() {}

    /** Normalized line with functional-currency (base) amounts at 6 dp. */
    /**
     * A validated line in the entry's currency and in base. {@code currencyId} / {@code foreignAmount} are the line's own
     * transaction currency and amount when it overrides the entry's ({@link PostingRequest.Line#inCurrency});
     * {@link #BASE_ONLY} as {@code currencyId} stores the line in base with no currency (the rounding line).
     */
    public record NormalizedLine(String accountId, String partnerId, String description, String branchId,
            String costCenterId, Map<String, Object> dimensions, BigDecimal debit, BigDecimal credit,
            BigDecimal baseDebit, BigDecimal baseCredit, String currencyId, BigDecimal foreignAmount) {

        public NormalizedLine(String accountId, String partnerId, String description, String branchId,
                String costCenterId, Map<String, Object> dimensions, BigDecimal debit, BigDecimal credit,
                BigDecimal baseDebit, BigDecimal baseCredit) {
            this(accountId, partnerId, description, branchId, costCenterId, dimensions, debit, credit, baseDebit,
                    baseCredit, null, null);
        }

        /** Whether the line states its own currency (or base-only) instead of the entry's. */
        public boolean overridesCurrency() {
            return currencyId != null;
        }
    }

    /** {@link NormalizedLine#currencyId()} marker: stored in base currency, no transaction currency. */
    public static final String BASE_ONLY = "";

    public record Totals(BigDecimal debit, BigDecimal credit) {}

    public record LineValidationError(int index, String message) {}

    private static BigDecimal r6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    public static List<NormalizedLine> normalizeLines(List<PostingRequest.Line> lines, BigDecimal exchangeRate) {
        List<NormalizedLine> out = new ArrayList<>(lines.size());
        for (PostingRequest.Line l : lines) {
            BigDecimal debit = r6(Money.dec(l.debit() == null ? 0 : l.debit()));
            BigDecimal credit = r6(Money.dec(l.credit() == null ? 0 : l.credit()));
            BigDecimal foreign = l.currencyId() == null || l.currencyId().isBlank() || l.foreignAmount() == null ? null
                    : r6(Money.dec(l.foreignAmount()).abs());
            out.add(new NormalizedLine(l.accountId(), l.partnerId(), l.description(), l.branchId(), l.costCenterId(),
                    l.dimensions(), debit, credit, r6(debit.multiply(exchangeRate)), r6(credit.multiply(exchangeRate)),
                    foreign == null ? null : l.currencyId(), foreign));
        }
        return out;
    }

    /** Totals in the entry's transaction currency (lines with their own currency left out). */
    public static Totals transactionTotals(List<NormalizedLine> lines) {
        BigDecimal d = BigDecimal.ZERO;
        BigDecimal c = BigDecimal.ZERO;
        for (NormalizedLine l : lines) {
            if (!l.overridesCurrency()) {
                d = d.add(l.debit());
                c = c.add(l.credit());
            }
        }
        return new Totals(d, c);
    }

    public static Totals totals(List<NormalizedLine> lines) {
        BigDecimal d = BigDecimal.ZERO;
        BigDecimal c = BigDecimal.ZERO;
        for (NormalizedLine l : lines) {
            d = d.add(l.baseDebit());
            c = c.add(l.baseCredit());
        }
        return new Totals(d, c);
    }

    /** Balanced on the functional (base) currency within 0.0001. */
    public static boolean isBalanced(List<NormalizedLine> lines) {
        Totals t = totals(lines);
        return Money.approxEqual(t.debit(), t.credit());
    }

    public static List<LineValidationError> validateLines(List<NormalizedLine> lines) {
        List<LineValidationError> errors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            NormalizedLine l = lines.get(i);
            if (l.debit().signum() > 0 && l.credit().signum() > 0) {
                errors.add(new LineValidationError(i, "A line cannot have both a debit and a credit"));
            }
            if (l.debit().signum() == 0 && l.credit().signum() == 0) {
                errors.add(new LineValidationError(i, "A line must have either a debit or a credit"));
            }
            if (l.debit().signum() < 0 || l.credit().signum() < 0) {
                errors.add(new LineValidationError(i, "Line amounts cannot be negative"));
            }
        }
        return errors;
    }
}
