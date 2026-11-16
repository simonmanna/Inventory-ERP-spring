-- R-8 foreign-currency treasury, phase 1 (ADR-S076, docs/accounting/FX-TREASURY-PLAN.md §5.1 M-1, M-3).
--
-- No historical row is changed and no balance is converted.
--
-- 1. A journal entry may snapshot one rate per currency pair (a cross-currency transfer converts two currencies).
--    Existing entries keep their single row. The source also allows 'carrying' (the leg of a foreign cash account
--    relieved at its carrying value).
-- 2. A foreign-denominated cash account with balances posted before FX treasury existed (amounts of unknown currency)
--    is brought in by an explicit designation entry; the account remembers when, and which entry.

DROP INDEX IF EXISTS "JournalEntryFxRate_entry_key";
CREATE UNIQUE INDEX "JournalEntryFxRate_entry_pair_key"
    ON "JournalEntryFxRate" ("journalEntryId", "fromCode", "toCode");

ALTER TABLE "JournalEntryFxRate" DROP CONSTRAINT IF EXISTS "JournalEntryFxRate_source_check";
ALTER TABLE "JournalEntryFxRate"
    ADD CONSTRAINT "JournalEntryFxRate_source_check" CHECK (source IN ('document', 'table', 'carrying'));

ALTER TABLE "Account"
    ADD COLUMN "currencyDesignatedAt"       TIMESTAMP(3),
    ADD COLUMN "currencyDesignationEntryId" TEXT;
