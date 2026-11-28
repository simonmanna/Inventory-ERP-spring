package com.inventoryerp.documents.application;

/**
 * ADR-S094 §8.2: the documents engine hands a reversal or reissue counterpart to the module that owns the
 * {@code Document} table (invoicing), so a document row is inserted in one place only. The row is written exactly as
 * the engine built it (Nest parity): header mirrored, money negated, posted, no lines.
 */
public interface CounterpartWriter {

    /** Insert the counterpart row. */
    void write(Counterpart counterpart);

    /**
     * The counterpart row. Money values are numeric strings; {@code relation} is the engine relation of the original
     * to this row ({@code CANCELS} for a reversal, {@code REISSUES} for a reissue).
     */
    record Counterpart(String id, Object organizationId, String number, String documentType, Object documentTypeId,
            Object partnerId, Object currencyId, Object exchangeRate, Object issueDate, Object dueDate,
            Object sourceDocument, String subtotal, String discountTotal, String taxAmount, String totalAmount,
            String amountResidual, Object reversedDocumentId, Object sourceType, Object sourceId, Object branchId,
            Object postedAt, String createdBy, String relation) {}
}
