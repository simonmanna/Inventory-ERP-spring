-- Cash-sale payments can use the organization's payment modes (PosPaymentMethod): the mode decides the method,
-- provider label and receiving account, so the provider is no longer limited to MTN / Airtel.
ALTER TABLE "SalesSubmissionPayment" DROP CONSTRAINT IF EXISTS "SalesSubmissionPayment_provider_check";
ALTER TABLE "SalesSubmissionPayment" ADD COLUMN "paymentModeId" TEXT;
ALTER TABLE "SalesSubmissionPayment" ADD COLUMN "paymentModeLabel" TEXT;
