-- EX-3 (ADR-S052): employee expense claims and cash advances. A claim groups employee-paid expenses (one Expense row
-- per line, paidBy = EMPLOYEE); approval accrues each line to the employee reimbursement payable, a linked advance is
-- settled against it, and the rest is reimbursed by paying the claim.

CREATE TABLE "ExpenseAdvance" (
    id                     TEXT PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "advanceNumber"        TEXT           NOT NULL,
    "employeeId"           TEXT           NOT NULL,
    purpose                TEXT           NOT NULL,
    amount                 NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    status                 TEXT           NOT NULL DEFAULT 'requested'
        CHECK (status IN ('requested', 'approved', 'rejected', 'disbursed', 'settled', 'cancelled')),
    "neededBy"             DATE,
    "branchId"             TEXT,
    "disbursedAmount"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "settledAmount"        NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "refundedAmount"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "approvedById"         TEXT,
    "approvedAt"           TIMESTAMP(3),
    "decisionNotes"        TEXT,
    "disbursedAt"          TIMESTAMP(3),
    notes                  TEXT,
    "createdById"          TEXT,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ExpenseAdvance_number" UNIQUE ("organizationId", "advanceNumber"),
    CONSTRAINT "ExpenseAdvance_balance" CHECK ("settledAmount" + "refundedAmount" <= "disbursedAmount")
);
CREATE INDEX "ExpenseAdvance_org_employee" ON "ExpenseAdvance" ("organizationId", "employeeId");

CREATE TABLE "ExpenseClaim" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "claimNumber"      TEXT           NOT NULL,
    "claimantId"       TEXT           NOT NULL,
    title              TEXT           NOT NULL,
    status             TEXT           NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'submitted', 'returned', 'approved', 'partially_paid', 'paid', 'rejected',
                          'cancelled')),
    "advanceId"        TEXT REFERENCES "ExpenseAdvance" (id),
    "totalAmount"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "advanceApplied"   NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "amountPaid"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "submittedAt"      TIMESTAMP(3),
    "approvedById"     TEXT,
    "approvedAt"       TIMESTAMP(3),
    "decisionNotes"    TEXT,
    notes              TEXT,
    "createdById"      TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ExpenseClaim_number" UNIQUE ("organizationId", "claimNumber")
);
CREATE INDEX "ExpenseClaim_org_claimant" ON "ExpenseClaim" ("organizationId", "claimantId");
CREATE INDEX "ExpenseClaim_org_status" ON "ExpenseClaim" ("organizationId", status);

ALTER TABLE "Expense" ADD COLUMN "claimId" TEXT REFERENCES "ExpenseClaim" (id);
CREATE INDEX "Expense_claim" ON "Expense" ("claimId") WHERE "claimId" IS NOT NULL;

-- Every money movement of an advance: disburse (Dr advance / Cr cash), settle (Dr reimbursement payable / Cr advance,
-- from a claim) and refund (Dr cash / Cr advance).
CREATE TABLE "ExpenseAdvanceMovement" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "advanceId"        TEXT           NOT NULL REFERENCES "ExpenseAdvance" (id) ON DELETE CASCADE,
    kind               TEXT           NOT NULL CHECK (kind IN ('disburse', 'settle', 'refund')),
    amount             NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "claimId"          TEXT,
    "accountId"        TEXT,
    "journalEntryId"   TEXT,
    reference          TEXT,
    "createdById"      TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ExpenseAdvanceMovement_advance" ON "ExpenseAdvanceMovement" ("organizationId", "advanceId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ExpenseAdvance', 'ExpenseClaim', 'ExpenseAdvanceMovement'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
