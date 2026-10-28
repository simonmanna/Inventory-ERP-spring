# ADR-S008: Standard costing with GL variances and process costing

- Status: Accepted (autonomous, 2026-10-28) — owner / client accountant to confirm defaults under "Open"
- Roadmap: part of 3.1 (advanced costing), brought forward for manufacturing clients; builds on ADR-S006 D and ADR-S007
- Migration: `V13__standard_and_process_costing.sql`

## Context

The ERP must serve manufacturing and production as well as trading. ADR-S006 package D already rolls BOM costs up,
freezes BOM standards and records production variances, but analytically only: stock stays at actual cost and the
variances never reach the GL. Continuous producers (cement, blocks, beverages) cost by process and period, not by
production order, and had no support. The ERP keeps these as separate, configurable dimensions:

| Dimension | Options | Where |
|---|---|---|
| Valuation formula (IAS 2) | FIFO, weighted average, specific identification | `InventoryCostPolicy` (ADR-S007) |
| Cost measurement technique | actual or **standard** | `ProductStandardCost` (this ADR) |
| Additional cost allocation | value, quantity, weight, volume, manual | landed cost (ADR-S007) |
| Operational cost tracking | batch / lot, job (production order), **process**, project | stock engine, manufacturing, this ADR |
| Management reporting | cost centres, branches, budgets | ADR-S005 / ADR-S007 |

## Decision

### 1. Standard costing (inventory owns the standard; manufacturing uses it)

- `ProductStandardCost`: per product, versioned, effective-dated, by element (material, labour, overhead,
  subcontract → total); draft → approved → active → superseded / cancelled; source `manual` or `bom_rollup`
  (`POST /manufacturing/boms/{id}/product-standard` freezes the roll-up and proposes it per output unit).
- A product is **standard-governed** only while it has an active standard; legacy `STANDARD` products without one
  post exactly as before (Nest parity).
- Activation (manual, or the daily ShedLock job): requires `costingMethod = STANDARD` or `convertMethod = true`
  (switches AVCO / FIFO); revalues on-hand stock from its current valuation to the standard (quants, lots,
  `costPrice`) with Dr / Cr stock valuation against **5960 Standard Cost Revaluation**; supersedes the previous
  version. Serial-tracked products are refused (they keep specific identification).
- **Receipts** of a governed product (`StockService.receive`, every source): stock and ledger at standard; the
  receipt's credit side (GRNI or the STOCK_IN rule) at actual; the difference to **5905 Purchase Price Variance**.
  Each variance is recorded in `StandardCostVariance`.
- **Production** of a governed output: received at standard (Dr stock / Cr WIP at standard); the WIP residual
  (actual pool − standard × produced) is split over the order's recorded variances (material price / usage, labour,
  overhead, subcontract — accounts 5910–5940) and the unexplained rest to **5950 Yield & Other Production Variance**,
  so WIP still nets to zero. Reversing the order reverses the variance entry.
- Issues of standard products were already at `costPrice` (= the active standard).
- Routes: `/inventory/standard-costs` (list, get, create, approve, activate, cancel, `/variances` report).

### 2. Process costing (manufacturing)

- `CostProcess` (sequence, method `weighted_average | fifo`, next process or final output product + location, cost
  centre), `ProcessCostPeriod` (one open period per process; units and completion %), `ProcessCostEntry` (inputs).
- Inputs: material issued from stock into the process (Dr WIP / Cr stock at the material's costing method),
  labour / overhead applied (Dr WIP / Cr the chosen applied-cost account, or recorded only when already in WIP), and
  transferred-in cost from the previous process.
- `ProcessCostCalculator` (pure, unit-tested): physical-unit balance check, equivalent units per element
  (transferred-in, material, conversion) for weighted average and FIFO, cost per EU, completed cost, closing WIP and
  abnormal loss; normal loss has no EU (absorbed); closing WIP takes the rounding so totals tie exactly.
- Close: abnormal loss Dr **5970 Abnormal Process Loss** / Cr WIP; completed cost → next process as transferred-in
  (sub-ledger move, same WIP account) or, for the last process, into stock of the output product (Dr stock / Cr
  WIP — at standard with a yield variance when the product is standard-governed); the next period opens with the
  closing WIP (units, %, cost by element).
- Routes: `/manufacturing/process-costing/...` (processes, periods, units, materials, costs, preview, close,
  cost-of-production report).

## Consequences

- Retail inventory method, activity-based costing and marginal / variable costing are **not** included; the ADR's
  dimension table leaves room for them (roadmap 3.1).
- Process periods cannot be reopened; corrections are made in the next period (adjust opening values or add costs).
- Process costing uses one WIP account; per-process WIP is the process sub-ledger (cost centre tags the applied
  costs and losses).
- A governed product's GRNI is relieved at actual by the vendor bill as before; PPV at receipt plus the existing
  bill-vs-receipt PPV (invoicing) together explain the full price difference.

## Open (client accountant to confirm)

1. Which products are standard-costed and how often standards are revised (default: manual approval, effective
   date, revaluation to 5960).
2. Variance disposition at period end (default: variances stay in P&L accounts 5905–5950; no proration to stock).
3. Process list, cost flow (weighted average default) and how labour / overhead are applied.
