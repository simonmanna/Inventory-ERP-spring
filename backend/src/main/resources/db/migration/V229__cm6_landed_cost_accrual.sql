-- V229: Cost Management CM-6 (ADR-S036) — landed-cost accrual at receipt (estimated rate per supplier, product
-- category, Incoterm or organisation default), cleared in full when the actual voucher posts.

CREATE TABLE "LandedCostAccrualRule" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "scopeType"      TEXT NOT NULL,
    "scopeId"        TEXT NOT NULL DEFAULT '',
    "ratePct"        NUMERIC(9, 4) NOT NULL CHECK ("ratePct" > 0 AND "ratePct" <= 100),
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "LandedCostAccrualRule_scope" CHECK ("scopeType" IN ('default', 'supplier', 'category', 'incoterm'))
);
CREATE UNIQUE INDEX "LandedCostAccrualRule_key" ON "LandedCostAccrualRule" ("organizationId", "scopeType", "scopeId")
    WHERE "isActive";

CREATE TABLE "LandedCostAccrual" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    number             TEXT NOT NULL,
    "goodsReceiptId"   TEXT NOT NULL REFERENCES "GoodsReceiptNote" (id),
    "basisValue"       NUMERIC(20, 2) NOT NULL,
    amount             NUMERIC(20, 2) NOT NULL CHECK (amount > 0),
    status             TEXT NOT NULL DEFAULT 'posted',
    "voucherId"        TEXT REFERENCES "LandedCost" (id),
    "actualAmount"     NUMERIC(20, 2),
    "journalEntryId"   TEXT,
    "clearingEntryId"  TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "clearedAt"        TIMESTAMP(3),
    CONSTRAINT "LandedCostAccrual_number" UNIQUE ("organizationId", number),
    CONSTRAINT "LandedCostAccrual_status" CHECK (status IN ('posted', 'cleared', 'reversed'))
);
CREATE UNIQUE INDEX "LandedCostAccrual_open" ON "LandedCostAccrual" ("organizationId", "goodsReceiptId")
    WHERE status = 'posted';

CREATE TABLE "LandedCostAccrualLine" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "accrualId"        TEXT NOT NULL REFERENCES "LandedCostAccrual" (id) ON DELETE CASCADE,
    "layerLedgerId"    TEXT NOT NULL,
    "productId"        TEXT NOT NULL,
    "ruleId"           TEXT REFERENCES "LandedCostAccrualRule" (id),
    "ratePct"          NUMERIC(9, 4) NOT NULL,
    "basisValue"       NUMERIC(20, 2) NOT NULL,
    amount             NUMERIC(20, 2) NOT NULL
);
CREATE INDEX "LandedCostAccrualLine_accrual_idx" ON "LandedCostAccrualLine" ("accrualId");

ALTER TABLE "CostValueAdjustment" DROP CONSTRAINT "CostValueAdjustment_type";
ALTER TABLE "CostValueAdjustment" ADD CONSTRAINT "CostValueAdjustment_type" CHECK ("adjustmentType" IN ('landed_cost',
    'revaluation', 'nrv', 'standard_change', 'std_price_difference', 'ml_price_diff', 'cost_adjustment',
    'policy_migration', 'price_difference_release', 'overhead_settlement', 'stripping_amortisation',
    'rehab_production', 'landed_accrual'));

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['LandedCostAccrualRule', 'LandedCostAccrual', 'LandedCostAccrualLine'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
