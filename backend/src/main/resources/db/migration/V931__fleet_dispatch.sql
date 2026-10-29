-- V931 (placeholder number, renumbered at merge): fleet 1.3 — trips, delivery planning & dispatch (ADR-S009 §3).
-- Customer sites, delivery orders (from confirmed sales orders / invoices or manual), loads = trips within payload (t)
-- and body volume (m³), own fleet or third-party transporter with an agreed freight rate, the trip lifecycle, proof of
-- delivery, delivered-vs-dispatched discrepancies (approval-gated) and delivery-charge rules. Stock leaves the yard
-- through StockService exactly once per load (the load row is locked and its stock stamp checked), returns put it back.
-- Adds the foreign keys 1.2 left open: FleetCheckout."tripId" / FleetDriverClaim."tripId" → FleetLoad (NOT VALID, so
-- rows written before 1.3 are not re-checked).

CREATE TABLE "FleetCustomerSite" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT          NOT NULL,
    "partnerId"      TEXT          NOT NULL REFERENCES "Partner" (id),
    name             TEXT          NOT NULL,
    address          TEXT,
    city             TEXT,
    latitude         NUMERIC(9, 6) CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    longitude        NUMERIC(9, 6) CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    "contactName"    TEXT,
    "contactPhone"   TEXT,
    "accessNotes"    TEXT,
    -- Delivery zone for transport charges (free text, e.g. "Kampala Central") and the road distance from the yard.
    zone             TEXT,
    "distanceKm"     NUMERIC(10, 1) CHECK ("distanceKm" IS NULL OR "distanceKm" >= 0),
    "isActive"       BOOLEAN       NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "FleetCustomerSite_code_key" ON "FleetCustomerSite" ("organizationId", code)
    WHERE "deletedAt" IS NULL;
CREATE INDEX "FleetCustomerSite_partner_idx" ON "FleetCustomerSite" ("organizationId", "partnerId");

-- Per-product load factors for planning (tonnes / m³ per product base unit) until Phase 4 brings full UoM densities.
CREATE TABLE "FleetProductLoadFactor" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"          TEXT           NOT NULL REFERENCES "Product" (id) ON DELETE CASCADE,
    "tonnesPerUnit"      NUMERIC(14, 6) CHECK ("tonnesPerUnit" IS NULL OR "tonnesPerUnit" > 0),
    "cubicMetresPerUnit" NUMERIC(14, 6) CHECK ("cubicMetresPerUnit" IS NULL OR "cubicMetresPerUnit" > 0),
    notes                TEXT,
    "updatedBy"          TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetProductLoadFactor_product_key" ON "FleetProductLoadFactor" ("organizationId", "productId");

-- Agreed freight rates of third-party transporters (supplier partners).
CREATE TABLE "FleetTransporterRate" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL REFERENCES "Partner" (id),
    name             TEXT           NOT NULL,
    basis            TEXT           NOT NULL CHECK (basis IN ('per_trip', 'per_tonne', 'per_km')),
    rate             NUMERIC(14, 2) NOT NULL CHECK (rate >= 0),
    zone             TEXT,
    "effectiveFrom"  DATE,
    "effectiveTo"    DATE,
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("effectiveTo" IS NULL OR "effectiveFrom" IS NULL OR "effectiveTo" >= "effectiveFrom")
);
CREATE INDEX "FleetTransporterRate_partner_idx" ON "FleetTransporterRate" ("organizationId", "partnerId");

-- Transport / delivery charges billed to the customer, per customer / zone / distance band (most specific wins).
CREATE TABLE "FleetDeliveryChargeRule" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    "partnerId"      TEXT REFERENCES "Partner" (id),
    zone             TEXT,
    "minDistanceKm"  NUMERIC(10, 1) CHECK ("minDistanceKm" IS NULL OR "minDistanceKm" >= 0),
    "maxDistanceKm"  NUMERIC(10, 1) CHECK ("maxDistanceKm" IS NULL OR "maxDistanceKm" >= 0),
    basis            TEXT           NOT NULL CHECK (basis IN ('flat', 'per_trip', 'per_tonne', 'per_m3', 'per_km')),
    rate             NUMERIC(14, 2) NOT NULL CHECK (rate >= 0),
    "minAmount"      NUMERIC(14, 2) CHECK ("minAmount" IS NULL OR "minAmount" >= 0),
    "taxId"          TEXT,
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("maxDistanceKm" IS NULL OR "minDistanceKm" IS NULL OR "maxDistanceKm" >= "minDistanceKm")
);
CREATE INDEX "FleetDeliveryChargeRule_org_idx" ON "FleetDeliveryChargeRule" ("organizationId", "isActive");

CREATE TABLE "FleetDeliveryOrder" (
    id                   TEXT          NOT NULL PRIMARY KEY,
    "organizationId"     TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                 TEXT          NOT NULL,
    "partnerId"          TEXT          NOT NULL REFERENCES "Partner" (id),
    "siteId"             TEXT REFERENCES "FleetCustomerSite" (id),
    -- manual, sales_order (orders module Order) or invoice (invoicing sales_invoice Document).
    "sourceType"         TEXT          NOT NULL DEFAULT 'manual' CHECK ("sourceType" IN ('manual', 'sales_order', 'invoice')),
    "sourceId"           TEXT,
    "sourceNumber"       TEXT,
    -- dispatch: fleet issues stock per load; invoiced: the source invoice already issued it (posted invoice / billed order).
    "stockMode"          TEXT          NOT NULL DEFAULT 'dispatch' CHECK ("stockMode" IN ('dispatch', 'invoiced')),
    -- Invoice carrying the goods (source invoice, the order's bill, or the invoice fleet raised) — credit-note target.
    "goodsInvoiceId"     TEXT,
    "goodsInvoiceNumber" TEXT,
    "goodsInvoiceKind"   TEXT CHECK ("goodsInvoiceKind" IS NULL OR "goodsInvoiceKind" IN ('document', 'pos_invoice')),
    "chargesInvoiceId"   TEXT,
    "branchId"           TEXT,
    "locationId"         TEXT          NOT NULL REFERENCES "InventoryLocation" (id),
    "costCenterId"       TEXT,
    "requestedDate"      DATE          NOT NULL,
    "windowFrom"         TEXT CHECK ("windowFrom" IS NULL OR "windowFrom" ~ '^[0-2][0-9]:[0-5][0-9]$'),
    "windowTo"           TEXT CHECK ("windowTo" IS NULL OR "windowTo" ~ '^[0-2][0-9]:[0-5][0-9]$'),
    priority             TEXT          NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    status               TEXT          NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'planned', 'in_progress', 'delivered', 'closed', 'cancelled')),
    "contactName"        TEXT,
    "contactPhone"       TEXT,
    notes                TEXT,
    "cancelReason"       TEXT,
    "cancelledAt"        TIMESTAMP(3),
    "closedAt"           TIMESTAMP(3),
    "closeReason"        TEXT,
    "createdBy"          TEXT,
    "updatedBy"          TEXT,
    "createdAt"          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetDeliveryOrder_code_key" ON "FleetDeliveryOrder" ("organizationId", code);
-- One live delivery order per source document.
CREATE UNIQUE INDEX "FleetDeliveryOrder_source_key" ON "FleetDeliveryOrder" ("organizationId", "sourceType", "sourceId")
    WHERE "sourceId" IS NOT NULL AND status <> 'cancelled';
CREATE INDEX "FleetDeliveryOrder_date_idx" ON "FleetDeliveryOrder" ("organizationId", "requestedDate", status);
CREATE INDEX "FleetDeliveryOrder_partner_idx" ON "FleetDeliveryOrder" ("organizationId", "partnerId");

CREATE TABLE "FleetDeliveryOrderLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "deliveryOrderId" TEXT           NOT NULL REFERENCES "FleetDeliveryOrder" (id) ON DELETE CASCADE,
    "lineNumber"      INTEGER        NOT NULL,
    "productId"       TEXT           NOT NULL REFERENCES "Product" (id),
    "variantId"       TEXT,
    description       TEXT,
    -- Line unit + snapshot of base units per line unit (ADR-S003); quantities below are in the line unit.
    "uomId"           TEXT,
    "uomFactor"       NUMERIC(20, 6) NOT NULL DEFAULT 1 CHECK ("uomFactor" > 0),
    "orderedQty"      NUMERIC(14, 3) NOT NULL CHECK ("orderedQty" > 0),
    -- In loads that are not cancelled / returned (re-plannable quantity = ordered − planned).
    "plannedQty"      NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "dispatchedQty"   NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "deliveredQty"    NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "returnedQty"     NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "sourceLineId"    TEXT,
    "unitPrice"       NUMERIC(20, 6),
    "discountPercent" NUMERIC(7, 3),
    "taxId"           TEXT,
    "batchNumber"     TEXT,
    notes             TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetDeliveryOrderLine_order_idx" ON "FleetDeliveryOrderLine" ("deliveryOrderId", "lineNumber");

-- A load = one trip of one truck (or transporter) for one delivery order.
CREATE TABLE "FleetLoad" (
    id                       TEXT           NOT NULL PRIMARY KEY,
    "organizationId"         TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                     TEXT           NOT NULL,
    "deliveryOrderId"        TEXT           NOT NULL REFERENCES "FleetDeliveryOrder" (id),
    sequence                 INTEGER        NOT NULL,
    "plannedDate"            DATE           NOT NULL,
    status                   TEXT           NOT NULL DEFAULT 'planned'
        CHECK (status IN ('planned', 'loading', 'weighed', 'dispatched', 'in_transit', 'delivered', 'closed',
                          'cancelled', 'returned')),
    carrier                  TEXT           NOT NULL DEFAULT 'own' CHECK (carrier IN ('own', 'transporter')),
    "vehicleId"              TEXT REFERENCES "FleetVehicle" (id),
    "trailerId"              TEXT REFERENCES "FleetVehicle" (id),
    "driverId"               TEXT REFERENCES "FleetDriver" (id),
    "transporterPartnerId"   TEXT REFERENCES "Partner" (id),
    "transporterRateId"      TEXT REFERENCES "FleetTransporterRate" (id),
    "transporterPlate"       TEXT,
    "transporterDriverName"  TEXT,
    "freightBasis"           TEXT CHECK ("freightBasis" IS NULL OR "freightBasis" IN ('per_trip', 'per_tonne', 'per_km')),
    "freightRate"            NUMERIC(14, 2),
    "freightAmount"          NUMERIC(16, 2),
    "capacityT"              NUMERIC(12, 3),
    "capacityM3"             NUMERIC(12, 3),
    "plannedTonnes"          NUMERIC(14, 3),
    "plannedM3"              NUMERIC(14, 3),
    "loadedTonnes"           NUMERIC(14, 3),
    "deliveredTonnes"        NUMERIC(14, 3),
    "locationId"             TEXT           NOT NULL REFERENCES "InventoryLocation" (id),
    "costCenterId"           TEXT,
    purpose                  TEXT,
    "startOdometer"          NUMERIC(14, 1),
    "endOdometer"            NUMERIC(14, 1),
    "startReadingId"         TEXT,
    "endReadingId"           TEXT,
    "distanceKm"             NUMERIC(10, 1),
    "distanceSource"         TEXT CHECK ("distanceSource" IS NULL OR "distanceSource" IN ('telematics', 'odometer', 'site', 'manual')),
    -- Weighed / loaded quantity source: manual entry or an external document (1.4b weighbridge ticket).
    "weighSourceType"        TEXT,
    "weighSourceId"          TEXT,
    "weighedNetQty"          NUMERIC(14, 3),
    "weighedUom"             TEXT,
    "stockIssuedAt"          TIMESTAMP(3),
    "stockReturnedAt"        TIMESTAMP(3),
    "deliveryNoteNumber"     TEXT,
    "deliveryNotePrints"     INTEGER        NOT NULL DEFAULT 0,
    "loadingStartedAt"       TIMESTAMP(3),
    "weighedAt"              TIMESTAMP(3),
    "dispatchedAt"           TIMESTAMP(3),
    "departedAt"             TIMESTAMP(3),
    "deliveredAt"            TIMESTAMP(3),
    "closedAt"               TIMESTAMP(3),
    "cancelledAt"            TIMESTAMP(3),
    "returnedAt"             TIMESTAMP(3),
    "cancelReason"           TEXT,
    "returnReason"           TEXT,
    notes                    TEXT,
    "dispatchedBy"           TEXT,
    "createdBy"              TEXT,
    "updatedBy"              TEXT,
    "createdAt"              TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"              TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (carrier = 'own' OR "transporterPartnerId" IS NOT NULL OR status IN ('planned', 'cancelled'))
);
CREATE UNIQUE INDEX "FleetLoad_code_key" ON "FleetLoad" ("organizationId", code);
CREATE UNIQUE INDEX "FleetLoad_note_key" ON "FleetLoad" ("organizationId", "deliveryNoteNumber")
    WHERE "deliveryNoteNumber" IS NOT NULL;
CREATE INDEX "FleetLoad_order_idx" ON "FleetLoad" ("deliveryOrderId", sequence);
CREATE INDEX "FleetLoad_board_idx" ON "FleetLoad" ("organizationId", "plannedDate", status);
CREATE INDEX "FleetLoad_vehicle_idx" ON "FleetLoad" ("organizationId", "vehicleId") WHERE "vehicleId" IS NOT NULL;
CREATE INDEX "FleetLoad_driver_idx" ON "FleetLoad" ("organizationId", "driverId") WHERE "driverId" IS NOT NULL;
CREATE INDEX "FleetLoad_weigh_idx" ON "FleetLoad" ("organizationId", "weighSourceType", "weighSourceId")
    WHERE "weighSourceId" IS NOT NULL;

CREATE TABLE "FleetLoadLine" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "loadId"              TEXT           NOT NULL REFERENCES "FleetLoad" (id) ON DELETE CASCADE,
    "deliveryOrderLineId" TEXT           NOT NULL REFERENCES "FleetDeliveryOrderLine" (id),
    "productId"           TEXT           NOT NULL REFERENCES "Product" (id),
    "uomId"               TEXT,
    "uomFactor"           NUMERIC(20, 6) NOT NULL DEFAULT 1,
    "plannedQty"          NUMERIC(14, 3) NOT NULL CHECK ("plannedQty" > 0),
    "loadedQty"           NUMERIC(14, 3),
    "dispatchedQty"       NUMERIC(14, 3),
    "deliveredQty"        NUMERIC(14, 3),
    "returnedQty"         NUMERIC(14, 3) NOT NULL DEFAULT 0,
    -- Quantity the customer is billed for: dispatched − returned, adjusted by approved discrepancies.
    "billableQty"         NUMERIC(14, 3),
    "tonnesPerUnit"       NUMERIC(14, 6),
    "m3PerUnit"           NUMERIC(14, 6),
    "batchNumber"         TEXT,
    "stockLedgerCode"     TEXT,
    "unitCost"            NUMERIC(20, 6),
    "stockValue"          NUMERIC(20, 6),
    "returnLedgerCode"    TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetLoadLine_load_idx" ON "FleetLoadLine" ("loadId");
CREATE INDEX "FleetLoadLine_order_line_idx" ON "FleetLoadLine" ("deliveryOrderLineId");

CREATE TABLE "FleetProofOfDelivery" (
    id                 TEXT          NOT NULL PRIMARY KEY,
    "organizationId"   TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "loadId"           TEXT          NOT NULL REFERENCES "FleetLoad" (id) ON DELETE CASCADE,
    "receiverName"     TEXT          NOT NULL,
    "receiverPhone"    TEXT,
    "receivedAt"       TIMESTAMP(3)  NOT NULL,
    latitude           NUMERIC(9, 6) CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    longitude          NUMERIC(9, 6) CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    "locationSource"   TEXT          NOT NULL DEFAULT 'none' CHECK ("locationSource" IN ('device', 'telematics', 'none')),
    "signatureFileId"  TEXT,
    "photoFileId"      TEXT,
    notes              TEXT,
    "recordedBy"       TEXT,
    "createdAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetProofOfDelivery_load_key" ON "FleetProofOfDelivery" ("loadId");

CREATE TABLE "FleetDeliveryDiscrepancy" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT           NOT NULL,
    "deliveryOrderId"   TEXT           NOT NULL REFERENCES "FleetDeliveryOrder" (id),
    "loadId"            TEXT           NOT NULL REFERENCES "FleetLoad" (id),
    "loadLineId"        TEXT           NOT NULL REFERENCES "FleetLoadLine" (id),
    "productId"         TEXT           NOT NULL REFERENCES "Product" (id),
    -- shortage / excess: delivered below / above dispatched (net of returns); returned: brought back to the yard.
    kind                TEXT           NOT NULL CHECK (kind IN ('shortage', 'excess', 'returned')),
    reason              TEXT           NOT NULL
        CHECK (reason IN ('spillage', 'weighing_variance', 'damaged', 'customer_rejected', 'wrong_product',
                          'theft', 'site_closed', 'other')),
    "dispatchedQty"     NUMERIC(14, 3) NOT NULL,
    "deliveredQty"      NUMERIC(14, 3) NOT NULL,
    -- Quantity the billable quantity drops by when approved (negative for an excess).
    "differenceQty"     NUMERIC(14, 3) NOT NULL,
    "uomId"             TEXT,
    notes               TEXT,
    status              TEXT           NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    "approvalRequestId" TEXT,
    "raisedBy"          TEXT,
    "decidedBy"         TEXT,
    "decidedAt"         TIMESTAMP(3),
    "decisionNotes"     TEXT,
    "creditNoteId"      TEXT,
    "creditNoteNumber"  TEXT,
    "creditAmount"      NUMERIC(16, 2),
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetDeliveryDiscrepancy_code_key" ON "FleetDeliveryDiscrepancy" ("organizationId", code);
CREATE INDEX "FleetDeliveryDiscrepancy_status_idx" ON "FleetDeliveryDiscrepancy" ("organizationId", status);
CREATE INDEX "FleetDeliveryDiscrepancy_order_idx" ON "FleetDeliveryDiscrepancy" ("deliveryOrderId");

-- Transport charges computed for a delivery order and put on a customer invoice.
CREATE TABLE "FleetDeliveryCharge" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "deliveryOrderId" TEXT           NOT NULL REFERENCES "FleetDeliveryOrder" (id),
    "ruleId"          TEXT REFERENCES "FleetDeliveryChargeRule" (id),
    description       TEXT           NOT NULL,
    basis             TEXT           NOT NULL,
    quantity          NUMERIC(14, 3) NOT NULL,
    rate              NUMERIC(14, 2) NOT NULL,
    amount            NUMERIC(16, 2) NOT NULL,
    "taxId"           TEXT,
    "invoiceId"       TEXT,
    "invoiceNumber"   TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetDeliveryCharge_order_idx" ON "FleetDeliveryCharge" ("deliveryOrderId");

-- 1.2 left "tripId" as plain text on check-outs and claims: it now references the trip (load).
ALTER TABLE "FleetCheckout" ADD CONSTRAINT "FleetCheckout_tripId_fkey" FOREIGN KEY ("tripId")
    REFERENCES "FleetLoad" (id) NOT VALID;
ALTER TABLE "FleetDriverClaim" ADD CONSTRAINT "FleetDriverClaim_tripId_fkey" FOREIGN KEY ("tripId")
    REFERENCES "FleetLoad" (id) NOT VALID;

ALTER TABLE "FleetCustomerSite" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetCustomerSite" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetProductLoadFactor" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetProductLoadFactor" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetTransporterRate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetTransporterRate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDeliveryChargeRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDeliveryChargeRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDeliveryOrder" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDeliveryOrder" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDeliveryOrderLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDeliveryOrderLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetLoad" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetLoad" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetLoadLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetLoadLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetProofOfDelivery" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetProofOfDelivery" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDeliveryDiscrepancy" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDeliveryDiscrepancy" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetDeliveryCharge" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDeliveryCharge" USING (("organizationId" = current_setting('app.org_id', true)));
