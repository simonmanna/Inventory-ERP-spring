-- V41: manufacturing costing & analytics (Track C 2.8, MFG-2, ADR-S018).
--
-- Additive only. Absorption rates per work centre turn actual hours and meter readings into labour, machine, energy and
-- overhead cost. A posted absorption goes through the stage engine (conversion cost: Dr WIP / Cr overhead absorbed or
-- the rate's credit account) or, for a plain order, into its overhead until the complete leg absorbs it into WIP. The
-- ledger below stops the same hours being absorbed twice, and frees them when the stage posting is reversed.

CREATE TABLE "WorkCenterCostRate" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workCenterId"      TEXT           NOT NULL REFERENCES "WorkCenter" (id) ON DELETE CASCADE,
    "labourRatePerHour" NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("labourRatePerHour" >= 0),
    "machineRatePerHour" NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("machineRatePerHour" >= 0),
    "overheadRatePerHour" NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("overheadRatePerHour" >= 0),
    "overheadBasis"     TEXT           NOT NULL DEFAULT 'machine_hours'
        CHECK ("overheadBasis" IN ('machine_hours', 'labour_hours')),
    -- Cost per unit of the energy meter (kWh, litres) and the meter it reads.
    "energyRatePerUnit" NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("energyRatePerUnit" >= 0),
    "energyMeterCode"   TEXT,
    -- When set, the machine rate is this vehicle's fleet cost per hour over the last fleetCostDays.
    "fleetVehicleId"    TEXT,
    "fleetCostDays"     INTEGER        NOT NULL DEFAULT 90 CHECK ("fleetCostDays" BETWEEN 1 AND 730),
    -- Credit of the absorption; null = the overhead absorbed mapping.
    "creditAccountId"   TEXT           REFERENCES "Account" (id),
    notes               TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "WorkCenterCostRate_wc_key" ON "WorkCenterCostRate" ("workCenterId");
CREATE INDEX "WorkCenterCostRate_org_idx" ON "WorkCenterCostRate" ("organizationId");

CREATE TABLE "ProductionCostAbsorption" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT           NOT NULL REFERENCES "ProductionOrder" (id) ON DELETE CASCADE,
    "workOrderId"    TEXT           NOT NULL REFERENCES "WorkOrder" (id) ON DELETE CASCADE,
    "workCenterId"   TEXT           NOT NULL REFERENCES "WorkCenter" (id),
    "stageId"        TEXT           REFERENCES "ProductionOrderStage" (id) ON DELETE CASCADE,
    -- Null for a plain order: the cost waits in the order's overhead.
    "stagePostingId" TEXT           REFERENCES "ProductionStagePosting" (id) ON DELETE CASCADE,
    kind             TEXT           NOT NULL CHECK (kind IN ('labour', 'machine', 'utility', 'overhead')),
    "basisQty"       NUMERIC(20, 6) NOT NULL,
    rate             NUMERIC(20, 6) NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProductionCostAbsorption_org_order_idx" ON "ProductionCostAbsorption" ("organizationId", "orderId");
CREATE INDEX "ProductionCostAbsorption_wo_idx" ON "ProductionCostAbsorption" ("workOrderId", kind);

ALTER TABLE "WorkCenterCostRate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WorkCenterCostRate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductionCostAbsorption" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductionCostAbsorption" USING (("organizationId" = current_setting('app.org_id', true)));
