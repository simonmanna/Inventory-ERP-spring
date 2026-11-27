# Purchase flow production readiness — 2026-11-27

Scope: the purchase flow from purchase request to supplier payment, with returns, supplier claims and landed cost,
and how it ties into inventory, accounting and payments.

Method:
- Read-only review of `origin/main` at `d69dc2d6` by five reviewers, one per area: orders, receipts, bills, payments,
  and returns / landed cost.
- Each blocker was then checked in the code.
- The owner asked for every blocker and high finding to be fixed ("fix all the blockers and high issues").
- The fixes are on branch `fix/purchase-readiness`. It merges four streams: `fix/purchase-pay`,
  `fix/purchase-bills`, `fix/purchase-returns` and `fix/purchase-receipts`.
- Behaviour changes, new messages and new defaults are in
  [procurement.md](../spec/procurement.md#purchase-readiness-fixes-owner-approved-2026-11-27--supplier-payments-stream-pay).

## Verdict

**Not production-ready on d69dc2d6.**

Sound on d69dc2d6:
- the stock-to-GL link at receipt;
- locking, tenancy and idempotency;
- period checks and FX handling.

But four blockers could pay a supplier twice, misstate stock or AP, or put the subledgers out of step with the GL.
Three items the 2026-11-17 audit counted as closed were still open: #21, #24 and #25.

**After `fix/purchase-readiness`:**
- Every blocker and high finding is fixed and has a test.
- The medium items that sat in the same code are fixed too.
- The open items listed at the end remain.
- Run the data checks below once per database **before** deploying, read-only. A fix in the code does not repair
  data that is already wrong.

## Blockers

| # | Defect (on d69dc2d6) | Fix | Test |
|---|---|---|---|
| B1 | The supplier overpayment cap summed open bill residuals only. Payments from the Supplier Payments screen carry no allocations, so they never lowered it, and the same bill could be paid twice. | The cap is the A5 tie-out measure: open bills at carrying rate less unallocated payments (`ApOpenItemsSql`). Payments to one supplier are serialised on the Partner row. | `PurchaseReadinessPayIT` |
| B2 | AP aging ignored unallocated payments, added up currencies unconverted, and counted received service lines as payable. | Base currency at carrying rate, less unapplied payments (new `unapplied` field), stocked lines only. The same measure is used by `OpenItems` and the snapshot. | `PurchaseReadinessPayIT` (aging = AP ledger) |
| B3 | Two lines of one bill for the same product both consumed the same open receipt quantity. GRNI was over-cleared, and the bill residual stopped matching AP in the GL. | The matcher subtracts quantity that earlier lines of the same bill already claimed. V692 adds CHECK `billedQuantity <= quantity`. | `PurchaseReadinessBillsIT` |
| B4 | The goods-receipt screen used the legacy landed-cost engine. It capitalised the landed cost of goods already sold into stock (`onHand = min(qty, receipt qty)`), accepted any credit account, and had no duplicate, approval, tax or currency checks. | The legacy create and post routes are retired (400 pointing to vouchers). The receipt screen opens a landed cost voucher for the receipt. Reading and reversing historical entries still work. | `PurchaseReadinessReturnsIT`, `LandedCostGapsIT.receiptScreenVoucher` |

## High

| # | Defect | Fix |
|---|---|---|
| H1 | The default `billWithoutReceipt=auto_receive` let a bill posted before the goods receive them. The PO receipt then doubled stock and AP. | Default `block`. In `auto_receive` mode, a later receipt absorbs the stock the bill already received (`VendorBillAutoReceipt`, V692). |
| H2 | The duplicate-bill check was off by default, and `INV-001` ≠ `INV001`. | New default `block_reference`: references are compared as lower-case letters and digits. Same-amount matches only warn. An advisory lock stops concurrent duplicates. |
| H3 | A return to vendor against a bill had no quantity or product cap. | Capped at billed − returned − claimed, per product and variant in base units, under the bill lock. Stock must be available. |
| H4 | A supplier claim and a debit note could credit the same receipt quantity twice. | Each cap deducts the other, in both orders, for quantity and value. |
| H5 | PO pay skipped the supplier-payment approval and guards. | PO pay goes through the shared `SupplierPaymentControls`. |
| H6 | One supplier-payment approval covered unlimited later payments. | An approval is consumed by exactly one payment (V690 `consumedAt` / `consumedRef`). A race for one approval gets 409. |
| H7 | Supplier bank-account dual control gated nothing, and suspended or blacklisted suppliers could be paid. | Suspended and blacklisted suppliers are refused. New setting `procurement.supplierBankVerification` (default `block`): a `bank` payment must name a verified supplier bank account. |
| H8 | Bill `overrideApproval` bypassed the workflow's steps, bands and approvers. The last editor could approve. | The override goes through `ApprovalsService.decide`. `updatedBy` is stamped, and the last editor cannot approve. The approval hash covers dates, reference and branch. |
| H9 | A receipt could be reversed after its goods were sold (stock went negative). Reversal left QC checks, quarantine and inspections alive. | Reversal requires the goods on hand, whatever `allowNegativeStock` says. QC and inspection are unwound, or the reversal is refused while a decided check still holds goods. |
| H10 | Purchase request lines accepted negative or zero quantity and price. PO line `taxRate` was unvalidated. | Bean Validation plus service checks. Tax rate 0–100 and equal to the Tax master's rate. V696 adds CHECKs. |

## Behaviour changes on deploy

These apply to every organisation that has no stored value for the setting:
1. A supplier bill for stock items needs a goods receipt (`procurement.billWithoutReceipt=block`).
2. A duplicate supplier invoice reference is refused (`bill.duplicateCheck=block_reference`).
3. A `bank` supplier payment, and a PO payment (whose method defaults to `bank`), needs a verified supplier bank
   account (`procurement.supplierBankVerification=block`). Set up verified accounts, or pay by cheque, cash or mobile
   money.
4. PO lines with a tax and no rate used to be stored at 0 %. They now take the tax's rate, so PO totals and receipt
   input VAT go up.
5. The legacy `POST /procurement/landed-costs` and `/:id/post` routes answer 400.

## Data checks before deploy (read-only)

Run these once per database. Each query lists candidates for a person to review. None of them corrects data, and
none should be turned into an automatic repair without separate approval.

```sql
-- B1 / H5: suppliers paid more than owed (AP control in debit), per the GL
SELECT l."organizationId", l."partnerId", SUM(l."baseCredit" - l."baseDebit") AS owed
FROM "JournalLine" l JOIN "JournalEntry" e ON e.id = l."journalEntryId"
JOIN "AccountMapping" m ON m."accountId" = l."accountId" AND m."organizationId" = l."organizationId"
     AND m.key = 'accounts_payable' AND m."deletedAt" IS NULL
WHERE e.status IN ('posted', 'reversed') AND l."partnerId" IS NOT NULL
GROUP BY l."organizationId", l."partnerId" HAVING SUM(l."baseCredit" - l."baseDebit") < -0.005;

-- B3: receipt lines billed more than received (also tells you whether V692's CHECK validated)
SELECT l."organizationId", g."receiptNumber", l."productId", l.quantity, l."billedQuantity"
FROM "GoodsReceiptLine" l JOIN "GoodsReceiptNote" g ON g.id = l."goodsReceiptId"
WHERE l."billedQuantity" > l.quantity + 0.0001;

-- B4: posted legacy per-receipt landed costs; review whether part of the goods had been sold at posting
SELECT "organizationId", code, "goodsReceiptId", "totalAmount", "capitalizedAmount", "postedAt"
FROM "LandedCost" WHERE "voucherType" = 'receipt' AND status = 'posted' ORDER BY "postedAt";

-- H1: stock a bill received itself while a posted PO receipt of the same supplier and product is still unbilled
SELECT d."organizationId", d."documentNumber" AS bill, d."partnerId", l."productId",
       l."quantityChange" AS bill_received_qty, g."receiptNumber" AS receipt,
       gl.quantity - gl."billedQuantity" AS receipt_unbilled_qty
FROM "InventoryLedger" l
JOIN "Document" d ON d.id = l."referenceId" AND d."organizationId" = l."organizationId"
JOIN "GoodsReceiptNote" g ON g."organizationId" = d."organizationId" AND g."partnerId" = d."partnerId"
     AND g."purchaseOrderId" IS NOT NULL AND g.status = 'posted' AND g."deletedAt" IS NULL
JOIN "GoodsReceiptLine" gl ON gl."goodsReceiptId" = g.id AND gl."productId" = l."productId"
WHERE l."referenceType" = 'vendor_bill' AND l."quantityChange" > 0 AND d.status::text IN ('posted', 'paid')
  AND gl.quantity > gl."billedQuantity";

-- H2: posted bills of one supplier sharing a normalised reference
SELECT "organizationId", "partnerId", lower(regexp_replace(reference, '[^A-Za-z0-9]', '', 'g')) AS ref,
       array_agg("documentNumber") AS bills
FROM "Document" WHERE "documentType"::text = 'vendor_bill' AND status::text IN ('posted', 'paid')
  AND reference IS NOT NULL AND regexp_replace(reference, '[^A-Za-z0-9]', '', 'g') <> ''
GROUP BY 1, 2, 3 HAVING count(*) > 1;

-- H3: return notes against a bill that return more of a product than the bill billed
SELECT n."organizationId", d."documentNumber" AS bill, nl."productId",
       SUM(nl.quantity) AS returned,
       (SELECT SUM(dl.quantity) FROM "DocumentLine" dl WHERE dl."documentId" = d.id AND dl."productId" = nl."productId") AS billed
FROM "DebitNote" n JOIN "DebitNoteLine" nl ON nl."debitNoteId" = n.id
JOIN "Document" d ON d.id = n."documentId"
WHERE n.direction = 'inbound' AND n.reason = 'returned_goods' AND n.status = 'posted' AND n."deletedAt" IS NULL AND nl."productId" IS NOT NULL
GROUP BY n."organizationId", d.id, d."documentNumber", nl."productId"
HAVING SUM(nl.quantity) > COALESCE((SELECT SUM(dl.quantity) FROM "DocumentLine" dl
                                    WHERE dl."documentId" = d.id AND dl."productId" = nl."productId"), 0);

-- H9: stock gone negative (a reversal of consumed goods is one cause; review each)
SELECT "organizationId", "productId", "locationId", quantity FROM "StockItem" WHERE quantity < 0;

-- H10: request / order lines that V696's CHECKs would reject (the migration leaves a CHECK NOT VALID if any exist)
SELECT 'PR line' AS kind, id, "organizationId" FROM "PurchaseRequestLine"
 WHERE quantity <= 0 OR "estimatedUnitPrice" < 0
UNION ALL
SELECT 'PO line', id, "organizationId" FROM "PurchaseOrderLine"
 WHERE quantity <= 0 OR "unitPrice" < 0 OR "taxRate" < 0 OR "taxRate" > 100;
```

Check the column and enum names against the live schema before you run these. A return to vendor is an `inbound`
`returned_goods` note. The `DocumentLine` quantities in H3 are in bill units.

## Still open (not in scope, or an owner decision)

- A return does not reduce the PO line's `receivedQuantity`. Changing that alters three-way match and PO status, so it
  needs an owner decision.
- Inactive or archived suppliers can still be paid, so their open debts can be settled. Suspended and blacklisted
  suppliers are blocked.
- The PO `receive` route silently uses the order line's variant. Only the goods-receipt draft path refuses a different
  variant.
- Reversing a receipt whose lots have expired, under the `skip_expired` policy, may be refused.
- The Supplier Payments screen has no bill picker: payments are advances until someone allocates them. The cap and the
  aging now account for that.
- Medium and low items from the audit that were not in the fixed files:
  - draft POs are not counted against the accounting budget in block mode;
  - cancelling a partly received PO frees its budget;
  - a PO change can raise prices without approval when the total does not rise;
  - the GRNI reconciliation report mixes currencies and as-of dates;
  - foreign-currency returns post at the note rate;
  - a PO payment cannot be reversed;
  - PR status writes have no status predicate;
  - `POST /purchase-orders` takes no idempotency key.
