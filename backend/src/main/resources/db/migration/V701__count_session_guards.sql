-- V701 (IPR Phase 1b, T12 + T11; count spec sections 4.4 and 6).
--
-- 1. S4: one draft count session per location (any count type). Existing data is never repaired: when an
--    organisation already has two drafts on one location, the index only covers sessions created after this
--    migration ran, and the legacy duplicates stay as they are (the application refuses a new draft next to them).
-- 2. Late count corrections are exactly once per (count line, late ledger row).
-- 3. S1: the column default of the count type becomes 'cycle' (the application always names the type).
-- Additive only; the trigger inventory_ledger_append_only stays enabled.

DO $$
DECLARE
    dup_count integer;
    cutoff timestamp(3);
BEGIN
    SELECT count(*) INTO dup_count FROM (
        SELECT "organizationId", "locationId" FROM "InventoryCountSession"
        WHERE status = 'draft' AND "deletedAt" IS NULL
        GROUP BY "organizationId", "locationId" HAVING count(*) > 1) d;
    IF dup_count = 0 THEN
        CREATE UNIQUE INDEX IF NOT EXISTS "InventoryCountSession_one_draft_per_location"
            ON "InventoryCountSession" ("organizationId", "locationId")
            WHERE status = 'draft' AND "deletedAt" IS NULL;
    ELSE
        cutoff := date_trunc('milliseconds', clock_timestamp())::timestamp(3);
        RAISE NOTICE 'V701: % location(s) already have more than one draft count; the one-draft index covers sessions created after %', dup_count, cutoff;
        EXECUTE format('CREATE UNIQUE INDEX IF NOT EXISTS "InventoryCountSession_one_draft_per_location"'
            || ' ON "InventoryCountSession" ("organizationId", "locationId")'
            || ' WHERE status = ''draft'' AND "deletedAt" IS NULL AND "createdAt" > %L::timestamp(3)', cutoff);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS "InventoryLedger_late_count_correction_once"
    ON "InventoryLedger" ("organizationId", "sourceLineId", "referenceId")
    WHERE "referenceType" = 'inventory_count_late_correction';

ALTER TABLE "InventoryCountSession" ALTER COLUMN "countType" SET DEFAULT 'cycle';
