-- V110: scheduled alerts and exception reports (Phase 5, ADR-S021). An alert rule holds one organization's settings for
-- one alert type; with no row the catalog defaults apply. An alert event is one subject raised in one period: the
-- unique key is the claim that stops a repeat, so a subject alerts at most once per period however often the scan runs.

CREATE TABLE "AlertRule" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "alertType"           TEXT           NOT NULL,
    enabled               BOOLEAN        NOT NULL DEFAULT true,
    -- Meaning and unit depend on the type (minimum yield %, lead days, credit use %, ...). Null = the catalog default.
    threshold             NUMERIC(20, 6) CHECK (threshold IS NULL OR threshold >= 0),
    "repeatEvery"         TEXT           NOT NULL DEFAULT 'daily' CHECK ("repeatEvery" IN ('daily', 'weekly', 'monthly')),
    channels              TEXT[]         NOT NULL DEFAULT ARRAY['in_app']::TEXT[],
    -- Users holding this permission receive the alert. Null = the catalog default.
    "recipientPermission" TEXT,
    -- Named users on top of the permission.
    "recipientUserIds"    TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "AlertRule_org_type_key" ON "AlertRule" ("organizationId", "alertType");

CREATE TABLE "AlertEvent" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "alertType"        TEXT         NOT NULL,
    -- The source's stable key for what the alert is about, with its state when a change of state must alert again.
    "subjectKey"       TEXT         NOT NULL,
    -- 2026-10-31, 2026-W44 or 2026-10, from the rule's repeatEvery on the day it was raised.
    "periodKey"        TEXT         NOT NULL,
    severity           TEXT         NOT NULL DEFAULT 'warning' CHECK (severity IN ('info', 'warning', 'critical')),
    title              TEXT         NOT NULL,
    body               TEXT,
    link               TEXT,
    data               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    channels           TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    recipients         INTEGER      NOT NULL DEFAULT 0,
    status             TEXT         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged')),
    "acknowledgedBy"   TEXT,
    "acknowledgedAt"   TIMESTAMP(3),
    "raisedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "AlertEvent_claim_key" ON "AlertEvent" ("organizationId", "alertType", "subjectKey", "periodKey");
CREATE INDEX "AlertEvent_org_raised_idx" ON "AlertEvent" ("organizationId", "raisedAt" DESC);
CREATE INDEX "AlertEvent_org_type_status_idx" ON "AlertEvent" ("organizationId", "alertType", status);

ALTER TABLE "AlertRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "AlertRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "AlertEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "AlertEvent" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['alerts:read', 'alerts:manage', 'bi:export']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['alerts:read', 'alerts:manage', 'bi:export'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
