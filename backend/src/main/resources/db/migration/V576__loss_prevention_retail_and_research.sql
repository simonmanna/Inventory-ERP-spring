-- V576: loss prevention LP5 (retail checkout and store room), LP6b (camera metrics) and LP7 (activity research).
-- Briefs: docs/phases/LP5-retail-checkout.md, docs/phases/LP6b-camera-metrics.md, docs/phases/LP7-activity-research.md.

-- LP5: one more reconciliation engine.
ALTER TABLE "LpWindow" DROP CONSTRAINT IF EXISTS "LpWindow_engine_check";
ALTER TABLE "LpWindow" ADD CONSTRAINT "LpWindow_engine_check"
    CHECK (engine IN ('cashier', 'bar', 'cashier_vision', 'bar_pour', 'kitchen_flow', 'service_vision',
        'retail_vision'));

-- LP5 and LP6b: a zone over the scanner of a checkout, a zone over a queue, and a dining zone linked to one table.
ALTER TABLE "LpZone" DROP CONSTRAINT IF EXISTS "LpZone_zoneType_check";
ALTER TABLE "LpZone" ADD CONSTRAINT "LpZone_zoneType_check"
    CHECK ("zoneType" IN ('cash_drawer', 'checkout', 'bar_counter', 'kitchen_pass', 'dining', 'cafe_counter',
        'storage', 'entrance', 'scan_area', 'queue'));
ALTER TABLE "LpZone" DROP CONSTRAINT IF EXISTS "LpZone_linkedType_check";
ALTER TABLE "LpZone" ADD CONSTRAINT "LpZone_linkedType_check"
    CHECK ("linkedType" IS NULL OR "linkedType" IN ('till', 'station', 'table_area', 'counter', 'location', 'table'));

-- LP7: what a person who reviewed a recognized activity (vision.activity) said about it. Append-only: a second
-- review is a new row, and the latest one counts. Research only: no rule reads it.
CREATE TABLE "LpActivityReview" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT,
    "eventId"        TEXT         NOT NULL REFERENCES "LpEvent" (id) ON DELETE CASCADE,
    -- Copied from the event, so the research summary needs no payload reads.
    activity         TEXT         NOT NULL,
    recognizer       TEXT         NOT NULL,
    verdict          TEXT         NOT NULL CHECK (verdict IN ('correct', 'incorrect', 'unsure')),
    -- The activity it really was, when the verdict is incorrect and the reviewer knows.
    label            TEXT         CHECK (label IS NULL OR label ~ '^[a-z][a-z0-9_]{0,63}$'),
    note             TEXT         CHECK (note IS NULL OR length(note) <= 1000),
    "reviewedBy"     TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpActivityReview_event_idx" ON "LpActivityReview" ("eventId", "createdAt" DESC);
CREATE INDEX "LpActivityReview_org_idx" ON "LpActivityReview" ("organizationId", recognizer, activity);
CREATE TRIGGER "LpActivityReview_append_only" BEFORE UPDATE ON "LpActivityReview"
    FOR EACH ROW EXECUTE FUNCTION lp_append_only();

ALTER TABLE "LpActivityReview" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "LpActivityReview"
    USING (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));

-- LP6b: camera observations already in the ledger are computed into the metric summaries by the first runs.
INSERT INTO "LpMetricDay" (id, "organizationId", "branchId", "businessDate")
SELECT gen_random_uuid()::text, e."organizationId", coalesce(e."branchId", ''), e."businessDate"
FROM "LpEvent" e
WHERE e.type IN ('vision.line_crossed', 'vision.dwell', 'vision.occupancy', 'vision.gap')
GROUP BY e."organizationId", coalesce(e."branchId", ''), e."businessDate"
ON CONFLICT ("organizationId", "branchId", "businessDate") DO UPDATE
    SET state = 'dirty', version = "LpMetricDay".version + 1, "dirtyAt" = CURRENT_TIMESTAMP;
