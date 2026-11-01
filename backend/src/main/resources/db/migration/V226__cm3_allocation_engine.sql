-- V226: Cost Management CM-3 (ADR-S033) — allocation cycles (financial / statistical), statistical keys,
-- auditable runs and lines traceable to their source account and sender.

CREATE TABLE "StatisticalKey" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    unit             TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "StatisticalKey_code" UNIQUE ("organizationId", code)
);

CREATE TABLE "StatisticalKeyValue" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "keyId"          TEXT NOT NULL REFERENCES "StatisticalKey" (id) ON DELETE CASCADE,
    "receiverId"     TEXT NOT NULL,
    "periodFrom"     DATE NOT NULL,
    "periodTo"       DATE NOT NULL,
    value            NUMERIC(20, 6) NOT NULL CHECK (value >= 0),
    CONSTRAINT "StatisticalKeyValue_period" CHECK ("periodTo" >= "periodFrom")
);
CREATE INDEX "StatisticalKeyValue_key_idx" ON "StatisticalKeyValue" ("keyId", "receiverId", "periodFrom");

CREATE TABLE "AllocationCycle" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    "postingMode"    TEXT NOT NULL DEFAULT 'financial',
    method           TEXT NOT NULL DEFAULT 'sequential',
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AllocationCycle_code" UNIQUE ("organizationId", code),
    CONSTRAINT "AllocationCycle_mode" CHECK ("postingMode" IN ('financial', 'statistical')),
    CONSTRAINT "AllocationCycle_method" CHECK (method IN ('sequential', 'reciprocal'))
);

CREATE TABLE "AllocationSegment" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "cycleId"            TEXT NOT NULL REFERENCES "AllocationCycle" (id) ON DELETE CASCADE,
    seq                  INTEGER NOT NULL,
    name                 TEXT NOT NULL,
    "senderCostCenterId" TEXT NOT NULL REFERENCES "CostCenter" (id),
    "senderAccountIds"   TEXT[] NOT NULL DEFAULT '{}',
    "receiverType"       TEXT NOT NULL DEFAULT 'cost_center',
    "receiverIds"        TEXT[] NOT NULL,
    basis                TEXT NOT NULL,
    "statisticalKeyId"   TEXT REFERENCES "StatisticalKey" (id),
    "manualValues"       JSONB,
    "sharePercent"       NUMERIC(9, 4) NOT NULL DEFAULT 100 CHECK ("sharePercent" > 0 AND "sharePercent" <= 100),
    rounding             TEXT NOT NULL DEFAULT 'largest_remainder',
    CONSTRAINT "AllocationSegment_seq" UNIQUE ("cycleId", seq),
    CONSTRAINT "AllocationSegment_receiver" CHECK ("receiverType" IN ('cost_center'))
);

CREATE TABLE "AllocationRun" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    number            TEXT NOT NULL,
    "cycleId"         TEXT NOT NULL REFERENCES "AllocationCycle" (id),
    "periodFrom"      DATE NOT NULL,
    "periodTo"        DATE NOT NULL,
    "postingMode"     TEXT NOT NULL,
    method            TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'posted',
    total             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "journalEntryId"  TEXT,
    "reversalEntryId" TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"      TIMESTAMP(3),
    CONSTRAINT "AllocationRun_number" UNIQUE ("organizationId", number),
    CONSTRAINT "AllocationRun_status" CHECK (status IN ('posted', 'reversed'))
);
-- One posted run per cycle and period (idempotent; reverse it to run again).
CREATE UNIQUE INDEX "AllocationRun_once" ON "AllocationRun" ("cycleId", "periodFrom", "periodTo")
    WHERE status = 'posted';

CREATE TABLE "AllocationLine" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"              TEXT NOT NULL REFERENCES "AllocationRun" (id) ON DELETE CASCADE,
    "segmentSeq"         INTEGER NOT NULL,
    "senderCostCenterId" TEXT NOT NULL,
    "sourceAccountId"    TEXT NOT NULL,
    "receiverType"       TEXT NOT NULL,
    "receiverId"         TEXT NOT NULL,
    basis                TEXT NOT NULL,
    "basisValue"         NUMERIC(20, 6) NOT NULL,
    share                NUMERIC(14, 10) NOT NULL,
    amount               NUMERIC(20, 2) NOT NULL,
    "journalEntryId"     TEXT
);
CREATE INDEX "AllocationLine_run_idx" ON "AllocationLine" ("runId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['StatisticalKey', 'StatisticalKeyValue', 'AllocationCycle', 'AllocationSegment',
            'AllocationRun', 'AllocationLine'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
