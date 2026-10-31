package com.inventoryerp.credit.application;

import java.math.BigDecimal;

/**
 * A module's share of a customer's credit exposure (credit.md §2). Implemented by sales (undelivered, delivered not
 * invoiced), invoicing (receivables, unapplied credits, deposits, draft invoices) and pos (credit sales). Amounts are
 * gross, in the organization currency, never negative.
 */
public interface CreditExposureContributor {

    Contribution contribute(String partnerId);

    /**
     * The same, leaving out {@code excludeDocumentId} (the document being checked, whose amount the check adds itself,
     * so it is never counted twice). Contributors that hold such documents override this.
     */
    default Contribution contribute(String partnerId, String excludeDocumentId) {
        return contribute(partnerId);
    }

    /** Bucket amounts plus the oldest overdue receivable. */
    record Contribution(BigDecimal undelivered, BigDecimal deliveredUninvoiced, BigDecimal receivables, BigDecimal other,
            BigDecimal deposits, BigDecimal credits, BigDecimal overdueAmount, int overdueDays) {

        public static Contribution none() {
            return new Contribution(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        public Contribution plus(Contribution o) {
            return new Contribution(undelivered.add(o.undelivered), deliveredUninvoiced.add(o.deliveredUninvoiced),
                    receivables.add(o.receivables), other.add(o.other), deposits.add(o.deposits), credits.add(o.credits),
                    overdueAmount.add(o.overdueAmount), Math.max(overdueDays, o.overdueDays));
        }
    }
}
