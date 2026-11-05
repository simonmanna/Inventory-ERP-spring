-- Delivery and fulfilment of a branch cash sale: how the goods reach the customer (pickup at the showroom or a
-- delivery) and where that handover stands. Logistics only: stock is still issued once at confirm.
ALTER TABLE "SalesSubmission"
    ADD COLUMN "deliveryMethod"        TEXT NOT NULL DEFAULT 'pickup' CHECK ("deliveryMethod" IN ('pickup', 'delivery')),
    ADD COLUMN "deliveryStatus"        TEXT NOT NULL DEFAULT 'pending'
        CHECK ("deliveryStatus" IN ('pending', 'scheduled', 'dispatched', 'delivered', 'failed')),
    ADD COLUMN "deliveryAddress"       TEXT,
    ADD COLUMN "deliveryContactName"   TEXT,
    ADD COLUMN "deliveryContactPhone"  TEXT,
    ADD COLUMN "deliveryScheduledDate" DATE,
    ADD COLUMN "deliveryDriver"        TEXT,
    ADD COLUMN "deliveryVehicle"       TEXT,
    ADD COLUMN "deliveryReceivedBy"    TEXT,
    ADD COLUMN "deliveryNotes"         TEXT,
    ADD COLUMN "deliveredAt"           TIMESTAMP(3),
    ADD COLUMN "deliveredById"         TEXT;
