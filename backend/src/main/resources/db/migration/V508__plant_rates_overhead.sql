-- V486 (ADR-S072): plant / equipment rate table for job costing, fleet trips and work orders charged to projects,
-- and overhead absorption rules with idempotent period runs.

-- Plant rates: a fleet unit (vehicle or fixed plant), a fixed asset or a free plant code, priced per hour, day or km
-- over an effective window. The credit of a charge goes to plant recovery on the plant's cost centre.
CREATE TABLE "ProjectPlantRate" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "equipmentType"  TEXT NOT NULL,
    "equipmentId"    TEXT NOT NULL,
    name             TEXT NOT NULL,
    unit             TEXT NOT NULL,
    rate             NUMERIC(20, 6) NOT NULL CHECK (rate > 0),
    "validFrom"      DATE NOT NULL,
    "validTo"        DATE,
    "costCenterId"   TEXT,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectPlantRate_type" CHECK ("equipmentType" IN ('vehicle', 'asset', 'code')),
    CONSTRAINT "ProjectPlantRate_unit" CHECK (unit IN ('hour', 'day', 'km')),
    CONSTRAINT "ProjectPlantRate_dates" CHECK ("validTo" IS NULL OR "validTo" >= "validFrom")
);
CREATE UNIQUE INDEX "ProjectPlantRate_key" ON "ProjectPlantRate" ("organizationId", "equipmentType", "equipmentId",
    unit, "validFrom");
CREATE INDEX "ProjectPlantRate_lookup_idx" ON "ProjectPlantRate" ("organizationId", "equipmentId");

-- Overhead absorption rules: % of direct cost (optionally of one cost element) or an amount per labour hour, for one
-- project or (projectId NULL) every project.
CREATE TABLE "ProjectOverheadRule" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT REFERENCES "Project" (id),
    name             TEXT NOT NULL,
    basis            TEXT NOT NULL,
    "costElement"    TEXT,
    rate             NUMERIC(20, 6) NOT NULL CHECK (rate > 0),
    "validFrom"      DATE,
    "validTo"        DATE,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectOverheadRule_basis" CHECK (basis IN ('direct_cost_pct', 'labour_hour')),
    CONSTRAINT "ProjectOverheadRule_element" CHECK ("costElement" IS NULL OR "costElement" IN ('material', 'labour',
        'equipment', 'subcontract', 'other')),
    CONSTRAINT "ProjectOverheadRule_dates" CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);
CREATE INDEX "ProjectOverheadRule_project_idx" ON "ProjectOverheadRule" ("organizationId", "projectId");

-- One posted run per project and period (YYYY-MM); a reversed run frees the period for a new run.
CREATE TABLE "ProjectOverheadRun" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"       TEXT NOT NULL REFERENCES "Project" (id),
    period            TEXT NOT NULL CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    "directCost"      NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "labourHours"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    amount            NUMERIC(20, 2) NOT NULL DEFAULT 0,
    detail            JSONB NOT NULL DEFAULT '[]'::jsonb,
    status            TEXT NOT NULL DEFAULT 'posted',
    "journalEntryId"  TEXT,
    "reversalEntryId" TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedBy"      TEXT,
    "reversedAt"      TIMESTAMP(3),
    CONSTRAINT "ProjectOverheadRun_status" CHECK (status IN ('posted', 'reversed'))
);
CREATE UNIQUE INDEX "ProjectOverheadRun_period" ON "ProjectOverheadRun" ("projectId", period) WHERE status = 'posted';

ALTER TABLE "ProjectCostLine" DROP CONSTRAINT "ProjectCostLine_source";
ALTER TABLE "ProjectCostLine" ADD CONSTRAINT "ProjectCostLine_source" CHECK ("sourceType" IN ('project_issue',
    'project_timesheet', 'project_equipment', 'project_reclass', 'project_overhead', 'project_subcontract',
    'project_back_charge', 'project_fleet'));
-- A fleet trip or work order is charged to a project once.
CREATE UNIQUE INDEX "ProjectCostLine_fleet" ON "ProjectCostLine" ("sourceLineId")
    WHERE "sourceType" = 'project_fleet' AND status = 'posted';

-- Fleet trips and work orders can carry the project (and WBS element) they serve.
ALTER TABLE "FleetLoad"
    ADD COLUMN "projectId" TEXT REFERENCES "Project" (id),
    ADD COLUMN "wbsId"     TEXT REFERENCES "ProjectWbsElement" (id);
ALTER TABLE "FleetWorkOrder"
    ADD COLUMN "projectId" TEXT REFERENCES "Project" (id),
    ADD COLUMN "wbsId"     TEXT REFERENCES "ProjectWbsElement" (id);
CREATE INDEX "FleetLoad_project_idx" ON "FleetLoad" ("organizationId", "projectId") WHERE "projectId" IS NOT NULL;
CREATE INDEX "FleetWorkOrder_project_idx" ON "FleetWorkOrder" ("organizationId", "projectId")
    WHERE "projectId" IS NOT NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ProjectPlantRate', 'ProjectOverheadRule', 'ProjectOverheadRun'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
