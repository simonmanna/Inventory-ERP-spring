-- V96: INV-2 — product attributes, specifications and grades (Track C Phase 4). Grades and attribute definitions are
-- organisation masters; ProductSpecification and ProductAttributeValue hang off "Product" so the Product table, and with
-- it every POS response and sync payload, stays exactly as it is.

CREATE TABLE "ProductGrade" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code              TEXT         NOT NULL,
    name              TEXT         NOT NULL,
    description       TEXT,
    sequence          INTEGER      NOT NULL DEFAULT 10,
    "isActive"        BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "ProductGrade_org_code_key" ON "ProductGrade" ("organizationId", code) WHERE "deletedAt" IS NULL;

CREATE TABLE "ProductAttribute" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code              TEXT         NOT NULL,
    name              TEXT         NOT NULL,
    "dataType"        TEXT         NOT NULL DEFAULT 'text' CHECK ("dataType" IN ('text', 'number', 'boolean', 'option')),
    unit              TEXT,
    options           TEXT[]       NOT NULL DEFAULT '{}',
    -- NULL: every product. Otherwise only products of this category carry the attribute.
    "categoryId"      TEXT         REFERENCES "ProductCategory" (id),
    "isRequired"      BOOLEAN      NOT NULL DEFAULT false,
    sequence          INTEGER      NOT NULL DEFAULT 10,
    "isActive"        BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "ProductAttribute_org_code_key" ON "ProductAttribute" ("organizationId", code)
    WHERE "deletedAt" IS NULL;

CREATE TABLE "ProductSpecification" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"       TEXT         NOT NULL REFERENCES "Product" (id),
    "gradeId"         TEXT         REFERENCES "ProductGrade" (id),
    -- The published standard the product meets, e.g. US 1:2018, and any free-text specification.
    "standardRef"     TEXT,
    specification     TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductSpecification_product_key" ON "ProductSpecification" ("organizationId", "productId");
CREATE INDEX "ProductSpecification_org_grade_idx" ON "ProductSpecification" ("organizationId", "gradeId");

CREATE TABLE "ProductAttributeValue" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"       TEXT           NOT NULL REFERENCES "Product" (id),
    "attributeId"     TEXT           NOT NULL REFERENCES "ProductAttribute" (id),
    "valueText"       TEXT,
    "valueNumber"     NUMERIC(20, 6),
    "valueBoolean"    BOOLEAN,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "ProductAttributeValue_product_attribute_key" ON "ProductAttributeValue" ("productId", "attributeId");
CREATE INDEX "ProductAttributeValue_org_attribute_idx" ON "ProductAttributeValue" ("organizationId", "attributeId");

ALTER TABLE "ProductGrade" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductGrade" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductAttribute" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductAttribute" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductSpecification" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductSpecification" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ProductAttributeValue" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProductAttributeValue" USING (("organizationId" = current_setting('app.org_id', true)));
