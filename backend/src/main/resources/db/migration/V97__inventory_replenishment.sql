-- V97: INV-3 — replenishment rules and alerts (Track C Phase 4). A rule watches one product at one location: when
-- available stock (on hand less active reservations) falls below the minimum, a scan raises an alert. An alert can ask
-- procurement for a purchase request, by hand or automatically when the rule says so. One live alert per rule.

CREATE TABLE "InventoryReplenishmentRule" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"        TEXT           NOT NULL REFERENCES "Product" (id),
    "locationId"       TEXT           NOT NULL REFERENCES "InventoryLocation" (id),
    "minQty"           NUMERIC(20, 6) NOT NULL CHECK ("minQty" > 0),
    -- Order up to this level when set; otherwise order reorderQty, or just the shortfall.
    "maxQty"           NUMERIC(20, 6),
    "reorderQty"       NUMERIC(20, 6) CHECK ("reorderQty" IS NULL OR "reorderQty" > 0),
    -- Falls back to the product's supplier.
    "supplierId"       TEXT           REFERENCES "Partner" (id),
    "leadTimeDays"     INTEGER        NOT NULL DEFAULT 0 CHECK ("leadTimeDays" >= 0),
    "autoRequisition"  BOOLEAN        NOT NULL DEFAULT false,
    "isActive"         BOOLEAN        NOT NULL DEFAULT true,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3),
    CONSTRAINT "InventoryReplenishmentRule_levels_chk" CHECK ("maxQty" IS NULL OR "maxQty" >= "minQty")
);
CREATE UNIQUE INDEX "InventoryReplenishmentRule_cell_key" ON "InventoryReplenishmentRule" ("organizationId", "productId",
    "locationId") WHERE "deletedAt" IS NULL;

CREATE TABLE "InventoryReplenishmentAlert" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "ruleId"           TEXT           NOT NULL REFERENCES "InventoryReplenishmentRule" (id),
    "productId"        TEXT           NOT NULL REFERENCES "Product" (id),
    "locationId"       TEXT           NOT NULL REFERENCES "InventoryLocation" (id),
    "onHand"           NUMERIC(20, 6) NOT NULL,
    reserved           NUMERIC(20, 6) NOT NULL,
    available          NUMERIC(20, 6) NOT NULL,
    "minQty"           NUMERIC(20, 6) NOT NULL,
    "suggestedQty"     NUMERIC(20, 6) NOT NULL,
    status             TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'requested', 'requisitioned', 'resolved', 'dismissed')),
    -- The purchase request procurement raised. A plain id: inventory does not depend on procurement.
    "purchaseRequestId" TEXT,
    "requestNumber"    TEXT,
    "statusReason"     TEXT,
    "resolvedAt"       TIMESTAMP(3),
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "InventoryReplenishmentAlert_org_status_idx" ON "InventoryReplenishmentAlert" ("organizationId", status);
CREATE UNIQUE INDEX "InventoryReplenishmentAlert_one_live_key" ON "InventoryReplenishmentAlert" ("ruleId")
    WHERE status IN ('open', 'requested', 'requisitioned');

ALTER TABLE "InventoryReplenishmentRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "InventoryReplenishmentRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "InventoryReplenishmentAlert" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "InventoryReplenishmentAlert" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['inventory:replenish']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['inventory:replenish'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
