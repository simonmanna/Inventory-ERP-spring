-- V26: Sales pricing (ADR-S011, phase S2) — named pricelists with rules by product / category, customer / customer
-- category, minimum quantity, unit, delivery zone, branch and validity; margin floors; customer and customer-category
-- pricelist assignment; order approval thresholds. CRM price rules (V17) stay where they are and are honoured by the
-- sales pricing engine as the "rule" tier (sales.md §6, deviation noted in sales-phases.md).

CREATE TABLE "Pricelist" (
    id                TEXT          NOT NULL PRIMARY KEY,
    "organizationId"  TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code              TEXT          NOT NULL,
    name              TEXT          NOT NULL,
    type              TEXT          NOT NULL DEFAULT 'wholesale'
        CHECK (type IN ('retail', 'wholesale', 'contractor', 'distributor', 'contract', 'other')),
    -- NULL = any document currency.
    "currencyCode"    TEXT,
    -- Default pricelist of a branch (or of the organization when NULL and isDefault).
    "branchId"        TEXT,
    -- Rules computing a % on "base" use this pricelist's price as base (else the product list price).
    "basePricelistId" TEXT REFERENCES "Pricelist" (id) ON DELETE SET NULL,
    "validFrom"       DATE,
    "validTo"         DATE,
    "isDefault"       BOOLEAN       NOT NULL DEFAULT false,
    "isActive"        BOOLEAN       NOT NULL DEFAULT true,
    notes             TEXT,
    "createdById"     TEXT,
    "createdAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3),
    CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom"),
    CHECK ("basePricelistId" IS NULL OR "basePricelistId" <> id)
);
CREATE UNIQUE INDEX "Pricelist_org_code_key" ON "Pricelist" ("organizationId", lower(code)) WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "Pricelist_org_branch_default_key" ON "Pricelist" ("organizationId", COALESCE("branchId", ''))
    WHERE "isDefault" AND "deletedAt" IS NULL;

CREATE TABLE "PricelistItem" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "pricelistId"        TEXT           NOT NULL REFERENCES "Pricelist" (id) ON DELETE CASCADE,
    scope                TEXT           NOT NULL DEFAULT 'product' CHECK (scope IN ('product', 'category', 'all')),
    "productId"          TEXT,
    "productCategoryId"  TEXT,
    -- Optional customer-specific rule (one customer or one customer category).
    "partnerId"          TEXT,
    "partnerCategoryId"  TEXT,
    -- Price is per this unit (NULL = product base unit; converted by the unit factor).
    "uomId"              TEXT,
    "minQuantity"        NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minQuantity" >= 0),
    -- Delivery zone of the customer site (FleetCustomerSite.zone), NULL = any.
    "siteZone"           TEXT,
    "branchId"           TEXT,
    "validFrom"          DATE,
    "validTo"            DATE,
    compute              TEXT           NOT NULL DEFAULT 'fixed'
        CHECK (compute IN ('fixed', 'discount', 'surcharge', 'cost_plus')),
    value                NUMERIC(20, 6) NOT NULL CHECK (value >= 0),
    priority             INTEGER        NOT NULL DEFAULT 0,
    notes                TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (compute <> 'discount' OR value <= 100),
    CHECK (scope <> 'product' OR "productId" IS NOT NULL),
    CHECK (scope <> 'category' OR "productCategoryId" IS NOT NULL),
    CHECK ("partnerId" IS NULL OR "partnerCategoryId" IS NULL),
    CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);
CREATE INDEX "PricelistItem_list_idx" ON "PricelistItem" ("pricelistId");
CREATE INDEX "PricelistItem_org_partner_idx" ON "PricelistItem" ("organizationId", "partnerId")
    WHERE "partnerId" IS NOT NULL;
CREATE INDEX "PricelistItem_org_product_idx" ON "PricelistItem" ("organizationId", "productId");

CREATE TABLE "MarginFloor" (
    id                   TEXT          NOT NULL PRIMARY KEY,
    "organizationId"     TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"          TEXT,
    "productCategoryId"  TEXT,
    "branchId"           TEXT,
    "partnerCategoryId"  TEXT,
    "minMarginPercent"   NUMERIC(9, 4) NOT NULL CHECK ("minMarginPercent" BETWEEN 0 AND 99.9999),
    "validFrom"          DATE,
    "validTo"            DATE,
    "isActive"           BOOLEAN       NOT NULL DEFAULT true,
    "createdAt"          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("productId" IS NULL OR "productCategoryId" IS NULL)
);
CREATE INDEX "MarginFloor_org_idx" ON "MarginFloor" ("organizationId") WHERE "isActive";

ALTER TABLE "PartnerCategory" ADD COLUMN "pricelistId" TEXT;

-- Order approval thresholds (quote thresholds already exist).
ALTER TABLE "SalesSettings"
    ADD COLUMN "orderMaxDiscountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 10
        CHECK ("orderMaxDiscountPercent" BETWEEN 0 AND 100),
    ADD COLUMN "orderApprovalAmount"     NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("orderApprovalAmount" >= 0),
    ADD COLUMN "marginCheck"             BOOLEAN        NOT NULL DEFAULT true;

ALTER TABLE "Pricelist" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "Pricelist" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PricelistItem" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PricelistItem" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "MarginFloor" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MarginFloor" USING (("organizationId" = current_setting('app.org_id', true)));
