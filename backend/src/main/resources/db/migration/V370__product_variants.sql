-- ADR-S058: product attributes & variants. Attribute masters, a product's attribute lines (which values it is
-- offered in, with a price extra per value), the variant combination key, and variantId on sales/purchase lines.

CREATE TABLE "VariantAttribute" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name              TEXT         NOT NULL,
    "displayType"     TEXT         NOT NULL DEFAULT 'pills'
        CHECK ("displayType" IN ('radio', 'pills', 'select', 'color')),
    sequence          INTEGER      NOT NULL DEFAULT 10,
    "isActive"        BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "VariantAttribute_org_name_key" ON "VariantAttribute" ("organizationId", lower(name))
    WHERE "deletedAt" IS NULL;

CREATE TABLE "VariantAttributeValue" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "attributeId"     TEXT         NOT NULL REFERENCES "VariantAttribute" (id) ON DELETE CASCADE,
    name              TEXT         NOT NULL,
    "htmlColor"       TEXT,
    sequence          INTEGER      NOT NULL DEFAULT 10,
    "isActive"        BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "VariantAttributeValue_attr_name_key" ON "VariantAttributeValue" ("attributeId", lower(name))
    WHERE "deletedAt" IS NULL;
CREATE INDEX "VariantAttributeValue_org_idx" ON "VariantAttributeValue" ("organizationId");

CREATE TABLE "ProductAttributeLine" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"       TEXT         NOT NULL REFERENCES "Product" (id) ON DELETE CASCADE,
    "attributeId"     TEXT         NOT NULL REFERENCES "VariantAttribute" (id),
    sequence          INTEGER      NOT NULL DEFAULT 0,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductAttributeLine_product_attr_key" ON "ProductAttributeLine" ("productId", "attributeId");
CREATE INDEX "ProductAttributeLine_org_attr_idx" ON "ProductAttributeLine" ("organizationId", "attributeId");

CREATE TABLE "ProductAttributeLineValue" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "lineId"          TEXT           NOT NULL REFERENCES "ProductAttributeLine" (id) ON DELETE CASCADE,
    "valueId"         TEXT           NOT NULL REFERENCES "VariantAttributeValue" (id),
    "priceExtra"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductAttributeLineValue_line_value_key" ON "ProductAttributeLineValue" ("lineId", "valueId");
CREATE INDEX "ProductAttributeLineValue_org_value_idx" ON "ProductAttributeLineValue" ("organizationId", "valueId");

-- Sorted value ids of the combination ('' for manually created legacy variants) and the cached sum of price extras.
ALTER TABLE "ProductVariant"
    ADD COLUMN "combinationKey" TEXT           NOT NULL DEFAULT '',
    ADD COLUMN "priceExtra"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    -- Archived by regeneration (combination dropped) rather than by hand: re-adding it re-activates the variant.
    ADD COLUMN "autoArchived"   BOOLEAN        NOT NULL DEFAULT false;
CREATE UNIQUE INDEX "ProductVariant_product_combination_key" ON "ProductVariant" ("productId", "combinationKey")
    WHERE "combinationKey" <> '';

-- Sales and purchase lines carry the variant (null = product without variants).
ALTER TABLE "SalesQuotationLine"  ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;
ALTER TABLE "SalesOrderLine"      ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;
ALTER TABLE "SalesReturnLine"     ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;
ALTER TABLE "SalesSubmissionLine" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;
ALTER TABLE "PurchaseRequestLine" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;
ALTER TABLE "PurchaseOrderLine"   ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;
ALTER TABLE "GoodsReceiptLine"    ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;
ALTER TABLE "DebitNoteLine"       ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL;

ALTER TABLE "VariantAttribute" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "VariantAttribute" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "VariantAttributeValue" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "VariantAttributeValue" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductAttributeLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductAttributeLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductAttributeLineValue" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductAttributeLineValue" USING (("organizationId" = current_setting('app.org_id', true)));
