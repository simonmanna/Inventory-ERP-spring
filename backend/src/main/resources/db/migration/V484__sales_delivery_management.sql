-- Delivery & fulfilment management on sales orders: one order, many deliveries (FleetLoad, manual carrier, V405), each
-- with its own method, transport arrangement, schedule, costs and outcome. Stock still moves only at dispatch and
-- returns only through the existing return paths; these columns are logistics and traceability.
ALTER TABLE "FleetLoad"
    ADD COLUMN "deliveryMethod"        TEXT
        CHECK ("deliveryMethod" IN ('customer_collect', 'site_delivery', 'warehouse_transfer', 'courier')),
    ADD COLUMN "scheduledTime"         TEXT CHECK ("scheduledTime" ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
    ADD COLUMN "driverPhone"           TEXT,
    ADD COLUMN "vehicleType"           TEXT,
    ADD COLUMN "transportCompany"      TEXT,
    ADD COLUMN "trackingNumber"        TEXT,
    ADD COLUMN "destinationLocationId" TEXT,
    ADD COLUMN "deliveryAddress"       TEXT,
    ADD COLUMN "siteContactName"       TEXT,
    ADD COLUMN "siteContactPhone"      TEXT,
    ADD COLUMN route                   TEXT,
    ADD COLUMN "offloadingEquipment"   TEXT,
    ADD COLUMN "expectedArrivalAt"     TIMESTAMP(3),
    ADD COLUMN "arrivedAt"             TIMESTAMP(3),
    ADD COLUMN "transportCost"         NUMERIC(20, 6) CHECK ("transportCost" >= 0),
    ADD COLUMN "tripExpenses"          NUMERIC(20, 6) CHECK ("tripExpenses" >= 0),
    -- A returned load whose delivery attempt failed (site closed, nobody to receive, …) rather than a customer refusal.
    ADD COLUMN "attemptFailed"         BOOLEAN NOT NULL DEFAULT false,
    -- The failed / cancelled delivery this one replaces.
    ADD COLUMN "rescheduledFromId"     TEXT REFERENCES "FleetLoad" (id);

-- Order-level delivery plan: warehouse transfer and courier besides collection and delivery to site.
ALTER TABLE "SalesHandover" DROP CONSTRAINT "SalesHandover_method_check";
ALTER TABLE "SalesHandover" ADD CONSTRAINT "SalesHandover_method_check"
    CHECK (method IN ('pickup', 'delivery', 'transfer', 'courier'));
ALTER TABLE "SalesHandover"
    ADD COLUMN "gpsCoordinates"        TEXT,
    ADD COLUMN "offloadingSite"        TEXT,
    ADD COLUMN "destinationLocationId" TEXT,
    ADD COLUMN "courierName"           TEXT;

-- Dispatch, confirm and cancel split out of sales:delivery:write; every role that could write deliveries keeps them.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:delivery:dispatch', 'sales:delivery:confirm', 'sales:delivery:cancel']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:delivery:dispatch', 'sales:delivery:confirm',
        'sales:delivery:cancel'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND (name = 'Administrator' OR permissions && ARRAY['sales:delivery:write']);
