-- V580: in-app guided tours (ADR-S079, docs/spec/guide.md). One row per user and tour: how far the user got and
-- whether they finished or dismissed it. The tours themselves live in the web client; this is learning progress only.
CREATE TABLE "GuideTourProgress" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL REFERENCES "User" (id) ON DELETE CASCADE,
    "tourKey"        TEXT         NOT NULL CHECK ("tourKey" ~ '^[a-z0-9.-]{1,100}$'),
    "tourVersion"    INTEGER      NOT NULL CHECK ("tourVersion" >= 1),
    status           TEXT         NOT NULL CHECK (status IN ('in_progress', 'completed', 'dismissed')),
    "currentStep"    INTEGER      NOT NULL DEFAULT 0 CHECK ("currentStep" >= 0),
    "startedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completedAt"    TIMESTAMP(3),
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "GuideTourProgress_user_tour_key" UNIQUE ("organizationId", "userId", "tourKey")
);

-- Tenant isolation (V214 pattern): no tenant set (migrations, schedulers) sees every row; a tenant sees its own.
ALTER TABLE "GuideTourProgress" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "GuideTourProgress"
    USING (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));
