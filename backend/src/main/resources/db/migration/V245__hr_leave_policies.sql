-- V245: HP-4 — leave policies (accrual, pro-rata, tenure tiers, carry-forward with expiry, encashment, day counting)
-- and the leave ledger. Leave balances (HrLeaveBalance) stay the figure the Nest screens show; the ledger explains it.

CREATE TABLE "HrLeavePolicy" (
    id                       TEXT          NOT NULL PRIMARY KEY,
    "organizationId"         TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "leaveTypeId"            TEXT          NOT NULL REFERENCES "HrLeaveType" (id),
    -- ANNUAL_UPFRONT: the year's entitlement on 1 January (pro-rata in the hire year); MONTHLY: 1/12 per month served.
    "accrualMethod"          TEXT          NOT NULL DEFAULT 'MONTHLY' CHECK ("accrualMethod" IN ('ANNUAL_UPFRONT', 'MONTHLY')),
    "daysPerYear"            NUMERIC(6, 2) NOT NULL CHECK ("daysPerYear" >= 0),
    -- [{"years": 5, "daysPerYear": 25}, ...]: entitlement from that many completed years of service.
    "tenureTiers"            JSONB         NOT NULL DEFAULT '[]',
    "proRataOnHire"          BOOLEAN       NOT NULL DEFAULT true,
    "minServiceMonths"       INTEGER       NOT NULL DEFAULT 0,
    "carryForwardMax"        NUMERIC(6, 2) NOT NULL DEFAULT 0,
    "carryForwardExpiryMonths" INTEGER     NOT NULL DEFAULT 0,
    "encashable"             BOOLEAN       NOT NULL DEFAULT false,
    "maxEncashDays"          NUMERIC(6, 2),
    "countWeekends"          BOOLEAN       NOT NULL DEFAULT false,
    "countHolidays"          BOOLEAN       NOT NULL DEFAULT false,
    "allowHalfDay"           BOOLEAN       NOT NULL DEFAULT true,
    "isActive"               BOOLEAN       NOT NULL DEFAULT true,
    "createdBy"              TEXT,
    "updatedBy"              TEXT,
    "createdAt"              TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"              TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"              TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrLeavePolicy_type_key" ON "HrLeavePolicy" ("organizationId", "leaveTypeId")
    WHERE "deletedAt" IS NULL;

CREATE TABLE "HrLeaveLedger" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT          NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    "leaveTypeId"    TEXT          NOT NULL REFERENCES "HrLeaveType" (id),
    year             INTEGER       NOT NULL,
    -- ACCRUAL, CARRY_FORWARD, EXPIRY, ENCASHMENT, ADJUSTMENT
    "entryType"      TEXT          NOT NULL,
    days             NUMERIC(8, 2) NOT NULL,
    "postedOn"       DATE          NOT NULL,
    -- Accrual idempotency: one ACCRUAL per employee, type and month ("2026-03"), one CARRY_FORWARD per year.
    "periodKey"      TEXT,
    "refId"          TEXT,
    note             TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "HrLeaveLedger_emp_idx" ON "HrLeaveLedger" ("employeeId", "leaveTypeId", year);
CREATE UNIQUE INDEX "HrLeaveLedger_period_key" ON "HrLeaveLedger" ("employeeId", "leaveTypeId", "entryType", "periodKey")
    WHERE "periodKey" IS NOT NULL AND "deletedAt" IS NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['HrLeavePolicy', 'HrLeaveLedger'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
