-- Blocker fix 2026-11-17 (B1): a goods receipt of a purchase order belongs to the order's supplier.
-- Receipts saved from the "New goods receipt" screen carried no supplier. The bill matcher pairs bill lines only with
-- receipts of the bill's supplier, so it never found them and the supplier bill received and charged the goods again.
-- Give every such receipt its order's supplier, so the next bill matches it.
-- A bill already posted against one of these receipts was not changed. The audit query in
-- docs/audits/2026-11-17-inventory-procurement-readiness.md lists receipts that may already be double-counted.
UPDATE "GoodsReceiptNote" g
SET "partnerId" = po."partnerId",
    "updatedAt" = CURRENT_TIMESTAMP
FROM "PurchaseOrder" po
WHERE g."purchaseOrderId" = po.id
  AND g."organizationId" = po."organizationId"
  AND g."partnerId" IS NULL
  AND po."partnerId" IS NOT NULL;
