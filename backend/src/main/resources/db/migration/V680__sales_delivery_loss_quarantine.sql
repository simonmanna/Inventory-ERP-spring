-- Sales readiness DEL-9 (owner decision 2026-11-26).
-- deliveryLossPosting: the cost of goods damaged or missing at delivery moves out of COGS into deliveryLossAccountId
--   (NULL = account 5190 "Delivery losses", created on first use).
-- rejectedToQuarantine: goods the customer rejects come back into rejectedQuarantineLocationId (NULL = the virtual
--   location SALES-QUARANTINE, created on first use) instead of saleable stock; released or written off after inspection.
ALTER TABLE "SalesSettings"
    ADD COLUMN "deliveryLossPosting"          BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "deliveryLossAccountId"        TEXT,
    ADD COLUMN "rejectedToQuarantine"         BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "rejectedQuarantineLocationId" TEXT;

-- Losses already booked per trip line (so a corrected POD posts only the difference).
ALTER TABLE "FleetLoadLine" ADD COLUMN "lossValue" NUMERIC(20, 6) NOT NULL DEFAULT 0;
