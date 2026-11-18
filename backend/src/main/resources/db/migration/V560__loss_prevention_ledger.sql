-- V560 (was V520): loss prevention, work package 1 (ADR-S077 §3–§4): the event ledger. One append-only table holds what was
-- recorded (POS, kitchen, cash, stock counts) and, later, what was observed (cameras, sensors). A sender may deliver an
-- event more than once and in any order: (organization, source, sourceEventId) is the key that stores it once.

CREATE TABLE "LpEvent" (
    id                  TEXT          NOT NULL PRIMARY KEY,
    -- Ledger order. Strictly increasing; a reconciliation run records the highest value it read.
    seq                 BIGINT        GENERATED ALWAYS AS IDENTITY,
    "organizationId"    TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"          TEXT,
    -- The sender's own identity for the event, and the system that sent it.
    "eventId"           TEXT          NOT NULL,
    source              TEXT          NOT NULL,
    "sourceEventId"     TEXT          NOT NULL,
    type                TEXT          NOT NULL,
    "schemaVersion"     INTEGER       NOT NULL CHECK ("schemaVersion" >= 1),
    -- Who and what the event is about. Each is optional; a rule states which it needs.
    "deviceId"          TEXT,
    "actorId"           TEXT,
    "transactionId"     TEXT,
    "orderId"           TEXT,
    "ticketId"          TEXT,
    "lineId"            TEXT,
    "sessionId"         TEXT,
    "businessDate"      DATE          NOT NULL,
    -- When it happened (sender clock) and when the ledger stored it.
    "occurredAt"        TIMESTAMP(3)  NOT NULL,
    "ingestedAt"        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- How strong the evidence is, so approximate evidence is never read as definitive.
    "evidenceLevel"     TEXT          NOT NULL CHECK ("evidenceLevel" IN ('item', 'ticket', 'order', 'session', 'zone')),
    "evidenceSource"    TEXT          NOT NULL
        CHECK ("evidenceSource" IN ('pos', 'kds', 'stock', 'camera', 'sensor', 'manual')),
    "observationStatus" TEXT          NOT NULL CHECK ("observationStatus" IN ('observed', 'inferred', 'unavailable')),
    confidence          NUMERIC(5, 4) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    payload             JSONB         NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object')
);

CREATE UNIQUE INDEX "LpEvent_seq_key" ON "LpEvent" (seq);
CREATE UNIQUE INDEX "LpEvent_org_source_key" ON "LpEvent" ("organizationId", source, "sourceEventId");
CREATE UNIQUE INDEX "LpEvent_org_eventId_key" ON "LpEvent" ("organizationId", "eventId");
CREATE INDEX "LpEvent_org_occurred_idx" ON "LpEvent" ("organizationId", "occurredAt" DESC, seq DESC);
CREATE INDEX "LpEvent_org_type_occurred_idx" ON "LpEvent" ("organizationId", type, "occurredAt");
CREATE INDEX "LpEvent_org_transaction_idx" ON "LpEvent" ("organizationId", "transactionId")
    WHERE "transactionId" IS NOT NULL;
CREATE INDEX "LpEvent_org_order_idx" ON "LpEvent" ("organizationId", "orderId") WHERE "orderId" IS NOT NULL;
CREATE INDEX "LpEvent_org_ticket_idx" ON "LpEvent" ("organizationId", "ticketId") WHERE "ticketId" IS NOT NULL;
CREATE INDEX "LpEvent_org_session_idx" ON "LpEvent" ("organizationId", "sessionId") WHERE "sessionId" IS NOT NULL;
CREATE INDEX "LpEvent_org_actor_idx" ON "LpEvent" ("organizationId", "actorId", "occurredAt")
    WHERE "actorId" IS NOT NULL;

-- History is evidence: a stored event is never changed. Deleting stays possible for the retention job.
CREATE FUNCTION lp_event_refuse_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'LpEvent rows are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER "LpEvent_append_only" BEFORE UPDATE ON "LpEvent"
    FOR EACH ROW EXECUTE FUNCTION lp_event_refuse_update();

ALTER TABLE "LpEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "LpEvent"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['lp:view', 'lp:investigate', 'lp:configure', 'lp:ingest']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions
        || ARRAY['lp:view', 'lp:investigate', 'lp:configure', 'lp:ingest'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
