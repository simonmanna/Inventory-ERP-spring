-- V74 (Phase 4, PROC-2): supplier price agreements. Price history is read from purchase order lines, not stored.
--
-- One row is one agreed price per base unit of a product, valid over a date range from a minimum quantity. A new price
-- is a new row, so past comparisons keep the price that applied then.

CREATE TABLE "SupplierPriceAgreement" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL,
    "productId"      TEXT           NOT NULL,
    "unitPrice"      NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    "currencyCode"   TEXT           NOT NULL,
    "minQuantity"    NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minQuantity" >= 0),
    "validFrom"      DATE           NOT NULL,
    "validTo"        DATE,
    reference        TEXT,
    notes            TEXT,
    status           TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'cancelled')),
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("validTo" IS NULL OR "validTo" >= "validFrom")
);
CREATE INDEX "SupplierPriceAgreement_org_partner_product_idx"
    ON "SupplierPriceAgreement" ("organizationId", "partnerId", "productId");
CREATE INDEX "SupplierPriceAgreement_org_product_idx" ON "SupplierPriceAgreement" ("organizationId", "productId");

ALTER TABLE "SupplierPriceAgreement" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierPriceAgreement"
    USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['price_agreement:read', 'price_agreement:manage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['price_agreement:read', 'price_agreement:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
