-- V80: customer portal (PORTAL-1, ADR-S050). A portal login is a User on the system role "Customer Portal" whose only
-- permission is portal:customer; this table ties the login to one customer, and every portal query is scoped by it.

CREATE TABLE "PortalAccount" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT         NOT NULL REFERENCES "Partner" (id),
    "userId"         TEXT         NOT NULL REFERENCES "User" (id) ON DELETE CASCADE,
    status           TEXT         NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PortalAccount_user_key" ON "PortalAccount" ("organizationId", "userId");
CREATE INDEX "PortalAccount_partner_idx" ON "PortalAccount" ("organizationId", "partnerId");

ALTER TABLE "PortalAccount" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PortalAccount" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['portal:customer', 'portal:manage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['portal:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
