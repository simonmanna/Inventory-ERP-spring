# ADR-S037 — Fleet and distribution costing; branch transfers vs intercompany (Cost Management CM-7)

- Status: accepted (CM-7 built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), G9, G16, AD-6, AD-10
- Migration: `V230__cm7_distribution_branch_transfer.sql`
- Extension: no NestJS counterpart.

## Context

- Third-party freight was already accrued per trip: Dr 6460 / Cr 2169, matched to the transporter's bill with a
  variance tolerance (`FleetFreightBillService`).
- Own-fleet delivery cost (fuel, maintenance, tyres, depreciation, driver) stayed in the vehicle cost pools and never
  reached the margin of the delivery it served.
- Empty return legs were not modelled.
- Transporter rate cards knew only per trip, per tonne and per km.
- Stock moved between branches with no branch view of the movement, so no branch trial balance could balance.

## Decision

### 1. Transporter rate cards

- New bases `per_m3` (planned volume) and `per_tonne_km`, plus a **minimum charge** per trip on the rate card. They
  apply where a trip's freight is computed (`FreightCalculator`).
- The existing bill matching (expected vs billed, variance tolerance, acceptance) remains the carrier variance control.

### 2. Empty return legs and cost per tonne-km — `DistributionCostCalculator` (pure)

- `FleetLoad.emptyKm` records the return leg beside `distanceKm`, which is the loaded leg.
- Governed election `empty_leg_policy`:

  | Value | Rule |
  |---|---|
  | `loaded_leg` (default) | Each trip carries its own empty return |
  | `pooled` | The vehicle's empty km are shared over its trips by loaded tonne-km |

- Each vehicle's total `rate × (loaded + empty km)` is rounded once and split by charged km (largest remainder), so
  trips sum exactly.
- Cost per tonne-km = amount ÷ (tonnes × loaded km).

### 3. Delivery cost run — `DistributionCostingService`

The run covers own-fleet trips delivered in the period, optionally for one branch.

**Rate per km:**

1. a manual rate, if given
2. otherwise the vehicle's TCO cost per km from the fleet costing report for the period
3. otherwise the run is refused

**Posting.** One journal entry dated the period end:

- Dr **6460 Outbound Freight & Transport** per trip, with `customerId`, `fleetLoadId`, `deliveryOrderId` and
  `vehicleId` dimensions, the trip's cost centre, and the delivery order's branch
- Cr **5385 Own Fleet Cost Recharged** per vehicle

**Effect.**

- The entry reclassifies cost by function and is P&L-neutral.
- Customer delivery is `distribution` (AD-10): it is expensed and never reaches inventory.
- Quarry face-to-crusher haulage stays capitalisable through quarry absorption.
- Inter-branch freight is capitalised (see §4).

**Integrity.**

- One posted run per period per branch (or for all branches); an overlapping run is refused with 409.
- `Idempotency-Key` is required.
- A run can be reversed.

### 4. Same-entity branch transfer (AD-6)

`POST /costing/branch-transfers` moves stock between locations of two **different branches** of this organisation at
carrying amount. The stock engine's own transfer posting is skipped (`skipGl`, now honoured by
`StockService.transfer`), and one journal carries the branch on every line:

| Line | Branch |
|---|---|
| Dr stock | destination |
| Cr stock | source |
| Dr **1299 Inter-branch Clearing** (due from), with inter-branch clearing on (default) | source |
| Cr 1299 (due to), with inter-branch clearing on | destination |

Each branch's lines net to zero, and the 1299 balances net to zero across the organisation
(`GET /costing/branch-transfers/clearing`).

**Freight to the destination** is `transfer_capitalisable` (IAS 2.10). It is capitalised on the **transfer-in layer
through lineage**: on hand → 1396 adjunct, sold since → cost of sales. The freight source may be cash, bank or
payables. An expense account used as the source must be classified capitalisable (AD-10). Lineage adjustment type:
`transfer_freight`.

**Guards.**

- Both locations need a branch, and the branches must differ.
- An unclassified expense freight source is refused.
- Another organisation's location is 404.

**Intercompany vs branch.** A different legal entity is a different organisation (ADR-S028), and tenancy scopes every
location to one organisation. So a branch transfer cannot cross entities, and an intercompany document cannot be
raised between branches of one entity: both cases are excluded by construction.

## Routes

| Verb | Path | Permission |
|---|---|---|
| POST | `/costing/delivery-cost-runs/preview` | `advanced_costing:manage` |
| GET / POST | `/costing/delivery-cost-runs` (POST: Idempotency-Key) | `advanced_costing:read` / `advanced_costing:post` |
| GET | `/costing/delivery-cost-runs/:id` | `advanced_costing:read` |
| POST | `/costing/delivery-cost-runs/:id/reverse` (Idempotency-Key) | `advanced_costing:post` |
| GET | `/costing/branch-transfers`, `/:id`, `/clearing` | `inventory_valuation:read` |
| POST | `/costing/branch-transfers` (Idempotency-Key) | `inventory_valuation:post` |

## Messages (verbatim)

- `Trip <code> has no distance; record it before costing`
- `No cost per km for vehicle <plate> in the period; give a manual rate`
- `A delivery cost run already covers <from> – <to>; reverse it first`
- `Nothing to charge: no own-fleet trip was delivered in the period`
- `Both locations need a branch for a branch transfer`
- `Both locations belong to the same branch; use a stock transfer`
- `The freight account is not classified as capitalisable (IAS 2.10–2.16); …`
- `The freight is paid per tonne-km: the trip needs its tonnage and distance`
- `The freight is paid per m³ but the trip has no planned volume`

## Deferred, with the reason

| Item | Deferred to | Reason |
|---|---|---|
| In-transit stage (ship / receive in two steps with an in-transit account) | Owner decision | Needs an in-transit location type and a receiving step on the transfer document; a same-day transfer covers the current branches |
| Per-branch / per-location stock accounts (`InventoryPostingRule.branchId/locationId`) | CM-10 | Branch views come from the line dimension; separate accounts are a presentation choice that the close cockpit reconciles |
| Migrating `FleetCostCalculator.allocate` onto `AllocationCalculator` | CM-10 | The delivery cost run uses the shared calculator for its new allocation. The legacy trip-cost report keeps its `fleet.tripCostBasis` behaviour and remains in the exception register |
| Intercompany stock sales at a transfer price with unrealised-profit elimination | PR-8 follow-up | Belongs to the intercompany module's mirrored documents and consolidation |
| Fleet part of the cost-flow bridge (vehicle pool = recharged + idle) | CM-10 | The complete bridge is a close gate |

## Tests

- `DistributionCostCalculatorTest` (4):
  - loaded leg: 400 / 100 / 120, 0.2 per tonne-km
  - pooled: 66.7 / 33.3 empty km, vehicle total 500 unchanged
  - 300 randomised exact sums under both policies, and an unknown policy refused
  - transporter per tonne-km / per m³ / minimum charge, and the missing-driver refusals
- `DistributionBranchScenarioIT` (2):
  - **branch transfer:** Idempotency-Key required; value 100 with each branch netting to 0; 1299 net 0; 30 of freight
    capitalised into the destination adjunct and ledger; quantity moved; organisation clearing total 0; same-branch,
    branchless and unclassified-expense refusals; tenant isolation
  - **delivery cost run:** own truck 100 loaded + 100 empty km at 2 gives 400 with the customer, load and branch on
    the line; 6460 + 5385 = 0; Idempotency-Key required; overlapping run 409; tenant isolation; reversal; double
    reversal refused; authorization matrix (9 routes)
