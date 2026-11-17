-- R11: an accepted supplier claim is settled by a posted supplier credit (Dr AP / Cr the claimed lines' counter
-- accounts and input VAT, key supplier_claim:<claimId>) instead of a draft outbound debit note. The claim keeps the
-- link to that journal entry. Claims settled before keep their "debitNoteId"; no existing row is changed.

ALTER TABLE "SupplierClaim" ADD COLUMN "journalEntryId" TEXT;
CREATE INDEX "SupplierClaim_journal_entry_idx" ON "SupplierClaim" ("journalEntryId") WHERE "journalEntryId" IS NOT NULL;
