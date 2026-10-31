-- V91: HR-2 — employment contract records (Track C Phase 4). An employee may hold many contracts over time, one active
-- at a time. HrEmployee."contractEndDate" stays and follows the active contract, so existing screens keep working.
-- Pay stays on HrEmployee behind hr:compensation; contracts carry no salary.

CREATE TABLE "HrEmployeeContract" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT          NOT NULL,
    "employeeId"        TEXT          NOT NULL REFERENCES "HrEmployee" (id),
    "contractType"      TEXT          NOT NULL DEFAULT 'permanent'
        CHECK ("contractType" IN ('permanent', 'fixed_term', 'casual', 'seasonal', 'consultant', 'apprenticeship')),
    status              TEXT          NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'active', 'expired', 'terminated', 'superseded')),
    "startDate"         DATE          NOT NULL,
    "endDate"           DATE,
    "positionId"        TEXT          REFERENCES "HrPosition" (id),
    "departmentId"      TEXT          REFERENCES "HrDepartment" (id),
    "hoursPerWeek"      NUMERIC(6, 2),
    "probationMonths"   INTEGER       CHECK ("probationMonths" IS NULL OR "probationMonths" >= 0),
    "signedOn"          DATE,
    "documentId"        TEXT          REFERENCES "HrEmployeeDocument" (id),
    "renewalOfId"       TEXT          REFERENCES "HrEmployeeContract" (id),
    "terminatedOn"      DATE,
    "terminationReason" TEXT,
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3),
    CONSTRAINT "HrEmployeeContract_dates_chk" CHECK ("endDate" IS NULL OR "endDate" >= "startDate")
);
CREATE UNIQUE INDEX "HrEmployeeContract_org_code_key" ON "HrEmployeeContract" ("organizationId", code);
CREATE INDEX "HrEmployeeContract_org_employee_idx" ON "HrEmployeeContract" ("organizationId", "employeeId");
CREATE INDEX "HrEmployeeContract_org_end_idx" ON "HrEmployeeContract" ("organizationId", "endDate")
    WHERE status = 'active' AND "deletedAt" IS NULL;
-- One active contract per employee.
CREATE UNIQUE INDEX "HrEmployeeContract_one_active_key" ON "HrEmployeeContract" ("employeeId")
    WHERE status = 'active' AND "deletedAt" IS NULL;

ALTER TABLE "HrEmployeeContract" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrEmployeeContract" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['hr:contract']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['hr:contract'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
