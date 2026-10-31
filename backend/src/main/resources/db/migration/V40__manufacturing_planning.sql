-- V40: advanced planning & scheduling (Track C 2.5, MFG-1, ADR-S017).
--
-- Additive only: no existing table is changed, so every existing production route keeps its JSON. Demand pegs tie a
-- plan line (and the order it generates) to the sales order, delivery order, forecast or stock policy it serves.
-- ProductionOrderPlanning holds the due date, priority and schedule result of an order. Shift plans and production
-- targets are the two levels of "what we meant to make" that production-vs-plan and production-vs-target compare with.

-- ---------------------------------------------------------------- demand pegging

CREATE TABLE "ProductionDemandPeg" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "planId"            TEXT           NOT NULL REFERENCES "ProductionPlan" (id) ON DELETE CASCADE,
    "planLineId"        TEXT           NOT NULL REFERENCES "ProductionPlanLine" (id) ON DELETE CASCADE,
    -- Set when the plan is confirmed and the line generates its production order.
    "productionOrderId" TEXT           REFERENCES "ProductionOrder" (id) ON DELETE SET NULL,
    "productId"         TEXT           NOT NULL REFERENCES "Product" (id),
    "demandType"        TEXT           NOT NULL
        CHECK ("demandType" IN ('delivery_order', 'sales_order', 'proforma', 'forecast', 'stock_policy')),
    -- The source document and line in their own modules (null for forecast and stock policy).
    "demandId"          TEXT,
    "demandLineId"      TEXT,
    "demandRef"         TEXT,
    "partnerId"         TEXT,
    "siteId"            TEXT,
    "dueAt"             TIMESTAMP(3)   NOT NULL,
    -- 1 low, 2 normal, 3 high, 4 urgent.
    priority            INTEGER        NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 4),
    quantity            NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProductionDemandPeg_org_plan_idx" ON "ProductionDemandPeg" ("organizationId", "planId");
CREATE INDEX "ProductionDemandPeg_line_idx" ON "ProductionDemandPeg" ("planLineId");
CREATE INDEX "ProductionDemandPeg_order_idx" ON "ProductionDemandPeg" ("productionOrderId");
CREATE INDEX "ProductionDemandPeg_demand_idx" ON "ProductionDemandPeg" ("organizationId", "demandType", "demandId");

-- ---------------------------------------------------------------- order planning record

CREATE TABLE "ProductionOrderPlanning" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productionOrderId" TEXT         NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    "dueAt"             TIMESTAMP(3),
    priority            INTEGER      NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 4),
    "priorityReason"    TEXT,
    "partnerId"         TEXT,
    -- Result of the last scheduling pass.
    "scheduleMode"      TEXT         NOT NULL DEFAULT 'none'
        CHECK ("scheduleMode" IN ('none', 'forward', 'backward', 'manual')),
    "plannedStartAt"    TIMESTAMP(3),
    "plannedEndAt"      TIMESTAMP(3),
    -- Earliest moment the output can be released: last operation end plus any curing still to run.
    "availableAt"       TIMESTAMP(3),
    "curingDays"        INTEGER      NOT NULL DEFAULT 0 CHECK ("curingDays" >= 0),
    "lateMinutes"       BIGINT       NOT NULL DEFAULT 0 CHECK ("lateMinutes" >= 0),
    "scheduledAt"       TIMESTAMP(3),
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductionOrderPlanning_order_key" ON "ProductionOrderPlanning" ("productionOrderId");
CREATE INDEX "ProductionOrderPlanning_org_due_idx" ON "ProductionOrderPlanning" ("organizationId", "dueAt");

-- ---------------------------------------------------------------- shift plans

-- What one work centre should make in one shift of one day.
CREATE TABLE "ProductionShiftPlan" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "shiftDate"         DATE           NOT NULL,
    "shiftId"           TEXT           NOT NULL REFERENCES "ShopFloorShift" (id),
    "workCenterId"      TEXT           NOT NULL REFERENCES "WorkCenter" (id),
    "productionOrderId" TEXT           REFERENCES "ProductionOrder" (id) ON DELETE SET NULL,
    "workOrderId"       TEXT           REFERENCES "WorkOrder" (id) ON DELETE SET NULL,
    "productId"         TEXT           REFERENCES "Product" (id),
    "targetQty"         NUMERIC(20, 6) NOT NULL CHECK ("targetQty" > 0),
    "crewSize"          INTEGER        CHECK ("crewSize" >= 0),
    status              TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    notes               TEXT,
    "publishedAt"       TIMESTAMP(3),
    "publishedBy"       TEXT,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProductionShiftPlan_org_date_idx" ON "ProductionShiftPlan" ("organizationId", "shiftDate", "workCenterId");

-- ---------------------------------------------------------------- production targets

-- A production target per work centre, crushing plant or product for a period.
CREATE TABLE "ProductionTarget" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    scope            TEXT           NOT NULL CHECK (scope IN ('work_center', 'crushing_plant', 'product')),
    "workCenterId"   TEXT           REFERENCES "WorkCenter" (id),
    "plantId"        TEXT           REFERENCES "CrushingPlant" (id),
    "productId"      TEXT           REFERENCES "Product" (id),
    "periodFrom"     DATE           NOT NULL,
    "periodTo"       DATE           NOT NULL,
    "targetQty"      NUMERIC(20, 6) NOT NULL CHECK ("targetQty" > 0),
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("periodTo" >= "periodFrom"),
    CHECK ((scope = 'work_center' AND "workCenterId" IS NOT NULL)
        OR (scope = 'crushing_plant' AND "plantId" IS NOT NULL)
        OR (scope = 'product' AND "productId" IS NOT NULL))
);
CREATE INDEX "ProductionTarget_org_period_idx" ON "ProductionTarget" ("organizationId", "periodFrom", "periodTo");

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "ProductionDemandPeg" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionDemandPeg" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionOrderPlanning" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionOrderPlanning" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionShiftPlan" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionShiftPlan" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionTarget" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionTarget" USING (("organizationId" = current_setting('app.org_id', true)));
