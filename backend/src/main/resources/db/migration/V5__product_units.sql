-- V5: per-product units of measure and immutable unit snapshots on transaction lines (ADR-S003).
--
-- Deliberate deviation from the NestJS reference (docs/spec/core.md, "Deviations"):
--   1. "ProductUom" lets each product declare its own pack sizes (Box = 24 for drinks, Box = 12 for soap)
--      instead of relying on the organization-wide UnitOfMeasure.factor alone.
--   2. Purchase, sales and return lines store the unit they were entered in ("uomId") and the conversion
--      that was in force at that moment ("uomFactor" = base units per 1 line unit). Stock posting uses the
--      snapshot, so editing a product's units later never changes what an old document means.
-- Lines written before this migration keep NULL snapshots and are posted exactly as before.

CREATE TABLE "ProductUom" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL,
    "productId"      TEXT           NOT NULL REFERENCES "Product"(id) ON DELETE CASCADE,
    "uomId"          TEXT           NOT NULL REFERENCES "UnitOfMeasure"(id),
    -- Base (stock) units of the product per 1 of this unit, e.g. Box of 24 bottles = 24.
    factor           NUMERIC(20,10) NOT NULL CHECK (factor > 0),
    "isPurchase"     BOOLEAN        NOT NULL DEFAULT true,
    "isSales"        BOOLEAN        NOT NULL DEFAULT true,
    -- Optional default prices for this unit; a line can always override them.
    "salePrice"      NUMERIC(20,6),
    "purchasePrice"  NUMERIC(20,6),
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX "ProductUom_organizationId_productId_uomId_key"
    ON "ProductUom" ("organizationId", "productId", "uomId");
CREATE INDEX "ProductUom_organizationId_productId_idx" ON "ProductUom" ("organizationId", "productId");

ALTER TABLE "ProductUom" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductUom"
    USING (("organizationId" = current_setting('app.org_id'::text, true)));

-- Line snapshots. "PurchaseOrderLine"."unitOfMeasureId" already exists and is the PO line unit.
ALTER TABLE "PurchaseOrderLine" ADD COLUMN "uomFactor" NUMERIC(20,10);
ALTER TABLE "GoodsReceiptLine"  ADD COLUMN "uomId" TEXT, ADD COLUMN "uomFactor" NUMERIC(20,10);
ALTER TABLE "DocumentLine"      ADD COLUMN "uomId" TEXT, ADD COLUMN "uomFactor" NUMERIC(20,10);
ALTER TABLE "DebitNoteLine"     ADD COLUMN "uomId" TEXT, ADD COLUMN "uomFactor" NUMERIC(20,10);
ALTER TABLE "InvoiceItem"       ADD COLUMN "uomId" TEXT, ADD COLUMN "uomFactor" NUMERIC(20,10);

ALTER TABLE "PurchaseOrderLine" ADD CONSTRAINT "PurchaseOrderLine_uomFactor_check" CHECK ("uomFactor" > 0);
ALTER TABLE "GoodsReceiptLine"  ADD CONSTRAINT "GoodsReceiptLine_uomFactor_check" CHECK ("uomFactor" > 0);
ALTER TABLE "DocumentLine"      ADD CONSTRAINT "DocumentLine_uomFactor_check" CHECK ("uomFactor" > 0);
ALTER TABLE "DebitNoteLine"     ADD CONSTRAINT "DebitNoteLine_uomFactor_check" CHECK ("uomFactor" > 0);
ALTER TABLE "InvoiceItem"       ADD CONSTRAINT "InvoiceItem_uomFactor_check" CHECK ("uomFactor" > 0);

-- Backfill: the legacy Product.uomConversion ("stock units per 1 purchase unit") was stored but never used
-- for stock. Promote it to a ProductUom row only where the category factors could not have produced the
-- intended conversion anyway (same factor, i.e. "Box" and "Piece" both 1, or different categories with no
-- bridge). Where the category factors already convert (kg -> g), behaviour is left unchanged.
INSERT INTO "ProductUom" (id, "organizationId", "productId", "uomId", factor, "isPurchase", "isSales",
                         "createdAt", "updatedAt")
SELECT gen_random_uuid()::text, p."organizationId", p.id, p."purchaseUomId", p."uomConversion", true, false,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM "Product" p
JOIN "UnitOfMeasure" pu ON pu.id = p."purchaseUomId"
JOIN "UnitOfMeasure" bu ON bu.id = p."uomId"
WHERE p."purchaseUomId" IS NOT NULL
  AND p."purchaseUomId" <> p."uomId"
  AND p."uomConversion" > 0
  AND p."uomConversion" <> 1
  AND (COALESCE(pu."categoryId", pu.category) IS DISTINCT FROM COALESCE(bu."categoryId", bu.category)
       OR pu.factor = bu.factor)
  AND NOT EXISTS (SELECT 1 FROM "ProductUomConversion" c
                  WHERE c."productId" = p.id
                    AND ((c."fromUomId" = p."purchaseUomId" AND c."toUomId" = p."uomId")
                      OR (c."fromUomId" = p."uomId" AND c."toUomId" = p."purchaseUomId")));
