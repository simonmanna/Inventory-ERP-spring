-- EX-4 (ADR-S052): petty cash funds (imprest float on a cash-equivalent GL account, custodian, per-payment cap) and
-- cash counts (expected = GL balance; a difference posts to cash short & over). Replenishments and spending are GL
-- entries on the fund account, so the statement is read from the ledger.

CREATE TABLE "PettyCashFund" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    "accountId"      TEXT           NOT NULL,
    "custodianId"    TEXT,
    "branchId"       TEXT,
    "floatAmount"    NUMERIC(20, 6) NOT NULL CHECK ("floatAmount" > 0),
    "maxPayment"     NUMERIC(20, 6) CHECK ("maxPayment" IS NULL OR "maxPayment" > 0),
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PettyCashFund_account" UNIQUE ("organizationId", "accountId")
);

CREATE TABLE "PettyCashCount" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "fundId"           TEXT           NOT NULL REFERENCES "PettyCashFund" (id) ON DELETE CASCADE,
    "expectedBalance"  NUMERIC(20, 6) NOT NULL,
    "countedAmount"    NUMERIC(20, 6) NOT NULL CHECK ("countedAmount" >= 0),
    difference         NUMERIC(20, 6) NOT NULL,
    denominations      JSONB,
    "journalEntryId"   TEXT,
    notes              TEXT,
    "countedById"      TEXT,
    "countedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "PettyCashCount_fund" ON "PettyCashCount" ("organizationId", "fundId", "countedAt" DESC);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['PettyCashFund', 'PettyCashCount'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
