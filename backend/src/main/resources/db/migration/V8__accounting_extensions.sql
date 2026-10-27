-- V8: accounting completion (ADR-S005) — budgets and the new permission keys.
--
-- Additive extension beyond the NestJS reference: no existing table or row changes. Budgets hold monthly
-- amounts per postable account ("period" = YYYY-MM); budget-vs-actual reads posted GL lines.

CREATE TABLE "Budget" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    "fiscalYear"     INTEGER      NOT NULL,
    "startDate"      DATE         NOT NULL,
    "endDate"        DATE         NOT NULL,
    "branchId"       TEXT,
    "costCenterId"   TEXT,
    status           TEXT         NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'approved')),
    notes            TEXT,
    "approvedBy"     TEXT,
    "approvedAt"     TIMESTAMP(3),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3),
    CHECK ("endDate" >= "startDate")
);
CREATE INDEX "Budget_organizationId_idx" ON "Budget" ("organizationId");
CREATE UNIQUE INDEX "Budget_organizationId_name_live_key" ON "Budget" ("organizationId", name) WHERE "deletedAt" IS NULL;

CREATE TABLE "BudgetLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL,
    "budgetId"       TEXT           NOT NULL REFERENCES "Budget" (id) ON DELETE CASCADE,
    "accountId"      TEXT           NOT NULL REFERENCES "Account" (id),
    period           TEXT           NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    amount           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    notes            TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "BudgetLine_organizationId_idx" ON "BudgetLine" ("organizationId");
CREATE UNIQUE INDEX "BudgetLine_budgetId_accountId_period_key" ON "BudgetLine" ("budgetId", "accountId", period);

ALTER TABLE "Budget" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "Budget" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "BudgetLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "BudgetLine" USING (("organizationId" = current_setting('app.org_id', true)));

-- New permission keys: catalog rows + granted to every existing Administrator role.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['budget:read', 'budget:create', 'budget:update', 'budget:delete', 'budget:approve',
                  'opening_balance:read', 'opening_balance:post', 'invoice:send']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['budget:read', 'budget:create', 'budget:update',
        'budget:delete', 'budget:approve', 'opening_balance:read', 'opening_balance:post', 'invoice:send'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
