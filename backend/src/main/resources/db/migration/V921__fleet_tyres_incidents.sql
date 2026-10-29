-- V921 (placeholder number, renumbered at merge): fleet 1.7 — tyres, incidents & insurance claims, traffic fines
-- (ADR-S009 §3 / §6, Track C Phase 1). Additive: new fleet tables, the expenses source link fleet fines use, and the
-- meter-reading source "tyre" (fitment / rotation / removal readings). Cross-module references (partner, product,
-- inventory location, hr employee, expense, journal entry, 1.6 work order) are plain text ids validated in the services.

-- ---------------------------------------------------------------- tyres

CREATE TABLE "FleetTyre" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "serialNumber"        TEXT           NOT NULL,
    brand                 TEXT,
    -- tread pattern / model line
    model                 TEXT,
    -- e.g. 315/80R22.5
    size                  TEXT,
    "tyreType"            TEXT           NOT NULL DEFAULT 'all_position'
        CHECK ("tyreType" IN ('steer', 'drive', 'trailer', 'all_position', 'offroad', 'other')),
    "purchaseCost"        NUMERIC(18, 2) CHECK ("purchaseCost" IS NULL OR "purchaseCost" >= 0),
    -- retread costs added over the tyre's life (cost per km = purchase + extra over km run)
    "extraCost"           NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK ("extraCost" >= 0),
    "supplierPartnerId"   TEXT,
    "purchasedOn"         DATE,
    "invoiceReference"    TEXT,
    -- Optional stocked inventory product: the first fitment issues one unit from "stockLocationId" (once).
    "productId"           TEXT,
    "stockLocationId"     TEXT,
    "stockIssuedAt"       TIMESTAMP(3),
    "stockLedgerCode"     TEXT,
    "stockIssueValue"     NUMERIC(18, 2),
    "issueJournalEntryId" TEXT,
    status                TEXT           NOT NULL DEFAULT 'in_stock'
        CHECK (status IN ('in_stock', 'fitted', 'removed', 'retreaded', 'scrapped')),
    -- Current fitment (only while fitted) and the lifetime meters at the start of the current stint.
    "vehicleId"           TEXT REFERENCES "FleetVehicle" (id),
    position              TEXT,
    "fittedAt"            TIMESTAMP(3),
    "fitOdometer"         NUMERIC(14, 1),
    "fitHours"            NUMERIC(14, 1),
    -- Distance / hours of the closed stints (the running stint is added from the vehicle's meters on read).
    "kmRun"               NUMERIC(14, 1) NOT NULL DEFAULT 0 CHECK ("kmRun" >= 0),
    "hoursRun"            NUMERIC(14, 1) NOT NULL DEFAULT 0 CHECK ("hoursRun" >= 0),
    -- km run when the current tread started (0 when new, km run at the last retread): wear is measured from here.
    "treadKmBase"         NUMERIC(14, 1) NOT NULL DEFAULT 0,
    "newTreadDepthMm"     NUMERIC(5, 1) CHECK ("newTreadDepthMm" IS NULL OR "newTreadDepthMm" > 0),
    "treadDepthMm"        NUMERIC(5, 1) CHECK ("treadDepthMm" IS NULL OR "treadDepthMm" >= 0),
    "treadMeasuredAt"     TIMESTAMP(3),
    "minTreadDepthMm"     NUMERIC(5, 1)  NOT NULL DEFAULT 3 CHECK ("minTreadDepthMm" >= 0),
    "retreadCount"        INTEGER        NOT NULL DEFAULT 0 CHECK ("retreadCount" >= 0),
    "scrappedAt"          TIMESTAMP(3),
    "scrapReason"         TEXT,
    notes                 TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3),
    CONSTRAINT "FleetTyre_fitted_chk" CHECK ((status = 'fitted') = ("vehicleId" IS NOT NULL AND position IS NOT NULL))
);
CREATE UNIQUE INDEX "FleetTyre_org_serial_key" ON "FleetTyre" ("organizationId", "serialNumber")
    WHERE "deletedAt" IS NULL;
-- One tyre per wheel position.
CREATE UNIQUE INDEX "FleetTyre_position_key" ON "FleetTyre" ("vehicleId", position) WHERE status = 'fitted';
CREATE INDEX "FleetTyre_org_status_idx" ON "FleetTyre" ("organizationId", status);
CREATE INDEX "FleetTyre_org_vehicle_idx" ON "FleetTyre" ("organizationId", "vehicleId");

CREATE TABLE "FleetTyreEvent" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "tyreId"           TEXT           NOT NULL REFERENCES "FleetTyre" (id) ON DELETE CASCADE,
    "eventType"        TEXT           NOT NULL
        CHECK ("eventType" IN ('registered', 'fitted', 'rotated', 'removed', 'retreaded', 'scrapped')),
    "vehicleId"        TEXT,
    "fromPosition"     TEXT,
    "toPosition"       TEXT,
    "eventAt"          TIMESTAMP(3)   NOT NULL,
    -- Raw meter as read at the event and the lifetime value the stint arithmetic uses.
    odometer           NUMERIC(14, 1),
    "odometerLifetime" NUMERIC(14, 1),
    hours              NUMERIC(14, 1),
    "hoursLifetime"    NUMERIC(14, 1),
    -- Distance / hours of the stint this event closed (rotation, removal).
    "kmRun"            NUMERIC(14, 1),
    "hoursRun"         NUMERIC(14, 1),
    "treadDepthMm"     NUMERIC(5, 1),
    cost               NUMERIC(18, 2),
    "partnerId"        TEXT,
    reason             TEXT,
    "meterReadingId"   TEXT,
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetTyreEvent_tyre_idx" ON "FleetTyreEvent" ("organizationId", "tyreId", "eventAt");
CREATE INDEX "FleetTyreEvent_vehicle_idx" ON "FleetTyreEvent" ("organizationId", "vehicleId", "eventAt");

CREATE TABLE "FleetTyreReading" (
    id                 TEXT          NOT NULL PRIMARY KEY,
    "organizationId"   TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "tyreId"           TEXT          NOT NULL REFERENCES "FleetTyre" (id) ON DELETE CASCADE,
    "vehicleId"        TEXT,
    position           TEXT,
    "readingAt"        TIMESTAMP(3)  NOT NULL,
    "treadDepthMm"     NUMERIC(5, 1) NOT NULL CHECK ("treadDepthMm" >= 0),
    "pressureKpa"      NUMERIC(6, 1) CHECK ("pressureKpa" IS NULL OR "pressureKpa" >= 0),
    "odometerLifetime" NUMERIC(14, 1),
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetTyreReading_tyre_idx" ON "FleetTyreReading" ("organizationId", "tyreId", "readingAt");

-- Tyre fitment / rotation / removal readings are recorded on the vehicle's meters with source 'tyre'.
ALTER TABLE "FleetMeterReading" DROP CONSTRAINT IF EXISTS "FleetMeterReading_source_check";
ALTER TABLE "FleetMeterReading" ADD CONSTRAINT "FleetMeterReading_source_check"
    CHECK (source IN ('opening', 'manual', 'correction', 'replacement', 'check_out', 'check_in', 'trip', 'fuel',
                      'work_order', 'inspection', 'telematics', 'import', 'tyre'));

-- ---------------------------------------------------------------- incidents & insurance claims

CREATE TABLE "FleetIncident" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "incidentNumber"     TEXT           NOT NULL,
    "vehicleId"          TEXT           NOT NULL REFERENCES "FleetVehicle" (id),
    -- Driver / operator at the time (hr employee id).
    "driverEmployeeId"   TEXT,
    "occurredAt"         TIMESTAMP(3)   NOT NULL,
    location             TEXT,
    latitude             NUMERIC(9, 6) CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    longitude            NUMERIC(9, 6) CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    "incidentType"       TEXT           NOT NULL DEFAULT 'collision'
        CHECK ("incidentType" IN ('collision', 'rollover', 'theft', 'vandalism', 'fire', 'injury', 'property_damage',
                                  'load_spill', 'other')),
    severity             TEXT           NOT NULL DEFAULT 'minor' CHECK (severity IN ('minor', 'major', 'critical')),
    description          TEXT           NOT NULL,
    "damageDescription"  TEXT,
    "estimatedCost"      NUMERIC(18, 2) CHECK ("estimatedCost" IS NULL OR "estimatedCost" >= 0),
    "atFault"            TEXT           NOT NULL DEFAULT 'unknown'
        CHECK ("atFault" IN ('own', 'third_party', 'shared', 'unknown')),
    injuries             TEXT,
    -- [{name, phone, vehiclePlate, insurer, policyNumber, notes}]
    "thirdParties"       JSONB          NOT NULL DEFAULT '[]'::jsonb,
    "policeReportNumber" TEXT,
    "policeStation"      TEXT,
    status               TEXT           NOT NULL DEFAULT 'reported'
        CHECK (status IN ('reported', 'investigating', 'repairing', 'closed', 'cancelled')),
    "statusReason"       TEXT,
    -- Repair link: 1.6 fleet work order id (no FK — built in parallel) and / or a free reference + cost.
    "workOrderId"        TEXT,
    "repairReference"    TEXT,
    "repairCost"         NUMERIC(18, 2) CHECK ("repairCost" IS NULL OR "repairCost" >= 0),
    "closedAt"           TIMESTAMP(3),
    "closedBy"           TEXT,
    notes                TEXT,
    "createdBy"          TEXT,
    "updatedBy"          TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"          TIMESTAMP(3)
);
CREATE UNIQUE INDEX "FleetIncident_org_number_key" ON "FleetIncident" ("organizationId", "incidentNumber");
CREATE INDEX "FleetIncident_org_vehicle_idx" ON "FleetIncident" ("organizationId", "vehicleId", "occurredAt");
CREATE INDEX "FleetIncident_org_status_idx" ON "FleetIncident" ("organizationId", status);

-- Photos / documents of an incident: stored as vehicle attachments (kernel file store) and linked here.
CREATE TABLE "FleetIncidentAttachment" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "incidentId"     TEXT         NOT NULL REFERENCES "FleetIncident" (id) ON DELETE CASCADE,
    "attachmentId"   TEXT         NOT NULL REFERENCES "FleetVehicleAttachment" (id) ON DELETE CASCADE,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetIncidentAttachment_attachment_key" ON "FleetIncidentAttachment" ("attachmentId");
CREATE INDEX "FleetIncidentAttachment_incident_idx" ON "FleetIncidentAttachment" ("organizationId", "incidentId");

CREATE TABLE "FleetInsuranceClaim" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "incidentId"       TEXT           NOT NULL REFERENCES "FleetIncident" (id) ON DELETE CASCADE,
    "claimNumber"      TEXT,
    "insurerPartnerId" TEXT,
    "insurerName"      TEXT,
    "policyNumber"     TEXT,
    "submittedOn"      DATE,
    "amountClaimed"    NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK ("amountClaimed" >= 0),
    "amountApproved"   NUMERIC(18, 2) CHECK ("amountApproved" IS NULL OR "amountApproved" >= 0),
    "amountReceived"   NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK ("amountReceived" >= 0),
    "excessAmount"     NUMERIC(18, 2) CHECK ("excessAmount" IS NULL OR "excessAmount" >= 0),
    status             TEXT           NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'submitted', 'approved', 'rejected', 'settled', 'withdrawn')),
    "statusReason"     TEXT,
    "decidedOn"        DATE,
    "settledOn"        DATE,
    notes              TEXT,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetInsuranceClaim_incident_idx" ON "FleetInsuranceClaim" ("organizationId", "incidentId");
CREATE INDEX "FleetInsuranceClaim_org_status_idx" ON "FleetInsuranceClaim" ("organizationId", status);

-- ---------------------------------------------------------------- traffic fines

CREATE TABLE "FleetFine" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "fineNumber"       TEXT           NOT NULL,
    "vehicleId"        TEXT           NOT NULL REFERENCES "FleetVehicle" (id),
    "driverEmployeeId" TEXT,
    "incidentId"       TEXT REFERENCES "FleetIncident" (id) ON DELETE SET NULL,
    "offenceAt"        TIMESTAMP(3)   NOT NULL,
    location           TEXT,
    offence            TEXT           NOT NULL,
    "offenceCode"      TEXT,
    authority          TEXT,
    -- Notice / ticket number of the issuing authority.
    "noticeNumber"     TEXT,
    amount             NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    "dueDate"          DATE,
    status             TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'disputed', 'charged', 'settled', 'cancelled')),
    "statusReason"     TEXT,
    -- company = expense (expenses module, vehicle cost centre); driver = payroll deduction request.
    "chargedTo"        TEXT CHECK ("chargedTo" IS NULL OR "chargedTo" IN ('company', 'driver')),
    "chargedAt"        TIMESTAMP(3),
    "chargedBy"        TEXT,
    "chargeCount"      INTEGER        NOT NULL DEFAULT 0,
    "expenseId"        TEXT,
    "deductionId"      TEXT,
    "settledAt"        TIMESTAMP(3),
    notes              TEXT,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3),
    CONSTRAINT "FleetFine_charge_chk" CHECK (status NOT IN ('charged', 'settled') OR "chargedTo" IS NOT NULL)
);
CREATE UNIQUE INDEX "FleetFine_org_number_key" ON "FleetFine" ("organizationId", "fineNumber");
CREATE UNIQUE INDEX "FleetFine_org_notice_key" ON "FleetFine" ("organizationId", "noticeNumber")
    WHERE "noticeNumber" IS NOT NULL AND "deletedAt" IS NULL;
CREATE INDEX "FleetFine_org_vehicle_idx" ON "FleetFine" ("organizationId", "vehicleId", "offenceAt");
CREATE INDEX "FleetFine_org_status_idx" ON "FleetFine" ("organizationId", status);
CREATE INDEX "FleetFine_org_driver_idx" ON "FleetFine" ("organizationId", "driverEmployeeId");
CREATE INDEX "FleetFine_expense_idx" ON "FleetFine" ("organizationId", "expenseId") WHERE "expenseId" IS NOT NULL;

-- Salary-deduction request of a fine charged to the driver. hr is not modified: fleet publishes
-- fleet.fine.deduction_requested and payroll (wired at merge) exports / settles the request.
CREATE TABLE "FleetFineDeduction" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "fineId"           TEXT           NOT NULL REFERENCES "FleetFine" (id) ON DELETE CASCADE,
    "employeeId"       TEXT           NOT NULL,
    amount             NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    status             TEXT           NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'exported', 'settled', 'cancelled')),
    "requestedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "requestedBy"      TEXT,
    "exportedAt"       TIMESTAMP(3),
    "exportReference"  TEXT,
    "settledAt"        TIMESTAMP(3),
    "settledReference" TEXT,
    "cancelledAt"      TIMESTAMP(3),
    "cancelReason"     TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetFineDeduction_open_key" ON "FleetFineDeduction" ("fineId") WHERE status <> 'cancelled';
CREATE INDEX "FleetFineDeduction_org_status_idx" ON "FleetFineDeduction" ("organizationId", status);
CREATE INDEX "FleetFineDeduction_org_employee_idx" ON "FleetFineDeduction" ("organizationId", "employeeId");

-- ---------------------------------------------------------------- expenses: source link (additive)

-- An expense raised by another module's document (fleet fine charged to the company) with the debit account, cost
-- centre and dimensions its payment entries carry. Read by ExpensesService.recordPayment; absent for every other
-- expense, which therefore posts exactly as before.
CREATE TABLE "ExpenseSourceLink" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "expenseId"      TEXT         NOT NULL REFERENCES "Expense" (id) ON DELETE CASCADE,
    "sourceType"     TEXT         NOT NULL,
    "sourceId"       TEXT         NOT NULL,
    "debitAccountId" TEXT,
    "costCenterId"   TEXT,
    dimensions       JSONB,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ExpenseSourceLink_expense_key" ON "ExpenseSourceLink" ("expenseId");
CREATE INDEX "ExpenseSourceLink_source_idx" ON "ExpenseSourceLink" ("organizationId", "sourceType", "sourceId");

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "FleetTyre" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetTyre" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetTyreEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetTyreEvent" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetTyreReading" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetTyreReading" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetIncident" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetIncident" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetIncidentAttachment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetIncidentAttachment" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetInsuranceClaim" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetInsuranceClaim" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFineDeduction" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFineDeduction" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ExpenseSourceLink" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ExpenseSourceLink" USING (("organizationId" = current_setting('app.org_id', true)));
