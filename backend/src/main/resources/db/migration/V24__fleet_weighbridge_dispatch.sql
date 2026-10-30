-- V951 (placeholder number, renumbered at merge): fleet 1.4b — weighbridge ↔ dispatch wiring.
-- 1. The foreign keys 1.4a left open: WeighbridgeTicket."deliveryOrderId" → FleetDeliveryOrder and "tripId" → FleetLoad.
--    Added NOT VALID (1.4a stored free text there), then validated only when no orphan value exists, so an
--    installation with pre-1.4b text references still migrates; new and changed rows are always checked.
-- 2. One live (open / awaiting second weighing / exception) ticket per trip.
-- 3. How a completed ticket fed its trip ("tripFeedStatus": fed / skipped / failed / cleared + message + time).
-- 4. FleetWeighbridgeRequirement: "weighbridge required for dispatch" per organization (branchId NULL) or branch —
--    off / all products / bulk products / listed product categories (with their sub-categories).

ALTER TABLE "WeighbridgeTicket" ADD CONSTRAINT "WeighbridgeTicket_deliveryOrderId_fkey" FOREIGN KEY ("deliveryOrderId")
    REFERENCES "FleetDeliveryOrder" (id) NOT VALID;
ALTER TABLE "WeighbridgeTicket" ADD CONSTRAINT "WeighbridgeTicket_tripId_fkey" FOREIGN KEY ("tripId")
    REFERENCES "FleetLoad" (id) NOT VALID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM "WeighbridgeTicket" t WHERE t."deliveryOrderId" IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM "FleetDeliveryOrder" o WHERE o.id = t."deliveryOrderId")) THEN
        ALTER TABLE "WeighbridgeTicket" VALIDATE CONSTRAINT "WeighbridgeTicket_deliveryOrderId_fkey";
    END IF;
    IF NOT EXISTS (SELECT 1 FROM "WeighbridgeTicket" t WHERE t."tripId" IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM "FleetLoad" l WHERE l.id = t."tripId")) THEN
        ALTER TABLE "WeighbridgeTicket" VALIDATE CONSTRAINT "WeighbridgeTicket_tripId_fkey";
    END IF;
END $$;

-- FK-side indexes (the 1.4a indexes lead with "organizationId"; these serve the referential checks).
CREATE INDEX "WeighbridgeTicket_deliveryOrderId_fk_idx" ON "WeighbridgeTicket" ("deliveryOrderId")
    WHERE "deliveryOrderId" IS NOT NULL;
CREATE INDEX "WeighbridgeTicket_tripId_fk_idx" ON "WeighbridgeTicket" ("tripId") WHERE "tripId" IS NOT NULL;
CREATE UNIQUE INDEX "WeighbridgeTicket_trip_live_key" ON "WeighbridgeTicket" ("organizationId", "tripId")
    WHERE "tripId" IS NOT NULL AND status IN ('open', 'first_weighed', 'exception');

ALTER TABLE "WeighbridgeTicket"
    ADD COLUMN "tripFeedStatus"  TEXT CHECK ("tripFeedStatus" IS NULL OR "tripFeedStatus" IN ('fed', 'skipped', 'failed',
                                                                                             'cleared')),
    ADD COLUMN "tripFeedMessage" TEXT,
    ADD COLUMN "tripFedAt"       TIMESTAMP(3);

CREATE TABLE "FleetWeighbridgeRequirement" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT REFERENCES "Branch" (id),
    mode             TEXT         NOT NULL CHECK (mode IN ('off', 'all', 'bulk', 'categories')),
    "categoryIds"    TEXT[]       NOT NULL DEFAULT '{}',
    notes            TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (mode <> 'categories' OR cardinality("categoryIds") > 0)
);
CREATE UNIQUE INDEX "FleetWeighbridgeRequirement_scope_key" ON "FleetWeighbridgeRequirement"
    ("organizationId", COALESCE("branchId", ''));
