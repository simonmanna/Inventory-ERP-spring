-- FX-2 B (owner decision 4, 2026-11-20): rate types, rate approval, per-context rate policies and richer
-- transaction-rate snapshots. Additive only: existing rates become 'official' + 'approved' (they were already in use),
-- and with no policy rows every context resolves exactly as before (official, no approval, stated rates allowed).

-- 1. Rate types and approval on the rate table.
ALTER TABLE "CurrencyRate"
    ADD COLUMN "rateType"   TEXT NOT NULL DEFAULT 'official',
    ADD COLUMN status       TEXT NOT NULL DEFAULT 'approved',
    ADD COLUMN "approvedBy" TEXT,
    ADD COLUMN "approvedAt" TIMESTAMP(3);
ALTER TABLE "CurrencyRate"
    ADD CONSTRAINT "CurrencyRate_rateType_check" CHECK ("rateType" IN ('official', 'bank', 'cash', 'market')),
    ADD CONSTRAINT "CurrencyRate_status_check" CHECK (status IN ('pending', 'approved', 'rejected')),
    ADD CONSTRAINT "CurrencyRate_rate_positive" CHECK (rate > 0) NOT VALID;

DROP INDEX IF EXISTS "CurrencyRate_org_fromCode_toCode_asOf_key";
CREATE UNIQUE INDEX "CurrencyRate_org_pair_asOf_type_key"
    ON "CurrencyRate" ((coalesce("organizationId", '')), "fromCode", "toCode", "asOf", "rateType");

-- 2. Rate policy per (organisation, transaction context). No row = defaults (official, no approval, stated allowed).
CREATE TABLE "FxRatePolicy" (
    id                     TEXT PRIMARY KEY,
    "organizationId"       TEXT NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    context                TEXT NOT NULL,
    "rateType"             TEXT NOT NULL DEFAULT 'official',
    "requireApprovedRate"  BOOLEAN NOT NULL DEFAULT false,
    "statedRateTolerancePct" NUMERIC(9, 4),
    "allowStatedRate"      BOOLEAN NOT NULL DEFAULT true,
    "updatedBy"            TEXT,
    "createdAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FxRatePolicy_context_check" CHECK (context IN ('sales', 'purchase', 'goods_receipt', 'expense',
        'payment_in', 'payment_out', 'treasury', 'deposit', 'revaluation', 'opening_balance')),
    CONSTRAINT "FxRatePolicy_rateType_check" CHECK ("rateType" IN ('official', 'bank', 'cash', 'market')),
    CONSTRAINT "FxRatePolicy_tolerance_check" CHECK ("statedRateTolerancePct" IS NULL OR "statedRateTolerancePct" >= 0)
);
CREATE UNIQUE INDEX "FxRatePolicy_org_context_key" ON "FxRatePolicy" ("organizationId", context);

-- 3. Snapshot: which rate type and context a foreign entry used, the policy rate at that moment, and any override.
ALTER TABLE "JournalEntryFxRate"
    ADD COLUMN "rateType"       TEXT,
    ADD COLUMN context          TEXT,
    ADD COLUMN "policyRate"     NUMERIC(18, 8),
    ADD COLUMN "overrideReason" TEXT,
    ADD COLUMN "approvedBy"     TEXT;

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    EXECUTE 'ALTER TABLE public."FxRatePolicy" ENABLE ROW LEVEL SECURITY';
    EXECUTE format('CREATE POLICY tenant_isolation ON public."FxRatePolicy" USING (%s) WITH CHECK (%s)', expr, expr);
END $$;

-- 4. Permissions (Administrator holds every permission by convention; other roles only by explicit edit).
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['currency:approve', 'currency:rate_override', 'currency:policy']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions
        || ARRAY['currency:approve', 'currency:rate_override', 'currency:policy'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
