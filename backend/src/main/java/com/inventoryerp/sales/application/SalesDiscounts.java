package com.inventoryerp.sales.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Fixed and whole-document discounts (SG-6), turned into the percent the tax engine works with. */
final class SalesDiscounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SalesDiscounts() {}

    /** A fixed discount on one line as a percent of its gross. */
    static BigDecimal lineAmountToPercent(BigDecimal amount, BigDecimal qty, BigDecimal unit) {
        BigDecimal gross = qty.multiply(unit);
        if (amount.signum() < 0 || amount.compareTo(gross) > 0) {
            throw ApiException.badRequest("discountAmount must be between 0 and the line amount");
        }
        return gross.signum() == 0 ? BigDecimal.ZERO : amount.multiply(HUNDRED).divide(gross, 6, RoundingMode.HALF_UP);
    }

    /** The order-level percent: the percent given, or the amount as a share of what the lines add up to. */
    static BigDecimal orderPercent(BigDecimal percent, BigDecimal amount, BigDecimal net) {
        if (percent != null && percent.signum() > 0) {
            return percent;
        }
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (amount.compareTo(net) > 0) {
            throw ApiException.badRequest("The order discount cannot exceed the order amount");
        }
        return net.signum() == 0 ? BigDecimal.ZERO : amount.multiply(HUNDRED).divide(net, 6, RoundingMode.HALF_UP);
    }

    /** Line discount and order discount together: they compound, so the order discount applies to the net line. */
    static BigDecimal combine(BigDecimal line, BigDecimal order) {
        BigDecimal keep = BigDecimal.ONE.subtract(line.divide(HUNDRED, 10, RoundingMode.HALF_UP))
                .multiply(BigDecimal.ONE.subtract(order.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        return BigDecimal.ONE.subtract(keep).multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    static BigDecimal net(BigDecimal qty, BigDecimal unit, BigDecimal discount) {
        return qty.multiply(unit).multiply(BigDecimal.ONE.subtract(discount.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
    }
}
