-- FX-3 (ADR-S082 Outstanding): a direct payment on a foreign PO is settled at the payment-date rate. The rate, the
-- carrying rate it relieved AP at and the realised FX are recorded on the payment. Existing rows stay NULL (they were
-- paid at the PO rate; nothing is restated).
ALTER TABLE "PurchasePayment"
    ADD COLUMN "currencyCode" TEXT,
    ADD COLUMN "exchangeRate" NUMERIC(18, 8),
    ADD COLUMN "carryingRate" NUMERIC(18, 8),
    ADD COLUMN "fxAmount"     NUMERIC(20, 6);
