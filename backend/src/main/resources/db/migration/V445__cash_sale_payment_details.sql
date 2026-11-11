-- V445: branch cash-sale payments carry their own evidence (payment date, payer, payer phone / account, notes and the
-- attached slip / receipt documents) and are verified at capture unless the branch requires segregation of duties
-- (verificationSource 'collector').
ALTER TABLE "SalesSubmissionPayment" ADD COLUMN "paymentDate" DATE;
ALTER TABLE "SalesSubmissionPayment" ADD COLUMN "payerName" TEXT;
ALTER TABLE "SalesSubmissionPayment" ADD COLUMN "payerAccount" TEXT;
ALTER TABLE "SalesSubmissionPayment" ADD COLUMN notes TEXT;
ALTER TABLE "SalesSubmissionPayment" ADD COLUMN "attachmentIds" TEXT[] NOT NULL DEFAULT '{}';

ALTER TABLE "SalesSubmissionPayment" DROP CONSTRAINT IF EXISTS "SalesSubmissionPayment_verificationSource_check";
ALTER TABLE "SalesSubmissionPayment" ADD CONSTRAINT "SalesSubmissionPayment_verificationSource_check"
    CHECK ("verificationSource" IN ('confirmer_cash', 'momo_gateway', 'verifier', 'collector'));
