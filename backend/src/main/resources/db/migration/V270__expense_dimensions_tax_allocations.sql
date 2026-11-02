-- EX-1 (ADR-S052): expense category controls, expense dimensions / tax / currency / receipt fields, and allocation
-- lines. Additive: every new column is nullable or defaulted, so existing rows and the Nest-shaped responses keep
-- their meaning.

ALTER TABLE "ExpenseCategory"
    ADD COLUMN code                  TEXT,
    ADD COLUMN "defaultTaxId"        TEXT,
    ADD COLUMN "defaultCostCenterId" TEXT,
    ADD COLUMN "receiptRequired"     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "approvalRequired"    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "approvalThreshold"   NUMERIC(20, 6),
    ADD COLUMN reimbursable          BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "budgetControl"       TEXT    NOT NULL DEFAULT 'none',
    ADD COLUMN "canCapitalise"       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "assetAccountId"      TEXT,
    ADD CONSTRAINT "ExpenseCategory_budgetControl" CHECK ("budgetControl" IN ('none', 'warn', 'block'));

CREATE UNIQUE INDEX "ExpenseCategory_org_code" ON "ExpenseCategory" ("organizationId", lower(code))
    WHERE code IS NOT NULL AND "deletedAt" IS NULL;

ALTER TABLE "Expense"
    ADD COLUMN "branchId"       TEXT,
    ADD COLUMN "departmentId"   TEXT,
    ADD COLUMN "costCenterId"   TEXT,
    ADD COLUMN "projectId"      TEXT,
    ADD COLUMN "receiptNumber"  TEXT,
    ADD COLUMN "dueDate"        TIMESTAMP(3),
    ADD COLUMN "paidBy"         TEXT           NOT NULL DEFAULT 'COMPANY',
    ADD COLUMN "claimantId"     TEXT,
    ADD COLUMN "currencyCode"   TEXT,
    ADD COLUMN "exchangeRate"   NUMERIC(20, 8),
    ADD COLUMN "foreignAmount"  NUMERIC(20, 6),
    ADD COLUMN "taxId"          TEXT,
    ADD COLUMN "taxAmount"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN capitalize       BOOLEAN        NOT NULL DEFAULT false,
    ADD CONSTRAINT "Expense_paidBy" CHECK ("paidBy" IN ('COMPANY', 'EMPLOYEE')),
    ADD CONSTRAINT "Expense_taxAmount" CHECK ("taxAmount" >= 0 AND "taxAmount" < amount);

CREATE INDEX "Expense_org_branch" ON "Expense" ("organizationId", "branchId") WHERE "branchId" IS NOT NULL;
CREATE INDEX "Expense_org_costCenter" ON "Expense" ("organizationId", "costCenterId") WHERE "costCenterId" IS NOT NULL;
CREATE INDEX "Expense_org_claimant" ON "Expense" ("organizationId", "claimantId") WHERE "claimantId" IS NOT NULL;

-- How the net (amount − tax) of an expense is spread over accounts / cost centres / branches / departments / projects.
-- No rows = one debit line carrying the expense's own tags.
CREATE TABLE "ExpenseAllocation" (
    id                     TEXT PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "expenseId"            TEXT           NOT NULL REFERENCES "Expense" (id) ON DELETE CASCADE,
    "lineNo"               INTEGER        NOT NULL,
    amount                 NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    percent                NUMERIC(9, 4),
    "accountId"            TEXT,
    "costCenterId"         TEXT,
    "costAllocationRuleId" TEXT,
    "branchId"             TEXT,
    "departmentId"         TEXT,
    "projectId"            TEXT,
    description            TEXT,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ExpenseAllocation_line" UNIQUE ("expenseId", "lineNo")
);
CREATE INDEX "ExpenseAllocation_org_expense" ON "ExpenseAllocation" ("organizationId", "expenseId");
CREATE INDEX "ExpenseAllocation_org_costCenter" ON "ExpenseAllocation" ("organizationId", "costCenterId");

ALTER TABLE "ExpenseAllocation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ExpenseAllocation"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
