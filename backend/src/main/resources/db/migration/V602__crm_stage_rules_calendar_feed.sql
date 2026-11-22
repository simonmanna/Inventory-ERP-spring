-- CRM enhancements T2b (post-parity).
-- Stage rules: fields a deal must have before it may enter the stage, and an optional follow-up activity created for
-- the deal owner when it does. Empty by default, so existing pipelines behave as before.
ALTER TABLE "CrmStage"
    ADD COLUMN "requiredFields" TEXT[]  NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN "followUpType"   TEXT CHECK ("followUpType" IN ('call', 'email', 'meeting', 'task')),
    ADD COLUMN "followUpTitle"  TEXT,
    ADD COLUMN "followUpDays"   INTEGER CHECK ("followUpDays" BETWEEN 0 AND 365);
ALTER TABLE "CrmStage" ADD CONSTRAINT "CrmStage_required_fields_check" CHECK ("requiredFields" <@ ARRAY['amount',
    'expectedClose', 'partnerId', 'contactName', 'email', 'phone']::TEXT[]);

-- Personal calendar feed (ICS) of a user's CRM activities. Only the SHA-256 of the secret token is stored; one live
-- feed per user (creating a new one revokes the old).
CREATE TABLE "CrmCalendarFeed" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL REFERENCES "User" (id) ON DELETE CASCADE,
    "tokenHash"      TEXT         NOT NULL UNIQUE,
    "lastUsedAt"     TIMESTAMP(3),
    "revokedAt"      TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrmCalendarFeed_live_user_key" ON "CrmCalendarFeed" ("organizationId", "userId")
    WHERE "revokedAt" IS NULL;
ALTER TABLE "CrmCalendarFeed" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmCalendarFeed" USING (("organizationId" = current_setting('app.org_id', true)));
