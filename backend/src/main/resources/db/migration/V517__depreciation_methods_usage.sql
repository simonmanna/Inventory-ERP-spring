-- R14: depreciation methods that do what they say.
--
-- 1. Declining balance takes a configurable factor per asset category (annual rate = factor / useful life in years).
--    The default 2.0 keeps every existing schedule unchanged; double declining stays at 2 regardless.
-- 2. Units of production: an asset's total estimated units over its life, and the units used per period. A run
--    charges (cost - residual) x units this period / total units, stopping at the residual value; an asset without
--    a total or without usage for the period is skipped and reported, never depreciated on straight line.
ALTER TABLE "AssetCategory" ADD COLUMN "decliningFactor" NUMERIC(9, 4) NOT NULL DEFAULT 2.0;
ALTER TABLE "AssetCategory" ADD CONSTRAINT "AssetCategory_decliningFactor_positive" CHECK ("decliningFactor" > 0);

ALTER TABLE "Asset" ADD COLUMN "totalUnits" NUMERIC(18, 6);
ALTER TABLE "Asset" ADD CONSTRAINT "Asset_totalUnits_positive" CHECK ("totalUnits" IS NULL OR "totalUnits" > 0);

CREATE TABLE "AssetUsage" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "assetId"        TEXT           NOT NULL REFERENCES "Asset" (id) ON DELETE CASCADE,
    period           TEXT           NOT NULL,
    units            NUMERIC(18, 6) NOT NULL,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AssetUsage_units" CHECK (units >= 0),
    CONSTRAINT "AssetUsage_period" CHECK (period ~ '^\d{4}-\d{2}$')
);
CREATE UNIQUE INDEX "AssetUsage_asset_period_key" ON "AssetUsage" ("organizationId", "assetId", period);

ALTER TABLE "AssetUsage" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "AssetUsage"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));
