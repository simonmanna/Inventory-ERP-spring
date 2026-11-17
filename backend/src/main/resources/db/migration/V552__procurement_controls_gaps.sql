-- Readiness gaps 2026-11-17, stream gE (procurement controls and sourcing; notes in
-- docs/audits/readiness-gaps/gE-procurement-controls.md).

-- h04-F11: the last person to edit a draft policy cannot also approve it, so the editor is recorded.
ALTER TABLE "ProcurementPolicy" ADD COLUMN "updatedBy" TEXT;

-- h04-F11: supplier approval is maker-checker. "updatedBy" also moves on lifecycle actions, so the last person who
-- edited the supplier's data is recorded separately.
ALTER TABLE "SupplierProfile" ADD COLUMN "lastEditedBy" TEXT;

-- h04-F7: the first delivery date the supplier promised is kept. A later confirmation still updates "promisedDate"
-- (and the change is audited), but on-time KPIs and RFQ delivery scores measure the first promise.
ALTER TABLE "PurchaseOrder" ADD COLUMN "firstPromisedDate" DATE;
UPDATE "PurchaseOrder" SET "firstPromisedDate" = "promisedDate" WHERE "promisedDate" IS NOT NULL;

-- h04-F5: cancelling an inspection lifts the bill hold, so it records who did it and why.
ALTER TABLE "IncomingInspection"
    ADD COLUMN "cancelReason" TEXT,
    ADD COLUMN "cancelledBy"  TEXT,
    ADD COLUMN "cancelledAt"  TIMESTAMP(3);
