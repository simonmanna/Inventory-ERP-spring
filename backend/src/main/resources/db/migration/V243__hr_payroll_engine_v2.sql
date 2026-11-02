-- V243: HP-2 — payroll engine v2: one result line per pay element, statutory item, input, loan and advance of an
-- employee in a run. The Nest item / allowance / deduction rows are still written, so existing screens keep working.

CREATE TABLE "HrPayrollResultLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"          TEXT           NOT NULL REFERENCES "HrPayrollRun" (id) ON DELETE CASCADE,
    "itemId"         TEXT           NOT NULL,
    "employeeId"     TEXT           NOT NULL REFERENCES "HrEmployee" (id),
    code             TEXT           NOT NULL,
    name             TEXT           NOT NULL,
    -- EARNING, DEDUCTION, EMPLOYER_CONTRIBUTION, INFO
    kind             TEXT           NOT NULL,
    -- BASIC, OVERTIME, ELEMENT, INPUT, RETRO, LATE, STATUTORY, TAX, LOAN, ADVANCE, INPUT_DEDUCTION
    category         TEXT           NOT NULL,
    "statutoryCode"  TEXT,
    amount           NUMERIC(20, 6) NOT NULL,
    base             NUMERIC(20, 6),
    rate             NUMERIC(14, 8),
    quantity         NUMERIC(14, 4),
    "isTaxable"      BOOLEAN        NOT NULL DEFAULT false,
    "glAccountId"    TEXT,
    "payableKey"     TEXT,
    "expenseKey"     TEXT,
    "sourceId"       TEXT,
    "sortOrder"      INTEGER        NOT NULL DEFAULT 100,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "HrPayrollResultLine_run_idx" ON "HrPayrollResultLine" ("runId", "employeeId");
CREATE INDEX "HrPayrollResultLine_stat_idx" ON "HrPayrollResultLine" ("organizationId", "statutoryCode")
    WHERE "statutoryCode" IS NOT NULL;

-- Which engine calculated a run (null = v1 / Nest).
ALTER TABLE "HrPayrollRun" ADD COLUMN "engine" TEXT;

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    EXECUTE 'ALTER TABLE public."HrPayrollResultLine" ENABLE ROW LEVEL SECURITY';
    EXECUTE format('CREATE POLICY tenant_isolation ON public."HrPayrollResultLine" USING (%s) WITH CHECK (%s)', expr,
                   expr);
END $$;
