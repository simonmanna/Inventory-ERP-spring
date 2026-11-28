package com.inventoryerp.kernel.recurring.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * ADR-S094 §8.2: the recurring job hands each generated document to the module that owns the {@code Document} table
 * (invoicing), so a document row is inserted in one place only. The writer keeps the recurring rules unchanged
 * (Nest parity): a draft with the template's own number and lines, no tax, linked to its template
 * ({@code sourceType = recurring}).
 */
public interface RecurringDocumentWriter {

    /** Insert the draft, its lines and its totals; returns the new document id. */
    String write(Draft draft);

    /** One generated document. {@code issueDate} is the run time. */
    record Draft(String organizationId, String templateId, String documentType, String documentTypeId, String number,
            String partnerId, String currencyId, BigDecimal exchangeRate, Instant issueDate, String reference,
            String notes, List<Line> lines) {}

    /** One template line, as written in the template. */
    record Line(String productId, String accountId, String description, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal discountPercent, String taxId) {}
}
