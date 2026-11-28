-- V700 (IPR Phase 1b, T10; ADR-S088): the business time of a stock movement, beside the write time.
--
-- R1 (ledger immutability): "effectiveAt" is added nullable, with NO default and NO backfill. ADD COLUMN without a default
-- touches no existing row, so the append-only trigger inventory_ledger_append_only never fires. Historical rows keep a
-- null effective time for ever; every reader uses COALESCE("effectiveAt", "createdAt"), which is exactly the old
-- behaviour for them. New rows always get it from StockService.insertLedger.
--
-- The previous application image never names these columns, so it runs on the new schema (R7 rollback path).
-- Plain CREATE INDEX inside the Flyway transaction (as V120): it blocks ledger writes while it builds. On a large copy,
-- build it beforehand with CREATE INDEX CONCURRENTLY under the same name.

ALTER TABLE "InventoryLedger" ADD COLUMN IF NOT EXISTS "effectiveAt" timestamp(3);

-- Count carry-forward, late-transaction lookup and the period-shifted report read a cell by effective time.
CREATE INDEX IF NOT EXISTS "InventoryLedger_org_loc_product_effective_idx" ON "InventoryLedger"
    ("organizationId", "locationId", "productId", (COALESCE("effectiveAt", "createdAt")));

-- Count spec section 6: an optional per-session freeze of the location (server-side manual moves refused).
ALTER TABLE "InventoryCountSession" ADD COLUMN IF NOT EXISTS "freezeLocation" boolean NOT NULL DEFAULT false;

-- Count spec section 1: the highest ledger seq of the line's cell that the submit measurement saw (null before V700).
ALTER TABLE "InventoryCountLine" ADD COLUMN IF NOT EXISTS "measuredSeq" bigint;
