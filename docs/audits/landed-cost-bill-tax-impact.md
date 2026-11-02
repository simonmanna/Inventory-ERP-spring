# Impact analysis — separate tax lines on generated landed-cost supplier bills (D-PC3)

- Status: **for owner review. No code or migration until approved** (owner decision D-PC3, 2026-11-03).
- Context: [ADR-S036](../adr/ADR-S036-landed-cost-completion.md) deferred this item;
  [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md); [PC roadmap](../roadmap/costing-production-ready-plan.md),
  PC-5.

## 1. Where input tax comes from today

| Flow | GL | VAT return (`TaxReportService.summary`) |
|---|---|---|
| Ordinary vendor bill | Dr input tax per bill line tax (`DocumentLine.taxAmount`, `taxId`) | **Counted**: `DocumentLine` of posted `vendor_bill`, × `Document.exchangeRate` |
| Landed-cost voucher charge with `taxAmount`, recoverable | Dr `tax_receivable` on the **voucher journal** (`LandedCostVoucherService.compute`) | **Not counted**: the voucher is not a `Document` |
| Generated landed-cost supplier bill (`billMode = generate`) | Dr 2155 clearing for the gross amount (charge + tax) on one line with no `taxId`; Cr AP | **Not counted as tax**: the line has `taxAmount = 0`, so the gross amount appears in the bill's taxable base and in "untaxed" |
| Linked bill (`billMode = link`) | Whatever the user entered on the bill. If the bill carried tax lines **and** the voucher charge also carried `taxAmount`, input tax is recognised twice | Counted from the bill only |
| Procurement debit note | reduces input tax | counted (`DebitNoteLine`) |
| GL cross-check (`gl(...)`) | movement on `tax_payable`, `tax_receivable` and each tax's account | includes voucher input tax |

**Findings:**

- **H1 (current defect, outside this change):** landed-cost input tax sits in the GL but not in the document-based VAT
  return. The return therefore understates input tax and disagrees with its own GL cross-check by the voucher tax.
  ADR-S036 said "the VAT return reads it there". That holds only for the GL cross-check figure.
- **H2 (current defect):** for a generated bill, the gross charge (net + tax) is reported as a taxable purchase with no
  tax.
- **H3 (current risk):** a linked bill with tax lines, plus voucher `taxAmount`, means double input tax. Nothing
  prevents it today.
- **H4 (related, pre-existing):** the return converts with `Document.exchangeRate`. A foreign-currency document posted
  at a table rate (no stated rate; stored as the default 1) is reported at 1. Since PC-0 the GL posts the resolved
  rate, which is snapshotted in `JournalEntryFxRate`. The return should read the snapshot.

## 2. Proposal: one authoritative source

**The supplier bill becomes the single source of landed-cost input tax.** The voucher stops debiting input tax itself.

| Item | Proposed |
|---|---|
| Generated bill lines | One line per charge: net amount (no tax) on 2155 clearing, and the charge's `taxId` / `taxAmount` on the bill line. Only when `taxRecoverable = true`. A non-recoverable tax stays inside the net cost (V290 semantics) |
| Bill posting | Ordinary bill engine: Dr 2155 (net) + Dr input tax (line tax) / Cr AP (gross). The VAT return counts it like any bill |
| Voucher journal | Credits 2155 with the **net** charge only. No `tax_receivable` debit for charges whose tax is billed (generated or linked) |
| `billMode = none` with tax | Refused: `Charge <label>: recoverable tax needs a supplier bill (generate or link)`. Otherwise input tax would have no document |
| Linked bills | The voucher charge's `taxAmount` must match the linked bill's tax for that line; the voucher never debits input tax itself. This closes H3 |
| Return conversion | Snapshot rate of the document's journal (`JournalEntryFxRate`), else `Document.exchangeRate`. This closes H4 for all documents |

The result: input tax appears once (on the bill), the return equals its GL cross-check, and 2155 still nets to zero per
invoice (net in, net out).

## 3. Historical transactions

- **Posted vouchers and bills are never rewritten.** Their GL stays as posted; VAT periods already filed stay
  reproducible.
- A cut-over date setting, `costing.landedCost.billTaxFrom` (date), applies the new rule only to vouchers posted on or
  after it. Older vouchers keep reversing exactly as they were posted.
- The return gains a reconciliation line, **"Input tax on landed-cost vouchers (pre cut-over)"**, read from the voucher
  journals for periods before the cut-over. This states H1 explicitly instead of hiding it. Whether to amend filed
  periods is a tax decision for the owner's accountant, not a code change.

## 4. Migration strategy

1. **V29x:** add the setting and nothing else structural. Bill lines already carry `taxId` / `taxAmount`.
2. **Code:** a flag-gated branch in `LandedCostVoucherService.compute` / `raiseSupplierBills`; the VAT-return
   conversion via snapshot.
3. **No data migration.** History is preserved by the cut-over date.

## 5. Acceptance tests (to be reviewed before implementation)

| # | Scenario | Expected |
|---|---|---|
| T1 | Before/after golden: a fixed set of periods with ordinary bills, invoices, POS, debit notes, and **no** landed tax | The VAT return is byte-identical before and after |
| T2 | Voucher after the cut-over: freight 1,000 + VAT 180, recoverable, generated bill | Bill: Dr 2155 1,000 + Dr input tax 180 / Cr AP 1,180. Voucher: Dr stock 1,000 / Cr 2155 1,000. Return: input tax +180 once. GL cross-check equals the return |
| T3 | Linked bill with tax 180 and a voucher charge stating 180 | Input tax once (from the bill) |
| T4 | Linked bill tax ≠ the charge's stated tax | Refused at voucher creation |
| T5 | `billMode = none` with recoverable tax after the cut-over | Refused |
| T6 | Non-recoverable tax | Unchanged from V290: capitalised with the charge, no input tax, no bill tax line |
| T7 | Voucher posted before the cut-over, reversed after | Reversal mirrors the original (voucher-journal tax); the pre-cut-over line in the return shows it |
| T8 | Foreign-currency bill at a table rate | Return converts at the snapshot rate (H4) |
| T9 | Tenant isolation and authorization on the new setting | — |

## 6. Decisions requested

1. Single source = supplier bill, as proposed (§2).
2. Cut-over date mechanism instead of rewriting history (§3).
3. Whether to fix H4 (return conversion via snapshot) in the same change. It affects every foreign-currency document,
   not only landed cost.
4. Who decides about amending VAT periods already filed (H1).
