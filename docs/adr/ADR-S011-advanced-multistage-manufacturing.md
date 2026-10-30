# ADR-S011: Advanced multi-stage manufacturing (Phase 2)

- Status: Accepted (autonomous, 2026-10-30) — owner / client production manager to review the defaults under "Open"
- Scope: all of Phase 2 of `docs/AUTONOMOUS_BUILD_CONSTRUCTION.md` (2.1–2.8) at design level; 2.1 is implemented
- Builds on: ADR-S006 (production-ready manufacturing), ADR-S007 (costing & cost centres), ADR-S008 (standard and
  process costing), ADR-S009 (fleet — equipment, trips, weighbridge, cost per hour)
- Migration (2.1): `V26__manufacturing_multistage.sql` (placeholder number, renumbered at merge)

## Context

The client quarries rock, crushes and screens it into sized aggregates and moulds concrete blocks / pavers / kerbs
that must cure before sale. ADR-S006 / S008 gave the ERP a discrete-manufacturing core (BOMs, routings, work orders,
single-leg production orders, MRP, scheduling, QC, standard and process costing). What is missing is the ability to
model **any number of stages** with real intermediate stock between them, joint outputs (co-products, by-products,
waste / fines), stage-level quality gates and WIP that can be reconciled stage by stage — and, on top of that, quarry
extraction, crushing runs, block recipes with curing, advanced planning, generic quality, shop-floor capture and
manufacturing analytics.

Constraints: the 61 Nest-parity manufacturing routes and every ADR-S006 / S008 route keep their exact contract; stock
moves only through `StockService` / `StockPostingService` / `StockReversalService`; every GL effect goes through the
posting engine (`PostingService`, mapping keys resolved at posting time via `AccountResolverService`); POS is frozen.

## Decision

### 1. Stages model (2.1 — built)

- A **routing operation is a stage**. Its stage behaviour lives in `RoutingStageSetup` (1:1, optional): issue mode
  (`backflush` | `manual`), intermediate (semi-finished) product + quantity per order unit, **stage location**,
  expected yield %, QC gate + checkpoint code, and the component products consumed at that stage (unassigned BOM
  components go to the first stage). No set-up = backflush, pass-through, 100 % yield, no gate.
- A production order **becomes multi-stage** when `ProductionOrderStage` rows exist
  (`POST /production-orders/{id}/stages/initialize`, or `POST /multistage-orders` = create + initialize + optional
  child orders). The order's `ProductionMaterial` rows get `operationId` = the consuming stage's routing operation;
  `ProductionOutput` gets `operationId` + the new `outputRole` (`main` | `co_product` | `by_product` | `waste`).
  Orders without stages behave exactly as before; the single-leg `start` / `complete` / `qc` / `reverse` refuse a
  multi-stage order (400 "… is a multi-stage order; use its stage routes."), `cancel` only while it has no open
  stage posting.
- Every stage transaction is a **`ProductionStagePosting`** (`issue` | `output` | `conversion` | `qc_release`) with
  lines (`ProductionStagePostingLine`: material, intermediate_in, transfer_in, conversion → into the stage WIP;
  main_output, co_product, by_product, waste, abnormal_scrap, transfer_out → out of it). All stock moves and journals
  of a posting carry `sourceType = production_stage`, `sourceId = posting id`. Stage, material, output and order
  totals and statuses are **recomputed from the non-reversed lines** after every posting / reversal (no drifting
  counters).
- **Intermediates are real stock**: a non-final stage receives its intermediate into its stage location (Dr stock /
  Cr WIP); the next stage consumes it from there (Dr WIP / Cr stock) — by backflush (planned share, all of this
  order's remaining balance on completion) or manual issue (never more than this order's released balance, never
  below zero). A stage without an intermediate is **pass-through**: its cost moves to the next stage in the WIP
  sub-ledger (`transfer_out` / `transfer_in`, no journal — same WIP account, like ADR-S008 process transfers).
- **Partial completion**: any number of output postings per stage; `complete: true` closes the stage (requires all
  earlier stages completed) and relieves its remaining WIP. The order is `in_progress` from the first posting,
  `completed` (or `qc_hold` while a gated final stage holds output) when the last stage completes; finished goods are
  received progressively.
- **Yield & scrap** (`StageYieldCalculator`, pure): a posting relieves in full what it backflushed itself plus
  `carried WIP × processed ÷ remaining planned gross units`; the completing posting relieves everything. Normal loss
  = processed × (1 − expected yield) is absorbed by good output; scrap above it is **abnormal** and written off
  (Dr stock adjustment expense / Cr WIP), as is everything when there is no good output.
- **Conversion cost**: `POST /production-stages/{id}/conversion-costs` (Dr WIP / Cr overhead absorbed or a given
  account). Work-order labour / machine / subcontract cost (ADR-S006) of the stage's operation and earlier ones is
  absorbed at the next output posting; the BOM overhead at the final stage's first output.
- Stock-moving routes require an `Idempotency-Key`; postings of one order are serialised with the same advisory lock
  as the single-leg legs plus a `FOR UPDATE` on the stage row; StockService locks the stock rows.

### 2. Multi-level orders and pegging (2.1 — built)

- `POST /production-orders/{id}/child-orders` explodes the order's materials: each component with an active BOM gets a
  child order (existing `createOrder`) for the required quantity — optionally net of stock at the parent's production
  location — delivering **into the parent's production location**, linked by `ProductionOrder.parentOrderId` and a
  `ProductionOrderPeg` row (parent material line, quantity, level). Recursive (default 5, max 10 levels; path-based
  cycle guard), idempotent per material line; a child whose routing has a stage set-up is staged automatically.
- `GET /production-orders/{id}/order-tree` rolls status and progress up (`OrderRollup`, pure): `awaiting_components`
  while children are open and the parent has not started, `ready` when all are done, stage share as own progress.
- Next (2.5): MRP planned make-orders firm into a parent + pegged children in one step; cancelling a parent offers
  to cancel unstarted children.

### 3. Co-products, by-products, waste and allocation (2.1 — built)

- `BomCoProduct` (per BOM, optionally per stage = routing operation; default the final stage): role, expected
  quantity per BOM output quantity, valuation `allocated` (shares the joint cost) | `nrv` (carved out at quantity ×
  NRV per unit, default the sales price) | `zero`, market value, weight per unit, fixed %, stocked flag (unstocked =
  recorded only, must be zero-valued), destination location. Copied by `POST /boms/{id}/revise`.
- **Allocation rule** (`MfgCostAllocationRule`): organization default + per-BOM override; method `quantity` (default),
  `weight` (quantity × weight per unit; main output's weight on the rule), `market_value` (quantity × unit market
  value, default sales price) or `fixed_pct` (main output takes 100 − Σ others).
- `JointCostAllocator` (pure, unit-tested): carve-outs first (NRV capped at what is left, zero, legacy `% of pool` of
  ADR-S006 by-product lines), the rest by the method; zero basis falls back to quantity, then to the main output;
  floor-and-largest-remainder rounding so the shares **sum exactly** to the joint cost (GL WIP nets to zero exactly).
- Waste / fines: `zero` valuation (received at zero cost — saleable quantity without cost), `nrv` (saleable fines) or
  unstocked (pure loss quantity).

### 4. Stage gates and the QC hook (2.1 — built; 2.6 plugs in)

- A gated stage receives its main output into the `QC-QUARANTINE` location and sets `qcStatus = pending`; **every
  later stage is blocked** (issue / output / conversion → 400 "Stage n · X is held at its QC checkpoint (status);
  stage m cannot proceed.") until the checkpoint passes. Pass transfers the quarantined quantity to the stage location
  (or finished goods for the final stage — an order held there is `qc_hold`), fail keeps it held (re-decidable).
- SPI in the exposed named interface `com.inventoryerp.manufacturing.spi`:
  - **`StageQcCheckpointHook.checkpointOpened(StageCheckpoint)`** — every bean is told, in the posting transaction,
    that a gated lot (order, stage, checkpoint code, product, quantity, quarantine location, posting, batch) waits.
  - **`StageCheckpointResults.recordResult(stageId, CheckpointDecision(passed, reference, notes))`** — records the
    decision (implemented by `StageExecutionService`); `POST /production-stages/{id}/qc-decision` is the manual path.
  - 2.6 implements the hook to open an in-process inspection lot from the stage's checkpoint code and calls
    `recordResult` when the lot is decided (reference = lot number).

### 5. WIP valuation and GL (2.1 — built)

| Event | Entry (posting engine, `sourceType = production_stage`) |
|---|---|
| material / intermediate into a stage | Dr WIP / Cr stock (`PRODUCTION_CONSUME` rule, fallback mapping `wip`) |
| conversion applied / work-order cost / BOM overhead absorbed | Dr WIP / Cr overhead absorbed (or given account) |
| stocked output (intermediate, main, co-, by-product, waste) | Dr stock / Cr WIP (`PRODUCTION_OUTPUT` rule) |
| standard-governed output (ADR-S008) | stock at standard; difference Dr / Cr 5950 yield variance vs WIP |
| abnormal scrap, unstocked valued output | Dr stock adjustment expense / Cr WIP |
| pass-through to next stage | none (WIP sub-ledger move) |
| reversal | `StockReversalService.reverseLedgerSource` (stock back at original cost, journals mirrored) or `PostingService.reverse`; last in, first out per order |

- Accounts are resolved through mapping keys at posting time (`AccountResolverService`), so remapping applies from
  that date on (effective-dated); WIP account = mapping `wip`.
- `GET /reports/stage-wip`: WIP per stage and per order, expected GL WIP = Σ stage WIP + material cost of single-leg
  orders in progress + open process-costing periods, vs the GL WIP balance (drift, balanced flag); intermediate
  stock in stage locations is listed separately (it is inventory). The legacy `GET /reports/wip-reconciliation`
  counts a multi-stage order at its stage WIP.

### 6. Quarry & extraction (2.2 — design; recommendation)

**Recommendation: a separate Modulith module `quarry`** (package `com.inventoryerp.quarry`, flag `ENABLE_QUARRY`,
licence feature `quarry`), not more tables inside `manufacturing`. Reasons: its master data (sites, zones / benches /
faces, geology & grade, extraction plans, drilling & blasting with controlled explosives, royalties / levies) and its
users (quarry manager, blaster, surveyor) are distinct from the plant; it depends on `fleet` (equipment, internal
trips, weighbridge) which manufacturing should not; and it keeps the already-large manufacturing module focused. The
seam to manufacturing is narrow: quarry receives raw stone into a **ROM stockpile location** as inventory with cost
(`StockService.receive` + its own posting: Dr stock / Cr extraction cost pool or the costs it accumulated), and the
crushing run (2.3) consumes it like any other material. The quarry agent decides finally after reading this ADR.

### 7. Crushing, screening & aggregates (2.3 — design)

- A crusher line = work centres (primary jaw, secondary cone, VSI, screens) with capacity t/h and meters; a
  **crushing run** = a multi-stage production order on the 2.1 engine (per shift), input ROM tonnes, outputs as
  `co_product` / `by_product` / `waste` lines into **sized stockpile locations**, oversize recirculated = an output
  routed back as input of the next run. Expected grading split = the BOM co-product quantities (per plant & material:
  one BOM per plant × material); actual from weighbridge / belt scale / survey → yield variance via the stage yield.
- Cost per tonne by size = allocation by quantity (tonnes) or market value; diesel / kWh / wear parts / labour via
  conversion postings. Continuous plants that cost by period use ADR-S008 process costing instead; both share the
  same allocation calculator for co-products. Stockpile surveys (volume × density) are inventory adjustments with
  approval (inventory module), not manufacturing.

### 8. Concrete blocks / precast (2.4 — design)

- Product spec attributes (block type, dimensions, strength class, weight, pallet qty) on the product; **recipes /
  mix designs** = versioned BOMs per mixer cycle (ECO flow of ADR-S006) with batch scaling from the existing
  explosion; material availability check = `GET /production-orders/{id}/availability`.
- Stages: mix → mould → **curing** (stage location = curing yard, intermediate "green blocks", QC gate on the curing
  stage released only after the minimum curing days — the 2.4 agent adds a `minCuringDays` hold to the stage set-up
  and an earliest-release check before `recordResult`) → strength QC (7 / 28-day results via 2.6 inspection lots) →
  release; rejects / second-grade = `by_product` (NRV) or `waste`; breakages by scrap reason; cost per acceptable
  block = main-output unit cost. Pallet labels reuse the barcode / QR services with the output batch.

### 9. Advanced planning & scheduling (2.5 — design)

- Demand = confirmed sales orders + fleet delivery schedule + forecast + stock policy, pegged to the order ("make for
  contractor X, site Y, date Z") in `MrpPlannedOrder.pegging`; MRP extended for multi-level (2.1 pegs), co-products
  (co-product supply nets demand), stage yields (gross-up) and curing lead time (stage duration).
- Capacity: load vs capacity per work centre / line (hours and t/h), bottleneck view, backward scheduling from the
  due date added to the forward scheduler, Gantt with drag-to-reschedule honouring calendars, shift plans; shortfall,
  production-vs-plan and plan-adherence reports.

### 10. Quality management (2.6 — design)

- Generic module `quality` (recommended, flag `ENABLE_QUALITY`): specifications per product / grade (parameters with
  min / max / target and test method), inspection plans and lots (incoming on GRN, in-process per stage through the
  §4 hook, final before release), CoA PDF, hold / release / reject / downgrade with permissions and audit, NCR /
  CAPA, lab result import SPI (CSV + REST), forward / backward batch traceability across quarry → crushing → blocks →
  delivery using ledger codes, batch ids and the stage posting lines. Existing ADR-S006 QC plans / scrap reasons stay
  as they are (single-leg orders).

### 11. Shop floor & `MachineDataAdapter` (2.7 — design)

- Tablet screens over the 2.1 routes (start / stop stage = work-order time logs, record output / scrap / downtime with
  reason codes, meter readings, issues) plus supervisor approval of shift reports.
- **`MachineDataAdapter` SPI** (in `manufacturing.spi`): `String code()`, `List<MachineEvent> poll(since)` /
  push endpoint `POST /manufacturing/machine-data/{adapter}` (HMAC-signed), events = counts, weights, downtime
  start / stop per work centre; a simulator adapter for demos; generic MQTT / HTTP adapters. Events become draft
  stage outputs / downtime logs that an operator confirms — never auto-posted GL.
- OEE per line = availability × performance × quality from time logs, capacity and stage scrap.

### 12. Manufacturing costing & analytics (2.8 — design)

- Actual cost per batch / order / shift / plant from the stage posting lines (materials, labour, energy, equipment
  from fleet cost per hour, overhead absorption rates per work centre) → WIP → FG through the posting engine; variances
  (material price / usage, yield, labour efficiency, overhead) per order extending ADR-S008; reports: cost per tonne
  by size, cost per block, yield % expected vs actual, material loss, volume vs target by shift / plant / product.

### 13. Permissions and feature flag

- No new permission keys for 2.1 (as in ADR-S006): routing stage set-up `routing:read` / `routing:create`; BOM
  co-products and allocation rules `bom:read` / `bom:update`; create / initialize / child orders
  `production_order:create`; stage views and order tree `production_order:read`; issue `production_order:start`;
  output and conversion `production_order:complete`; QC decision `production_order:qc`; reversals
  `production_order:cancel`; stage WIP report `production:report`. New modules (quarry, quality) get their own keys.
- Feature flag: the existing `manufacturing` module flag (`ENABLE_MANUFACTURING`, `@RequiresModule("manufacturing")`)
  covers 2.1, 2.3, 2.4, 2.5, 2.7 and 2.8; `quarry` and `quality` get their own flags if built as modules.

## Consequences

- Any number of stages, intermediates and joint outputs per order with WIP visible and reconcilable per stage; the
  single-leg engine and all its routes are untouched for orders without stages.
- Intermediates are valued in stock valuation while they wait in a stage location (not in GL WIP); the stage WIP
  report lists them separately.
- Stage quantities are in each product's base unit; joint-output quantities of one stage are assumed to be in one
  physical measure (tonnes, units) for yield and planned-share maths.
- Manual-issue stages relieve carried WIP pro rata to planned gross units, so a partial output before all materials
  are issued is valued on the materials issued so far; the completing posting trues it up.

## Open (owner / client to confirm)

1. Default allocation method `quantity` (alternatives: market value for aggregates; weight).
2. By-products default to NRV = sales price unless an NRV per unit is set; waste defaults to zero value.
3. Manual issues are strict (never negative stock); backflush follows `inventory.allowNegativeStock`.
4. A stage gate blocks all later stages while any lot of the gated stage is pending (strict; per-lot release could
   be added later).
