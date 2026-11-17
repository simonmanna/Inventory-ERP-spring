-- Review 2026-11-17 (costing review, Tier 3): rework costing. A rework order is 'normal' — the rework is part of the
-- process, so its extra material and conversion stay in the reworked units (the behaviour so far) — or 'abnormal'
-- (IAS 2.16(a)): the units come back at the cost they already carried and the extra cost of the rework is a loss of
-- the period. reworkLoss is what completion expensed, so a reversal can take it back.
ALTER TABLE "ProductionOrder" ADD COLUMN "reworkTreatment" text
    CONSTRAINT "ProductionOrder_reworkTreatment_check" CHECK ("reworkTreatment" IN ('normal', 'abnormal'));
ALTER TABLE "ProductionOrder" ADD COLUMN "reworkLoss" numeric(20, 6) NOT NULL DEFAULT 0;
