-- Phase I2 (audit R-1, review requirement R-A): every stock ledger row records the document unit it was moved in.
--   docUomId     the unit the document line was in (the product's stock unit for base-unit moves)
--   uomFactor    stock (base) units per 1 document unit used for this move
--   docQuantity  this row's quantity in the document unit (quantityChange / uomFactor), signed like quantityChange
--   sourceLineId the document line that caused the move, when the caller names one
-- Nullable: rows written before this migration have no trace.

ALTER TABLE "InventoryLedger" ADD COLUMN "docUomId" TEXT REFERENCES "UnitOfMeasure" (id);
ALTER TABLE "InventoryLedger" ADD COLUMN "uomFactor" NUMERIC(20, 10);
ALTER TABLE "InventoryLedger" ADD COLUMN "docQuantity" NUMERIC(20, 6);
ALTER TABLE "InventoryLedger" ADD COLUMN "sourceLineId" TEXT;
