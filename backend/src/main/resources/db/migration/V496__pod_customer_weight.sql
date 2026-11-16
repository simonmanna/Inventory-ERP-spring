-- Phase A A2 (plan §7, §11): customer weight evidence on the proof of delivery, with the comparison values frozen
-- when the evidence is recorded, and weight claims as their own discrepancy kind. Nullable columns, no backfill;
-- every CHECK passes when the values are NULL. Evidence never changes the quarry ticket, stock or documents.
-- Numbered V496: V490–V495 are claimed by other open branches (acc-*); renumber at merge if needed.

ALTER TABLE "FleetProofOfDelivery"
    ADD COLUMN "customerGrossKg"        NUMERIC(14, 3) CHECK ("customerGrossKg" IS NULL OR "customerGrossKg" >= 0),
    ADD COLUMN "customerTareKg"         NUMERIC(14, 3) CHECK ("customerTareKg" IS NULL OR "customerTareKg" >= 0),
    ADD COLUMN "customerNetKg"          NUMERIC(14, 3) CHECK ("customerNetKg" IS NULL OR "customerNetKg" >= 0),
    ADD COLUMN "customerTicketRef"      TEXT,
    ADD COLUMN "customerWeighedAt"      TIMESTAMP(3),
    ADD COLUMN "customerWeightFileIds"  TEXT[]         NOT NULL DEFAULT '{}',
    ADD COLUMN "customerWeightNotes"    TEXT,
    ADD COLUMN "customerWeightBy"       TEXT,
    ADD COLUMN "customerWeightAt"       TIMESTAMP(3),
    -- Frozen comparison (plan §7): Q = the feeding ticket's net (or manual gross − tare), V = Q − C, T = tolerance.
    ADD COLUMN "quarryNetKg"            NUMERIC(14, 3) CHECK ("quarryNetKg" IS NULL OR "quarryNetKg" >= 0),
    ADD COLUMN "quarryWeightSource"     TEXT CHECK ("quarryWeightSource" IS NULL
        OR "quarryWeightSource" IN ('ticket', 'manual')),
    ADD COLUMN "quarryTicketId"         TEXT,
    ADD COLUMN "weightVarianceKg"       NUMERIC(14, 3),
    ADD COLUMN "allowedVarianceKg"      NUMERIC(14, 3) CHECK ("allowedVarianceKg" IS NULL OR "allowedVarianceKg" >= 0),
    ADD COLUMN "weightWithinTolerance"  BOOLEAN,
    ADD COLUMN "weightOutcome"          TEXT CHECK ("weightOutcome" IS NULL OR "weightOutcome" IN
        ('within_tolerance', 'heavier', 'disputed', 'awaiting_split', 'no_comparison')),
    ADD COLUMN "unallocatedVarianceKg"  NUMERIC(14, 3) CHECK ("unallocatedVarianceKg" IS NULL
        OR "unallocatedVarianceKg" >= 0),
    ADD CONSTRAINT "FleetProofOfDelivery_customer_weight_check" CHECK (
        ("customerGrossKg" IS NULL OR "customerTareKg" IS NULL OR "customerGrossKg" > "customerTareKg")
        AND ("customerGrossKg" IS NULL OR "customerTareKg" IS NULL OR "customerNetKg" IS NULL
            OR "customerNetKg" = "customerGrossKg" - "customerTareKg"));

-- Weight claims (plan §2/§3: Wp / Wc) are a kind of their own; A3 widens the CHECK again (damaged).
ALTER TABLE "FleetDeliveryDiscrepancy" DROP CONSTRAINT "FleetDeliveryDiscrepancy_kind_check";
ALTER TABLE "FleetDeliveryDiscrepancy" ADD CONSTRAINT "FleetDeliveryDiscrepancy_kind_check"
    CHECK (kind IN ('shortage', 'excess', 'returned', 'weight'));
-- The truck-level weight a weight claim carries for its line (kg); NULL for every other kind. withdrawnAt marks a
-- pending weight claim that a corrected customer weight no longer supported (closed as rejected, but not a decision:
-- it never blocks a later correction).
ALTER TABLE "FleetDeliveryDiscrepancy"
    ADD COLUMN "claimKg" NUMERIC(14, 3) CHECK ("claimKg" IS NULL OR "claimKg" >= 0),
    ADD COLUMN "withdrawnAt" TIMESTAMP(3),
    ADD CONSTRAINT "FleetDeliveryDiscrepancy_withdrawn_check"
        CHECK ("withdrawnAt" IS NULL OR (kind = 'weight' AND status = 'rejected'));
