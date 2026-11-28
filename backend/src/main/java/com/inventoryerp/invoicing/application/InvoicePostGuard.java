package com.inventoryerp.invoicing.application;

/**
 * Phase A A4 (plan §5, defence in depth): a module that allocates delivered goods to invoices rechecks a document
 * issued by a delivery ({@code data.stockIssuedBy}) just before it posts, and throws an {@code ApiException} when the
 * document would invoice more than is invoiceable. Invoicing calls every guard and depends on none of them.
 */
public interface InvoicePostGuard {

    /** Called inside the posting transaction, before the workflow transition. */
    void beforePost(String documentId);

    /**
     * ADR-S094 C1b: called inside the posting transaction right after the workflow transition (the document is posted
     * and journalled), for the same documents as {@link #beforePost}: a module books what the post settles, such as the
     * deposits its deductions consume. Throwing rolls the post back.
     */
    default void afterPost(String documentId) {}

    /**
     * ADR-S094 C2b: called inside the cancelling transaction of a posted invoice, right after the workflow reversed its
     * journal entry: a module reverses what it booked for the post (a revenue deferral). Throwing rolls the cancel back.
     */
    default void afterCancel(String documentId) {}
}
