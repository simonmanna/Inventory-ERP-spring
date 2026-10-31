-- PR-3 (roadmap 2.4, ADR-S025): generic analytic dimensions. Extension tables; the free-form "dimensions" jsonb on
-- JournalEntry / JournalLine (baseline) keeps its shape: {"<dimension code>": "<value code>", ...}. A key that matches a
-- defined dimension is validated at posting; other keys (system tags such as vehicleId) pass through unchanged.
-- The cost centre stays in its own column; the reserved code 'costCenter' addresses it in rules and reports, so no
-- data migration is needed.

CREATE TABLE "AnalyticDimension" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    description      TEXT,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "sortOrder"      INTEGER      NOT NULL DEFAULT 0,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AnalyticDimension_code_format" CHECK (code ~ '^[A-Za-z][A-Za-z0-9_]{0,39}$'),
    CONSTRAINT "AnalyticDimension_code_reserved" CHECK (code NOT IN ('costCenter', 'costAllocationRuleId')),
    CONSTRAINT "AnalyticDimension_org_code" UNIQUE ("organizationId", code)
);

CREATE TABLE "AnalyticDimensionValue" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "dimensionId"    TEXT         NOT NULL REFERENCES "AnalyticDimension" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    "parentId"       TEXT REFERENCES "AnalyticDimensionValue" (id) ON DELETE RESTRICT,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "validFrom"      DATE,
    "validTo"        DATE,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AnalyticDimensionValue_dates" CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom"),
    CONSTRAINT "AnalyticDimensionValue_dim_code" UNIQUE ("dimensionId", code)
);
CREATE INDEX "AnalyticDimensionValue_org_dim" ON "AnalyticDimensionValue" ("organizationId", "dimensionId");

-- Account rules generalise from the cost centre to any dimension code.
ALTER TABLE "AccountDimensionRule" DROP CONSTRAINT IF EXISTS "AccountDimensionRule_dimension_check";
ALTER TABLE "AccountDimensionRule" ADD CONSTRAINT "AccountDimensionRule_dimension_format"
    CHECK (dimension ~ '^[A-Za-z][A-Za-z0-9_]{0,39}$');

-- Dimensions on document lines (invoices, vendor bills): carried to the revenue / expense GL lines at posting.
ALTER TABLE "DocumentLine" ADD COLUMN dimensions JSONB;

-- Dimension-filtered reports read JournalLine.dimensions with @> containment.
CREATE INDEX "JournalLine_dimensions_gin" ON "JournalLine" USING gin (dimensions jsonb_path_ops);

-- Row-level security like every tenant table (V214).
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['AnalyticDimension', 'AnalyticDimensionValue'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
