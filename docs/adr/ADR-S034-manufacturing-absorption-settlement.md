# ADR-S034 — Manufacturing cost unification and IAS 2.13 absorption settlement (Cost Management CM-4)

- Status: accepted (CM-4 built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), AD-10, AD-11, R3-2, C0
- Migration: `V227__cm4_absorption_settlement.sql`
- Extension: no NestJS counterpart.

## Context

- Rates × drivers absorb conversion cost into production (`CostAbsorptionService`), but nothing compared the absorbed
  amount with the actual cost booked. Under- and over-absorbed overhead stayed in the P&L pool account (5350) with no
  treatment by cause.
- IAS 2.13 requires fixed overhead to be absorbed on **normal** capacity. Unallocated overhead from low production is
  expensed. Abnormally high production lowers the fixed overhead per unit so inventory is never above cost.
- The stage engine posted its standard-cost difference as an unregistered lump on 5950. The single-leg order went
  through `StandardCostService.postProductionVariances`, which records each variance in the register.

## Decision

### 1. Governed normal capacity — `WorkCenterCapacity`

Each row holds:

- the work centre and the **cost centre** that collects its actual cost (the link CM-3 lacked)
- basis: `machine_hours` or `labour_hours`
- normal capacity and budgeted fixed overhead, per `periodDays` (default 30)
- optional governed fixed rate
- a derivation text (≥ 10 characters: average of past periods adjusted for planned maintenance, or design capacity ×
  expected efficiency)
- rate source: `manual`, `fleet_tco` or `payroll_actual`
- `effectiveFrom` (never in the past) and `reviewDue` (≤ 1 year later)

Governance:

- Status goes `proposed → approved`. The approver must not be the proposer (403).
- Settlement uses the approved row in force at the period end.
- A missing capacity blocks settlement. So does a capacity whose review date has passed.

### 2. Settlement — `AbsorptionSettlementService` (costing)

**Inputs** per work centre and period:

| Input | Source |
|---|---|
| Actual (L3) | Expense / cost-of-sales lines posted on the work centre's cost centre, excluding the pool account and earlier settlements. Each account goes through the **AD-10 gate**. `production_capitalisable` enters the pool. Other classes are reported as `expensedDirectly`. An unclassified account **blocks** the settlement |
| Absorbed | `ProductionCostAbsorption` of the work centre in the period |
| Activity H | Run hours (machine basis) or clocked crew hours, else run hours (labour basis), of the centre's completed work orders |
| N, F | Capacity × period days / `periodDays` |

**Decomposition** (`AbsorptionSettlementCalculator`, pure; the three parts always sum to `actual − absorbed`):

| Component | Formula |
|---|---|
| volume | `(N − H) × F / N` |
| rate error | `(F / N − r) × H`, where `r` is the governed rate (else `F / N`) |
| spending | the remainder |

**Timing items block the settlement.** They are reported in the preview:

- draft journals on the cost centre
- conversion cost not absorbed yet
- absorption of a plain order that has not posted its output
- orders still open

**Treatment by cause (AD-11).** There is no blanket pro-rata.

| Cause | Treatment |
|---|---|
| Below the governed `materiality_threshold` | whole difference → cost of sales |
| Volume, activity below normal | expense **5970 Unabsorbed Production Overhead** |
| Volume, activity above normal | reduce the **period's output** through lineage |
| Spending / rate error, favourable | reduce the period's output through lineage |
| Spending / rate error, unfavourable, within the `price_difference_tolerance_pct` of absorbed | capitalise into the period's output through lineage |
| Above tolerance, reviewer classifies `abnormal` | expense **5972 Abnormal Production Cost** |
| Above tolerance, reviewer classifies `normal` | capitalise, with a justification of at least 10 characters |
| Above tolerance, no classification | blocked |

**Lineage of the period's output.**

1. The amount is split over the orders that absorbed in the period, weighted by their absorbed amount (largest
   remainder).
2. Each order's share is split over its output receipt rows (plain and staged outputs), weighted by value.
3. Each share runs through `CostLineageService.distribute`:

| Lineage target | Account |
|---|---|
| on hand (and transferred) | 1396 price-difference **adjunct**, with a value-only stock-ledger row (`valuationComponent = price_difference`), released to cost of sales by the CM-1 release run |
| sold | cost of sales |
| consumed by an open order | WIP |
| scrapped | stock adjustment expense |

Older layers are never touched. The counter-entry of every line is the overhead-absorbed pool (5350).

**Posting and integrity.**

- One journal per settlement, dated the period end. Source `absorption_settlement`, which is also a costing source for
  the branch-dimension guard.
- One posted settlement per work centre and overlapping period (409).
- `Idempotency-Key` is required on post and reverse.
- Reversal mirrors the entry and writes negative value-only rows. It is refused once any adjunct share has been
  released.

### 3. Cost-flow bridge, production part (C0)

`GET /costing/cost-flow-bridge?from&to`, per work centre with a capacity:

- eligible actual
- expensed directly
- capitalised by absorption
- settled by treatment
- **unallocated** = eligible actual − absorbed − settled. This is 0 once the period is settled.

### 4. One variance engine

- The stage engine's standard-cost difference now goes through `StandardCostService.postStageVariance`. The accounts
  (5950 vs WIP), the source and the description are unchanged, but the variance is now recorded in the
  `StandardCostVariance` register like a single-leg order's.
- `VarianceCalculator` gains the component split: labour rate / efficiency, fixed-overhead spending / volume, and
  material mix / yield. Each pair reconciles to its total.

## Routes

| Verb | Path | Permission |
|---|---|---|
| GET / POST | `/costing/normal-capacities` | `advanced_costing:read` / `advanced_costing:manage` |
| POST | `/costing/normal-capacities/:id/approve` | `accounting_policy:approve` (maker ≠ checker) |
| POST | `/costing/absorption-settlements/preview` | `advanced_costing:manage` |
| GET / POST | `/costing/absorption-settlements` (POST: Idempotency-Key) | read / `advanced_costing:post` |
| GET | `/costing/absorption-settlements/:id` | read |
| POST | `/costing/absorption-settlements/:id/reverse` (Idempotency-Key) | `advanced_costing:post` |
| GET | `/costing/cost-flow-bridge?from&to` | read |

## Messages (verbatim)

- `No approved normal capacity for work centre <code> (IAS 2.13)`
- `The normal capacity of <code> was due for review on <date>; approve a reviewed capacity first`
- `<n> draft journal entr(y|ies) on the cost centre in the period; post or delete them first (timing)`
- `Account <code> carries cost on the cost centre but has no cost eligibility (IAS 2.10–2.16); classify it under
  Costing & Valuation`
- `Production order <code> has absorbed cost that is not posted yet; complete the order first (timing)`
- `Production order <code> is still open; settle once it has completed (timing)`
- `Production order <code> has conversion cost not absorbed yet (timing)`
- `Unfavourable spending of <x> exceeds the tolerance of <y>; classify it as normal or abnormal`
- `Capitalising spending above the tolerance needs a justification of at least 10 characters`
- `Settlement blocked: <blockers>`
- `Work centre <code> already has a posted settlement overlapping <from> – <to>; reverse it first`
- `Nothing to settle: absorbed equals actual for the period`
- `A settlement cannot end in the future`
- `Only a posted settlement can be reversed`
- `Part of this settlement was already released to cost of sales; post a correcting settlement instead`
- `A normal capacity must be approved by someone other than its proposer`
- `A normal capacity applies prospectively; effectiveFrom cannot be in the past`
- `reviewDue must fall after effectiveFrom and within one year of it`

## Deferred, with the reason

| Item | Deferred to | Reason |
|---|---|---|
| Settlement shares into **open** orders (WIP) | CM-10 cost adjustment run | Open orders block settlement as a timing item, so the WIP subledger never diverges from the GL. The lineage WIP target still applies when an output was consumed by another open order |
| `CostAllocationService` reimplemented as a single-segment cycle | CM-10 | Its routes, rules and `CostCenterAccountingIT` stay unchanged. Re-platforming them adds risk with no new behaviour, so it moves next to the close cockpit that consumes both |
| Misposting detection (absorption on the wrong work centre) | CM-10 | Needs the close cockpit's correction workflow |
| `RateResolver` fallback to crushing-plant / quarry-equipment rate fields | CM-5 | Belongs with the quarry / crushing KPI unification |
| Element split (material / labour / overhead) of stage-order variances | CM-10 material-ledger rework | Stage outputs carry no element split yet. They post to yield, as before, but are now registered |
| Tonnes / units capacity basis | CM-5 | Crushing plants measure in tonnes |
| Single-leg by-products through `JointCostAllocator` | CM-5 | Reviewed with the joint-product KPI work, as the exception register says |

## Tests

- `AbsorptionSettlementCalculatorTest` (4):
  - 400 randomised exact-sum decompositions
  - the treatment matrix: low volume, tolerance block, abnormal, normal reviewed, within tolerance, high volume,
    favourable, materiality
  - labour / overhead / mix splits reconcile
- `AbsorptionSettlementCausesIT` (4):
  - blockers: no capacity, unabsorbed cost, unposted absorption, stale review, unclassified account
  - under-absorbed: 50 to 5970 and 30 to 5972 against the pool, 409 on re-settle, bridge unallocated 0, reversal
    mirrors the entry, double reversal refused, tenant isolation
  - over-absorbed: −20 credited only to the period's output layer (adjunct and value-only row), the older layer
    untouched, reversal nets the ledger to 0
  - capacity validation and the authorization matrix (9 routes)
- Added to `accountingRegression`.
