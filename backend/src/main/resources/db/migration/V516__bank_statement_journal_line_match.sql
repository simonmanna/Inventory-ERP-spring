-- Review R5: a bank statement line may be matched to a posted GL journal line on the bank account (a treasury
-- transfer, expense, petty-cash top-up or payroll payment booked without a Payment row), as well as to a payment.
-- Auto-match and post_entry bank rules use it so a movement already in the ledger is never booked a second time.
-- One journal line backs at most one statement line; unmatch clears the column.
ALTER TABLE "BankStatementLine" ADD COLUMN "matchedJournalLineId" TEXT;
CREATE UNIQUE INDEX "BankStatementLine_matchedJournalLine_key" ON "BankStatementLine" ("organizationId",
    "matchedJournalLineId") WHERE "matchedJournalLineId" IS NOT NULL;
