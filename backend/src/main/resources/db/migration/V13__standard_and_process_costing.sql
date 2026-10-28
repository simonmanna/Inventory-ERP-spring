-- V13: standard costing with GL variances and process costing (ADR-S008, roadmap 3.1 brought forward).
--
-- Additive extension beyond the NestJS reference. A product is "standard-governed" only once it has an active
-- ProductStandardCost; every other product (including legacy STANDARD ones) behaves exactly as before.

-- ---------------------------------------------------------------- standard costs (inventory)

CREATE TABLE "ProductStandardCost" (
    id                          TEXT           NOT NULL PRIMARY KEY,
    "organizationId"            TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"                 TEXT           NOT NULL,
    version                     INTEGER        NOT NULL DEFAULT 1,
    status                      TEXT           NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'approved', 'active', 'superseded', 'cancelled')),
    "effectiveFrom"             DATE           NOT NULL,
    "materialCost"              NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "labourCost"                NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "overheadCost"              NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "subcontractCost"           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "totalCost"                 NUMERIC(20, 6) NOT NULL CHECK ("totalCost" >= 0),
    source                      TEXT           NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'bom_rollup')),
    "bomStandardCostId"         TEXT,
    "convertMethod"             BOOLEAN        NOT NULL DEFAULT false,
    "previousCost"              NUMERIC(20, 6),
    "revaluationAmount"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "revaluationQuantity"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "revaluationJournalEntryId" TEXT,
    notes                       TEXT,
    "approvedBy"                TEXT,
    "approvedAt"                TIMESTAMP(3),
    "activatedAt"               TIMESTAMP(3),
    "createdBy"                 TEXT,
    "createdAt"                 TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"                 TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProductStandardCost_org_product_idx" ON "ProductStandardCost" ("organizationId", "productId");
CREATE UNIQUE INDEX "ProductStandardCost_active_key" ON "ProductStandardCost" ("organizationId", "productId")
    WHERE status = 'active';

-- Purchase price variance per receipt of a standard-governed product (audit of the PPV postings).
CREATE TABLE "StandardCostVariance" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL,
    kind             TEXT           NOT NULL CHECK (kind IN ('purchase_price', 'material_price', 'material_usage',
                                                            'labour', 'overhead', 'subcontract', 'yield', 'other')),
    "sourceType"     TEXT,
    "sourceId"       TEXT,
    quantity         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "standardCost"   NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "actualCost"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    amount           NUMERIC(20, 6) NOT NULL,
    "journalEntryId" TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "StandardCostVariance_org_created_idx" ON "StandardCostVariance" ("organizationId", "createdAt");
CREATE INDEX "StandardCostVariance_org_product_idx" ON "StandardCostVariance" ("organizationId", "productId");

ALTER TABLE "ProductionOrder" ADD COLUMN "varianceJournalEntryId" TEXT;

-- ---------------------------------------------------------------- process costing (manufacturing)

CREATE TABLE "CostProcess" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code               TEXT         NOT NULL,
    name               TEXT         NOT NULL,
    sequence           INTEGER      NOT NULL DEFAULT 1,
    method             TEXT         NOT NULL DEFAULT 'weighted_average' CHECK (method IN ('weighted_average', 'fifo')),
    "nextProcessId"    TEXT REFERENCES "CostProcess" (id) ON DELETE SET NULL,
    "outputProductId"  TEXT,
    "outputLocationId" TEXT,
    "costCenterId"     TEXT,
    "isActive"         BOOLEAN      NOT NULL DEFAULT true,
    notes              TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CostProcess_org_code_key" ON "CostProcess" ("organizationId", code);

CREATE TABLE "ProcessCostPeriod" (
    id                         TEXT           NOT NULL PRIMARY KEY,
    "organizationId"           TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "processId"                TEXT           NOT NULL REFERENCES "CostProcess" (id) ON DELETE CASCADE,
    "periodFrom"               DATE           NOT NULL,
    "periodTo"                 DATE           NOT NULL,
    status                     TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    "openingUnits"             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "openingMaterialPct"       NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    "openingConversionPct"     NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    "openingMaterialCost"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "openingConversionCost"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "openingTransferredInCost" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitsStarted"             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitsCompleted"           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "closingUnits"             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "closingMaterialPct"       NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    "closingConversionPct"     NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    "normalLossUnits"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "abnormalLossUnits"        NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "abnormalMaterialPct"      NUMERIC(9, 4)  NOT NULL DEFAULT 100,
    "abnormalConversionPct"    NUMERIC(9, 4)  NOT NULL DEFAULT 100,
    result                     JSONB,
    "completedCost"            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "closingWipCost"           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "abnormalLossCost"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitCost"                 NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "journalEntryIds"          TEXT[],
    "closedAt"                 TIMESTAMP(3),
    "closedBy"                 TEXT,
    "createdAt"                TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"                TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProcessCostPeriod_dates_chk" CHECK ("periodTo" >= "periodFrom")
);
CREATE UNIQUE INDEX "ProcessCostPeriod_process_from_key" ON "ProcessCostPeriod" ("processId", "periodFrom");
CREATE UNIQUE INDEX "ProcessCostPeriod_one_open_key" ON "ProcessCostPeriod" ("processId") WHERE status = 'open';

CREATE TABLE "ProcessCostEntry" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "periodId"       TEXT           NOT NULL REFERENCES "ProcessCostPeriod" (id) ON DELETE CASCADE,
    element          TEXT           NOT NULL CHECK (element IN ('material', 'labour', 'overhead', 'transferred_in')),
    amount           NUMERIC(20, 6) NOT NULL,
    quantity         NUMERIC(20, 6),
    "productId"      TEXT,
    "locationId"     TEXT,
    "sourceType"     TEXT,
    "sourceId"       TEXT,
    description      TEXT,
    "journalEntryId" TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProcessCostEntry_period_idx" ON "ProcessCostEntry" ("organizationId", "periodId");

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "ProductStandardCost" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductStandardCost" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "StandardCostVariance" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "StandardCostVariance" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CostProcess" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CostProcess" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProcessCostPeriod" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessCostPeriod" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProcessCostEntry" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessCostEntry" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['standard_cost:read', 'standard_cost:manage', 'standard_cost:approve', 'process_costing:read',
                  'process_costing:manage', 'process_costing:close']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['standard_cost:read', 'standard_cost:manage',
        'standard_cost:approve', 'process_costing:read', 'process_costing:manage', 'process_costing:close'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
