-- V12: landed costing, configurable inventory costing policy and cost-centre accounting (ADR-S007, roadmap 1.8).
--
-- Additive extension beyond the NestJS reference: new tables plus nullable / defaulted columns. Every Nest route
-- keeps its behaviour; the legacy single-receipt landed cost (voucherType = 'receipt') is unchanged.

-- ---------------------------------------------------------------- cost centres (accounting)

ALTER TABLE "CostCenter"
    ADD COLUMN "parentId"      TEXT REFERENCES "CostCenter" (id) ON DELETE SET NULL,
    ADD COLUMN "branchId"      TEXT,
    ADD COLUMN category        TEXT NOT NULL DEFAULT 'department'
        CHECK (category IN ('department', 'branch', 'project', 'warehouse', 'shipment', 'vehicle', 'other')),
    ADD COLUMN description     TEXT,
    ADD COLUMN "managerUserId" TEXT,
    ADD COLUMN "validFrom"     DATE,
    ADD COLUMN "validTo"       DATE;
CREATE INDEX "CostCenter_org_parent_idx" ON "CostCenter" ("organizationId", "parentId");

-- Default cost centre per branch / partner (supplier, customer) / user / stock location / employee.
CREATE TABLE "CostCenterDefault" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "scopeType"      TEXT         NOT NULL CHECK ("scopeType" IN ('branch', 'partner', 'user', 'location', 'employee')),
    "scopeId"        TEXT         NOT NULL,
    "costCenterId"   TEXT         NOT NULL REFERENCES "CostCenter" (id) ON DELETE CASCADE,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CostCenterDefault_scope_key" ON "CostCenterDefault" ("organizationId", "scopeType", "scopeId");

-- Per-account dimension policy: cost centre required / forbidden on posting (absent = optional).
CREATE TABLE "AccountDimensionRule" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "accountId"      TEXT         NOT NULL REFERENCES "Account" (id) ON DELETE CASCADE,
    dimension        TEXT         NOT NULL DEFAULT 'costCenter' CHECK (dimension IN ('costCenter')),
    rule             TEXT         NOT NULL CHECK (rule IN ('required', 'forbidden')),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "AccountDimensionRule_key" ON "AccountDimensionRule" ("organizationId", "accountId", dimension);

-- Allocation rules. kind 'split': a posting line tagged with the rule is split across the targets at post time.
-- kind 'periodic': a run moves the period's balance of the source centre (optionally limited to accounts) to the
-- targets with an allocation journal. Shares = weight / Σ weights (percent, floor area, headcount, ...).
CREATE TABLE "CostAllocationRule" (
    id                     TEXT         NOT NULL PRIMARY KEY,
    "organizationId"       TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                   TEXT         NOT NULL,
    name                   TEXT         NOT NULL,
    kind                   TEXT         NOT NULL CHECK (kind IN ('split', 'periodic')),
    driver                 TEXT         NOT NULL DEFAULT 'percent',
    "sourceCostCenterId"   TEXT REFERENCES "CostCenter" (id) ON DELETE RESTRICT,
    "accountIds"           TEXT[],
    "isActive"             BOOLEAN      NOT NULL DEFAULT true,
    notes                  TEXT,
    "createdBy"            TEXT,
    "createdAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"            TIMESTAMP(3)
);
CREATE UNIQUE INDEX "CostAllocationRule_org_code_key" ON "CostAllocationRule" ("organizationId", code)
    WHERE "deletedAt" IS NULL;

CREATE TABLE "CostAllocationTarget" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "ruleId"         TEXT           NOT NULL REFERENCES "CostAllocationRule" (id) ON DELETE CASCADE,
    "costCenterId"   TEXT           NOT NULL REFERENCES "CostCenter" (id) ON DELETE RESTRICT,
    weight           NUMERIC(20, 6) NOT NULL CHECK (weight > 0),
    "lineNumber"     INTEGER        NOT NULL DEFAULT 1
);
CREATE INDEX "CostAllocationTarget_rule_idx" ON "CostAllocationTarget" ("organizationId", "ruleId");

CREATE TABLE "CostAllocationRun" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "ruleId"            TEXT           NOT NULL REFERENCES "CostAllocationRule" (id) ON DELETE RESTRICT,
    "periodFrom"        DATE           NOT NULL,
    "periodTo"          DATE           NOT NULL,
    status              TEXT           NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'reversed')),
    "totalAmount"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "journalEntryId"    TEXT,
    detail              JSONB,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"        TIMESTAMP(3),
    "reversedBy"        TEXT
);
CREATE UNIQUE INDEX "CostAllocationRun_once_key" ON "CostAllocationRun" ("organizationId", "ruleId", "periodFrom",
    "periodTo") WHERE status = 'posted';

CREATE INDEX IF NOT EXISTS "JournalLine_org_costCenter_idx" ON "JournalLine" ("organizationId", "costCenterId")
    WHERE "costCenterId" IS NOT NULL;

-- ---------------------------------------------------------------- inventory costing policy (inventory)

CREATE TABLE "InventoryCostPolicy" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code               TEXT         NOT NULL,
    "scopeType"        TEXT         NOT NULL CHECK ("scopeType" IN ('organization', 'category')),
    "categoryId"       TEXT,
    method             TEXT         NOT NULL CHECK (method IN ('AVCO', 'FIFO', 'SPECIFIC', 'STANDARD')),
    "effectiveFrom"    DATE         NOT NULL,
    status             TEXT         NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'approved', 'active', 'superseded', 'cancelled')),
    version            INTEGER      NOT NULL DEFAULT 1,
    notes              TEXT,
    "approvedBy"       TEXT,
    "approvedAt"       TIMESTAMP(3),
    "activatedAt"      TIMESTAMP(3),
    "migrationSummary" JSONB,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "InventoryCostPolicy_scope_chk" CHECK (("scopeType" = 'category') = ("categoryId" IS NOT NULL))
);
CREATE UNIQUE INDEX "InventoryCostPolicy_org_code_key" ON "InventoryCostPolicy" ("organizationId", code);
-- At most one active policy per scope.
CREATE UNIQUE INDEX "InventoryCostPolicy_active_key" ON "InventoryCostPolicy" ("organizationId", "scopeType",
    COALESCE("categoryId", '')) WHERE status = 'active';

-- One row per product × location re-costed when a policy changed the method (audit + reconciliation).
CREATE TABLE "InventoryCostMigrationLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "policyId"       TEXT           NOT NULL REFERENCES "InventoryCostPolicy" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL,
    "locationId"     TEXT,
    "methodFrom"     TEXT           NOT NULL,
    "methodTo"       TEXT           NOT NULL,
    quantity         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "valueBefore"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "valueAfter"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "InventoryCostMigrationLine_policy_idx" ON "InventoryCostMigrationLine" ("organizationId", "policyId");

-- ---------------------------------------------------------------- product master (core)

ALTER TABLE "Product"
    ADD COLUMN weight NUMERIC(20, 6),
    ADD COLUMN volume NUMERIC(20, 6);

-- ---------------------------------------------------------------- landed cost (procurement)

-- Cost component master: default IAS 2 treatment and accounts per charge type.
CREATE TABLE "CostComponent" (
    id                        TEXT         NOT NULL PRIMARY KEY,
    "organizationId"          TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                      TEXT         NOT NULL,
    name                      TEXT         NOT NULL,
    kind                      TEXT         NOT NULL DEFAULT 'other'
        CHECK (kind IN ('freight', 'duty', 'insurance', 'handling', 'other')),
    treatment                 TEXT         NOT NULL CHECK (treatment IN ('capitalize', 'expense', 'recoverable_tax')),
    "defaultAllocationMethod" TEXT         NOT NULL DEFAULT 'value'
        CHECK ("defaultAllocationMethod" IN ('value', 'quantity', 'equal', 'weight', 'volume', 'manual')),
    "expenseAccountId"        TEXT,
    description               TEXT,
    "isActive"                BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CostComponent_org_code_key" ON "CostComponent" ("organizationId", code);

-- Import shipment: groups the goods receipts / vouchers of one consignment.
CREATE TABLE "Shipment" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    reference        TEXT,
    "supplierId"     TEXT,
    "transportMode"  TEXT,
    "billOfLading"   TEXT,
    origin           TEXT,
    "etd"            DATE,
    "eta"            DATE,
    "arrivedAt"      DATE,
    status           TEXT         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_transit', 'arrived', 'closed')),
    "costCenterId"   TEXT,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "Shipment_org_code_key" ON "Shipment" ("organizationId", code);

CREATE TABLE "ShipmentReceipt" (
    id               TEXT NOT NULL PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "shipmentId"     TEXT NOT NULL REFERENCES "Shipment" (id) ON DELETE CASCADE,
    "goodsReceiptId" TEXT NOT NULL
);
CREATE UNIQUE INDEX "ShipmentReceipt_key" ON "ShipmentReceipt" ("organizationId", "goodsReceiptId");

ALTER TABLE "LandedCost"
    ALTER COLUMN "goodsReceiptId" DROP NOT NULL,
    ALTER COLUMN "creditAccountId" DROP NOT NULL,
    ADD COLUMN "voucherType"            TEXT NOT NULL DEFAULT 'receipt' CHECK ("voucherType" IN ('receipt', 'voucher')),
    ADD COLUMN "shipmentId"             TEXT,
    ADD COLUMN "costCenterId"           TEXT,
    ADD COLUMN "submittedAt"            TIMESTAMP(3),
    ADD COLUMN "submittedBy"            TEXT,
    ADD COLUMN "approvedAt"             TIMESTAMP(3),
    ADD COLUMN "approvedBy"             TEXT,
    ADD COLUMN "reversedAt"             TIMESTAMP(3),
    ADD COLUMN "reversedBy"             TEXT,
    ADD COLUMN "reversalJournalEntryId" TEXT,
    ADD COLUMN "taxAmount"              NUMERIC(20, 6) NOT NULL DEFAULT 0;
ALTER TABLE "LandedCost" DROP CONSTRAINT "LandedCost_method_chk";
ALTER TABLE "LandedCost" ADD CONSTRAINT "LandedCost_method_chk"
    CHECK ("allocationMethod" IN ('value', 'quantity', 'equal', 'weight', 'volume', 'manual'));
ALTER TABLE "LandedCost" DROP CONSTRAINT "LandedCost_status_chk";
ALTER TABLE "LandedCost" ADD CONSTRAINT "LandedCost_status_chk"
    CHECK (status IN ('draft', 'submitted', 'approved', 'posted', 'reversed', 'cancelled'));
ALTER TABLE "LandedCost" ADD CONSTRAINT "LandedCost_receipt_chk"
    CHECK ("voucherType" = 'voucher' OR ("goodsReceiptId" IS NOT NULL AND "creditAccountId" IS NOT NULL));
CREATE INDEX "LandedCost_org_shipment_idx" ON "LandedCost" ("organizationId", "shipmentId");

-- Receipts a voucher allocates over (a legacy landed cost has exactly its goodsReceiptId).
CREATE TABLE "LandedCostReceipt" (
    id               TEXT NOT NULL PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "landedCostId"   TEXT NOT NULL REFERENCES "LandedCost" (id) ON DELETE CASCADE,
    "goodsReceiptId" TEXT NOT NULL
);
CREATE UNIQUE INDEX "LandedCostReceipt_key" ON "LandedCostReceipt" ("landedCostId", "goodsReceiptId");
CREATE INDEX "LandedCostReceipt_grn_idx" ON "LandedCostReceipt" ("organizationId", "goodsReceiptId");

ALTER TABLE "LandedCostCharge"
    ADD COLUMN "lineNumber"         INTEGER        NOT NULL DEFAULT 1,
    ADD COLUMN "costComponentId"    TEXT,
    ADD COLUMN "supplierId"         TEXT,
    ADD COLUMN "supplierInvoiceRef" TEXT,
    ADD COLUMN "invoiceDate"        DATE,
    ADD COLUMN "currencyCode"       TEXT,
    ADD COLUMN "exchangeRate"       NUMERIC(20, 10) NOT NULL DEFAULT 1,
    ADD COLUMN "baseAmount"         NUMERIC(20, 6),
    ADD COLUMN "taxAmount"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN treatment            TEXT CHECK (treatment IN ('capitalize', 'expense', 'recoverable_tax')),
    ADD COLUMN "allocationMethod"   TEXT
        CHECK ("allocationMethod" IN ('value', 'quantity', 'equal', 'weight', 'volume', 'manual')),
    ADD COLUMN "expenseAccountId"   TEXT,
    ADD COLUMN "creditAccountId"    TEXT,
    ADD COLUMN "costCenterId"       TEXT,
    ADD COLUMN "appliesToLineIds"   TEXT[],
    ADD COLUMN "manualAllocations"  JSONB;
CREATE INDEX "LandedCostCharge_supplier_ref_idx" ON "LandedCostCharge" ("organizationId", "supplierId",
    "supplierInvoiceRef") WHERE "supplierInvoiceRef" IS NOT NULL;

ALTER TABLE "LandedCostAllocation"
    ADD COLUMN "chargeId"        TEXT,
    ADD COLUMN "goodsReceiptId"  TEXT,
    ADD COLUMN "basisMethod"     TEXT,
    ADD COLUMN "sharePct"        NUMERIC(20, 10),
    ADD COLUMN "roundingAmount"  NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "reversedAmount"  NUMERIC(20, 6) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "CostCenterDefault" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CostCenterDefault" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "AccountDimensionRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "AccountDimensionRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CostAllocationRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CostAllocationRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CostAllocationTarget" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CostAllocationTarget" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CostAllocationRun" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CostAllocationRun" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "InventoryCostPolicy" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "InventoryCostPolicy" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "InventoryCostMigrationLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "InventoryCostMigrationLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CostComponent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CostComponent" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "Shipment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "Shipment" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShipmentReceipt" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShipmentReceipt" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "LandedCostReceipt" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "LandedCostReceipt" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['cost_center:allocate', 'costing_policy:read', 'costing_policy:manage', 'costing_policy:approve',
                  'landed_cost:read', 'landed_cost:create', 'landed_cost:approve', 'landed_cost:post',
                  'landed_cost:reverse', 'landed_cost:configure']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['cost_center:allocate', 'costing_policy:read',
        'costing_policy:manage', 'costing_policy:approve', 'landed_cost:read', 'landed_cost:create',
        'landed_cost:approve', 'landed_cost:post', 'landed_cost:reverse', 'landed_cost:configure'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
