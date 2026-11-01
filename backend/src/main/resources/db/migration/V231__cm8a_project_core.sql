-- V231: Cost Management CM-8a (ADR-S038) — project core and job costing: projects, sites, hierarchical WBS,
-- versioned budgets, cost-to-complete forecasts, a project cost sub-ledger written by every capture path (stock
-- issue to WBS / site, timesheets x rate, equipment hours x rate, reclass of posted cost) and the conversion of the
-- free-text procurement "projectId" columns into foreign keys.

CREATE TABLE "Project" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    "customerId"     TEXT REFERENCES "Partner" (id),
    "contractRef"    TEXT,
    "contractValue"  NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK ("contractValue" >= 0),
    "branchId"       TEXT REFERENCES "Branch" (id),
    "costCenterId"   TEXT,
    status           TEXT NOT NULL DEFAULT 'draft',
    "startDate"      DATE,
    "endDate"        DATE,
    "needsReview"    BOOLEAN NOT NULL DEFAULT false,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "Project_code" UNIQUE ("organizationId", code),
    CONSTRAINT "Project_status" CHECK (status IN ('draft', 'active', 'on_hold', 'completed', 'closed', 'cancelled'))
);

CREATE TABLE "ProjectSite" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"          TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    code                 TEXT NOT NULL,
    name                 TEXT NOT NULL,
    address              TEXT,
    "deliveryLocationId" TEXT REFERENCES "InventoryLocation" (id),
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectSite_code" UNIQUE ("projectId", code)
);

CREATE TABLE "ProjectWbsElement" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    "parentId"       TEXT REFERENCES "ProjectWbsElement" (id),
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    "isBillable"     BOOLEAN NOT NULL DEFAULT true,
    "isCostable"     BOOLEAN NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectWbsElement_code" UNIQUE ("projectId", code)
);

CREATE TABLE "ProjectBudget" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    version          INTEGER NOT NULL,
    kind             TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'draft',
    total            NUMERIC(20, 2) NOT NULL DEFAULT 0,
    notes            TEXT,
    "createdBy"      TEXT,
    "approvedBy"     TEXT,
    "approvedAt"     TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectBudget_version" UNIQUE ("projectId", version),
    CONSTRAINT "ProjectBudget_kind" CHECK (kind IN ('original', 'revised')),
    CONSTRAINT "ProjectBudget_status" CHECK (status IN ('draft', 'approved', 'superseded'))
);

CREATE TABLE "ProjectBudgetLine" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "budgetId"       TEXT NOT NULL REFERENCES "ProjectBudget" (id) ON DELETE CASCADE,
    "wbsId"          TEXT NOT NULL REFERENCES "ProjectWbsElement" (id),
    "costElement"    TEXT NOT NULL,
    amount           NUMERIC(20, 2) NOT NULL CHECK (amount >= 0),
    revenue          NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK (revenue >= 0),
    CONSTRAINT "ProjectBudgetLine_element" CHECK ("costElement" IN ('material', 'labour', 'equipment',
        'subcontract', 'other', 'overhead'))
);
CREATE INDEX "ProjectBudgetLine_budget_idx" ON "ProjectBudgetLine" ("budgetId");

-- Cost to complete per WBS element and cost element; the latest row per (project, wbs, element) is in force.
-- "fulfilmentClass" tags each ETC line for the IAS 37.68A onerous-contract assessment (CM-8b).
CREATE TABLE "ProjectForecastLine" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"       TEXT NOT NULL REFERENCES "Project" (id) ON DELETE CASCADE,
    "wbsId"           TEXT NOT NULL REFERENCES "ProjectWbsElement" (id),
    "costElement"     TEXT NOT NULL,
    "costToComplete"  NUMERIC(20, 2) NOT NULL CHECK ("costToComplete" >= 0),
    "fulfilmentClass" TEXT NOT NULL DEFAULT 'incremental',
    "asOf"            DATE NOT NULL,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectForecastLine_element" CHECK ("costElement" IN ('material', 'labour', 'equipment',
        'subcontract', 'other', 'overhead')),
    CONSTRAINT "ProjectForecastLine_class" CHECK ("fulfilmentClass" IN ('incremental', 'directly_related',
        'excluded'))
);
CREATE INDEX "ProjectForecastLine_project_idx" ON "ProjectForecastLine" ("projectId", "wbsId", "costElement",
    "createdAt" DESC);

-- The project cost sub-ledger: one row per captured cost, always with its journal entry (Dr 1450 Project WIP).
CREATE TABLE "ProjectCostLine" (
    id                     TEXT PRIMARY KEY,
    "organizationId"       TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"            TEXT NOT NULL REFERENCES "Project" (id),
    "wbsId"                TEXT NOT NULL REFERENCES "ProjectWbsElement" (id),
    "siteId"               TEXT REFERENCES "ProjectSite" (id),
    "costElement"          TEXT NOT NULL,
    "sourceType"           TEXT NOT NULL,
    "sourceId"             TEXT NOT NULL,
    "sourceLineId"         TEXT,
    "date"                 DATE NOT NULL,
    quantity               NUMERIC(20, 6),
    rate                   NUMERIC(20, 6),
    amount                 NUMERIC(20, 2) NOT NULL,
    "excludeFromProgress"  BOOLEAN NOT NULL DEFAULT false,
    "creditAccountId"      TEXT,
    "journalEntryId"       TEXT,
    "reversalEntryId"      TEXT,
    status                 TEXT NOT NULL DEFAULT 'posted',
    description            TEXT,
    "createdBy"            TEXT,
    "createdAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectCostLine_element" CHECK ("costElement" IN ('material', 'labour', 'equipment',
        'subcontract', 'other', 'overhead')),
    CONSTRAINT "ProjectCostLine_source" CHECK ("sourceType" IN ('project_issue', 'project_timesheet',
        'project_equipment', 'project_reclass', 'project_overhead')),
    CONSTRAINT "ProjectCostLine_status" CHECK (status IN ('posted', 'reversed'))
);
CREATE INDEX "ProjectCostLine_project_idx" ON "ProjectCostLine" ("projectId", "wbsId");
-- A timesheet entry is captured once.
CREATE UNIQUE INDEX "ProjectCostLine_timesheet" ON "ProjectCostLine" ("sourceLineId")
    WHERE "sourceType" = 'project_timesheet' AND status = 'posted';

CREATE INDEX "JournalLine_project_idx" ON "JournalLine" ((dimensions ->> 'projectId'))
    WHERE dimensions ? 'projectId';

-- Free-text procurement project references become foreign keys: unmatched values map to a placeholder project per
-- organisation and value, flagged for review.
INSERT INTO "Project" (id, "organizationId", code, name, status, "needsReview", notes)
SELECT gen_random_uuid()::text, v."organizationId", left('LEGACY-' || md5(v.ref), 20), left(v.ref, 200), 'active',
       true, 'Created from a free-text project reference (V231); review and rename.'
FROM (SELECT DISTINCT "organizationId", "projectId" AS ref FROM "PurchaseRequestLine" WHERE "projectId" IS NOT NULL
      UNION
      SELECT DISTINCT "organizationId", "projectId" FROM "Commitment" WHERE "projectId" IS NOT NULL) v;

UPDATE "PurchaseRequestLine" l SET "projectId" = p.id FROM "Project" p
WHERE l."projectId" IS NOT NULL AND p."organizationId" = l."organizationId" AND p."needsReview"
  AND p.code = left('LEGACY-' || md5(l."projectId"), 20);
UPDATE "Commitment" c SET "projectId" = p.id FROM "Project" p
WHERE c."projectId" IS NOT NULL AND p."organizationId" = c."organizationId" AND p."needsReview"
  AND p.code = left('LEGACY-' || md5(c."projectId"), 20);

ALTER TABLE "PurchaseRequestLine" ADD CONSTRAINT "PurchaseRequestLine_project_fk"
    FOREIGN KEY ("projectId") REFERENCES "Project" (id);
ALTER TABLE "Commitment" ADD CONSTRAINT "Commitment_project_fk"
    FOREIGN KEY ("projectId") REFERENCES "Project" (id);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['Project', 'ProjectSite', 'ProjectWbsElement', 'ProjectBudget', 'ProjectBudgetLine',
        'ProjectForecastLine', 'ProjectCostLine'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
