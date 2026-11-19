-- V577: loss prevention, camera platform follow-ups (ADR-S077, LP1 §12).
--   1. A camera can be deleted. The row stays (its zones, observations and clips keep pointing at it) with deletedAt
--      set; its stream alias is free again for a new camera of the same agent.
--   2. Live views and stills can be shared by several API instances (app.lossprevention.live.store=database). The
--      tables are UNLOGGED: a crash empties them, which only ends the live views. Frames and stills are encrypted with
--      the evidence key, a session keeps only its latest frame, and a still lives ten minutes.
--   3. The ledger retention job (LpLedgerRetentionService) needs no schema change: it uses LpEvent_org_bdate_type_idx.

ALTER TABLE "LpCamera" ADD COLUMN "deletedAt" TIMESTAMP(3);
ALTER TABLE "LpCamera" ADD COLUMN "deletedBy" TEXT;
DROP INDEX "LpCamera_agent_alias_key";
CREATE UNIQUE INDEX "LpCamera_agent_alias_key" ON "LpCamera" ("agentId", "streamAlias") WHERE "deletedAt" IS NULL;

CREATE UNLOGGED TABLE "LpLiveSession" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "agentId"        TEXT         NOT NULL,
    "cameraId"       TEXT         NOT NULL,
    "connectBy"      TIMESTAMP(3) NOT NULL,
    "endsAt"         TIMESTAMP(3) NOT NULL,
    stopped          BOOLEAN      NOT NULL DEFAULT false,
    -- The latest frame only (encrypted), and how many frames came before it.
    seq              BIGINT       NOT NULL DEFAULT 0,
    frame            BYTEA,
    "lastFrameAt"    TIMESTAMP(3),
    watched          BOOLEAN      NOT NULL DEFAULT false,
    -- Refreshed by every open stream while it runs; a session nobody refreshed for a minute ends.
    "lastViewerAt"   TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpLiveSession_agent_idx" ON "LpLiveSession" ("agentId");

CREATE UNLOGGED TABLE "LpLiveSnapshot" (
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "cameraId"       TEXT         NOT NULL,
    jpeg             BYTEA        NOT NULL,
    "receivedAt"     TIMESTAMP(3) NOT NULL,
    PRIMARY KEY ("organizationId", "cameraId")
);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['LpLiveSession', 'LpLiveSnapshot']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I'
            || ' USING (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))'
            || ' WITH CHECK (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))', t);
    END LOOP;
END $$;

