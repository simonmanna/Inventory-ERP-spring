-- Landed cost LC-4 (ADR-S053): accrual management.
--
-- 1. Rules get a name, an effective period, a rate type (percent of value, amount per unit, amount per kg) with its
--    currency, unit and weight basis, and the expected settlement days. Several active rules may exist per scope as
--    long as their periods do not overlap (checked by the service), so the one-active-rule index goes.
-- 2. Accruals clear partially: cleared / remaining amounts and a partially_cleared status; one LandedCostAccrualClearing
--    row per clear keeps the accrual reversal (functional, receipt-date rate) apart from the actual charge (its own
--    currency, amount and recognition rate) and their estimate variance. Realised FX belongs to the payment (PC-0).
-- 3. Product gross weight for per-kg rules on a gross basis.

ALTER TABLE "LandedCostAccrualRule"
    ADD COLUMN name                     TEXT,
    ADD COLUMN "effectiveFrom"          DATE,
    ADD COLUMN "effectiveTo"            DATE,
    ADD COLUMN "rateType"               TEXT NOT NULL DEFAULT 'percent',
    ADD COLUMN rate                     NUMERIC(20, 6),
    ADD COLUMN "currencyCode"           TEXT,
    ADD COLUMN "uomId"                  TEXT,
    ADD COLUMN "weightBasis"            TEXT NOT NULL DEFAULT 'net',
    ADD COLUMN "expectedSettlementDays" INTEGER NOT NULL DEFAULT 30;
ALTER TABLE "LandedCostAccrualRule" ADD CONSTRAINT "LandedCostAccrualRule_rateType"
    CHECK ("rateType" IN ('percent', 'per_unit', 'per_kg'));
ALTER TABLE "LandedCostAccrualRule" ADD CONSTRAINT "LandedCostAccrualRule_weightBasis"
    CHECK ("weightBasis" IN ('net', 'gross'));
ALTER TABLE "LandedCostAccrualRule" ADD CONSTRAINT "LandedCostAccrualRule_period"
    CHECK ("effectiveTo" IS NULL OR "effectiveFrom" IS NULL OR "effectiveTo" >= "effectiveFrom");
ALTER TABLE "LandedCostAccrualRule" ALTER COLUMN "ratePct" DROP NOT NULL;
ALTER TABLE "LandedCostAccrualRule" DROP CONSTRAINT IF EXISTS "LandedCostAccrualRule_ratePct_check";
ALTER TABLE "LandedCostAccrualRule" ADD CONSTRAINT "LandedCostAccrualRule_rate_chk" CHECK (
    ("rateType" = 'percent' AND "ratePct" > 0 AND "ratePct" <= 100)
    OR ("rateType" <> 'percent' AND rate > 0));
DROP INDEX IF EXISTS "LandedCostAccrualRule_key";
CREATE INDEX "LandedCostAccrualRule_scope_idx" ON "LandedCostAccrualRule" ("organizationId", "scopeType", "scopeId")
    WHERE "isActive";
UPDATE "LandedCostAccrualRule" SET name = "scopeType" || coalesce(' ' || nullif("scopeId", ''), '') WHERE name IS NULL;

ALTER TABLE "LandedCostAccrualLine"
    ADD COLUMN "rateType"     TEXT NOT NULL DEFAULT 'percent',
    ADD COLUMN rate           NUMERIC(20, 6),
    ADD COLUMN quantity       NUMERIC(20, 6),
    ADD COLUMN "weightKg"     NUMERIC(20, 6),
    ADD COLUMN "currencyCode" TEXT,
    ADD COLUMN "currencyAmount" NUMERIC(20, 6),
    ADD COLUMN "exchangeRate" NUMERIC(20, 10);
ALTER TABLE "LandedCostAccrualLine" ALTER COLUMN "ratePct" DROP NOT NULL;

ALTER TABLE "LandedCostAccrual" DROP CONSTRAINT "LandedCostAccrual_status";
ALTER TABLE "LandedCostAccrual" ADD CONSTRAINT "LandedCostAccrual_status"
    CHECK (status IN ('posted', 'partially_cleared', 'cleared', 'reversed'));
ALTER TABLE "LandedCostAccrual"
    ADD COLUMN "clearedAmount"   NUMERIC(20, 2) NOT NULL DEFAULT 0,
    ADD COLUMN "remainingAmount" NUMERIC(20, 2),
    ADD COLUMN "expectedSettlementDate" DATE;
UPDATE "LandedCostAccrual" SET "remainingAmount" = CASE WHEN status = 'posted' THEN amount ELSE 0 END,
                               "clearedAmount" = CASE WHEN status = 'cleared' THEN amount ELSE 0 END;
ALTER TABLE "LandedCostAccrual" ALTER COLUMN "remainingAmount" SET NOT NULL;
ALTER TABLE "LandedCostAccrual" ADD CONSTRAINT "LandedCostAccrual_amounts"
    CHECK ("clearedAmount" >= 0 AND "remainingAmount" >= 0 AND "clearedAmount" + "remainingAmount" <= amount);
DROP INDEX IF EXISTS "LandedCostAccrual_open";
CREATE UNIQUE INDEX "LandedCostAccrual_open" ON "LandedCostAccrual" ("organizationId", "goodsReceiptId")
    WHERE status IN ('posted', 'partially_cleared');

CREATE TABLE "LandedCostAccrualClearing" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "accrualId"          TEXT NOT NULL REFERENCES "LandedCostAccrual" (id),
    "voucherId"          TEXT REFERENCES "LandedCost" (id),
    kind                 TEXT NOT NULL CHECK (kind IN ('clear', 'close', 'reverse')),
    "functionalAmount"   NUMERIC(20, 2) NOT NULL,
    "originalCurrency"   TEXT,
    "originalAmount"     NUMERIC(20, 6),
    "originalRate"       NUMERIC(20, 10),
    "actualCurrency"     TEXT,
    "actualAmount"       NUMERIC(20, 6),
    "actualRate"         NUMERIC(20, 10),
    "actualFunctional"   NUMERIC(20, 2),
    variance             NUMERIC(20, 2),
    "journalEntryId"     TEXT,
    details              JSONB,
    "createdBy"          TEXT,
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LandedCostAccrualClearing_accrual_idx" ON "LandedCostAccrualClearing" ("accrualId");
CREATE UNIQUE INDEX "LandedCostAccrualClearing_voucher_uq" ON "LandedCostAccrualClearing" ("accrualId", "voucherId")
    WHERE "voucherId" IS NOT NULL;

-- What each clear took out of each adjustment (adjunct still open, released since, consumed), so successive partial
-- clears add up exactly to what the accrual put in.
CREATE TABLE "LandedCostAccrualClearingLine" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "clearingId"         TEXT NOT NULL REFERENCES "LandedCostAccrualClearing" (id) ON DELETE CASCADE,
    "adjustmentId"       TEXT NOT NULL,
    "onHandAmount"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "adjunctAmount"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "consumedAmount"     NUMERIC(20, 6) NOT NULL DEFAULT 0
);
CREATE INDEX "LandedCostAccrualClearingLine_adj_idx" ON "LandedCostAccrualClearingLine" ("adjustmentId");

ALTER TABLE "Product" ADD COLUMN "grossWeight" NUMERIC(20, 6);

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['LandedCostAccrualClearing', 'LandedCostAccrualClearingLine'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
