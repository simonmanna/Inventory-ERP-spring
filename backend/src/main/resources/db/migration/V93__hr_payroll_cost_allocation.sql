-- V93: HR-5 — payroll cost allocation by department and cost centre (Track C Phase 4).
-- HrCostAllocationRule: how an employee's (or a whole department's) pay is split across cost centres. Each target's
-- percentages sum to 100; the service replaces a target's rows as one set. An employee's own rows beat the department's.
-- HrPayrollCostLine: what an approved run actually charged, per employee and cost centre, for the reports. It is written
-- for every approved run; without rules its cost centre is NULL and the journal stays the single Nest line. Reversing the
-- run removes its lines. Cost centre and branch references are plain text ids validated in the service.

CREATE TABLE "HrCostAllocationRule" (
    id                TEXT          NOT NULL PRIMARY KEY,
    "organizationId"  TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    scope             TEXT          NOT NULL CHECK (scope IN ('employee', 'department')),
    "targetId"        TEXT          NOT NULL,
    "costCenterId"    TEXT          NOT NULL,
    percent           NUMERIC(7, 4) NOT NULL CHECK (percent > 0 AND percent <= 100),
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "HrCostAllocationRule_target_center_key" ON "HrCostAllocationRule" ("organizationId", scope,
    "targetId", "costCenterId");

CREATE TABLE "HrPayrollCostLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"           TEXT           NOT NULL REFERENCES "HrPayrollRun" (id),
    "employeeId"      TEXT           NOT NULL REFERENCES "HrEmployee" (id),
    "departmentId"    TEXT,
    "branchId"        TEXT,
    "costCenterId"    TEXT,
    amount            NUMERIC(20, 6) NOT NULL,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "HrPayrollCostLine_org_run_idx" ON "HrPayrollCostLine" ("organizationId", "runId");

ALTER TABLE "HrCostAllocationRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrCostAllocationRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "HrPayrollCostLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrPayrollCostLine" USING (("organizationId" = current_setting('app.org_id', true)));
