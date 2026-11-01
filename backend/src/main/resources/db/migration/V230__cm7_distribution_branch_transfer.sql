-- V230: Cost Management CM-7 (ADR-S037) — distribution costing (transporter rate cards per m³ / tonne-km with a
-- minimum charge, empty return legs, owned-fleet delivery cost runs) and same-entity branch transfers with
-- inter-branch clearing and capitalisable transfer freight.

ALTER TABLE "FleetTransporterRate" DROP CONSTRAINT IF EXISTS "FleetTransporterRate_basis_check";
ALTER TABLE "FleetTransporterRate" ADD CONSTRAINT "FleetTransporterRate_basis_check"
    CHECK (basis IN ('per_trip', 'per_tonne', 'per_km', 'per_m3', 'per_tonne_km'));
ALTER TABLE "FleetTransporterRate" ADD COLUMN "minimumCharge" NUMERIC(14, 2) CHECK ("minimumCharge" >= 0);

ALTER TABLE "FleetLoad" DROP CONSTRAINT IF EXISTS "FleetLoad_freightBasis_check";
ALTER TABLE "FleetLoad" ADD CONSTRAINT "FleetLoad_freightBasis_check"
    CHECK ("freightBasis" IS NULL OR "freightBasis" IN ('per_trip', 'per_tonne', 'per_km', 'per_m3', 'per_tonne_km'));
-- Loaded distance stays "distanceKm"; the empty return leg is recorded beside it.
ALTER TABLE "FleetLoad" ADD COLUMN "emptyKm" NUMERIC(10, 1) CHECK ("emptyKm" IS NULL OR "emptyKm" >= 0);

CREATE TABLE "DeliveryCostRun" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    number            TEXT NOT NULL,
    "periodFrom"      DATE NOT NULL,
    "periodTo"        DATE NOT NULL,
    "branchId"        TEXT REFERENCES "Branch" (id),
    "emptyLegPolicy"  TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'posted',
    total             NUMERIC(20, 2) NOT NULL,
    "journalEntryId"  TEXT,
    "reversalEntryId" TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"      TIMESTAMP(3),
    CONSTRAINT "DeliveryCostRun_number" UNIQUE ("organizationId", number),
    CONSTRAINT "DeliveryCostRun_policy" CHECK ("emptyLegPolicy" IN ('loaded_leg', 'pooled')),
    CONSTRAINT "DeliveryCostRun_status" CHECK (status IN ('posted', 'reversed'))
);
CREATE UNIQUE INDEX "DeliveryCostRun_posted" ON "DeliveryCostRun" ("organizationId", coalesce("branchId", ''),
    "periodFrom", "periodTo") WHERE status = 'posted';

CREATE TABLE "DeliveryCostLine" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"           TEXT NOT NULL REFERENCES "DeliveryCostRun" (id) ON DELETE CASCADE,
    "loadId"          TEXT NOT NULL REFERENCES "FleetLoad" (id),
    "vehicleId"       TEXT,
    "customerId"      TEXT,
    "costCenterId"    TEXT,
    "loadedKm"        NUMERIC(12, 1) NOT NULL,
    "emptyKm"         NUMERIC(12, 1) NOT NULL,
    tonnes            NUMERIC(14, 3) NOT NULL,
    "ratePerKm"       NUMERIC(20, 6) NOT NULL,
    amount            NUMERIC(20, 2) NOT NULL,
    "perTonneKm"      NUMERIC(20, 6)
);
CREATE INDEX "DeliveryCostLine_run_idx" ON "DeliveryCostLine" ("runId");
CREATE UNIQUE INDEX "DeliveryCostLine_load" ON "DeliveryCostLine" ("loadId", "runId");

CREATE TABLE "BranchTransfer" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id),
    number              TEXT NOT NULL,
    "productId"         TEXT NOT NULL REFERENCES "Product" (id),
    quantity            NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "fromLocationId"    TEXT NOT NULL REFERENCES "InventoryLocation" (id),
    "toLocationId"      TEXT NOT NULL REFERENCES "InventoryLocation" (id),
    "fromBranchId"      TEXT NOT NULL REFERENCES "Branch" (id),
    "toBranchId"        TEXT NOT NULL REFERENCES "Branch" (id),
    value               NUMERIC(20, 2) NOT NULL,
    freight             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "freightAccountId"  TEXT,
    "useClearing"       BOOLEAN NOT NULL DEFAULT true,
    "transferLedgerId"  TEXT,
    "journalEntryId"    TEXT,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "BranchTransfer_number" UNIQUE ("organizationId", number),
    CONSTRAINT "BranchTransfer_branches" CHECK ("fromBranchId" <> "toBranchId")
);

ALTER TABLE "CostValueAdjustment" DROP CONSTRAINT "CostValueAdjustment_type";
ALTER TABLE "CostValueAdjustment" ADD CONSTRAINT "CostValueAdjustment_type" CHECK ("adjustmentType" IN ('landed_cost',
    'revaluation', 'nrv', 'standard_change', 'std_price_difference', 'ml_price_diff', 'cost_adjustment',
    'policy_migration', 'price_difference_release', 'overhead_settlement', 'stripping_amortisation',
    'rehab_production', 'landed_accrual', 'transfer_freight'));

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['DeliveryCostRun', 'DeliveryCostLine', 'BranchTransfer'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
