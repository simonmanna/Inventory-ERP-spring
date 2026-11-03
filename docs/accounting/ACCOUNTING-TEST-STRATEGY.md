# Accounting test strategy (ACC-0)

Owner: accounting. Status: introduced in ACC-0 (2026-11-03). Applies to every ACC phase (ACC-1 … ACC-8).

The aim is that an external accountant can trust the numbers. A green build alone is not enough: every
accounting test checks **two levels**.

1. **Double-entry integrity.** Debits equal credits and every sub-ledger reconciles to its GL control account.
   This is checked by `LedgerInvariants`.
2. **Accounting treatment.** The test asserts the exact accounts, amounts, currency, rate, posting date,
   branch/dimensions and source document.

An entry can balance and still be wrong, so both levels are required.

---

## 1. Layout

```
backend/src/test/java/com/inventoryerp/accounting/
├── support/
│   ├── AccountingFixtures.java   base class (extends ApiTestSupport): isolated books, JE posting, periods, reports, FX
│   └── LedgerInvariants.java     reusable controls (below)
└── verification/                 ACC-0 reproduction suites, one per area
    ├── Acc0FinancialReportingIT  B1, B2, B10
    ├── Acc0FxIT                  B3, B4, B14
    ├── Acc0BankReconciliationIT  B5
    ├── Acc0ReversalIT            B6, B7
    ├── Acc0ArApIT                B8, B9
    ├── Acc0FixedAssetIT          B11
    ├── Acc0MoneyPrecisionIT      B12 (vendor-bill PO links)
    ├── Acc0PostingDateIT         B13
    ├── Acc0IdempotencyIT         P1-a … P1-e
    ├── Acc0TenantIsolationIT     tenancy, locked periods, branch dimension
    └── Acc0LifecycleIT           full P2P → O2C → bank rec → month-end scenario
backend/src/test/java/com/inventoryerp/hr/application/Acc0PayrollPrecisionTest   B12 (payroll double path)
```

## 2. Fixtures and isolation

Every suite reuses the single cached `ApiTestSupport` Spring context and adds no new `@MockitoBean` or profile.
Isolation happens per organization, not per context.

| Need | Fixture | Isolation |
|---|---|---|
| GL, reports, close, FX, idempotency, tenancy, lifecycle | `freshBooks()` bootstraps a brand-new org through `POST /organizations/bootstrap`. It gets its own CoA, journals, mappings, open period and owner token. | Full. Nothing else posts there. |
| Monthly periods | `monthlyPeriods(b, months…)` replaces the bootstrap year period with open monthly periods. | Per org |
| Document flows that need DEMO setup (approvals, expense categories, assets, bank rec) | `demoPartner`, `demoProduct` and unique test currencies. Every assertion is scoped to the test's own partners, products and source documents through `netForSources` and `partnerControlReconciles`. | Scoped. DEMO's history cannot mask or fake a result. |
| Currencies | `testCurrency()` creates a unique `Q??` code. The Currency table is global (finding B4), so real codes are never touched. | Per test |

Helpers:

- `postJe(books, journal, date, currency, rate, sourceType, postingKey, lines)` posts through `PostingService`, the
  single GL writer. It handles multi-currency, branch and dimensions on each line, and the posting date.
- `closePeriod(books, date)` closes the period through the API, asserts it closed, and returns the closing entry.
- `tbAsOf(org, date, PRE_CLOSING | POST_CLOSING)` is a ledger trial balance by account code.
  - PRE_CLOSING excludes `period_close` / `fiscal_year_close` entries; the product has no such view until ACC-1.
  - POST_CLOSING includes them.
- `pnl`, `trialBalance`, `balanceSheet` call the report endpoints exactly as the web screens do.

## 3. Invariants (`LedgerInvariants`)

| Invariant | Method | Rule |
|---|---|---|
| Journal balance | `entriesBalanced` | Σ baseDebit = Σ baseCredit per posted/reversed entry. This is mandatory and authoritative. |
| Transaction-currency balance | `singleCurrencyEntriesBalanced` | Applies only to entries whose lines all carry **one** currency. Mixed-currency entries are never summed across currencies. |
| FX line validity | `fxLinesValid` | Every foreign line has a `JournalEntryFxRate` snapshot, and base equals amount × line rate within 0.01. |
| Trial balance | `trialBalanceBalanced` | Σ dr = Σ cr, for both the report and the ledger map. |
| Balance sheet | `balanceSheetBalanced` | Assets = liabilities + equity + current-year earnings. |
| AR / AP control | `partnerControlReconciles` | Per partner: control GL = open document residuals − unapplied cash − open credits. Deposits and advances sit on their own accounts, outside the control. |
| Inventory | `inventoryTiesOut` | `/reports/inventory/gl-tieout` valuation vs GL is within tolerance. |
| Fixed assets | `fixedAssetsReconcile` | Register NBV = GL (cost − accumulated depreciation). |
| Tax | `taxReconciles` | Document tax = tax GL movement for the scope. |
| Posting completeness | `postingComplete` | For each source document: the expected number of primary entries; no entry without lines; every reversed entry has a linked posted compensation; no primary entry left posted on a reversed document. |
| Immutability | `postedEntryImmutable` + `snapshot` | Attempts a real UPDATE/DELETE on a posted entry and its lines and expects the DB triggers to refuse. The before/after snapshot compare complements this. |

## 4. Known-defect lifecycle

1. A reproduction test asserts the **correct** outcome, with
   `@DisplayName("[B<n>][owner:accounting][ACC-<phase>] …")`.
2. If it fails, it gets `@Tag("acc-known-defect")` and an entry in `ACC-0-verification.md` recording the evidence,
   owner, target phase and disposition.
3. Tagged tests are excluded from `test`, `fastTest` and `accountingRegression`, so the build stays green. They run
   in their own task:
   ```bash
   cd backend && ./gradlew acc0VerificationTest
   ```
   - `ignoreFailures = true`: test failures are reported, not fatal.
   - Zero discovered tests **fails** the task, so a silently empty run cannot pass.
   - Fresh DB container: the shared reusable one carries other branches' schema.
   - JUnit XML and HTML are always written (`build/test-results/acc0VerificationTest`,
     `build/reports/tests/acc0VerificationTest`).
4. In CI (`.github/workflows/ci.yml`, backend job):
   - The step runs the task. Compile, Docker and zero-test errors fail the step; test failures do not.
   - A summary step (`if: always()`) prints tests/failures/errors/skipped per class into the job summary and fails if
     no JUnit XML exists, which signals an infrastructure failure.
   - The reports are uploaded as artifact `acc0-verification-reports` with `if: always()`.
5. The ACC phase that fixes a defect removes the tag in the same PR and adds the class to `accountingRegression`.
6. **Never** weaken an assertion to make a test pass. If the correct behaviour itself changes, the owner approves the
   doc change first.
7. A test that passes on first run means "Not reproduced": it stays untagged as a regression guard.

### Execution-semantics checks (verified in ACC-0)

- **All tagged tests discovered:** the count of `@Tag("acc-known-defect")` occurrences in the source equals the
  task's "N run" line. Recorded in `ACC-0-verification.md` §Run.
- **XML holds failures:** the JUnit XML carries `<failure>` elements for the failing tests.
- **Regular counts unchanged:** `test`, `fastTest` and `accountingRegression` differ from before the wiring only by the
  tagged tests. The existing suites carry no tag, so only the new ACC-0 classes are affected.

## 5. Scenario map (owner's release scenarios → test → phase)

| # | Scenario | Expected | Test | Phase |
|---|---|---|---|---|
| 1 | Purchase inventory with VAT | Stock, GRNI, VAT and AP agree | `Acc0LifecycleIT` | ACC-1 gate |
| 2 | Receive goods, then supplier invoice | GRNI clears | `Acc0LifecycleIT` | ACC-1 gate |
| 3 | Partial customer payment | AR residual correct | `Acc0LifecycleIT`, `Acc0ArApIT.openItemAllocation` | ACC-4 |
| 4 | FX invoice and payment | Realised FX correct | existing `SharedFxIT` (PC-0) | regression |
| 5 | Month-end FX revaluation | Second run posts nothing | `Acc0FxIT.receivableRevaluation` | ACC-2 |
| 6 | Close month | P&L historically correct | `Acc0FinancialReportingIT.pnlOfClosedMonth` | ACC-1 |
| 7 | Close year | RE and BS agree | `Acc0FinancialReportingIT.fiscalYearCloseInBaseCurrency`, existing `FiscalYearIT` | ACC-1 |
| 8 | Cancel invoice with stock | AR, revenue, COGS, stock corrected | `Acc0ReversalIT.cancelRestoresStock` | ACC-3 |
| 9 | Customer credit note | Revenue, VAT, AR correct | `Acc0ArApIT`, existing `InvoicingIT.creditNotes` | ACC-4 |
| 10 | Dispose fixed asset | Cost, acc. depreciation, gain/loss agree | `Acc0FixedAssetIT.disposalPostsGainLoss` | ACC-5 |
| 11 | Bank reconciliation | Differences explained | `Acc0BankReconciliationIT`, `Acc0LifecycleIT` | ACC-6 |
| 12 | Multi-branch | Branch and consolidated reconcile | `Acc0TenantIsolationIT.branchesReconcile` | ACC-8 (report level) |
| 13 | Concurrent posting | No duplicates, no imbalance | `Acc0IdempotencyIT` | ACC-3 |
| 14 | Backup restore | Integrity preserved | existing backup round-trip test in CI (`PG_BIN`) + `LedgerInvariants` after restore | ACC-8 |

The full integration scenario is `Acc0LifecycleIT`:
Purchase → GRN → Bill → Supplier pay → Valuation → Sale → Invoice → Customer pay → Bank rec → Month-end.
It deliberately leaves residuals: 2 units unreceived, partial supplier and customer payments, unsold stock, unsettled VAT.
It asserts the exact expected balance of each control account.

## 6. Concurrency, tenancy and recovery

| Concern | Test |
|---|---|
| Same document posted twice at once | `Acc0IdempotencyIT.concurrentSameKey` |
| Different documents, same account, concurrently | `Acc0IdempotencyIT.concurrentDifferentDocuments` |
| Failure before commit, then retry | `Acc0IdempotencyIT.failureThenRetry` |
| Timeout after commit, retry with same key | `Acc0IdempotencyIT.openingBalanceRetrySameKey`; payments covered by `InvoicingIT.receipts` |
| Two organizations writing exchange rates | `Acc0FxIT.ratesAreTenantScoped` |
| Cross-tenant journal read | `Acc0TenantIsolationIT.crossTenantReadsRefused` |
| Post into a locked period | `Acc0TenantIsolationIT.lockedPeriodRefusesPostings` |

## 7. Commands (always from `backend/`)

```bash
cd backend && ./gradlew compileJava compileTestJava -q
cd backend && ./gradlew acc0VerificationTest
cd backend && ./gradlew test --tests '*Acc0*' --tests '*AccountingIT' --tests '*FiscalYearIT' --tests '*FinancialStatementsIT'
cd backend && ./gradlew build
```

`fastTest` is fine for a single class on a quiet machine. When other sessions share the reusable container, use
`test` instead: it runs on a fresh container.
