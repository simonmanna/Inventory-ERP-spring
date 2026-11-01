-- V228: Cost Management CM-5 (ADR-S035) — quarry area dimensions (site → pit → zone → bench → face), IFRIC 20
-- stripping-activity assets and IAS 37 rehabilitation provisions with four distinct movements plus utilisation.

-- Areas: the existing zone hierarchy gains the pit level, a lifecycle, disturbed area, reserves and the IFRIC 20
-- ore-body component it belongs to.
ALTER TABLE "QuarryZone" DROP CONSTRAINT IF EXISTS "QuarryZone_kind_check";
ALTER TABLE "QuarryZone" ADD CONSTRAINT "QuarryZone_kind_check" CHECK (kind IN ('pit', 'zone', 'bench', 'face'));
ALTER TABLE "QuarryZone" ADD COLUMN "areaStatus" TEXT NOT NULL DEFAULT 'production';
ALTER TABLE "QuarryZone" ADD CONSTRAINT "QuarryZone_areaStatus" CHECK ("areaStatus" IN ('planned', 'development',
    'production', 'exhausted', 'rehabilitating', 'rehabilitated'));
ALTER TABLE "QuarryZone" ADD COLUMN "disturbedAreaM2" NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE "QuarryZone" ADD COLUMN "reservesTonnes" NUMERIC(16, 3);
ALTER TABLE "QuarryZone" ADD COLUMN "oreBodyComponent" TEXT;

CREATE TABLE "QuarryStrippingAsset" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    code                  TEXT NOT NULL,
    "siteId"              TEXT NOT NULL REFERENCES "QuarrySite" (id),
    "zoneId"              TEXT NOT NULL REFERENCES "QuarryZone" (id),
    component             TEXT NOT NULL,
    "expectedOreTonnes"   NUMERIC(16, 3) NOT NULL CHECK ("expectedOreTonnes" > 0),
    "expectedWasteTonnes" NUMERIC(16, 3) NOT NULL CHECK ("expectedWasteTonnes" >= 0),
    "benefitProbable"     BOOLEAN NOT NULL,
    "componentIdentified" BOOLEAN NOT NULL,
    "costMeasurable"      BOOLEAN NOT NULL,
    assessment            TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'proposed',
    capitalised           NUMERIC(20, 2) NOT NULL DEFAULT 0,
    amortised             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "amortisedTonnes"     NUMERIC(16, 3) NOT NULL DEFAULT 0,
    "proposedBy"          TEXT,
    "approvedBy"          TEXT,
    "approvedAt"          TIMESTAMP(3),
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "QuarryStrippingAsset_code" UNIQUE ("organizationId", code),
    CONSTRAINT "QuarryStrippingAsset_status" CHECK (status IN ('proposed', 'approved', 'closed'))
);

CREATE TABLE "QuarryStrippingMovement" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "assetId"          TEXT NOT NULL REFERENCES "QuarryStrippingAsset" (id),
    type               TEXT NOT NULL,
    "periodFrom"       DATE NOT NULL,
    "periodTo"         DATE NOT NULL,
    "strippingCost"    NUMERIC(20, 2),
    "wasteTonnes"      NUMERIC(16, 3),
    "oreTonnes"        NUMERIC(16, 3) NOT NULL DEFAULT 0,
    "inventoryShare"   NUMERIC(20, 2) NOT NULL DEFAULT 0,
    amount             NUMERIC(20, 2) NOT NULL,
    "creditAccountId"  TEXT,
    "journalEntryId"   TEXT,
    "reversalEntryId"  TEXT,
    status             TEXT NOT NULL DEFAULT 'posted',
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"       TIMESTAMP(3),
    CONSTRAINT "QuarryStrippingMovement_type" CHECK (type IN ('capitalise', 'amortise')),
    CONSTRAINT "QuarryStrippingMovement_status" CHECK (status IN ('posted', 'reversed'))
);
CREATE INDEX "QuarryStrippingMovement_asset_idx" ON "QuarryStrippingMovement" ("assetId", "createdAt");

CREATE TABLE "QuarryRehabObligation" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id),
    code                TEXT NOT NULL,
    "siteId"            TEXT NOT NULL REFERENCES "QuarrySite" (id),
    "zoneId"            TEXT NOT NULL REFERENCES "QuarryZone" (id),
    "discountRatePct"   NUMERIC(9, 4) NOT NULL CHECK ("discountRatePct" >= 0 AND "discountRatePct" <= 100),
    "estimatePerM2"     NUMERIC(20, 6),
    description         TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'open',
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "QuarryRehabObligation_code" UNIQUE ("organizationId", code),
    CONSTRAINT "QuarryRehabObligation_status" CHECK (status IN ('open', 'closed'))
);

-- Provision layers by origin: an initial (asset-backed) layer, production-disturbance layers (inventory-backed) and
-- remeasurement layers linked to the layer they change.
CREATE TABLE "QuarryRehabLayer" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "obligationId"     TEXT NOT NULL REFERENCES "QuarryRehabObligation" (id),
    origin             TEXT NOT NULL,
    "linkedLayerId"    TEXT REFERENCES "QuarryRehabLayer" (id),
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "QuarryRehabLayer_origin" CHECK (origin IN ('initial', 'production', 'remeasurement'))
);

CREATE TABLE "QuarryRehabMovement" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "obligationId"        TEXT NOT NULL REFERENCES "QuarryRehabObligation" (id),
    "layerId"             TEXT REFERENCES "QuarryRehabLayer" (id),
    type                  TEXT NOT NULL,
    "movementDate"        DATE NOT NULL,
    "periodFrom"          DATE,
    amount                NUMERIC(20, 2) NOT NULL,
    "assetAmount"         NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "profitOrLoss"        NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "disturbedM2"         NUMERIC(14, 2),
    "impairmentIndicator" BOOLEAN NOT NULL DEFAULT false,
    "creditAccountId"     TEXT,
    notes                 TEXT,
    "journalEntryId"      TEXT,
    "reversalEntryId"     TEXT,
    status                TEXT NOT NULL DEFAULT 'posted',
    "createdBy"           TEXT,
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"          TIMESTAMP(3),
    CONSTRAINT "QuarryRehabMovement_type" CHECK (type IN ('initial', 'unwind', 'production', 'remeasurement',
        'utilisation')),
    CONSTRAINT "QuarryRehabMovement_status" CHECK (status IN ('posted', 'reversed'))
);
CREATE INDEX "QuarryRehabMovement_obligation_idx" ON "QuarryRehabMovement" ("obligationId", "movementDate");

ALTER TABLE "CostValueAdjustment" DROP CONSTRAINT "CostValueAdjustment_type";
ALTER TABLE "CostValueAdjustment" ADD CONSTRAINT "CostValueAdjustment_type" CHECK ("adjustmentType" IN ('landed_cost',
    'revaluation', 'nrv', 'standard_change', 'std_price_difference', 'ml_price_diff', 'cost_adjustment',
    'policy_migration', 'price_difference_release', 'overhead_settlement', 'stripping_amortisation',
    'rehab_production'));

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['QuarryStrippingAsset', 'QuarryStrippingMovement', 'QuarryRehabObligation',
            'QuarryRehabLayer', 'QuarryRehabMovement'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
