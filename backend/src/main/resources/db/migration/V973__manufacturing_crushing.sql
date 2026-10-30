-- V973 (placeholder, renumbered at merge): crushing, screening & aggregate processing (Track C 2.3, ADR-S016).
--
-- Additive only. A crushing plant owns a circuit (stages → routing operations, equipment units → work centres);
-- a grading profile (expected size split per plant × feed material) owns a system-managed BOM with co-products per
-- stage; a posted crushing run IS a multi-stage production order of that BOM (ADR-S011 engine): feed out of the ROM
-- stockpile, conversion cost per stage, sized products into their stockpiles at joint cost. Stockpile surveys
-- (volume × bulk density = tonnes) adjust stock through the inventory adjustment (posting engine) after approval.

-- ---------------------------------------------------------------- plants & circuits

CREATE TABLE "CrushingPlant" (
    id                       TEXT           NOT NULL PRIMARY KEY,
    "organizationId"         TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                     TEXT           NOT NULL,
    name                     TEXT           NOT NULL,
    "siteId"                 TEXT           REFERENCES "QuarrySite" (id),
    -- Default feed source (a ROM stockpile); a run may take its feed from another stockpile.
    "feedStockpileId"        TEXT           REFERENCES "QuarryStockpile" (id),
    "designCapacityTph"      NUMERIC(12, 3) CHECK ("designCapacityTph" > 0),
    -- Rates turning meter readings into conversion cost (diesel litres, kWh, crew hours).
    "dieselCostPerLitre"     NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("dieselCostPerLitre" >= 0),
    "electricityCostPerKwh"  NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("electricityCostPerKwh" >= 0),
    "labourRatePerHour"      NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("labourRatePerHour" >= 0),
    -- The routing of the current circuit (a new routing version is created whenever the stages change).
    "routingId"              TEXT           REFERENCES "Routing" (id),
    "circuitVersion"         INTEGER        NOT NULL DEFAULT 0,
    "isActive"               BOOLEAN        NOT NULL DEFAULT true,
    notes                    TEXT,
    "createdBy"              TEXT,
    "updatedBy"              TEXT,
    "createdAt"              TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"              TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"              TIMESTAMP(3)
);
CREATE UNIQUE INDEX "CrushingPlant_org_code_key" ON "CrushingPlant" ("organizationId", code) WHERE "deletedAt" IS NULL;

-- One step of the circuit (primary crush → secondary → screening ...), a routing operation of the plant's routing.
CREATE TABLE "CrushingPlantStage" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "plantId"            TEXT           NOT NULL REFERENCES "CrushingPlant" (id) ON DELETE CASCADE,
    sequence             INTEGER        NOT NULL CHECK (sequence > 0),
    name                 TEXT           NOT NULL,
    kind                 TEXT           NOT NULL DEFAULT 'other'
        CHECK (kind IN ('primary', 'secondary', 'tertiary', 'quaternary', 'screening', 'washing', 'other')),
    "capacityTph"        NUMERIC(12, 3) CHECK ("capacityTph" > 0),
    "routingOperationId" TEXT           REFERENCES "RoutingOperation" (id) ON DELETE SET NULL,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrushingPlantStage_plant_seq_key" ON "CrushingPlantStage" ("plantId", sequence);
CREATE INDEX "CrushingPlantStage_org_idx" ON "CrushingPlantStage" ("organizationId");

-- Equipment of the circuit (jaw / cone / VSI crushers, screens, conveyors, feeders ...), each a work centre.
CREATE TABLE "CrushingPlantUnit" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "plantId"        TEXT           NOT NULL REFERENCES "CrushingPlant" (id) ON DELETE CASCADE,
    "stageId"        TEXT           REFERENCES "CrushingPlantStage" (id) ON DELETE SET NULL,
    code             TEXT           NOT NULL,
    name             TEXT           NOT NULL,
    kind             TEXT           NOT NULL DEFAULT 'other'
        CHECK (kind IN ('feeder', 'jaw_crusher', 'cone_crusher', 'vsi_crusher', 'impact_crusher', 'screen', 'conveyor',
                        'washer', 'generator', 'other')),
    "workCenterId"   TEXT           REFERENCES "WorkCenter" (id),
    "capacityTph"    NUMERIC(12, 3) CHECK ("capacityTph" > 0),
    "powerKw"        NUMERIC(12, 3) CHECK ("powerKw" >= 0),
    -- Which meters the operator reads per run.
    "meterKind"      TEXT           NOT NULL DEFAULT 'engine_hours' CHECK ("meterKind" IN ('none', 'engine_hours', 'kwh', 'both')),
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    "sortOrder"      INTEGER        NOT NULL DEFAULT 0,
    notes            TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrushingPlantUnit_plant_code_key" ON "CrushingPlantUnit" ("plantId", code);
CREATE INDEX "CrushingPlantUnit_org_idx" ON "CrushingPlantUnit" ("organizationId");

-- ---------------------------------------------------------------- grading profiles (expected yield split)

CREATE TABLE "CrushingGradingProfile" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "plantId"             TEXT           NOT NULL REFERENCES "CrushingPlant" (id),
    code                  TEXT           NOT NULL,
    name                  TEXT           NOT NULL,
    "feedProductId"       TEXT           NOT NULL REFERENCES "Product" (id),
    version               INTEGER        NOT NULL DEFAULT 1,
    status                TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'archived')),
    "allocationMethod"    TEXT           NOT NULL DEFAULT 'quantity'
        CHECK ("allocationMethod" IN ('quantity', 'weight', 'market_value', 'fixed_pct')),
    -- Σ expected % of all lines; the rest of the feed is the expected (normal) process loss.
    "expectedRecoveryPct" NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    -- System-managed BOM + the routing it was built on (set when activated).
    "bomId"               TEXT           REFERENCES "Bom" (id),
    "routingId"           TEXT           REFERENCES "Routing" (id),
    "activatedAt"         TIMESTAMP(3),
    "activatedBy"         TEXT,
    notes                 TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3)
);
CREATE UNIQUE INDEX "CrushingGradingProfile_org_code_version_key" ON "CrushingGradingProfile" ("organizationId", code, version);
CREATE UNIQUE INDEX "CrushingGradingProfile_active_key" ON "CrushingGradingProfile" ("plantId", "feedProductId")
    WHERE status = 'active' AND "deletedAt" IS NULL;
CREATE INDEX "CrushingGradingProfile_org_plant_idx" ON "CrushingGradingProfile" ("organizationId", "plantId");

-- One size fraction of a profile: e.g. 0–6 mm dust 18 %, 10 mm 22 %, 20 mm 30 % (main), oversize to stockpile ...
CREATE TABLE "CrushingGradingLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "profileId"      TEXT           NOT NULL REFERENCES "CrushingGradingProfile" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL REFERENCES "Product" (id),
    "sizeLabel"      TEXT,
    role             TEXT           NOT NULL CHECK (role IN ('main', 'co_product', 'by_product', 'waste')),
    -- Circuit stage (sequence) that yields it; NULL = the last stage.
    "stageSequence"  INTEGER        CHECK ("stageSequence" > 0),
    "expectedPct"    NUMERIC(9, 4)  NOT NULL CHECK ("expectedPct" > 0 AND "expectedPct" <= 100),
    stocked          BOOLEAN        NOT NULL DEFAULT true,
    "stockpileId"    TEXT           REFERENCES "QuarryStockpile" (id),
    valuation        TEXT           NOT NULL DEFAULT 'allocated' CHECK (valuation IN ('allocated', 'nrv', 'zero')),
    "nrvUnitValue"   NUMERIC(20, 6) CHECK ("nrvUnitValue" >= 0),
    "marketValue"    NUMERIC(20, 6) CHECK ("marketValue" >= 0),
    "fixedPct"       NUMERIC(9, 4)  CHECK ("fixedPct" >= 0 AND "fixedPct" <= 100),
    "sortOrder"      INTEGER        NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrushingGradingLine_org_profile_idx" ON "CrushingGradingLine" ("organizationId", "profileId");

-- ---------------------------------------------------------------- crushing runs (by shift)

CREATE TABLE "CrushingRun" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    "plantId"             TEXT           NOT NULL REFERENCES "CrushingPlant" (id),
    "profileId"           TEXT           NOT NULL REFERENCES "CrushingGradingProfile" (id),
    "runDate"             DATE           NOT NULL,
    shift                 TEXT           NOT NULL DEFAULT 'day' CHECK (shift IN ('day', 'night', 'morning', 'afternoon')),
    "operatorName"        TEXT,
    "feedStockpileId"     TEXT           NOT NULL REFERENCES "QuarryStockpile" (id),
    "feedProductId"       TEXT           NOT NULL REFERENCES "Product" (id),
    "feedTonnes"          NUMERIC(14, 3) NOT NULL CHECK ("feedTonnes" > 0),
    "feedMethod"          TEXT           NOT NULL DEFAULT 'belt_scale'
        CHECK ("feedMethod" IN ('belt_scale', 'weighbridge', 'loads', 'survey')),
    -- How the product split was measured: tonnes per fraction, or % of the feed (survey).
    "outputMethod"        TEXT           NOT NULL DEFAULT 'belt_scale'
        CHECK ("outputMethod" IN ('belt_scale', 'weighbridge', 'survey_pct')),
    -- Oversize sent back to the crusher (closed circuit) — measured, never stocked.
    "recirculatedTonnes"  NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK ("recirculatedTonnes" >= 0),
    "operatingHours"      NUMERIC(10, 2) CHECK ("operatingHours" >= 0),
    "downtimeHours"       NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK ("downtimeHours" >= 0),
    "downtimeReason"      TEXT,
    "labourHours"         NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK ("labourHours" >= 0),
    status                TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'posted', 'reversed')),
    "productionOrderId"   TEXT           REFERENCES "ProductionOrder" (id),
    -- Snapshot when posted.
    "outputTonnes"        NUMERIC(14, 3),
    "lossTonnes"          NUMERIC(14, 3),
    "actualYieldPct"      NUMERIC(9, 4),
    "expectedYieldPct"    NUMERIC(9, 4),
    "feedCost"            NUMERIC(18, 2),
    "conversionCost"      NUMERIC(18, 2),
    "abnormalLossValue"   NUMERIC(18, 2),
    "totalCost"           NUMERIC(18, 2),
    "costPerTonne"        NUMERIC(18, 6),
    notes                 TEXT,
    "postedAt"            TIMESTAMP(3),
    "postedBy"            TEXT,
    "reversedAt"          TIMESTAMP(3),
    "reversedBy"          TEXT,
    "reversalReason"      TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3)
);
CREATE UNIQUE INDEX "CrushingRun_org_code_key" ON "CrushingRun" ("organizationId", code);
CREATE INDEX "CrushingRun_org_plant_date_idx" ON "CrushingRun" ("organizationId", "plantId", "runDate");

-- Actual output per size fraction (tonnes, or % of feed for a survey split); value and unit cost once posted.
CREATE TABLE "CrushingRunOutput" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"          TEXT           NOT NULL REFERENCES "CrushingRun" (id) ON DELETE CASCADE,
    "gradingLineId"  TEXT           NOT NULL REFERENCES "CrushingGradingLine" (id),
    "productId"      TEXT           NOT NULL REFERENCES "Product" (id),
    role             TEXT           NOT NULL,
    "stageSequence"  INTEGER,
    "stockpileId"    TEXT           REFERENCES "QuarryStockpile" (id),
    "expectedPct"    NUMERIC(9, 4)  NOT NULL,
    "expectedTonnes" NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "actualPct"      NUMERIC(9, 4),
    "actualTonnes"   NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK ("actualTonnes" >= 0),
    value            NUMERIC(18, 6),
    "unitCost"       NUMERIC(18, 6),
    "sortOrder"      INTEGER        NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrushingRunOutput_run_line_key" ON "CrushingRunOutput" ("runId", "gradingLineId");
CREATE INDEX "CrushingRunOutput_org_idx" ON "CrushingRunOutput" ("organizationId");

-- Meter readings per equipment unit: engine hours and kWh (start / end), diesel litres.
CREATE TABLE "CrushingRunMeter" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"          TEXT           NOT NULL REFERENCES "CrushingRun" (id) ON DELETE CASCADE,
    "unitId"         TEXT           NOT NULL REFERENCES "CrushingPlantUnit" (id),
    "hoursStart"     NUMERIC(14, 2),
    "hoursEnd"       NUMERIC(14, 2),
    "runHours"       NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK ("runHours" >= 0),
    "kwhStart"       NUMERIC(16, 2),
    "kwhEnd"         NUMERIC(16, 2),
    kwh              NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (kwh >= 0),
    "dieselLitres"   NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK ("dieselLitres" >= 0),
    notes            TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrushingRunMeter_run_unit_key" ON "CrushingRunMeter" ("runId", "unitId");
CREATE INDEX "CrushingRunMeter_org_idx" ON "CrushingRunMeter" ("organizationId");

-- Conversion cost of a run: manual lines (wear parts, maintenance, other) on the draft; at posting the derived
-- diesel / electricity / labour lines (auto = true) are added — the cost snapshot that went to the stages.
CREATE TABLE "CrushingRunCost" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"          TEXT           NOT NULL REFERENCES "CrushingRun" (id) ON DELETE CASCADE,
    kind             TEXT           NOT NULL
        CHECK (kind IN ('diesel', 'electricity', 'labour', 'wear_parts', 'maintenance', 'other')),
    "unitId"         TEXT           REFERENCES "CrushingPlantUnit" (id),
    "stageSequence"  INTEGER,
    quantity         NUMERIC(14, 3),
    "unitRate"       NUMERIC(14, 4),
    amount           NUMERIC(18, 2) NOT NULL CHECK (amount >= 0),
    description      TEXT,
    auto             BOOLEAN        NOT NULL DEFAULT false,
    "stagePostingId" TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrushingRunCost_org_run_idx" ON "CrushingRunCost" ("organizationId", "runId");

-- ---------------------------------------------------------------- stockpile surveys

CREATE TABLE "CrushingStockpileSurvey" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code               TEXT           NOT NULL,
    "stockpileId"      TEXT           NOT NULL REFERENCES "QuarryStockpile" (id),
    "productId"        TEXT           NOT NULL REFERENCES "Product" (id),
    "surveyDate"       DATE           NOT NULL,
    method             TEXT           NOT NULL DEFAULT 'drone'
        CHECK (method IN ('drone', 'total_station', 'gps', 'tape', 'visual', 'other')),
    surveyor           TEXT,
    "volumeM3"         NUMERIC(14, 3) NOT NULL CHECK ("volumeM3" >= 0),
    "bulkDensity"      NUMERIC(8, 4)  NOT NULL CHECK ("bulkDensity" > 0),
    "surveyedTonnes"   NUMERIC(14, 3) NOT NULL,
    -- Book stock when submitted; the adjustment applies (surveyed − book) to the stock on hand at approval.
    "bookTonnes"       NUMERIC(14, 3),
    "varianceTonnes"   NUMERIC(14, 3),
    "variancePct"      NUMERIC(9, 4),
    "unitCost"         NUMERIC(18, 6),
    "varianceValue"    NUMERIC(18, 2),
    status             TEXT           NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'submitted', 'posted', 'rejected', 'reversed')),
    "ledgerCode"       TEXT,
    notes              TEXT,
    "submittedAt"      TIMESTAMP(3),
    "submittedBy"      TEXT,
    "decidedAt"        TIMESTAMP(3),
    "decidedBy"        TEXT,
    "rejectionReason"  TEXT,
    "reversedAt"       TIMESTAMP(3),
    "reversedBy"       TEXT,
    "reversalReason"   TEXT,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3)
);
CREATE UNIQUE INDEX "CrushingStockpileSurvey_org_code_key" ON "CrushingStockpileSurvey" ("organizationId", code);
CREATE INDEX "CrushingStockpileSurvey_org_pile_idx" ON "CrushingStockpileSurvey" ("organizationId", "stockpileId", "surveyDate");

-- ---------------------------------------------------------------- tenancy

ALTER TABLE "CrushingPlant" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingPlant" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingPlantStage" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingPlantStage" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingPlantUnit" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingPlantUnit" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingGradingProfile" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingGradingProfile" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingGradingLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingGradingLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingRun" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingRun" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingRunOutput" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingRunOutput" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingRunMeter" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingRunMeter" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingRunCost" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingRunCost" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrushingStockpileSurvey" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrushingStockpileSurvey" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions (ADR-S016)

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['crushing:read', 'crushing:setup', 'crushing_run:record', 'crushing_run:post',
                  'crushing_survey:record', 'crushing_survey:approve']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['crushing:read', 'crushing:setup',
        'crushing_run:record', 'crushing_run:post', 'crushing_survey:record', 'crushing_survey:approve'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
