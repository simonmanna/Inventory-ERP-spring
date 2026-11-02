-- V242: HP-1 — compensation: salary grades, pay elements, salary structures, effective-dated employee compensation
-- with approved salary revisions, and per-employee pay elements (docs/roadmap/hr-payroll-plan.md, ADR-S048).

CREATE TABLE "HrSalaryGrade" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT           NOT NULL,
    name             TEXT           NOT NULL,
    "minAmount"      NUMERIC(20, 6),
    "midAmount"      NUMERIC(20, 6),
    "maxAmount"      NUMERIC(20, 6),
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    description      TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3),
    CHECK ("minAmount" IS NULL OR "maxAmount" IS NULL OR "minAmount" <= "maxAmount")
);
CREATE UNIQUE INDEX "HrSalaryGrade_org_code_key" ON "HrSalaryGrade" ("organizationId", code) WHERE "deletedAt" IS NULL;

-- The pay element catalogue of engine v2. statutoryCode ties an element to a statutory pack rule (HP-3).
CREATE TABLE "HrPayElement" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT           NOT NULL,
    name             TEXT           NOT NULL,
    kind             TEXT           NOT NULL CHECK (kind IN ('EARNING', 'DEDUCTION', 'EMPLOYER_CONTRIBUTION', 'INFO')),
    "calcMethod"     TEXT           NOT NULL DEFAULT 'FIXED'
        CHECK ("calcMethod" IN ('FIXED', 'PERCENT_OF_BASIC', 'PERCENT_OF_GROSS', 'FORMULA')),
    amount           NUMERIC(20, 6),
    rate             NUMERIC(12, 6),
    formula          TEXT,
    "isTaxable"      BOOLEAN        NOT NULL DEFAULT true,
    "isPensionable"  BOOLEAN        NOT NULL DEFAULT false,
    "isRecurring"    BOOLEAN        NOT NULL DEFAULT true,
    "isProrated"     BOOLEAN        NOT NULL DEFAULT true,
    "glAccountId"    TEXT,
    "sortOrder"      INTEGER        NOT NULL DEFAULT 100,
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    description      TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrPayElement_org_code_key" ON "HrPayElement" ("organizationId", code) WHERE "deletedAt" IS NULL;

CREATE TABLE "HrSalaryStructure" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT           NOT NULL,
    name             TEXT           NOT NULL,
    "gradeId"        TEXT           REFERENCES "HrSalaryGrade" (id),
    -- Lines: [{elementId, amount, rate, formula}] overriding the element defaults.
    lines            JSONB          NOT NULL DEFAULT '[]',
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    description      TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrSalaryStructure_org_code_key" ON "HrSalaryStructure" ("organizationId", code)
    WHERE "deletedAt" IS NULL;

-- Effective-dated compensation. A revision is PENDING until approved; approving closes the previous ACTIVE row.
CREATE TABLE "HrEmployeeCompensation" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT           NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    "effectiveFrom"  DATE           NOT NULL,
    "effectiveTo"    DATE,
    "basicSalary"    NUMERIC(20, 6) NOT NULL CHECK ("basicSalary" >= 0),
    "gradeId"        TEXT           REFERENCES "HrSalaryGrade" (id),
    "structureId"    TEXT           REFERENCES "HrSalaryStructure" (id),
    status           TEXT           NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUPERSEDED', 'REJECTED')),
    reason           TEXT,
    "previousBasic"  NUMERIC(20, 6),
    "approvedBy"     TEXT,
    "approvedAt"     TIMESTAMP(3),
    "rejectedReason" TEXT,
    -- HP-2 retro pay: the run that paid the arrears of a back-dated revision.
    "retroRunId"     TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "HrEmployeeCompensation_emp_idx" ON "HrEmployeeCompensation" ("employeeId", "effectiveFrom");
CREATE UNIQUE INDEX "HrEmployeeCompensation_one_pending_key" ON "HrEmployeeCompensation" ("employeeId")
    WHERE status = 'PENDING' AND "deletedAt" IS NULL;

CREATE TABLE "HrEmployeePayElement" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT           NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    "elementId"      TEXT           NOT NULL REFERENCES "HrPayElement" (id),
    amount           NUMERIC(20, 6),
    rate             NUMERIC(12, 6),
    formula          TEXT,
    "effectiveFrom"  DATE           NOT NULL,
    "effectiveTo"    DATE,
    note             TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3),
    CHECK ("effectiveTo" IS NULL OR "effectiveTo" >= "effectiveFrom")
);
CREATE INDEX "HrEmployeePayElement_emp_idx" ON "HrEmployeePayElement" ("employeeId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['HrSalaryGrade', 'HrPayElement', 'HrSalaryStructure', 'HrEmployeeCompensation',
                             'HrEmployeePayElement'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['hr:compensation_approve']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['hr:compensation_approve'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
