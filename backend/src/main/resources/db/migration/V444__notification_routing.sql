-- V444: notify the responsible people when something happens (ADR-S065).
--
-- EventOutbox."actorId" is the user whose request published the event (null for jobs), so a subscriber can leave the
-- actor out: nobody is told about what they did themselves.
--
-- A NotificationRoute holds one organization's settings for one catalogued event; with no row the catalog defaults
-- apply. A NotificationRouteClaim is one outbox row handled by the router: the primary key stops a retried row (another
-- subscriber failed) from notifying twice.

ALTER TABLE "EventOutbox" ADD COLUMN "actorId" TEXT;

CREATE TABLE "NotificationRoute" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "eventName"      TEXT         NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    -- Keys of the event's parties (assignee, salesperson, requester, ...) who are told.
    parties          TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    -- Everyone holding one of these roles is told as well.
    "roleIds"        TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    -- Named users on top.
    "userIds"        TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    channels         TEXT[]       NOT NULL DEFAULT ARRAY['in_app']::TEXT[]
        CHECK (channels <@ ARRAY['in_app', 'email', 'push', 'sms']::TEXT[]),
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "NotificationRoute_org_event_key" ON "NotificationRoute" ("organizationId", "eventName");

CREATE TABLE "NotificationRouteClaim" (
    "outboxId"       TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "eventName"      TEXT         NOT NULL,
    "recordId"       TEXT,
    recipients       INTEGER      NOT NULL DEFAULT 0,
    "claimedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "NotificationRouteClaim_claimed_idx" ON "NotificationRouteClaim" ("claimedAt");

ALTER TABLE "NotificationRoute" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "NotificationRoute" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "NotificationRouteClaim" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "NotificationRouteClaim"
    USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['notification_routing:read', 'notification_routing:manage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions
        || ARRAY['notification_routing:read', 'notification_routing:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
