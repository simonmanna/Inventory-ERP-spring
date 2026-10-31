-- V63 (SALES-3): named price lists with tiers, branch, site, zone and unit prices, applied to /orders, /invoices,
-- manual delivery orders and quotations whenever a line comes with no price of its own. A line with an explicit price,
-- and every POS sale, is untouched. No list means pricing behaves as before.

CREATE TABLE "PriceList" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    tier             TEXT         NOT NULL DEFAULT 'retail'
        CHECK (tier IN ('retail', 'wholesale', 'contract', 'negotiated')),
    -- Applies to every customer without a more specific list.
    "isDefault"      BOOLEAN      NOT NULL DEFAULT false,
    "branchId"       TEXT REFERENCES "Branch" (id),
    "validFrom"      DATE,
    "validTo"        DATE,
    priority         INTEGER      NOT NULL DEFAULT 0,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3),
    CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);

CREATE UNIQUE INDEX "PriceList_code_key" ON "PriceList" ("organizationId", code) WHERE "deletedAt" IS NULL;

CREATE TABLE "PriceListItem" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "priceListId"       TEXT           NOT NULL REFERENCES "PriceList" (id) ON DELETE CASCADE,
    -- What: one product, or a product category, or everything (both NULL).
    "productId"         TEXT REFERENCES "Product" (id),
    "productCategoryId" TEXT,
    -- NULL = the product's default sales unit.
    "uomId"             TEXT REFERENCES "UnitOfMeasure" (id),
    -- A customer site, or a delivery zone of FleetCustomerSite.
    "siteId"            TEXT,
    zone                TEXT,
    "minQuantity"       NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minQuantity" >= 0),
    "unitPrice"         NUMERIC(20, 6) CHECK ("unitPrice" >= 0),
    "discountPercent"   NUMERIC(7, 3)  CHECK ("discountPercent" >= 0 AND "discountPercent" <= 100),
    CONSTRAINT "PriceListItem_has_price" CHECK ("unitPrice" IS NOT NULL OR "discountPercent" IS NOT NULL)
);

CREATE INDEX "PriceListItem_list_idx" ON "PriceListItem" ("priceListId");
CREATE INDEX "PriceListItem_product_idx" ON "PriceListItem" ("organizationId", "productId");

CREATE TABLE "PriceListAssignment" (
    id                  TEXT NOT NULL PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "priceListId"       TEXT NOT NULL REFERENCES "PriceList" (id) ON DELETE CASCADE,
    "partnerId"         TEXT REFERENCES "Partner" (id) ON DELETE CASCADE,
    "partnerCategoryId" TEXT REFERENCES "PartnerCategory" (id) ON DELETE CASCADE,
    CONSTRAINT "PriceListAssignment_one_target" CHECK (("partnerId" IS NULL) <> ("partnerCategoryId" IS NULL))
);

CREATE INDEX "PriceListAssignment_list_idx" ON "PriceListAssignment" ("priceListId");
CREATE INDEX "PriceListAssignment_partner_idx" ON "PriceListAssignment" ("organizationId", "partnerId");

ALTER TABLE "PriceList" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PriceList" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PriceListItem" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PriceListItem" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PriceListAssignment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PriceListAssignment" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['price_list:read', 'price_list:manage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['price_list:read', 'price_list:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
