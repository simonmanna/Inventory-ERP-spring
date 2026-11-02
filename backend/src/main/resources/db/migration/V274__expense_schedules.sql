-- EX-5 (ADR-S052): recurring expenses (templates that raise CREDIT expenses, with a reminder before each run),
-- prepaid expenses released monthly, and period-end accruals that reverse themselves.

CREATE TABLE "ExpenseRecurrence" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name               TEXT           NOT NULL,
    title              TEXT           NOT NULL,
    description        TEXT,
    amount             NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "categoryId"       TEXT,
    "supplierId"       TEXT,
    "branchId"         TEXT,
    "departmentId"     TEXT,
    "costCenterId"     TEXT,
    "projectId"        TEXT,
    "taxId"            TEXT,
    frequency          TEXT           NOT NULL CHECK (frequency IN ('weekly', 'monthly', 'quarterly', 'yearly')),
    "intervalCount"    INTEGER        NOT NULL DEFAULT 1 CHECK ("intervalCount" BETWEEN 1 AND 12),
    "nextRunDate"      DATE           NOT NULL,
    "endDate"          DATE,
    "dueDays"          INTEGER        NOT NULL DEFAULT 0 CHECK ("dueDays" BETWEEN 0 AND 365),
    "reminderDays"     INTEGER CHECK ("reminderDays" BETWEEN 0 AND 60),
    "lastReminderFor"  DATE,
    "lastRunAt"        TIMESTAMP(3),
    "lastExpenseId"    TEXT,
    "runCount"         INTEGER        NOT NULL DEFAULT 0,
    status             TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused', 'ended')),
    "createdById"      TEXT           NOT NULL,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ExpenseRecurrence_due" ON "ExpenseRecurrence" ("organizationId", "nextRunDate") WHERE status = 'active';

ALTER TABLE "Expense"
    ADD COLUMN "recurrenceId"  TEXT,
    ADD COLUMN "prepaidMonths" INTEGER CHECK ("prepaidMonths" IS NULL OR "prepaidMonths" BETWEEN 1 AND 120),
    ADD COLUMN "prepaidStart"  DATE;

CREATE TABLE "ExpensePrepayment" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "expenseId"         TEXT           NOT NULL REFERENCES "Expense" (id) ON DELETE CASCADE,
    "prepaidAccountId"  TEXT           NOT NULL,
    "expenseAccountId"  TEXT           NOT NULL,
    "branchId"          TEXT,
    "costCenterId"      TEXT,
    "startMonth"        TEXT           NOT NULL CHECK ("startMonth" ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    months              INTEGER        NOT NULL CHECK (months > 0),
    "totalAmount"       NUMERIC(20, 6) NOT NULL CHECK ("totalAmount" > 0),
    "releasedAmount"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    status              TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'cancelled')),
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ExpensePrepayment_expense" UNIQUE ("expenseId")
);

CREATE TABLE "ExpensePrepaymentRelease" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "prepaymentId"     TEXT           NOT NULL REFERENCES "ExpensePrepayment" (id) ON DELETE CASCADE,
    period             TEXT           NOT NULL,
    amount             NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "journalEntryId"   TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ExpensePrepaymentRelease_period" UNIQUE ("prepaymentId", period)
);

-- A period-end accrual of an expense not yet invoiced: Dr expense / Cr accrued expenses on accrualDate, reversed on
-- reversalDate (default: first day of the next month) by the daily job or by hand.
CREATE TABLE "ExpenseAccrual" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "accrualNumber"    TEXT           NOT NULL,
    title              TEXT           NOT NULL,
    amount             NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "accountId"        TEXT           NOT NULL,
    "categoryId"       TEXT,
    "supplierId"       TEXT,
    "branchId"         TEXT,
    "costCenterId"     TEXT,
    "accrualDate"      DATE           NOT NULL,
    "reversalDate"     DATE           NOT NULL,
    status             TEXT           NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'reversed')),
    "journalEntryId"   TEXT,
    "reversalEntryId"  TEXT,
    notes              TEXT,
    "createdById"      TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ExpenseAccrual_number" UNIQUE ("organizationId", "accrualNumber"),
    CONSTRAINT "ExpenseAccrual_dates" CHECK ("reversalDate" > "accrualDate")
);
CREATE INDEX "ExpenseAccrual_due" ON "ExpenseAccrual" ("organizationId", "reversalDate") WHERE status = 'posted';

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ExpenseRecurrence', 'ExpensePrepayment', 'ExpensePrepaymentRelease',
                             'ExpenseAccrual'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
