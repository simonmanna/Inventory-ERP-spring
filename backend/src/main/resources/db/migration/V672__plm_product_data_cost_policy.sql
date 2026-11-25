-- V672: PLM ↔ product master data and accounting (ADR-S086 §4, §5).
--   * productChanges        — a product ECO may change the product's master data (name, description, barcode, weight,
--                              volume, image): the proposed values, applied through the product service.
--   * productChangesApplied — what apply changed, field by field ({field: {from, to}}).
--   * costPolicy            — what applying a BOM ECO does to the product's standard cost: nothing, propose a draft
--                              standard from the new version's roll-up, or propose + approve + activate it (stock on
--                              hand revalued, journal posted).
--   * standardCostId / revaluationJournalId / costResult — the outcome of that policy.

ALTER TABLE "EngineeringChange"
    ADD COLUMN "productChanges" JSONB,
    ADD COLUMN "productChangesApplied" JSONB,
    ADD COLUMN "costPolicy" TEXT NOT NULL DEFAULT 'none',
    ADD COLUMN "standardCostId" TEXT,
    ADD COLUMN "revaluationJournalId" TEXT,
    ADD COLUMN "costResult" JSONB;

ALTER TABLE "EngineeringChange"
    ADD CONSTRAINT "EngineeringChange_cost_policy" CHECK ("costPolicy" IN ('none', 'propose', 'activate'));

CREATE INDEX "EngineeringChange_standard_cost_idx" ON "EngineeringChange" ("organizationId", "standardCostId")
    WHERE "standardCostId" IS NOT NULL;
