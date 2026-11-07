-- SG-6: promotions, absolute price limits, order-level and fixed discounts, project agreements, price history.
-- The two price list systems stay as they are: the pricing engine already reads both, and POS depends on PriceList.
ALTER TABLE "SalesQuotationLine" DROP CONSTRAINT "SalesQuotationLine_priceSource_check";
ALTER TABLE "SalesQuotationLine" ADD CONSTRAINT "SalesQuotationLine_priceSource_check"
    CHECK ("priceSource" IN ('list', 'rule', 'customer', 'manual', 'pricelist', 'price_list', 'agreement', 'promotion'));
ALTER TABLE "SalesOrderLine" DROP CONSTRAINT "SalesOrderLine_priceSource_check";
ALTER TABLE "SalesOrderLine" ADD CONSTRAINT "SalesOrderLine_priceSource_check"
    CHECK ("priceSource" IN ('list', 'rule', 'customer', 'manual', 'pricelist', 'price_list', 'agreement', 'quotation', 'promotion'));

CREATE TABLE "SalesPromotion" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name                TEXT           NOT NULL,
    kind                TEXT           NOT NULL CHECK (kind IN ('percent', 'fixed_price', 'amount_off')),
    value               NUMERIC(20, 6) NOT NULL CHECK (value >= 0),
    "productId"         TEXT,
    "productCategoryId" TEXT,
    "partnerCategoryId" TEXT,
    "branchId"          TEXT,
    "minQuantity"       NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minQuantity" >= 0),
    "startDate"         DATE           NOT NULL,
    "endDate"           DATE           NOT NULL,
    priority            INTEGER        NOT NULL DEFAULT 0,
    "isActive"          BOOLEAN        NOT NULL DEFAULT true,
    "createdById"       TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("endDate" >= "startDate"),
    CHECK (kind <> 'percent' OR value <= 100)
);
CREATE INDEX "SalesPromotion_active_idx" ON "SalesPromotion" ("organizationId", "startDate", "endDate") WHERE "isActive";
ALTER TABLE "SalesPromotion" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesPromotion" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "SalesPriceLimit" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"         TEXT,
    "productCategoryId" TEXT,
    "minPrice"          NUMERIC(20, 6) CHECK ("minPrice" IS NULL OR "minPrice" >= 0),
    "maxPrice"          NUMERIC(20, 6) CHECK ("maxPrice" IS NULL OR "maxPrice" >= 0),
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("productId" IS NOT NULL OR "productCategoryId" IS NOT NULL),
    CHECK ("minPrice" IS NOT NULL OR "maxPrice" IS NOT NULL),
    CHECK ("minPrice" IS NULL OR "maxPrice" IS NULL OR "maxPrice" >= "minPrice")
);
CREATE UNIQUE INDEX "SalesPriceLimit_product_key" ON "SalesPriceLimit" ("organizationId", "productId") WHERE "productId" IS NOT NULL;
CREATE UNIQUE INDEX "SalesPriceLimit_category_key" ON "SalesPriceLimit" ("organizationId", "productCategoryId")
    WHERE "productCategoryId" IS NOT NULL AND "productId" IS NULL;
ALTER TABLE "SalesPriceLimit" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesPriceLimit" USING (("organizationId" = current_setting('app.org_id', true)));

ALTER TABLE "SalesOrder"
    ADD COLUMN "orderDiscountPercent" NUMERIC(9, 6) CHECK ("orderDiscountPercent" IS NULL OR ("orderDiscountPercent" >= 0 AND "orderDiscountPercent" <= 100)),
    ADD COLUMN "orderDiscountAmount" NUMERIC(20, 6) CHECK ("orderDiscountAmount" IS NULL OR "orderDiscountAmount" >= 0);
ALTER TABLE "SalesQuotation"
    ADD COLUMN "orderDiscountPercent" NUMERIC(9, 6) CHECK ("orderDiscountPercent" IS NULL OR ("orderDiscountPercent" >= 0 AND "orderDiscountPercent" <= 100)),
    ADD COLUMN "orderDiscountAmount" NUMERIC(20, 6) CHECK ("orderDiscountAmount" IS NULL OR "orderDiscountAmount" >= 0);
-- The line's own discount before the order discount; null when no order discount applies.
ALTER TABLE "SalesOrderLine" ADD COLUMN "lineDiscountPercent" NUMERIC(9, 6);
ALTER TABLE "SalesQuotationLine" ADD COLUMN "lineDiscountPercent" NUMERIC(9, 6);
ALTER TABLE "SalesAgreement" ADD COLUMN "projectName" TEXT;

CREATE TABLE "SalesPriceHistory" (
    id               BIGSERIAL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL,
    system           TEXT         NOT NULL,
    action           TEXT         NOT NULL,
    "listId"         TEXT,
    "itemId"         TEXT,
    "productId"      TEXT,
    "oldValue"       JSONB,
    "newValue"       JSONB,
    "changedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesPriceHistory_product_idx" ON "SalesPriceHistory" ("organizationId", "productId", "changedAt" DESC);
ALTER TABLE "SalesPriceHistory" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesPriceHistory" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE FUNCTION sales_pricelist_item_history() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN r := OLD; ELSE r := NEW; END IF;
    INSERT INTO "SalesPriceHistory" ("organizationId", system, action, "listId", "itemId", "productId", "oldValue", "newValue")
    VALUES (r."organizationId", 'pricelist', lower(TG_OP), r."pricelistId", r.id, r."productId",
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE jsonb_build_object('compute', OLD.compute, 'value', OLD.value, 'minQuantity', OLD."minQuantity") END,
            CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE jsonb_build_object('compute', NEW.compute, 'value', NEW.value, 'minQuantity', NEW."minQuantity") END);
    RETURN r;
END $$;
CREATE TRIGGER sales_pricelist_item_history AFTER INSERT OR UPDATE OR DELETE ON "PricelistItem"
    FOR EACH ROW EXECUTE FUNCTION sales_pricelist_item_history();

CREATE FUNCTION sales_price_list_item_history() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN r := OLD; ELSE r := NEW; END IF;
    INSERT INTO "SalesPriceHistory" ("organizationId", system, action, "listId", "itemId", "productId", "oldValue", "newValue")
    VALUES (r."organizationId", 'price_list', lower(TG_OP), r."priceListId", r.id, r."productId",
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE jsonb_build_object('unitPrice', OLD."unitPrice", 'discountPercent', OLD."discountPercent", 'minQuantity', OLD."minQuantity") END,
            CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE jsonb_build_object('unitPrice', NEW."unitPrice", 'discountPercent', NEW."discountPercent", 'minQuantity', NEW."minQuantity") END);
    RETURN r;
END $$;
CREATE TRIGGER sales_price_list_item_history AFTER INSERT OR UPDATE OR DELETE ON "PriceListItem"
    FOR EACH ROW EXECUTE FUNCTION sales_price_list_item_history();
