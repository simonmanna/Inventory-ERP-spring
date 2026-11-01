-- V222: PR-13 (roadmap 3.1 remainder, ADR-S030) — material ledger actual costing and activity-based costing.

CREATE TABLE "MaterialLedgerRun" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    number           TEXT NOT NULL,
    "periodFrom"     DATE NOT NULL,
    "periodTo"       DATE NOT NULL,
    status           TEXT NOT NULL DEFAULT 'draft',
    "productCount"   INTEGER NOT NULL DEFAULT 0,
    "poolTotal"      NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "toInventory"    NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "toConsumption"  NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "deltaTotal"     NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "journalEntryId" TEXT,
    "reversalEntryId" TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "postedAt"       TIMESTAMP(3),
    "postedBy"       TEXT,
    "reversedAt"     TIMESTAMP(3),
    CONSTRAINT "MaterialLedgerRun_number" UNIQUE ("organizationId", number),
    CONSTRAINT "MaterialLedgerRun_status" CHECK (status IN ('draft', 'posted', 'reversed')),
    CONSTRAINT "MaterialLedgerRun_period" CHECK ("periodTo" >= "periodFrom")
);
CREATE INDEX "MaterialLedgerRun_org" ON "MaterialLedgerRun" ("organizationId", status, "periodTo");

CREATE TABLE "MaterialLedgerLine" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"           TEXT NOT NULL REFERENCES "MaterialLedgerRun" (id) ON DELETE CASCADE,
    "productId"       TEXT NOT NULL REFERENCES "Product" (id),
    "standardCost"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "openingQty"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "carriedVariance" NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "receiptQty"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "periodVariance"  NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "consumedQty"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "endingQty"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    pool              NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "toInventory"     NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "toConsumption"   NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "periodicUnitCost" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    delta             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    CONSTRAINT "MaterialLedgerLine_product" UNIQUE ("runId", "productId")
);

CREATE TABLE "AbcActivity" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    description      TEXT,
    "costObject"     TEXT NOT NULL,
    driver           TEXT NOT NULL,
    "accountIds"     TEXT[] NOT NULL DEFAULT '{}',
    "costCenterId"   TEXT,
    "poolPercent"    NUMERIC(9, 4) NOT NULL DEFAULT 100,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AbcActivity_code" UNIQUE ("organizationId", code),
    CONSTRAINT "AbcActivity_object" CHECK ("costObject" IN ('customer', 'product')),
    CONSTRAINT "AbcActivity_pct" CHECK ("poolPercent" > 0 AND "poolPercent" <= 100)
);

CREATE TABLE "AbcRun" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "costObject"     TEXT NOT NULL,
    "periodFrom"     DATE NOT NULL,
    "periodTo"       DATE NOT NULL,
    "poolTotal"      NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "assignedTotal"  NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "unassignedTotal" NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "objectCount"    INTEGER NOT NULL DEFAULT 0,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AbcRun_object" CHECK ("costObject" IN ('customer', 'product'))
);
CREATE INDEX "AbcRun_org" ON "AbcRun" ("organizationId", "createdAt");

CREATE TABLE "AbcRunActivity" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"          TEXT NOT NULL REFERENCES "AbcRun" (id) ON DELETE CASCADE,
    "activityId"     TEXT NOT NULL,
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    driver           TEXT NOT NULL,
    pool             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "driverTotal"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    rate             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    assigned         NUMERIC(20, 2) NOT NULL DEFAULT 0
);

CREATE TABLE "AbcRunResult" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"          TEXT NOT NULL REFERENCES "AbcRun" (id) ON DELETE CASCADE,
    "objectId"       TEXT NOT NULL,
    "objectName"     TEXT,
    revenue          NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "costOfGoods"    NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "activityCost"   NUMERIC(20, 2) NOT NULL DEFAULT 0,
    margin           NUMERIC(20, 2) NOT NULL DEFAULT 0,
    breakdown        JSONB NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX "AbcRunResult_run" ON "AbcRunResult" ("runId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['MaterialLedgerRun', 'MaterialLedgerLine', 'AbcActivity', 'AbcRun', 'AbcRunActivity',
            'AbcRunResult'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
