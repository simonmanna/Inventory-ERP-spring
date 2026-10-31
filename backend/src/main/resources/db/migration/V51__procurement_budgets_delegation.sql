-- V51: procurement budgets, spend control and delegated purchasing (PROC-3).
--
-- Inert until configured: with no active budget and no delegation rule, requests and orders behave as before.
-- Consumption is computed from the live documents, never stored, so it cannot drift from them. The department column
-- is used only for budget and approval scope; it is deliberately not on the entities, so existing routes keep their
-- exact JSON. Branch, department, category and user ids are plain text like the rest of the schema.

ALTER TABLE "PurchaseRequest" ADD COLUMN "departmentId" TEXT;
ALTER TABLE "PurchaseOrder" ADD COLUMN "departmentId" TEXT;

CREATE TABLE "ProcurementBudget" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    "startDate"      DATE           NOT NULL,
    "endDate"        DATE           NOT NULL,
    -- Scope: an empty column means all.
    "branchId"       TEXT,
    "departmentId"   TEXT,
    "categoryId"     TEXT,
    -- In base currency, excluding tax.
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount >= 0),
    "warnPercent"    NUMERIC(5, 2)  NOT NULL DEFAULT 90 CHECK ("warnPercent" > 0 AND "warnPercent" <= 100),
    -- block refuses a document that would go over; warn lets it through and records it.
    "controlMode"    TEXT           NOT NULL DEFAULT 'block' CHECK ("controlMode" IN ('warn', 'block')),
    -- draft (editable) → active (enforced) → closed.
    status           TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'closed')),
    notes            TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProcurementBudget_period_check" CHECK ("endDate" >= "startDate")
);
CREATE INDEX "ProcurementBudget_org_status_idx" ON "ProcurementBudget" ("organizationId", status);

-- Who may buy for a branch. Once a branch has an active rule, only central buyers and matching delegates may.
CREATE TABLE "ProcurementDelegation" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT           NOT NULL,
    -- central: only central buyers. delegated: the delegate (or anyone, when empty) up to the limit.
    mode             TEXT           NOT NULL CHECK (mode IN ('central', 'delegated')),
    "delegateUserId" TEXT,
    "maxOrderAmount" NUMERIC(20, 6) CHECK ("maxOrderAmount" >= 0),
    "validFrom"      DATE,
    "validTo"        DATE,
    "isActive"       BOOLEAN        NOT NULL DEFAULT TRUE,
    notes            TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ProcurementDelegation_org_branch_idx" ON "ProcurementDelegation" ("organizationId", "branchId");

ALTER TABLE "ProcurementBudget" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcurementBudget" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProcurementDelegation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcurementDelegation" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['procurement_budget:read', 'procurement_budget:manage', 'procurement_delegation:read',
                  'procurement_delegation:manage', 'procurement_delegation:central']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['procurement_budget:read',
        'procurement_budget:manage', 'procurement_delegation:read', 'procurement_delegation:manage',
        'procurement_delegation:central'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
