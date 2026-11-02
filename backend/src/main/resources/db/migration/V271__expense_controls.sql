-- EX-2 (ADR-S052): returned-for-correction status, spending limits and expense settings.

ALTER TYPE "ExpenseStatus" ADD VALUE IF NOT EXISTS 'RETURNED';

-- A spending limit. Every scope column is optional (null = any): a limit with userId only caps that person, one with
-- categoryId + branchId caps that category in that branch, and so on. per_expense caps one expense, monthly the sum of
-- the calendar month of the expense date. block refuses; warn records the overrun in the audit trail.
CREATE TABLE "ExpenseLimit" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    "userId"         TEXT,
    "categoryId"     TEXT,
    "branchId"       TEXT,
    period           TEXT           NOT NULL DEFAULT 'per_expense' CHECK (period IN ('per_expense', 'monthly')),
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    action           TEXT           NOT NULL DEFAULT 'block' CHECK (action IN ('block', 'warn')),
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ExpenseLimit_org" ON "ExpenseLimit" ("organizationId") WHERE "isActive";

-- One row per organization; absent = the defaults below.
CREATE TABLE "ExpenseSettings" (
    "organizationId"         TEXT PRIMARY KEY REFERENCES "Organization" (id) ON DELETE CASCADE,
    "notifyOnDecision"       BOOLEAN      NOT NULL DEFAULT true,
    "notifyOnPayment"        BOOLEAN      NOT NULL DEFAULT true,
    "notifyApproversOnSubmit" BOOLEAN     NOT NULL DEFAULT false,
    "recurringReminderDays"  INTEGER      NOT NULL DEFAULT 3 CHECK ("recurringReminderDays" BETWEEN 0 AND 60),
    "claimReceiptRequired"   BOOLEAN      NOT NULL DEFAULT false,
    "updatedBy"              TEXT,
    "updatedAt"              TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ExpenseLimit', 'ExpenseSettings'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
