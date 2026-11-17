# Deploying the accounting controls branch (`phase/acc-erp-controls`)

Read-only checks to run against a copy of production **before** deploying V512–V520, and what each result means. None
of these queries writes anything. Run them as the migration owner on a restored copy or a read replica.

## 1. Recurring templates and deferral schedules that will pause (V514)

After V514 every existing row is unapproved. A post-mode template then stages drafts instead of posting, and an
active deferral schedule releases nothing until a second user holding `journal_entry:post` approves it.

```sql
SELECT "organizationId", count(*) FILTER (WHERE mode = 'post') AS post_mode_templates, count(*) AS active_templates
FROM "RecurringJournalTemplate" WHERE status = 'active' AND "deletedAt" IS NULL GROUP BY 1 ORDER BY 2 DESC;

SELECT "organizationId", count(*) AS active_schedules, sum("totalAmount" - "releasedAmount") AS unreleased
FROM "DeferralSchedule" WHERE status = 'active' GROUP BY 1 ORDER BY 2 DESC;
```

Any non-zero row: tell that organization's finance lead before deploy, and have a second user approve the templates
and schedules on deploy day (Accounting → Recurring entries / Deferrals → Approve). A schedule approved late catches up
on its next run; nothing is lost, only delayed.

## 2. Payroll on the old engine with "other" deductions (R12)

A v1 run whose items carry deductions that map to no account can no longer be approved.

```sql
SELECT r."organizationId", count(DISTINCT r.id) AS open_v1_runs, sum(i."otherDeductions") AS other_deductions
FROM "HrPayrollRun" r JOIN "HrPayrollItem" i ON i."runId" = r.id
WHERE coalesce(r.engine, 'v1') <> 'v2' AND r.status::text IN ('DRAFT', 'CALCULATED') AND i."otherDeductions" > 0
GROUP BY 1;
```

Rows here are blocked until the organization moves those deductions to engine v2 elements with a GL account.

Payroll amounts are now rounded to the currency's minor unit when a run is calculated (whole shillings for UGX). A run
calculated before deploy but not yet approved holds amounts finer than that, and approval refuses it until it is
recalculated:

```sql
SELECT r."organizationId", o."currencyCode", count(*) AS runs_to_recalculate
FROM "HrPayrollRun" r JOIN "Organization" o ON o.id = r."organizationId"
JOIN "Currency" c ON c.code = o."currencyCode"
WHERE r.status::text = 'CALCULATED' AND EXISTS (
  SELECT 1 FROM "HrPayrollItem" i WHERE i."runId" = r.id
     AND i."netPay" <> round(i."netPay", c."decimalPlaces"))
GROUP BY 1, 2;
```

Recalculate those runs after deploy. Approved and paid runs are not touched.

Pending one-off deductions with no credit account (manual inputs, or fleet fines when no fines-payable account is
mapped) also block approval of the run that picks them up:

```sql
SELECT "organizationId", source, count(*) AS deductions, sum(amount) AS amount
FROM "HrPayrollInput" WHERE status = 'pending' AND kind = 'deduction' AND "creditAccountId" IS NULL AND amount > 0
GROUP BY 1, 2;
```

Cancel and re-add manual ones with an account. For fleet fines, map the fines-payable account, then re-queue the
fines already queued (cancel the deduction in Fleet and deduct again).

## 3. Historical journal entries that do not balance (V513)

The commit-time balance check refuses to change or reverse a posted entry that does not balance in base currency.

```sql
SELECT e."organizationId", e."entryNumber", e."postingDate", sum(l."baseDebit") - sum(l."baseCredit") AS diff
FROM "JournalEntry" e JOIN "JournalLine" l ON l."journalEntryId" = e.id
WHERE e.status::text IN ('posted', 'reversed')
GROUP BY 1, 2, 3 HAVING sum(l."baseDebit") <> sum(l."baseCredit") ORDER BY 1, 3;
```

Expected: no rows. Any row needs an approved correcting entry before anyone tries to reverse it.

## 4. Fiscal periods that already overlap (V512)

V512 checks only new or re-dated periods, so existing overlaps survive the migration but block any edit of those rows.

```sql
SELECT a."organizationId", a.name, b.name AS overlaps
FROM "FiscalPeriod" a JOIN "FiscalPeriod" b ON b."organizationId" = a."organizationId" AND b.id > a.id
 AND b."deletedAt" IS NULL AND b."startDate" <= a."endDate" AND b."endDate" >= a."startDate"
WHERE a."deletedAt" IS NULL;
```

## 5. Supplier claims settled the old way (R11)

See `supplier-claims-legacy-settlement.md`.

## 6. Items that will hit the AR/AP control-account block (V518)

After V518 an account is an AR/AP control account when it is designated, or when V518 designates it because it is
mapped as `accounts_receivable` / `accounts_payable` or used as a partner's receivable / payable account. Manual drafts,
recurring templates, deferral schedules, treasury deposit / withdrawal counterparts, cash-drawer movements and
`post_entry` bank rules can no longer post to it. Run before deploy:

```sql
WITH ctl AS (
  SELECT a.id, a."organizationId", a.code FROM "Account" a
  WHERE a."deletedAt" IS NULL AND (a."controlAccountType"::text IN ('ar', 'ap')
     OR EXISTS (SELECT 1 FROM "AccountMapping" m WHERE m."accountId" = a.id AND m."deletedAt" IS NULL
                AND m.key IN ('accounts_receivable', 'accounts_payable'))
     OR EXISTS (SELECT 1 FROM "Partner" p WHERE p."deletedAt" IS NULL
                AND a.id IN (p."receivableAccountId", p."payableAccountId"))))
SELECT 'draft journal entry' AS item, e."organizationId", e."entryNumber" AS ref
  FROM "JournalEntry" e JOIN "JournalLine" l ON l."journalEntryId" = e.id JOIN ctl ON ctl.id = l."accountId"
 WHERE e.status::text = 'draft'
UNION
SELECT 'recurring template', t."organizationId", t.name
  FROM "RecurringJournalTemplate" t CROSS JOIN LATERAL jsonb_array_elements(t.lines) x
  JOIN ctl ON ctl.id = x->>'accountId'
 WHERE t.status = 'active' AND t."deletedAt" IS NULL
UNION
SELECT 'deferral schedule', d."organizationId", d.id
  FROM "DeferralSchedule" d JOIN ctl ON ctl.id IN (d."sourceAccountId", d."targetAccountId")
 WHERE d.status = 'active'
ORDER BY 2, 1, 3;
```

Each row needs a decision before deploy: re-point it to a non-control account, or replace it with a
control-account adjustment (a manual entry with a reason and a partner on every AR/AP line, posted by a second user
holding `journal_entry:control_adjust`). Also check the V518 migration log for `NOTICE` lines: they list accounts
configured as both AR and AP, or already designated another control type.

## 6b. Draft documents and settings pointing at AR/AP

The sub-ledger services now refuse a user-chosen account that is a designated AR/AP control account, with a 400 at
create / update and again at posting. That covers:

- invoice, credit-note and bill lines (including a product category's income / expense account);
- debit-note lines;
- expense allocations, expense category accounts and expense accruals;
- customer and supplier write-off accounts;
- the bad debt / sales returns accounts in Sales Settings;
- the POS write-off (`bad_debt` mapping);
- every payment tender account: receipts, supplier payments, refunds, deposits, POS and mobile money.

A document's own receivable / payable line still posts to the configured AR / AP, partner-tagged. Existing drafts and
settings saved before deploy are not rewritten. They fail when posted or used. Run before deploy (read-only; the `ctl`
set is the same as in section 6, so it also works before V518 has designated the accounts):

```sql
WITH ctl AS (
  SELECT a.id, a."organizationId", a.code FROM "Account" a
  WHERE a."deletedAt" IS NULL AND (a."controlAccountType"::text IN ('ar', 'ap')
     OR EXISTS (SELECT 1 FROM "AccountMapping" m WHERE m."accountId" = a.id AND m."deletedAt" IS NULL
                AND m.key IN ('accounts_receivable', 'accounts_payable'))
     OR EXISTS (SELECT 1 FROM "Partner" p WHERE p."deletedAt" IS NULL
                AND a.id IN (p."receivableAccountId", p."payableAccountId"))))
SELECT 'unposted document line' AS item, d."organizationId", d."documentType"::text || ' ' || d."documentNumber"
       || ' line ' || dl."lineNumber" AS ref, ctl.code
  FROM "Document" d JOIN "DocumentLine" dl ON dl."documentId" = d.id JOIN ctl ON ctl.id = dl."accountId"
 WHERE d.status::text IN ('draft', 'submitted', 'approved')
UNION
SELECT 'expense allocation', e."organizationId", e.title || ' (' || e.id || ') line ' || x."lineNo", ctl.code
  FROM "ExpenseAllocation" x JOIN "Expense" e ON e.id = x."expenseId" JOIN ctl ON ctl.id = x."accountId"
 WHERE e."deletedAt" IS NULL AND e.status::text IN ('DRAFT', 'RETURNED', 'APPROVED')
UNION
SELECT 'expense category', c."organizationId", c.name, ctl.code
  FROM "ExpenseCategory" c JOIN ctl ON ctl.id IN (c."ledgerAccountId", c."assetAccountId")
 WHERE c."deletedAt" IS NULL
UNION
SELECT 'sales settings', s."organizationId", 'bad debt / sales returns account', ctl.code
  FROM "SalesSettings" s JOIN ctl ON ctl.id IN (s."badDebtAccountId", s."salesReturnsAccountId")
UNION
SELECT 'product category', pc."organizationId", pc.name, ctl.code
  FROM "ProductCategory" pc JOIN ctl ON ctl.id IN (pc."incomeAccountId", pc."expenseAccountId")
 WHERE pc."deletedAt" IS NULL
UNION
SELECT 'account mapping', m."organizationId", m.key, ctl.code
  FROM "AccountMapping" m JOIN ctl ON ctl.id = m."accountId"
 WHERE m."deletedAt" IS NULL AND m.key NOT IN ('accounts_receivable', 'accounts_payable')
UNION
SELECT 'product override (informational)', pr."organizationId", pr.code || ' ' || pr.name, ctl.code
  FROM "Product" pr JOIN ctl ON ctl.id IN (pr."incomeAccountOverrideId", pr."expenseAccountOverrideId")
 WHERE pr."deletedAt" IS NULL
ORDER BY 2, 1, 3;
```

Re-point each row to a non-control account before deploy:

- edit the draft's line account;
- change the expense category, Sales Settings account or product category;
- move the mapping (for example `bad_debt` to the bad debt expense, `purchase_discount` to "Purchase Discounts
  Received").

What each row means:

- **Unposted document lines.** The post fails with "… is a designated accounts receivable control account …". Nothing
  posts and the draft stays editable.
- **Expense categories with an AR/AP ledger account.** Save is refused, and an expense of that category fails when
  accrued or paid.
- **A product category whose income or expense account is AR/AP.** Every invoice / bill of its products fails at
  posting until the category is corrected.
- **`bad_debt` mapped to AR.** The POS write-off fails.
- **Sales Settings bad debt account set to AR.** The customer write-off fails. It also needs an expense or income
  account, or an allowance (contra-asset) account.
- **Product overrides.** These are not read by invoice / bill posting. They are listed so a stock flow is not left
  pointing at AR/AP.

Balances that were genuinely owed must not be cleared with a manual journal on AR/AP. Use the sub-ledger:

- **Customer.** Use a credit note, or the write-off (`POST /sales/invoices/{id}/write-off`, permission
  `sales:write_off:post`).
- **Supplier.** Use a debit note, or the new supplier write-off on the bill (`POST /vendor-bills/{id}/write-off`; needs
  both `expense:post` and `payment:create`, an Idempotency-Key and a reason). It posts Dr AP (supplier) / Cr
  `purchase_discount` or a chosen income / expense account, and lowers the bill's residual in the same transaction.
  It counts as settled, so a written-off bill can no longer be voided.

## 7. Payroll and claims behaviour on day one

- Payroll pays whole currency units (whole shillings for UGX); recalculate the runs found in section 2.
- A shortage / damage / quality claim settled without verified quantity lines now credits purchase price variance,
  not stock adjustment expense. Stock and GRNI are only ever credited by a `stock_adjustment` settlement whose
  quantity lines a second user verified. If the supplier agreed more than the verified goods' receipt cost, the rest
  is credited to PPV.
- A manual payroll deduction (HR → Payroll operations → One-off inputs) now needs a credit account. Pending manual
  deductions queued before deploy have none and block approval: cancel and re-add them with the account.

## 8. Document reversals (V520)

Owner decision 2026-11-17: an entry posted by a customer / supplier sub-ledger document (invoice, credit note, bill,
bill write-off, debit note, payment, customer deposit, POS invoice / refund, supplier claim, goods receipt, purchase
order payment, cash sale, expense, opening-balance batch) can no longer be reversed from the journal entry. `POST
/journal-entries/{id}/reverse` (and the `journal_entry` workflow's `reverse` action) answers
**400** naming the document and its action, for the entry itself and for the reversal a document workflow posted. The
journal entry page hides **Reverse** on those entries and links to the document. Manual entries, control-account
adjustments, period close, revaluation and bank-rule entries are reversed as before.

Each document has its own reversal workflow. It needs a **reason**, runs in one transaction under row locks, can be
replayed safely, and writes one `DocumentReversal` row. That row holds the reason, who, when, the original and
reversal entry ids and the allocations it released, and it is audited on the document:

| Document | Route | Permission | Approval entity type | Allocations |
|---|---|---|---|---|
| Sales invoice | `POST /invoices/{id}/cancel` `{reason}` | `invoice:cancel` | `invoice_cancel` (existing) | refused while payments, deposit applications, posted credit notes or debit notes are applied |
| Supplier bill | `POST /vendor-bills/{id}/cancel` `{reason}` | `expense:cancel` | `vendor_bill_cancel` | refused while supplier payments, a write-off, posted debit notes or settled claims are applied |
| Credit note | `POST /credit-notes/{id}/reverse` `{reason}` (Idempotency-Key) | `credit_note:reverse` (new) | `credit_note_reverse` | its credit allocations are unwound (invoice residual restored); refused while refunded or paid out; restocked goods are issued back out (refused when no longer on hand) |
| Debit note | `PATCH /procurement/debit-notes/{id}/reverse` `{reason}` (Idempotency-Key) | `debit_note:reverse` (new) | `debit_note_reverse` | gives back exactly what it raised on the invoice / bill (refused once that part is paid) or, for a return to vendor, what it took off the bill; returned goods come back into stock |
| Payment | `POST /payments/{id}/void` `{reason}` (existing) | `payment:void` | `payment_void` | allocations given back (existing); now refused while matched to a bank statement line (unmatch first) |
| Deposit application | `POST /customer-deposits/{id}/applications/{paymentId}/reverse` `{reason}` (new) | `payment:void` | none | the application's payment is voided, the invoice re-opens, the amount returns to the deposit |

**Before deploying**

- **Clients that cancel invoices or void bills without a body now get 400 "A reason is required ...".** The web app
  sends one. Update any integration that posts to `/invoices/{id}/cancel` or `/vendor-bills/{id}/cancel`.
- **Invoices and bills that are partly settled can no longer be cancelled or voided.** Before, they could be. Void the
  payments, reverse the debit / credit notes, then cancel. Cash-sale invoice cancel still unapplies its deposits
  itself.
- `credit_note:reverse` and `debit_note:reverse` are granted to **Administrator** only. Grant them to other finance
  roles if they need them.
- **Separation of duties is opt-in.** Switch it on in Approval controls → Separation of duties → "Document reversal".
  Then whoever created or posted the document, or entered the payment, cannot reverse it, unless an approval request
  for the pair was approved. Approval workflows can be configured for the five entity types above.
- **Find sub-ledger entries that were already reversed generically before this release.** The document is still open
  while its entry is reversed, so the sub-ledger disagrees with the control account. These documents need a
  control-account adjustment naming the partner, or a re-issue:

```sql
SELECT d."documentType", d."documentNumber", d.status, d."amountResidual", e."entryNumber"
FROM "Document" d JOIN "JournalEntry" e ON e.id = d."journalEntryId"
WHERE e.status = 'reversed' AND d.status::text IN ('posted', 'paid')
  AND NOT EXISTS (SELECT 1 FROM "DocumentReversal" r WHERE r."documentId" = d.id);

SELECT p."paymentNumber", p.status, e."entryNumber"
FROM "Payment" p JOIN "JournalEntry" e ON e.id = p."journalEntryId"
WHERE e.status = 'reversed' AND p.status::text = 'posted';
```

- **Debit notes posted before V520 have no recorded `documentAdjustment`.** Reversing one assumes it raised the full
  total, or, for a return to vendor, min(total, payable − paid − residual). Check the bill or invoice after reversing an
  old note.

**Not covered yet (use a control-account adjustment naming the partner):**

- supplier bill write-offs (`vendor_bill_writeoff`);
- POS invoice write-offs;
- supplier-claim settlements;
- opening-balance batches.
