-- ADR-S083 O0 corrections (owner review of 1fe16e54). V593 is applied and is not edited.
--
-- 1. Server-controlled time and sequence evidence for revocation (correction 2):
--    receivedAt = when the server first received an op; lastAcceptedSeq = highest deviceSeq the server applied for a
--    device; revokedAtSeq = lastAcceptedSeq frozen at revocation. A device's own occurredAt is never trusted for
--    acceptance after revocation.
-- 2. Legacy dead letters (written before V593, no failure code) are classified LEGACY_UNKNOWN (never retried
--    server-side; resolve, discard, or correct and resubmit).
-- 3. Supervisor recovery of quarantined ops is recorded on the dead letter (recoveredById/At, gap acknowledgement).

ALTER TABLE "SyncAppliedOp" ADD COLUMN "receivedAt" timestamp(3) without time zone;

ALTER TABLE "SyncOpDeadLetter" ADD COLUMN "receivedAt" timestamp(3) without time zone;
ALTER TABLE "SyncOpDeadLetter" ADD COLUMN "recoveredById" text;
ALTER TABLE "SyncOpDeadLetter" ADD COLUMN "recoveredAt" timestamp(3) without time zone;
ALTER TABLE "SyncOpDeadLetter" ADD COLUMN "recoveryGapAcknowledged" boolean DEFAULT false NOT NULL;
UPDATE "SyncOpDeadLetter" SET "receivedAt" = "createdAt" WHERE "receivedAt" IS NULL;
UPDATE "SyncOpDeadLetter" SET "failureCode" = 'LEGACY_UNKNOWN' WHERE "failureCode" IS NULL;
CREATE INDEX "SyncOpDeadLetter_device_seq_idx" ON "SyncOpDeadLetter" ("organizationId", "deviceId", "deviceSeq");

ALTER TABLE "PosDevice" ADD COLUMN "lastAcceptedSeq" integer DEFAULT 0 NOT NULL;
ALTER TABLE "PosDevice" ADD COLUMN "revokedAtSeq" integer;
UPDATE "PosDevice" SET "lastAcceptedSeq" = coalesce("lastPushSeq", 0);
UPDATE "PosDevice" SET "revokedAtSeq" = "lastAcceptedSeq" WHERE "revokedAt" IS NOT NULL;
