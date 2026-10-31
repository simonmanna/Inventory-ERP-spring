-- V30 (placeholder number, renumbered at merge): shop-floor execution & data capture (ADR-S015, construction-materials
-- Phase 2.7).
--
-- New tables only; no existing table is changed. Production shifts, the downtime reason taxonomy, per-work-centre
-- shop-floor profiles (ideal rate for OEE, machine code), shift reports with supervisor approval, operator clock
-- entries, downtime events, production (output / scrap) reports, meter readings and raw machine-data events.
-- Stock and GL never move here: a confirmed production report of a multi-stage order posts through the ADR-S011
-- stage engine (StageExecutionService.output) and keeps the stage posting id; machine data only creates drafts.

-- ---------------------------------------------------------------- production shifts

CREATE TABLE "ShopFloorShift" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    -- Minutes after local midnight (organization time zone); end <= start = the shift ends the next day.
    "startMinute"    INTEGER      NOT NULL CHECK ("startMinute" BETWEEN 0 AND 1439),
    "endMinute"      INTEGER      NOT NULL CHECK ("endMinute" BETWEEN 1 AND 1440),
    -- Paid breaks are not planned production time (OEE).
    "breakMinutes"   INTEGER      NOT NULL DEFAULT 0 CHECK ("breakMinutes" >= 0),
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "sortOrder"      INTEGER      NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("startMinute" <> "endMinute")
);
CREATE UNIQUE INDEX "ShopFloorShift_org_code_key" ON "ShopFloorShift" ("organizationId", code);

-- ---------------------------------------------------------------- downtime reason taxonomy

CREATE TABLE "ShopFloorDowntimeReason" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    category         TEXT         NOT NULL DEFAULT 'other' CHECK (category IN ('breakdown', 'setup', 'material',
        'quality', 'operator', 'utility', 'minor_stop', 'planned', 'other')),
    -- Planned stops (breaks, planned maintenance, no orders) are excluded from planned production time; unplanned
    -- stops are availability losses.
    "isPlanned"      BOOLEAN      NOT NULL DEFAULT false,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "sortOrder"      INTEGER      NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ShopFloorDowntimeReason_org_code_key" ON "ShopFloorDowntimeReason" ("organizationId", code);

-- ---------------------------------------------------------------- work-centre profile (OEE rate, machine mapping)

CREATE TABLE "ShopFloorWorkCenterProfile" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workCenterId"     TEXT           NOT NULL REFERENCES "WorkCenter" (id) ON DELETE CASCADE,
    -- Ideal (nameplate) output per run hour in the product's base unit (t/h for a crusher, blocks/h for a press).
    "idealRatePerHour" NUMERIC(20, 6) CHECK ("idealRatePerHour" IS NULL OR "idealRatePerHour" > 0),
    "rateUom"          TEXT,
    -- Identifier the PLC / counter / belt scale reports (machine-data events); default the work centre code.
    "machineCode"      TEXT,
    -- Meters read on this work centre (e.g. ["hours", "kwh", "tonnes"]).
    "meterCodes"       JSONB          NOT NULL DEFAULT '[]'::jsonb,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ShopFloorWorkCenterProfile_wc_key" ON "ShopFloorWorkCenterProfile" ("workCenterId");
CREATE UNIQUE INDEX "ShopFloorWorkCenterProfile_org_machine_key" ON "ShopFloorWorkCenterProfile"
    ("organizationId", "machineCode") WHERE "machineCode" IS NOT NULL;
CREATE INDEX "ShopFloorWorkCenterProfile_org_idx" ON "ShopFloorWorkCenterProfile" ("organizationId");

-- ---------------------------------------------------------------- shift reports (supervisor approval)

CREATE TABLE "ShopFloorShiftReport" (
    id                       TEXT          NOT NULL PRIMARY KEY,
    "organizationId"         TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                     TEXT          NOT NULL,
    "workCenterId"           TEXT          NOT NULL REFERENCES "WorkCenter" (id),
    "shiftId"                TEXT          NOT NULL REFERENCES "ShopFloorShift" (id),
    "shiftDate"              DATE          NOT NULL,
    -- The shift window (UTC instants of the local start / end).
    "startsAt"               TIMESTAMP(3)  NOT NULL,
    "endsAt"                 TIMESTAMP(3)  NOT NULL,
    -- open (recording) → submitted (by the shift lead) → approved (supervisor; locked) | rejected → open again.
    status                   TEXT          NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'submitted', 'approved')),
    notes                    TEXT,
    "submittedAt"            TIMESTAMP(3),
    "submittedBy"            TEXT,
    "approvedAt"             TIMESTAMP(3),
    "approvedBy"             TEXT,
    "approvalNotes"          TEXT,
    "rejectedAt"             TIMESTAMP(3),
    "rejectedBy"             TEXT,
    "rejectionReason"        TEXT,
    -- OEE snapshot taken at submission / approval (live values are computed on read while open).
    "plannedMinutes"         INTEGER,
    "plannedDowntimeMinutes" INTEGER,
    "downtimeMinutes"        INTEGER,
    "runMinutes"             INTEGER,
    "goodQty"                NUMERIC(20, 6),
    "scrapQty"               NUMERIC(20, 6),
    availability             NUMERIC(9, 4),
    performance              NUMERIC(9, 4),
    quality                  NUMERIC(9, 4),
    oee                      NUMERIC(9, 4),
    "createdBy"              TEXT,
    "createdAt"              TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"              TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("endsAt" > "startsAt")
);
CREATE UNIQUE INDEX "ShopFloorShiftReport_org_code_key" ON "ShopFloorShiftReport" ("organizationId", code);
CREATE UNIQUE INDEX "ShopFloorShiftReport_wc_shift_date_key" ON "ShopFloorShiftReport"
    ("organizationId", "workCenterId", "shiftId", "shiftDate");
CREATE INDEX "ShopFloorShiftReport_org_status_idx" ON "ShopFloorShiftReport" ("organizationId", status, "shiftDate");
CREATE INDEX "ShopFloorShiftReport_org_window_idx" ON "ShopFloorShiftReport" ("organizationId", "workCenterId", "startsAt");

-- ---------------------------------------------------------------- operator clock entries

CREATE TABLE "ShopFloorClockEntry" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL,
    "workCenterId"   TEXT         NOT NULL REFERENCES "WorkCenter" (id),
    "workOrderId"    TEXT         REFERENCES "WorkOrder" (id) ON DELETE SET NULL,
    "shiftReportId"  TEXT         REFERENCES "ShopFloorShiftReport" (id),
    "crewCode"       TEXT,
    role             TEXT,
    "clockOnAt"      TIMESTAMP(3) NOT NULL,
    "clockOffAt"     TIMESTAMP(3),
    minutes          INTEGER,
    notes            TEXT,
    "recordedBy"     TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("clockOffAt" IS NULL OR "clockOffAt" >= "clockOnAt")
);
-- One open clock entry per person.
CREATE UNIQUE INDEX "ShopFloorClockEntry_open_user_key" ON "ShopFloorClockEntry" ("organizationId", "userId")
    WHERE "clockOffAt" IS NULL;
CREATE INDEX "ShopFloorClockEntry_org_wc_idx" ON "ShopFloorClockEntry" ("organizationId", "workCenterId", "clockOnAt");
CREATE INDEX "ShopFloorClockEntry_report_idx" ON "ShopFloorClockEntry" ("shiftReportId");

-- ---------------------------------------------------------------- raw machine-data events

CREATE TABLE "ShopFloorMachineEvent" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    adapter          TEXT           NOT NULL,
    -- The source's own event id; re-delivery of the same id is ignored (at-least-once sources).
    "externalId"     TEXT           NOT NULL,
    "machineCode"    TEXT           NOT NULL,
    "workCenterId"   TEXT           REFERENCES "WorkCenter" (id),
    "eventType"      TEXT           NOT NULL CHECK ("eventType" IN ('count', 'downtime', 'downtime_start', 'downtime_stop')),
    "occurredAt"     TIMESTAMP(3)   NOT NULL,
    "endedAt"        TIMESTAMP(3),
    "goodQty"        NUMERIC(20, 6),
    "scrapQty"       NUMERIC(20, 6),
    "reasonCode"     TEXT,
    payload          JSONB,
    -- drafted = became a draft production report / downtime; ignored = nothing to draft; error = see error.
    status           TEXT           NOT NULL CHECK (status IN ('drafted', 'ignored', 'error')),
    "draftType"      TEXT           CHECK ("draftType" IS NULL OR "draftType" IN ('production', 'downtime')),
    "draftId"        TEXT,
    error            TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ShopFloorMachineEvent_org_adapter_ext_key" ON "ShopFloorMachineEvent"
    ("organizationId", adapter, "externalId");
CREATE INDEX "ShopFloorMachineEvent_org_wc_idx" ON "ShopFloorMachineEvent" ("organizationId", "workCenterId", "occurredAt");

-- ---------------------------------------------------------------- downtime events

CREATE TABLE "ShopFloorDowntime" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workCenterId"   TEXT         NOT NULL REFERENCES "WorkCenter" (id),
    "workOrderId"    TEXT         REFERENCES "WorkOrder" (id) ON DELETE SET NULL,
    "shiftReportId"  TEXT         REFERENCES "ShopFloorShiftReport" (id),
    "reasonId"       TEXT         REFERENCES "ShopFloorDowntimeReason" (id),
    "reasonCode"     TEXT,
    "isPlanned"      BOOLEAN      NOT NULL DEFAULT false,
    "startedAt"      TIMESTAMP(3) NOT NULL,
    "endedAt"        TIMESTAMP(3),
    minutes          INTEGER,
    -- operator = logged / pause on the terminal; machine = drafted from machine data.
    source           TEXT         NOT NULL DEFAULT 'operator' CHECK (source IN ('operator', 'machine')),
    -- draft (machine, awaits operator confirmation) → confirmed | rejected.
    status           TEXT         NOT NULL DEFAULT 'confirmed' CHECK (status IN ('draft', 'confirmed', 'rejected')),
    "machineEventId" TEXT         REFERENCES "ShopFloorMachineEvent" (id),
    notes            TEXT,
    "recordedBy"     TEXT,
    "confirmedBy"    TEXT,
    "confirmedAt"    TIMESTAMP(3),
    "rejectedReason" TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("endedAt" IS NULL OR "endedAt" >= "startedAt"),
    CHECK (status = 'draft' OR status = 'rejected' OR "reasonId" IS NOT NULL)
);
-- At most one open stop per work centre and source.
CREATE UNIQUE INDEX "ShopFloorDowntime_open_key" ON "ShopFloorDowntime" ("organizationId", "workCenterId", source)
    WHERE "endedAt" IS NULL AND status <> 'rejected';
CREATE INDEX "ShopFloorDowntime_org_wc_idx" ON "ShopFloorDowntime" ("organizationId", "workCenterId", "startedAt");
CREATE INDEX "ShopFloorDowntime_report_idx" ON "ShopFloorDowntime" ("shiftReportId");

-- ---------------------------------------------------------------- production (output / scrap) reports

CREATE TABLE "ShopFloorProductionReport" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workCenterId"      TEXT           NOT NULL REFERENCES "WorkCenter" (id),
    "workOrderId"       TEXT           REFERENCES "WorkOrder" (id),
    "productionOrderId" TEXT           REFERENCES "ProductionOrder" (id),
    -- Set for a multi-stage order: the report posts through the stage engine.
    "stageId"           TEXT           REFERENCES "ProductionOrderStage" (id),
    "shiftReportId"     TEXT           REFERENCES "ShopFloorShiftReport" (id),
    "qtyGood"           NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("qtyGood" >= 0),
    "qtyScrap"          NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("qtyScrap" >= 0),
    "scrapReasonCode"   TEXT,
    "completesStage"    BOOLEAN        NOT NULL DEFAULT false,
    "batchNumber"       TEXT,
    -- Interval a machine count covers (null for operator reports).
    "periodStart"       TIMESTAMP(3),
    "periodEnd"         TIMESTAMP(3),
    "reportedAt"        TIMESTAMP(3)   NOT NULL,
    source              TEXT           NOT NULL DEFAULT 'operator' CHECK (source IN ('operator', 'machine')),
    -- draft (machine, awaits confirmation) → posted (stage posting / work-order count) | rejected.
    status              TEXT           NOT NULL DEFAULT 'posted' CHECK (status IN ('draft', 'posted', 'rejected')),
    "stagePostingId"    TEXT,
    "machineEventId"    TEXT           REFERENCES "ShopFloorMachineEvent" (id),
    notes               TEXT,
    "recordedBy"        TEXT,
    "confirmedBy"       TEXT,
    "confirmedAt"       TIMESTAMP(3),
    "rejectedReason"    TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ShopFloorProductionReport_org_wc_idx" ON "ShopFloorProductionReport" ("organizationId", "workCenterId", "reportedAt");
CREATE INDEX "ShopFloorProductionReport_org_status_idx" ON "ShopFloorProductionReport" ("organizationId", status);
CREATE INDEX "ShopFloorProductionReport_report_idx" ON "ShopFloorProductionReport" ("shiftReportId");
CREATE INDEX "ShopFloorProductionReport_wo_idx" ON "ShopFloorProductionReport" ("workOrderId");

-- ---------------------------------------------------------------- meter readings

CREATE TABLE "ShopFloorMeterReading" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workCenterId"   TEXT           NOT NULL REFERENCES "WorkCenter" (id),
    "meterCode"      TEXT           NOT NULL,
    reading          NUMERIC(20, 6) NOT NULL CHECK (reading >= 0),
    -- reading − the previous reading of the same meter (null for the first one or after a reset).
    delta            NUMERIC(20, 6),
    "isReset"        BOOLEAN        NOT NULL DEFAULT false,
    "readAt"         TIMESTAMP(3)   NOT NULL,
    "shiftReportId"  TEXT           REFERENCES "ShopFloorShiftReport" (id),
    notes            TEXT,
    "recordedBy"     TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ShopFloorMeterReading_org_wc_meter_idx" ON "ShopFloorMeterReading"
    ("organizationId", "workCenterId", "meterCode", "readAt");

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "ShopFloorShift" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorShift" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorDowntimeReason" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorDowntimeReason" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorWorkCenterProfile" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorWorkCenterProfile" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorShiftReport" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorShiftReport" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorClockEntry" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorClockEntry" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorMachineEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorMachineEvent" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorDowntime" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorDowntime" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorProductionReport" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorProductionReport" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ShopFloorMeterReading" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ShopFloorMeterReading" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions (ADR-S015)

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['shop_floor:approve', 'shop_floor:machine_data']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['shop_floor:approve', 'shop_floor:machine_data'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
