-- V219: PR-5 (roadmap 2.3, ADR-S027) — replenishment & MRP completion.
--
-- 1. Replenishment rules get a route: buy (procurement draft purchase request, as before), manufacture (manufacturing
--    production request delivering to the rule's location) or transfer (draft stock transfer from a source location).
--    Alerts remember the document that answered them, whatever its kind.
-- 2. Production request lines may name the location they deliver to (replenishment), so the scan counts them as
--    incoming supply.
-- 3. MRP plans products as make, buy or subcontract; a subcontract planned order explodes like make and firms into a
--    draft production order plus a draft service purchase request to the subcontractor.
-- 4. Per-organization MRP settings: an optional nightly run.

ALTER TABLE "InventoryReplenishmentRule"
    ADD COLUMN route              TEXT NOT NULL DEFAULT 'buy',
    ADD COLUMN "sourceLocationId" TEXT REFERENCES "InventoryLocation" (id),
    ADD CONSTRAINT "InventoryReplenishmentRule_route_chk" CHECK (route IN ('buy', 'manufacture', 'transfer')),
    ADD CONSTRAINT "InventoryReplenishmentRule_source_chk" CHECK (
        (route = 'transfer' AND "sourceLocationId" IS NOT NULL AND "sourceLocationId" <> "locationId")
        OR (route <> 'transfer' AND "sourceLocationId" IS NULL));

ALTER TABLE "InventoryReplenishmentAlert"
    ADD COLUMN route            TEXT NOT NULL DEFAULT 'buy',
    -- purchase_request | production_request | stock_transfer; purchaseRequestId / requestNumber stay filled for buy.
    ADD COLUMN "documentType"   TEXT,
    ADD COLUMN "documentId"     TEXT,
    ADD COLUMN "documentNumber" TEXT;
UPDATE "InventoryReplenishmentAlert"
SET "documentType" = 'purchase_request', "documentId" = "purchaseRequestId", "documentNumber" = "requestNumber"
WHERE "purchaseRequestId" IS NOT NULL;

ALTER TABLE "ProductionRequestLine" ADD COLUMN "deliveryLocationId" TEXT REFERENCES "InventoryLocation" (id);
CREATE INDEX "ProductionRequestLine_delivery_idx" ON "ProductionRequestLine" ("organizationId", "productId",
    "deliveryLocationId") WHERE "deliveryLocationId" IS NOT NULL;

ALTER TABLE "ProductPlanning" DROP CONSTRAINT IF EXISTS "ProductPlanning_procurement_check";
ALTER TABLE "ProductPlanning" ADD CONSTRAINT "ProductPlanning_procurement_check"
    CHECK (procurement IN ('make', 'buy', 'subcontract'));
ALTER TABLE "MrpPlannedOrder" DROP CONSTRAINT IF EXISTS "MrpPlannedOrder_kind_check";
ALTER TABLE "MrpPlannedOrder" ADD CONSTRAINT "MrpPlannedOrder_kind_check"
    CHECK (kind IN ('make', 'buy', 'subcontract'));
-- The service purchase request raised next to the production order when a subcontract planned order is firmed.
ALTER TABLE "MrpPlannedOrder" ADD COLUMN "subcontractRequestId" TEXT;

CREATE TABLE "MrpSettings" (
    "organizationId" TEXT         NOT NULL PRIMARY KEY REFERENCES "Organization" (id) ON DELETE CASCADE,
    "autoRun"        BOOLEAN      NOT NULL DEFAULT false,
    "horizonDays"    INTEGER      NOT NULL DEFAULT 30 CHECK ("horizonDays" BETWEEN 1 AND 730),
    "locationId"     TEXT REFERENCES "InventoryLocation" (id),
    "lastAutoRunAt"  TIMESTAMP(3),
    "lastAutoRunId"  TEXT,
    "updatedBy"      TEXT,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    EXECUTE 'ALTER TABLE public."MrpSettings" ENABLE ROW LEVEL SECURITY';
    EXECUTE format('CREATE POLICY tenant_isolation ON public."MrpSettings" USING (%s) WITH CHECK (%s)', expr, expr);
END $$;
