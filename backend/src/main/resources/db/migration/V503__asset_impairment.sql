-- ADR-S073 (3/3): fixed-asset impairment (IAS 36) and the permissions of the GL automation extensions.
--
-- AssetImpairment records each impairment loss and reversal: the carrying amount before, the recoverable amount,
-- the amount booked (Dr impairment loss / Cr accumulated impairment, reversed for a reversal) and the remaining
-- useful life at that date, from which future straight-line depreciation is re-based.
CREATE TABLE "AssetImpairment" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "assetId"             TEXT           NOT NULL REFERENCES "Asset" (id) ON DELETE CASCADE,
    kind                  TEXT           NOT NULL,
    "impairmentDate"      TIMESTAMP(3)   NOT NULL,
    "carryingBefore"      NUMERIC(20, 6) NOT NULL,
    "recoverableAmount"   NUMERIC(20, 6) NOT NULL,
    amount                NUMERIC(20, 6) NOT NULL,
    "carryingAfter"       NUMERIC(20, 6) NOT NULL,
    "remainingLifeMonths" INTEGER        NOT NULL,
    "depreciationCount"   INTEGER        NOT NULL,
    reason                TEXT,
    "journalEntryId"      TEXT,
    "createdBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AssetImpairment_kind" CHECK (kind IN ('impairment', 'reversal')),
    CONSTRAINT "AssetImpairment_amount" CHECK (amount > 0)
);
CREATE INDEX "AssetImpairment_asset_idx" ON "AssetImpairment" ("organizationId", "assetId", "createdAt");

ALTER TABLE "AssetImpairment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "AssetImpairment"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['recurring_entry:read', 'recurring_entry:manage',
        'deferral:read', 'deferral:manage', 'fixed_asset:impair'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
