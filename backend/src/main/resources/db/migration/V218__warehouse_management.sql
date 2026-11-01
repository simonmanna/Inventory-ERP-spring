-- PR-4 (roadmap 2.1, ADR-S026): warehouse management. Extension tables on top of the location-level stock ledger.
-- The ledger (StockItem / InventoryLedger) stays the single source of truth for quantities and value: bins are a
-- sub-ledger inside one location, so moving stock between bins never posts a ledger row or a GL line.
--   location quantity = binned (WarehouseBinStock) + staged (picked, not shipped) + unbinned (received, not put away)

-- Per-location WMS switches and the ABC cycle-count policy.
CREATE TABLE "WarehouseSettings" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"       TEXT          NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    enabled            BOOLEAN       NOT NULL DEFAULT true,
    "outboundSteps"    INTEGER       NOT NULL DEFAULT 1,
    "abcAPercent"      NUMERIC(5, 2) NOT NULL DEFAULT 80,
    "abcBPercent"      NUMERIC(5, 2) NOT NULL DEFAULT 95,
    "abcLookbackDays"  INTEGER       NOT NULL DEFAULT 90,
    "countDaysA"       INTEGER       NOT NULL DEFAULT 30,
    "countDaysB"       INTEGER       NOT NULL DEFAULT 90,
    "countDaysC"       INTEGER       NOT NULL DEFAULT 180,
    "createdAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehouseSettings_location" UNIQUE ("locationId"),
    CONSTRAINT "WarehouseSettings_steps" CHECK ("outboundSteps" BETWEEN 1 AND 3),
    CONSTRAINT "WarehouseSettings_abc" CHECK ("abcAPercent" > 0 AND "abcAPercent" < "abcBPercent"
        AND "abcBPercent" < 100),
    CONSTRAINT "WarehouseSettings_days" CHECK ("abcLookbackDays" > 0 AND "countDaysA" > 0 AND "countDaysB" > 0
        AND "countDaysC" > 0)
);

-- Zones / aisles / racks / bins: a tree inside one location. Only kind = 'bin' holds stock.
CREATE TABLE "WarehouseBin" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"     TEXT           NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    "parentId"       TEXT REFERENCES "WarehouseBin" (id) ON DELETE RESTRICT,
    code             TEXT           NOT NULL,
    name             TEXT,
    kind             TEXT           NOT NULL DEFAULT 'bin',
    usage            TEXT           NOT NULL DEFAULT 'storage',
    "pickSequence"   INTEGER        NOT NULL DEFAULT 0,
    "maxQty"         NUMERIC(20, 6),
    "mixedProducts"  BOOLEAN        NOT NULL DEFAULT true,
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehouseBin_location_code" UNIQUE ("locationId", code),
    CONSTRAINT "WarehouseBin_kind" CHECK (kind IN ('zone', 'aisle', 'rack', 'bin')),
    CONSTRAINT "WarehouseBin_usage" CHECK (usage IN ('storage', 'receiving', 'output', 'quarantine')),
    CONSTRAINT "WarehouseBin_max" CHECK ("maxQty" IS NULL OR "maxQty" > 0)
);
CREATE INDEX "WarehouseBin_org_location" ON "WarehouseBin" ("organizationId", "locationId");

-- Quantity per bin, product, variant and batch ('' = none).
CREATE TABLE "WarehouseBinStock" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"     TEXT           NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    "binId"          TEXT           NOT NULL REFERENCES "WarehouseBin" (id) ON DELETE RESTRICT,
    "productId"      TEXT           NOT NULL REFERENCES "Product" (id) ON DELETE RESTRICT,
    "variantKey"     TEXT           NOT NULL DEFAULT '',
    "batchKey"       TEXT           NOT NULL DEFAULT '',
    quantity         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehouseBinStock_key" UNIQUE ("binId", "productId", "variantKey", "batchKey"),
    CONSTRAINT "WarehouseBinStock_qty" CHECK (quantity >= 0)
);
CREATE INDEX "WarehouseBinStock_location_product" ON "WarehouseBinStock" ("locationId", "productId");

-- Putaway rules: product beats category beats the location default; lower priority number first.
CREATE TABLE "WarehousePutawayRule" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"     TEXT         NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    "productId"      TEXT REFERENCES "Product" (id) ON DELETE CASCADE,
    "categoryId"     TEXT,
    "targetBinId"    TEXT REFERENCES "WarehouseBin" (id) ON DELETE CASCADE,
    "targetZoneId"   TEXT REFERENCES "WarehouseBin" (id) ON DELETE CASCADE,
    priority         INTEGER      NOT NULL DEFAULT 10,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehousePutawayRule_target" CHECK ("targetBinId" IS NOT NULL OR "targetZoneId" IS NOT NULL),
    CONSTRAINT "WarehousePutawayRule_scope" CHECK ("productId" IS NULL OR "categoryId" IS NULL)
);
CREATE INDEX "WarehousePutawayRule_location" ON "WarehousePutawayRule" ("organizationId", "locationId");

CREATE TABLE "WarehouseWave" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"     TEXT         NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'open',
    notes            TEXT,
    "createdBy"      TEXT,
    "releasedAt"     TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehouseWave_status" CHECK (status IN ('open', 'released', 'done', 'cancelled'))
);
CREATE INDEX "WarehouseWave_org_location" ON "WarehouseWave" ("organizationId", "locationId");

-- Outbound: pick → (pack) → ship, 1 to 3 steps. The source document (POS sale, stock-out, transfer, fleet dispatch)
-- still posts the ledger issue; a pick order moves stock out of the bins into staging and records the shipment.
CREATE TABLE "WarehousePickOrder" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"     TEXT         NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    "sourceType"     TEXT         NOT NULL DEFAULT 'manual',
    "sourceId"       TEXT,
    "sourceRef"      TEXT,
    steps            INTEGER      NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'draft',
    "waveId"         TEXT REFERENCES "WarehouseWave" (id) ON DELETE SET NULL,
    priority         INTEGER      NOT NULL DEFAULT 0,
    "scheduledDate"  DATE,
    notes            TEXT,
    carrier          TEXT,
    "trackingRef"    TEXT,
    "shippedAt"      TIMESTAMP(3),
    "shippedBy"      TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehousePickOrder_steps" CHECK (steps BETWEEN 1 AND 3),
    CONSTRAINT "WarehousePickOrder_source" CHECK ("sourceType" IN ('manual', 'sales_order', 'delivery_order',
        'transfer', 'stock_out')),
    CONSTRAINT "WarehousePickOrder_status" CHECK (status IN ('draft', 'released', 'picking', 'picked', 'packed',
        'shipped', 'cancelled'))
);
CREATE INDEX "WarehousePickOrder_org_location" ON "WarehousePickOrder" ("organizationId", "locationId", status);
CREATE INDEX "WarehousePickOrder_wave" ON "WarehousePickOrder" ("waveId");

CREATE TABLE "WarehousePickLine" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "pickOrderId"    TEXT           NOT NULL REFERENCES "WarehousePickOrder" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL REFERENCES "Product" (id) ON DELETE RESTRICT,
    "variantKey"     TEXT           NOT NULL DEFAULT '',
    quantity         NUMERIC(20, 6) NOT NULL,
    "pickedQty"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "packedQty"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "shippedQty"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    CONSTRAINT "WarehousePickLine_qty" CHECK (quantity > 0 AND "pickedQty" >= 0 AND "pickedQty" <= quantity
        AND "packedQty" >= 0 AND "packedQty" <= "pickedQty" AND "shippedQty" >= 0 AND "shippedQty" <= "pickedQty")
);
CREATE INDEX "WarehousePickLine_order" ON "WarehousePickLine" ("pickOrderId");

-- Work for the floor: putaway (unbinned → bin), move (bin → bin) and pick (bin → staging).
CREATE TABLE "WarehouseTask" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"     TEXT           NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    code             TEXT           NOT NULL,
    type             TEXT           NOT NULL,
    status           TEXT           NOT NULL DEFAULT 'open',
    "productId"      TEXT           NOT NULL REFERENCES "Product" (id) ON DELETE RESTRICT,
    "variantKey"     TEXT           NOT NULL DEFAULT '',
    "batchKey"       TEXT           NOT NULL DEFAULT '',
    quantity         NUMERIC(20, 6) NOT NULL,
    "doneQty"        NUMERIC(20, 6),
    "fromBinId"      TEXT REFERENCES "WarehouseBin" (id) ON DELETE RESTRICT,
    "toBinId"        TEXT REFERENCES "WarehouseBin" (id) ON DELETE RESTRICT,
    "pickOrderId"    TEXT REFERENCES "WarehousePickOrder" (id) ON DELETE CASCADE,
    "pickLineId"     TEXT REFERENCES "WarehousePickLine" (id) ON DELETE CASCADE,
    "waveId"         TEXT REFERENCES "WarehouseWave" (id) ON DELETE SET NULL,
    sequence         INTEGER        NOT NULL DEFAULT 0,
    "doneBy"         TEXT,
    "doneAt"         TIMESTAMP(3),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehouseTask_type" CHECK (type IN ('putaway', 'move', 'pick')),
    CONSTRAINT "WarehouseTask_status" CHECK (status IN ('open', 'done', 'cancelled')),
    CONSTRAINT "WarehouseTask_qty" CHECK (quantity > 0 AND ("doneQty" IS NULL OR "doneQty" >= 0))
);
CREATE INDEX "WarehouseTask_org_location" ON "WarehouseTask" ("organizationId", "locationId", status, type);
CREATE INDEX "WarehouseTask_pick_order" ON "WarehouseTask" ("pickOrderId");

CREATE TABLE "WarehousePackage" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "pickOrderId"    TEXT           NOT NULL REFERENCES "WarehousePickOrder" (id) ON DELETE CASCADE,
    code             TEXT           NOT NULL,
    "packageType"    TEXT,
    "weightKg"       NUMERIC(12, 3),
    status           TEXT           NOT NULL DEFAULT 'closed',
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WarehousePackage_status" CHECK (status IN ('closed', 'shipped')),
    CONSTRAINT "WarehousePackage_weight" CHECK ("weightKg" IS NULL OR "weightKg" >= 0)
);
CREATE INDEX "WarehousePackage_order" ON "WarehousePackage" ("pickOrderId");

CREATE TABLE "WarehousePackageItem" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "packageId"      TEXT           NOT NULL REFERENCES "WarehousePackage" (id) ON DELETE CASCADE,
    "pickLineId"     TEXT           NOT NULL REFERENCES "WarehousePickLine" (id) ON DELETE CASCADE,
    quantity         NUMERIC(20, 6) NOT NULL,
    CONSTRAINT "WarehousePackageItem_qty" CHECK (quantity > 0)
);
CREATE INDEX "WarehousePackageItem_package" ON "WarehousePackageItem" ("packageId");

-- ABC classification per location and product, with the cycle-count schedule it drives.
CREATE TABLE "WarehouseAbcClass" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "locationId"       TEXT           NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    "productId"        TEXT           NOT NULL REFERENCES "Product" (id) ON DELETE CASCADE,
    "abcClass"         CHAR(1)        NOT NULL,
    "consumptionValue" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "cumulativeShare"  NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    "classifiedAt"     TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastCountedAt"    TIMESTAMP(3),
    "lastScheduledAt"  TIMESTAMP(3),
    "lastSessionId"    TEXT,
    CONSTRAINT "WarehouseAbcClass_key" UNIQUE ("locationId", "productId"),
    CONSTRAINT "WarehouseAbcClass_class" CHECK ("abcClass" IN ('A', 'B', 'C'))
);
CREATE INDEX "WarehouseAbcClass_org_location" ON "WarehouseAbcClass" ("organizationId", "locationId");

-- Row-level security like every tenant table (V214).
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['WarehouseSettings', 'WarehouseBin', 'WarehouseBinStock', 'WarehousePutawayRule',
            'WarehouseWave', 'WarehousePickOrder', 'WarehousePickLine', 'WarehouseTask', 'WarehousePackage',
            'WarehousePackageItem', 'WarehouseAbcClass'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
