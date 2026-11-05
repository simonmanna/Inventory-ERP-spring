-- Delivery & fulfilment handover of a sales order or a sales invoice (same model as a branch cash sale, V342): how
-- the goods reach the customer (customer collects from a store / warehouse, or delivery to site) and where that
-- handover stands. Logistics only: stock moves stay with the delivery orders / invoice posting.
CREATE TABLE "SalesHandover" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "sourceType"      TEXT         NOT NULL CHECK ("sourceType" IN ('sales_order', 'invoice')),
    "sourceId"        TEXT         NOT NULL,
    method            TEXT         NOT NULL DEFAULT 'pickup' CHECK (method IN ('pickup', 'delivery')),
    status            TEXT         NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'scheduled', 'dispatched', 'delivered', 'failed')),
    -- Store / warehouse the customer collects from (or the goods leave from).
    "locationId"      TEXT,
    address           TEXT,
    "contactName"     TEXT,
    "contactPhone"    TEXT,
    "scheduledDate"   DATE,
    driver            TEXT,
    vehicle           TEXT,
    "receivedBy"      TEXT,
    notes             TEXT,
    "deliveredAt"     TIMESTAMP(3),
    "deliveredById"   TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedById"     TEXT,
    CONSTRAINT "SalesHandover_source_key" UNIQUE ("organizationId", "sourceType", "sourceId")
);
