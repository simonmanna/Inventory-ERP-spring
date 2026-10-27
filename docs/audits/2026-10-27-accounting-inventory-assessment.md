# Accounting & Inventory — Deep Assessment

> **Date:** 2026-10-27 · **Branch:** `phase/P7-beverage` · **Scope:** Spring backend (`backend/`), web (`web/`),
> Nest reference (`reference/nest-api/`) · **Benchmark:** what a professional mid-market ERP (Odoo 17 Enterprise,
> SAP Business One, NetSuite, Dynamics 365 Business Central) is expected to provide.
>
> Method: read the module specs, port status and handover; walked the Java services, entities, DB guards
> (V1 triggers), tests and web pages; grepped the 296-model Prisma schema and the Java tree for every feature a
> professional accounting / inventory package normally has. Statements below are backed by file references in the
> appendix. "Missing" means: no table, no service, no route — not just "no UI".

---

## 1. Executive summary

| Question | Answer |
|---|---|
| Does the system have a full accounting system? | **A solid core, not yet "full".** Double-entry GL, CoA, journals, periods/close, multi-currency, AR, AP, bank/treasury, cash management and the standard statements are all implemented and ported (110/110 accounting routes). Missing for "professional": tax (VAT/PPN) returns, budgets, fixed assets (0/54 routes ported), realized FX gain/loss, intercompany/consolidation, dunning, comparative statements, bank-feed import formats, and several correctness bugs kept on purpose for Nest parity. |
| Does it have a full inventory system? | **A strong perpetual-inventory core for F&B / retail, not a full WMS.** Multi-location stock, append-only ledger, AVCO/FIFO/STANDARD/SPECIFIC costing, lots/serials/expiry, transfers with in-transit, counts, reservations/ATP, GL posting rules, landed costs, 3-way match (69/69 inventory routes). Missing: bins/sub-locations, put-away, pick/pack/ship (delivery orders), automatic replenishment, ABC / ageing / turnover analytics, quality inspection on receipt; manufacturing is 0/42 routes ported. |
| Is it flexible? | **Yes — the strongest aspect.** Account determination resolves Product → Category → Movement rule → Account mapping, per-org posting rules are editable via API/UI, costing method is per product, negative-stock policy is configurable, approval workflows are generic, UoM conversions are per product. |
| Is it production ready? | **Engine-level yes; product-level not yet certified.** Strong integrity controls (DB immutability triggers, idempotent posting keys, row/advisory locks, `BigDecimal` everywhere, tenancy filters, period gating). But: parity harness has never been run against Nest; per-screen web walkthrough not done for finance screens; JaCoCo 74 % vs 80 % target; known accounting bugs kept for parity; owner deviations not approved; POS p95 latency target missed. |

**Ratings (0–10, against a professional mid-market ERP):**

| Area | Rating | Verdict |
|---|---|---|
| General-ledger engine (posting, controls, audit) | **8.5** | Production-grade |
| Chart of accounts & account determination | **8.5** | Production-grade, very flexible |
| Fiscal periods & year-end | **7** | Good; no soft-close per module, no audit adjustments period |
| Multi-currency | **5** | Rates + unrealized revaluation (no route); no realized FX on payments |
| Accounts receivable | **6** | Invoices, credit notes, payments, ageing; no dunning, statements, write-offs, collections |
| Accounts payable / procure-to-pay | **7** | PR→PO→GRN→bill, 3-way match, GRNI, landed cost, WHT; 1 posting bug |
| Bank & treasury | **5.5** | Transfers, deposits; bank rec is basic (JSON import, exact match to `Payment` only) |
| Cash management (POS drawers/shifts) | **9** | Best-in-class for hospitality |
| Tax | **4** | Inclusive/compound/withholding calculation; no tax return/report, fiscal positions are empty shells |
| Budgeting / planning | **0** | Missing |
| Fixed assets | **1** | Schema + web pages exist; backend 0/54 routes |
| Financial reporting | **6.5** | TB, GL, P&L, BS, CF (direct), snapshots, tie-outs; no comparatives, no dimension filters on statements, no report designer |
| Consolidation / intercompany | **0** | Missing |
| **Accounting overall** | **≈ 6.5** | Strong core, missing several "professional" layers |
| Inventory core (ledger, costing, documents) | **8.5** | Production-grade |
| Lots / serials / expiry | **8** | FEFO/FIFO/serial picking strategies |
| Warehouse management (bins, pick/pack, put-away) | **2** | Flat locations only |
| Replenishment & planning | **3** | Reorder report only; MRP not ported |
| Inventory analytics | **6** | Valuation, movements, reconciliation, stock health; no ABC/ageing/turnover |
| Manufacturing | **1** | Schema only, 0/42 routes |
| **Inventory overall** | **≈ 6.5–7** | Strong for F&B / retail, short of a full WMS/MRP |

---

## 2. Port status snapshot (finance & inventory relevant)

| Module | Routes ported | Tests (methods) | Notes |
|---|---|---|---|
| `accounting` | **110 / 110** | 58 (6 files, incl. `AccountingIT`, `TreasuryIT`, `ReportingIT`, `TreasurySpecsIT`) | Parity scenarios written, never run vs Nest |
| `inventory` | **69 / 69** | 39 (`InventoryIT`, `InventoryUnitTest`) | 1 deviation pending (`lowStock`) |
| `invoicing` (AR + vendor bills) | **30 / 30** | 17 | UoM deviation pending |
| `expenses` | **18 / 18** | inside `InvoicingIT` | No dedicated test class |
| `procurement` | **36 / 36** | 29 (`ProcurementIT` 1 100 LOC) | Two Nest bugs kept |
| `core` (products, taxes, UoM, payment terms, fiscal positions) | 81 / 81 | `CoreIT` | |
| `orders` (non-POS sales orders) | 11 / 11 | `OrdersIT` | Order → invoice only |
| `fixedasset` | **0 / 54** | — | Only generated entities; web pages call routes that do not exist |
| `manufacturing` | **0 / 42** | — | Only generated entities; production GL postings unreachable |
| Whole system | 889 / 1 118 | last run: 514 tests, 0 failures, 1 skipped | |

---

## 3. Accounting — deep dive

### 3.1 General-ledger posting engine — ✅ done, production-grade

`accounting/application/PostingService.java` (434 LOC) + DB triggers in `V1__baseline.sql`.

| Control | Status | Evidence |
|---|---|---|
| Double entry, ≥ 2 lines, balanced (≤ 0.01 residual auto-routed to `ROUNDING` account, larger rejected) | ✅ | `applyRounding()` |
| Idempotent automatic postings (`postingKey` unique per org; replay returns existing entry) | ✅ | `post()` lines 73–80 |
| Period gating (no period → reject; closed/locked → reject; org-wide "books locked through" date) | ✅ | `FiscalPeriodControl.assertOpen()` |
| Maker-checker for manual entries (creator cannot post own draft) | ✅ | `postDraft()` |
| Reversal = mirrored entry linked to original; original → `reversed` | ✅ | `reverse()` |
| Immutability enforced **in the database**: posted entries/lines cannot be updated or deleted; only `posted → reversed` | ✅ | `guard_journal_entry`, `guard_journal_line` triggers |
| Entry numbering per journal + year | ⚠️ | Postgres sequences: **unique but not gap-free** (a rolled-back transaction leaves a gap — `SequenceService` doc). The legacy assessment's "gap-free" claim is wrong. Matters where invoice/tax numbering must be contiguous (e.g. e-Faktur, EU). |
| Multi-currency lines (`currencyId`, `exchangeRate`, `baseDebit/baseCredit`) | ✅ | `persistLines()` |
| Group / inactive account guards | ✅ | `assertAccounts()` |
| Domain events `journal.posted` / `journal.reversed` after commit | ✅ | |
| DB-level balance constraint (Σdr = Σcr enforced by Postgres) | ❌ | Only enforced in Java. A deferred constraint trigger would make it tamper-proof. |

### 3.2 Chart of accounts & account determination — ✅ done, very flexible

- Hierarchical CoA (`/accounts/tree`, reorder, deprecate), account categories with usage, classification
  (`revenue`, `cogs`, `operating_expense`, `other_income`, contra accounts …), normal balance, cash-flow class,
  control-account type, protected system accounts. Template seeding (`CoaSeeder`).
- **Account mappings** (`/account-mappings` registry / missing / PUT): every automatic posting resolves through
  a named key; a missing mapping fails with the key in the message, and `/missing` lists gaps — good
  onboarding control.
- **Inventory posting rules** (`InventoryPostingRuleService`): 4-level resolution
  *Product override → Category field → Movement-type rule → Account mapping*, multi-leg rules, `resolve-preview`,
  `restore-defaults`. More flexible than Odoo's category-only determination.
- Partner-level AR/AP overrides, tax-level accounts.

Gaps: no account-level "allowed dimensions / mandatory cost centre" rules; no account-group reporting
structures separate from the CoA tree (e.g. IFRS vs local statutory layouts).

### 3.3 Journals & manual entries — ✅ done

Journals CRUD (types), journal entries CRUD / post / reverse, draft workflow, approval workflow hook
(`AccountingWorkflowsInitializer`). Web: list, create, detail, edit pages.

Gaps: **no recurring journal entries** (the kernel recurring engine only supports `sales_invoice`,
`credit_note`, `vendor_bill`, `debit_note`); **no automatic reversing entries** (accrual on day 1 of next
period); no journal import (CSV/Excel); no allocation entries (distribute cost by % across cost centres).

### 3.4 Fiscal periods & year-end — ✅ done, ⚠️ partial vs professional

`PeriodCloseService` closes a period in one transaction with **strong pre-close checks**: all register shifts
reconciled, no unposted COGS / stock-posting jobs, no pending stock documents, no open posting exceptions. Closing
entry moves revenue/expense to the `retained_earnings` mapping; reopen reverses it; lock is irreversible.
Web has `fiscal-periods` and `year-end-close` pages.

Gaps vs professional:
- Closes P&L to retained earnings **every period** (not only at year end) — fine for management, unusual for
  statutory books (most ERPs close only at fiscal year end and keep monthly P&L visible).
- No "period 13" / audit-adjustment period, no per-module soft close (e.g. AP closed, GL open), no close checklist /
  task tracking beyond the hard checks.
- Re-close after reopen answers "Nothing to close" until new P&L is posted (documented Nest behaviour).

### 3.5 Multi-currency — ⚠️ partial

| Feature | Status |
|---|---|
| Currencies, dated rates, rate lookup | ✅ |
| Foreign-currency invoices / entries with exchange rate and base amounts | ✅ |
| Unrealized FX revaluation of monetary accounts (`RevaluationService`, posts to `fx_gain` / `fx_loss`) | ⚠️ exists but **no route, no scheduler, no UI**, no auto-reversal next period |
| **Realized FX gain/loss when a foreign invoice is paid at a different rate** | ❌ `PaymentService` has no currency logic |
| Automatic rate feeds (central bank API) | ❌ |
| Multi-currency bank accounts with revaluation | ❌ |

### 3.6 Accounts receivable — ⚠️ core done

Done: sales invoices (manual + POS), journal preview, post, cancel; credit notes with disposition (refund /
restock); customer receipts, refunds, void; payment allocation (partial payments); AR ageing, customer balances,
outstanding, revenue by customer / period; AR ageing CSV; invoice PDF; payment terms with due-date derivation;
recurring invoices.

Missing / weak:
- **Dunning / payment reminders** (schema comment only, no code).
- **Customer statements** (statement of account PDF/email).
- **Bad-debt write-off / allowance for doubtful accounts** workflow.
- Credit limits & credit hold on sales orders.
- Early-payment discount handling on receipt.
- Customer advances / unapplied cash as a managed flow (prepayments).
- Known kept bug: `GET /invoices` reports manual invoices as always `unpaid` / `unsettled`.

### 3.7 Accounts payable & procure-to-pay — ✅ strong, one posting bug

Done: purchase requests (submit/approve/reject) → purchase orders (activate, receive, pay, cancel) → goods
receipts (post / reverse / ad-hoc) → vendor bills with **3-way match** (override flag), **GRNI** accrual and
report, **landed costs** (charges, allocation to receipt lines, post / cancel), **debit notes / return-to-vendor**,
supplier payments with **withholding tax** deduction, AP ageing, supplier ledger, line UoM snapshots.

Known bugs kept for Nest parity (should be fixed post-parity):
1. **Inbound debit note that is not a return-to-vendor always fails "Unbalanced entry"** (credits AP *and* expense).
   A vendor price-correction credit note therefore cannot be posted — a real AP gap.
2. Supplier ledger drops fully-paid bills (status `paid`) but keeps their payments → closing balance goes negative.
3. `GET /procurement/purchase-orders?search=` returns 500.
4. Reversing a GRN cancels its draft landed costs even when the reversal is then refused.

Missing: payment runs / batch payments, payment file export (bank formats), vendor prepayments as a flow, 2-way
match option, PO tolerance rules per vendor/category, blanket/contract POs, vendor price lists / RFQs.

### 3.8 Bank, treasury & cash — treasury ✅, bank reconciliation ⚠️ basic

Done: bank accounts, idempotent treasury transfers, deposits / withdrawals (money operations with a full
taxonomy), money overview / activity / settlement, POS tender settlements.

Bank reconciliation (`BankReconciliationService`) is **minimal**:
- Import accepts JSON lines only — **no CSV / OFX / MT940 / CAMT.053 parser**, no bank-feed API.
- Auto-match = **exact amount + date window against the `Payment` table only**. Expense payments, supplier
  payments with WHT (net ≠ gross), treasury transfers, bank fees and manual journal lines are never candidates;
  no many-to-one / one-to-many matching; no matching rules; no "create entry from statement line" (bank charges,
  interest).
- No reconciliation report (book balance vs statement balance with outstanding items) and no statement closing
  balance check.

Cash management (`CashSessionService` 1 459 LOC, `SessionReconciliation` 691 LOC): open/close/force-close,
handover, pay-in/pay-out with manager approval, variance approval, banking, daily reconcile, expected cash per
tender, DB-frozen closed shifts. **Best-in-class for hospitality.** Cheque register and petty-cash custodian
workflows are not modelled beyond expense payment sources.

### 3.9 Tax — ⚠️ calculation only

Done: `Tax` (rate, inclusive, compound, VAT category, account, withholding type), `TaxCalculationService`
(exclusive / inclusive / compound, withholding partitioned out, currency-scale rounding), tax payable / receivable
mappings, WHT on supplier payments.

Missing for professional tax compliance:
- **Tax / VAT (PPN) return report** — no route reports output vs input tax by period / tax code.
- **Fiscal positions are empty shells** (`FiscalPosition` has only code/name/sortOrder — no tax-mapping or
  account-mapping rules), so B2B / export / exempt customer handling is manual.
- Tax groups, reverse charge, partial exemption, cash-basis VAT.
- E-invoicing / e-Faktur integration (references only in strings/comments).
- WHT certificates (bukti potong) and WHT report.

### 3.10 Expenses — ✅ done

Categories, expenses with approval / reject / pay (drawer, bank, petty cash) / void, audit trail, stats; web pages.
No dedicated test class (covered inside `InvoicingIT`). Missing: employee expense claims with receipts OCR,
per-diem / mileage, corporate card import.

### 3.11 Cost centres & dimensions — ⚠️ partial

`branchId` and `costCenterId` are stored on every journal line and filterable in the **detailed report**
(`DetailedReportService`). But **P&L / balance sheet / trial balance do not accept branch or cost-centre filters**,
there are no project / department / custom analytic dimensions, and no allocation rules.

### 3.12 Financial reporting — ✅ standard set, ⚠️ not "professional" yet

Done: trial balance, general ledger, account ledger, P&L, balance sheet (+ detailed), **direct-method** cash-flow
statement, detailed multi-filter report, snapshot tables with tie-out and nightly rebuild, POS → GL reconciliation,
inventory valuation + inventory GL tie-out, dashboard KPIs, CSV/PDF exports.

Missing:
- Comparative columns (this period vs prior period / prior year / budget), multi-period columns.
- **Indirect-method cash-flow** (usually required for statutory statements).
- Statement of changes in equity, notes.
- Financial report designer (custom rows/formulas), XLSX export, scheduled e-mail reports.
- Known kept quirk: `trial-balance.csv` counts draft / future lines (filter is in the `LEFT JOIN`).

### 3.13 Budgeting — ❌ missing

Only an `allowBudgeting` boolean on accounts / categories. No budget table, versions, budget vs actual report,
commitment control or PO budget check.

### 3.14 Fixed assets — ❌ not ported (0/54)

Schema is rich (acquisition, depreciation methods, disposal, revaluation, transfer, maintenance, insurance,
warranty) and web pages exist (`web/src/pages/fixed-asset/*`), but the Spring backend has **only generated
entities**: no service, no controller. Depreciation runs, disposal gain/loss and revaluation postings therefore do
not exist on the Spring stack, and the web screens 404.

### 3.15 Consolidation & intercompany — ❌ missing

No intercompany accounts, due-to/due-from automation, eliminations, transfer pricing, or group consolidation across
organizations. Branches share one ledger (dimension only).

### 3.16 Audit trail & security — ✅ strong

Audit log (`/audit-logs`), DB-level immutability for journal entries/lines, inventory ledger, payments and closed
cash shifts, permission per route with an authorization-matrix test, tenant isolation tests, MFA, approval
policies/workflows, idempotency keys on money routes.

---

## 4. Inventory — deep dive

### 4.1 Locations — ⚠️ flat

`InventoryLocation`: code, name, type (warehouse / register / bar / kitchen), active. **No parent location,
no bins/shelves/zones, no branch link, no address, no capacity.** Fine for a café; insufficient for a warehouse.

### 4.2 Stock & ledger — ✅ production-grade

- Every movement goes through `StockService` (1 065 LOC) and writes one `InventoryLedger` row
  (`qtyBefore`, `quantityChange`, `balanceAfter`, `unitCost`, type, reference, `ledgerCode`).
- Ledger is **append-only in the database** (`inventory_ledger_append_only` trigger).
- Concurrency: `FOR UPDATE` on `StockItem` / batches / serials, **transaction-scoped advisory lock** for AVCO
  recompute — correct under parallel tills.
- Stock period lock, configurable negative-stock policy (`block | warn | silent`), reversal documents
  (`reversal_in/out`) with double-reversal protection, staff attribution, idempotent replay (offline POS sync).

### 4.3 Costing — ✅ done

| Method | Status |
|---|---|
| AVCO (perpetual weighted average, 6 dp) | ✅ |
| FIFO (batch layers; requires `batchTracking`) | ✅ |
| STANDARD (variance posted to GL) | ✅ |
| SPECIFIC (serial cost) | ✅ engine; little workflow use |
| Landed cost capitalisation into layers / AVCO | ✅ (procurement) |
| Periodic AVCO, LIFO (not IFRS), last purchase price | ❌ |
| Standard-cost roll-up / revaluation run | ❌ (REVALUATION posting rule exists, no revaluation document/route) |

### 4.4 Lots, serials, expiry — ✅ done

`InventoryBatch` (batch no., expiry, mfg date, received), `InventorySerial` with status, picking strategies
FEFO / FIFO / MANUAL / SERIAL, expiring report, expiry write-off posting. Missing: lot genealogy outside
manufacturing, recall management, quarantine status / quality hold.

### 4.5 Stock documents — ✅ done

Stock-outs (categories mapped to proper GL: promo, internal use, expired …), waste (categories + summary),
adjustments (reasons), transfers (approve → dispatch → receive / recall, partial receipts, reverse), direct stock
in/out — all with approval and reversal. All 14 movement types post to GL via posting rules.

### 4.6 Counts — ✅ done

Preview, start (full/partial types), draft save, submit, review with variance value, cancel; variances post through
adjustments. Missing: cycle-count scheduling (ABC-driven), blind count enforcement option, mobile scanner flow,
count freeze per location.

### 4.7 Reservations / ATP — ✅ done

Create / release / consume, ATP query. Missing: automatic reservation from sales orders, backorders, allocation
priorities.

### 4.8 Reports — ✅ good set, ⚠️ analytics gaps

Valuation, movements, item movements, movement analysis, registers by kind, reconciliation (ledger vs balance),
stock health, negative stock, reorder, expiring, inventory GL tie-out (accounting). Missing: **stock ageing,
ABC/XYZ classification, turnover / days-on-hand, dead / slow-moving stock, forecast**.

### 4.9 Products, units, variants — ✅ done

Product types, categories with 10 account fields, variants, packaging, UoM categories & conversions, per-product
units with purchase / sales enablement (ADR-S003, V5), barcodes, min/max/reorder quantities, costing method and
batch tracking per product.

### 4.10 Procurement integration — ✅ strong

See §3.7. Receipt → stock + GRNI, bill → match → AP, landed cost → layer cost, RTV → stock out + AP debit.

### 4.11 Sales-side fulfilment — ⚠️ minimal

POS issues stock on sale (with async posting drain); non-POS `orders` module = order → invoice (stock issued at
invoice post). **No quotation → sales order → delivery order → invoice chain, no pick / pack / ship documents, no
backorders, no drop-ship, no shipping carriers.** Sales returns exist on the POS side.

### 4.12 Replenishment & manufacturing — ❌ mostly missing

Reorder **report** only (no auto PO / transfer suggestions, no reorder rules per location, no lead times / safety
stock). Manufacturing (BOM, MRP, production orders, QC, WIP reconciliation) is **0/42** on Spring, so
`PRODUCTION_CONSUME` / `PRODUCTION_OUTPUT` posting rules are seeded but never triggered. Recipe-based
ingredient consumption on POS sales does work (invoice item recipe ingredients).

---

## 5. Flexibility assessment

| Dimension | Assessment |
|---|---|
| Account determination | Excellent: 4-level resolution, per-org editable rules, preview, defaults restore |
| Costing | Per product, 4 methods |
| Policies | Negative stock, POS stock policy, closing amount visibility, approval workflows, maker-checker |
| Multi-org / tenancy | Every org-scoped query filtered; RLS planned on staging |
| Extensibility | Spring Modulith modules + after-commit domain events (outbox) — easy to add tax, budget, FA modules |
| Localisation | Org currency + timezone, VAT category on tax; no country packs (CoA + tax + reports) |
| Configuration UI | Account mappings, posting rules, taxes, payment terms, currencies, fiscal periods all have screens |
| Weak spots | Fiscal positions without rules; flat locations; statements without dimension filters; bank-rec rules fixed |

---

## 6. Production-readiness assessment

### Strengths
- Integrity enforced twice: Java rules **and** Postgres triggers (journal, ledger, payment, cash session).
- Idempotency on all money routes + `postingKey` on GL; offline sync replay tested (`SyncInventoryReplayIT`).
- Correct locking (row locks + advisory locks), `BigDecimal` with HALF_UP everywhere, no float money.
- Period-close pre-checks tie POS, inventory posting jobs and GL together.
- Test suite green (514 tests, 0 failures, last run 2026-10-26), authorization matrix and tenant isolation per module.
- Deployed and running on the VPS demo stack.

### Risks / blockers before calling it "professional production"
| # | Risk | Severity |
|---|---|---|
| 1 | Parity harness **never run** against Nest for any accounting/inventory scenario | High |
| 2 | Fixed assets 0/54 and manufacturing 0/42 — web screens exist but backend missing | High (if those features are sold) |
| 3 | Kept Nest bugs with accounting impact: inbound non-RTV debit note can't post; supplier ledger negative balance; manual invoice status wrong in list; TB CSV includes drafts | High |
| 4 | No VAT/tax return report; fiscal positions inert | High for compliance |
| 5 | No realized FX gain/loss; revaluation not callable | Medium–High for multi-currency customers |
| 6 | Bank reconciliation too basic for month-end close | Medium |
| 7 | Entry/invoice numbering not gap-free | Medium (jurisdiction-dependent) |
| 8 | JaCoCo 74 % (< 80 % target); expenses has no own test class | Medium |
| 9 | Finance web screens not walked end-to-end against Spring | Medium |
| 10 | Owner deviations (inventory `lowStock`, UoM line units, PDF engine) unapproved | Low–Medium |
| 11 | POS checkout p95 342 ms (target 150 ms) — drives stock/GL posting load | Medium |
| 12 | No DB-level Σdr = Σcr constraint | Low |

---

## 7. Gap matrix vs a professional ERP

Legend: ✅ have · ⚠️ partial · ❌ missing. Priority: **P0** = must for professional accounting/inventory,
**P1** = expected by most customers, **P2** = advanced.

### Accounting
| Capability | Status | Priority |
|---|---|---|
| Double-entry GL, journals, reversals, maker-checker | ✅ | — |
| CoA hierarchy, mappings, posting rules | ✅ | — |
| Period close / lock / reopen, retained earnings | ✅ | — |
| AR invoices, credit notes, receipts, ageing | ✅ | — |
| AP bills, 3-way match, GRNI, landed cost, WHT, ageing | ✅ | — |
| Cash sessions / drawers / tender settlement | ✅ | — |
| TB, GL, P&L, BS, cash flow (direct) | ✅ | — |
| Fix kept accounting bugs (debit note, supplier ledger, invoice status, TB CSV) | ⚠️ | **P0** |
| Fixed assets + depreciation + disposal postings (port 54 routes) | ❌ | **P0** |
| VAT/PPN return report, tax by code report, WHT report | ❌ | **P0** |
| Realized FX gain/loss on payments; revaluation route + schedule + auto-reverse | ❌ / ⚠️ | **P0** (multi-currency orgs) |
| Bank statement import (CSV/OFX/MT940/CAMT), rule-based matching vs all cash lines, rec report | ⚠️ | **P0** |
| Comparative & multi-period statements; branch / cost-centre filters on P&L/BS/TB | ❌ | **P1** |
| Budgets + budget vs actual | ❌ | **P1** |
| Recurring journals, auto-reversing accruals, prepaid/deferred schedules | ❌ | **P1** |
| Dunning, customer statements, bad-debt write-off | ❌ | **P1** |
| Fiscal-position tax/account mapping rules | ⚠️ | **P1** |
| Payment runs / bank payment files | ❌ | **P1** |
| Indirect cash flow, statement of changes in equity | ❌ | **P1** |
| Gap-free legal numbering option | ⚠️ | **P1** |
| Opening-balance / journal import tool | ❌ | **P1** |
| Analytic dimensions (project/department), allocations | ❌ | **P2** |
| Intercompany & consolidation | ❌ | **P2** |
| E-invoicing / e-Faktur | ❌ | **P2** (P0 if selling in ID to PKP businesses) |
| Report designer, XLSX export, scheduled reports | ❌ | **P2** |

### Inventory
| Capability | Status | Priority |
|---|---|---|
| Multi-location stock, append-only ledger, reversals | ✅ | — |
| AVCO/FIFO/STANDARD/SPECIFIC, landed cost | ✅ | — |
| Lots, serials, expiry, FEFO | ✅ | — |
| Transfers w/ in-transit, counts, adjustments, waste | ✅ | — |
| Reservations / ATP | ✅ | — |
| GL posting for all 14 movement types | ✅ | — |
| Valuation, reconciliation, GL tie-out | ✅ | — |
| Manufacturing / BOM / MRP (port 42 routes) | ❌ | **P0** (if sold) |
| Stock ageing, ABC/XYZ, turnover, slow-moving reports | ❌ | **P1** |
| Replenishment rules (min/max per location, lead time, safety stock) → auto PO / transfer suggestions | ⚠️ | **P1** |
| Sales order → delivery order (pick/pack/ship) → invoice, backorders | ⚠️ | **P1** |
| Hierarchical locations / bins / zones, put-away rules | ❌ | **P1** (warehouse customers) |
| Inventory revaluation document (standard-cost update) | ⚠️ | **P1** |
| Quality inspection / quarantine on receipt, recalls | ❌ | **P2** |
| Cycle-count scheduling, scanner/mobile WMS flows | ❌ | **P2** |
| Drop-ship, consignment stock, shipping carriers | ❌ | **P2** |

---

## 8. Recommended roadmap

Effort = rough engineer-weeks for one experienced developer on this codebase.

### Phase A — Certify what exists (2–3 weeks) — *gate for "production ready"*
1. Run the parity harness (`contract-tests/scenarios/accounting-*`, `inventory-*`, `invoicing-*`,
   `procurement-*`, `expenses-*`) against Nest; fix diffs.
2. Owner decision on all pending deviations; then fix the kept accounting bugs (labelled post-parity change).
3. End-to-end walkthrough of every finance/inventory web screen against Spring (Playwright).
4. Raise `application` coverage to ≥ 80 % for accounting/inventory/invoicing/procurement/expenses; add
   `ExpensesIT`.
5. Add DB constraint trigger for balanced posted entries.

### Phase B — Finish the ported scope (3–5 weeks)
6. Port **fixed assets** (54 routes: depreciation runs + posting, disposal, revaluation).
7. Port **manufacturing** (42 routes) if production is in scope.

### Phase C — Professional accounting layer (6–9 weeks)
8. Tax: VAT/PPN return + tax-by-code + WHT reports; fiscal-position mapping rules.
9. Multi-currency: realized FX on allocation/payment; revaluation route + scheduler + auto-reversal.
10. Bank: statement parsers (CSV/OFX/MT940/CAMT), rule-based matching over all cash-account lines,
    create-entry-from-line, reconciliation report with statement balance.
11. Reporting: comparatives, multi-period, dimension filters on statements, indirect cash flow, XLSX.
12. Budgets (versions, by account × period × branch/cost centre) + budget vs actual.
13. Accruals: recurring journals, auto-reversing entries, prepaid / deferred revenue schedules.
14. AR collections: customer statements, dunning levels, write-off flow; AP payment runs.

### Phase D — Professional inventory layer (5–8 weeks)
15. Analytics: ageing, ABC/XYZ, turnover, slow-moving.
16. Replenishment rules per location → suggested POs / transfers.
17. Sales fulfilment: delivery orders with pick/pack/ship, backorders, reservation from orders.
18. Hierarchical locations / bins + put-away; inventory revaluation document.

### Phase E — Advanced (as market requires)
19. Intercompany & consolidation; analytic dimensions; e-invoicing (e-Faktur); report designer; QC / recalls.

**Total to reach "professional mid-market" for both modules: ≈ 16–25 engineer-weeks** (Phases A–D).

---

## 9. Appendix — evidence

| Claim | Where |
|---|---|
| Posting engine controls | `backend/src/main/java/com/inventoryerp/accounting/application/PostingService.java` |
| Period gating / lock date | `accounting/application/FiscalPeriodControl.java` |
| Period close pre-checks & retained earnings | `accounting/application/PeriodCloseService.java` |
| FX revaluation (module API only) | `accounting/application/RevaluationService.java`; no controller references it |
| Bank match only against `Payment`, exact amount | `accounting/application/BankReconciliationService.java` `match()` |
| No FX logic in payments | `invoicing/application/PaymentService.java` |
| Direct-method cash flow; no comparatives / dimension filters on statements | `accounting/application/FinancialReportsService.java` |
| Dimension filters only in detailed report | `accounting/application/DetailedReportService.java` `Query` |
| Recurring limited to documents | `kernel/recurring/RecurringController.java` (`sales_invoice, credit_note, vendor_bill, debit_note`) |
| Sequences not gap-free | `kernel/sequence/SequenceService.java` (class doc) |
| DB immutability triggers | `backend/src/main/resources/db/migration/V1__baseline.sql` (`guard_journal_entry`, `guard_journal_line`, `guard_inventory_ledger`, `guard_cash_session`, `guard_payment`) |
| Fiscal position has no rules | `core/domain/FiscalPosition.java` |
| Flat locations | `inventory/domain/InventoryLocation.java` |
| Costing & locks | `inventory/application/StockService.java`, `CostResolver.java` |
| Fixed assets / manufacturing not ported | `fixedasset/` and `manufacturing/` contain only `domain/`; `docs/PORT_STATUS.md` 0/54, 0/42 |
| Kept Nest bugs | `docs/spec/procurement.md`, `docs/spec/invoicing.md`, `docs/spec/accounting.md` → Deviations |
| Open items (parity, coverage, web walkthrough) | `docs/HANDOVER.md` → Open items |
| No budget / dunning / consolidation / bins / delivery models | `reference/nest-api/prisma/schema.prisma` (no such `model`) and no Java hits |
