-- V911 (placeholder number, renumbered at merge): fleet 1.6 — maintenance of vehicles, mobile plant and (Phase 4)
-- fixed plant (ADR-S009 §5): service templates per class + schedules by km / engine hours / calendar (whichever
-- first), fleet-owned maintenance work orders with tasks and cost lines (spare parts issued from inventory, labour,
-- external workshop bills), downtime, inspection (DVIR) checklists, inspections and defects.
--
-- Additive: no existing table changes. Every maintenance row keys on the maintainable unit "vehicleId" (a
-- FleetVehicle — trucks, mobile plant and fixed plant of category kind fixed_plant share the register). Cross-module
-- references (product, inventory location, hr employee, partner, vendor bill, journal entry, file) are plain text ids
-- validated by the fleet services. Permissions fleet_maintenance:* already exist (V15).

-- ---------------------------------------------------------------- service templates (per vehicle class)

CREATE TABLE "FleetServiceTemplate" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT           NOT NULL,
    name             TEXT           NOT NULL,
    description      TEXT,
    -- Class the template is meant for (null = any class).
    "categoryId"     TEXT REFERENCES "FleetVehicleCategory" (id),
    -- Intervals: the service is due at whichever comes first. At least one is set.
    "intervalKm"     NUMERIC(14, 1) CHECK ("intervalKm" IS NULL OR "intervalKm" > 0),
    "intervalHours"  NUMERIC(14, 1) CHECK ("intervalHours" IS NULL OR "intervalHours" > 0),
    "intervalDays"   INTEGER        CHECK ("intervalDays" IS NULL OR "intervalDays" > 0),
    -- "Due soon" window before each due point.
    "leadKm"         NUMERIC(14, 1) NOT NULL DEFAULT 0 CHECK ("leadKm" >= 0),
    "leadHours"      NUMERIC(14, 1) NOT NULL DEFAULT 0 CHECK ("leadHours" >= 0),
    "leadDays"       INTEGER        NOT NULL DEFAULT 0 CHECK ("leadDays" >= 0),
    -- Task checklist copied onto generated work orders: JSON array of strings.
    tasks            JSONB          NOT NULL DEFAULT '[]'::jsonb,
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetServiceTemplate_interval_chk"
        CHECK ("intervalKm" IS NOT NULL OR "intervalHours" IS NOT NULL OR "intervalDays" IS NOT NULL)
);
CREATE UNIQUE INDEX "FleetServiceTemplate_org_code_key" ON "FleetServiceTemplate" ("organizationId", code);
CREATE INDEX "FleetServiceTemplate_org_category_idx" ON "FleetServiceTemplate" ("organizationId", "categoryId");

-- ---------------------------------------------------------------- service schedules (per unit)

CREATE TABLE "FleetServiceSchedule" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"        TEXT           NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "templateId"       TEXT REFERENCES "FleetServiceTemplate" (id),
    name               TEXT           NOT NULL,
    "intervalKm"       NUMERIC(14, 1) CHECK ("intervalKm" IS NULL OR "intervalKm" > 0),
    "intervalHours"    NUMERIC(14, 1) CHECK ("intervalHours" IS NULL OR "intervalHours" > 0),
    "intervalDays"     INTEGER        CHECK ("intervalDays" IS NULL OR "intervalDays" > 0),
    "leadKm"           NUMERIC(14, 1) NOT NULL DEFAULT 0 CHECK ("leadKm" >= 0),
    "leadHours"        NUMERIC(14, 1) NOT NULL DEFAULT 0 CHECK ("leadHours" >= 0),
    "leadDays"         INTEGER        NOT NULL DEFAULT 0 CHECK ("leadDays" >= 0),
    tasks              JSONB          NOT NULL DEFAULT '[]'::jsonb,
    -- Baseline of the next due point: the last service (or the schedule start). Meter values are lifetime values.
    "lastDoneOn"       DATE           NOT NULL,
    "lastDoneKm"       NUMERIC(14, 1),
    "lastDoneHours"    NUMERIC(14, 1),
    status             TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused')),
    "openWorkOrderId"  TEXT,
    "lastWorkOrderId"  TEXT,
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetServiceSchedule_interval_chk"
        CHECK ("intervalKm" IS NOT NULL OR "intervalHours" IS NOT NULL OR "intervalDays" IS NOT NULL)
);
CREATE UNIQUE INDEX "FleetServiceSchedule_vehicle_template_key" ON "FleetServiceSchedule" ("vehicleId", "templateId")
    WHERE "templateId" IS NOT NULL;
CREATE INDEX "FleetServiceSchedule_org_vehicle_idx" ON "FleetServiceSchedule" ("organizationId", "vehicleId");
CREATE INDEX "FleetServiceSchedule_org_status_idx" ON "FleetServiceSchedule" ("organizationId", status);

-- ---------------------------------------------------------------- maintenance work orders

CREATE TABLE "FleetWorkOrder" (
    id                      TEXT           NOT NULL PRIMARY KEY,
    "organizationId"        TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                    TEXT           NOT NULL,
    "vehicleId"             TEXT           NOT NULL REFERENCES "FleetVehicle" (id),
    type                    TEXT           NOT NULL
        CHECK (type IN ('preventive', 'corrective', 'breakdown', 'inspection_defect')),
    priority                TEXT           NOT NULL DEFAULT 'normal'
        CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    status                  TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('draft', 'open', 'in_progress', 'completed', 'closed', 'cancelled')),
    title                   TEXT           NOT NULL,
    description             TEXT,
    "scheduleId"            TEXT REFERENCES "FleetServiceSchedule" (id) ON DELETE SET NULL,
    -- generated by the due-service job / the "generate" action, or entered by hand
    source                  TEXT           NOT NULL DEFAULT 'manual'
        CHECK (source IN ('manual', 'schedule', 'schedule_job', 'defect', 'downtime')),
    "dueOn"                 DATE,
    "reportedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "openedAt"              TIMESTAMP(3),
    "startedAt"             TIMESTAMP(3),
    "completedAt"           TIMESTAMP(3),
    "closedAt"              TIMESTAMP(3),
    "cancelledAt"           TIMESTAMP(3),
    "cancelReason"          TEXT,
    resolution              TEXT,
    -- Meters (lifetime values) recorded at completion; they re-base the schedule.
    "completionOdometer"    NUMERIC(14, 1),
    "completionHours"       NUMERIC(14, 1),
    -- Lead mechanic / supervisor (hr employee) and the vehicle status to restore on completion.
    "assignedEmployeeId"    TEXT,
    "previousVehicleStatus" TEXT,
    "costCenterId"          TEXT,
    "branchId"              TEXT,
    -- Running cost totals of the posted lines (parts net of returns, labour, external bills).
    "partsCost"             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "labourCost"            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "externalCost"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "totalCost"             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "createdBy"             TEXT,
    "updatedBy"             TEXT,
    "createdAt"             TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"             TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetWorkOrder_org_code_key" ON "FleetWorkOrder" ("organizationId", code);
CREATE INDEX "FleetWorkOrder_org_status_idx" ON "FleetWorkOrder" ("organizationId", status);
CREATE INDEX "FleetWorkOrder_org_vehicle_idx" ON "FleetWorkOrder" ("organizationId", "vehicleId", "reportedAt");
-- One live work order per schedule: generation (job or manual) can never double-book a service.
CREATE UNIQUE INDEX "FleetWorkOrder_live_schedule_key" ON "FleetWorkOrder" ("scheduleId")
    WHERE "scheduleId" IS NOT NULL AND status IN ('draft', 'open', 'in_progress');

CREATE TABLE "FleetWorkOrderTask" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workOrderId"    TEXT         NOT NULL REFERENCES "FleetWorkOrder" (id) ON DELETE CASCADE,
    "sortOrder"      INTEGER      NOT NULL DEFAULT 0,
    description      TEXT         NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'skipped')),
    notes            TEXT,
    "doneBy"         TEXT,
    "doneAt"         TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetWorkOrderTask_wo_idx" ON "FleetWorkOrderTask" ("organizationId", "workOrderId", "sortOrder");

-- Cost lines: part (issued from stock), part_return (unused part back to stock, negative amount), labour, external
-- (supplier / workshop bill). Each posted line carries its own journal entry (posting key per line); a reversal
-- mirrors it and flips the line to "reversed".
CREATE TABLE "FleetWorkOrderLine" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "workOrderId"       TEXT           NOT NULL REFERENCES "FleetWorkOrder" (id) ON DELETE CASCADE,
    "vehicleId"         TEXT           NOT NULL REFERENCES "FleetVehicle" (id),
    "lineType"          TEXT           NOT NULL CHECK ("lineType" IN ('part', 'part_return', 'labour', 'external')),
    status              TEXT           NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'reversed')),
    description         TEXT,
    -- part / part_return
    "productId"         TEXT,
    "variantId"         TEXT,
    "locationId"        TEXT,
    quantity            NUMERIC(20, 6),
    "returnedQuantity"  NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "returnOfLineId"    TEXT REFERENCES "FleetWorkOrderLine" (id),
    "stockLedgerCode"   TEXT,
    -- labour
    "employeeId"        TEXT,
    hours               NUMERIC(10, 2),
    "workDate"          DATE,
    -- external workshop / supplier bill
    "partnerId"         TEXT,
    "supplierReference" TEXT,
    "billDate"          DATE,
    "vendorBillId"      TEXT,
    -- unit cost (part: per base unit at issue; labour: hourly rate; external: the bill amount) and signed amount
    "unitCost"          NUMERIC(20, 6),
    amount              NUMERIC(20, 6) NOT NULL,
    "journalEntryId"    TEXT,
    "postingKey"        TEXT,
    "reversalEntryId"   TEXT,
    "reversedAt"        TIMESTAMP(3),
    "reversedBy"        TEXT,
    "reverseReason"     TEXT,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetWorkOrderLine_part_chk" CHECK ("lineType" NOT IN ('part', 'part_return')
        OR ("productId" IS NOT NULL AND "locationId" IS NOT NULL AND quantity > 0)),
    CONSTRAINT "FleetWorkOrderLine_labour_chk" CHECK ("lineType" <> 'labour'
        OR ("employeeId" IS NOT NULL AND hours > 0)),
    CONSTRAINT "FleetWorkOrderLine_external_chk" CHECK ("lineType" <> 'external' OR "partnerId" IS NOT NULL),
    CONSTRAINT "FleetWorkOrderLine_returned_chk" CHECK ("returnedQuantity" >= 0
        AND ("lineType" <> 'part' OR "returnedQuantity" <= quantity))
);
CREATE INDEX "FleetWorkOrderLine_wo_idx" ON "FleetWorkOrderLine" ("organizationId", "workOrderId", "createdAt");
CREATE INDEX "FleetWorkOrderLine_vehicle_idx" ON "FleetWorkOrderLine" ("organizationId", "vehicleId", "lineType");
CREATE UNIQUE INDEX "FleetWorkOrderLine_posting_key" ON "FleetWorkOrderLine" ("organizationId", "postingKey")
    WHERE "postingKey" IS NOT NULL;

-- ---------------------------------------------------------------- downtime

CREATE TABLE "FleetDowntime" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"      TEXT           NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "workOrderId"    TEXT REFERENCES "FleetWorkOrder" (id) ON DELETE SET NULL,
    -- unplanned = breakdown / failure (counts for MTBF / MTTR); planned = scheduled service / inspection.
    kind             TEXT           NOT NULL DEFAULT 'unplanned' CHECK (kind IN ('planned', 'unplanned')),
    "reasonCode"     TEXT           NOT NULL
        CHECK ("reasonCode" IN ('engine', 'transmission', 'hydraulic', 'electrical', 'brakes', 'tyres', 'suspension',
                                'body', 'accident', 'preventive_service', 'inspection_defect', 'waiting_parts',
                                'operator_error', 'other')),
    description      TEXT,
    "startedAt"      TIMESTAMP(3)   NOT NULL,
    "endedAt"        TIMESTAMP(3),
    source           TEXT           NOT NULL DEFAULT 'manual'
        CHECK (source IN ('manual', 'work_order', 'inspection')),
    odometer         NUMERIC(14, 1),
    hours            NUMERIC(14, 1),
    "reportedBy"     TEXT,
    "endedBy"        TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetDowntime_period_chk" CHECK ("endedAt" IS NULL OR "endedAt" >= "startedAt")
);
-- A unit is down at most once at a time.
CREATE UNIQUE INDEX "FleetDowntime_open_key" ON "FleetDowntime" ("vehicleId") WHERE "endedAt" IS NULL;
CREATE INDEX "FleetDowntime_org_vehicle_idx" ON "FleetDowntime" ("organizationId", "vehicleId", "startedAt");
CREATE INDEX "FleetDowntime_wo_idx" ON "FleetDowntime" ("workOrderId") WHERE "workOrderId" IS NOT NULL;

-- ---------------------------------------------------------------- inspections (DVIR) and defects

CREATE TABLE "FleetInspectionTemplate" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    kind             TEXT         NOT NULL DEFAULT 'pre_trip' CHECK (kind IN ('pre_trip', 'post_trip', 'periodic')),
    "categoryId"     TEXT REFERENCES "FleetVehicleCategory" (id),
    -- JSON array of {code, label, section, severity (minor / major / critical when the item fails)}.
    items            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetInspectionTemplate_org_code_key" ON "FleetInspectionTemplate" ("organizationId", code);

CREATE TABLE "FleetInspection" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    "vehicleId"           TEXT           NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "templateId"          TEXT REFERENCES "FleetInspectionTemplate" (id),
    kind                  TEXT           NOT NULL CHECK (kind IN ('pre_trip', 'post_trip', 'periodic')),
    -- driver / inspector (hr employee) and the signed-in user who recorded it
    "inspectorEmployeeId" TEXT,
    "recordedBy"          TEXT,
    "inspectedAt"         TIMESTAMP(3)   NOT NULL,
    odometer              NUMERIC(14, 1),
    hours                 NUMERIC(14, 1),
    result                TEXT           NOT NULL CHECK (result IN ('pass', 'fail')),
    -- JSON array of {code, label, section, result (pass / fail / na), severity, notes}.
    items                 JSONB          NOT NULL DEFAULT '[]'::jsonb,
    -- JSON array of {attachmentId, itemCode} (vehicle attachments of kind photo).
    photos                JSONB          NOT NULL DEFAULT '[]'::jsonb,
    "defectCount"         INTEGER        NOT NULL DEFAULT 0,
    notes                 TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetInspection_org_code_key" ON "FleetInspection" ("organizationId", code);
CREATE INDEX "FleetInspection_org_vehicle_idx" ON "FleetInspection" ("organizationId", "vehicleId", "inspectedAt");

CREATE TABLE "FleetDefect" (
    id                     TEXT         NOT NULL PRIMARY KEY,
    "organizationId"       TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                   TEXT         NOT NULL,
    "vehicleId"            TEXT         NOT NULL REFERENCES "FleetVehicle" (id) ON DELETE CASCADE,
    "inspectionId"         TEXT REFERENCES "FleetInspection" (id) ON DELETE SET NULL,
    "itemCode"             TEXT,
    title                  TEXT         NOT NULL,
    description            TEXT,
    severity               TEXT         NOT NULL CHECK (severity IN ('minor', 'major', 'critical')),
    -- open → in_repair (linked work order) → resolved; or dismissed (not a defect, with a reason)
    status                 TEXT         NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'in_repair', 'resolved', 'dismissed')),
    "workOrderId"          TEXT REFERENCES "FleetWorkOrder" (id) ON DELETE SET NULL,
    "reportedByEmployeeId" TEXT,
    "reportedBy"           TEXT,
    "reportedAt"           TIMESTAMP(3) NOT NULL,
    "resolvedAt"           TIMESTAMP(3),
    "resolvedBy"           TEXT,
    resolution             TEXT,
    "createdAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetDefect_org_code_key" ON "FleetDefect" ("organizationId", code);
CREATE INDEX "FleetDefect_org_vehicle_status_idx" ON "FleetDefect" ("organizationId", "vehicleId", status);
CREATE INDEX "FleetDefect_wo_idx" ON "FleetDefect" ("workOrderId") WHERE "workOrderId" IS NOT NULL;

-- ---------------------------------------------------------------- tenancy (RLS)

ALTER TABLE "FleetServiceTemplate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetServiceTemplate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetServiceSchedule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetServiceSchedule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetWorkOrder" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetWorkOrder" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetWorkOrderTask" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetWorkOrderTask" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetWorkOrderLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetWorkOrderLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDowntime" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDowntime" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetInspectionTemplate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetInspectionTemplate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetInspection" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetInspection" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDefect" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDefect" USING (("organizationId" = current_setting('app.org_id', true)));
