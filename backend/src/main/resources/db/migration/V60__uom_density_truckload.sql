-- V60 (UOM-1, extends ADR-S003): construction units and a record of the unit a sales order line was entered in.
--
-- 1. M3, BAG and TRUCK join the seeded units. BAG and TRUCK stay at factor 1 in the Quantity category: what a bag or a
--    truckload holds differs per product and lives in ProductUom. Existing codes are left alone.
-- 2. Product density is not a new column: it is the ProductUomConversion bridge "1 M3 = d TON" (ADR-S003), managed by
--    PUT /products/:id/unit-profile. The Product table and its JSON stay unchanged.
-- 3. An OrderItem keeps its quantity in the product's sales unit because the shared POS billing spine reads it that
--    way (POS is frozen). OrderLineEntryUnit records what the user typed: "2 TRUCK" stored as 56 t.

INSERT INTO "UnitOfMeasure" (id, "organizationId", code, name, symbol, "categoryId", category, factor, ratio,
                             "roundingPrecision", "uomType", "isBase", "isActive", "updatedAt")
SELECT gen_random_uuid()::text, c."organizationId", u.code, u.name, u.symbol, c.id, lower(c.name), u.factor, u.factor,
       u.rounding, 'bigger', false, true, CURRENT_TIMESTAMP
FROM "UomCategory" c
JOIN (VALUES ('Volume', 'M3', 'Cubic metre', 'm³', 1000::numeric, 0.001::numeric),
             ('Quantity', 'BAG', 'Bag', 'bag', 1::numeric, 1::numeric),
             ('Quantity', 'TRUCK', 'Truckload', 'truck', 1::numeric, 0.01::numeric))
     AS u(category, code, name, symbol, factor, rounding) ON u.category = c.name
WHERE c."deletedAt" IS NULL
ON CONFLICT ("organizationId", code) DO NOTHING;

CREATE TABLE "OrderLineEntryUnit" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT           NOT NULL REFERENCES "Order" (id) ON DELETE CASCADE,
    "lineNumber"     INTEGER        NOT NULL,
    "uomId"          TEXT           NOT NULL REFERENCES "UnitOfMeasure" (id),
    quantity         NUMERIC(20, 6) NOT NULL,
    "unitPrice"      NUMERIC(20, 6) NOT NULL,
    -- Sales units per 1 entered unit, frozen when the line was written.
    factor           NUMERIC(20, 10) NOT NULL CHECK (factor > 0),
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX "OrderLineEntryUnit_line_key" ON "OrderLineEntryUnit" ("organizationId", "orderId", "lineNumber");

ALTER TABLE "OrderLineEntryUnit" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "OrderLineEntryUnit" USING (("organizationId" = current_setting('app.org_id', true)));
