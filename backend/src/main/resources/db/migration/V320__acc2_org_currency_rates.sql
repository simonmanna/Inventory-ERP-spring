-- ACC-2 (B4, docs/accounting/ACC-0-verification.md): exchange rates per organization.
--
-- CurrencyRate had no organizationId: one tenant's rate for (from, to, asOf) overwrote every other tenant's. Rates
-- now carry the organization that recorded them; rows without one (seeds, pre-ACC-2 history) stay shared and are the
-- fallback when an organization has no rate of its own. Posted entries are untouched: each keeps the rate it used in
-- JournalEntryFxRate (V291).
ALTER TABLE "CurrencyRate"
    ADD COLUMN "organizationId" TEXT REFERENCES "Organization" (id) ON DELETE CASCADE,
    ADD COLUMN "createdBy"      TEXT;

DROP INDEX IF EXISTS "CurrencyRate_fromCode_toCode_asOf_key";
CREATE UNIQUE INDEX "CurrencyRate_org_fromCode_toCode_asOf_key"
    ON "CurrencyRate" ((coalesce("organizationId", '')), "fromCode", "toCode", "asOf");
CREATE INDEX "CurrencyRate_organizationId_idx" ON "CurrencyRate" ("organizationId");

-- Tenancy backstop (same policy shape as V214, nullable column → shared rows visible).
ALTER TABLE "CurrencyRate" ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON "CurrencyRate";
CREATE POLICY tenant_isolation ON "CurrencyRate"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true)
        OR "organizationId" IS NULL)
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));
