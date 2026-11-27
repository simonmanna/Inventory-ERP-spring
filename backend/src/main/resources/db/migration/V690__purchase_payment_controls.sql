-- Purchase readiness fixes (owner-approved 2026-11-27), stream "pay".
-- H6: a supplier-payment approval authorises one payment; the payment that used it is recorded here.
ALTER TABLE "ApprovalRequest" ADD COLUMN "consumedAt" timestamp(3) without time zone;
ALTER TABLE "ApprovalRequest" ADD COLUMN "consumedRef" text;

-- H7: the verified supplier bank account a supplier payment / PO payment was sent to.
ALTER TABLE "Payment" ADD COLUMN "supplierBankAccountId" text
    REFERENCES "SupplierBankAccount"(id) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE "PurchasePayment" ADD COLUMN "supplierBankAccountId" text
    REFERENCES "SupplierBankAccount"(id) ON UPDATE CASCADE ON DELETE RESTRICT;
CREATE INDEX "Payment_supplierBankAccountId_idx" ON "Payment"("supplierBankAccountId")
    WHERE "supplierBankAccountId" IS NOT NULL;
