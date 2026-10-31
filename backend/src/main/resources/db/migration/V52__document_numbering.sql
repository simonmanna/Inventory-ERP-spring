-- V52: document numbering configuration (CTRL-2).
--
-- A row overrides the format of one logical sequence for one organization; no row keeps the format in the code. The
-- counter itself stays in the native per-organization sequence, so changing a format can never reuse a number.
-- Keys are limited to DocumentNumberCatalog: POS keys and the journals are not configurable.

CREATE TABLE "DocumentNumberFormat" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "sequenceKey"    TEXT         NOT NULL,
    prefix           TEXT         NOT NULL DEFAULT '',
    suffix           TEXT         NOT NULL DEFAULT '',
    padding          INTEGER      NOT NULL CHECK (padding BETWEEN 1 AND 12),
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "DocumentNumberFormat_org_key" ON "DocumentNumberFormat" ("organizationId", "sequenceKey");

ALTER TABLE "DocumentNumberFormat" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "DocumentNumberFormat" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['document_numbering:read', 'document_numbering:manage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['document_numbering:read',
        'document_numbering:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
