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
    public record NormalizedLine(String accountId, String partnerId, String description, String branchId,
            String costCenterId, Map<String, Object> dimensions, BigDecimal debit, BigDecimal credit,
            BigDecimal baseDebit, BigDecimal baseCredit) {}

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
            out.add(new NormalizedLine(l.accountId(), l.partnerId(), l.description(), l.branchId(), l.costCenterId(),
                    l.dimensions(), debit, credit, r6(debit.multiply(exchangeRate)), r6(credit.multiply(exchangeRate))));
        }
        return out;
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
