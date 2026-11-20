-- FX-5 (ADR-S082 Outstanding): rental deposits are classified like customer deposits (owner decision 3).
-- 'refundable' = security deposit, a liability returned unless forfeited for damage / no-show (never income until
-- forfeited); 'advance' = prepaid rent, applied to the rental charges. Existing deposits are NOT classified in bulk:
-- kind stays NULL and they appear in the classification review until someone classifies them with a reason.
ALTER TABLE "RentalDeposit"
    ADD COLUMN "kind"                 TEXT,
    ADD COLUMN "classifiedAt"         TIMESTAMP(3),
    ADD COLUMN "classifiedById"       TEXT,
    ADD COLUMN "classificationReason" TEXT;
ALTER TABLE "RentalDeposit"
    ADD CONSTRAINT "RentalDeposit_kind_check" CHECK ("kind" IS NULL OR "kind" IN ('advance', 'refundable'));
