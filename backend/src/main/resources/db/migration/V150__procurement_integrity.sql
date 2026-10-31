-- V26: procurement integrity hardening (ADR-PROCUREMENT-001, wave 1 / P0).
-- Additive only: a non-negative guard on PO receipt progress (NOT VALID so historical rows are not re-checked, every
-- new write is) and the indexes behind the receipt-voucher payment cap and the three-way match recompute.

ALTER TABLE "PurchaseOrderLine"
    ADD CONSTRAINT "PurchaseOrderLine_receivedQuantity_nonneg" CHECK ("receivedQuantity" >= 0) NOT VALID;

CREATE INDEX IF NOT EXISTS "GoodsReceiptNote_org_po_status_idx"
    ON "GoodsReceiptNote" ("organizationId", "purchaseOrderId", status);

CREATE INDEX IF NOT EXISTS "GoodsReceiptLine_poLine_idx"
    ON "GoodsReceiptLine" ("purchaseOrderLineId");

CREATE INDEX IF NOT EXISTS "VendorBillLink_org_po_idx"
    ON "VendorBillLink" ("organizationId", "purchaseOrderId");
