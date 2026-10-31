-- V34: Customer portal access (ADR-S011 phase S10, licence customerPortal). A customer contact gets a magic link whose
-- token (only its SHA-256 is stored) opens the portal: quotations (accept / decline), orders and deliveries, invoices,
-- statement, mobile-money payment and complaints. Looked up by token hash before the organization is known, so the
-- table has no row-level policy; every portal query afterwards runs in the token's organization.

CREATE TABLE "CustomerPortalAccess" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT         NOT NULL,
    email            TEXT,
    "tokenHash"      TEXT         NOT NULL,
    "expiresAt"      TIMESTAMP(3) NOT NULL,
    "revokedAt"      TIMESTAMP(3),
    "lastUsedAt"     TIMESTAMP(3),
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CustomerPortalAccess_token_key" ON "CustomerPortalAccess" ("tokenHash");
CREATE INDEX "CustomerPortalAccess_partner_idx" ON "CustomerPortalAccess" ("organizationId", "partnerId");

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:portal:manage']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:portal:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
