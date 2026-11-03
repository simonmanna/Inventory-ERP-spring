# ACC-0 verification report

| | |
|---|---|
| Phase | ACC-0, verification only. No production code changed. |
| Date | 2026-11-03 |
| Branch | `phase/ACC-0-verification` (worktree), based on `main` @ 6fa2c29b |
| Owner of all findings | accounting |
| Status | **Awaiting owner review.** ACC-1 has not started. |

**Method.** Every finding from the read-only audit got a reproduction test that asserts the **correct** accounting
outcome. A finding is **Confirmed** only when that test fails against the current code. Suspicious code alone never
confirms a finding. The test strategy is in `ACCOUNTING-TEST-STRATEGY.md`; the revised plan is in
`ACC-EXECUTION-PLAN.md`.

## Run

| Check | Result |
|---|---|
| `./gradlew acc0VerificationTest` (tagged) | **23 run, 0 passed, 23 failed.** Every tagged test reproduces its defect. |
| Tagged tests discovered | 23 `@Tag("acc-known-defect")` occurrences in the source = 23 run. Nothing silently skipped. |
| `./gradlew test --tests "com.inventoryerp.accounting.verification.*" …` (untagged) | 14 run, 14 passed, after one test-design fix (see the tenancy observation O1). |
| JUnit XML | `build/test-results/acc0VerificationTest/TEST-*.xml`, with failures recorded as `<failure>` elements |
| `./gradlew build` | See §Build at the end. |
| `src/main/**` changed | none |

The first run produced 4 failures that came from the tests themselves, not the product. I corrected and re-ran them
without weakening any assertion:

- The snapshot rebuild route returns 201, not 200.
- The debit-note scenario was re-expressed as its commercial event.
- The stock-cancel assertions were made "soft" so all four deviations get reported.
- The opening-balance "retry without key" case was rewritten (see P1-b).

---

## A. Defects (existing logic produces wrong results)

Columns:

- **Hist.**: does the defect affect records already posted?
- **Mig.**: is a data correction or migration needed later? Any correction is approved and posted as entries; nothing is
  repaired silently.
- **API**: impact on API compatibility.

### B1: P&L of a closed period shows zero (**Confirmed**, ACC-1)

- **Test:** `Acc0FinancialReportingIT.pnlOfClosedMonth`
- **Scenario:** a month with revenue 100,000, COGS 55,000 and opex 25,000. Before close, P&L net = 20,000 ✔. Then the month is closed.
- **Actual:**
  - `GET /reports/accounting/profit-and-loss` for that month returns **revenue 0, net profit 0**.
  - The closing entry Dr revenue 100,000 / Cr COGS 55,000 / Cr opex 25,000 / Cr RE 20,000 is included in the P&L range.
  - The lifecycle test shows the same thing: the year-to-date P&L after month-end close is **0, expected 250**.
- **Expected:** P&L 100,000 / 20,000. Balance sheet and post-closing TB include the closing entry; they balanced ✔, and RE = 20,000 Cr ✔.
- **Root cause:**
  - `FinancialReportsService.pnl` (`accounting/application/FinancialReportsService.java:186`) uses `LedgerQueries.balances` without excluding `sourceType IN ('period_close','fiscal_year_close')`.
  - The same omission exists in the `reporting/application/LedgerAggregator` P&L path.
  - Only `FiscalYearService.profitAndLossNet` (line ~233) excludes closing entries.
- **Hist.:** yes, every closed period's P&L reads 0. Read-only detection:
  ```sql
  SELECT "organizationId", count(*) FROM "JournalEntry" WHERE "sourceType" = 'period_close' AND status = 'posted' GROUP BY 1;
  ```
- **Mig.:** none. This is a report-query fix; posted data is correct.
- **API:** response shape unchanged; values become correct. A pre-/post-closing TB toggle would be an optional new query parameter.

### B2: Fiscal-year close compares transaction-currency amounts (**Confirmed**, ACC-1)

- **Test:** `Acc0FinancialReportingIT.fiscalYearCloseInBaseCurrency`
- **Scenario:** a foreign sale of 1,000 in the test currency at rate 2 (2,000 base) plus a base expense of 500. All periods closed, sign-offs done.
- **Actual:** `POST /fiscal-years/{id}/close` is **refused** with
  `400 "Cannot close FY2026: Profit and loss closed to retained earnings"`. A multi-currency organization cannot close its year at all.
- **Expected:** close succeeds; net income 1,500 base; closing cash 1,500; RE 1,500.
- **Root cause:** `FiscalYearService` lines 230, 270 and 400 sum `l.debit`/`l.credit` (transaction amounts). The period-close entry is in base, so the P&L-closed check never reaches 0 for foreign activity. The trial-balance check and the `FiscalYearBalance` snapshot use the same columns.
- **Hist.:** only if a multi-currency organization has already closed a year. Detection: `FiscalYearBalance` rows for organizations with foreign-currency lines in that year. Expected count is 0 (close is blocked).
- **Mig.:** none expected. Re-snapshot if any rows are found.
- **API:** none.

### B3: FX revaluation re-posts the whole difference on every run (**Confirmed**, ACC-2)

- **Test:** `Acc0FxIT.receivableRevaluation`
- **Scenario:** a monetary asset of 10,000 FC at 1.08 (10,800 base). Closing rate 1.12.
- **Actual:**
  - First run +400 ✔.
  - **Second run on the same date: cumulative 800.** It must stay 400.
- **Root cause:** `RevaluationService.java:73` skips lines whose `currencyId` is null or base. Its own adjustments post through `PostingRequest.of` with no currency (lines 110–117), so they never enter the carrying value. Every run recomputes against the original 10,800. Other contributing factors:
  - no posting key;
  - `double` amounts (line 110);
  - a constant `'adhoc'` period;
  - no reversal.
- **Not reproduced:** the AP side posts the correct −200 on a single run (`Acc0FxIT.payableRevaluation` ✔). It shares the rerun defect.
- **Reachability:** no route calls `RevaluationService` (Nest has none), so no tenant can have triggered it yet.
- **Hist.:** no, because the service is unreachable. Detection:
  ```sql
  SELECT count(*) FROM "JournalEntry" WHERE "sourceType" = 'fx_revaluation';
  ```
  Expected 0.
- **Mig.:** none.
- **API:** the new revaluation routes are an owner-approved deviation (Nest has none).
- **Subledger observation (input to the design decision in §C1):** the adjustment is posted to the control account only. Per-document carrying rates (`DocumentFxRates`, ADR-S054) are unchanged, so after a revaluation AR control ≠ Σ open items × carrying rate.

### B4: Exchange rates are shared across tenants (**Confirmed, security / tenancy**, ACC-2)

- **Test:** `Acc0FxIT.ratesAreTenantScoped`
- **Actual:**
  - Org A posts rate 5 for (FC→base, day D). Org B, a different tenant, then posts 7 for the same pair and day.
  - **Org A now resolves 7.**
  - Org B's write silently overwrote org A's rate. The unique index is on `(fromCode, toCode, asOf)`, and `CurrencyRate` has no `organizationId` (`V1__baseline.sql:3594`).
- **Impact:** any tenant holding `currency:update` changes every other tenant's FX postings and reports.
- **Hist.:** possible on multi-tenant deployments. Detection (read-only, per deployment):
  ```sql
  SELECT "fromCode", "toCode", "asOf", "createdBy" FROM "CurrencyRate" ORDER BY "updatedAt" DESC;
  ```
  Then compare against `JournalEntryFxRate`, which snapshots the rate actually used per entry, so posted entries keep their own rate.
- **Mig.:** yes. A new `V{n}` adds nullable `organizationId` (null = shared/global), adds RLS, and changes the unique index to `(organizationId, fromCode, toCode, asOf)`. Existing rows stay global.
- **API:** no shape change. Writes become tenant-scoped.

### B5: API auto-match (unsigned mode) (**Partially reproduced**, ACC-6)

- **Tests:** `Acc0BankReconciliationIT.outgoingLineMatches`, `noCrossRunDuplicate`
- **Actual:**
  1. Statement line −500 vs outbound payment 500: **0 matched** (expected 1).
  2. A payment matched in run 1 is **matched again** by a second line in run 2 (expected 0).
- **Not reproduced:** `signed: true` mode, which the web screen uses (`web/src/features/accounting/bank-reconciliation.ts:176`), handles both cases correctly (`signedModeIsCorrect` ✔). The web UI is therefore not affected; API clients using the default mode are.
- **Root cause:** the unsigned branch at `BankReconciliationService.java:99–120` compares `amount = ?` with the signed amount and only de-duplicates within one run (`used` set).
- **Hist.:** possible double-matched payments from API use. Detection:
  ```sql
  SELECT "matchedPaymentId", count(*) FROM "BankStatementLine" WHERE status = 'matched' GROUP BY 1 HAVING count(*) > 1;
  ```
- **Mig.:** none. Unmatch through the existing route if any duplicates are found.
- **API:** behaviour change of the Nest-parity default (owner decision **D-B5**: make signed the default, or keep the unsigned default and fix both bugs).

### B6: Reversal dated "today", not the original date, while the period is open (**Confirmed**, ACC-3)

- **Test:** `Acc0ReversalIT.reversalInOpenPeriodKeepsOriginalDate`
- **Scenario:** invoice dated 2026-10-10 (open October period), cancelled on 2026-11-03.
- **Actual:** reversal dated **2026-11-03**. The October balances keep the revenue while November carries the negative.
- **Expected:** 2026-10-10. The link `reversedEntryId` ↔ `reversalOfId` is present ✔.
- **Not reproduced:** a closed original period correctly reverses into the open month (`reversalOfClosedPeriodLandsInOpenPeriod` ✔).
- **Root cause:** `PostingService.reverse(id, null, …)` defaults the date to `Instant.now()`. All cancel/void callers pass null.
- **Hist.:** yes, cancelled documents whose reversal crossed a month. Detection:
  ```sql
  SELECT o.id FROM "JournalEntry" o JOIN "JournalEntry" r ON r."reversalOfId" = o.id
  WHERE date_trunc('month', o."postingDate") <> date_trunc('month', r."postingDate");
  ```
- **Mig.:** none automatic. If the owner wants restated history, it goes through approved correction entries.
- **API:** none. Needs the org reversal-date policy (owner decision **D-B6**).

### B7: Cancelling a stock invoice does not reverse the stock issue (**Confirmed**, ACC-3)

- **Test:** `Acc0ReversalIT.cancelRestoresStock`
- **Scenario:** 10 units received at 4, then an invoice for 3 units (COGS 12), then cancel.
- **Actual:**

  | Item | Actual | Expected |
  |---|---|---|
  | Stock quantity | 7 | 10 |
  | Stock value | 28 | 40 |
  | COGS | 12 | 0 |

  The **second cancel also returns 200** (an idempotent echo, not a refusal).
- **Insufficient-stock case** (`cancelAfterInsufficientStock`): 3 units on hand, 5 sold (stock −2), cancel. Stock stays at **−2** (expected 3).
- **Root cause:** the `invoice` workflow `cancel` transition (`InvoicingWorkflowsInitializer`) reverses only the invoice's GL entry. The `stock.issue` movement and its INV-journal COGS entry are left untouched.
- **Hist.:** yes. Detection: cancelled `sales_invoice` documents that have `InventoryLedger` rows with `referenceId` = the invoice and no compensating movement.
- **Mig.:** approved correcting stock movements, never a silent edit.
- **API:** none. The second-cancel 200 is a Nest-parity echo; owner decision **D-B7b**: keep it, or return 400.
- **Not covered in ACC-0:** FIFO consumed-layer behaviour. It needs the ACC-3 compensation design and gets a test there.

### B8: Inbound debit note posts no VAT and is not linked to the bill's open payable (**Confirmed**, ACC-4)

- **Test:** `Acc0ArApIT.supplierChargeWithVat`
- **Commercial event:** the supplier charges an extra 10 + 18 % VAT against bill B (inbound = received from the supplier).
- **Actual vs expected:**

  | Line | Actual | Expected |
  |---|---|---|
  | AP | Cr **10.00** | Cr 11.80 |
  | Input VAT | **0** | Dr 1.80 |
  | Bill B residual | **118** (unchanged) | 129.80 |

- **Root cause:** `DebitNotesService.post` (line 198 ff.) posts on `subtotal` only and never touches the referenced document's residual.
- **Hist.:** yes. Detection:
  ```sql
  SELECT n.id FROM "DebitNote" n JOIN "DebitNoteLine" l ON l."debitNoteId" = n.id
  WHERE n.status = 'posted' AND l."taxId" IS NOT NULL;
  ```
- **Mig.:** approved VAT correction entries per affected note.
- **API:** none.
- **Correction to the audit:** a cancel route does exist (`PATCH …/cancel`). It cancels drafts and refuses posted notes with "reverse with a credit note".
- **Capability gap:** there is no document for a claim **against** a supplier that reduces AP (supplier credit note). See C-gaps.

### B9: Customer credit notes: no quantity cap, no allocation history, excess credit lost from the document (**Confirmed**, ACC-4)

| Test | Actual | Expected |
|---|---|---|
| `creditNoteQuantityCapped` | CN for **3 of 2** invoiced units posts (total 150 against an invoice of 100) | refused |
| `openItemAllocation` | Invoice 10,000 − receipt 4,000 − CN 1,000 → residual 5,000 ✔ and AR control reconciles ✔, but **1 allocation row** (the receipt only). The credit is applied by editing `amountResidual` directly. | 2 rows (receipt + credit) |
| `creditBeyondResidual` | Invoice 100, paid 60, CN 100 → invoice residual 0 ✔, **CN residual 0** | 60, kept as reusable credit |

In `creditBeyondResidual` the 60 surplus exists only as a GL credit on AR, with no open item.

- **Root cause:** `CreditNoteService.post` lowers the invoice residual by min(total, residual), writes no `PaymentAllocation`, sets its own residual to 0, and does not validate quantity or amount against the invoice lines.
- **Hist.:** yes. Detection: partners with an AR control GL balance ≠ open items (the `LedgerInvariants.partnerControlReconciles` query, run read-only per partner).
- **Mig.:** backfill allocation rows for credit notes from `reversedDocumentId` (owner approval required); restore CN residuals.
- **API:** the allocation list on the invoice detail gains rows (additive).

### B10: Report paths drop or omit data (**Confirmed**, ACC-1)

| Test | Actual | Expected |
|---|---|---|
| `snapshotPnl` | After `rebuild-snapshots`, the snapshot P&L shows **other income 0** | 300 |
| `balanceSheetInactiveAccount` | An inactive non-current asset with balance 300 is **left out** of the balance sheet: total assets 700 | 1,000 |

- **Root causes:**
  - `FinancialReportsService.pnl` snapshot branch, lines 199–202: `otherIncome`/`otherExpense` are hard-coded "0" and `from` is ignored.
  - `aggregate` skips inactive accounts (~line 328).
- **Not reproduced:** close → reopen → post → reclose zeroes P&L once with no duplicate closing (`reopenAndReclose` ✔).
- **Hist.:** report-only.
- **Mig.:** none.
- **API:** none.

### B11: Fixed assets: acquisition and disposal never reach the GL; DDB at 4× (**Confirmed**, ACC-5)

| Test | Actual | Expected |
|---|---|---|
| `acquisitionPostsToGl` | `POST /fixed-assets/{id}/acquisition` creates **no journal entry** (FA GL 0) | Dr fixed assets 1,200 |
| `disposalPostsGainLoss` | Cost 1,200, accumulated depreciation 200, sold for 1,050: **accumulated depreciation stays −200** and no gain/loss entry | 0 and gain 50 Cr |
| `doubleDecliningRate` | 1,200 over 60 months, first month **80** | 40 = 1,200 × (2/5) / 12 |

- **Root causes:**
  - `fixedasset/application` posts only from `AssetDepreciationService`.
  - `DepreciationCalculator.java:29` uses factor 4 for `double_declining`; `declining_balance` uses 2, which is actually DDB.
- **Not reproduced:** depreciation never goes below the residual value (`residualFloor` ✔).
- **Hist.:** yes. The FA register and the GL diverge for every acquired or disposed asset. Detection: assets with `AssetAcquisition`/`AssetDisposal` rows and no `JournalEntry` for those sources.
- **Mig.:** approved catch-up entries per asset (owner decision **D-B11** on the cut-over date).
- **API:** none. A posting preview would be additive.

### B12: Floating-point money (**Partially reproduced**, ACC-3)

| Path | Test | Result |
|---|---|---|
| Vendor bill ↔ PO links | `Acc0MoneyPrecisionIT.vendorBillLinkSplitIsExact` | **Confirmed.** A 100 bill over 3 POs stores 33.333333 × 3 = **99.999999** (`VendorBillService.syncPoLinks:280`, `total / ids.size()` in double). Feeds three-way-match amounts. |
| Payroll progressive tax | `Acc0PayrollPrecisionTest.bracketedTaxIsExact` | **Confirmed (intermediate).** 1,234,567.89 → **272370.36699999997**, expected 272370.367 (`HrPayrollService.computeProgressive:239`). Final payslip values are rounded later (`jsRound`); the exposure is a 1-unit rounding flip at the boundary. |
| Payroll, small case | `progressiveTaxIsExact` | Not reproduced (0.30 × 10 % = 0.03 exactly after `Double.toString`). |
| POS tenders | not tested | **Not reproduced by inspection.** `PosInvoiceService.normalizeTenders:585` compares sums in double with a 1e-6 tolerance but posts the tender amounts as given. No wrong-money scenario found. Moved to the ACC-3 clean-up, not a defect. |

- **Hist.:** link amounts are slightly off. Detection:
  ```sql
  SELECT "vendorBillId" FROM "VendorBillLink" GROUP BY 1 HAVING sum(amount) <> (SELECT "totalAmount" FROM "Document" d WHERE d.id = "vendorBillId");
  ```
- **Mig.:** recompute the links (owner approval).
- **API:** none.

### B13: Posting dates (**Not reproduced for expenses; the rest reclassified**, ACC-3)

- **Expenses:** a back-dated credit expense accrues on its expense date (`Acc0PostingDateIT.backDatedExpenseAccrual` ✔). Not a defect.
- **Expense payments:** `PayExpenseRequest` has no payment-date field, so "payment date = document date" cannot apply. This is a **capability gap** (add `paymentDate`), not a wrong posting.
- **Payroll run approval:** posts at `Instant.now()` (`HrPayrollService.java:755`). Code evidence only; **not reproduced by test** because there is no HR payroll IT fixture yet. The ACC-3 fixture will reproduce it. Disposition: **Needs reproduction (ACC-3)**.
- **POS cash movements:** real-time events, so posting "now" is correct. **Not a defect.**

### B14: Foreign-currency expense posts in base only (**Confirmed**, ACC-2)

- **Test:** `Acc0FxIT.foreignExpense`
- **Scenario:** expense of 50 FC at rate 2 (amount 100).
- **Actual:** accrual lines have `currencyId = null` and no `JournalEntryFxRate` snapshot. The foreign amount and rate are lost from the ledger.
- **Root cause:** `ExpensesService` builds the accrual with `PostingRequest.of(...)`, which carries no currency or rate.
- **Hist.:** yes. Detection: `Expense` rows with `currencyCode` ≠ base whose accrual entry has no FX snapshot.
- **Mig.:** none needed for base totals, which are correct. Optionally backfill FX snapshots (owner).
- **API:** none.

### B15 (new, found by the lifecycle test): PO-backed bill shows no open payable (**Confirmed**, ACC-4)

- **Test:** `Acc0LifecycleIT.lifecycle`
- **Scenario:** PO 10 × 50 + 18 % VAT; receive 8 (receipt voucher Dr GRNI + VAT / Cr AP 472 ✔); then a bill for the 8 units linked to the PO.
- **Actual:**
  - Bill `amountResidual` = **0**.
  - `POST /supplier-payments` with allocation to the bill is refused: "Payment amount 300 exceeds supplier outstanding balance 0".
  - After an unallocated payment, AP control GL = **172 Cr**, while the open-items view = **300 Dr unapplied**.
  - The AP sub-ledger does not reconcile, and AP aging / supplier statements are wrong for every PO-backed purchase.
- **Expected:** the bill (or the voucher) is the open AP item, residual 472.
- **Every GL control balance was correct:** inventory 150, GRNI 0, AP 172 Cr, VAT receivable 72, VAT payable 90, AR 190, bank 100, revenue 500, COGS 250, RE 250 after close. Trial balance, balance sheet, inventory tie-out and AR reconciliation all ✔.
- **Root cause (to confirm in ACC-4):** the vouchered recognition model (`procurement-postings.md` §1). The receipt voucher raises AP in the GL with no AP document, and the bill posts only variance and sets residual from its postable total (0).
- **Hist.:** yes, every PO-backed receipt. Detection: `receipt_voucher:%` posting keys per supplier vs the sum of the supplier's bill residuals.
- **Mig.:** set the bill residual from the voucher amount (owner approval).
- **API:** none. Supplier statement and aging values change.

### P1: Idempotency and concurrency

| ID | Test | Disposition |
|---|---|---|
| P1-a | `Acc0IdempotencyIT.concurrentSameKey` | **Confirmed** (ACC-3). Two concurrent posts with one `postingKey`: the loser throws `ConstraintViolationException … "JournalEntry_organizationId_postingKey_key"` (HTTP 500 via the API) instead of returning the winner's entry. No duplicate entry is written; the DB index holds. |
| P1-b | `openingBalanceRequiresKey` | **Not reproduced.** `POST /opening-balances` requires an `Idempotency-Key`, so a keyless blind retry is refused (400) and a retry with the same key replays once (`openingBalanceRetrySameKey` ✔). The random `batchId` cannot double-post through the API. |
| P1-c | `openingBalanceRetrySameKey`, `InvoicingIT.receipts` | Not reproduced. Timeout-after-commit retry with the same key returns the original. |
| P1-d | `concurrentDifferentDocuments` | Not reproduced. 6 concurrent postings to one account: all land, balances correct. |
| P1-e | `failureThenRetry` | Not reproduced. A failure before commit leaves no entry and no orphan lines; the retry posts exactly once. |

### Tenancy and controls (all passed)

- `Acc0TenantIsolationIT.crossTenantReadsRefused` ✔: no data from org A reaches org B (row-level security).
- `lockedPeriodRefusesPostings` ✔
- `branchesReconcile` ✔: the branch dimension is kept and branch nets add up to the organization.
- Posted-entry immutability ✔: real UPDATE and DELETE attempts are refused by the DB triggers.

**O1 (observation, low):** `GET /journal-entries/{foreignId}` answers **200 with an empty body**, not 404. This is Nest parity: `findOne` returns null and `JournalEntryService.findOne:57` has no organization predicate, relying on RLS. No leak. Owner decision **D-O1**: keep parity, or add an explicit org filter plus 404 as defence in depth (recommended).

---

## B. Capability gaps (missing features, not wrong logic)

| Gap | Target |
|---|---|
| Fiscal positions are labels only: no tax/account mapping, no auto-detection | ACC-7 |
| Tax groups; more than one tax per line; compound-inclusive math; withholding tax on bills; VAT settlement entry; tax-period lock | ACC-7 |
| No document for a supplier credit or claim that reduces AP | ACC-4 |
| Allocating an existing unallocated payment or credit later (open-item matching), AR + AP; supplier-advance account | ACC-4 |
| Payment-term installments; early-payment discount (fields unused); T&C text on invoice PDF | ACC-4 |
| Expense payment has no payment date | ACC-3 |
| FX revaluation route, preview, reversal and UI; organization rate source/approval audit | ACC-2 |
| Bank reconciliation rules, matching GL lines, partial/grouped matches, charges/interest entries, opening/closing proof, rec lock, OFX | ACC-6 |
| Fixed assets: CIP capitalisation, impairment, revaluation per policy, per-category accounts, units of production, posting preview | ACC-5 |
| Trial balance with pre-/post-closing view; report filters, export, drill-down; FS layout editor; GRNI and AR analysis screens | ACC-1 / ACC-8 |
| Recurring journals, deferred revenue, accruals engine, cash forecast, Accounting Integrity Dashboard | later |
| Country CoA packs; 6 unmapped template keys (`card_clearing`, `mobile_money`, `employer_contribution_expense`, `local_service_tax_payable`, `gratuity_expense`, `gratuity_provision`); revaluation surplus mapped to RE | ACC-5 / later |

---

## C. Decisions for the owner

### C1: FX revaluation model (open-item vs valuation account)

**Recommendation: valuation-adjustment with automatic reversal on day 1 of the next period.**

- Realised FX at settlement (ADR-S054) relieves each document at its **original carrying rate** (`DocumentFxRates.documentRate`).
- An open-item model would have to rewrite those carrying rates on every revaluation. That creates a second source of truth and changes how realised FX is computed.
- A valuation adjustment keeps original invoice values intact, which makes it auditable. With day-1 reversal it never double-counts against realised FX.
- The reconciliation engine treats the `fx_revaluation` adjustment as a known reconciling item until the reversal.

The adjustment can be posted to a separate "FX valuation adjustment" account per control account, or to the control account with a dedicated source type. I recommend the separate account: the AR/AP control accounts then reconcile to open items with no exceptions.

### C2: Other decisions

| ID | Decision |
|---|---|
| D-B5 | Make `signed` the API default (behaviour change vs Nest), or fix the unsigned mode? |
| D-B6 | Reversal-date policy per organization: open period → original date (recommended default); closed → first open date; locked → first open date + authorization. |
| D-B7b | Second cancel of an already-cancelled invoice: keep the idempotent 200 or return 400? |
| D-B11 | Cut-over date for posting catch-up FA entries for existing assets. |
| D-O1 | Explicit organization predicate + 404 on `findOne` routes (defence in depth). |
| D-HIST | For each "Hist. = yes" finding: approve read-only detection queries per customer database before any correction entries are proposed. |

---

## D. Accounting-policy configuration: what exists today

| Policy | Where | Org-scoped | Effective-dated | Audited | Gap |
|---|---|---|---|---|---|
| Functional currency | `Organization.currencyCode` | yes | no | no | no change control |
| Fiscal calendar / periods | `FiscalYear`, `FiscalPeriod` | yes | yes (periods) | yes (close/lock audit) | — |
| Books lock / require period | org settings (`booksLockDate`, `requireFiscalPeriod`) | yes | no | partial | audit of changes |
| Inventory valuation | `InventoryCostPolicyService` (ADR-S007) | yes | **yes** | yes | — |
| IFRS elections, capitalisation, maker-checker | `AccountingPolicyService` (ADR-S032) | yes | partial | yes | covers costing only |
| Account determination | `AccountMapping`, inventory posting rules | yes | **no** | partial | mapping changes not versioned (posted entries unaffected) |
| Revenue recognition | — | — | — | — | missing |
| Depreciation policy | per asset/category method | yes | no | no | per-category accounts, framework policy |
| Tax regime / fiscal positions | `Tax`, `FiscalPosition` (labels) | yes | no | no | mapping engine |
| FX policy (rate source, revaluation, reversal) | — | **no** (rates global, B4) | — | — | missing |
| Period-close controls | close checklist (does not gate close) | yes | — | yes | make blocking items gate close |

ACC-0 documents this only. No policy engine is introduced.

---

## Build

`cd backend && ./gradlew build` ran in the ACC-0 worktree on 2026-11-03:

- **1,759 tests, 1,756 passed, 1 failed, 2 skipped.** Known-defect tests are excluded, as designed.
- `spotlessCheck` is clean.
- The single failure is environmental and not caused by ACC-0:
  - `BackupJobsIT.configBackup` fails with "no .env file found".
  - The test reads the developer's git-ignored `.env`. It exists in the main checkout but not in a fresh worktree.
  - ACC-0 touches no backup code. Nothing was copied, because `.env` holds secrets.
  - It passes wherever a `.env` exists (main checkout, CI env).
- The known-defect tests were run separately through `./gradlew acc0VerificationTest`: 23 run, 23 failed (expected).
  Results are under §Run.
