-- Still images as evidence (LP1c, ADR-S077 §8). A still is an LpEvidence row of kind 'snapshot' whose startedAt =
-- endedAt is the moment it shows. Two ways in:
--   * origin 'range': the edge box takes the buffered frame nearest that moment and uploads it like a clip, with the
--     SHA-256 it computed (asked for by hand, by the footage view, or by the system for a new case);
--   * origin 'frame': an investigator saved a frame of an uploaded clip from the player. The SHA-256 is taken when
--     the backend receives it; "sourceEvidenceId" and "frameOffsetMs" say which clip and where.
ALTER TABLE "LpEvidence" DROP CONSTRAINT IF EXISTS "LpEvidence_origin_check";
ALTER TABLE "LpEvidence" ADD CONSTRAINT "LpEvidence_origin_check" CHECK (origin IN ('flagged', 'range', 'frame'));
ALTER TABLE "LpEvidence"
    ADD COLUMN "sourceEvidenceId" TEXT REFERENCES "LpEvidence" (id) ON DELETE SET NULL,
    ADD COLUMN "frameOffsetMs"    BIGINT CHECK ("frameOffsetMs" IS NULL OR "frameOffsetMs" >= 0);
ALTER TABLE "LpEvidence" ADD CONSTRAINT "LpEvidence_frame_source_check"
    CHECK (origin <> 'frame' OR (kind = 'snapshot' AND "frameOffsetMs" IS NOT NULL));
CREATE INDEX "LpEvidence_source_idx" ON "LpEvidence" ("sourceEvidenceId") WHERE "sourceEvidenceId" IS NOT NULL;
