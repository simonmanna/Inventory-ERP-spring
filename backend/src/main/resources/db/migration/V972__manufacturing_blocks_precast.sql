-- V972 (placeholder, renumbered at merge): concrete block / precast manufacturing (Phase 2.4, ADR-S014).
--
-- Additive only. A mix design (recipe) is a versioned BOM plus its batch parameters; a precast order is a
-- multi-stage production order (ADR-S011) whose last stage is a QC-gated curing stage. Two columns are added to the
-- 2.1 stage tables for the minimum-curing-days hold; everything else is new.

-- ---------------------------------------------------------------- 2.1 extension: minimum curing days

-- Days the output of a gated stage must cure before its QC gate can pass (0 = no hold).
ALTER TABLE "RoutingStageSetup" ADD COLUMN "minCuringDays" INTEGER NOT NULL DEFAULT 0
    CHECK ("minCuringDays" >= 0 AND "minCuringDays" <= 365);
ALTER TABLE "ProductionOrderStage" ADD COLUMN "minCuringDays" INTEGER NOT NULL DEFAULT 0
    CHECK ("minCuringDays" >= 0 AND "minCuringDays" <= 365);
-- New gate state 'curing': passed, waiting for the minimum curing time.
ALTER TABLE "ProductionOrderStage" DROP CONSTRAINT "ProductionOrderStage_qcStatus_check";
ALTER TABLE "ProductionOrderStage" ADD CONSTRAINT "ProductionOrderStage_qcStatus_check"
    CHECK ("qcStatus" IN ('not_required', 'pending', 'passed', 'failed', 'curing'));
-- Set when a pass arrives before curing is over (qcStatus = 'curing'): the gate passes at this time.
ALTER TABLE "ProductionOrderStage" ADD COLUMN "qcPassDueAt" TIMESTAMP(3);
CREATE INDEX "ProductionOrderStage_curing_idx" ON "ProductionOrderStage" ("qcPassDueAt")
    WHERE "qcStatus" = 'curing';

-- ---------------------------------------------------------------- product specification

CREATE TABLE "PrecastProductSpec" (
    id                     TEXT           NOT NULL PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"            TEXT           NOT NULL REFERENCES "Product" (id),
    "blockType"            TEXT           NOT NULL DEFAULT 'hollow'
        CHECK ("blockType" IN ('hollow', 'solid', 'paver', 'kerb', 'lintel', 'slab', 'other')),
    "lengthMm"             NUMERIC(12, 2) CHECK ("lengthMm" > 0),
    "widthMm"              NUMERIC(12, 2) CHECK ("widthMm" > 0),
    "heightMm"             NUMERIC(12, 2) CHECK ("heightMm" > 0),
    -- e.g. '7 N/mm²'; strengthMpa is the numeric class used for reports and quality specs.
    "strengthClass"        TEXT,
    "strengthMpa"          NUMERIC(12, 3) CHECK ("strengthMpa" >= 0),
    "weightKg"             NUMERIC(12, 3) CHECK ("weightKg" >= 0),
    "unitsPerPallet"       INTEGER        NOT NULL DEFAULT 1 CHECK ("unitsPerPallet" > 0),
    -- Blocks made per machine (mould) cycle.
    "unitsPerCycle"        INTEGER        NOT NULL DEFAULT 1 CHECK ("unitsPerCycle" > 0),
    "secondGradeProductId" TEXT           REFERENCES "Product" (id),
    notes                  TEXT,
    "isActive"             BOOLEAN        NOT NULL DEFAULT true,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PrecastProductSpec_org_product_key" ON "PrecastProductSpec" ("organizationId", "productId");

-- ---------------------------------------------------------------- curing chambers / yards

CREATE TABLE "PrecastCuringArea" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    name                  TEXT           NOT NULL,
    kind                  TEXT           NOT NULL DEFAULT 'yard' CHECK (kind IN ('chamber', 'yard', 'shed')),
    "capacityPallets"     INTEGER        CHECK ("capacityPallets" > 0),
    -- Optional physical inventory location (informational; curing stock is held at the QC gate).
    "locationId"          TEXT           REFERENCES "InventoryLocation" (id),
    "targetTemperatureC"  NUMERIC(8, 2),
    notes                 TEXT,
    "isActive"            BOOLEAN        NOT NULL DEFAULT true,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PrecastCuringArea_org_code_key" ON "PrecastCuringArea" ("organizationId", code);

-- ---------------------------------------------------------------- mix design (recipe) = BOM + batch parameters

-- One row per BOM version: the BOM's outputQuantity is the units per mixer batch, its lines the materials per batch.
CREATE TABLE "PrecastMixDesign" (
    id                     TEXT           NOT NULL PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "bomId"                TEXT           NOT NULL REFERENCES "Bom" (id) ON DELETE CASCADE,
    "productId"            TEXT           NOT NULL REFERENCES "Product" (id),
    "cementProductId"      TEXT           REFERENCES "Product" (id),
    -- Cement base units per bag (e.g. 50 when cement is stocked in kg, 1 when stocked in bags).
    "cementPerBag"         NUMERIC(20, 6) NOT NULL DEFAULT 50 CHECK ("cementPerBag" > 0),
    "batchVolumeM3"        NUMERIC(20, 6) CHECK ("batchVolumeM3" > 0),
    "waterCementRatio"     NUMERIC(8, 4)  CHECK ("waterCementRatio" >= 0),
    "minCuringDays"        INTEGER        NOT NULL DEFAULT 7 CHECK ("minCuringDays" >= 0 AND "minCuringDays" <= 365),
    "curingCheckpointCode" TEXT,
    "defaultCuringAreaId"  TEXT           REFERENCES "PrecastCuringArea" (id),
    -- Nurse-Saul maturity target (°C·h) and datum temperature, informational.
    "targetMaturity"       NUMERIC(20, 2) CHECK ("targetMaturity" >= 0),
    "datumTemperatureC"    NUMERIC(8, 2)  NOT NULL DEFAULT -10,
    notes                  TEXT,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PrecastMixDesign_bom_key" ON "PrecastMixDesign" ("bomId");
CREATE INDEX "PrecastMixDesign_org_product_idx" ON "PrecastMixDesign" ("organizationId", "productId");

-- ---------------------------------------------------------------- curing batches (pallet groups)

-- Blocks of one pressing run, from the press into curing until they are cubed and released.
CREATE TABLE "PrecastCuringBatch" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                 TEXT           NOT NULL,
    "orderId"            TEXT           NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    "stageId"            TEXT           NOT NULL REFERENCES "ProductionOrderStage" (id) ON DELETE CASCADE,
    "pressingPostingId"  TEXT           NOT NULL REFERENCES "ProductionStagePosting" (id),
    "cubingPostingId"    TEXT           REFERENCES "ProductionStagePosting" (id),
    "productId"          TEXT           NOT NULL REFERENCES "Product" (id),
    "batchNumber"        TEXT           NOT NULL,
    quantity             NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "brokenAtPress"      NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("brokenAtPress" >= 0),
    cycles               INTEGER        CHECK (cycles >= 0),
    pallets              INTEGER        NOT NULL DEFAULT 0 CHECK (pallets >= 0),
    "curingAreaId"       TEXT           REFERENCES "PrecastCuringArea" (id),
    "palletLocation"     TEXT,
    shift                TEXT,
    "startedAt"          TIMESTAMP(3)   NOT NULL,
    "minCuringDays"      INTEGER        NOT NULL DEFAULT 0,
    "curingUntil"        TIMESTAMP(3)   NOT NULL,
    status               TEXT           NOT NULL DEFAULT 'curing'
        CHECK (status IN ('curing', 'cubed', 'released', 'failed', 'cancelled')),
    maturity             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "lastTemperatureC"   NUMERIC(8, 2),
    "lastReadingAt"      TIMESTAMP(3),
    "qtyGood"            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qtySecondGrade"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qtyBroken"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "scrapReasonCode"    TEXT,
    "cubedAt"            TIMESTAMP(3),
    "releasedAt"         TIMESTAMP(3),
    "finalLotId"         TEXT,
    notes                TEXT,
    "createdBy"          TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PrecastCuringBatch_org_code_key" ON "PrecastCuringBatch" ("organizationId", code);
CREATE UNIQUE INDEX "PrecastCuringBatch_pressing_key" ON "PrecastCuringBatch" ("pressingPostingId");
CREATE INDEX "PrecastCuringBatch_org_status_idx" ON "PrecastCuringBatch" ("organizationId", status, "curingUntil");
CREATE INDEX "PrecastCuringBatch_order_idx" ON "PrecastCuringBatch" ("orderId");
CREATE INDEX "PrecastCuringBatch_area_idx" ON "PrecastCuringBatch" ("curingAreaId");

-- Temperature readings (maturity) and pallet moves of a curing batch.
CREATE TABLE "PrecastCuringReading" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "batchId"         TEXT           NOT NULL REFERENCES "PrecastCuringBatch" (id) ON DELETE CASCADE,
    kind              TEXT           NOT NULL DEFAULT 'temperature' CHECK (kind IN ('temperature', 'move')),
    "readAt"          TIMESTAMP(3)   NOT NULL,
    "temperatureC"    NUMERIC(8, 2),
    "humidityPct"     NUMERIC(6, 2)  CHECK ("humidityPct" >= 0 AND "humidityPct" <= 100),
    "curingAreaId"    TEXT           REFERENCES "PrecastCuringArea" (id),
    "palletLocation"  TEXT,
    notes             TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "PrecastCuringReading_org_batch_idx" ON "PrecastCuringReading" ("organizationId", "batchId", "readAt");

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "PrecastProductSpec" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PrecastProductSpec" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PrecastCuringArea" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PrecastCuringArea" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PrecastMixDesign" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PrecastMixDesign" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PrecastCuringBatch" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PrecastCuringBatch" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PrecastCuringReading" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PrecastCuringReading" USING (("organizationId" = current_setting('app.org_id', true)));
