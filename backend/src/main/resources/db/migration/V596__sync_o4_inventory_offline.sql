-- ADR-S083 O4 / S4: documents created from an offline device say so. "occurredAt" is when the device recorded the
-- physical fact; the document's own dates stay the server's. Nullable / defaulted: existing rows are unaffected.
ALTER TABLE "StockTransfer" ADD COLUMN "recordedOffline" boolean DEFAULT false NOT NULL;
ALTER TABLE "StockTransfer" ADD COLUMN "occurredAt" timestamp(3) without time zone;
ALTER TABLE "StockAdjustment" ADD COLUMN "recordedOffline" boolean DEFAULT false NOT NULL;
ALTER TABLE "StockAdjustment" ADD COLUMN "occurredAt" timestamp(3) without time zone;
ALTER TABLE "GoodsReceiptNote" ADD COLUMN "recordedOffline" boolean DEFAULT false NOT NULL;
ALTER TABLE "GoodsReceiptNote" ADD COLUMN "occurredAt" timestamp(3) without time zone;

-- Offline stock discrepancies reuse "InventoryException" (kinds offline_negative_stock, offline_held_posting,
-- offline_awaiting_dispatch). The awaiting-dispatch job looks them up by kind and transfer.
CREATE INDEX "InventoryException_offline_open_idx" ON "InventoryException" ("organizationId", kind)
    WHERE status = 'open' AND kind LIKE 'offline_%';
