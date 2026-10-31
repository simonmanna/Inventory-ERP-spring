-- V28: requisition-to-order core (ADR-PROCUREMENT-001, wave 3 / roadmap P1).
-- Additive only: nullable / defaulted columns on parity tables (not exposed in parity JSON) so a request line knows
-- how much was ordered from it and an order line knows which request line it came from; supplier confirmation and
-- close-out fields on the order.

ALTER TABLE "PurchaseRequestLine"
    ADD COLUMN "orderedQuantity"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "requiredDate"        DATE,
    ADD COLUMN "deliveryLocationId"  TEXT,
    ADD COLUMN "preferredSupplierId" TEXT,
    ADD COLUMN "costCenterId"        TEXT,
    ADD COLUMN "projectId"           TEXT,
    ADD COLUMN "demandSource"        TEXT NOT NULL DEFAULT 'manual'
        CHECK ("demandSource" IN ('manual', 'reorder', 'mrp', 'maintenance', 'project')),
    ADD COLUMN "demandRef"           TEXT,
    ADD CONSTRAINT "PurchaseRequestLine_ordered_nonneg" CHECK ("orderedQuantity" >= 0);

ALTER TABLE "PurchaseOrderLine"
    ADD COLUMN "requestLineId"     TEXT,
    ADD COLUMN "cancelledQuantity" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD CONSTRAINT "PurchaseOrderLine_cancelled_nonneg" CHECK ("cancelledQuantity" >= 0);
CREATE INDEX "PurchaseOrderLine_requestLine_idx" ON "PurchaseOrderLine" ("requestLineId")
    WHERE "requestLineId" IS NOT NULL;

ALTER TABLE "PurchaseOrder"
    ADD COLUMN "sentById"          TEXT,
    ADD COLUMN "acknowledgedAt"    TIMESTAMP(3),
    ADD COLUMN "supplierReference" TEXT,
    ADD COLUMN "promisedDate"      DATE,
    ADD COLUMN "closedAt"          TIMESTAMP(3),
    ADD COLUMN "closedById"        TEXT,
    ADD COLUMN "closeReason"       TEXT;
CREATE INDEX "PurchaseOrder_open_delivery_idx" ON "PurchaseOrder" ("organizationId", status, "expectedDeliveryDate")
    WHERE "deletedAt" IS NULL;
