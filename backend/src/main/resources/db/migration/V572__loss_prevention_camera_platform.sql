-- V572 (written as V523): loss prevention LP1, the camera platform (ADR-S077 §1, §7, §8; docs/phases/LP1-camera-platform.md). Edge agents
-- on the customer's network read the cameras; the backend never does. Camera addresses and passwords stay on the edge
-- box: no column here can hold one. Video stays on site; a clip reaches the backend only when it is requested.

-- An edge agent: one box (or one container) at one branch. Its credentials are an API client with the lp:ingest scope.
CREATE TABLE "LpAgent" (
    id                      TEXT         NOT NULL PRIMARY KEY,
    "organizationId"        TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"              TEXT         NOT NULL,
    name                    TEXT         NOT NULL,
    status                  TEXT         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'revoked')),
    health                  TEXT         NOT NULL DEFAULT 'unknown'
        CHECK (health IN ('unknown', 'online', 'degraded', 'offline')),
    "healthReasons"         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- When the health last changed (alerts wait this long before telling anyone).
    "healthSince"           TIMESTAMP(3),
    -- SHA-256 of the one-time enrolment code; cleared once used.
    "enrolCodeHash"         TEXT,
    "enrolCodeExpiresAt"    TIMESTAMP(3),
    "apiClientId"           TEXT,
    hostname                TEXT,
    "agentVersion"          TEXT,
    "modelVersion"          TEXT,
    -- What the agent should run (staged per branch by setting it on that branch's agents first).
    "desiredModelVersion"   TEXT,
    "desiredAgentVersion"   TEXT,
    "configVersion"         INTEGER      NOT NULL DEFAULT 1,
    "reportedConfigVersion" INTEGER,
    "lastHeartbeatAt"       TIMESTAMP(3),
    -- The agent's watermark: every observation up to this instant has been sent.
    "observedThrough"       TIMESTAMP(3),
    "clockDriftMs"          INTEGER,
    "diskFreeBytes"         BIGINT,
    "diskTotalBytes"        BIGINT,
    "queueLength"           INTEGER,
    -- When an advancing watermark last made the vision windows of the branch due again.
    "coverageMarkedAt"      TIMESTAMP(3),
    "enrolledAt"            TIMESTAMP(3),
    "revokedAt"             TIMESTAMP(3),
    "revokedBy"             TEXT,
    "revokeReason"          TEXT,
    "createdBy"             TEXT,
    "createdAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpAgent_org_branch_idx" ON "LpAgent" ("organizationId", "branchId");
CREATE UNIQUE INDEX "LpAgent_enrolCode_key" ON "LpAgent" ("enrolCodeHash") WHERE "enrolCodeHash" IS NOT NULL;
CREATE UNIQUE INDEX "LpAgent_apiClient_key" ON "LpAgent" ("apiClientId") WHERE "apiClientId" IS NOT NULL;

-- Heartbeats, kept for heartbeatRetentionDays for the agent screen.
CREATE TABLE "LpAgentHeartbeat" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "agentId"         TEXT         NOT NULL REFERENCES "LpAgent" (id) ON DELETE CASCADE,
    "receivedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "agentVersion"    TEXT,
    "modelVersion"    TEXT,
    "configVersion"   INTEGER,
    "clockDriftMs"    INTEGER,
    "diskFreeBytes"   BIGINT,
    "diskTotalBytes"  BIGINT,
    "queueLength"     INTEGER,
    "observedThrough" TIMESTAMP(3),
    cameras           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    health            TEXT         NOT NULL,
    "healthReasons"   JSONB        NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX "LpAgentHeartbeat_agent_idx" ON "LpAgentHeartbeat" ("agentId", "receivedAt" DESC);

-- The agent's timeline: enrolment, health changes, revocation. Append-only.
CREATE TABLE "LpAgentEvent" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "agentId"        TEXT         NOT NULL REFERENCES "LpAgent" (id) ON DELETE CASCADE,
    kind             TEXT         NOT NULL CHECK (kind IN ('created', 'code_issued', 'enrolled', 'online', 'degraded',
        'offline', 'revoked', 'updated')),
    "actorId"        TEXT,
    data             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpAgentEvent_agent_idx" ON "LpAgentEvent" ("agentId", "createdAt" DESC);
CREATE TRIGGER "LpAgentEvent_append_only" BEFORE UPDATE ON "LpAgentEvent"
    FOR EACH ROW EXECUTE FUNCTION lp_append_only();

-- A camera: a display name and the alias the edge box maps to a stream address. No address, no password.
CREATE TABLE "LpCamera" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT          NOT NULL,
    "agentId"        TEXT          NOT NULL REFERENCES "LpAgent" (id) ON DELETE CASCADE,
    name             TEXT          NOT NULL,
    "streamAlias"    TEXT          NOT NULL CHECK ("streamAlias" ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    enabled          BOOLEAN       NOT NULL DEFAULT true,
    -- false: the agent keeps no video and runs no detection for it (recorded on the NVR only).
    analysed         BOOLEAN       NOT NULL DEFAULT true,
    notes            TEXT,
    -- As last reported by the agent.
    "streamState"    TEXT          CHECK ("streamState" IS NULL OR "streamState" IN ('ok', 'connecting', 'lost', 'disabled')),
    fps              NUMERIC(6, 2),
    "lastFrameAt"    TIMESTAMP(3),
    -- The camera's watermark: every observation of this camera up to this instant has been sent. It stops while the
    -- stream is lost, so a rule never reads a lost picture as "nobody there".
    "observedThrough" TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpCamera_org_branch_idx" ON "LpCamera" ("organizationId", "branchId");
CREATE UNIQUE INDEX "LpCamera_agent_alias_key" ON "LpCamera" ("agentId", "streamAlias");

-- A zone: a polygon on a camera picture with one purpose, linked to a till, station, table area or location.
CREATE TABLE "LpZone" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT         NOT NULL,
    "cameraId"       TEXT         NOT NULL REFERENCES "LpCamera" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    "zoneType"       TEXT         NOT NULL CHECK ("zoneType" IN ('cash_drawer', 'checkout', 'bar_counter',
        'kitchen_pass', 'dining', 'cafe_counter', 'storage', 'entrance')),
    -- [[x, y], …], each from 0 to 1. Two points are a counting line from A to B.
    polygon          JSONB        NOT NULL CHECK (jsonb_typeof(polygon) = 'array'),
    -- For a counting line: the side of A → B an object enters when it crosses "in".
    "inSide"         TEXT         CHECK ("inSide" IS NULL OR "inSide" IN ('left', 'right')),
    "linkedType"     TEXT         CHECK ("linkedType" IS NULL OR "linkedType" IN ('till', 'station', 'table_area',
        'counter', 'location')),
    "linkedId"       TEXT,
    params           JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(params) = 'object'),
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    "retiredAt"      TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpZone_camera_idx" ON "LpZone" ("cameraId");
CREATE INDEX "LpZone_org_branch_type_idx" ON "LpZone" ("organizationId", "branchId", "zoneType");

-- Work for an agent, fetched by long poll. Snapshot, calibration, clip upload, live view, configuration change.
CREATE TABLE "LpAgentCommand" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "agentId"        TEXT         NOT NULL REFERENCES "LpAgent" (id) ON DELETE CASCADE,
    kind             TEXT         NOT NULL CHECK (kind IN ('config_changed', 'snapshot', 'calibrate', 'clip_upload',
        'live_start', 'live_stop')),
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    state            TEXT         NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'delivered', 'done', 'failed', 'expired')),
    result           JSONB,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deliveredAt"    TIMESTAMP(3),
    "completedAt"    TIMESTAMP(3),
    "expiresAt"      TIMESTAMP(3) NOT NULL
);
CREATE INDEX "LpAgentCommand_agent_state_idx" ON "LpAgentCommand" ("agentId", state, "createdAt");

-- A clip or still kept by an agent. Registered when captured (with its SHA-256); uploaded only when requested.
CREATE TABLE "LpEvidence" (
    id                    TEXT         NOT NULL PRIMARY KEY,
    "organizationId"      TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"            TEXT         NOT NULL,
    "agentId"             TEXT         NOT NULL REFERENCES "LpAgent" (id) ON DELETE CASCADE,
    "cameraId"            TEXT         NOT NULL,
    "zoneId"              TEXT,
    -- The agent's own id of a flagged clip; null for a clip cut from the buffer on request.
    "clipId"              TEXT,
    kind                  TEXT         NOT NULL DEFAULT 'clip' CHECK (kind IN ('clip', 'snapshot')),
    origin                TEXT         NOT NULL CHECK (origin IN ('flagged', 'range')),
    "startedAt"           TIMESTAMP(3) NOT NULL,
    "endedAt"             TIMESTAMP(3) NOT NULL,
    -- Taken at capture (or when cut from the buffer), checked at upload and at every view.
    sha256                TEXT         CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    "byteSize"            BIGINT,
    "contentType"         TEXT,
    state                 TEXT         NOT NULL DEFAULT 'on_agent' CHECK (state IN ('on_agent', 'requested',
        'uploading', 'uploaded', 'missing', 'integrity_failed', 'deleted')),
    "observationEventIds" TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    "requestedAt"         TIMESTAMP(3),
    "requestedBy"         TEXT,
    "requestReason"       TEXT,
    "receivedBytes"       BIGINT       NOT NULL DEFAULT 0,
    "uploadedAt"          TIMESTAMP(3),
    "storageKey"          TEXT,
    encryption            TEXT,
    "retainUntil"         TIMESTAMP(3),
    "deletedAt"           TIMESTAMP(3),
    "deleteReason"        TEXT,
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("endedAt" >= "startedAt")
);
CREATE UNIQUE INDEX "LpEvidence_agent_clip_key" ON "LpEvidence" ("agentId", "clipId") WHERE "clipId" IS NOT NULL;
CREATE INDEX "LpEvidence_org_camera_idx" ON "LpEvidence" ("organizationId", "cameraId", "startedAt" DESC);
CREATE INDEX "LpEvidence_org_state_idx" ON "LpEvidence" ("organizationId", state, "retainUntil");

-- Chunks of an upload in progress. Deleted once the clip is assembled, checked and stored.
CREATE TABLE "LpEvidenceChunk" (
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "evidenceId"     TEXT         NOT NULL REFERENCES "LpEvidence" (id) ON DELETE CASCADE,
    "offset"         BIGINT       NOT NULL CHECK ("offset" >= 0),
    bytes            BYTEA        NOT NULL,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("evidenceId", "offset")
);

-- Which cases a clip belongs to.
CREATE TABLE "LpEvidenceCase" (
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "evidenceId"     TEXT         NOT NULL REFERENCES "LpEvidence" (id) ON DELETE CASCADE,
    "caseId"         TEXT         NOT NULL REFERENCES "LpCase" (id) ON DELETE CASCADE,
    "linkedBy"       TEXT,
    "linkedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("evidenceId", "caseId")
);
CREATE INDEX "LpEvidenceCase_case_idx" ON "LpEvidenceCase" ("caseId");

-- Every view, download, export, request, snapshot and live view, and every deletion. Append-only.
CREATE TABLE "LpEvidenceAccess" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "evidenceId"     TEXT,
    "cameraId"       TEXT,
    "caseId"         TEXT,
    -- Null when the system acted (retention, an upload).
    "userId"         TEXT,
    action           TEXT         NOT NULL CHECK (action IN ('view', 'download', 'export', 'request', 'snapshot',
        'live_view_start', 'live_view_stop', 'uploaded', 'integrity_failed', 'missing', 'deleted')),
    detail           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpEvidenceAccess_evidence_idx" ON "LpEvidenceAccess" ("evidenceId", "createdAt");
CREATE INDEX "LpEvidenceAccess_org_created_idx" ON "LpEvidenceAccess" ("organizationId", "createdAt" DESC);
CREATE TRIGGER "LpEvidenceAccess_append_only" BEFORE UPDATE ON "LpEvidenceAccess"
    FOR EACH ROW EXECUTE FUNCTION lp_append_only();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['LpAgent', 'LpAgentHeartbeat', 'LpAgentEvent', 'LpCamera', 'LpZone', 'LpAgentCommand',
        'LpEvidence', 'LpEvidenceChunk', 'LpEvidenceCase', 'LpEvidenceAccess']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I'
            || ' USING (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))'
            || ' WITH CHECK (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))', t);
    END LOOP;
END $$;

-- A case's timeline records the clips linked to it.
ALTER TABLE "LpCaseEvent" DROP CONSTRAINT IF EXISTS "LpCaseEvent_kind_check";
ALTER TABLE "LpCaseEvent" ADD CONSTRAINT "LpCaseEvent_kind_check" CHECK (kind IN ('opened', 'viewed', 'assigned', 'note',
    'review_started', 'escalated', 'finding_changed', 'finding_withdrawn', 'closed', 'reopened', 'evidence_linked'));

-- A run of a vision engine records whether part of its window was not observed (a gap, or past the watermark).
ALTER TABLE "LpReconRun" ADD COLUMN "observationGap" BOOLEAN NOT NULL DEFAULT false;

-- Vision observations are found by zone (deviceId) and by clip.
CREATE INDEX "LpEvent_org_device_idx" ON "LpEvent" ("organizationId", "deviceId", "occurredAt")
    WHERE "deviceId" IS NOT NULL;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
VALUES (gen_random_uuid()::text, 'lp:evidence', 'lp', 'evidence', CURRENT_TIMESTAMP)
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['lp:evidence'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
