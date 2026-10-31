-- V92: HR-3 — configurable overtime (Track C Phase 4). Without an active rule the payroll engine keeps the 1.5
-- multiplier of the Nest port. A rule sets multipliers per kind of day and can target a department, a position or an
-- employment type with the same "key:value" syntax as payroll components.

CREATE TABLE "HrOvertimeRule" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT          NOT NULL,
    name                TEXT          NOT NULL,
    -- NULL: everyone without a more specific rule. Otherwise departmentId:<id>, positionId:<id>, employmentType:<type>.
    "appliesTo"         TEXT,
    "weekdayMultiplier" NUMERIC(6, 3) NOT NULL DEFAULT 1.5 CHECK ("weekdayMultiplier" > 0),
    "weekendMultiplier" NUMERIC(6, 3) NOT NULL DEFAULT 1.5 CHECK ("weekendMultiplier" > 0),
    "holidayMultiplier" NUMERIC(6, 3) NOT NULL DEFAULT 2 CHECK ("holidayMultiplier" > 0),
    -- Comma-separated ISO days counted as weekend, e.g. SATURDAY,SUNDAY or SUNDAY.
    "weekendDays"       TEXT          NOT NULL DEFAULT 'SATURDAY,SUNDAY',
    "isActive"          BOOLEAN       NOT NULL DEFAULT true,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrOvertimeRule_org_code_key" ON "HrOvertimeRule" ("organizationId", code) WHERE "deletedAt" IS NULL;

ALTER TABLE "HrOvertimeRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrOvertimeRule" USING (("organizationId" = current_setting('app.org_id', true)));
