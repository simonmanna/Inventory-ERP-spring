-- V9: production-ready manufacturing (ADR-S006).
--
-- Additive extension beyond the NestJS reference: new tables plus nullable / defaulted columns on manufacturing
-- tables only. Existing rows keep their Nest meaning; every Nest route still behaves as before except where
-- docs/spec/manufacturing.md "Deviations" lists a bug fix.

-- ---------------------------------------------------------------- shop floor (packages A + C)

-- Operator / resource time logs. Labour minutes of a work order = sum of its closed logs (fixes the Nest pause
-- maths, which re-counted time since the first start on every pause).
CREATE TABLE "WorkOrderTimeLog" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workOrderId"    TEXT         NOT NULL REFERENCES "WorkOrder" (id) ON DELETE CASCADE,
    "resourceId"     TEXT,
    "userId"         TEXT,
    kind             TEXT         NOT NULL DEFAULT 'run' CHECK (kind IN ('run', 'setup', 'manual')),
    "startedAt"      TIMESTAMP(3) NOT NULL,
    "endedAt"        TIMESTAMP(3),
    minutes          INTEGER      NOT NULL DEFAULT 0 CHECK (minutes >= 0),
    notes            TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "WorkOrderTimeLog_org_workOrder_idx" ON "WorkOrderTimeLog" ("organizationId", "workOrderId");

ALTER TABLE "WorkOrder"
    ADD COLUMN "plannedStartAt"  TIMESTAMP(3),
    ADD COLUMN "plannedEndAt"    TIMESTAMP(3),
    ADD COLUMN "scrapReasonCode" TEXT,
    ADD COLUMN "subcontractCost" NUMERIC(20, 6) NOT NULL DEFAULT 0;
CREATE INDEX "WorkOrder_org_workCenter_plannedStart_idx" ON "WorkOrder" ("organizationId", "workCenterId", "plannedStartAt");

ALTER TABLE "ProductionOrder"
    ADD COLUMN "parentOrderId" TEXT REFERENCES "ProductionOrder" (id) ON DELETE SET NULL,
    ADD COLUMN "orderKind"     TEXT NOT NULL DEFAULT 'standard' CHECK ("orderKind" IN ('standard', 'rework')),
    ADD COLUMN "standardCostId" TEXT;

ALTER TABLE "WorkCenter"
    ADD COLUMN "costPerHour"   NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("costPerHour" >= 0),
    ADD COLUMN "efficiencyPct" NUMERIC(9, 4)  NOT NULL DEFAULT 100 CHECK ("efficiencyPct" > 0),
    ADD COLUMN "parallelSlots" INTEGER        NOT NULL DEFAULT 1 CHECK ("parallelSlots" >= 1);

-- Weekly working pattern of a work centre (minutes from midnight, UTC business clock). No rows = 24h.
CREATE TABLE "WorkCenterShift" (
    id               TEXT     NOT NULL PRIMARY KEY,
    "organizationId" TEXT     NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workCenterId"   TEXT     NOT NULL REFERENCES "WorkCenter" (id) ON DELETE CASCADE,
    weekday          SMALLINT NOT NULL CHECK (weekday BETWEEN 0 AND 6),
    "startMinute"    INTEGER  NOT NULL CHECK ("startMinute" BETWEEN 0 AND 1439),
    "endMinute"      INTEGER  NOT NULL CHECK ("endMinute" BETWEEN 1 AND 1440),
    CHECK ("endMinute" > "startMinute")
);
CREATE INDEX "WorkCenterShift_org_wc_idx" ON "WorkCenterShift" ("organizationId", "workCenterId");

-- Non-working days; "workCenterId" NULL = the whole plant.
CREATE TABLE "WorkCenterClosure" (
    id               TEXT NOT NULL PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workCenterId"   TEXT REFERENCES "WorkCenter" (id) ON DELETE CASCADE,
    date             DATE NOT NULL,
    reason           TEXT
);
CREATE INDEX "WorkCenterClosure_org_date_idx" ON "WorkCenterClosure" ("organizationId", date);

CREATE TABLE "ScrapReason" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "ScrapReason_org_code_live_key" ON "ScrapReason" ("organizationId", code) WHERE "deletedAt" IS NULL;

-- Inspection template: items = [{key, label, type: boolean|number|text, required, min, max}].
CREATE TABLE "QcPlan" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    "productId"      TEXT,
    "bomId"          TEXT,
    items            JSONB        NOT NULL DEFAULT '[]',
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "QcPlan_org_product_idx" ON "QcPlan" ("organizationId", "productId");

-- ---------------------------------------------------------------- MRP (package B)

-- Per-product planning parameters (make vs buy, lead time, safety stock, lot sizing).
CREATE TABLE "ProductPlanning" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL REFERENCES "Product" (id) ON DELETE CASCADE,
    procurement      TEXT           NOT NULL DEFAULT 'buy' CHECK (procurement IN ('make', 'buy')),
    "leadTimeDays"   INTEGER        NOT NULL DEFAULT 0 CHECK ("leadTimeDays" >= 0),
    "safetyStock"    NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("safetyStock" >= 0),
    "reorderPoint"   NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("reorderPoint" >= 0),
    "minOrderQty"    NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minOrderQty" >= 0),
    "orderMultiple"  NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("orderMultiple" >= 0),
    "supplierId"     TEXT,
    "locationId"     TEXT,
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductPlanning_org_product_key" ON "ProductPlanning" ("organizationId", "productId");

CREATE TABLE "MrpRun" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runNumber"      TEXT         NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'completed' CHECK (status IN ('completed', 'failed', 'superseded')),
    "horizonDays"    INTEGER      NOT NULL DEFAULT 30,
    "locationId"     TEXT,
    summary          JSONB        NOT NULL DEFAULT '{}',
    "startedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completedAt"    TIMESTAMP(3),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "MrpRun_org_runNumber_key" ON "MrpRun" ("organizationId", "runNumber");

-- One row per product the run planned: the netting worksheet.
CREATE TABLE "MrpRunItem" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL,
    "runId"              TEXT           NOT NULL REFERENCES "MrpRun" (id) ON DELETE CASCADE,
    "productId"          TEXT           NOT NULL,
    "lowLevelCode"       INTEGER        NOT NULL DEFAULT 0,
    procurement          TEXT           NOT NULL,
    "grossRequirement"   NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "onHand"             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    reserved             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "scheduledReceipts"  NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "safetyStock"        NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "netRequirement"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "plannedQty"         NUMERIC(20, 6) NOT NULL DEFAULT 0
);
CREATE INDEX "MrpRunItem_run_idx" ON "MrpRunItem" ("runId");

CREATE TABLE "MrpPlannedOrder" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"          TEXT           NOT NULL REFERENCES "MrpRun" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL,
    kind             TEXT           NOT NULL CHECK (kind IN ('make', 'buy')),
    quantity         NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "uomId"          TEXT,
    "bomId"          TEXT,
    "supplierId"     TEXT,
    "locationId"     TEXT,
    "dueDate"        TIMESTAMP(3)   NOT NULL,
    "releaseDate"    TIMESTAMP(3)   NOT NULL,
    "lowLevelCode"   INTEGER        NOT NULL DEFAULT 0,
    status           TEXT           NOT NULL DEFAULT 'planned' CHECK (status IN ('planned', 'firmed', 'cancelled')),
    "firmedDocType"  TEXT,
    "firmedDocId"    TEXT,
    pegging          JSONB          NOT NULL DEFAULT '[]',
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "MrpPlannedOrder_org_run_idx" ON "MrpPlannedOrder" ("organizationId", "runId");

-- ---------------------------------------------------------------- costing & engineering (package D)

ALTER TABLE "RoutingOperation"
    ADD COLUMN "isSubcontracted"        BOOLEAN        NOT NULL DEFAULT false,
    ADD COLUMN "subcontractorId"        TEXT,
    ADD COLUMN "subcontractCostPerUnit" NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("subcontractCostPerUnit" >= 0);

-- Alternative components: "ratio" units of the substitute replace 1 unit of the BOM line's component.
CREATE TABLE "BomLineSubstitute" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL,
    "bomLineId"      TEXT           NOT NULL REFERENCES "BomLine" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL,
    ratio            NUMERIC(20, 6) NOT NULL DEFAULT 1 CHECK (ratio > 0),
    priority         INTEGER        NOT NULL DEFAULT 0,
    notes            TEXT
);
CREATE INDEX "BomLineSubstitute_line_idx" ON "BomLineSubstitute" ("bomLineId");

-- Frozen standard cost of a BOM version (one run = outputQuantity units).
CREATE TABLE "BomStandardCost" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "bomId"           TEXT           NOT NULL REFERENCES "Bom" (id) ON DELETE CASCADE,
    "bomVersion"      INTEGER        NOT NULL,
    "outputQuantity"  NUMERIC(20, 6) NOT NULL,
    "materialCost"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "labourCost"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "overheadCost"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "subcontractCost" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "totalCost"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitCost"        NUMERIC(20, 6) NOT NULL DEFAULT 0,
    breakdown         JSONB          NOT NULL DEFAULT '[]',
    "isCurrent"       BOOLEAN        NOT NULL DEFAULT true,
    "createdBy"       TEXT,
    "computedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "BomStandardCost_org_bom_idx" ON "BomStandardCost" ("organizationId", "bomId");

-- Analytic variances of a completed order against its standard (no GL effect: stock is actual-costed).
CREATE TABLE "ProductionVariance" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT           NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    kind             TEXT           NOT NULL CHECK (kind IN ('material_price', 'material_usage', 'labour', 'overhead',
                                                             'subcontract', 'yield')),
    "productId"      TEXT,
    "standardQty"    NUMERIC(20, 6),
    "actualQty"      NUMERIC(20, 6),
    "standardCost"   NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "actualCost"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    amount           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProductionVariance_org_order_idx" ON "ProductionVariance" ("organizationId", "orderId");

-- Engineering change order: a reviewed revision from one BOM version to the next.
CREATE TABLE "EngineeringChange" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number           TEXT         NOT NULL,
    title            TEXT         NOT NULL,
    reason           TEXT,
    "bomId"          TEXT         NOT NULL REFERENCES "Bom" (id),
    "newBomId"       TEXT         NOT NULL REFERENCES "Bom" (id),
    status           TEXT         NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'approved', 'applied', 'rejected')),
    "approvedById"   TEXT,
    "approvedAt"     TIMESTAMP(3),
    "appliedAt"      TIMESTAMP(3),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "EngineeringChange_org_number_key" ON "EngineeringChange" ("organizationId", number);

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "WorkOrderTimeLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WorkOrderTimeLog" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "WorkCenterShift" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WorkCenterShift" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "WorkCenterClosure" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WorkCenterClosure" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ScrapReason" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ScrapReason" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QcPlan" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QcPlan" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductPlanning" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductPlanning" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "MrpRun" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MrpRun" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "MrpRunItem" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MrpRunItem" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "MrpPlannedOrder" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MrpPlannedOrder" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "BomLineSubstitute" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "BomLineSubstitute" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "BomStandardCost" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "BomStandardCost" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionVariance" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionVariance" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "EngineeringChange" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "EngineeringChange" USING (("organizationId" = current_setting('app.org_id', true)));
