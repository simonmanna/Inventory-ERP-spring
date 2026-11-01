# ADR-S042 — Cost simulation (Cost Management CM-11)

- Status: accepted (CM-11 built 2026-11-02)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), item 8 of the owner requirements, AD-8
- Migration: `V235__cm11_cost_simulation.sql`
- Extension: no NestJS counterpart. Service in `costing`; routes under `/costing/simulation`.

## Decision

### 1. Scenarios

- `CostScenario(name, version, parameters)`. Creating a scenario with an existing name adds the next version; older
  versions are kept.
- Parameters are % changes, validated as above −100 and at most 1000:

  | Parameter | Effect |
  |---|---|
  | `materialPricePct` | cost of sales |
  | `fxPct` with `importedCostSharePct` (0–100) | the imported share of cost of sales |
  | `fuelPricePct` | distribution |
  | `energyTariffPct` | fixed overhead |
  | `capacityPct` | fixed overhead per unit of capacity (more shifts → overhead ÷ (1 + %)) |
  | `sellingPricePct` | revenue per unit |
  | `volumePct` | quantities; variable costs follow |

- Unknown parameters are refused.

### 2. Pure engine — `SimulationEngine` (AD-8)

- Input: baseline per segment (quantity, revenue, cost of sales incl. variances, distribution, overhead) and a
  `CostContext`.
- Cost of sales and distribution are variable; overhead is a fixed pool. The fixed pool (optionally plus the build's
  unallocated cost) is re-spread by simulated revenue with `AllocationCalculator` (largest remainder, exact sums).
- Output per segment: quantity, revenue, cost lines, CM1 / CM2 / CM3, unit cost, margin %, break-even quantity
  (fixed overhead share ÷ unit contribution).
- No database access; deterministic.

### 3. Service — `SimulationService`

- `POST /costing/simulation/scenarios/:id/simulate {profitabilityRunId, groupBy: product|customer,
  includeUnallocated}`.
- Reads the ACTUAL facts of a CM-9 build and the invoiced quantities of its period. It runs the engine and stores the
  result in `SimulationResult`.
- Each row shows the simulated figures beside the ACTUAL (quantity, revenue, CM3) and STANDARD (revenue, cost of
  sales) layers of the same build. The layers stay separate fields, never summed.

### 4. Isolation

- The only tables written are `CostScenario` and `SimulationResult`.
- An ArchUnit rule (`SimulationIsolationTest`) forbids `Simulation*` classes in costing from depending on
  `PostingService`, `StockService`, `StockPostingService`, `CostLineageService` or `LineageCapitalisation`.
- `SimulationIsolationIT` counts journal entries, journal lines, stock-ledger rows and profitability facts before and
  after a simulation (unchanged).

## Permissions

`cost_simulation:read`, `cost_simulation:manage`.

## Deferred

- Driver-level roll-ups through the BOM, absorption rates, joint-cost allocation, fleet TCO and quarry KPIs (the plan's
  full engine list). The first version simulates at the profitability-segment level, with the drivers above.
- Scenario comparison screens and charts (APIs only).

## Tests

- `SimulationEngineTest` (3): a neutral scenario reproduces the baseline; every driver (material, FX share, fuel,
  energy over capacity, price, volume) with an exact pool spread; determinism; edge cases (no quantity, no
  contribution).
- `SimulationIsolationTest` (ArchUnit).
- `SimulationIsolationIT` (1): versions, parameter validation, simulated vs actual, deterministic results, zero
  ledger writes, tenant isolation, authorization matrix (6).
