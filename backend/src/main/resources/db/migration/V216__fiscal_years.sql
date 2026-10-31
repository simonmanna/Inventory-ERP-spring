-- PR-2 (roadmap 1.7, ADR-S023): fiscal years and the year-end close. Extension tables; no existing column changes
-- meaning. A period belongs to at most one year ("fiscalYearId" stays null for periods created outside a year).

CREATE TABLE "FiscalYear" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id),
    name             TEXT         NOT NULL,
    "startDate"      DATE         NOT NULL,
    "endDate"        DATE         NOT NULL,
    periodicity      TEXT         NOT NULL DEFAULT 'monthly' CHECK (periodicity IN ('monthly', 'quarterly', 'yearly')),
    status           TEXT         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    "lockedPeriods"  BOOLEAN      NOT NULL DEFAULT false,
    "closedAt"       TIMESTAMP(3),
    "closedBy"       TEXT,
    "netIncome"      NUMERIC(20, 6),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdBy"      TEXT,
    CONSTRAINT "FiscalYear_dates" CHECK ("endDate" > "startDate"),
    CONSTRAINT "FiscalYear_org_start" UNIQUE ("organizationId", "startDate"),
    CONSTRAINT "FiscalYear_org_name" UNIQUE ("organizationId", name)
);
CREATE INDEX "FiscalYear_org_dates" ON "FiscalYear" ("organizationId", "startDate", "endDate");

ALTER TABLE "FiscalPeriod" ADD COLUMN "fiscalYearId" TEXT REFERENCES "FiscalYear" (id);
CREATE INDEX "FiscalPeriod_fiscalYearId" ON "FiscalPeriod" ("fiscalYearId");

-- Manual year-end steps signed by a person (stock count, asset register, accountant review, tax computation).
CREATE TABLE "FiscalYearSignOff" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id),
    "fiscalYearId"   TEXT         NOT NULL REFERENCES "FiscalYear" (id),
    key              TEXT         NOT NULL,
    note             TEXT,
    "signedBy"       TEXT,
    "signedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FiscalYearSignOff_unique" UNIQUE ("organizationId", "fiscalYearId", key)
);

-- Balance-sheet balances at the year end, taken when the year closes: the next year's opening balances and the audit
-- evidence that the carried-forward figures did not change afterwards.
CREATE TABLE "FiscalYearBalance" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id),
    "fiscalYearId"   TEXT           NOT NULL REFERENCES "FiscalYear" (id),
    "accountId"      TEXT           NOT NULL REFERENCES "Account" (id),
    debit            NUMERIC(20, 6) NOT NULL,
    credit           NUMERIC(20, 6) NOT NULL,
    balance          NUMERIC(20, 6) NOT NULL,
    CONSTRAINT "FiscalYearBalance_unique" UNIQUE ("fiscalYearId", "accountId")
);
CREATE INDEX "FiscalYearBalance_org" ON "FiscalYearBalance" ("organizationId", "fiscalYearId");

-- Row-level security like every tenant table (V214).
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['FiscalYear', 'FiscalYearSignOff', 'FiscalYearBalance'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
