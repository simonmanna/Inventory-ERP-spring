-- Inventory engine hardening (deliberate deviations from the Nest reference, approved by the owner).
--
-- 1. StockTransferLayer: what each transit transfer line put into the (shared, one per organisation) transit
--    location: the lots created there, the serial units moved and the dispatch unit cost (row with neither). Receipt,
--    in-transit loss and recall move exactly these units at their cost instead of the oldest lots / serials in transit,
--    which may belong to another transfer. Lines dispatched before this migration have no rows and keep the old
--    behaviour.
-- 2. One active StockReservation per source line and stock cell: re-reserving a line updates it (ON CONFLICT) instead
--    of a concurrent duplicate. Existing duplicates keep the newest active row; the others are released.

CREATE TABLE "StockTransferLayer" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "transferId"     TEXT NOT NULL REFERENCES "StockTransfer" (id) ON DELETE CASCADE,
    "transferItemId" TEXT NOT NULL REFERENCES "StockTransferItem" (id) ON DELETE CASCADE,
    "batchId"        TEXT,
    "serialId"       TEXT,
    quantity         NUMERIC(20, 6) NOT NULL,
    "unitCost"       NUMERIC(20, 6),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "StockTransferLayer_item_idx" ON "StockTransferLayer" ("organizationId", "transferItemId");
CREATE INDEX "StockTransferLayer_transfer_idx" ON "StockTransferLayer" ("organizationId", "transferId");
ALTER TABLE "StockTransferLayer" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "StockTransferLayer"
    USING (("organizationId" = current_setting('app.org_id', true)));

UPDATE "StockReservation" r SET status = 'released', "releasedAt" = CURRENT_TIMESTAMP, "updatedAt" = CURRENT_TIMESTAMP
WHERE r.status = 'active' AND EXISTS (
    SELECT 1 FROM "StockReservation" n
    WHERE n.status = 'active' AND n."organizationId" = r."organizationId" AND n."productId" = r."productId"
      AND n."variantKey" = r."variantKey" AND n."locationId" = r."locationId" AND n."sourceType" = r."sourceType"
      AND n."sourceId" = r."sourceId"
      AND (n."updatedAt" > r."updatedAt" OR (n."updatedAt" = r."updatedAt" AND n.id > r.id)));

CREATE UNIQUE INDEX "StockReservation_active_line_uq"
    ON "StockReservation" ("organizationId", "productId", "variantKey", "locationId", "sourceType", "sourceId")
    WHERE status = 'active';
