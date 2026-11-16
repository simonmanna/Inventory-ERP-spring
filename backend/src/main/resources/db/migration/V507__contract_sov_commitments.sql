-- V485 (ADR-S072): contract schedule of values with line-level progress billing (G702 / G703), subcontract
-- variations, and WBS on purchase-request lines so commitments can be reported per WBS element.

-- Schedule of values: one row per billable item of the contract. scheduledValue is the original value; approved
-- variations add to variationValue (or add a new line, variationId set). Current value = scheduled + variation.
CREATE TABLE "ContractSovLine" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id),
    code             TEXT NOT NULL,
    description      TEXT NOT NULL,
    "wbsId"          TEXT REFERENCES "ProjectWbsElement" (id),
    "scheduledValue" NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK ("scheduledValue" >= 0),
    "variationValue" NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "retentionPct"   NUMERIC(7, 4) CHECK ("retentionPct" IS NULL OR ("retentionPct" >= 0 AND "retentionPct" < 100)),
    "variationId"    TEXT REFERENCES "ContractVariation" (id),
    "sortOrder"      INTEGER NOT NULL DEFAULT 0,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ContractSovLine_code" UNIQUE ("projectId", code),
    CONSTRAINT "ContractSovLine_value" CHECK ("scheduledValue" + "variationValue" >= 0)
);
CREATE INDEX "ContractSovLine_project_idx" ON "ContractSovLine" ("projectId", "sortOrder");

-- A variation may change one SOV line's value (sovLineId) or add a new line (sovCode + sovDescription) on approval.
ALTER TABLE "ContractVariation"
    ADD COLUMN "sovLineId"      TEXT REFERENCES "ContractSovLine" (id),
    ADD COLUMN "sovCode"        TEXT,
    ADD COLUMN "sovDescription" TEXT;

-- G703 continuation sheet: one row per SOV line of a line-level progress claim. Columns follow the AIA layout:
-- C scheduled value, D previous (work completed in earlier certified claims), E this period, F materials presently
-- stored, G = D + E + F, % = G / C, H balance to finish = C - G, retention on this claim's movement of G.
CREATE TABLE "ProgressClaimLine" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id),
    "claimId"           TEXT NOT NULL REFERENCES "ProgressClaim" (id),
    "sovLineId"         TEXT NOT NULL REFERENCES "ContractSovLine" (id),
    "scheduledValue"    NUMERIC(20, 2) NOT NULL,
    "previousCompleted" NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "previousStored"    NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "thisPeriod"        NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "materialsStored"   NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK ("materialsStored" >= 0),
    "totalCompleted"    NUMERIC(20, 2) NOT NULL,
    "percentComplete"   NUMERIC(9, 4) NOT NULL,
    "balanceToFinish"   NUMERIC(20, 2) NOT NULL,
    "retentionPct"      NUMERIC(7, 4) NOT NULL DEFAULT 0,
    retention           NUMERIC(20, 2) NOT NULL DEFAULT 0,
    CONSTRAINT "ProgressClaimLine_line" UNIQUE ("claimId", "sovLineId")
);
CREATE INDEX "ProgressClaimLine_sov_idx" ON "ProgressClaimLine" ("sovLineId");

-- Subcontract variations (maker / checker): approved ones raise the subcontract's adjusted value, which caps the
-- valuations and drives the unvalued balance in committed cost.
CREATE TABLE "SubcontractVariation" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "subcontractId"  TEXT NOT NULL REFERENCES "Subcontract" (id),
    number           TEXT NOT NULL,
    description      TEXT NOT NULL,
    "valueDelta"     NUMERIC(20, 2) NOT NULL,
    status           TEXT NOT NULL DEFAULT 'pending',
    "createdBy"      TEXT,
    "decidedBy"      TEXT,
    "decidedAt"      TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SubcontractVariation_number" UNIQUE ("organizationId", number),
    CONSTRAINT "SubcontractVariation_status" CHECK (status IN ('pending', 'approved', 'rejected'))
);
CREATE INDEX "SubcontractVariation_subcontract_idx" ON "SubcontractVariation" ("subcontractId");

-- Commitments by WBS: purchase-request lines (and the orders sourced from them) carry the WBS element.
ALTER TABLE "PurchaseRequestLine" ADD COLUMN "wbsId" TEXT REFERENCES "ProjectWbsElement" (id);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ContractSovLine', 'ProgressClaimLine', 'SubcontractVariation'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
