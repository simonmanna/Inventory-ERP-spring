-- V241: HP-0 — payroll foundations (docs/roadmap/hr-payroll-plan.md, ADR-S047). Additive: an organisation without a
-- settings row and without pay groups keeps the Nest payroll behaviour unchanged.

-- One row per organisation. Absent → defaults are reported and nothing is enforced.
CREATE TABLE "HrPayrollSettings" (
    id                        TEXT         NOT NULL PRIMARY KEY,
    "organizationId"          TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- v1: the Nest calculation. v2: the decimal engine (HP-2).
    engine                    TEXT         NOT NULL DEFAULT 'v1' CHECK (engine IN ('v1', 'v2')),
    -- Statutory rules applied by engine v2 (HP-3): NONE, UG (Uganda), AE (United Arab Emirates).
    "statutoryPack"           TEXT         NOT NULL DEFAULT 'NONE' CHECK ("statutoryPack" IN ('NONE', 'UG', 'AE')),
    -- How a partial period is paid: calendar days, working days, or a fixed 30-day month.
    "prorationBasis"          TEXT         NOT NULL DEFAULT 'CALENDAR_DAYS'
        CHECK ("prorationBasis" IN ('CALENDAR_DAYS', 'WORKING_DAYS', 'FIXED_30')),
    -- Comma-separated ISO days that are not working days, e.g. SATURDAY,SUNDAY.
    "weekendDays"             TEXT         NOT NULL DEFAULT 'SATURDAY,SUNDAY',
    "standardHoursPerDay"     NUMERIC(5, 2) NOT NULL DEFAULT 8
        CHECK ("standardHoursPerDay" > 0 AND "standardHoursPerDay" <= 24),
    -- Decimal places of pay amounts (UGX 0, AED 2).
    "roundingScale"           INTEGER      NOT NULL DEFAULT 2 CHECK ("roundingScale" BETWEEN 0 AND 4),
    -- Maker-checker: the user who calculated a run cannot approve it.
    "requireSeparateApprover" BOOLEAN      NOT NULL DEFAULT true,
    -- Approving a run locks its period; reversing the run reopens it.
    "lockPeriodOnApproval"    BOOLEAN      NOT NULL DEFAULT true,
    "payslipNote"             TEXT,
    "createdBy"               TEXT,
    "updatedBy"               TEXT,
    "createdAt"               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "HrPayrollSettings_org_key" ON "HrPayrollSettings" ("organizationId");

-- Employees paid together on one calendar. Employees without a group belong to the default group.
CREATE TABLE "HrPayGroup" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code               TEXT         NOT NULL,
    name               TEXT         NOT NULL,
    "payFrequency"     TEXT         NOT NULL DEFAULT 'MONTHLY'
        CHECK ("payFrequency" IN ('MONTHLY', 'BIWEEKLY', 'WEEKLY')),
    -- First day of the first period for WEEKLY / BIWEEKLY calendars.
    "anchorDate"       DATE,
    -- MONTHLY: day of the month (0 = last day). WEEKLY / BIWEEKLY: days after the period end.
    "paymentDay"       INTEGER      NOT NULL DEFAULT 0 CHECK ("paymentDay" BETWEEN 0 AND 31),
    "isDefault"        BOOLEAN      NOT NULL DEFAULT false,
    "isActive"         BOOLEAN      NOT NULL DEFAULT true,
    description        TEXT,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3),
    CHECK ("payFrequency" = 'MONTHLY' OR "anchorDate" IS NOT NULL)
);
CREATE UNIQUE INDEX "HrPayGroup_org_code_key" ON "HrPayGroup" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "HrPayGroup_org_default_key" ON "HrPayGroup" ("organizationId")
    WHERE "isDefault" AND "deletedAt" IS NULL;

-- Not mapped on the Nest entities, so the Nest JSON of employees and periods is unchanged.
ALTER TABLE "HrEmployee" ADD COLUMN "payGroupId" TEXT REFERENCES "HrPayGroup" (id) ON DELETE SET NULL;
CREATE INDEX "HrEmployee_payGroupId_idx" ON "HrEmployee" ("payGroupId") WHERE "payGroupId" IS NOT NULL;

ALTER TABLE "HrPayrollPeriod" ADD COLUMN "payGroupId" TEXT REFERENCES "HrPayGroup" (id) ON DELETE SET NULL;
ALTER TABLE "HrPayrollPeriod" ADD COLUMN "paymentDate" DATE;
ALTER TABLE "HrPayrollPeriod" ADD COLUMN "lockedAt" TIMESTAMP(3);
ALTER TABLE "HrPayrollPeriod" ADD COLUMN "lockedBy" TEXT;
CREATE INDEX "HrPayrollPeriod_payGroupId_idx" ON "HrPayrollPeriod" ("payGroupId") WHERE "payGroupId" IS NOT NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['HrPayrollSettings', 'HrPayGroup'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['hr:payroll_settings']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['hr:payroll_settings'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
