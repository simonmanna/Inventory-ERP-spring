-- Hardening R5a (inventory review 2026-11-22).
-- 1. The replenishment incoming-supply query (TransferIncomingSupply) scans the open transfer lines of one product for
--    every rule; V1 only indexes ("organizationId", "transferId").
CREATE INDEX IF NOT EXISTS "StockTransferItem_organizationId_productId_idx"
    ON public."StockTransferItem" ("organizationId", "productId");

-- 2. Repair parts: the variant of a part is issued (and returned) with it, and an issued part can be returned to stock.
ALTER TABLE public."RepairPart" ADD COLUMN IF NOT EXISTS "variantId" text;
ALTER TYPE public."RepairPartStatus" ADD VALUE IF NOT EXISTS 'returned';
