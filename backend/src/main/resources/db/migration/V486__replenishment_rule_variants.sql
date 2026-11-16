-- Phase I2 (audit V-10, owner decision D2): replenishment and reorder rules per product variant.
-- A rule with a variant compares that variant's stock (and supply) with its levels; a rule without one keeps the
-- product total (products without variants, and rules made before this change). Purchase requests, transfers and
-- production requests raised from a variant rule carry the variant.

ALTER TABLE "InventoryReplenishmentRule" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id);
ALTER TABLE "InventoryReplenishmentAlert" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id);

DROP INDEX "InventoryReplenishmentRule_cell_key";
CREATE UNIQUE INDEX "InventoryReplenishmentRule_cell_key" ON "InventoryReplenishmentRule" ("organizationId", "productId",
    COALESCE("variantId", ''), "locationId") WHERE "deletedAt" IS NULL;

ALTER TABLE "ReorderRule" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE CASCADE;

DROP INDEX "ReorderRule_key";
CREATE UNIQUE INDEX "ReorderRule_key" ON "ReorderRule" ("organizationId", "productId", COALESCE("variantId", ''),
    "locationId");
