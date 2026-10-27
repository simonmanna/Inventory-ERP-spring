# Phase A — parity run (finance & inventory) and fix plan

> **Date:** 2026-10-27 · **Branch:** `phase/P7-beverage` · Follows
> [2026-10-27-accounting-inventory-assessment.md](2026-10-27-accounting-inventory-assessment.md).
>
> Setup: Nest reference (`reference/nest-api`, built copy) on `:3000` against `parity_nest`; Spring (`bootRun`, working
> tree) on `:3031` against `parity_spring`; both databases recreated from `parity_nest_tpl` / `parity_spring_tpl`
> (Prisma migrate + Nest seed); same JWT secrets. Modules: `accounting`, `expenses`, `inventory`, `invoicing`,
> `procurement` (8 scenario files, 183 steps).

## 1. Result

| Run | ✓ | ✗ | Notes |
|---|---|---|---|
| Previous all-module run (2026-10-26, scratchpad `parity-all.txt`) | — | 51 | **Invalid** for most files, see §2 |
| Run 1 today (hardened harness) | 143 | 40 | Two scenarios still had dead captures |
| Run 2 today (harness + scenarios fixed) | 150 | 33 | Triage below |
| **Run 3 today (S1–S5 fixed, D2 widened)** | **167** | **16** | Only D1 (15 steps, UoM fields only) and D3 (1 step) left — both owner decisions |

| Scenario | Steps ✗ | Classes |
|---|---|---|
| `accounting-ledger` | 6 | S1 ×4, S2, D3 |
| `accounting-reports` | 1 | S1 |
| `accounting-treasury` | 5 | S1 ×4, S5 |
| `expenses-lifecycle` | 0 | ✅ all match |
| `inventory-stock` | 3 | S3 ×3 |
| `invoicing-ar-ap` | 2 | S4, S3 |
| `procurement-landed-cost` | 3 | D1 ×3 (+ D2) |
| `procurement-purchasing` | 13 | D1 ×11 (+ D2), S4, S5 |

**Business logic (amounts, GL lines, stock, statuses, error messages of business rules) matched on every step.**
All 33 differences are contract-surface issues: pagination defaults, validation message lists, query-int parsing,
replay number types, and the pending UoM deviation.

## 2. Harness defects found and fixed

| # | Defect | Effect | Fix (this change) |
|---|---|---|---|
| H1 | A capture that resolved to nothing (failed login, 429, error body) was ignored | Later steps ran with an empty token or `//` paths on **both** sides; identical 401/404s counted as ✓ | `run.mjs` now fails the step: `capture:<name>: nest=missing (HTTP 4xx)` |
| H2 | Login throttle: Nest allows 10 logins / 5 min / IP; every scenario logs in | A 20-file run exceeds it → both sides 429 → H1. The 2026-10-26 all-module run was mostly unauthenticated (e.g. `journal-entries//post` steps) | Documented; run ≤ 9 files per fresh Nest process (or wait 5 min). Re-verify any earlier "all green" claims made from a long run |
| H3 | UUIDs embedded in strings (`DRAFT-<uuid>`, `cash-flow:deposit:<uuid>`, `/journal-entries/<uuid>`) were not normalized | 5 false diffs | `normalize()` replaces embedded UUIDs |
| H4 | `inventory-stock` captured `$.0.id` from an enveloped list; `invoicing-ar-ap` filtered partners with `isCustomer=true` (rejected by both: whitelist) | `loc` / `customer` empty on both sides → later document steps exercised only validation | Capture `$.data.0.id`; look the customer up with `search=CUST-001` |

## 3. Spring parity bugs (Nest wins — fix without owner approval) — ✅ all fixed in run 3

What changed (backend build green: 515 tests, 0 failures):

- **S1** removed the `pageSize ?? 200/100` overrides in `AccountService`, `JournalService`, `BankAccountService`
  (base default 25). Web screens that need more already send `pageSize`.
- **S2** `EmptyBodyAdvice` records the body type; `GlobalExceptionHandler` re-reads the cached body skipping unknown
  properties (collecting **all** of them, with parent paths) and validates it, so one 400 carries whitelist + field
  messages.
- **S3** kernel now sorts validation messages like class-validator (declared property order → list index → reverse
  annotation order; Hibernate Validator's `HashSet` order was random). New `@ArrayMinSize` (fails on null) replaces
  `@Size(min = n)` on 20 required arrays; 41 required strings got the missing `@NotNull("must be a string")`, found
  by comparing Nest DTO decorators with the Java records (`{@code XxxDto}` Javadoc mapping).
- **S4** `QueryParams.parseInt` (JS `Number()` → NaN = default → integer check) replaces the four local copies.
- **S5** `IdempotencyService.complete()` caches the body with decimals as JSON numbers (Prisma `Json` column).
  `BusinessOutcome` keeps strings: Nest writes that one through `JSON.stringify`.
- Tests: new assertions in `KernelUnitTest`, `AccountingIT`, `TreasuryIT`, `InventoryIT`, `InvoicingIT`; stale
  expectations that encoded the old Spring behaviour updated in `AccountingIT`, `CoreIT` (verified on Nest),
  `InvoicingIT`, `ProcurementIT`, `OrdersIT`.
- Found on the way: `TreasuryIT` / `TreasurySpecsIT` depend on test-class order (admin cash shift left open by
  other suites). Gradle's "previously failed first" ordering exposes it; `cleanTest` restores the default order.

| # | Diff | Root cause | Fix | Steps |
|---|---|---|---|---|
| **S1** | `GET /accounts`, `/journals`, `/bank-accounts` without `pageSize`: Nest `meta.pageSize = 25`, Spring 200 / 100 | Nest `PaginationDto` has a field initializer `pageSize = DEFAULT_PAGE_SIZE`, so the controller's `query.pageSize ?? 200` is dead code. Spring ported the intent | Default 25 in `AccountController`, `JournalController`, bank-account list. Check the same pattern in `documents.controller.ts` (`?? 20`) and the notifications list | 9 |
| **S2** | Body with an unknown property **and** a missing field: Nest returns `["property categoryKey should not exist", "categoryId should not be empty", "categoryId must be a string"]`; Spring only the first | `GlobalExceptionHandler.unreadable()` answers on Jackson's `UnrecognizedPropertyException` before Bean Validation runs | Kernel: on unknown properties, re-read the body ignoring unknowns, validate, and return whitelist + field messages in class-validator order. Fixes every module at once | 1 |
| **S3** | Missing required field / array: Nest emits every failing constraint (`code should not be empty` + `code must be a string`; `items must contain at least 1 elements` + `items must be an array`); Spring emits one | DTO records only carry `@NotBlank` / `@NotNull`; class-validator also fails `@IsString`, `@IsArray`, `@ArrayMinSize` on `undefined` | Per-DTO: add the missing constraints with Nest's order. Seen in `CreateLocation`, stock-out, adjustment (`responsibleById`, `approvedById`, `items`), invoice `lines`. **Systemic:** write a checker (`tools/`) that compares class-validator decorators per DTO field with the Java record annotations and run it over all modules | 4 |
| **S4** | `GET /invoices?page=x`, `/procurement/purchase-orders?page=x`: Nest 200 (page 1), Spring 400 `Validation failed (numeric string is expected)` | Nest's global `ValidationPipe` first converts the query to `Number` (`'x'` → `NaN`), then `DefaultValuePipe` treats `NaN` as missing. Probed on live Nest: `x`/`""`/`%20` → default, `1e1` → 10, `0x2` → 2, `1.5`/`Infinity` → 400 | One kernel helper with JS `Number()` semantics (NaN → default; non-integer / non-finite → 400) replacing the four copies of `parseInt` in `InvoicingDtos`, `ProcurementDtos`, `PosDtos`, `OrdersController` | 2 |
| **S5** | Idempotent **replay** returns decimals as JSON numbers in Nest (`"debit": 1000`), strings in Spring (`"1000"`); first response matches | Nest writes the handler body into a Prisma `Json` column, which serializes `Decimal` as numbers | `IdempotencyService.complete()` (and `BusinessOutcome` merge) write the cache with a mapper that emits `BigDecimal` as numbers. Clients (web, Android) already handle Nest's numbers | 2 |

## 4. Pending owner decisions (no code until decided)

| # | Diff | Status | Steps |
|---|---|---|---|
| D1 | `uomId` / `uomFactor` on PO / GRN / debit-note lines, `units` on products (ADR-S003, V5) | Listed under procurement/invoicing "Deviations — pending owner approval". If approved → add `allowed-diffs.json` entries for these paths | 14 |
| D2 | `doublePourMl` on products outside the beverage scenarios | **Approved** (ADR-S004 §3) but `allowed-diffs.json` scopes it to `beverage-` → widen the entry | 2 (overlaps D1) |
| D3 | 500 detail text (`error`, `name`, `stack`) on `GET /currencies/rate` with missing params | Accounting deviation #3 (pending). Dev-only fields; both answer 500 | 1 |

## 5. Kept accounting bugs — fix plan (post-parity, owner-approved, labelled commits)

Each of these keeps a Nest bug on purpose today. Each fix is its own labelled change (`fix(<module>)!: … (post-parity)`)
that moves the behaviour into the module spec's **Deviations** table and adds an `allowed-diffs.json` entry for the
changed step, plus integration tests.

| # | Bug | Impact | Fix | Tests | Effort |
|---|---|---|---|---|---|
| **B1** | Posting an **inbound non-RTV debit note** always fails `Unbalanced entry` (`DebitNotesService.post`: AP credited **and** expense credited, line 252) | A supplier's debit note can never be posted, so AP is understated | Inbound → **Dr expense / Cr AP** (`Line.debit` for the expense leg); check how tax on note lines posts (subtotal vs total) and post input tax to `tax_receivable` | `ProcurementIT`: inbound plain note posts, entry balanced, AP balance + ageing include it; parity step "DN: inbound plain" becomes an allowed diff | 0.5 d |
| **B2** | **Supplier ledger** (`VendorBillService.ledger`) lists only `draft / posted / cancelled` bills: paid bills vanish while their payments stay → negative closing balance. Also: draft and cancelled bills **add to** the balance, voided payments reduce it, money is summed in `double` | Wrong supplier statements; breaks AP reconciliation | Bills `posted`, `paid` (and partially paid) count; drafts excluded from the balance; a cancelled bill / voided payment appears as a pair that nets to zero (or is excluded); add debit notes and vendor credit; `BigDecimal` throughout; closing balance must equal the partner's AP sub-ledger | Ledger closing balance = AP ageing total for the partner = GL AP lines tagged with the partner | 1.5 d |
| **B3** | **Manual invoices** in `GET /invoices` always report `paymentStatus: "unpaid"`, `settlementStatus: "unsettled"` (`InvoiceService` lines 160–161); filters only match POS rows | AR list wrong for every manual invoice | Derive from `amountResidual` vs `totalAmount` (`unpaid` / `partial` / `paid`) and settlement; filters then work on both kinds | Invoice → partial payment → full payment shows the three states; filter returns it | 0.5 d |
| **B4** | **`trial-balance.csv`** counts draft and post-`asOf` lines: entry status/date filter sits in the `LEFT JOIN` while sums read `jl.*` | The exported TB disagrees with `GET /reports/accounting/trial-balance` | Sum only lines whose entry matched (`SUM(CASE WHEN je.id IS NOT NULL …)` or join a filtered sub-select); use base amounts like the JSON TB | CSV totals = JSON TB per account, with a draft and a future-dated entry present | 0.5 d |
| **B5** | **Reopening a period reverses its closing entry dated _today_** (`PeriodCloseService.reopen` → `PostingService.reverse(id, null, …)` → `Instant.now()`) | The reopened period keeps zeroed revenue/expense while the **current** period receives the prior period's whole P&L a second time — current P&L and retained earnings are overstated until re-close. Also why re-close answers "Nothing to close" | Date the reversal at the closing entry's own posting date (period end), inside the reopened period, so the period returns to its pre-close state and can be closed again | Close Jan → reopen → Jan P&L restored, Feb P&L unchanged → close Jan again succeeds | 0.5 d |
| **B6** | `GET /procurement/purchase-orders?search=…` → **500** (`contains` on enum columns) | PO search unusable | Search text columns only (number, partner name, notes); match enum columns by equality when the term is a valid value | Search by PO number / supplier returns rows | 0.25 d |
| **B7** | `PATCH /goods-receipts/:id/reverse` cancels the receipt's draft landed costs **before** the bill / payment checks (autocommit) | A refused reversal still destroys draft landed costs | Run the cancellation after the checks, in the reversal transaction | Refused reversal leaves draft landed costs `draft` | 0.25 d |
| B8 | POS → GL reconciliation `unpostedJobs` | Already fixed in Spring (accounting deviation #1) | Owner approval only | — | — |
| B9 | `PATCH /cash-sessions/:id/reconcile` with `notes` that do not start with the stored notes → **500** (DB trigger) | Confusing failure for managers | Return 400 with a clear message (or append automatically) | Mismatched notes → 400 | 0.25 d |

Priority: **B5, B1, B2** (financial statement correctness) → B4, B3 → B6, B7, B9.

## 6. Execution order

1. **Fix S1–S5** + widen the D2 allowed-diff (parity work, ≈ 2–3 days incl. the S3 DTO checker); rerun
   (`node contract-tests/run.mjs --module <m>` per module on a fresh Nest) → target **zero unexplained diffs**, only
   D1/D3 left.
2. **Owner decisions:** D1 (UoM, ADR-S003), D3, and approval of the B-list behaviour changes.
3. **Implement B1–B9** as labelled post-parity commits (≈ 4–5 days), each with an integration test, a Deviations
   entry and an `allowed-diffs.json` line.
4. Tick the "Parity scenarios" box in `docs/spec/{accounting,inventory,invoicing,procurement,expenses}.md` and update
   `docs/HANDOVER.md` open item 1.
5. Continue Phase A: finance web screen walkthrough, coverage ≥ 80 %, `ExpensesIT`.

## 7. How to rerun

```bash
# reset both databases (servers stopped)
for d in parity_nest parity_spring; do
  docker exec ierp-db psql -U erp -d postgres -c "DROP DATABASE IF EXISTS $d WITH (FORCE)" -c "CREATE DATABASE $d TEMPLATE ${d}_tpl"
done
# Nest: built reference copy, PORT=3000, DATABASE_URL=.../parity_nest, same JWT_ACCESS/REFRESH/POS secrets as Spring
# Spring: cd backend && PORT=3031 JDBC_DATABASE_URL='jdbc:postgresql://localhost:5434/parity_spring?stringtype=unspecified' ./gradlew bootRun
for m in accounting expenses inventory invoicing procurement; do
  NEST_URL=http://127.0.0.1:3000 SPRING_URL=http://127.0.0.1:3031 node contract-tests/run.mjs --module $m
done
```

Note: if `node dist/main.js` fails with `Cannot find module '@nestjs/mapped-types'`, the pnpm links in the Nest
copy were damaged (package root files deleted); `pnpm install --frozen-lockfile --force --prefer-offline` then
`npx prisma generate` restores it.
