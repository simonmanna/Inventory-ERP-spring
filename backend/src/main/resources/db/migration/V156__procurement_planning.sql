-- V32: purchase planning and the supplier portal (ADR-PROCUREMENT-001, wave 7 / roadmap P5).
-- Reorder rules for replenishment suggestions, and per-document supplier portal links (only the SHA-256 hash of the
-- link token is stored). Additive only.

CREATE TABLE "ReorderRule" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"           TEXT           NOT NULL REFERENCES "Product" (id) ON DELETE CASCADE,
    "locationId"          TEXT           NOT NULL REFERENCES "InventoryLocation" (id) ON DELETE CASCADE,
    "branchId"            TEXT,
    method                TEXT           NOT NULL DEFAULT 'min_max' CHECK (method IN ('min_max', 'reorder_point')),
    "minQuantity"         NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minQuantity" >= 0),
    "maxQuantity"         NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("maxQuantity" >= 0),
    "safetyStock"         NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("safetyStock" >= 0),
    "leadTimeDays"        INTEGER        NOT NULL DEFAULT 7 CHECK ("leadTimeDays" BETWEEN 0 AND 365),
    "orderMultiple"       NUMERIC(20, 6) CHECK ("orderMultiple" IS NULL OR "orderMultiple" > 0),
    "preferredSupplierId" TEXT,
    "autoRaise"           BOOLEAN        NOT NULL DEFAULT false,
    "isActive"            BOOLEAN        NOT NULL DEFAULT true,
    "lastRaisedAt"        TIMESTAMP(3),
    "createdBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("maxQuantity" >= "minQuantity")
);
CREATE UNIQUE INDEX "ReorderRule_key" ON "ReorderRule" ("organizationId", "productId", "locationId");

CREATE TABLE "SupplierPortalToken" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "tokenHash"      TEXT         NOT NULL,
    "documentType"   TEXT         NOT NULL CHECK ("documentType" IN ('rfq', 'purchase_order')),
    "documentId"     TEXT         NOT NULL,
    "partnerId"      TEXT         NOT NULL REFERENCES "Partner" (id),
    "expiresAt"      TIMESTAMP(3) NOT NULL,
    "revokedAt"      TIMESTAMP(3),
    "lastUsedAt"     TIMESTAMP(3),
    "useCount"       INTEGER      NOT NULL DEFAULT 0,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SupplierPortalToken_hash_key" ON "SupplierPortalToken" ("tokenHash");
CREATE INDEX "SupplierPortalToken_doc_idx" ON "SupplierPortalToken" ("organizationId", "documentType", "documentId");

-- Analytics read paths.
CREATE INDEX IF NOT EXISTS "InventoryLedger_consumption_idx" ON "InventoryLedger" ("organizationId", "productId", "createdAt")
    WHERE "quantityChange" < 0;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['replenishment:read', 'replenishment:manage', 'procurement_analytics:read', 'supplier_portal:manage'])
    AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role" SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['replenishment:read',
        'replenishment:manage', 'procurement_analytics:read', 'supplier_portal:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

ALTER TABLE "ReorderRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ReorderRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierPortalToken" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierPortalToken" USING (("organizationId" = current_setting('app.org_id', true)));
