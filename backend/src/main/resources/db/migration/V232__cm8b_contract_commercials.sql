-- V232: Cost Management CM-8b (ADR-S039) — contract commercials: IFRS 15.35 criteria assessments, variations
-- (modifications), progress claims, customer advances, retentions (customer contract asset / receivable and
-- subcontractor payable), subcontracts and valuations, revenue recognition runs (cost-to-cost input method) and IAS 37
-- onerous-contract assessments.

CREATE TABLE "ContractCriteriaAssessment" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id),
    "criterionA"     BOOLEAN NOT NULL DEFAULT false,
    "criterionB"     BOOLEAN NOT NULL DEFAULT false,
    "criterionC"     BOOLEAN NOT NULL DEFAULT false,
    evidence         TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'draft',
    "assessedBy"     TEXT,
    "approvedBy"     TEXT,
    "approvedAt"     TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ContractCriteriaAssessment_status" CHECK (status IN ('draft', 'approved', 'rejected'))
);
CREATE INDEX "ContractCriteriaAssessment_project_idx" ON "ContractCriteriaAssessment" ("projectId");

CREATE TABLE "ContractVariation" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id),
    number           TEXT NOT NULL,
    description      TEXT NOT NULL,
    "priceDelta"     NUMERIC(20, 2) NOT NULL,
    "costDelta"      NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "wbsId"          TEXT REFERENCES "ProjectWbsElement" (id),
    "costElement"    TEXT,
    modification     TEXT NOT NULL DEFAULT 'cumulative_catch_up',
    status           TEXT NOT NULL DEFAULT 'pending',
    "budgetId"       TEXT,
    "createdBy"      TEXT,
    "decidedBy"      TEXT,
    "decidedAt"      TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ContractVariation_number" UNIQUE ("organizationId", number),
    CONSTRAINT "ContractVariation_modification" CHECK (modification IN ('cumulative_catch_up', 'separate_contract',
        'prospective')),
    CONSTRAINT "ContractVariation_status" CHECK (status IN ('pending', 'approved', 'rejected'))
);

CREATE TABLE "CustomerAdvance" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id),
    amount           NUMERIC(20, 2) NOT NULL CHECK (amount > 0),
    "recoveryPct"    NUMERIC(7, 4) NOT NULL CHECK ("recoveryPct" > 0 AND "recoveryPct" <= 100),
    recovered        NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "invoiceId"      TEXT,
    status           TEXT NOT NULL DEFAULT 'open',
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CustomerAdvance_status" CHECK (status IN ('open', 'recovered')),
    CONSTRAINT "CustomerAdvance_recovered" CHECK (recovered >= 0 AND recovered <= amount)
);

CREATE TABLE "ProgressClaim" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"           TEXT NOT NULL REFERENCES "Project" (id),
    number                TEXT NOT NULL,
    "claimDate"           DATE NOT NULL,
    "cumulativeCertified" NUMERIC(20, 2) NOT NULL,
    "previousCertified"   NUMERIC(20, 2) NOT NULL,
    "thisClaim"           NUMERIC(20, 2) NOT NULL,
    "retentionPct"        NUMERIC(7, 4) NOT NULL DEFAULT 0,
    retention             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "retentionCondition"  TEXT NOT NULL DEFAULT 'completion_certificate',
    "retentionReleaseDate" DATE,
    "advanceRecovery"     NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "netAmount"           NUMERIC(20, 2) NOT NULL,
    "taxId"               TEXT,
    status                TEXT NOT NULL DEFAULT 'draft',
    "invoiceId"           TEXT,
    "retentionInvoiceId"  TEXT,
    "journalEntryId"      TEXT,
    "createdBy"           TEXT,
    "certifiedBy"         TEXT,
    "certifiedAt"         TIMESTAMP(3),
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProgressClaim_number" UNIQUE ("organizationId", number),
    CONSTRAINT "ProgressClaim_status" CHECK (status IN ('draft', 'certified', 'cancelled')),
    CONSTRAINT "ProgressClaim_condition" CHECK ("retentionCondition" IN ('completion_certificate',
        'defects_period', 'date_only'))
);
CREATE UNIQUE INDEX "ProgressClaim_open_draft" ON "ProgressClaim" ("projectId") WHERE status = 'draft';

CREATE TABLE "Subcontract" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"      TEXT NOT NULL REFERENCES "Project" (id),
    number           TEXT NOT NULL,
    "supplierId"     TEXT NOT NULL REFERENCES "Partner" (id),
    "wbsId"          TEXT NOT NULL REFERENCES "ProjectWbsElement" (id),
    scope            TEXT NOT NULL,
    value            NUMERIC(20, 2) NOT NULL CHECK (value > 0),
    "retentionPct"   NUMERIC(7, 4) NOT NULL DEFAULT 0 CHECK ("retentionPct" >= 0 AND "retentionPct" <= 100),
    "retentionCondition" TEXT NOT NULL DEFAULT 'completion_certificate',
    status           TEXT NOT NULL DEFAULT 'active',
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "Subcontract_number" UNIQUE ("organizationId", number),
    CONSTRAINT "Subcontract_status" CHECK (status IN ('active', 'completed', 'cancelled'))
);

CREATE TABLE "SubcontractValuation" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id),
    "subcontractId"     TEXT NOT NULL REFERENCES "Subcontract" (id),
    number              TEXT NOT NULL,
    "valuationDate"     DATE NOT NULL,
    "cumulativeValue"   NUMERIC(20, 2) NOT NULL,
    "previousValue"     NUMERIC(20, 2) NOT NULL,
    "thisValuation"     NUMERIC(20, 2) NOT NULL,
    retention           NUMERIC(20, 2) NOT NULL,
    "netAmount"         NUMERIC(20, 2) NOT NULL,
    "billId"            TEXT,
    "journalEntryId"    TEXT,
    "costLineId"        TEXT,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SubcontractValuation_number" UNIQUE ("organizationId", number)
);

-- Both sides of retention: customer (contract asset while conditional, receivable once unconditional) and
-- subcontractor (financial liability: retention payable). Back-charges reduce the payable.
CREATE TABLE "RetentionLedger" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"       TEXT NOT NULL REFERENCES "Project" (id),
    side              TEXT NOT NULL,
    "partnerId"       TEXT,
    "claimId"         TEXT REFERENCES "ProgressClaim" (id),
    "valuationId"     TEXT REFERENCES "SubcontractValuation" (id),
    amount            NUMERIC(20, 2) NOT NULL,
    "backCharged"     NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "releaseCondition" TEXT NOT NULL,
    "releaseDate"     DATE,
    classification    TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'held',
    "journalEntryId"  TEXT,
    "releaseDocumentId" TEXT,
    "releaseEntryId"  TEXT,
    "releasedAt"      TIMESTAMP(3),
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "RetentionLedger_side" CHECK (side IN ('customer', 'subcontractor')),
    CONSTRAINT "RetentionLedger_class" CHECK (classification IN ('contract_asset', 'receivable', 'payable')),
    CONSTRAINT "RetentionLedger_status" CHECK (status IN ('held', 'released')),
    CONSTRAINT "RetentionLedger_condition" CHECK ("releaseCondition" IN ('completion_certificate',
        'defects_period', 'date_only'))
);
CREATE INDEX "RetentionLedger_project_idx" ON "RetentionLedger" ("projectId", side);

CREATE TABLE "RevenueRecognitionRun" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"          TEXT NOT NULL REFERENCES "Project" (id),
    "periodEnd"          DATE NOT NULL,
    method               TEXT NOT NULL,
    "transactionPrice"   NUMERIC(20, 2) NOT NULL,
    "costToDate"         NUMERIC(20, 2) NOT NULL,
    "estimatedTotalCost" NUMERIC(20, 2) NOT NULL,
    progress             NUMERIC(12, 8) NOT NULL,
    "cumulativeRevenue"  NUMERIC(20, 2) NOT NULL,
    "revenueThisPeriod"  NUMERIC(20, 2) NOT NULL,
    "cumulativeCost"     NUMERIC(20, 2) NOT NULL,
    "costThisPeriod"     NUMERIC(20, 2) NOT NULL,
    status               TEXT NOT NULL DEFAULT 'posted',
    "journalEntryId"     TEXT,
    "reversalEntryId"    TEXT,
    "createdBy"          TEXT,
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "RevenueRecognitionRun_method" CHECK (method IN ('point_in_time', 'over_time_cost_to_cost')),
    CONSTRAINT "RevenueRecognitionRun_status" CHECK (status IN ('posted', 'reversed'))
);
CREATE UNIQUE INDEX "RevenueRecognitionRun_period" ON "RevenueRecognitionRun" ("projectId", "periodEnd")
    WHERE status = 'posted';

CREATE TABLE "OnerousContractAssessment" (
    id                       TEXT PRIMARY KEY,
    "organizationId"         TEXT NOT NULL REFERENCES "Organization" (id),
    "projectId"              TEXT NOT NULL REFERENCES "Project" (id),
    "asOf"                   DATE NOT NULL,
    indicators               TEXT NOT NULL,
    "remainingPrice"         NUMERIC(20, 2) NOT NULL,
    "probableClaims"         NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "expectedBenefits"       NUMERIC(20, 2) NOT NULL,
    "incrementalCost"        NUMERIC(20, 2) NOT NULL,
    "directlyRelatedCost"    NUMERIC(20, 2) NOT NULL,
    "excludedCost"           NUMERIC(20, 2) NOT NULL,
    "costOfFulfilling"       NUMERIC(20, 2) NOT NULL,
    "costOfExiting"          NUMERIC(20, 2),
    "exitEvidence"           TEXT,
    "unavoidableCost"        NUMERIC(20, 2) NOT NULL,
    "impairmentRecognised"   NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "impairmentReference"    TEXT,
    provision                NUMERIC(20, 2) NOT NULL,
    "previousProvision"      NUMERIC(20, 2) NOT NULL DEFAULT 0,
    movement                 NUMERIC(20, 2) NOT NULL DEFAULT 0,
    status                   TEXT NOT NULL DEFAULT 'draft',
    "journalEntryId"         TEXT,
    "reversalEntryId"        TEXT,
    "createdBy"              TEXT,
    "approvedBy"             TEXT,
    "approvedAt"             TIMESTAMP(3),
    "createdAt"              TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "OnerousContractAssessment_status" CHECK (status IN ('draft', 'approved', 'posted', 'reversed',
        'rejected'))
);
CREATE INDEX "OnerousContractAssessment_project_idx" ON "OnerousContractAssessment" ("projectId", "createdAt");

ALTER TABLE "ProjectCostLine" DROP CONSTRAINT "ProjectCostLine_source";
ALTER TABLE "ProjectCostLine" ADD CONSTRAINT "ProjectCostLine_source" CHECK ("sourceType" IN ('project_issue',
    'project_timesheet', 'project_equipment', 'project_reclass', 'project_overhead', 'project_subcontract',
    'project_back_charge'));

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ContractCriteriaAssessment', 'ContractVariation', 'CustomerAdvance', 'ProgressClaim',
        'Subcontract', 'SubcontractValuation', 'RetentionLedger', 'RevenueRecognitionRun',
        'OnerousContractAssessment'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
