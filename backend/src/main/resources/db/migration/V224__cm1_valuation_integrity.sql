-- V224: Cost Management CM-1 (ADR-S031) — valuation integrity, cost lineage, revaluation and NRV.

-- Value-only stock ledger rows: a cost change with no quantity change (landed cost, standard activation, policy
-- migration, revaluation, NRV, material ledger, price-difference release). quantityChange = 0 and the signed amount
-- is in totalValue, so the historical valuation and the GL tie-out (by source) include every value change.
ALTER TYPE "StockMoveType" ADD VALUE IF NOT EXISTS 'cost_adjustment';

-- Carrying-amount component of a value-only row that is NOT held in the quant's unit cost (adjunct to stock):
-- 'price_difference' (STANDARD items: actual − standard still on hand) or 'nrv_allowance' (IAS 2 write-down).
ALTER TABLE "InventoryLedger" ADD COLUMN IF NOT EXISTS "valuationComponent" TEXT;
-- A value-only row (quantity 0) carries a signed amount; quantity movements stay non-negative.
ALTER TABLE "InventoryLedger" DROP CONSTRAINT IF EXISTS "InventoryLedger_non_negative_value_check";
ALTER TABLE "InventoryLedger" ADD CONSTRAINT "InventoryLedger_non_negative_value_check"
    CHECK ((("totalValue" >= 0) OR ("quantityChange" = 0)) AND ("unitCost" >= 0)) NOT VALID;
ALTER TABLE "InventoryLedger" ADD CONSTRAINT "InventoryLedger_valuationComponent"
    CHECK ("valuationComponent" IS NULL OR "valuationComponent" IN ('price_difference', 'nrv_allowance'));
CREATE INDEX IF NOT EXISTS "InventoryLedger_component_idx" ON "InventoryLedger" ("organizationId", "productId")
    WHERE "valuationComponent" IS NOT NULL;
CREATE INDEX IF NOT EXISTS "InventoryLedger_pool_idx"
    ON "InventoryLedger" ("organizationId", "productId", "locationId", "createdAt");

-- One value adjustment applied to a cost layer (the receipt ledger row), distributed over lineage: still on hand vs
-- each consumption since. The original layer cost (InventoryLedger.unitCost of the receipt) is never rewritten.
CREATE TABLE "CostValueAdjustment" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "adjustmentType"   TEXT NOT NULL,
    "sourceType"       TEXT NOT NULL,
    "sourceId"         TEXT NOT NULL,
    "productId"        TEXT NOT NULL REFERENCES "Product" (id),
    "variantId"        TEXT,
    "locationId"       TEXT NOT NULL,
    "batchId"          TEXT,
    "layerLedgerId"    TEXT,
    amount             NUMERIC(20, 6) NOT NULL,
    "onHandAmount"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "consumedAmount"   NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "valuationComponent" TEXT,
    "openAmount"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "anchorLedgerId"   TEXT,
    "journalEntryId"   TEXT,
    "reversedAt"       TIMESTAMP(3),
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CostValueAdjustment_type" CHECK ("adjustmentType" IN ('landed_cost', 'revaluation', 'nrv',
        'standard_change', 'std_price_difference', 'ml_price_diff', 'cost_adjustment', 'policy_migration',
        'price_difference_release'))
);
CREATE INDEX "CostValueAdjustment_source_idx" ON "CostValueAdjustment" ("organizationId", "sourceType", "sourceId");
CREATE INDEX "CostValueAdjustment_open_idx" ON "CostValueAdjustment" ("organizationId", "productId")
    WHERE "openAmount" <> 0;

CREATE TABLE "CostValueAdjustmentLine" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "adjustmentId"        TEXT NOT NULL REFERENCES "CostValueAdjustment" (id) ON DELETE CASCADE,
    "consumptionLedgerId" TEXT,
    target                TEXT NOT NULL,
    "targetRef"           TEXT,
    amount                NUMERIC(20, 6) NOT NULL,
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CostValueAdjustmentLine_target" CHECK (target IN ('on_hand', 'cogs', 'wip', 'scrap', 'transfer',
        'negative_stock'))
);
-- AD-2a: one share per (adjustment, consumption) — re-running a distribution is a no-op.
CREATE UNIQUE INDEX "CostValueAdjustmentLine_unique"
    ON "CostValueAdjustmentLine" ("adjustmentId", COALESCE("consumptionLedgerId", '~'), target);

-- Inventory revaluation and NRV review documents (draft → posted → reversed).
CREATE TABLE "InventoryRevaluation" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    number            TEXT NOT NULL,
    kind              TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'draft',
    "revaluationDate" DATE NOT NULL,
    reason            TEXT,
    "accountId"       TEXT,
    "totalAmount"     NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "journalEntryId"  TEXT,
    "reversalEntryId" TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "postedAt"        TIMESTAMP(3),
    "postedBy"        TEXT,
    "reversedAt"      TIMESTAMP(3),
    CONSTRAINT "InventoryRevaluation_number" UNIQUE ("organizationId", number),
    CONSTRAINT "InventoryRevaluation_kind" CHECK (kind IN ('revaluation', 'nrv')),
    CONSTRAINT "InventoryRevaluation_status" CHECK (status IN ('draft', 'posted', 'reversed'))
);

CREATE TABLE "InventoryRevaluationLine" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "revaluationId"      TEXT NOT NULL REFERENCES "InventoryRevaluation" (id) ON DELETE CASCADE,
    "productId"          TEXT NOT NULL REFERENCES "Product" (id),
    "variantId"          TEXT,
    "locationId"         TEXT NOT NULL,
    quantity             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitCostBefore"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitCostAfter"      NUMERIC(20, 6),
    "netRealisableValue" NUMERIC(20, 6),
    "allowanceBefore"    NUMERIC(20, 2) NOT NULL DEFAULT 0,
    amount               NUMERIC(20, 2) NOT NULL DEFAULT 0
);
CREATE INDEX "InventoryRevaluationLine_doc_idx" ON "InventoryRevaluationLine" ("revaluationId");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['CostValueAdjustment', 'CostValueAdjustmentLine', 'InventoryRevaluation',
            'InventoryRevaluationLine'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
