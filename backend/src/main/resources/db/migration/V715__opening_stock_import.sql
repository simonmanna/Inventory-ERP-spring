-- IPR Phase 4, R5 (ADR-S090): opening-stock import runs and their batches.
--
-- A run groups the batches of one imported file. Each batch holds at most 2,000 rows of one location and posts in one
-- transaction: its ledger rows carry referenceType 'opening_stock_import' and referenceId = the batch id, and its one
-- journal entry carries sourceType 'opening_stock_import' and sourceId = the batch id.
--
-- Idempotency: the unique ("organizationId", "importHash") on the run refuses the same file twice; the one on the batch
-- refuses the same location rows twice (in any run). New tables only: no existing row is changed and the ledger trigger
-- inventory_ledger_append_only stays enabled (R1).

CREATE TABLE "OpeningStockImportRun" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "runCode"          TEXT NOT NULL,
    status             TEXT NOT NULL,
    "importHash"       TEXT NOT NULL,
    "previewToken"     TEXT NOT NULL,
    "fileName"         TEXT,
    notes              TEXT,
    "rowCount"         INTEGER NOT NULL,
    "batchCount"       INTEGER NOT NULL,
    "totalValue"       NUMERIC(20, 6) NOT NULL,
    "approvalRequestId" TEXT,
    "approvedById"     TEXT,
    "approvedAt"       TIMESTAMP(3) NOT NULL,
    "completedAt"      TIMESTAMP(3),
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "OpeningStockImportRun_status" CHECK (status IN ('posting', 'partial', 'failed', 'completed')),
    CONSTRAINT "OpeningStockImportRun_counts" CHECK ("rowCount" > 0 AND "batchCount" > 0)
);
CREATE UNIQUE INDEX "OpeningStockImportRun_org_hash_key" ON "OpeningStockImportRun" ("organizationId", "importHash");
CREATE UNIQUE INDEX "OpeningStockImportRun_org_code_key" ON "OpeningStockImportRun" ("organizationId", "runCode");
CREATE INDEX "OpeningStockImportRun_org_created_idx" ON "OpeningStockImportRun" ("organizationId", "createdAt");

CREATE TABLE "OpeningStockImportBatch" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"            TEXT NOT NULL REFERENCES "OpeningStockImportRun" (id),
    "batchNo"          INTEGER NOT NULL,
    "locationId"       TEXT NOT NULL,
    status             TEXT NOT NULL,
    "importHash"       TEXT NOT NULL,
    "batchToken"       TEXT NOT NULL,
    "rowCount"         INTEGER NOT NULL,
    "totalValue"       NUMERIC(20, 6) NOT NULL,
    rows               JSONB NOT NULL,
    "ledgerValue"      NUMERIC(20, 6),
    "journalEntryId"   TEXT,
    "valuationBefore"  NUMERIC(20, 6),
    "valuationAfter"   NUMERIC(20, 6),
    attempts           INTEGER NOT NULL DEFAULT 0,
    error              TEXT,
    "postedById"       TEXT,
    "postedAt"         TIMESTAMP(3),
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "OpeningStockImportBatch_status" CHECK (status IN ('pending', 'posted', 'failed')),
    CONSTRAINT "OpeningStockImportBatch_rows" CHECK ("rowCount" > 0 AND "rowCount" <= 2000)
);
CREATE UNIQUE INDEX "OpeningStockImportBatch_org_hash_key" ON "OpeningStockImportBatch" ("organizationId", "importHash");
CREATE UNIQUE INDEX "OpeningStockImportBatch_run_no_key" ON "OpeningStockImportBatch" ("runId", "batchNo");
CREATE INDEX "OpeningStockImportBatch_org_location_idx" ON "OpeningStockImportBatch" ("organizationId", "locationId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['OpeningStockImportRun', 'OpeningStockImportBatch'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
