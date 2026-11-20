-- FX-2 G (owner decision 2, 2026-11-20): a foreign PO receipt is valued at the receipt-date rate. The rate each receipt
-- was valued at is recorded on the receipt. Existing receipts are backfilled from their PO: that is the rate they were
-- actually costed at, so this records history and changes no value.
ALTER TABLE "GoodsReceiptNote"
    ADD COLUMN "currencyCode" TEXT,
    ADD COLUMN "exchangeRate" NUMERIC(18, 8),
    ADD COLUMN "rateSource"   TEXT;

UPDATE "GoodsReceiptNote" g
SET "currencyCode" = po."currencyCode", "exchangeRate" = po."exchangeRate", "rateSource" = 'po'
FROM "PurchaseOrder" po, "Organization" o
WHERE po.id = g."purchaseOrderId" AND o.id = g."organizationId" AND g.status = 'posted'
  AND po."currencyCode" IS NOT NULL AND upper(po."currencyCode") <> upper(o."currencyCode")
  AND po."exchangeRate" > 0;
