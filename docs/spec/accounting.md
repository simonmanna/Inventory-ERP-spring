# Accounting, treasury and cash — `accounting`

| | |
|---|---|
| Phase | P2 |
| Nest source | `reference/nest-api/src/modules/accounting` |
| Routes | 110 — full table: [generated/accounting.md](generated/accounting.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.accounting` |

## Purpose

Chart of accounts and categories, account mappings (posting keys → accounts), journals and journal entries (post/reverse), cost centers, currencies and FX rates/revaluation, fiscal period close/lock/reopen, bank accounts, treasury transfers, bank reconciliation, financial reports and snapshots, money operations (`accounts/cash-flow`), POS payment methods, cash registers, cash sessions (shifts), tender settlements.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| chart of accounts | `/accounts` CRUD, tree, reorder, deprecate; `/account-categories` read + usage; `/account-mappings` registry/missing/PUT |
| journals | `/journals` CRUD; `/journal-entries` CRUD + post + reverse; posting engine in `accounting/posting` |
| periods | `/fiscal-periods/:id/close|lock|reopen` (period close checks) |
| fx | `/currencies`, rates, revaluation (`FxRevaluation`) |
| bank | `/bank-accounts`, `/treasury/transfer` (idempotent), `/bank-reconciliation` import/match/unmatch/status |
| reports | `/reports/accounting/*` (trial balance, GL, account ledger, P&L, balance sheet (+detailed), cash flow, tieout, pos-gl-reconciliation, rebuild snapshots), `/reports/export/*.csv|pdf`, `/reports/inventory/valuation|gl-tieout`, `/reports/dashboard-kpi|dashboard-activity` |
| money operations | `/accounts/cash-flow`: overview, activity, operation types, transactions, deposit, withdraw (idempotent) — `money-activity*`, `money-overview`, `money-settlement` |
| payment methods | `/accounts/pos-payment-methods` CRUD (tender → account) |
| cash registers | `/cash-registers` CRUD + drawer balance |
| cash sessions | `/cash-sessions/*`: open, close, movement, force-close, banking, reconcile, daily report/reconcile, variance, expected, movements, history, tender-settlements |

## Business rules to reproduce exactly

1. Journal entry must balance (Σdebit = Σcredit, `approxEqual` 0.0001) and date must fall in an open period; `postingKey` unique per org makes postings idempotent; reversal creates a mirrored entry linked by `reversalOfId`.
2. Account mappings: every automatic posting resolves accounts through mapping keys (`packages/shared/src/accounting/account-mappings.ts`); missing mapping → error listing the key (verbatim).
3. Period close/lock: closed periods reject postings; lock is irreversible except `reopen` with permission `fiscal_period:reopen`.
4. Cash session open: register must have its own active drawer cash account; opening count may not be below the drawer ledger balance (unless `autoAdjust`); added float requires a funding account (safe/bank) with sufficient recorded funds and a reason.
5. Movements: `pay_in | pay_out | adjustment`, non-zero amount, reason, counterpart account, cannot overdraw the drawer, only the session owner (or manager); pay_out requires manager sign-off (`approvedById`/`managerPin`); corrections to a closed shift = adjustment with `correctionOfSessionId` and permission `cash_session:correct`.
6. Close: expected cash computed per payment method; counted cannot be negative; a variance needs a reason and approval from a holder of `cash_session:approve_variance` (manager PIN); `autoAdjustCash` records the difference as pay-in/pay-out; closing first drains pending stock postings and is blocked by open sync dead letters; variance and bank deposit post to GL.
7. Force-close: requires `cash_session:force_close`, a reason, and must target another cashier's shift. Any holder of `cash_session:close` may close an open shift on a shared terminal (recent change, commit 93c3414).
8. Handover (`/pos/shift/handover`): different incoming cashier; approving manager must be neither cashier and hold `cash_session:approve_variance`.
9. Closing amounts visibility is controlled by a setting (commit 17126b5) — respect it in responses.
10. Status machine: `open → closed → reconciled`.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`journal.posted`, `journal.reversed`, `cash.received`, `cash.paid`, `bank.transfer`, `cash.session.opened/closed`, …

## Scheduled jobs

report snapshot cron (`snapshot-cron.worker.ts`).

## Notes and pitfalls

- Port `accounting/treasury` specs (5) and `accounting/posting` specs (3) first — they pin the cash rules.
- Trial balance / P&L queries are heavy SQL: port the raw SQL as-is via `JdbcClient`.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported (module unit specs); integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/accounting-*.json` — written, not yet run against Nest
- [ ] Web screens for this module work against Spring (no console errors) — not yet checked
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Pending owner approval:

1. **`GET /reports/accounting/pos-gl-reconciliation`**: `inventory.jobCounts` values are numbers and
   `unpostedJobs` is their sum. Nest groups with `_count: { _all: true }`, so in production each value is
   a `{ "_all": n }` object and `unpostedJobs` becomes the string `"[object Object]00"` (the note then
   always says every job has posted). The Jest spec mocks `_count` as a number, which is the behaviour
   kept here.
2. **`GET /reports/export/invoice/:id.pdf`** is rendered with OpenPDF instead of PDFKit: same text,
   order and alignment, different bytes and font metrics. Like Nest, the id still comes from `?id=`
   (the path segment is ignored) and a missing `id` renders the organization's first document.
3. **Unhandled errors** (an invalid date in a report query, `to` before `from` on POS → GL
   reconciliation, a non-numeric `limit` on dashboard activity) answer 500 like Nest, but the
   development-only `error` detail text comes from Java rather than Prisma.

Same as Nest, kept on purpose:

- `PATCH /cash-sessions/:id/reconcile` writes `notes` as sent. The `guard_cash_session` trigger lets a
  closed shift's notes only be appended to, so a body whose `notes` does not start with the stored notes
  fails with 500, exactly as in Nest. Omitting `notes` keeps the stored value.
- Re-closing a period after `reopen` answers "Nothing to close" until new revenue/expense is posted (the
  reversal of the closing entry posts today and nets the period to zero).
- `GET /reports/export/trial-balance.csv` keeps Nest's SQL verbatim: the entry status/date filter sits in
  the `LEFT JOIN`, so lines of draft or later entries still count in the sums.
- `RevaluationService` (FX revaluation) has no route in Nest either; it is module API only.

## Accounting remediation deviations (ACC-1 … ACC-4, owner-approved 2026-11-03)

Evidence and the original defects are in `docs/accounting/ACC-0-verification.md`. The phase plan is in
`docs/accounting/ACC-EXECUTION-PLAN.md`.

**ACC-1: reporting**

- **B1.** Closing entries are left out of P&L and of every ranged movement in the reporting engine. This covers
  `sourceType` `period_close` and `fiscal_year_close`, including their reopen reversals. A closed period still reports
  its result. Point-in-time balances (balance sheet, post-closing TB) keep the closing entries.
- **B1.** `GET /reports/accounting/trial-balance` takes an optional `view=pre_closing|post_closing`. The default is
  unchanged. The `view` field is echoed only when it was asked for.
- **B10.** `GET /reports/accounting/profit-and-loss` always computes from the ledger. The cumulative `ReportPnLSnapshot`
  ignored the date range and carried no other income/expense.
- **B10.** The trial-balance snapshot answers only open-ended post-closing requests.
- **B10.** The balance sheet keeps inactive accounts that still carry a balance.
- **B2.** Fiscal-year close checks, and the `FiscalYearBalance` snapshot, use base amounts (`baseDebit`/`baseCredit`).
  Before this, a multi-currency organization could not close its year.

**ACC-2: foreign currency**

- **B4: rates per organization.**
  - V320 adds `CurrencyRate."organizationId"` (null = shared), the unique key `(org, from, to, asOf)` and RLS.
  - A tenant's `POST /currencies/rates` writes its own rate.
  - Resolution, rate lists and the rate-history report use the organization's own rate first, then the shared one.
  - Intercompany consolidation still averages across all rows; it is group-level by design.
- **B3: revaluation lifecycle.** Owner decision C1, simplified:
  - Each adjustment posts at `asOf` with a linked automatic reversal on the next day.
  - The carrying basis is the original foreign-currency lines only.
  - A re-run for the same date posts only the difference. Amounts are BigDecimal.
  - The adjustment hits the monetary account itself; a separate valuation-adjustment account is a later option.
  - New routes (extension, Nest has none): `GET /fx-revaluations`, `POST /fx-revaluations/preview` (`currency:read`)
    and `POST /fx-revaluations` (`currency:update`), body `{asOf}`.
- **B14.** Expense accrual and payment entries post in the expense's currency and rate. Line amounts are base ÷ rate;
  `PostingService` converts back and writes the FX snapshot.

## Extensions beyond Nest (ADR-S005)

These are additive routes the owner asked for. Nest has no counterpart.
- `/budgets` (CRUD, `PUT /:id/lines`, `POST /:id/approve`, `POST /:id/reopen`, `GET /:id/vs-actual`), with
  permissions `budget:*`. Tables are `Budget` and `BudgetLine` (V8). Lines can be edited only while the budget is
  draft, and approving it locks them. Actuals are posted GL lines in each account's natural sign, filtered by the
  budget's branch or cost centre.
- `GET /reports/accounting/consolidated?from&to&statement=pl|tb` (`report:accounting`) shows each branch side by
  side, plus the consolidated total. Lines are attributed by their line `branchId`; lines without one fall into
  `unassigned`.

## Status

P2: 110/110 routes. Tests: `AccountingIT` (seed, accounts, journal entries + maker-checker, period
close/reopen/lock, journals/cost centers/currencies/mappings, authorization matrix, tenant isolation),
`TreasuryIT` (idempotent money operations, registers, full cash-session simulation against the GL),
`ReportingIT` (statements, detailed report, snapshots + tie-out, POS → GL reconciliation, inventory
valuation/tie-out, CSV/PDF exports, dashboard, FX revaluation, journal_entry workflow), `TreasurySpecsIT`
(ports of `pos-payment-method`, `cash-session` guards and the remaining `period-close`/`posting` cases),
`AccountingUnitTest` + `AccountingSpecsTest` (ports of `posting.math`, `money-activity.taxonomy`,
`org-dates`, `coa-template`, `accounting-invariant`, `pos-gl-reconciliation`, `session-reconciliation`,
`currency` specs). The listed `test/business-simulation/*` and `test/integration/*` specs drive POS,
inventory and stock-posting flows and are ported with P3–P5. Parity scenarios are written
(`accounting-ledger`, `accounting-treasury`, `accounting-reports`) but have not been run against Nest.

## ADR-S007 extensions — cost-centre accounting (roadmap 1.8)

- `CostCenter`: `parentId`, `branchId`, `category`, `description`, `managerUserId`, `validFrom`, `validTo`;
  tenancy + soft-delete filters added (**deviation / fix**: the Nest-port list was not tenant-filtered).
- Posting engine applies, per entry: split rules (`dimensions.costAllocationRuleId`), defaults (partner → branch →
  user) on revenue / expense lines, account rules (`required` / `forbidden`), validity on the posting date.
- Manual journal lines accept `branchId`, `costCenterId`, `dimensions` (additive DTO fields).
- `ensureByCode` sets `isActive` / `isPostable` explicitly on the created account (fix).

| Method | Path | Permission |
|---|---|---|
| GET | `/cost-centers/tree` | `cost_center:read` |
| GET/POST, DELETE | `/cost-centers/defaults`, `/defaults/{id}` | `cost_center:read` / `update` |
| GET, PUT | `/cost-centers/account-rules`, `/account-rules/{accountId}` | `cost_center:read` / `update` |
| GET | `/cost-centers/allocation-rules`, `/{id}`, `/{id}/preview?periodFrom&periodTo` | `cost_center:read` |
| POST/PATCH/DELETE | `/cost-centers/allocation-rules`, `/{id}` | `cost_center:allocate` |
| POST | `/cost-centers/allocation-rules/{id}/run` (Idempotency-Key) | `cost_center:allocate` |
| GET, POST | `/cost-centers/allocation-runs`, `/allocation-runs/{id}/reverse` | `cost_center:read` / `allocate` |
| GET | `/cost-centers/reports/profit-and-loss?from&to[&costCenterId]`, `/reports/matrix?from&to` | `cost_center:read` |

Tests: `CostCenterAccountingIT`. Web: Cost Centers (hierarchy fields), Cost Centre Accounting (P&L, matrix,
hierarchy, allocation rules / runs, defaults & account rules).

## Phase 4 bank reconciliation (built, unverified)

FIN-1: `GET /bank-reconciliation/{lines,runs,summary}`, `GET /bank-reconciliation/lines/{id}/candidates` (`bank_account:read`), `POST /bank-reconciliation/lines/{id}/{match,exclude}` (`bank_account:update`). `POST /bank-reconciliation/match` takes an optional `signed` flag; without it the behaviour is unchanged.
`bankAccountId` on these routes is the GL account id. Screen `/bank-reconciliation`. Test: `BankReconciliationIT`. Nothing was compiled or run.

## Phase 4 period-close checklist (built, unverified)

FIN-3: `GET /fiscal-periods/{id}/close-checklist` (`fiscal_period:read`), `PUT /fiscal-periods/{id}/close-checklist/{key}` (`fiscal_period:update`), table `PeriodCloseSignOff` (V72). The checklist does not gate `close`.
Screen `/fiscal-periods/:id/checklist`. Tests: `PeriodCloseChecklistIT`, `FinancialStatementsIT`, `core/TaxesIT`. Nothing was compiled or run.
## INT-3 bank statement files (Phase 4, built, unverified: nothing compiled or run)

`POST /bank-reconciliation/import-file` (multipart `file`; `bankAccountId, currencyCode, format` = auto, csv, mt940, camt053;
`dryRun`; `bank_account:update`) parses with `BankStatementParser` and imports through the unchanged
`BankReconciliationService.importStatement`. CSV: header row found in the first 30 rows, comma, semicolon or tab, one amount
column or debit + credit columns, day-first dates, thousands in either style. MT940: `:61:` + `:86:`, reversals flipped.
camt.053: `Ntry` elements, DTDs refused. A line without a bank reference gets a stable hash reference (date, amount, text,
occurrence in the file), so a repeat import skips it and two identical charges stay. Screen: `/accounts/bank-import`.
## Branch performance (BR-2)

Status: built, not compiled or run. `GET /reports/branches/comparison` (`report:accounting`, `BranchPerformanceService`):
ledger P&L, sales, open receivables, stock value, deliveries and production per branch plus a total. Screen:
`/reports/branches`. `ReportFilterBar` (`web/src/components/report-filter-bar.tsx`) is the shared filter row. Tests:
`BranchPerformanceIT`, `AccountingExtensionsIT.budgetByBranch`.

