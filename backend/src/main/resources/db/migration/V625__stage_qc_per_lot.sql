-- Integrity fix 2026-11-24 (manufacturing review, item 1): a stage QC gate is decided per lot.
-- Each gated output posting is one lot: it carries its own QC status, and a pass releases only that posting's
-- quarantined quantity (with its batch). Before, one pass released the whole stage's quarantine, including lots that
-- had failed or were never inspected.
ALTER TABLE "ProductionStagePosting"
    ADD COLUMN "qcStatus" TEXT,
    ADD COLUMN "qcPassDueAt" TIMESTAMP(3),
    ADD COLUMN "qcReference" TEXT,
    ADD COLUMN "qcDecidedAt" TIMESTAMP(3),
    ADD COLUMN "qcDecidedBy" TEXT,
    ADD COLUMN "qcLotId" TEXT,
    ADD COLUMN "releasesPostingId" TEXT;

ALTER TABLE "ProductionStagePosting"
    ADD CONSTRAINT "ProductionStagePosting_qcStatus_check"
        CHECK ("qcStatus" IS NULL OR "qcStatus" IN ('pending', 'curing', 'passed', 'failed'));

CREATE INDEX "ProductionStagePosting_org_stage_qc_idx" ON "ProductionStagePosting" ("organizationId", "stageId")
    WHERE "qcStatus" IS NOT NULL AND "reversedAt" IS NULL;

-- Lots open today: every unreversed gated output posting whose stage still holds quarantined stock takes the stage's
-- status (curing keeps its due time). Output postings of a stage that has passed with nothing held are passed.
UPDATE "ProductionStagePosting" p
SET "qcStatus" = CASE WHEN s."qtyQuarantined" > 0 AND s."qcStatus" IN ('pending', 'curing', 'failed') THEN s."qcStatus"
                      ELSE 'passed' END,
    "qcPassDueAt" = CASE WHEN s."qtyQuarantined" > 0 AND s."qcStatus" = 'curing' THEN s."qcPassDueAt" END,
    "qcReference" = s."qcReference"
FROM "ProductionOrderStage" s
WHERE s.id = p."stageId" AND s."organizationId" = p."organizationId" AND s."qcGate" = true
  AND p.kind = 'output' AND p."reversedAt" IS NULL AND p."qtyGood" > 0;
