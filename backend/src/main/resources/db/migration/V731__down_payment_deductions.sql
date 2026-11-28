-- ADR-S094 C1b (D-VAT-1, accountant decisions AD-14 / AD-15 / AD-17): a taxed down payment is deducted on the final
-- invoice instead of being applied to it.
--
-- With depositTaxPoint = receipt a down-payment invoice posts Dr AR gross / Cr customer deposits net / Cr VAT. Before
-- this migration the final invoice charged VAT on the whole order again and the deposit (net) was applied to it, so
-- output VAT was counted twice and the customer was left owing the VAT of the advance.
--
-- * SalesInvoiceLink kind 'deduction': the negative line of a regular invoice that deducts a taxed down payment (on the
--   deposit account, with the down payment's tax), and the down-payment invoice it deducts.
-- * DepositApplication kinds 'deduction' and 'credit_note': the down payment's deposit consumed by a posted final
--   invoice, or by a credit note on its own down-payment invoice. Neither is a payment, so paymentId is empty for them.
--   One live row per deposit, document and kind.

ALTER TABLE "SalesInvoiceLink" DROP CONSTRAINT "SalesInvoiceLink_kind_check";
ALTER TABLE "SalesInvoiceLink" ADD CONSTRAINT "SalesInvoiceLink_kind_check"
    CHECK (kind IN ('regular', 'down_payment', 'freight', 'credit_note', 'deduction'));
ALTER TABLE "SalesInvoiceLink" ADD COLUMN "downPaymentDocumentId" TEXT;
ALTER TABLE "SalesInvoiceLink" ADD CONSTRAINT "SalesInvoiceLink_deduction_check"
    CHECK (kind <> 'deduction' OR ("downPaymentDocumentId" IS NOT NULL AND "documentLineId" IS NOT NULL));
CREATE INDEX "SalesInvoiceLink_down_payment_idx" ON "SalesInvoiceLink" ("downPaymentDocumentId")
    WHERE "downPaymentDocumentId" IS NOT NULL;

ALTER TABLE "DepositApplication" DROP CONSTRAINT "DepositApplication_kind_check";
ALTER TABLE "DepositApplication" ADD CONSTRAINT "DepositApplication_kind_check"
    CHECK (kind IN ('application', 'refund', 'deduction', 'credit_note'));
ALTER TABLE "DepositApplication" ALTER COLUMN "paymentId" DROP NOT NULL;
ALTER TABLE "DepositApplication" ADD CONSTRAINT "DepositApplication_payment_check"
    CHECK (kind IN ('deduction', 'credit_note') OR "paymentId" IS NOT NULL);
CREATE UNIQUE INDEX "DepositApplication_consumption_key" ON "DepositApplication" ("depositId", "documentId", kind)
    WHERE kind IN ('deduction', 'credit_note') AND "reversedAt" IS NULL;
