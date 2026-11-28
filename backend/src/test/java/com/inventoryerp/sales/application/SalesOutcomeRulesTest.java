package com.inventoryerp.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S094 §4.4: the payment, credit and settlement rules on computed amounts (pure, no Spring context). */
class SalesOutcomeRulesTest {

    static BigDecimal m(double v) {
        return BigDecimal.valueOf(v).setScale(2);
    }

    /**
     * status, total, creditKept, invoiced, invoiceOpen, credited, writtenOff, creditOpen, refunded, elsewhere, advance.
     */
    static SalesOutcomeService.Outcome of(String status, double total, double kept, double invoiced, double invoiceOpen,
            double credited, double writtenOff, double creditOpen, double refunded, double elsewhere, double advance) {
        return SalesOutcomeService.outcome(status, m(total), m(kept), m(invoiced), m(invoiceOpen), m(credited),
                m(writtenOff), m(creditOpen), m(refunded), m(elsewhere), m(advance));
    }

    @Test
    @DisplayName("nothing invoiced: not invoiced while it may still bill, no charge for a zero-value or finished sale")
    void nothingInvoiced() {
        SalesOutcomeService.Outcome o = of("confirmed", 1180, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(o.paymentStatus()).isEqualTo("not_invoiced");
        assertThat(o.settlementStatus()).isEqualTo("open");
        assertThat(of("confirmed", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).paymentStatus()).isEqualTo("no_charge");
        assertThat(of("confirmed", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).settlementStatus()).isEqualTo("settled");
        assertThat(of("done", 1180, 0, 0, 0, 0, 0, 0, 0, 0, 0).paymentStatus()).isEqualTo("no_charge");
        assertThat(of("draft", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).settlementStatus()).as("a draft is never settled")
                .isEqualTo("open");
    }

    @Test
    @DisplayName("receipts: unpaid, partially paid, paid; an advance still held keeps the sale open")
    void receipts() {
        assertThat(of("done", 1180, 0, 1180, 1180, 0, 0, 0, 0, 0, 0).paymentStatus()).isEqualTo("unpaid");
        SalesOutcomeService.Outcome part = of("done", 1180, 0, 1180, 780, 0, 0, 0, 0, 0, 0);
        assertThat(part.paymentStatus()).isEqualTo("partially_paid");
        assertThat(part.received()).isEqualByComparingTo("400");
        assertThat(part.outstanding()).isEqualByComparingTo("780");
        SalesOutcomeService.Outcome paid = of("done", 1180, 0, 1180, 0, 0, 0, 0, 0, 0, 0);
        assertThat(paid.paymentStatus()).isEqualTo("paid");
        assertThat(paid.settlementStatus()).isEqualTo("settled");
        assertThat(of("confirmed", 1180, 0, 600, 0, 0, 0, 0, 0, 0, 400).settlementStatus()).isEqualTo("open");
    }

    @Test
    @DisplayName("a credit after payment is a credit balance until refunded, or kept as customer credit")
    void creditBalance() {
        SalesOutcomeService.Outcome owed = of("done", 1180, 0, 1180, 0, 236, 0, 236, 0, 0, 0);
        assertThat(owed.paymentStatus()).isEqualTo("credit_balance");
        assertThat(owed.outstanding()).isEqualByComparingTo("-236");
        assertThat(owed.openCredit()).isEqualByComparingTo("236");
        assertThat(owed.creditStatus()).isEqualTo("partial");

        SalesOutcomeService.Outcome refunded = of("done", 1180, 0, 1180, 0, 236, 0, 0, 236, 0, 0);
        assertThat(refunded.paymentStatus()).isEqualTo("paid");
        assertThat(refunded.received()).isEqualByComparingTo("1180");
        assertThat(refunded.refunded()).isEqualByComparingTo("236");

        SalesOutcomeService.Outcome kept = of("done", 1180, 236, 1180, 0, 236, 0, 236, 0, 0, 0);
        assertThat(kept.paymentStatus()).isEqualTo("paid");
        assertThat(kept.retained()).isEqualByComparingTo("236");
        assertThat(kept.openCredit()).isEqualByComparingTo("0");
        assertThat(kept.settlementStatus()).isEqualTo("settled");

        SalesOutcomeService.Outcome usedElsewhere = of("done", 1180, 0, 1180, 0, 236, 0, 0, 0, 236, 0);
        assertThat(usedElsewhere.retained()).isEqualByComparingTo("236");
        assertThat(usedElsewhere.received()).isEqualByComparingTo("1180");
    }

    @Test
    @DisplayName("fully credited: credited when nothing was paid back, refunded when it was; a write-off is not a credit")
    void fullyCredited() {
        SalesOutcomeService.Outcome credited = of("done", 1180, 0, 1180, 0, 1180, 0, 0, 0, 0, 0);
        assertThat(credited.paymentStatus()).isEqualTo("credited");
        assertThat(credited.creditStatus()).isEqualTo("full");
        assertThat(credited.settlementStatus()).isEqualTo("settled");
        assertThat(of("done", 1180, 0, 1180, 0, 1180, 0, 0, 1180, 0, 0).paymentStatus()).isEqualTo("refunded");

        SalesOutcomeService.Outcome writtenOff = of("done", 1180, 0, 1180, 0, 0, 1180, 0, 0, 0, 0);
        assertThat(writtenOff.paymentStatus()).isEqualTo("written_off");
        assertThat(writtenOff.creditStatus()).isEqualTo("none");
        assertThat(writtenOff.received()).isEqualByComparingTo("0");
        assertThat(of("done", 1180, 0, 1180, 180, 0, 1000, 0, 0, 0, 0).paymentStatus())
                .as("an open balance comes first").isEqualTo("unpaid");
    }
}
