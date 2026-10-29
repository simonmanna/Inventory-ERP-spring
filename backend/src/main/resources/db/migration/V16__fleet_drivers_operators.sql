-- V16: fleet 1.2 — drivers & operators (ADR-S009, Track C Phase 1): driver / operator profiles linked to hr
-- employees, qualified vehicle classes, vehicle ↔ driver assignments, check-out / check-in, allowance rules and
-- driver allowance / trip-expense claims; plus the additive hr payroll-input queue the claims export to.
--
-- Additive extension beyond the NestJS reference; no existing table is changed. Cross-module references (hr employee,
-- cost centre, kernel file, approval request, expense, journal entry, user) are plain text ids validated in the
-- services, like the rest of the schema.

-- ---------------------------------------------------------------- driver / operator profiles

CREATE TABLE "FleetDriver" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT         NOT NULL,
    -- The hr employee this profile belongs to (one live profile per employee).
    "employeeId"        TEXT         NOT NULL,
    "driverType"        TEXT         NOT NULL DEFAULT 'driver'
        CHECK ("driverType" IN ('driver', 'operator', 'driver_operator')),
    -- Current driving licence: number, the legal classes printed on it (B, C, CE, ...), issue / expiry.
    "licenceNumber"     TEXT,
    "licenceClasses"    TEXT[]       NOT NULL DEFAULT '{}',
    "licenceIssuer"     TEXT,
    "licenceIssuedOn"   DATE,
    "licenceExpiresOn"  DATE,
    -- Medical fitness certificate and operator permit (mobile plant) expiries.
    "medicalExpiresOn"  DATE,
    "permitNumber"      TEXT,
    "permitExpiresOn"   DATE,
    "reminderLeadDays"  INTEGER      NOT NULL DEFAULT 30 CHECK ("reminderLeadDays" BETWEEN 0 AND 365),
    status              TEXT         NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'inactive')),
    "statusReason"      TEXT,
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3),
    CONSTRAINT "FleetDriver_licence_dates_chk" CHECK ("licenceIssuedOn" IS NULL OR "licenceExpiresOn" IS NULL
        OR "licenceExpiresOn" >= "licenceIssuedOn")
);
CREATE UNIQUE INDEX "FleetDriver_org_code_key" ON "FleetDriver" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "FleetDriver_org_employee_key" ON "FleetDriver" ("organizationId", "employeeId")
    WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "FleetDriver_org_licence_key" ON "FleetDriver" ("organizationId", "licenceNumber")
    WHERE "licenceNumber" IS NOT NULL AND "deletedAt" IS NULL;
CREATE INDEX "FleetDriver_org_status_idx" ON "FleetDriver" ("organizationId", status);
-- The expiring-documents query (1.9 reminder job) scans licence / medical / permit expiries.
CREATE INDEX "FleetDriver_org_licence_expiry_idx" ON "FleetDriver" ("organizationId", "licenceExpiresOn")
    WHERE "deletedAt" IS NULL;

-- Vehicle classes (1.1 configurable categories) the driver / operator is qualified for.
CREATE TABLE "FleetDriverQualification" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "driverId"       TEXT         NOT NULL REFERENCES "FleetDriver" (id) ON DELETE CASCADE,
    "categoryId"     TEXT         NOT NULL REFERENCES "FleetVehicleCategory" (id),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetDriverQualification_driver_category_key" ON "FleetDriverQualification" ("driverId",
    "categoryId");
CREATE INDEX "FleetDriverQualification_org_category_idx" ON "FleetDriverQualification" ("organizationId",
    "categoryId");

-- ---------------------------------------------------------------- vehicle ↔ driver assignments

CREATE TABLE "FleetAssignment" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"      TEXT         NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "driverId"       TEXT         NOT NULL REFERENCES "FleetDriver" (id) ON DELETE CASCADE,
    -- primary = the vehicle's regular driver (open-ended); temporary = cover for a period (needs an end).
    kind             TEXT         NOT NULL DEFAULT 'primary' CHECK (kind IN ('primary', 'temporary')),
    "startsAt"       TIMESTAMP(3) NOT NULL,
    "endsAt"         TIMESTAMP(3),
    status           TEXT         NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'ended')),
    notes            TEXT,
    "endReason"      TEXT,
    "createdBy"      TEXT,
    "endedBy"        TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetAssignment_period_chk" CHECK ("endsAt" IS NULL OR "endsAt" >= "startsAt"),
    CONSTRAINT "FleetAssignment_temporary_end_chk" CHECK (kind <> 'temporary' OR "endsAt" IS NOT NULL)
);
-- One active primary driver per vehicle (a new primary assignment ends the previous one).
CREATE UNIQUE INDEX "FleetAssignment_primary_key" ON "FleetAssignment" ("vehicleId")
    WHERE kind = 'primary' AND status = 'active';
CREATE INDEX "FleetAssignment_vehicle_idx" ON "FleetAssignment" ("organizationId", "vehicleId", "startsAt");
CREATE INDEX "FleetAssignment_driver_idx" ON "FleetAssignment" ("organizationId", "driverId", "startsAt");

-- ---------------------------------------------------------------- check-out / check-in

CREATE TABLE "FleetCheckout" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code              TEXT           NOT NULL,
    "vehicleId"       TEXT           NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "driverId"        TEXT           NOT NULL REFERENCES "FleetDriver" (id) ON DELETE CASCADE,
    "assignmentId"    TEXT REFERENCES "FleetAssignment" (id) ON DELETE SET NULL,
    status            TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    purpose           TEXT,
    -- Trip of 1.3 (planned FleetLoad); plain text until 1.3 adds the trips table and the foreign key.
    "tripId"          TEXT,
    "checkedOutAt"    TIMESTAMP(3)   NOT NULL,
    "outOdometer"     NUMERIC(14, 1) CHECK ("outOdometer" IS NULL OR "outOdometer" >= 0),
    "outHours"        NUMERIC(14, 1) CHECK ("outHours" IS NULL OR "outHours" >= 0),
    "outFuelLevelPct" NUMERIC(5, 1)  CHECK ("outFuelLevelPct" IS NULL OR "outFuelLevelPct" BETWEEN 0 AND 100),
    "outCondition"    TEXT           NOT NULL DEFAULT 'good' CHECK ("outCondition" IN ('good', 'fair', 'poor')),
    "outDamageFlags"  TEXT[]         NOT NULL DEFAULT '{}',
    "outNotes"        TEXT,
    "checkedOutBy"    TEXT,
    "checkedInAt"     TIMESTAMP(3),
    "inOdometer"      NUMERIC(14, 1) CHECK ("inOdometer" IS NULL OR "inOdometer" >= 0),
    "inHours"         NUMERIC(14, 1) CHECK ("inHours" IS NULL OR "inHours" >= 0),
    "inFuelLevelPct"  NUMERIC(5, 1)  CHECK ("inFuelLevelPct" IS NULL OR "inFuelLevelPct" BETWEEN 0 AND 100),
    "inCondition"     TEXT           CHECK ("inCondition" IS NULL OR "inCondition" IN ('good', 'fair', 'poor')),
    "inDamageFlags"   TEXT[]         NOT NULL DEFAULT '{}',
    "inNotes"         TEXT,
    "checkedInBy"     TEXT,
    -- Lifetime meter difference between check-out and check-in.
    "distanceKm"      NUMERIC(14, 1),
    "hoursUsed"       NUMERIC(14, 1),
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetCheckout_times_chk" CHECK ("checkedInAt" IS NULL OR "checkedInAt" >= "checkedOutAt"),
    CONSTRAINT "FleetCheckout_closed_chk" CHECK ((status = 'closed') = ("checkedInAt" IS NOT NULL))
);
CREATE UNIQUE INDEX "FleetCheckout_org_code_key" ON "FleetCheckout" ("organizationId", code);
-- One open check-out per vehicle and per driver (backstop of the row locks in the service).
CREATE UNIQUE INDEX "FleetCheckout_open_vehicle_key" ON "FleetCheckout" ("vehicleId") WHERE status = 'open';
CREATE UNIQUE INDEX "FleetCheckout_open_driver_key" ON "FleetCheckout" ("driverId") WHERE status = 'open';
CREATE INDEX "FleetCheckout_vehicle_idx" ON "FleetCheckout" ("organizationId", "vehicleId", "checkedOutAt");
CREATE INDEX "FleetCheckout_driver_idx" ON "FleetCheckout" ("organizationId", "driverId", "checkedOutAt");
CREATE INDEX "FleetCheckout_trip_idx" ON "FleetCheckout" ("tripId") WHERE "tripId" IS NOT NULL;

-- Check-out / check-in photos: vehicle attachments (1.1 file store) linked to the check-out and its phase.
CREATE TABLE "FleetCheckoutPhoto" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "checkoutId"     TEXT         NOT NULL REFERENCES "FleetCheckout" (id) ON DELETE CASCADE,
    "attachmentId"   TEXT         NOT NULL REFERENCES "FleetVehicleAttachment" (id) ON DELETE CASCADE,
    phase            TEXT         NOT NULL CHECK (phase IN ('out', 'in')),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetCheckoutPhoto_attachment_key" ON "FleetCheckoutPhoto" ("attachmentId");
CREATE INDEX "FleetCheckoutPhoto_checkout_idx" ON "FleetCheckoutPhoto" ("organizationId", "checkoutId");

-- ---------------------------------------------------------------- allowance rules

CREATE TABLE "FleetAllowanceRule" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT           NOT NULL,
    name             TEXT           NOT NULL,
    basis            TEXT           NOT NULL CHECK (basis IN ('per_trip', 'per_day', 'per_night')),
    amount           NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    -- The organization's currency when the rule was created.
    "currencyCode"   TEXT           NOT NULL,
    "effectiveFrom"  DATE           NOT NULL,
    "effectiveTo"    DATE,
    -- Optional scope: one vehicle class and / or one route zone; the most specific matching rule wins.
    "categoryId"     TEXT REFERENCES "FleetVehicleCategory" (id),
    "routeZone"      TEXT,
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetAllowanceRule_period_chk" CHECK ("effectiveTo" IS NULL OR "effectiveTo" >= "effectiveFrom")
);
CREATE UNIQUE INDEX "FleetAllowanceRule_org_code_key" ON "FleetAllowanceRule" ("organizationId", code);
CREATE INDEX "FleetAllowanceRule_org_basis_idx" ON "FleetAllowanceRule" ("organizationId", basis, "effectiveFrom");

-- ---------------------------------------------------------------- driver allowance / trip-expense claims

CREATE TABLE "FleetDriverClaim" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT           NOT NULL,
    "driverId"          TEXT           NOT NULL REFERENCES "FleetDriver" (id),
    "vehicleId"         TEXT REFERENCES "FleetVehicle" (id),
    -- Trip of 1.3 (planned FleetLoad); plain text until 1.3 adds the trips table and the foreign key.
    "tripId"            TEXT,
    "claimDate"         DATE           NOT NULL,
    "routeZone"         TEXT,
    description         TEXT,
    status              TEXT           NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'submitted', 'approved', 'rejected', 'paid', 'exported', 'cancelled')),
    "totalAmount"       NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK ("totalAmount" >= 0),
    "currencyCode"      TEXT           NOT NULL,
    -- Cost centre the expense is charged to (the vehicle's when the claim is submitted).
    "costCenterId"      TEXT,
    "approvalRequestId" TEXT,
    "submittedBy"       TEXT,
    "submittedAt"       TIMESTAMP(3),
    "decidedBy"         TEXT,
    "decidedAt"         TIMESTAMP(3),
    "decisionNotes"     TEXT,
    -- Settlement: paid through expenses (expenseId + its payment journal) or exported to hr payroll (payroll input +
    -- the fleet accrual journal).
    settlement          TEXT CHECK (settlement IS NULL OR settlement IN ('expenses', 'payroll')),
    "expenseId"         TEXT,
    "payrollInputId"    TEXT,
    "journalEntryId"    TEXT,
    "settledBy"         TEXT,
    "settledAt"         TIMESTAMP(3),
    "settlementCount"   INTEGER        NOT NULL DEFAULT 0,
    "reversedBy"        TEXT,
    "reversedAt"        TIMESTAMP(3),
    "reversalReason"    TEXT,
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetDriverClaim_org_code_key" ON "FleetDriverClaim" ("organizationId", code);
CREATE INDEX "FleetDriverClaim_org_status_idx" ON "FleetDriverClaim" ("organizationId", status);
CREATE INDEX "FleetDriverClaim_driver_idx" ON "FleetDriverClaim" ("organizationId", "driverId", "claimDate");
CREATE INDEX "FleetDriverClaim_vehicle_idx" ON "FleetDriverClaim" ("organizationId", "vehicleId")
    WHERE "vehicleId" IS NOT NULL;
CREATE INDEX "FleetDriverClaim_trip_idx" ON "FleetDriverClaim" ("tripId") WHERE "tripId" IS NOT NULL;
CREATE INDEX "FleetDriverClaim_approval_idx" ON "FleetDriverClaim" ("approvalRequestId")
    WHERE "approvalRequestId" IS NOT NULL;

CREATE TABLE "FleetDriverClaimLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "claimId"        TEXT           NOT NULL REFERENCES "FleetDriverClaim" (id) ON DELETE CASCADE,
    -- allowance = rate from an allowance rule × quantity (trips / days / nights); expense = a trip expense paid by
    -- the driver (fuel, toll, parking, meals, lodging, ...) with its receipt.
    "lineType"       TEXT           NOT NULL CHECK ("lineType" IN ('allowance', 'expense')),
    basis            TEXT CHECK (basis IS NULL OR basis IN ('per_trip', 'per_day', 'per_night')),
    "ruleId"         TEXT REFERENCES "FleetAllowanceRule" (id),
    "expenseType"    TEXT CHECK ("expenseType" IS NULL OR "expenseType" IN ('fuel', 'toll', 'parking', 'meals',
        'lodging', 'repairs', 'loading', 'other')),
    description      TEXT,
    quantity         NUMERIC(12, 2) NOT NULL DEFAULT 1 CHECK (quantity > 0),
    rate             NUMERIC(14, 2) NOT NULL CHECK (rate >= 0),
    amount           NUMERIC(14, 2) NOT NULL CHECK (amount >= 0),
    "sortOrder"      INTEGER        NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetDriverClaimLine_allowance_chk" CHECK (("lineType" = 'allowance') = (basis IS NOT NULL)),
    CONSTRAINT "FleetDriverClaimLine_expense_chk" CHECK ("lineType" <> 'expense' OR "expenseType" IS NOT NULL)
);
CREATE INDEX "FleetDriverClaimLine_claim_idx" ON "FleetDriverClaimLine" ("organizationId", "claimId");

-- Receipts (kernel file store, owner type fleet_driver_claim), optionally tied to one line.
CREATE TABLE "FleetClaimReceipt" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "claimId"        TEXT         NOT NULL REFERENCES "FleetDriverClaim" (id) ON DELETE CASCADE,
    "lineId"         TEXT REFERENCES "FleetDriverClaimLine" (id) ON DELETE SET NULL,
    "fileId"         TEXT         NOT NULL,
    title            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetClaimReceipt_file_key" ON "FleetClaimReceipt" ("fileId");
CREATE INDEX "FleetClaimReceipt_claim_idx" ON "FleetClaimReceipt" ("organizationId", "claimId");

-- ---------------------------------------------------------------- hr: payroll inputs (additive, ADR-S009 §2)
-- One-off earnings queued for an employee's next payroll run by other modules (fleet driver claims now; 1.7 fines
-- add deductions). The payroll calculation adds pending inputs as allowance lines; when an input names a debit
-- account (the source's clearing liability) the payroll journal debits that account instead of salaries expense.

CREATE TABLE "HrPayrollInput" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT           NOT NULL REFERENCES "HrEmployee" (id),
    kind             TEXT           NOT NULL DEFAULT 'earning' CHECK (kind IN ('earning')),
    name             TEXT           NOT NULL,
    amount           NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    "earnedOn"       DATE           NOT NULL,
    "isTaxable"      BOOLEAN        NOT NULL DEFAULT false,
    "debitAccountId" TEXT,
    source           TEXT           NOT NULL,
    "sourceId"       TEXT           NOT NULL,
    -- pending (waiting for / calculated in a run: runId set), included (the run was approved), cancelled.
    status           TEXT           NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'included', 'cancelled')),
    "runId"          TEXT,
    "itemId"         TEXT,
    "includedAt"     TIMESTAMP(3),
    "cancelledAt"    TIMESTAMP(3),
    "cancelReason"   TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- A source document is queued once (until cancelled).
CREATE UNIQUE INDEX "HrPayrollInput_source_key" ON "HrPayrollInput" ("organizationId", source, "sourceId")
    WHERE status <> 'cancelled';
CREATE INDEX "HrPayrollInput_org_status_idx" ON "HrPayrollInput" ("organizationId", status, "employeeId");
CREATE INDEX "HrPayrollInput_run_idx" ON "HrPayrollInput" ("runId") WHERE "runId" IS NOT NULL;

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "FleetDriver" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDriver" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDriverQualification" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDriverQualification" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetAssignment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetAssignment" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetCheckout" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetCheckout" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetCheckoutPhoto" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetCheckoutPhoto" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetAllowanceRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetAllowanceRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDriverClaim" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDriverClaim" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDriverClaimLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDriverClaimLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetClaimReceipt" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetClaimReceipt" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "HrPayrollInput" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrPayrollInput" USING (("organizationId" = current_setting('app.org_id', true)));
