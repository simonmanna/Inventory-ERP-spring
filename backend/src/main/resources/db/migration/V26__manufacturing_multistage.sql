-- V26 (placeholder, renumbered at merge): multi-stage production & WIP between stages (ADR-S011, Phase 2.1).
--
-- Additive only: new tables plus one nullable column on "ProductionOutput". Orders without stages keep the exact
-- ADR-S006 / ADR-S008 behaviour; a production order becomes multi-stage once "ProductionOrderStage" rows exist.

-- ---------------------------------------------------------------- set-up: routing stages

-- Stage behaviour of a routing operation: how its materials are issued, which intermediate (semi-finished) product
-- it puts into which stage location, its expected yield and whether a QC checkpoint gates the next stage.
CREATE TABLE "RoutingStageSetup" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "routingOperationId" TEXT           NOT NULL REFERENCES "RoutingOperation" (id) ON DELETE CASCADE,
    "issueMode"          TEXT           NOT NULL DEFAULT 'backflush' CHECK ("issueMode" IN ('backflush', 'manual')),
    -- Intermediate product of a non-final stage (NULL = pass-through: cost moves on in the WIP sub-ledger).
    "outputProductId"    TEXT           REFERENCES "Product" (id),
    -- Intermediate units per unit of the order's output (planned stage output = order planned qty × this).
    "outputPerUnit"      NUMERIC(20, 6) NOT NULL DEFAULT 1 CHECK ("outputPerUnit" > 0),
    "stageLocationId"    TEXT           REFERENCES "InventoryLocation" (id),
    "expectedYieldPct"   NUMERIC(9, 4)  NOT NULL DEFAULT 100 CHECK ("expectedYieldPct" > 0 AND "expectedYieldPct" <= 100),
    "qcGate"             BOOLEAN        NOT NULL DEFAULT false,
    "qcCheckpointCode"   TEXT,
    -- Component products consumed at this stage (unassigned BOM components go to the first stage).
    "materialProductIds" JSONB          NOT NULL DEFAULT '[]'::jsonb,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "RoutingStageSetup_operation_key" ON "RoutingStageSetup" ("routingOperationId");
CREATE INDEX "RoutingStageSetup_org_idx" ON "RoutingStageSetup" ("organizationId");

-- ---------------------------------------------------------------- set-up: co-products, by-products, waste

CREATE TABLE "BomCoProduct" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "bomId"              TEXT           NOT NULL REFERENCES "Bom" (id) ON DELETE CASCADE,
    -- Stage (routing operation) that yields it; NULL = the final stage.
    "routingOperationId" TEXT           REFERENCES "RoutingOperation" (id) ON DELETE SET NULL,
    "productId"          TEXT           NOT NULL REFERENCES "Product" (id),
    role                 TEXT           NOT NULL CHECK (role IN ('co_product', 'by_product', 'waste')),
    -- Expected quantity per BOM outputQuantity (like a BOM line).
    quantity             NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    "uomId"              TEXT,
    -- allocated = shares the joint cost; nrv = carved out at net realisable value; zero = no cost.
    valuation            TEXT           NOT NULL DEFAULT 'allocated' CHECK (valuation IN ('allocated', 'nrv', 'zero')),
    "nrvUnitValue"       NUMERIC(20, 6) CHECK ("nrvUnitValue" >= 0),
    "marketValue"        NUMERIC(20, 6) CHECK ("marketValue" >= 0),
    "weightPerUnit"      NUMERIC(20, 6) CHECK ("weightPerUnit" >= 0),
    "fixedPct"           NUMERIC(9, 4)  CHECK ("fixedPct" >= 0 AND "fixedPct" <= 100),
    -- false = recorded only (e.g. unusable fines), nothing is received into stock.
    stocked              BOOLEAN        NOT NULL DEFAULT true,
    "locationId"         TEXT           REFERENCES "InventoryLocation" (id),
    sequence             INTEGER        NOT NULL DEFAULT 0,
    notes                TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "BomCoProduct_org_bom_idx" ON "BomCoProduct" ("organizationId", "bomId");

-- Joint-cost allocation rule: the organization default ("bomId" NULL) or a BOM override.
CREATE TABLE "MfgCostAllocationRule" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "bomId"             TEXT           REFERENCES "Bom" (id) ON DELETE CASCADE,
    method              TEXT           NOT NULL DEFAULT 'quantity'
        CHECK (method IN ('quantity', 'weight', 'market_value', 'fixed_pct')),
    "mainWeightPerUnit" NUMERIC(20, 6) CHECK ("mainWeightPerUnit" >= 0),
    "mainMarketValue"   NUMERIC(20, 6) CHECK ("mainMarketValue" >= 0),
    notes               TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "MfgCostAllocationRule_org_bom_key" ON "MfgCostAllocationRule" ("organizationId", (coalesce("bomId", '')));

-- ---------------------------------------------------------------- execution: order stages

CREATE TABLE "ProductionOrderStage" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"            TEXT           NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    "routingOperationId" TEXT,
    sequence             INTEGER        NOT NULL,
    name                 TEXT           NOT NULL,
    "workCenterId"       TEXT,
    "issueMode"          TEXT           NOT NULL DEFAULT 'backflush' CHECK ("issueMode" IN ('backflush', 'manual')),
    "outputProductId"    TEXT,
    "stageLocationId"    TEXT,
    "plannedOutputQty"   NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "expectedYieldPct"   NUMERIC(9, 4)  NOT NULL DEFAULT 100,
    "isLast"             BOOLEAN        NOT NULL DEFAULT false,
    "qcGate"             BOOLEAN        NOT NULL DEFAULT false,
    "qcCheckpointCode"   TEXT,
    status               TEXT           NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'in_progress', 'completed', 'cancelled')),
    "qcStatus"           TEXT           NOT NULL DEFAULT 'not_required'
        CHECK ("qcStatus" IN ('not_required', 'pending', 'passed', 'failed')),
    "qcReference"        TEXT,
    "qcNotes"            TEXT,
    "qcDecidedAt"        TIMESTAMP(3),
    "qcDecidedBy"        TEXT,
    "qtyGood"            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qtyScrap"           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qtyProcessed"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qtyQuarantined"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "materialCost"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "transferredInCost"  NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "conversionCost"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "outputCost"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "scrapCost"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "startedAt"          TIMESTAMP(3),
    "completedAt"        TIMESTAMP(3),
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductionOrderStage_order_sequence_key" ON "ProductionOrderStage" ("orderId", sequence);
CREATE INDEX "ProductionOrderStage_org_status_idx" ON "ProductionOrderStage" ("organizationId", status);

-- One stage transaction (issue / output / conversion / QC release); its stock moves and journals carry
-- sourceType 'production_stage' + sourceId = this id, so a reversal mirrors exactly what it posted.
CREATE TABLE "ProductionStagePosting" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"          TEXT           NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    "stageId"          TEXT           NOT NULL REFERENCES "ProductionOrderStage" (id) ON DELETE CASCADE,
    code               TEXT           NOT NULL,
    kind               TEXT           NOT NULL CHECK (kind IN ('issue', 'output', 'conversion', 'qc_release')),
    "postedAt"         TIMESTAMP(3)   NOT NULL,
    "qtyGood"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qtyScrap"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qtyAbnormalScrap" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "completesStage"   BOOLEAN        NOT NULL DEFAULT false,
    "allocationMethod" TEXT,
    "scrapReasonCode"  TEXT,
    notes              TEXT,
    "reversedAt"       TIMESTAMP(3),
    "reversalReason"   TEXT,
    "reversedBy"       TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductionStagePosting_org_code_key" ON "ProductionStagePosting" ("organizationId", code);
CREATE INDEX "ProductionStagePosting_org_order_idx" ON "ProductionStagePosting" ("organizationId", "orderId");

-- Lines of a stage posting. Roles adding to the stage's WIP: material, intermediate_in, transfer_in, conversion;
-- relieving it: main_output, co_product, by_product, waste, abnormal_scrap, transfer_out. qc_release moves
-- quarantined stage output to its destination (no WIP effect).
CREATE TABLE "ProductionStagePostingLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "postingId"       TEXT           NOT NULL REFERENCES "ProductionStagePosting" (id) ON DELETE CASCADE,
    "stageId"         TEXT           NOT NULL REFERENCES "ProductionOrderStage" (id) ON DELETE CASCADE,
    role              TEXT           NOT NULL CHECK (role IN ('material', 'intermediate_in', 'transfer_in',
        'conversion', 'main_output', 'co_product', 'by_product', 'waste', 'abnormal_scrap', 'transfer_out',
        'qc_release')),
    "productId"       TEXT,
    quantity          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitCost"        NUMERIC(20, 6) NOT NULL DEFAULT 0,
    value             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "locationId"      TEXT,
    "ledgerCode"      TEXT,
    "materialId"      TEXT,
    "outputId"        TEXT,
    "costSource"      TEXT,
    "sourceRef"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProductionStagePostingLine_org_posting_idx" ON "ProductionStagePostingLine" ("organizationId", "postingId");
CREATE INDEX "ProductionStagePostingLine_org_stage_idx" ON "ProductionStagePostingLine" ("organizationId", "stageId");

-- ---------------------------------------------------------------- multi-level orders: pegging

CREATE TABLE "ProductionOrderPeg" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "parentOrderId"    TEXT           NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    "childOrderId"     TEXT           NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    "parentMaterialId" TEXT,
    "productId"        TEXT           NOT NULL,
    quantity           NUMERIC(20, 6) NOT NULL,
    level              INTEGER        NOT NULL DEFAULT 1,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductionOrderPeg_child_key" ON "ProductionOrderPeg" ("childOrderId");
CREATE INDEX "ProductionOrderPeg_org_parent_idx" ON "ProductionOrderPeg" ("organizationId", "parentOrderId");

-- ---------------------------------------------------------------- order outputs: role of a stage output

-- NULL on legacy orders; main / co_product / by_product / waste on multi-stage orders.
ALTER TABLE "ProductionOutput" ADD COLUMN "outputRole" TEXT
    CHECK ("outputRole" IS NULL OR "outputRole" IN ('main', 'co_product', 'by_product', 'waste'));

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "RoutingStageSetup" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "RoutingStageSetup" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "BomCoProduct" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "BomCoProduct" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "MfgCostAllocationRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MfgCostAllocationRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionOrderStage" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionOrderStage" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionStagePosting" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionStagePosting" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionStagePostingLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionStagePostingLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionOrderPeg" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionOrderPeg" USING (("organizationId" = current_setting('app.org_id', true)));
