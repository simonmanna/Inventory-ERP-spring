-- Review 2026-11-15 (G-6): journal-line amount rules were enforced in the application only. Every line carries
-- non-negative amounts, and never a debit and a credit at once, in transaction and base currency.
-- NOT VALID: enforced for every new or updated row without scanning history (posted rows are immutable anyway).
ALTER TABLE "JournalLine"
    ADD CONSTRAINT "JournalLine_amounts_non_negative"
        CHECK (debit >= 0 AND credit >= 0 AND "baseDebit" >= 0 AND "baseCredit" >= 0) NOT VALID,
    ADD CONSTRAINT "JournalLine_one_side"
        CHECK (NOT (debit > 0 AND credit > 0) AND NOT ("baseDebit" > 0 AND "baseCredit" > 0)) NOT VALID;
