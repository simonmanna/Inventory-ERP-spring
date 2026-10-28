# ADR-S006: Production-ready manufacturing — hardening, MRP, shop floor, costing & engineering

- Status: accepted (owner, 2026-10-28)
- Builds on: the parity port of `manufacturing` (PR #4), roadmap items 0.1 and 2.3, and partly 3.1
- Migration: `V9__manufacturing_production.sql` (version reserved for manufacturing)

## Context

The parity port reproduces the Nest module exactly: BOMs, production orders, a WIP-flat consume/output engine,
QC quarantine, requests/plans, an "MRP-lite" shortfall run, work centres/routings/work orders and reports. That
covers a bakery. A general discrete or process manufacturer also needs time-phased multi-level planning, real
shop-floor capacity and time capture, quality plans, rework, standard costing with variance analysis, alternative
components, subcontracted operations and controlled engineering changes. The port also surfaced Nest bugs that a
production system cannot ship with.

## Decision

Extend the module in four packages, each its own PR stacked on the parity branch. **Every Nest route keeps its
path, verb, status, permission and response shape**; new behaviour is new routes, new optional request fields and
new response fields. No new permission keys: the existing manufacturing permissions are reused (listed per route in
`docs/spec/manufacturing.md`). All new tables are org-scoped with RLS.

### A. Hardening (bug fixes; deviations from Nest, listed in the spec)

1. **MRP purchase quantity in purchase unit.** `POST /mrp/run` converted nothing but labelled the base-unit
   shortfall with the purchase UoM. The quantity is now converted base → purchase unit (rounded up at 6 dp), and open
   purchase orders net only their unreceived quantity (Nest counted received lines twice: on hand + full PO qty).
2. **Work-order labour from time logs.** Start/pause/complete open and close `WorkOrderTimeLog` rows; labour minutes
   are the sum of closed logs, so pause/resume no longer re-counts time since the first start.
3. **No cost after the order is closed.** Completing a work order of a completed/cancelled production order is
   refused (its overhead could never be absorbed; WIP reports drifted).
4. **Min-stock scan nets open supply.** `POST /mrp/scan-min-stock` subtracts open production orders' remaining
   output and open (unplanned) production requests, so repeated scans stop raising duplicate requests.
5. **QC-held orders can be reversed.** `reverse` accepts `qc_hold` orders (finished goods leave quarantine), so a
   rejected batch is no longer stuck.
6. **Genealogy batch link.** Output lines record the `InventoryBatch` they created (`batchId` was never set).
7. **Referential checks → 404/400, not 500.** Unknown products, locations, BOMs and routings in create bodies are
   rejected with a message instead of a foreign-key error. BOM version allocation is serialised per output.
8. **BOM `routingId` / `overheadCost` editable** on draft BOMs (create + update), so routing-driven work orders and
   overhead can be configured through the API.
9. **List paging.** Every list route accepts optional `limit` / `offset` (and keeps returning a bare array).

### B. MRP (time-phased, multi-level)

- `ProductPlanning` per product: make/buy, lead time, safety stock, reorder point, minimum order quantity, order
  multiple, preferred supplier and planning location. Defaults: products with an active BOM are *make*, others *buy*.
- `POST /manufacturing/mrp/runs` (horizon days, optional location) computes low-level codes from the active-BOM
  graph and nets level by level: gross requirement (open sales documents due in the horizon, approved production
  requests, open production orders' remaining materials, dependent demand of planned make orders, reorder point) +
  safety stock − (on hand − others' reservations + open PO remaining quantities + open production orders' remaining
  output). Shortfalls become planned orders sized by minimum quantity / multiple, due at the earliest demand date and
  released lead-time days earlier; make orders explode their BOM into dependent demand at the release date. Each
  planned order carries its pegging (which demands it covers).
- Results are stored (`MrpRun`, `MrpRunItem`, `MrpPlannedOrder`); a new run supersedes the previous run's unfirmed
  planned orders. `POST /mrp/planned-orders/{id}/firm` turns a make order into a draft production order and a buy
  order into a draft purchase request line (bulk firm per run groups buy orders by supplier into one request).
- The Nest `POST /mrp/run` (single-level shortfall → purchase request) is kept unchanged apart from fix A1.

### C. Shop floor & capacity

- Work-centre calendars: weekly shifts (`WorkCenterShift`), closures (`WorkCenterClosure`), parallel slots,
  efficiency % and hourly cost.
- Finite scheduling: `POST /production-orders/{id}/schedule` places the order's work orders forward from a start
  time through each centre's working windows, after the load already scheduled there (sequence order, slots honoured).
  `GET /manufacturing/schedule` returns the Gantt data; `GET /reports/capacity` keeps its Nest shape.
- Time capture: operator/resource time logs per work order (`GET`/`POST /work-orders/{id}/time-logs`).
- Scrap reasons master; work-order completion and QC failure accept a reason code (validated when the master
  holds codes).
- QC plans: checklist templates per product/BOM; `recordQc` validates the submitted checklist against the order's
  plan (required items, numeric ranges) when one exists.
- Rework: `POST /production-orders/{id}/rework` turns rework units of a `qc_hold` order into a linked rework order
  that consumes them from quarantine (plus optional extra materials) and produces the same product.

### D. Costing & engineering

- BOM cost roll-up (`GET /boms/{id}/cost`): multi-level material cost (sub-assemblies roll up through their active
  BOM, purchased items at their current average cost, else `costPrice`), routing labour/machine (operation minutes ×
  work-centre cost, ÷ efficiency), subcontracted operations and BOM overhead.
- Standard cost: `POST /boms/{id}/standard-cost` freezes the roll-up (`BomStandardCost`); production orders created
  afterwards reference it. On completion the order's analytic variances are written (`ProductionVariance`: material
  price, material usage, labour, overhead, subcontract, yield). Stock stays actual-costed (AVCO/FIFO), so variances
  have **no GL effect**; `GET /reports/variances` reports them.
- Where-used (`GET /reports/where-used/{productId}`, multi-level, active BOMs).
- Substitutes on BOM lines (`BomLineSubstitute`); `start` accepts a substitute product per material line (validated
  against the BOM, quantity scaled by the ratio); `GET /production-orders/{id}/availability` shows shortages and the
  substitutes that cover them.
- Subcontracted routing operations (subcontractor + cost per unit). Completing such a work order adds an
  `outsourcing` cost component, rolls the cost into the order's overhead (absorbed into WIP at completion like
  labour) and raises a draft purchase request line for the subcontractor service.
- Engineering changes: `POST /boms/{id}/revise` clones a BOM (lines + substitutes) into the next draft version under
  an `EngineeringChange`; ECOs are approved, then applied (activating the new version through the existing approval
  gate). `GET /boms/{id}/compare/{otherId}` diffs two versions.

## Consequences

- The web client keeps working unchanged; new screens are added under `web/src/pages/manufacturing`.
- MRP runs synchronously inside the request (bounded by the horizon and the BOM graph); very large catalogues may
  later need an async job (roadmap 3.3).
- Variances are analytic only; posting purchase-price / usage variances to the GL needs standard-cost inventory
  valuation, which is out of scope (roadmap 3.1).
- Subcontracting does not move material to the subcontractor's location (no consignment stock); it costs the
  operation and requests the service purchase.
