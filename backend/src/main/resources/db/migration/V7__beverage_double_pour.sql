-- V7: per-product double-shot size for beverage products (ADR-S004 §3, approved by the owner 2026-10-26).
--
-- Deliberate deviation from the NestJS reference (docs/spec/beverage.md, "Deviations"): "standardPourMl" is the
-- single shot; "doublePourMl" is the double. NULL means "twice the single". Sales still draw ml through menu-item
-- recipes, so this only drives the yield report and the product setup screen; it never moves stock.

ALTER TABLE "Product"
    ADD COLUMN "doublePourMl" NUMERIC(20,6) CHECK ("doublePourMl" IS NULL OR "doublePourMl" > 0);
