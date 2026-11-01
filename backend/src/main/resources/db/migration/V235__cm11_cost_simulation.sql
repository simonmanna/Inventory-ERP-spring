-- V235: Cost Management CM-11 (ADR-S042) — cost scenarios (versioned drivers) and simulation results. A simulation
-- never posts: it writes only these two tables.

CREATE TABLE "CostScenario" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    version          INTEGER NOT NULL,
    parameters       JSONB NOT NULL,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CostScenario_version" UNIQUE ("organizationId", name, version)
);

CREATE TABLE "SimulationResult" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "scenarioId"         TEXT NOT NULL REFERENCES "CostScenario" (id) ON DELETE CASCADE,
    "profitabilityRunId" TEXT NOT NULL REFERENCES "ProfitabilityRun" (id),
    "groupBy"            TEXT NOT NULL,
    results              JSONB NOT NULL,
    totals               JSONB NOT NULL,
    "createdBy"          TEXT,
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SimulationResult_scenario_idx" ON "SimulationResult" ("scenarioId", "createdAt" DESC);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['CostScenario', 'SimulationResult'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
