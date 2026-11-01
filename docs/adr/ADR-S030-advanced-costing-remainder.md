# ADR-S030 — Advanced costing remainder (material ledger actual costing, variance analysis, activity-based costing)

Status: **accepted** (owner instruction "build PR-8, PR-11, PR-13", 2026-11-01; roadmap 3.1 remainder, PR-13).
Implemented in `V222`, the new module `com.inventoryerp.costing` (`MaterialLedgerService` + `MaterialLedgerCalculator`,
`VarianceAnalysisService`, `ActivityBasedCostingService` + `AbcCalculator`) and the web page `/costing/advanced` (Manufacturing → Costing).

## Context

Roadmap 3.1 asks for actual costing / a material ledger, standard-cost variance analysis (price, usage, efficiency) and
activity-based costing. Already built:

| Piece | Where |
|---|---|
| Standard costs (versioned, effective-dated), revaluation on activation (5960) | ADR-S008 §1, `StandardCostService` |
| Variances booked at receipt (PPV 5905) and production (5910–5950), one `StandardCostVariance` row each | ADR-S008 §1 |
| Process costing (equivalent units, weighted average / FIFO) | ADR-S008 §2 |
| Per-order production variances on read: material price + usage, labour efficiency, overhead, yield | ADR-S018 §6 |
| Cost centres, allocations, budgets | ADR-S007, ADR-S005 |

Missing: inventory of standard-costed products stays at standard for ever (all variances go to P&L at once, even for
goods still on hand); variance figures are scattered over three reports; and overheads reach customers and products only
by blunt allocation keys.

## Decision

### 1. Material ledger — periodic actual costing

- A **run** covers one period (`periodFrom`..`periodTo`) for every product that has an active standard or a posted
  variance in the period. Runs are sequential: a new run starts the day after the last posted run.
- Per product (pure `MaterialLedgerCalculator`, unit-tested):
  - opening quantity Q₀ and the variance **carried** from the previous posted run (C₀);
  - receipts R (positive ledger moves, transfers excluded) and the period's booked variances V (`StandardCostVariance`,
    every kind; positive = unfavourable);
  - consumption X (negative moves, transfers excluded); ending E = Q₀ + R − X;
  - pool P = C₀ + V, spread over the available quantity Q₀ + R: **to inventory** P·E/(Q₀+R), **to consumption** the
    rest; periodic actual unit cost = standard + P/(Q₀+R). With no available quantity everything goes to consumption.
- **Posting** (`POST /costing/material-ledger/runs/:id/post`): one `GEN` entry per run, one line pair per product for
  the change in carried variance Δ = to-inventory − C₀: Dr / Cr **stock valuation** against **5965 Material Ledger
  Revaluation** (cost of sales; created and mapped `material_ledger_revaluation` on first use). Inventory on the
  balance sheet then shows actual cost; P&L keeps only the consumed share (variance accounts + 5965 net to it).
  The next run's C₀ is this run's to-inventory amount.
- Draft runs can be recomputed or deleted; the last posted run can be reversed (mirrored entry; carried amounts fall
  back to the run before). Periods are checked by the posting engine (closed period / books lock refuse).
- Stock quantities and unit costs in the stock engine are untouched (the revaluation lives in the GL and the ledger
  lines); reports that value stock from quants keep showing standard.

### 2. Variance analysis (`GET /costing/variance-analysis?from&to`)

- One view over every booked variance (`StandardCostVariance`) in the period: totals per kind, favourable vs
  unfavourable, per product, per supplier (purchase-price variances of receipts traced to their goods receipt / PO
  partner), per month, and a **GL tie-out** (the mapped variance accounts — `purchase_price_variance` … `production_yield_variance` —
  from the ledger vs the booked rows).
- Production variances computed on read (ADR-S018: material price, usage, labour efficiency, overhead, yield) for the
  orders completed in the period, through the manufacturing application API, shown next to the booked ones.

### 3. Activity-based costing (analytic, no postings)

- `AbcActivity`: code, name, **cost object** (`customer` | `product`), **driver** and its **resource pool**: GL accounts
  (expense), optional cost centre filter and a pool percentage (e.g. 40 % of warehouse salaries).
- Drivers measured from operational data in the period:
  - customer: `sales_invoices` (posted invoices), `invoice_lines`, `credit_notes`, `sales_orders` (confirmed),
    `payments_received`;
  - product: `units_sold` (quantity on posted invoice lines), `invoice_lines`, `production_orders` (completed, output
    product), `stock_receipts` (receipt moves).
- A **run** (`POST /costing/abc/runs {costObject, from, to}`): pool = Σ(debit − credit) of the activity's accounts
  (cost-centre filtered) × pool %; rate = pool / total driver volume; each cost object gets rate × its volume per
  activity. Results per object: revenue (posted invoices − credit notes, net of tax), estimated cost of goods (sold
  quantity × current product cost price; stated as an estimate), activity cost by activity, and **margin after
  activity costs**. Unassigned pool (activities with no driver volume) is reported, not spread.
- Runs are snapshots (`AbcRun`, `AbcRunActivity`, `AbcRunResult`); they never post to the GL.

## Routes (module `costing`)

`GET|POST /costing/material-ledger/runs`, `GET|DELETE /costing/material-ledger/runs/:id`,
`POST /costing/material-ledger/runs/:id/recompute|post|reverse`, `GET /costing/variance-analysis`,
`GET /costing/abc/drivers`, `GET|POST /costing/abc/activities`, `PATCH|DELETE /costing/abc/activities/:id`,
`GET|POST /costing/abc/runs`, `GET /costing/abc/runs/:id`. Permissions: `advanced_costing:read`,
`advanced_costing:manage` (activities, runs), `advanced_costing:post` (post / reverse material-ledger runs; those two
routes also require an `Idempotency-Key`).

## Consequences

- Additive; tenant tables with the V214 RLS policy; no existing posting changes.
- Material-ledger revaluation is at product level (not per location / lot); multi-level actual-cost roll-up (passing a
  component's variance into the finished product's actual cost) is not done — consumed variances go to P&L.
- ABC is a management-accounting analysis; it does not change inventory valuation (IAS 2) or booked COGS.

## Amendment 2026-11-01 — Cost Management CM-0

Part of the integrated Cost Management roadmap (CM-0 … CM-11, ADR-S031 onward).

- **ABC cost-centre filter rolls up**: an activity filtered on a cost centre takes the journal lines of that centre
  *and every descendant* (recursive `CostCenter.parentId`), the same roll-up as the cost-centre P&L.
  `AbcActivity.costCenterId` is now a foreign key to `CostCenter` (V223, `ON DELETE SET NULL`).
- **ABC cost of goods is booked, not estimated**: the cost of goods per customer / product is the value of the stock
  movements posted by the period's sales invoices and credit notes (`InventoryLedger`, issue +, return −), so ABC
  margins agree with the GL cost of sales. Service lines carry no cost of goods. Replaces quantity × current cost price.
- **Material ledger scope (until CM-10)**: STANDARD-costed products only; it remains an analytic actual-costing run
  that posts the price-difference split. It is *not* a valuation engine: per ADR-S031 (AD-1/AD-3) it will read cost
  lineage and post only through the inventory valuation API in CM-10.
- **Accounting regression suite**: `./gradlew accountingRegression` runs the costing / valuation / landed-cost /
  manufacturing / quarry / fleet / cost-centre integration tests (reused DB container, no coverage). Every Cost
  Management phase adds its scenario ITs to it.
- Tests: `AdvancedCostingIT.abcCostCentreRollup`.
