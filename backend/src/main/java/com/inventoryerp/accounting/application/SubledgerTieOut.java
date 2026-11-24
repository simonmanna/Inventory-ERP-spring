package com.inventoryerp.accounting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Finance readiness audit A5 (P2-5): the customer and supplier sub-ledgers against their control accounts, in base
 * currency and per partner. The sub-ledger is the open items at the rate they were posted at (sales invoices, debit and
 * credit notes, POS invoices; vendor bills and receipt-voucher payables) less payments not yet applied; the general
 * ledger is every account of the {@code receivable} / {@code payable} category, by partner.
 *
 * <p>Implemented by the reporting module over its {@code ar.reconciliation} / {@code ap.reconciliation} reports, so the
 * nightly tie-out, the ledger integrity check (I8) and those reports measure the same thing. A port here because the
 * reporting module already depends on this one.
 */
public interface SubledgerTieOut {

    /** Amount tolerance of a side and of each partner. */
    BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** One partner whose sub-ledger and control-account balances differ by more than {@link #TOLERANCE}. */
    record PartnerDifference(String partnerId, String partner, BigDecimal subledger, BigDecimal gl,
            BigDecimal difference) {}

    /**
     * One side ({@code AR} or {@code AP}) at the end of {@code asOf} (organisation-local).
     *
     * @param subledger   open items less unapplied payments, base currency
     * @param gl          control-account balance, base currency (debit-normal for AR, credit-normal for AP)
     * @param differences the partners that differ, largest first
     */
    record Measure(String side, LocalDate asOf, BigDecimal subledger, BigDecimal gl,
            List<PartnerDifference> differences) {

        /** General ledger less sub-ledger: the sign the tie-out snapshot has always stored. */
        public BigDecimal variance() {
            return gl.subtract(subledger);
        }

        /** The side ties: its totals agree and no partner differs. */
        public boolean balanced() {
            return variance().abs().compareTo(TOLERANCE) <= 0 && differences.isEmpty();
        }
    }

    /** {@code side} AR or AP of the current organisation (tenant context) at the end of {@code asOf}, org-local. */
    Measure measure(String side, LocalDate asOf);
}
