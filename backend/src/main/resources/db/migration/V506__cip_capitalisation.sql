-- V494: own-use construction (ADR-S074 §3) — a capital project accumulates its cost in Construction in Progress
-- (mapping construction_in_progress) instead of project WIP 1460; a capitalisation moves all or part of the CIP
-- balance into one or more new fixed assets (Dr fixed_asset_valuation / Cr CIP) and can be reversed while none of
-- those assets has posted depreciation. Assets get an in-service date: depreciation starts in that month.

ALTER TABLE "Project" ADD COLUMN "isCapital" BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE "Asset" ADD COLUMN "inServiceDate" DATE;

CREATE TABLE "ProjectCapitalisation" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"        TEXT NOT NULL REFERENCES "Project" (id),
    number             TEXT NOT NULL,
    "capitalisationDate" DATE NOT NULL,
    amount             NUMERIC(20, 2) NOT NULL CHECK (amount > 0),
    status             TEXT NOT NULL DEFAULT 'posted',
    "journalEntryId"   TEXT,
    "reversalEntryId"  TEXT,
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedBy"       TEXT,
    "reversedAt"       TIMESTAMP(3),
    CONSTRAINT "ProjectCapitalisation_number" UNIQUE ("organizationId", number),
    CONSTRAINT "ProjectCapitalisation_status" CHECK (status IN ('posted', 'reversed'))
);
CREATE INDEX "ProjectCapitalisation_project_idx" ON "ProjectCapitalisation" ("projectId", "createdAt");

CREATE TABLE "ProjectCapitalisationAsset" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "capitalisationId" TEXT NOT NULL REFERENCES "ProjectCapitalisation" (id),
    "assetId"          TEXT NOT NULL REFERENCES "Asset" (id),
    amount             NUMERIC(20, 2) NOT NULL CHECK (amount > 0),
    "inServiceDate"    DATE NOT NULL,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProjectCapitalisationAsset_cap_idx" ON "ProjectCapitalisationAsset" ("capitalisationId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ProjectCapitalisation', 'ProjectCapitalisationAsset'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
