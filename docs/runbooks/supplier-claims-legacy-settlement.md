# Supplier claims settled before 2026-11-17 (R11)

Before the accounting controls fix (`docs/spec/accounting.md`, "Accounting controls deviations", R11) an accepted
supplier claim was settled by an **outbound debit note**. When that note was posted it booked **Dr the supplier's AR
account / Cr sales revenue (+ output VAT)** and never reduced the vendor bill's residual. AP stayed overstated and
revenue was inflated.

Settlements made after the fix post their own supplier credit (`SupplierClaim."journalEntryId"` is set). Nothing
rewrites the old entries: corrections are approved, audited journal entries.

## 1. Find affected claims (read-only)

```sql
SELECT c."organizationId", c."claimNumber", c.type, c."settledAmount", c."settledAt", c."partnerId", c."vendorBillId",
       n."noteNumber", n.direction, n.status::text AS note_status, n."totalAmount", je."entryNumber", je."postingDate"
FROM "SupplierClaim" c JOIN "DebitNote" n ON n.id = c."debitNoteId"
LEFT JOIN "JournalEntry" je ON je."organizationId" = c."organizationId"
     AND je."postingKey" = 'debit_note:' || n.id AND je.status = 'posted'
WHERE c.status = 'settled' AND c."journalEntryId" IS NULL
ORDER BY c."organizationId", c."settledAt";
```

- `entryNumber` set: the note posted Dr AR / Cr revenue. Correct it (step 2).
- `entryNumber` null: the note is still a draft and posted nothing, but AP was never reduced. Correct it the same way
  (step 2); in 2a the draft note is discarded instead of reversed.

## 2. Correct each claim

A manual journal entry cannot reduce a vendor bill's residual, so the correction re-settles the claim through the
fixed code, which posts the supplier credit and reduces the bill in one transaction.

a. **Undo the old note.**
   - Posted note (`entryNumber` set): reverse its journal entry from the journal entry screen (Reverse, permission
     `journal_entry:reverse`). The reversal mirrors the partner-tagged lines, so it removes the AR debit, the revenue
     credit and the output VAT and the AR sub-ledger stays reconciled. Do not issue a customer credit note against the
     debit note: that would create a second AR document. The debit note itself stays `posted`; note the reversal
     number on it.
   - Draft note: cancel it (`PATCH /debit-notes/:id/cancel`, or Cancel on the debit notes screen).

b. **Put the claim back to `accepted`.** There is no route for this, so a DBA runs it, with the owner's written
   approval recorded in the change ticket. The claim row is not ledger history, and the guard below only touches a
   legacy settlement:

   ```sql
   UPDATE "SupplierClaim"
      SET status = 'accepted', "settledAmount" = NULL, "settledAt" = NULL, "debitNoteId" = NULL,
          version = version + 1, "updatedAt" = now()
    WHERE "organizationId" = :org AND "claimNumber" = :claim
      AND status = 'settled' AND "journalEntryId" IS NULL;
   ```

c. **Settle the claim again** in Procurement → Controls → Supplier claims. It posts Dr AP / Cr expense, stock
   adjustment or price variance / Cr input VAT (`supplier_claim:<id>`) and reduces the bill's residual.

## 3. Check

- `partnerControlReconciles` / the AP per-partner reconciliation report shows no difference for the supplier.
- The revenue account no longer carries the claim amounts for the period.
