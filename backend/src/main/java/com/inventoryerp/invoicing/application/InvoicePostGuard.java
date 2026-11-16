package com.inventoryerp.invoicing.application;

/**
 * Phase A A4 (plan §5, defence in depth): a module that allocates delivered goods to invoices rechecks a document
 * issued by a delivery ({@code data.stockIssuedBy}) just before it posts, and throws an {@code ApiException} when the
 * document would invoice more than is invoiceable. Invoicing calls every guard and depends on none of them.
 */
public interface InvoicePostGuard {

    /** Called inside the posting transaction, before the workflow transition. */
    void beforePost(String documentId);
}
