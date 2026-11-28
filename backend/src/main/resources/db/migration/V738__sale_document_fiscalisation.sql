-- ADR-S094 C4b (AD-5): every posted invoice of a Sale is fiscalised through the outbox, and so is a credit note on a
-- fiscalised sale invoice.
--
-- * FiscalDocument.ownerKind names what an operation belongs to:
--     submission  a legacy branch cash sale (ADR-S056);
--     sale        a cash Sale: one fiscal document for the sale, its status on SalesOrder.fiscalStatus (C3a);
--     document    one invoice or credit note of a Sale (invoiceId names the document, salesOrderId the sale).
-- * operation 'credit_note': the credit note of a fiscalised sale invoice, sent once the invoice is registered.
-- * One live operation of each kind per owner: per sale for 'sale' rows (as before), per document for 'document'
--   rows, so a sale's several invoices are registered side by side.
--
-- Existing rows belong to a submission or to a cash Sale; nothing else changes.

ALTER TABLE "FiscalDocument" ADD COLUMN "ownerKind" text;
UPDATE "FiscalDocument" SET "ownerKind" = CASE WHEN "submissionId" IS NOT NULL THEN 'submission' ELSE 'sale' END;
ALTER TABLE "FiscalDocument" ALTER COLUMN "ownerKind" SET NOT NULL;

ALTER TABLE "FiscalDocument" ADD CONSTRAINT "FiscalDocument_owner_kind_check" CHECK (
    ("ownerKind" = 'submission' AND "submissionId" IS NOT NULL)
    OR ("ownerKind" = 'sale' AND "salesOrderId" IS NOT NULL)
    OR ("ownerKind" = 'document' AND "salesOrderId" IS NOT NULL AND "invoiceId" IS NOT NULL));

ALTER TABLE "FiscalDocument" DROP CONSTRAINT "FiscalDocument_operation_check";
ALTER TABLE "FiscalDocument" ADD CONSTRAINT "FiscalDocument_operation_check"
    CHECK (operation IN ('issue', 'cancel', 'correct', 'credit_note'));
-- a credit note is registered for one document; identity corrections stay a legacy submission operation
ALTER TABLE "FiscalDocument" ADD CONSTRAINT "FiscalDocument_owner_operation_check" CHECK (
    (operation <> 'credit_note' OR "ownerKind" = 'document')
    AND ("ownerKind" <> 'document' OR operation IN ('issue', 'cancel', 'credit_note')));

DROP INDEX "FiscalDocument_sale_live_op_key";
CREATE UNIQUE INDEX "FiscalDocument_sale_live_op_key" ON "FiscalDocument" ("organizationId", "salesOrderId", operation)
    WHERE status IN ('pending', 'sent', 'unknown', 'review') AND "ownerKind" = 'sale';
CREATE UNIQUE INDEX "FiscalDocument_document_live_op_key" ON "FiscalDocument" ("organizationId", "invoiceId", operation)
    WHERE status IN ('pending', 'sent', 'unknown', 'review') AND "ownerKind" = 'document';
CREATE INDEX "FiscalDocument_invoice_idx" ON "FiscalDocument" ("invoiceId") WHERE "invoiceId" IS NOT NULL;

COMMENT ON COLUMN "FiscalDocument"."ownerKind" IS
    'ADR-S094 C4b: submission (legacy cash sale), sale (a cash Sale''s one document) or document (one invoice or credit note of a Sale)';
