-- V15: fleet & logistics module foundation + 1.1 vehicle & equipment registry (ADR-S009, Track C Phase 1).
--
-- Additive extension beyond the NestJS reference; no existing table is changed. Cross-module references (branch,
-- inventory location, cost centre, partner, fixed asset, file, approval request, user) are plain text ids like the
-- rest of the schema (aggregate-by-id): fleet validates them in its services and never writes those tables.

-- ---------------------------------------------------------------- vehicle / equipment classes (configurable)

CREATE TABLE "FleetVehicleCategory" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code               TEXT         NOT NULL,
    name               TEXT         NOT NULL,
    -- road_vehicle / trailer need a registration plate; mobile_plant / fixed_plant usually run on engine hours.
    kind               TEXT         NOT NULL DEFAULT 'road_vehicle'
        CHECK (kind IN ('road_vehicle', 'trailer', 'mobile_plant', 'fixed_plant', 'other')),
    "defaultMeterType" TEXT         NOT NULL DEFAULT 'odometer'
        CHECK ("defaultMeterType" IN ('odometer', 'hours', 'both', 'none')),
    description        TEXT,
    "sortOrder"        INTEGER      NOT NULL DEFAULT 0,
    "isSystem"         BOOLEAN      NOT NULL DEFAULT false,
    "isActive"         BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetVehicleCategory_org_code_key" ON "FleetVehicleCategory" ("organizationId", code);

-- ---------------------------------------------------------------- vehicle & equipment master

CREATE TABLE "FleetVehicle" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT           NOT NULL,
    name                TEXT,
    "registrationPlate" TEXT,
    vin                 TEXT,
    "engineNumber"      TEXT,
    make                TEXT,
    model               TEXT,
    year                INTEGER CHECK (year IS NULL OR year BETWEEN 1950 AND 2100),
    color               TEXT,
    "categoryId"        TEXT           NOT NULL REFERENCES "FleetVehicleCategory" (id),
    ownership           TEXT           NOT NULL DEFAULT 'owned'
        CHECK (ownership IN ('owned', 'leased', 'hired', 'third_party')),
    -- Lessor, hire company or third-party transporter (a Partner) when not owned.
    "ownerPartnerId"    TEXT,
    "payloadCapacityT"  NUMERIC(12, 3) CHECK ("payloadCapacityT" IS NULL OR "payloadCapacityT" >= 0),
    "bodyVolumeM3"      NUMERIC(12, 3) CHECK ("bodyVolumeM3" IS NULL OR "bodyVolumeM3" >= 0),
    "axleConfig"        TEXT,
    "fuelType"          TEXT           NOT NULL DEFAULT 'diesel'
        CHECK ("fuelType" IN ('diesel', 'petrol', 'electric', 'hybrid', 'lpg', 'cng', 'none')),
    "tankCapacityL"     NUMERIC(12, 2) CHECK ("tankCapacityL" IS NULL OR "tankCapacityL" > 0),
    "meterType"         TEXT           NOT NULL DEFAULT 'odometer'
        CHECK ("meterType" IN ('odometer', 'hours', 'both', 'none')),
    -- Current raw meter values (as shown on the dashboard) and the offset added by meter replacements, so the
    -- lifetime value (current + offset) never goes down.
    "currentOdometer"   NUMERIC(14, 1),
    "odometerOffset"    NUMERIC(14, 1) NOT NULL DEFAULT 0,
    "odometerAt"        TIMESTAMP(3),
    "currentHours"      NUMERIC(14, 1),
    "hoursOffset"       NUMERIC(14, 1) NOT NULL DEFAULT 0,
    "hoursAt"           TIMESTAMP(3),
    "branchId"          TEXT,
    "homeLocationId"    TEXT,
    "costCenterId"      TEXT,
    status              TEXT           NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'in_workshop', 'breakdown', 'idle', 'disposed')),
    "statusReason"      TEXT,
    "statusChangedAt"   TIMESTAMP(3),
    -- Optional link to the fixed-asset register (depreciation stays in fixedasset; fleet only reads it).
    "assetId"           TEXT,
    "acquiredOn"        DATE,
    "disposedAt"        TIMESTAMP(3),
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3)
);
CREATE UNIQUE INDEX "FleetVehicle_org_code_key" ON "FleetVehicle" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "FleetVehicle_org_plate_key" ON "FleetVehicle" ("organizationId", "registrationPlate")
    WHERE "registrationPlate" IS NOT NULL AND "deletedAt" IS NULL;
CREATE UNIQUE INDEX "FleetVehicle_org_vin_key" ON "FleetVehicle" ("organizationId", vin)
    WHERE vin IS NOT NULL AND "deletedAt" IS NULL;
CREATE UNIQUE INDEX "FleetVehicle_org_asset_key" ON "FleetVehicle" ("organizationId", "assetId")
    WHERE "assetId" IS NOT NULL AND "deletedAt" IS NULL;
CREATE INDEX "FleetVehicle_org_status_idx" ON "FleetVehicle" ("organizationId", status);
CREATE INDEX "FleetVehicle_org_category_idx" ON "FleetVehicle" ("organizationId", "categoryId");
CREATE INDEX "FleetVehicle_org_branch_idx" ON "FleetVehicle" ("organizationId", "branchId");
CREATE INDEX "FleetVehicle_org_ownership_idx" ON "FleetVehicle" ("organizationId", ownership);

CREATE TABLE "FleetVehicleStatusHistory" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"      TEXT           NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "fromStatus"     TEXT,
    "toStatus"       TEXT           NOT NULL,
    reason           TEXT,
    -- manual (registry screen), registration, or the sub-module that moved it (maintenance, inspection, trip...).
    source           TEXT           NOT NULL DEFAULT 'manual',
    "sourceId"       TEXT,
    odometer         NUMERIC(14, 1),
    hours            NUMERIC(14, 1),
    "changedBy"      TEXT,
    "changedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetVehicleStatusHistory_vehicle_idx" ON "FleetVehicleStatusHistory" ("organizationId", "vehicleId",
    "changedAt");

-- ---------------------------------------------------------------- meter readings (odometer km / engine hours)

CREATE TABLE "FleetMeterReading" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"      TEXT           NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "meterType"      TEXT           NOT NULL CHECK ("meterType" IN ('odometer', 'hours')),
    "readingAt"      TIMESTAMP(3)   NOT NULL,
    -- Raw value on the meter and the lifetime value (raw + replacement offset) the monotonic rule is checked on.
    value            NUMERIC(14, 1) NOT NULL CHECK (value >= 0),
    "lifetimeValue"  NUMERIC(14, 1) NOT NULL CHECK ("lifetimeValue" >= 0),
    source           TEXT           NOT NULL DEFAULT 'manual'
        CHECK (source IN ('opening', 'manual', 'correction', 'replacement', 'check_out', 'check_in', 'trip', 'fuel',
                          'work_order', 'inspection', 'telematics', 'import')),
    "sourceId"       TEXT,
    -- valid, or superseded by an applied correction (kept for the audit trail, ignored by the rules).
    status           TEXT           NOT NULL DEFAULT 'valid' CHECK (status IN ('valid', 'superseded')),
    "correctionId"   TEXT,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetMeterReading_vehicle_idx" ON "FleetMeterReading" ("organizationId", "vehicleId", "meterType",
    "readingAt");
-- An automated reading (trip, fuel log, telematics...) is recorded once per source document.
CREATE UNIQUE INDEX "FleetMeterReading_source_key" ON "FleetMeterReading" ("vehicleId", "meterType", source,
    "sourceId") WHERE "sourceId" IS NOT NULL AND status = 'valid';

CREATE TABLE "FleetMeterCorrection" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"         TEXT           NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "meterType"         TEXT           NOT NULL CHECK ("meterType" IN ('odometer', 'hours')),
    -- adjust = fix a wrongly recorded reading; replace = meter replaced / rolled over (new baseline value).
    kind                TEXT           NOT NULL CHECK (kind IN ('adjust', 'replace')),
    "readingId"         TEXT REFERENCES "FleetMeterReading" (id),
    "previousValue"     NUMERIC(14, 1),
    "correctedValue"    NUMERIC(14, 1) NOT NULL CHECK ("correctedValue" >= 0),
    "readingAt"         TIMESTAMP(3),
    reason              TEXT           NOT NULL,
    status              TEXT           NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'applied', 'rejected', 'failed')),
    "approvalRequestId" TEXT,
    "failureReason"     TEXT,
    "requestedBy"       TEXT,
    "requestedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "decidedBy"         TEXT,
    "decidedAt"         TIMESTAMP(3),
    "appliedReadingId"  TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetMeterCorrection_reading_chk" CHECK ((kind = 'adjust') = ("readingId" IS NOT NULL))
);
-- One open correction per meter: corrections are applied in order against a known state.
CREATE UNIQUE INDEX "FleetMeterCorrection_pending_key" ON "FleetMeterCorrection" ("vehicleId", "meterType")
    WHERE status = 'pending';
CREATE INDEX "FleetMeterCorrection_vehicle_idx" ON "FleetMeterCorrection" ("organizationId", "vehicleId",
    "requestedAt");
CREATE INDEX "FleetMeterCorrection_approval_idx" ON "FleetMeterCorrection" ("approvalRequestId")
    WHERE "approvalRequestId" IS NOT NULL;

-- ---------------------------------------------------------------- compliance documents

CREATE TABLE "FleetComplianceDocument" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"        TEXT         NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "docType"          TEXT         NOT NULL
        CHECK ("docType" IN ('registration', 'insurance', 'road_licence', 'inspection', 'operator_permit', 'other')),
    "documentNumber"   TEXT,
    issuer             TEXT,
    "issuedOn"         DATE,
    "expiresOn"        DATE,
    "reminderLeadDays" INTEGER      NOT NULL DEFAULT 30 CHECK ("reminderLeadDays" BETWEEN 0 AND 365),
    -- active, or renewed (superseded by the document in "renewedById").
    status             TEXT         NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'renewed')),
    "renewedById"      TEXT,
    notes              TEXT,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3),
    CONSTRAINT "FleetComplianceDocument_dates_chk" CHECK ("issuedOn" IS NULL OR "expiresOn" IS NULL
        OR "expiresOn" >= "issuedOn")
);
CREATE INDEX "FleetComplianceDocument_vehicle_idx" ON "FleetComplianceDocument" ("organizationId", "vehicleId");
-- The expiry query (alerts, 1.9 reminder job) scans active documents by expiry date.
CREATE INDEX "FleetComplianceDocument_expiry_idx" ON "FleetComplianceDocument" ("organizationId", "expiresOn")
    WHERE status = 'active' AND "deletedAt" IS NULL;

-- ---------------------------------------------------------------- photos & documents (kernel File store)

CREATE TABLE "FleetVehicleAttachment" (
    id                     TEXT         NOT NULL PRIMARY KEY,
    "organizationId"       TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"            TEXT         NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "fileId"               TEXT         NOT NULL,
    kind                   TEXT         NOT NULL DEFAULT 'document' CHECK (kind IN ('photo', 'document')),
    "complianceDocumentId" TEXT REFERENCES "FleetComplianceDocument" (id) ON DELETE SET NULL,
    title                  TEXT,
    "createdBy"            TEXT,
    "createdAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetVehicleAttachment_file_key" ON "FleetVehicleAttachment" ("fileId");
CREATE INDEX "FleetVehicleAttachment_vehicle_idx" ON "FleetVehicleAttachment" ("organizationId", "vehicleId");
CREATE INDEX "FleetVehicleAttachment_compliance_idx" ON "FleetVehicleAttachment" ("complianceDocumentId")
    WHERE "complianceDocumentId" IS NOT NULL;

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "FleetVehicleCategory" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetVehicleCategory" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetVehicle" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetVehicle" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetVehicleStatusHistory" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetVehicleStatusHistory" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetMeterReading" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetMeterReading" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetMeterCorrection" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetMeterCorrection" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetComplianceDocument" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetComplianceDocument" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetVehicleAttachment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetVehicleAttachment" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions (all of Phase 1, ADR-S009 §7)

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY[
    'fleet:report', 'fleet:settings',
    'fleet_vehicle:read', 'fleet_vehicle:create', 'fleet_vehicle:update', 'fleet_vehicle:delete',
    'fleet_vehicle:status', 'fleet_vehicle:meter', 'fleet_vehicle:approve_meter', 'fleet_vehicle:compliance',
    'fleet_driver:read', 'fleet_driver:create', 'fleet_driver:update', 'fleet_driver:delete', 'fleet_driver:assign',
    'fleet_driver:checkout', 'fleet_driver:allowance', 'fleet_driver:approve_allowance',
    'fleet_dispatch:read', 'fleet_dispatch:create', 'fleet_dispatch:update', 'fleet_dispatch:plan',
    'fleet_dispatch:cancel', 'fleet_dispatch:approve_discrepancy',
    'fleet_trip:read', 'fleet_trip:create', 'fleet_trip:update', 'fleet_trip:dispatch', 'fleet_trip:deliver',
    'fleet_trip:close', 'fleet_trip:cancel',
    'fleet_fuel:read', 'fleet_fuel:create', 'fleet_fuel:update', 'fleet_fuel:post', 'fleet_fuel:tank',
    'fleet_maintenance:read', 'fleet_maintenance:create', 'fleet_maintenance:update', 'fleet_maintenance:schedule',
    'fleet_maintenance:complete', 'fleet_maintenance:inspect', 'fleet_maintenance:tyre',
    'fleet_incident:read', 'fleet_incident:create', 'fleet_incident:update', 'fleet_incident:charge',
    'weighbridge:read', 'weighbridge:weigh', 'weighbridge:manual_entry', 'weighbridge:approve_exception',
    'weighbridge:void', 'weighbridge:configure']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY[
        'fleet:report', 'fleet:settings',
        'fleet_vehicle:read', 'fleet_vehicle:create', 'fleet_vehicle:update', 'fleet_vehicle:delete',
        'fleet_vehicle:status', 'fleet_vehicle:meter', 'fleet_vehicle:approve_meter', 'fleet_vehicle:compliance',
        'fleet_driver:read', 'fleet_driver:create', 'fleet_driver:update', 'fleet_driver:delete', 'fleet_driver:assign',
        'fleet_driver:checkout', 'fleet_driver:allowance', 'fleet_driver:approve_allowance',
        'fleet_dispatch:read', 'fleet_dispatch:create', 'fleet_dispatch:update', 'fleet_dispatch:plan',
        'fleet_dispatch:cancel', 'fleet_dispatch:approve_discrepancy',
        'fleet_trip:read', 'fleet_trip:create', 'fleet_trip:update', 'fleet_trip:dispatch', 'fleet_trip:deliver',
        'fleet_trip:close', 'fleet_trip:cancel',
        'fleet_fuel:read', 'fleet_fuel:create', 'fleet_fuel:update', 'fleet_fuel:post', 'fleet_fuel:tank',
        'fleet_maintenance:read', 'fleet_maintenance:create', 'fleet_maintenance:update', 'fleet_maintenance:schedule',
        'fleet_maintenance:complete', 'fleet_maintenance:inspect', 'fleet_maintenance:tyre',
        'fleet_incident:read', 'fleet_incident:create', 'fleet_incident:update', 'fleet_incident:charge',
        'weighbridge:read', 'weighbridge:weigh', 'weighbridge:manual_entry', 'weighbridge:approve_exception',
        'weighbridge:void', 'weighbridge:configure'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
