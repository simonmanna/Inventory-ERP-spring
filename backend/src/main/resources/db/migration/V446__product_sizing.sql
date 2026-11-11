-- V446: sell by size (ADR-S066). A product can be sold by length (m), area (m²) or volume (m³): the document line
-- records pieces × length [× width [× height]] typed in the product's sizing unit, and the server computes the line
-- quantity from them (e.g. granite kerb 3 pcs × 3 m = 9 m; slab 2 pcs × 2.4 m × 1.2 m = 5.76 m²).
-- Variants carry their own standard size and a sales description, so "Granite 1 m" and "Granite 3 m" can be variants
-- with different prices and details.

ALTER TABLE "Product"
    ADD COLUMN "sizingMode"    TEXT CHECK ("sizingMode" IN ('length', 'area', 'volume')),
    ADD COLUMN "sizingUnit"    TEXT NOT NULL DEFAULT 'm' CHECK ("sizingUnit" IN ('m', 'cm', 'mm')),
    ADD COLUMN "defaultLength" NUMERIC(20, 6) CHECK ("defaultLength" > 0),
    ADD COLUMN "defaultWidth"  NUMERIC(20, 6) CHECK ("defaultWidth" > 0),
    ADD COLUMN "defaultHeight" NUMERIC(20, 6) CHECK ("defaultHeight" > 0);

ALTER TABLE "ProductVariant"
    ADD COLUMN length      NUMERIC(20, 6) CHECK (length > 0),
    ADD COLUMN width       NUMERIC(20, 6) CHECK (width > 0),
    ADD COLUMN height      NUMERIC(20, 6) CHECK (height > 0),
    ADD COLUMN description TEXT;

ALTER TABLE "SalesQuotationLine"
    ADD COLUMN pieces NUMERIC(20, 6), ADD COLUMN length NUMERIC(20, 6), ADD COLUMN width NUMERIC(20, 6),
    ADD COLUMN height NUMERIC(20, 6);
ALTER TABLE "SalesOrderLine"
    ADD COLUMN pieces NUMERIC(20, 6), ADD COLUMN length NUMERIC(20, 6), ADD COLUMN width NUMERIC(20, 6),
    ADD COLUMN height NUMERIC(20, 6);
ALTER TABLE "SalesSubmissionLine"
    ADD COLUMN pieces NUMERIC(20, 6), ADD COLUMN length NUMERIC(20, 6), ADD COLUMN width NUMERIC(20, 6),
    ADD COLUMN height NUMERIC(20, 6);
ALTER TABLE "DocumentLine"
    ADD COLUMN pieces NUMERIC(20, 6), ADD COLUMN length NUMERIC(20, 6), ADD COLUMN width NUMERIC(20, 6),
    ADD COLUMN height NUMERIC(20, 6);
