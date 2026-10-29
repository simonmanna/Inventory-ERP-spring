-- V941 (placeholder number, renumbered at merge): fleet 1.4a — weighbridge core (ADR-S009 §3 / §7, requirements §5.1):
-- stations per site, indicator devices (WeighbridgeAdapter SPI: generic ASCII over TCP / serial, simulator), per-product
-- weighing settings (kg per unit, tolerance overrides), stored vehicle tares with expiry, weighing tickets and their
-- out-of-tolerance exceptions (approval engine, separation of duties).
--
-- Cross-module references (branch, inventory location, partner, product, unit, purchase order / line, goods receipt,
-- sales order) are plain text ids validated by the fleet service (as in V15). "deliveryOrderId" / "tripId" stay
-- without a foreign key: fleet 1.3 owns those tables and 1.4b adds the constraints.

CREATE TABLE "WeighbridgeStation" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT          NOT NULL,
    name                TEXT          NOT NULL,
    "branchId"          TEXT,
    -- Yard / store the inbound goods receipts of this station go to (default of new tickets).
    "locationId"        TEXT,
    -- Tolerance of net vs expected weight: out of tolerance when |variance| > max(toleranceKg, expected x pct / 100).
    "tolerancePct"      NUMERIC(7, 3)  NOT NULL DEFAULT 0.5 CHECK ("tolerancePct" >= 0 AND "tolerancePct" <= 100),
    "toleranceKg"       NUMERIC(14, 2) NOT NULL DEFAULT 20 CHECK ("toleranceKg" >= 0),
    -- A stored tare is valid for this many days (re-tare after N days).
    "tareValidityDays"  INTEGER       NOT NULL DEFAULT 30 CHECK ("tareValidityDays" BETWEEN 1 AND 3650),
    -- Plausibility of a captured weight (an empty platform / an overload is refused).
    "minWeightKg"       NUMERIC(14, 2) NOT NULL DEFAULT 200 CHECK ("minWeightKg" >= 0),
    "maxWeightKg"       NUMERIC(14, 2) NOT NULL DEFAULT 100000 CHECK ("maxWeightKg" > 0),
    "isActive"          BOOLEAN       NOT NULL DEFAULT true,
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("maxWeightKg" > "minWeightKg")
);
CREATE UNIQUE INDEX "WeighbridgeStation_org_code_key" ON "WeighbridgeStation" ("organizationId", code);

CREATE TABLE "WeighbridgeDevice" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "stationId"         TEXT          NOT NULL REFERENCES "WeighbridgeStation" (id) ON DELETE CASCADE,
    code                TEXT          NOT NULL,
    name                TEXT,
    -- WeighbridgeAdapter.type(): simulator, ascii-tcp, ascii-serial (validated against the registered adapters).
    "adapterType"       TEXT          NOT NULL,
    -- Adapter connection / frame configuration (host, port, frame regex, ...). Never secrets.
    config              JSONB         NOT NULL DEFAULT '{}'::jsonb,
    "readTimeoutMs"     INTEGER       NOT NULL DEFAULT 5000 CHECK ("readTimeoutMs" BETWEEN 500 AND 60000),
    "isActive"          BOOLEAN       NOT NULL DEFAULT true,
    "lastReadAt"        TIMESTAMP(3),
    "lastReadKg"        NUMERIC(14, 2),
    "lastStatus"        TEXT CHECK ("lastStatus" IS NULL OR "lastStatus" IN ('ok', 'error')),
    "lastError"         TEXT,
    notes               TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "WeighbridgeDevice_org_code_key" ON "WeighbridgeDevice" ("organizationId", code);
CREATE INDEX "WeighbridgeDevice_station_idx" ON "WeighbridgeDevice" ("organizationId", "stationId");

-- Weighing settings of a product: kg per 1 unit of the product's base unit (density for m3 products, bag weight, ...)
-- when its units have no weight conversion, and tolerance overrides (each null = the station's).
CREATE TABLE "WeighbridgeProductSetting" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"         TEXT          NOT NULL,
    "kgPerUnit"         NUMERIC(18, 6) CHECK ("kgPerUnit" IS NULL OR "kgPerUnit" > 0),
    "tolerancePct"      NUMERIC(7, 3)  CHECK ("tolerancePct" IS NULL OR ("tolerancePct" >= 0 AND "tolerancePct" <= 100)),
    "toleranceKg"       NUMERIC(14, 2) CHECK ("toleranceKg" IS NULL OR "toleranceKg" >= 0),
    notes               TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "WeighbridgeProductSetting_product_key" ON "WeighbridgeProductSetting" ("organizationId",
    "productId");

-- Stored tare per vehicle (fleet vehicle or third-party plate) with expiry. One active tare per plate.
CREATE TABLE "FleetVehicleTare" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "vehicleId"         TEXT REFERENCES "FleetVehicle" (id),
    "registrationPlate" TEXT          NOT NULL,
    "tareKg"            NUMERIC(14, 2) NOT NULL CHECK ("tareKg" > 0),
    "weighedAt"         TIMESTAMP(3)  NOT NULL,
    "expiresAt"         TIMESTAMP(3)  NOT NULL,
    "stationId"         TEXT REFERENCES "WeighbridgeStation" (id),
    "deviceId"          TEXT REFERENCES "WeighbridgeDevice" (id),
    source              TEXT          NOT NULL CHECK (source IN ('device', 'manual')),
    "rawFrame"          TEXT,
    "manualReason"      TEXT,
    -- The ticket whose weighed tare was saved (no FK: tickets reference tares).
    "ticketId"          TEXT,
    status              TEXT          NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'superseded', 'revoked')),
    "revokedReason"     TEXT,
    "revokedBy"         TEXT,
    "revokedAt"         TIMESTAMP(3),
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("expiresAt" > "weighedAt"),
    CHECK (source <> 'manual' OR "manualReason" IS NOT NULL)
);
CREATE UNIQUE INDEX "FleetVehicleTare_active_plate_key" ON "FleetVehicleTare" ("organizationId", "registrationPlate")
    WHERE status = 'active';
CREATE INDEX "FleetVehicleTare_vehicle_idx" ON "FleetVehicleTare" ("organizationId", "vehicleId", "weighedAt");

CREATE TABLE "WeighbridgeTicket" (
    id                      TEXT          NOT NULL PRIMARY KEY,
    "organizationId"        TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "ticketNumber"          TEXT          NOT NULL,
    "stationId"             TEXT          NOT NULL REFERENCES "WeighbridgeStation" (id),
    "subjectType"           TEXT          NOT NULL
        CHECK ("subjectType" IN ('sale_delivery', 'purchase_receipt', 'internal_haulage', 'other')),
    direction               TEXT          NOT NULL CHECK (direction IN ('outbound', 'inbound', 'internal')),
    status                  TEXT          NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'first_weighed', 'exception', 'completed', 'cancelled', 'void')),
    -- Truck: a fleet vehicle or an ad-hoc plate (third-party transporter).
    "vehicleId"             TEXT REFERENCES "FleetVehicle" (id),
    "registrationPlate"     TEXT          NOT NULL,
    "trailerPlate"          TEXT,
    "driverId"              TEXT REFERENCES "FleetDriver" (id),
    "driverName"            TEXT,
    "transporterPartnerId"  TEXT,
    -- Customer (outbound) or supplier (inbound).
    "partnerId"             TEXT,
    -- Reference documents. deliveryOrderId / tripId: fleet 1.3 ids, FK added by 1.4b.
    "salesOrderId"          TEXT,
    "deliveryOrderId"       TEXT,
    "tripId"                TEXT,
    "purchaseOrderId"       TEXT,
    "purchaseOrderLineId"   TEXT,
    "referenceNumber"       TEXT,
    "productId"             TEXT,
    "orderedQty"            NUMERIC(18, 4) CHECK ("orderedQty" IS NULL OR "orderedQty" > 0),
    "orderedUomId"          TEXT,
    "expectedKg"            NUMERIC(14, 2),
    "locationId"            TEXT,
    -- First and second weighing: device-captured (device + raw frame), manual (reason, permission) or the stored tare.
    "firstWeightKg"         NUMERIC(14, 2),
    "firstWeighedAt"        TIMESTAMP(3),
    "firstSource"           TEXT CHECK ("firstSource" IS NULL OR "firstSource" IN ('device', 'manual', 'stored_tare')),
    "firstDeviceId"         TEXT REFERENCES "WeighbridgeDevice" (id),
    "firstRawFrame"         TEXT,
    "firstManualReason"     TEXT,
    "firstBy"               TEXT,
    "firstTareId"           TEXT REFERENCES "FleetVehicleTare" (id),
    "secondWeightKg"        NUMERIC(14, 2),
    "secondWeighedAt"       TIMESTAMP(3),
    "secondSource"          TEXT CHECK ("secondSource" IS NULL OR "secondSource" IN ('device', 'manual', 'stored_tare')),
    "secondDeviceId"        TEXT REFERENCES "WeighbridgeDevice" (id),
    "secondRawFrame"        TEXT,
    "secondManualReason"    TEXT,
    "secondBy"              TEXT,
    "secondTareId"          TEXT REFERENCES "FleetVehicleTare" (id),
    -- Result: gross = heavier, tare = lighter weighing; net in kg and in the document unit.
    "grossKg"               NUMERIC(14, 2),
    "tareKg"                NUMERIC(14, 2),
    "netKg"                 NUMERIC(14, 2),
    "netQty"                NUMERIC(18, 4),
    "netUomId"              TEXT,
    "varianceKg"            NUMERIC(14, 2),
    "variancePct"           NUMERIC(9, 3),
    "allowedVarianceKg"     NUMERIC(14, 2),
    "tolerancePct"          NUMERIC(7, 3),
    "toleranceKg"           NUMERIC(14, 2),
    "withinTolerance"       BOOLEAN,
    "isManual"              BOOLEAN       NOT NULL DEFAULT false,
    "usedStoredTare"        BOOLEAN       NOT NULL DEFAULT false,
    "completedAt"           TIMESTAMP(3),
    "completedBy"           TEXT,
    "printCount"            INTEGER       NOT NULL DEFAULT 0,
    "firstPrintedAt"        TIMESTAMP(3),
    "lastPrintedAt"         TIMESTAMP(3),
    "lastPrintedBy"         TEXT,
    "cancelledAt"           TIMESTAMP(3),
    "cancelledBy"           TEXT,
    "cancelReason"          TEXT,
    "voidedAt"              TIMESTAMP(3),
    "voidedBy"              TEXT,
    "voidReason"            TEXT,
    "reissuedFromId"        TEXT REFERENCES "WeighbridgeTicket" (id),
    "replacedById"          TEXT REFERENCES "WeighbridgeTicket" (id),
    -- Inbound: the procurement goods receipt (draft) this ticket fed — exactly one line per ticket.
    "goodsReceiptId"        TEXT,
    "goodsReceiptLineId"    TEXT,
    notes                   TEXT,
    "createdBy"             TEXT,
    "updatedBy"             TEXT,
    "createdAt"             TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"             TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("firstSource" <> 'manual' OR "firstManualReason" IS NOT NULL),
    CHECK ("secondSource" <> 'manual' OR "secondManualReason" IS NOT NULL),
    CHECK (status <> 'completed' OR "netKg" IS NOT NULL),
    CHECK (status <> 'void' OR "voidReason" IS NOT NULL)
);
CREATE UNIQUE INDEX "WeighbridgeTicket_org_number_key" ON "WeighbridgeTicket" ("organizationId", "ticketNumber");
-- A truck is on at most one live ticket.
CREATE UNIQUE INDEX "WeighbridgeTicket_live_plate_key" ON "WeighbridgeTicket" ("organizationId", "registrationPlate")
    WHERE status IN ('open', 'first_weighed', 'exception');
CREATE UNIQUE INDEX "WeighbridgeTicket_grn_line_key" ON "WeighbridgeTicket" ("goodsReceiptLineId")
    WHERE "goodsReceiptLineId" IS NOT NULL;
CREATE INDEX "WeighbridgeTicket_queue_idx" ON "WeighbridgeTicket" ("organizationId", status, "createdAt");
CREATE INDEX "WeighbridgeTicket_station_idx" ON "WeighbridgeTicket" ("organizationId", "stationId", "createdAt");
CREATE INDEX "WeighbridgeTicket_completed_idx" ON "WeighbridgeTicket" ("organizationId", "completedAt")
    WHERE status = 'completed';
CREATE INDEX "WeighbridgeTicket_partner_idx" ON "WeighbridgeTicket" ("organizationId", "partnerId");
CREATE INDEX "WeighbridgeTicket_product_idx" ON "WeighbridgeTicket" ("organizationId", "productId");
CREATE INDEX "WeighbridgeTicket_delivery_idx" ON "WeighbridgeTicket" ("organizationId", "deliveryOrderId")
    WHERE "deliveryOrderId" IS NOT NULL;
CREATE INDEX "WeighbridgeTicket_trip_idx" ON "WeighbridgeTicket" ("organizationId", "tripId")
    WHERE "tripId" IS NOT NULL;
CREATE INDEX "WeighbridgeTicket_po_idx" ON "WeighbridgeTicket" ("organizationId", "purchaseOrderId")
    WHERE "purchaseOrderId" IS NOT NULL;
CREATE INDEX "WeighbridgeTicket_vehicle_idx" ON "WeighbridgeTicket" ("organizationId", "vehicleId");

-- Out-of-tolerance exception of a ticket: approved through the approval engine (entity type weighbridge_exception);
-- the ticket's operators cannot approve it. One pending exception per ticket.
CREATE TABLE "WeighbridgeException" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "ticketId"          TEXT          NOT NULL REFERENCES "WeighbridgeTicket" (id) ON DELETE CASCADE,
    kind                TEXT          NOT NULL DEFAULT 'tolerance' CHECK (kind IN ('tolerance')),
    "expectedKg"        NUMERIC(14, 2) NOT NULL,
    "netKg"             NUMERIC(14, 2) NOT NULL,
    "varianceKg"        NUMERIC(14, 2) NOT NULL,
    "variancePct"       NUMERIC(9, 3),
    "allowedVarianceKg" NUMERIC(14, 2) NOT NULL,
    reason              TEXT,
    status              TEXT          NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    "approvalRequestId" TEXT,
    "raisedBy"          TEXT,
    "raisedAt"          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "decidedBy"         TEXT,
    "decidedAt"         TIMESTAMP(3),
    "decisionNotes"     TEXT,
    "createdAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "WeighbridgeException_pending_key" ON "WeighbridgeException" ("ticketId") WHERE status = 'pending';
CREATE INDEX "WeighbridgeException_org_status_idx" ON "WeighbridgeException" ("organizationId", status, "raisedAt");

ALTER TABLE "WeighbridgeStation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WeighbridgeStation" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "WeighbridgeDevice" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WeighbridgeDevice" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "WeighbridgeProductSetting" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WeighbridgeProductSetting" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetVehicleTare" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetVehicleTare" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "WeighbridgeTicket" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WeighbridgeTicket" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "WeighbridgeException" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "WeighbridgeException" USING (("organizationId" = current_setting('app.org_id', true)));
