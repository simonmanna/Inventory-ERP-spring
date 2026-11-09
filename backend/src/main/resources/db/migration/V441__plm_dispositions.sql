-- V441: PLM change dispositions and queued switch-over (ADR-S063 §3.1).
--   applyQueued        — apply was requested but waits (effective date in the org's time zone, or use-up of stock);
--                        the hourly PlmEffectivityJob applies it once nothing blocks it any more
--   dispositionResult  — what apply did: stock documents raised (adjustment / transfer / supplier return) and the
--                        open production orders replaced, flagged or kept
ALTER TABLE "EngineeringChange"
    ADD COLUMN "applyQueued"       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "dispositionResult" JSONB;
CREATE INDEX "EngineeringChange_queued_idx" ON "EngineeringChange" ("organizationId")
    WHERE status = 'approved' AND ("applyQueued" OR effectivity = 'date');
