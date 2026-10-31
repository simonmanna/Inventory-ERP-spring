-- V61 (SALES-1): credit limits, hold and exposure on non-POS sales.
--
-- The limit and hold stay on "Partner". A temporary override is granted by someone other than the seller: an override
-- created by the current user never applies to that user's own sale (segregation of duties). POS keeps its own check.

CREATE TABLE "PartnerCreditOverride" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL REFERENCES "Partner" (id) ON DELETE CASCADE,
    -- Extra headroom on top of the limit.
    amount           NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    "coversHold"     BOOLEAN        NOT NULL DEFAULT false,
    reason           TEXT           NOT NULL,
    "expiresAt"      TIMESTAMP(3)   NOT NULL,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "revokedBy"      TEXT,
    "revokedAt"      TIMESTAMP(3)
);

CREATE INDEX "PartnerCreditOverride_partner_idx" ON "PartnerCreditOverride" ("organizationId", "partnerId", "expiresAt");

ALTER TABLE "PartnerCreditOverride" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PartnerCreditOverride" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['credit_control:read', 'credit_control:manage', 'credit_control:override']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['credit_control:read', 'credit_control:manage',
        'credit_control:override'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
