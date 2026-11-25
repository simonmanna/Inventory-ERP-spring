-- Finance readiness audit A3 (P2-4): a VAT settlement can be undone (POST /tax-settlements/{id}/reverse). The row stays
-- as the record of what was filed and is marked reversed; a reversed settlement no longer locks its VAT period, and the
-- period can be settled again, so one settlement per period end applies to settlements in force only.
ALTER TABLE "TaxSettlement"
    ADD COLUMN "reversedAt"             TIMESTAMP(3),
    ADD COLUMN "reversedBy"             TEXT,
    ADD COLUMN "reversalJournalEntryId" TEXT;

DROP INDEX "TaxSettlement_org_to_key";
CREATE UNIQUE INDEX "TaxSettlement_org_to_key" ON "TaxSettlement" ("organizationId", "periodTo")
    WHERE "reversedAt" IS NULL;
