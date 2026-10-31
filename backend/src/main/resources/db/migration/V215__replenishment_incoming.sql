-- PR-1: the replenishment scan counts stock already ordered or requested for the location (incoming) before comparing
-- with the rule minimum. Stored on the alert next to on hand / reserved / available.
ALTER TABLE "InventoryReplenishmentAlert" ADD COLUMN "incoming" NUMERIC(20, 6) NOT NULL DEFAULT 0;
