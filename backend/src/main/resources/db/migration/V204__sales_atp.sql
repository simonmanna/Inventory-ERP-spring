-- V28: Available-to-promise and reservations for sales orders (ADR-S011, phase S4; sales.md §9).
-- Firm reservations live in inventory's "StockReservation" (sourceType 'sales_order_line'); this migration adds the
-- soft pegging of unreserved demand to incoming purchase supply, order priority and the promise-at-risk flag.

ALTER TABLE "SalesOrder"
    ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;

ALTER TABLE "SalesOrderLine"
    -- Location the firm reservation was taken at (base units in StockReservation; qtyReserved is in line units).
    ADD COLUMN "reservedLocationId" TEXT,
    -- Promised date can no longer be met from stock + pegged supply.
    ADD COLUMN "promiseAtRisk" BOOLEAN NOT NULL DEFAULT false;

-- Soft commitment of unreserved order demand against expected supply (never a reservation).
CREATE TABLE "SalesSupplyPeg" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT           NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    "orderLineId"    TEXT           NOT NULL REFERENCES "SalesOrderLine" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL,
    "supplyType"     TEXT           NOT NULL CHECK ("supplyType" IN ('purchase_order_line', 'production')),
    "supplyId"       TEXT           NOT NULL,
    "supplyNumber"   TEXT,
    -- Base units.
    quantity         NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "expectedDate"   DATE,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesSupplyPeg_line_idx" ON "SalesSupplyPeg" ("orderLineId");
CREATE INDEX "SalesSupplyPeg_supply_idx" ON "SalesSupplyPeg" ("organizationId", "supplyType", "supplyId");

ALTER TABLE "SalesSupplyPeg" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesSupplyPeg" USING (("organizationId" = current_setting('app.org_id', true)));
