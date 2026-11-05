-- Site details of a delivery on a sales order / invoice handover (V350) and a branch cash sale (V342): a GPS pin or
-- landmark for the drop-off, the loading site the goods leave from, whether the delivery is full or partial and
-- whether offloading equipment (crane, forklift, ...) is needed / available at site. Logistics only.
ALTER TABLE "SalesHandover"
    ADD COLUMN landmark              TEXT,
    ADD COLUMN "loadingSite"         TEXT,
    ADD COLUMN "deliveryScope"       TEXT CHECK ("deliveryScope" IN ('full', 'partial')),
    ADD COLUMN "offloadingEquipment" TEXT CHECK ("offloadingEquipment" IN ('yes', 'no'));

ALTER TABLE "SalesSubmission"
    ADD COLUMN "deliveryLandmark"            TEXT,
    ADD COLUMN "deliveryLoadingSite"         TEXT,
    ADD COLUMN "deliveryScope"               TEXT CHECK ("deliveryScope" IN ('full', 'partial')),
    ADD COLUMN "deliveryOffloadingEquipment" TEXT CHECK ("deliveryOffloadingEquipment" IN ('yes', 'no'));
