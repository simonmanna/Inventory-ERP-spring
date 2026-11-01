-- V233: Cost Management CM-9 (ADR-S040) — profitability facts in three separate layers (ACTUAL from the booked GL,
-- STANDARD from quantities x standard cost / price, ESTIMATE from approved project budgets) and the ACTUAL
-- reconciliation to the GL P&L (segments + unallocated + out-of-scope accounts).

CREATE TABLE "ProfitabilityRun" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "periodFrom"     DATE NOT NULL,
    "periodTo"       DATE NOT NULL,
    status           TEXT NOT NULL DEFAULT 'current',
    "glNetIncome"    NUMERIC(20, 2) NOT NULL,
    "segmentNet"     NUMERIC(20, 2) NOT NULL,
    unallocated      NUMERIC(20, 2) NOT NULL,
    "outOfScope"     NUMERIC(20, 2) NOT NULL,
    difference       NUMERIC(20, 2) NOT NULL,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProfitabilityRun_status" CHECK (status IN ('current', 'superseded')),
    CONSTRAINT "ProfitabilityRun_period" CHECK ("periodTo" >= "periodFrom")
);
CREATE UNIQUE INDEX "ProfitabilityRun_current" ON "ProfitabilityRun" ("organizationId", "periodFrom", "periodTo")
    WHERE status = 'current';

CREATE TABLE "ProfitabilityFact" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"          TEXT NOT NULL REFERENCES "ProfitabilityRun" (id) ON DELETE CASCADE,
    layer            TEXT NOT NULL,
    measure          TEXT NOT NULL,
    "branchId"       TEXT,
    "costCenterId"   TEXT,
    "customerId"     TEXT,
    "productId"      TEXT,
    "projectId"      TEXT,
    channel          TEXT,
    amount           NUMERIC(20, 2) NOT NULL,
    statistical      BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT "ProfitabilityFact_layer" CHECK (layer IN ('actual', 'standard', 'estimate')),
    CONSTRAINT "ProfitabilityFact_measure" CHECK (measure IN ('revenue', 'cogs', 'variances', 'distribution',
        'overhead', 'statistical_overhead'))
);
CREATE INDEX "ProfitabilityFact_run_idx" ON "ProfitabilityFact" ("runId", layer);

-- Unallocated and out-of-scope P&L balances, listed per account.
CREATE TABLE "ProfitabilityReconLine" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"          TEXT NOT NULL REFERENCES "ProfitabilityRun" (id) ON DELETE CASCADE,
    bucket           TEXT NOT NULL,
    "accountId"      TEXT NOT NULL,
    amount           NUMERIC(20, 2) NOT NULL,
    CONSTRAINT "ProfitabilityReconLine_bucket" CHECK (bucket IN ('unallocated', 'out_of_scope'))
);
CREATE INDEX "ProfitabilityReconLine_run_idx" ON "ProfitabilityReconLine" ("runId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ProfitabilityRun', 'ProfitabilityFact', 'ProfitabilityReconLine'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
