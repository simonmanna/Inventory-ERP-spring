-- V901 (placeholder number, renumbered at merge): fleet 1.5 — fuel & energy (ADR-S009 §3 / §6, Track C Phase 1).
--
-- Additive: new fleet tables only. Fuel bought into a site tank is a normal procurement GRN into the tank's inventory
-- location; issues to vehicles are inventory stock issues (StockService) referenced from the fuel log; the GL goes
-- through the posting engine (fleet postings) or the expenses module (cash / credit purchases at a station).
-- Cross-module references (vehicle class, inventory location, product, partner, hr employee, cost centre, account,
-- expense, journal entry, approval request, user) are plain text ids validated by the fleet services.

-- ---------------------------------------------------------------- settings (one row per organization)

CREATE TABLE "FleetFuelSettings" (
    id                     TEXT          NOT NULL PRIMARY KEY,
    "organizationId"       TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- A fill above the vehicle's tank size by more than this share is flagged as an overfill.
    "overfillTolerancePct" NUMERIC(6, 2) NOT NULL DEFAULT 2 CHECK ("overfillTolerancePct" BETWEEN 0 AND 100),
    -- A dip variance above either threshold needs approval before it is adjusted in stock.
    "dipApprovalLitres"    NUMERIC(14, 3) NOT NULL DEFAULT 50 CHECK ("dipApprovalLitres" >= 0),
    "dipApprovalPct"       NUMERIC(6, 2) NOT NULL DEFAULT 0.5 CHECK ("dipApprovalPct" BETWEEN 0 AND 100),
    -- The expenses category fleet uses for fuel bought at stations (created on first use).
    "expenseCategoryId"    TEXT,
    "updatedBy"            TEXT,
    "createdAt"            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetFuelSettings_org_key" ON "FleetFuelSettings" ("organizationId");

-- Expected consumption band per vehicle class: L/100 km for road units, L/hour for plant (either may be blank).
CREATE TABLE "FleetFuelBand" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "categoryId"     TEXT           NOT NULL REFERENCES "FleetVehicleCategory" (id) ON DELETE CASCADE,
    "minLPer100Km"   NUMERIC(10, 2) CHECK ("minLPer100Km" IS NULL OR "minLPer100Km" >= 0),
    "maxLPer100Km"   NUMERIC(10, 2) CHECK ("maxLPer100Km" IS NULL OR "maxLPer100Km" >= 0),
    "minLPerHour"    NUMERIC(10, 2) CHECK ("minLPerHour" IS NULL OR "minLPerHour" >= 0),
    "maxLPerHour"    NUMERIC(10, 2) CHECK ("maxLPerHour" IS NULL OR "maxLPerHour" >= 0),
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetFuelBand_km_chk" CHECK ("minLPer100Km" IS NULL OR "maxLPer100Km" IS NULL
        OR "maxLPer100Km" >= "minLPer100Km"),
    CONSTRAINT "FleetFuelBand_hour_chk" CHECK ("minLPerHour" IS NULL OR "maxLPerHour" IS NULL
        OR "maxLPerHour" >= "minLPerHour")
);
CREATE UNIQUE INDEX "FleetFuelBand_org_category_key" ON "FleetFuelBand" ("organizationId", "categoryId");

-- ---------------------------------------------------------------- fuel cards

-- The full card number is never stored: only the masked form, the last four digits and a keyed hash (uniqueness).
CREATE TABLE "FleetFuelCard" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "maskedNumber"      TEXT           NOT NULL,
    "last4"             TEXT           NOT NULL,
    "numberHash"        TEXT           NOT NULL,
    label               TEXT,
    -- Card provider / fuel company (a Partner): its statement settles the fuel-card payable.
    "providerPartnerId" TEXT,
    "vehicleId"         TEXT REFERENCES "FleetVehicle" (id),
    -- Driver the card is issued to (hr employee).
    "employeeId"        TEXT,
    status              TEXT           NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'blocked', 'expired', 'cancelled')),
    "expiresOn"         DATE,
    "limitAmount"       NUMERIC(18, 2) CHECK ("limitAmount" IS NULL OR "limitAmount" > 0),
    "limitPeriod"       TEXT           NOT NULL DEFAULT 'monthly' CHECK ("limitPeriod" IN ('daily', 'weekly', 'monthly')),
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3)
);
CREATE UNIQUE INDEX "FleetFuelCard_org_hash_key" ON "FleetFuelCard" ("organizationId", "numberHash")
    WHERE "deletedAt" IS NULL;
CREATE INDEX "FleetFuelCard_org_status_idx" ON "FleetFuelCard" ("organizationId", status);
CREATE INDEX "FleetFuelCard_vehicle_idx" ON "FleetFuelCard" ("organizationId", "vehicleId");

-- ---------------------------------------------------------------- site fuel tanks / bowsers

CREATE TABLE "FleetFuelTank" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code              TEXT           NOT NULL,
    name              TEXT           NOT NULL,
    kind              TEXT           NOT NULL DEFAULT 'tank' CHECK (kind IN ('tank', 'bowser')),
    -- The inventory location holding the tank's stock and the fuel product it holds (litres = product base unit).
    "locationId"      TEXT           NOT NULL,
    "productId"       TEXT           NOT NULL,
    "capacityL"       NUMERIC(14, 3) NOT NULL CHECK ("capacityL" > 0),
    "reorderLevelL"   NUMERIC(14, 3) CHECK ("reorderLevelL" IS NULL OR "reorderLevelL" >= 0),
    "branchId"        TEXT,
    -- A bowser mounted on a fleet vehicle.
    "bowserVehicleId" TEXT REFERENCES "FleetVehicle" (id),
    "isActive"        BOOLEAN        NOT NULL DEFAULT true,
    notes             TEXT,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "FleetFuelTank_org_code_key" ON "FleetFuelTank" ("organizationId", code) WHERE "deletedAt" IS NULL;
-- One tank per inventory location: the location's stock of the fuel product is the tank's book level.
CREATE UNIQUE INDEX "FleetFuelTank_org_location_key" ON "FleetFuelTank" ("organizationId", "locationId")
    WHERE "deletedAt" IS NULL;

-- ---------------------------------------------------------------- fuel logs

CREATE TABLE "FleetFuelLog" (
    id                       TEXT           NOT NULL PRIMARY KEY,
    "organizationId"         TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                     TEXT           NOT NULL,
    "vehicleId"              TEXT           NOT NULL REFERENCES "FleetVehicle" (id),
    "fuelledAt"              TIMESTAMP(3)   NOT NULL,
    -- station = bought outside (fuel station, supplier, fuel card); tank = issued from a site tank / bowser.
    source                   TEXT           NOT NULL CHECK (source IN ('station', 'tank')),
    "fuelType"               TEXT,
    litres                   NUMERIC(14, 3) NOT NULL CHECK (litres > 0),
    "unitPrice"              NUMERIC(18, 4) CHECK ("unitPrice" IS NULL OR "unitPrice" >= 0),
    amount                   NUMERIC(18, 2) CHECK (amount IS NULL OR amount >= 0),
    "fullTank"               BOOLEAN        NOT NULL DEFAULT true,
    odometer                 NUMERIC(14, 1) CHECK (odometer IS NULL OR odometer >= 0),
    hours                    NUMERIC(14, 1) CHECK (hours IS NULL OR hours >= 0),
    -- station purchases: supplier, station name, how it was paid (cash / bank account, fuel card, supplier credit).
    "supplierId"             TEXT,
    "stationName"            TEXT,
    "paymentMethod"          TEXT CHECK ("paymentMethod" IS NULL OR "paymentMethod" IN ('cash', 'fuel_card', 'credit')),
    "paymentAccountId"       TEXT,
    "fuelCardId"             TEXT REFERENCES "FleetFuelCard" (id),
    "tankId"                 TEXT REFERENCES "FleetFuelTank" (id),
    "receiptNumber"          TEXT,
    "receiptAttachmentId"    TEXT,
    -- Who fuelled (hr employee; the 1.2 driver profile links to the same employee).
    "employeeId"             TEXT,
    "costCenterId"           TEXT,
    notes                    TEXT,
    status                   TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'posted', 'reversed')),
    -- Efficiency between consecutive full-tank logs (set when posted; recomputed when the chain changes).
    "previousFullLogId"      TEXT,
    "segmentLitres"          NUMERIC(14, 3),
    "distanceKm"             NUMERIC(14, 1),
    "hoursRun"               NUMERIC(14, 1),
    "lPer100Km"              NUMERIC(12, 3),
    "lPerHour"               NUMERIC(12, 3),
    "anomalyCount"           INTEGER        NOT NULL DEFAULT 0,
    -- Meter readings recorded from the log (FleetMeterReading source = fuel), or why the meter rule refused it.
    "odometerReadingId"      TEXT,
    "hoursReadingId"         TEXT,
    "meterNote"              TEXT,
    -- Accounting / stock references.
    "expenseId"              TEXT,
    "journalEntryId"         TEXT,
    "stockLedgerCode"        TEXT,
    "stockUnitCost"          NUMERIC(20, 6),
    "stockValue"             NUMERIC(20, 6),
    "postedAt"               TIMESTAMP(3),
    "postedBy"               TEXT,
    "reversedAt"             TIMESTAMP(3),
    "reversedBy"             TEXT,
    "reversalReason"         TEXT,
    "reversalJournalEntryId" TEXT,
    "createdBy"              TEXT,
    "updatedBy"              TEXT,
    "createdAt"              TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"              TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"              TIMESTAMP(3),
    CONSTRAINT "FleetFuelLog_source_chk" CHECK ((source = 'tank') = ("tankId" IS NOT NULL)),
    CONSTRAINT "FleetFuelLog_payment_chk" CHECK (source = 'tank' OR "paymentMethod" IS NOT NULL)
);
CREATE UNIQUE INDEX "FleetFuelLog_org_code_key" ON "FleetFuelLog" ("organizationId", code);
CREATE INDEX "FleetFuelLog_vehicle_idx" ON "FleetFuelLog" ("organizationId", "vehicleId", "fuelledAt");
CREATE INDEX "FleetFuelLog_org_date_idx" ON "FleetFuelLog" ("organizationId", "fuelledAt");
CREATE INDEX "FleetFuelLog_org_status_idx" ON "FleetFuelLog" ("organizationId", status);
CREATE INDEX "FleetFuelLog_tank_idx" ON "FleetFuelLog" ("organizationId", "tankId") WHERE "tankId" IS NOT NULL;
CREATE INDEX "FleetFuelLog_card_idx" ON "FleetFuelLog" ("organizationId", "fuelCardId", "fuelledAt")
    WHERE "fuelCardId" IS NOT NULL;
CREATE UNIQUE INDEX "FleetFuelLog_expense_key" ON "FleetFuelLog" ("expenseId") WHERE "expenseId" IS NOT NULL;

-- ---------------------------------------------------------------- anomalies (review queue)

CREATE TABLE "FleetFuelAnomaly" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "fuelLogId"      TEXT           NOT NULL REFERENCES "FleetFuelLog" (id) ON DELETE CASCADE,
    "vehicleId"      TEXT           NOT NULL REFERENCES "FleetVehicle" (id),
    type             TEXT           NOT NULL CHECK (type IN ('overfill', 'meter_not_increasing', 'high_consumption',
                                                              'low_consumption', 'card_vehicle_mismatch',
                                                              'card_limit_exceeded')),
    detail           TEXT           NOT NULL,
    observed         NUMERIC(14, 3),
    "expectedMin"    NUMERIC(14, 3),
    "expectedMax"    NUMERIC(14, 3),
    -- open → acknowledged (reviewed with a note); resolved = no longer applies (log reversed or chain recomputed).
    status           TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged', 'resolved')),
    note             TEXT,
    "acknowledgedBy" TEXT,
    "acknowledgedAt" TIMESTAMP(3),
    "resolvedAt"     TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetFuelAnomaly_log_type_key" ON "FleetFuelAnomaly" ("fuelLogId", type);
CREATE INDEX "FleetFuelAnomaly_org_status_idx" ON "FleetFuelAnomaly" ("organizationId", status, "createdAt");
CREATE INDEX "FleetFuelAnomaly_vehicle_idx" ON "FleetFuelAnomaly" ("organizationId", "vehicleId");

-- ---------------------------------------------------------------- tank dips and reconciliation

CREATE TABLE "FleetTankDip" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "tankId"              TEXT           NOT NULL REFERENCES "FleetFuelTank" (id),
    "dippedAt"            TIMESTAMP(3)   NOT NULL,
    "dipLitres"           NUMERIC(14, 3) NOT NULL CHECK ("dipLitres" >= 0),
    -- Book stock of the tank location at the dip time and the variance dip − book.
    "bookLitres"          NUMERIC(14, 3) NOT NULL,
    "varianceLitres"      NUMERIC(14, 3) NOT NULL,
    notes                 TEXT,
    "recordedBy"          TEXT,
    -- open → accepted (no variance) | adjusted | pending_approval → adjusted | rejected | failed.
    status                TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'accepted', 'pending_approval', 'adjusted', 'rejected', 'failed')),
    reason                TEXT,
    "approvalRequestId"   TEXT,
    "adjustmentLedgerCode" TEXT,
    "adjustedLitres"      NUMERIC(14, 3),
    "adjustmentValue"     NUMERIC(20, 6),
    "failureReason"       TEXT,
    "reconciledBy"        TEXT,
    "reconciledAt"        TIMESTAMP(3),
    "decidedBy"           TEXT,
    "decidedAt"           TIMESTAMP(3),
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetTankDip_tank_idx" ON "FleetTankDip" ("organizationId", "tankId", "dippedAt");
CREATE INDEX "FleetTankDip_approval_idx" ON "FleetTankDip" ("approvalRequestId") WHERE "approvalRequestId" IS NOT NULL;
-- One reconciliation waiting for approval per tank: decisions apply in order against a known book level.
CREATE UNIQUE INDEX "FleetTankDip_pending_key" ON "FleetTankDip" ("tankId") WHERE status = 'pending_approval';

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "FleetFuelSettings" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFuelSettings" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFuelBand" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFuelBand" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFuelCard" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFuelCard" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFuelTank" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFuelTank" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFuelLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFuelLog" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFuelAnomaly" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFuelAnomaly" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetTankDip" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetTankDip" USING (("organizationId" = current_setting('app.org_id', true)));
