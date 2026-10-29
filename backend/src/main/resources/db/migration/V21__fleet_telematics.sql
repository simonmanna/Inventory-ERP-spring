-- V922 (placeholder number, renumbered at merge): fleet 1.8 — telematics SPI storage (ADR-S009 §3 / §7): provider
-- devices mapped to vehicles, GPS positions, ignition events and alerts. Odometer / hour readings synced from the
-- provider go to FleetMeterReading (source 'telematics', idempotent per snapshot) through FleetMeterService.
--
-- Volume: positions are append-only and the largest fleet table (one row per unit every few minutes). Queries always
-- go by (organization, vehicle, time) and are paged; the sync job purges positions older than
-- app.fleet.telematics.retention-days (default 365) in batches. Monthly partitioning can be added later without an API
-- change (the primary key would become (id, "recordedAt")).

CREATE TABLE "FleetTelematicsDevice" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"         TEXT REFERENCES "FleetVehicle" (id),
    -- Provider type (TelematicsProvider.type(): mock, vendor adapters later) and the provider's unit id.
    provider            TEXT          NOT NULL,
    "externalUnitId"    TEXT          NOT NULL,
    name                TEXT,
    imei                TEXT,
    "simNumber"         TEXT,
    "isActive"          BOOLEAN       NOT NULL DEFAULT true,
    "syncOdometer"      BOOLEAN       NOT NULL DEFAULT true,
    "syncHours"         BOOLEAN       NOT NULL DEFAULT true,
    -- Sync cursors and the last outcome.
    "positionsSyncedTo" TIMESTAMP(3),
    "eventsSyncedTo"    TIMESTAMP(3),
    "lastSyncAt"        TIMESTAMP(3),
    "lastSyncStatus"    TEXT CHECK ("lastSyncStatus" IS NULL OR "lastSyncStatus" IN ('ok', 'partial', 'error')),
    "lastError"         TEXT,
    -- Last known position / meters (cached for the live list).
    "lastPositionAt"    TIMESTAMP(3),
    "lastLatitude"      NUMERIC(9, 6),
    "lastLongitude"     NUMERIC(9, 6),
    "lastSpeedKmh"      NUMERIC(6, 1),
    "lastIgnition"      BOOLEAN,
    "lastOdometerKm"    NUMERIC(14, 1),
    "lastEngineHours"   NUMERIC(14, 1),
    "lastMeterAt"       TIMESTAMP(3),
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- One active device per provider unit, and one active device per vehicle.
CREATE UNIQUE INDEX "FleetTelematicsDevice_unit_key" ON "FleetTelematicsDevice" ("organizationId", provider,
    "externalUnitId") WHERE "isActive";
CREATE UNIQUE INDEX "FleetTelematicsDevice_vehicle_key" ON "FleetTelematicsDevice" ("organizationId", "vehicleId")
    WHERE "isActive" AND "vehicleId" IS NOT NULL;

CREATE TABLE "FleetPosition" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"      TEXT          NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "deviceId"       TEXT          NOT NULL REFERENCES "FleetTelematicsDevice" (id) ON DELETE CASCADE,
    "recordedAt"     TIMESTAMP(3)  NOT NULL,
    latitude         NUMERIC(9, 6) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude        NUMERIC(9, 6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    "speedKmh"       NUMERIC(6, 1) CHECK ("speedKmh" IS NULL OR "speedKmh" >= 0),
    ignition         BOOLEAN,
    -- Provider's id of the fix: re-pulling a window never duplicates a position.
    "externalId"     TEXT,
    "receivedAt"     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetPosition_vehicle_time_idx" ON "FleetPosition" ("organizationId", "vehicleId", "recordedAt");
CREATE UNIQUE INDEX "FleetPosition_external_key" ON "FleetPosition" ("deviceId", "externalId")
    WHERE "externalId" IS NOT NULL;
-- Retention purge scans by time.
CREATE INDEX "FleetPosition_recorded_idx" ON "FleetPosition" ("organizationId", "recordedAt");

CREATE TABLE "FleetTelematicsEvent" (
    id                 TEXT          NOT NULL PRIMARY KEY,
    "organizationId"   TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"        TEXT          NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "deviceId"         TEXT          NOT NULL REFERENCES "FleetTelematicsDevice" (id) ON DELETE CASCADE,
    "eventType"        TEXT          NOT NULL
        CHECK ("eventType" IN ('ignition_on', 'ignition_off', 'speeding', 'idling', 'geofence_enter', 'geofence_exit',
                               'harsh_braking', 'harsh_acceleration', 'other')),
    "occurredAt"       TIMESTAMP(3)  NOT NULL,
    latitude           NUMERIC(9, 6),
    longitude          NUMERIC(9, 6),
    "speedKmh"         NUMERIC(6, 1),
    data               JSONB         NOT NULL DEFAULT '{}'::jsonb,
    "externalId"       TEXT,
    -- Alerts (speeding, idling, geofence, harsh driving) need an acknowledgement; ignition events do not.
    "isAlert"          BOOLEAN       NOT NULL DEFAULT false,
    severity           TEXT          NOT NULL DEFAULT 'info' CHECK (severity IN ('info', 'warning', 'critical')),
    "acknowledgedAt"   TIMESTAMP(3),
    "acknowledgedBy"   TEXT,
    "acknowledgeNote"  TEXT,
    "receivedAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetTelematicsEvent_external_key" ON "FleetTelematicsEvent" ("deviceId", "externalId")
    WHERE "externalId" IS NOT NULL;
CREATE INDEX "FleetTelematicsEvent_vehicle_idx" ON "FleetTelematicsEvent" ("organizationId", "vehicleId",
    "occurredAt");
CREATE INDEX "FleetTelematicsEvent_open_alert_idx" ON "FleetTelematicsEvent" ("organizationId", "occurredAt")
    WHERE "isAlert" AND "acknowledgedAt" IS NULL;

ALTER TABLE "FleetTelematicsDevice" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetTelematicsDevice" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetPosition" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetPosition" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetTelematicsEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetTelematicsEvent" USING (("organizationId" = current_setting('app.org_id', true)));
