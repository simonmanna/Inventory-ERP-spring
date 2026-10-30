-- V961 (placeholder number, renumbered at merge): fleet 1.9 — costing, delivery profitability, transporter freight
-- bills, reminder jobs (ADR-S009 §3 / §6 / §9); hr payroll deductions (additive, fines charged to drivers).
--
-- All changes are additive: one nullable column on the compliance documents (premium / fee for TCO), three new fleet
-- tables, one nullable column on the fine deductions and the widened kind of the hr payroll inputs (earning →
-- earning / deduction) with the account a deduction credits. Existing rows keep their values.

-- ---------------------------------------------------------------- compliance cost (TCO: insurance, licences)

-- Premium / fee paid for the document; spread evenly over issuedOn .. expiresOn in the TCO report.
ALTER TABLE "FleetComplianceDocument" ADD COLUMN "costAmount" NUMERIC(18, 2)
    CHECK ("costAmount" IS NULL OR "costAmount" >= 0);

-- ---------------------------------------------------------------- transporter freight bills

CREATE TABLE "FleetFreightBill" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    -- outbound: closed transporter trips of a period (Dr outbound freight); inbound: freight on a goods receipt,
    -- capitalised as landed cost (ADR-S007 voucher).
    direction             TEXT           NOT NULL DEFAULT 'outbound' CHECK (direction IN ('outbound', 'inbound')),
    "transporterPartnerId" TEXT          NOT NULL REFERENCES "Partner" (id),
    "periodFrom"          DATE,
    "periodTo"            DATE,
    -- draft (trips collected) → billed (supplier invoice matched: AP draft bill + accrual) | disputed (outside the
    -- tolerance, waiting for a decision) → billed; draft / disputed / billed → cancelled.
    status                TEXT           NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'disputed', 'billed', 'cancelled')),
    "tripCount"           INTEGER        NOT NULL DEFAULT 0,
    "totalAmount"         NUMERIC(16, 2) NOT NULL DEFAULT 0,
    "currencyCode"        TEXT,
    "supplierReference"   TEXT,
    "supplierInvoiceDate" DATE,
    "supplierAmount"      NUMERIC(16, 2),
    "varianceAmount"      NUMERIC(16, 2),
    "variancePct"         NUMERIC(9, 3),
    "allowedVariance"     NUMERIC(16, 2),
    "varianceAccepted"    BOOLEAN        NOT NULL DEFAULT false,
    "varianceAcceptedBy"  TEXT,
    "vendorBillId"        TEXT,
    "vendorBillNumber"    TEXT,
    "journalEntryId"      TEXT,
    "postingKey"          TEXT,
    "reversalEntryId"     TEXT,
    -- inbound: the posted goods receipt the freight is capitalised on and the landed cost voucher.
    "goodsReceiptId"      TEXT,
    "landedCostId"        TEXT,
    "branchId"            TEXT,
    "matchedAt"           TIMESTAMP(3),
    "matchedBy"           TEXT,
    "cancelledAt"         TIMESTAMP(3),
    "cancelledBy"         TEXT,
    "cancelReason"        TEXT,
    notes                 TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (direction = 'outbound' OR "goodsReceiptId" IS NOT NULL)
);
CREATE UNIQUE INDEX "FleetFreightBill_org_code_key" ON "FleetFreightBill" ("organizationId", code);
CREATE INDEX "FleetFreightBill_org_status_idx" ON "FleetFreightBill" ("organizationId", status, "createdAt");
CREATE INDEX "FleetFreightBill_partner_idx" ON "FleetFreightBill" ("organizationId", "transporterPartnerId");
-- One live inbound freight charge per receipt + supplier invoice (replays return it).
CREATE UNIQUE INDEX "FleetFreightBill_inbound_key" ON "FleetFreightBill" ("organizationId", "goodsReceiptId",
    "supplierReference") WHERE direction = 'inbound' AND status <> 'cancelled';

CREATE TABLE "FleetFreightBillLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "freightBillId"   TEXT           NOT NULL REFERENCES "FleetFreightBill" (id) ON DELETE CASCADE,
    "tripId"          TEXT           REFERENCES "FleetLoad" (id),
    "deliveryOrderId" TEXT,
    description       TEXT           NOT NULL,
    "freightBasis"    TEXT,
    "freightRate"     NUMERIC(14, 2),
    tonnes            NUMERIC(14, 3),
    "distanceKm"      NUMERIC(10, 1),
    amount            NUMERIC(16, 2) NOT NULL CHECK (amount >= 0),
    "costCenterId"    TEXT,
    -- active while its bill is live; cancelled with the bill (or removed from a draft) — the trip can be billed again.
    status            TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'cancelled')),
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "FleetFreightBillLine_bill_idx" ON "FleetFreightBillLine" ("freightBillId");
-- A trip is billed once.
CREATE UNIQUE INDEX "FleetFreightBillLine_trip_key" ON "FleetFreightBillLine" ("organizationId", "tripId")
    WHERE status = 'active' AND "tripId" IS NOT NULL;

-- ---------------------------------------------------------------- reminder log (dedupe per item per day)

CREATE TABLE "FleetReminderLog" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- compliance_document, driver_document, vehicle_tare, service_due
    "itemType"       TEXT         NOT NULL
        CHECK ("itemType" IN ('compliance_document', 'driver_document', 'vehicle_tare', 'service_due')),
    "itemId"         TEXT         NOT NULL,
    -- itemId plus the document kind (a driver has a licence, a medical and a permit).
    "itemKey"        TEXT         NOT NULL,
    "reminderDate"   DATE         NOT NULL,
    -- expired / expiring (documents, tares) or overdue / due (services).
    state            TEXT         NOT NULL,
    "vehicleId"      TEXT,
    "driverId"       TEXT,
    "dueOn"          DATE,
    title            TEXT         NOT NULL,
    body             TEXT,
    "notificationId" TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FleetReminderLog_item_day_key" ON "FleetReminderLog" ("organizationId", "itemType", "itemKey",
    "reminderDate");
CREATE INDEX "FleetReminderLog_org_date_idx" ON "FleetReminderLog" ("organizationId", "reminderDate");

-- ---------------------------------------------------------------- fines → hr payroll deduction

-- The hr payroll input that deducts the fine from the driver's next payroll run.
ALTER TABLE "FleetFineDeduction" ADD COLUMN "payrollInputId" TEXT;

-- hr: payroll inputs may now be deductions (subtracted from net pay; the payroll journal credits creditAccountId).
ALTER TABLE "HrPayrollInput" DROP CONSTRAINT IF EXISTS "HrPayrollInput_kind_check";
ALTER TABLE "HrPayrollInput" ADD CONSTRAINT "HrPayrollInput_kind_check" CHECK (kind IN ('earning', 'deduction'));
ALTER TABLE "HrPayrollInput" ADD COLUMN "creditAccountId" TEXT;

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "FleetFreightBill" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFreightBill" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetFreightBillLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetFreightBillLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "FleetReminderLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetReminderLog" USING (("organizationId" = current_setting('app.org_id', true)));
