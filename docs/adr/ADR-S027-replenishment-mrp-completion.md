# ADR-S027 — Replenishment & MRP completion (routes, subcontracting, rough-cut capacity, nightly run)

Status: **accepted** (owner instruction "build it production ready", 2026-10-31; roadmap 2.3, PR-5). Implemented in
`V219`, `ReplenishmentService`, `TransferIncomingSupply` (inventory), `ProcurementReplenishmentService` /
`ProcurementReplenishmentSubscriber` (procurement), `ManufacturingReplenishmentService`, `ProductionIncomingSupply`,
`MrpPlanningService`, `MrpCapacityService`, `MrpSettingsService`, `MrpAutoRunJob` (manufacturing), and the web pages
`/inventory/replenishment` and `/manufacturing/mrp`.

## Context

Roadmap 2.3 asks for min/max and reorder rules that create purchase or production documents, an MRP run, capacity
planning, subcontracting, by-products and scrap. Most of it already existed:

| Piece | Where | State before PR-5 |
|---|---|---|
| Min / max rules per location, alerts, auto requisition | INV-3 (`/inventory/replenishment`) | buy only: draft purchase request through procurement |
| Reorder rules → purchase requests | S2P W7 (`/procurement/replenishment`) | done |
| Time-phased multi-level MRP, lot sizing, pegging, firming | ADR-S006 B (`/manufacturing/mrp`) | make / buy only |
| By-products / co-products in MRP and costing | ADR-S011, ADR-S019 | done (co-products count as receipts) |
| Scrap reasons, normal / abnormal loss | ADR-S006 C, ADR-S011 | done |
| Subcontracted routing operations (`SUBPR-` request, outsourcing cost) | ADR-S006 D | done per operation |
| Finite scheduling, load vs capacity of scheduled work orders | ADR-S006 B, ADR-S019 | done; MRP proposals not included |

The gaps: a replenishment rule cannot make or move stock, MRP cannot plan a product made entirely by a subcontractor,
MRP proposals are invisible to capacity, and nobody runs MRP unless a planner presses the button.

## Decision

1. **Replenishment routes.** `InventoryReplenishmentRule.route` is `buy` (default, unchanged behaviour), `manufacture`
   or `transfer` (+ `sourceLocationId`, required, different from the rule's location). The alert keeps the route and
   the answering document (`documentType` / `documentId` / `documentNumber`; buy also still fills `purchaseRequestId`
   / `requestNumber`).
   - **transfer**: inventory answers in the same transaction with a *pending, immediate* stock transfer per source and
     destination (`TRF-`). It still needs approval; nothing moves on a scan.
   - **buy / manufacture**: one `inventory.replenishment.requested` event, each line tagged `route` and `locationId`.
     Procurement takes buy lines (lines without a route are buys — events already in the outbox keep working) and now
     sets the request line's delivery location, demand source `reorder` and demand ref = alert id. Manufacturing takes
     manufacture lines and raises one **submitted** production request (`sourceType = replenishment`, the product's
     active BOM on each line, `ProductionRequestLine.deliveryLocationId` = rule location), so MRP and production plans
     see it as demand. Both handlers re-check that the alerts still wait (at-least-once delivery).
   - Inventory never calls manufacturing or procurement (both depend on inventory).
2. **Incoming supply.** The scan's `IncomingSupplyProvider`s gain transfers into the location (requested quantity until
   dispatch, then what is still in transit) and production (remaining output of open orders delivering there, and
   unplanned production request lines raised for it). Goods already on their way are not ordered twice.
3. **Subcontract procurement in MRP.** `ProductPlanning.procurement` and `MrpPlannedOrder.kind` accept `subcontract`.
   A subcontract product is netted and exploded exactly like *make* (its components are demand, they have to be sent
   to the subcontractor), with `supplierId` = the subcontractor. Firming creates the draft production order (components
   issued and output received through it, as for make) **plus** a draft `SUBPR-` purchase request to the subcontractor
   for the conversion, with **no product on the line**, so receiving it never adds stock; the planned order keeps
   `subcontractRequestId`. Run summaries count `subcontract` separately.
4. **Rough-cut capacity.** `GET /manufacturing/mrp/runs/:id/capacity?bucket=week|day` lays the run's open make and
   subcontract planned orders over `PlanningCapacityService.capacity` (calendar capacity, scheduled work orders). A
   planned order loads each in-house operation of its BOM's routing with `setupMins + durationMins × quantity ÷ BOM
   output quantity`, spread evenly over the days from release (today at the earliest) to the day before due (at least
   one day). Subcontracted operations load nobody. Per bucket: `plannedLoadHours`, `totalLoadHours`,
   `totalUtilisationPct`, `overloaded`; the bottleneck is re-ranked on total load. Advisory only: nothing is
   rescheduled.
5. **Nightly MRP.** `MrpSettings` (one row per organization; absent = off, 30 days, all locations),
   `GET|PUT /manufacturing/mrp/settings` (`production_plan:read` / `production_plan:update`). `MrpAutoRunJob` runs at
   02:30 UTC (ShedLock) for organizations with `autoRun`; like a manual run it supersedes the previous run's unfirmed
   planned orders. Nothing is firmed automatically.

## Consequences

- No Nest route, payload or status changes; all additions are new fields or routes. Existing alerts are backfilled
  with `documentType = purchase_request` where they have a purchase request.
- Durations: routing `durationMins` is treated per BOM batch (`outputQuantity`), like the BOM cost roll-up. Work-order
  generation still copies the routing minutes per order (Nest parity), so a scheduled order and its MRP proposal can
  differ for multi-batch quantities.
- Transfer quantities are taken as base units (the transfer screen does not convert).

## Not done (owner may ask later)

- RFQs straight from rules (a draft purchase request is converted to an RFQ on the procurement screen).
- Finite-capacity MRP (levelling proposals to capacity) and automatic rescheduling.
- Shipping components to a subcontractor location as a separate transfer (issue stays on the production order).
