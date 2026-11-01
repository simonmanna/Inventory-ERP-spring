# ADR-S033 — Cost-centre allocation engine: financial and statistical (Cost Management CM-3)

- Status: accepted (CM-3 built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), AD-5, AD-8, R3-6
- Migration: `V226__cm3_allocation_engine.sql`
- Extension: no NestJS counterpart.

## Context

The codebase had ten allocation engines, each with its own rounding. `CostAllocationService` was single-step with
static weights, and its `driver` field was stored but never used. There were no assessment cycles, no reciprocal
service-department allocation, and no statistical (memo) allocation for management reporting.

## Decision

### 1. One pure primitive — `AllocationCalculator` (AD-8)

`allocate(total, receivers[key, basis], rounding, scale)`:

- Rejects a negative basis and an all-zero basis.
- Shares always sum **exactly** to the total.

| Rounding | Behaviour | Use |
|---|---|---|
| `LARGEST_REMAINDER` (default) | Every share is rounded down; the leftover units go to the largest remainders | Default |
| `LAST` | Half-up; the last receiver takes the difference | Landed-cost style |
| `MAIN` | Half-up; the first receiver takes the difference | Joint-cost style |

`reciprocal(D, F)` solves `T = D + F·T` by fixed-point iteration (100 rounds, 1e-9). It refuses service centres that
allocate everything to each other, because that system has no solution. Both functions are pure, so they can be
reused by the CM-11 simulation.

### 2. Allocation cycles — `CostCycleService`

**Structure.**

- `AllocationCycle` (code, posting mode, method) holds ordered `AllocationSegment`s.
- A segment has:
  - a sender cost centre and its source accounts (expense or cost-of-sales only; empty = every expense account it
    posted to)
  - receiver cost centres
  - a driver
  - an optional statistical key or manual values
  - a share % and a rounding policy

**Measured drivers.** Never assumed.

| Driver | Source |
|---|---|
| `equal` | 1 per receiver |
| `manual`, `fixed_pct` | Values entered on the segment |
| `posted_cost` | The receiver's own posted expense in the period. The allocation's own entries are excluded |
| `revenue` | The receiver's posted revenue |
| `tonnage`, `distance_km`, `tonne_km` | Dispatched `FleetLoad` rows carrying the receiver's cost centre |
| `statistical_key`, `labour_hours`, `machine_hours`, `operating_hours`, `headcount`, `floor_area` | A `StatisticalKey` with period values per receiver |

The shop-floor tables do not carry a cost centre yet, so the hour drivers are loaded as statistical keys. A direct
reader arrives with CM-4, once a work centre is linked to its cost centre.

**Methods.**

- **Sequential (step-down)**: segments run in order. A later sender also passes on what it received earlier in the
  same run.
- **Reciprocal**: per cost element, the senders' totals are solved simultaneously. Each sender then allocates its
  total to its receivers. Service centres end the run at zero.

**Posting modes (AD-5).**

- **Financial**: one journal entry dated the period end, source `cost_allocation`. The cost element is preserved: Cr
  the account on the sender's cost centre, Dr the same account on each receiver. The P&L total does not change; only
  the cost-centre view moves. Period locks apply. Reversal mirrors the entry.
- **Statistical**: the same lines are computed and kept with `journalEntryId = null`. There is no GL movement, so
  statistical runs are excluded from GL reconciliation by construction.

**Integrity.**

- One posted run per (cycle, period): unique index plus a 409. Reverse the run to re-run the period.
- Run and reversal require an `Idempotency-Key`.
- Every `AllocationLine` keeps segment, sender, source account, receiver, driver value, share, amount and journal
  entry.

### 3. Allocation-engine exception register (R3-6)

Existing engines move onto the primitive only behind a golden test that proves identical output. Until then each one
is registered with its decision, protecting tests and review phase. `AllocationExceptionRegisterTest` (ArchUnit) fails
when an unregistered top-level `*Allocat*` class appears in an application package.

| Engine | Owner | Why it stays independent | Protecting tests | Decision / review |
|---|---|---|---|---|
| `LandedCostVoucherService` basis split | procurement | Remainder on the last line, manual per-line amounts, parity | `LandedCostVoucherIT` | Migrate to `AllocationCalculator.LAST` with a golden test — **CM-6** |
| Legacy `LandedCostService` | procurement | Nest parity route | `ProcurementIT.landedCost*`, contract tests | Retire by delegating to the voucher — **CM-6** |
| `JointCostAllocator` | manufacturing | The carve-out pre-step (NRV / zero / carve %) is accounting policy | `JointCostAllocatorTest`, `MultiStageProductionIT` | Keep as a policy layer; delegate the final rounding if the golden test matches — **CM-5** |
| `CostAbsorptionService` / `CostAbsorptionCalculator` | manufacturing | Rate × driver absorption (L1 → L2), not pool allocation | `CostAbsorptionCalculatorTest`, `ManufacturingCostingIT` | **Permanently independent.** Settlement uses the engine — CM-4 |
| `FleetWorkOrderCostService` | fleet | Hours × rate absorption | `FleetCostingIT` | **Permanently independent** |
| `FleetCostCalculator.allocate` | fleet | `fleet.tripCostBasis` | `FleetCostingPoliciesTest`, `FleetCostingIT` | Migrate (tonne-km, m³, empty legs) — **CM-7** |
| `QuarryCostCalculator` | quarry | Net / gross tonne basis feeding the posting | `QuarryCalculatorsTest`, `QuarryIT` | Migrate the allocation part — **CM-5** |
| `HrPayrollCostAllocator` | hr | Must match payslip cents per employee | HR payroll ITs | **Permanently independent.** Its output is an L3 source for cycles |
| `CostAllocationService` | accounting | Existing routes and rules (`CostCenterAccountingIT`) | `CostCenterAccountingIT` | Reimplement as a single-segment cycle with routes unchanged — **CM-4** (deferred from CM-3 to keep this PR's risk on new code only) |
| `AbcCalculator` | costing | Analytic by nature | `AbcCalculatorTest`, `AdvancedCostingIT` | Becomes statistical allocation documents — **CM-9** |
| `ProcessCostCalculator` | manufacturing | A valuation method (equivalent units), not allocation | `ProcessCostCalculatorTest` | **Permanently independent** |

## Routes

| Verb | Path | Permission |
|---|---|---|
| GET / POST | `/costing/allocation-cycles` | `cost_allocation:read` / `cost_allocation:manage` |
| GET / PUT | `/costing/allocation-cycles/:id` (PUT replaces the segments when given) | read / manage |
| POST | `/costing/allocation-cycles/:id/preview` (`{from, to}`) | `cost_allocation:manage` |
| POST | `/costing/allocation-cycles/:id/runs` (`{from, to}`, Idempotency-Key) | `cost_allocation:post` |
| GET | `/costing/allocation-runs`, `/costing/allocation-runs/:id` | read |
| POST | `/costing/allocation-runs/:id/reverse` (Idempotency-Key) | `cost_allocation:post` |
| GET / POST | `/costing/statistical-keys` | read / manage |
| PUT | `/costing/statistical-keys/:id/values` | manage |

Web: `/costing/allocation` (cycles, preview, post, runs, reverse).

## Messages (verbatim)

- `A cost centre cannot allocate to itself`
- `basis must be one of the following values: …`
- `basis <x> is measured through a statistical key; choose one`
- `give a <manual|fixed pct> value for every receiver`
- `sender accounts must be expense or cost-of-sales accounts`
- `sharePercent must be greater than 0 and at most 100`
- `This cycle already has a posted run for <from> – <to>; reverse it first`
- `Nothing to allocate: the senders have no cost in the period`
- `A run cannot end in the future`
- `A reciprocal cycle needs exactly one segment per sender cost centre`
- `A reciprocal cycle allocates 100 % of each sender`
- `… reciprocal allocation has no solution`
- `Only a posted run can be reversed`

## Tests

- `AllocationCalculatorTest` (5): largest remainder; last / main; 600 randomised exact-sum runs (positive and
  negative); refusals; reciprocal 1071.43 / 714.29 and the no-solution loop.
- `AllocationCycleIT` (5):
  - financial step-down 1000 by headcount, then 800 equally. Senders end at 0; plants get 1000 and 600; every line
    traces to account and entry. Idempotency-Key required, 409 on re-run, reversal restores, double reversal refused,
    tenant isolation.
  - statistical run: same total, zero journal lines.
  - reciprocal run: service centres at 0, production gets all 1600.
  - refusals.
  - authorization matrix.
- `AllocationExceptionRegisterTest` (ArchUnit).
