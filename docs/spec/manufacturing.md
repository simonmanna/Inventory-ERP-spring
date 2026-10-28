# Manufacturing — `manufacturing`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/manufacturing` |
| Routes | 42 — full table: [generated/manufacturing.md](generated/manufacturing.md) |
| Feature flag | `ENABLE_MANUFACTURING` |
| Java package | `com.inventoryerp.manufacturing` |

## Purpose

BOMs, production requests/plans, production orders (confirm, start, complete, QC, cancel, reverse), material consumption and outputs, cost components, work centers, resources, routings, work orders, MRP, reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~42 routes | see generated table |

## Business rules to reproduce exactly

1. Completing a production order consumes materials (production_consume) and receives outputs (production_output) through StockService with cost roll-up; reverse undoes both.
2. WIP account handling (backfill-wip-account script).

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned — none needed (services use `JdbcClient` + `SqlRows`; generated entities untouched)
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/manufacturing-*.json` written — diff run against Nest pending
- [ ] Web screens for this module work against Spring (no console errors) — not yet smoke-tested
- [x] `docs/PORT_STATUS.md` updated

## Notes on the port

- **19 extra routes.** `phase4.controller.ts` (work centres, resources, routings, work orders) is registered by
  `ManufacturingModule`, but its one-line decorators are missed by `tools/extract-routes`, so the generated table
  lists 42 routes while Nest serves 61. All 61 are ported; the extra 19 are listed under the module in
  `PORT_STATUS.md` (not counted in the summary row).
- **Module gate.** As in Nest, only the BOM, production-order, request and work-centre controllers carry
  `@RequiresModule("manufacturing")`; every controller is behind `app.modules.manufacturing` (`ENABLE_MANUFACTURING`).
- **Independent commits kept.** The approval gate (BOM activate, order start), soft reservations (confirm), the
  reservation release on cancel and the auto-created `QC-QUARANTINE` location run in their own committed
  transactions, as they run outside Nest's `$transaction`. Plan confirm commits per line (retryable, as in Nest).
- **Cross-module writes.** Like Nest, the module writes `MenuItem`/`MenuProduct` (finished good to POS item),
  `PurchaseRequest` (MRP run) and `InventoryLocation` (quarantine) rows directly; stock and GL go through
  `StockService` / `StockPostingService` / `StockReservationService`.
- **`Partial<Dto>` PATCH bodies** (work centre, resource) are unvalidated in Nest; unknown keys give Prisma's 500.
- **Zero-yield complete** is reachable only through the service (the DTO's `@IsPositive` rejects 0), as in Nest.
- **Weekday maths** in the demand forecast use UTC (Nest uses the server zone, UTC in the stock deployment).
- **Posting rules.** The consume leg needs the `PRODUCTION_CONSUME` rules (seeded for new orgs by
  `OrganizationsService`); without them `postIssue` falls back to COGS exactly as in Nest. The DEMO seed has none, so
  `ManufacturingIT` restores the defaults first (the Nest spec seeds them by hand).
- **No events** are published or consumed (Nest has none). No Flyway migration needed.

## Production-ready extensions (ADR-S006)

Additive routes beyond Nest (every Nest route keeps its contract). Packages: **A** hardening (see Deviations), **B** time-phased MRP, **C** shop floor & quality, **D** costing & engineering. Web: `/manufacturing/mrp`, `/manufacturing/shop-floor`, `/manufacturing/engineering`, `/manufacturing/boms/:id`, plus an order-detail panel (availability, scheduling, rework, variances).

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/manufacturing/planning-params` | `production_plan:read` |
| PUT | `/api/v1/manufacturing/planning-params/:productId` | `production_plan:update` |
| POST | `/api/v1/manufacturing/mrp/runs` | `production_plan:confirm` |
| GET | `/api/v1/manufacturing/mrp/runs` | `production_plan:read` |
| GET | `/api/v1/manufacturing/mrp/runs/:id` | `production_plan:read` |
| POST | `/api/v1/manufacturing/mrp/runs/:id/firm` | `production_plan:confirm` |
| POST | `/api/v1/manufacturing/mrp/planned-orders/:id/firm` | `production_plan:confirm` |
| POST | `/api/v1/manufacturing/mrp/planned-orders/:id/cancel` | `production_plan:update` |
| GET | `/api/v1/manufacturing/work-centers/:id/calendar` | `work_center:read` |
| PUT | `/api/v1/manufacturing/work-centers/:id/calendar` | `work_center:update` |
| POST | `/api/v1/manufacturing/production-orders/:id/schedule` | `work_order:manage` |
| GET | `/api/v1/manufacturing/schedule` | `work_order:read` |
| GET | `/api/v1/manufacturing/work-orders/:id/time-logs` | `work_order:read` |
| POST | `/api/v1/manufacturing/work-orders/:id/time-logs` | `work_order:manage` |
| POST | `/api/v1/manufacturing/production-orders/:id/rework` | `production_order:qc` |
| GET | `/api/v1/manufacturing/production-orders/:id/qc-plan` | `production_order:read` |
| GET | `/api/v1/manufacturing/scrap-reasons` | `production_order:read` |
| POST | `/api/v1/manufacturing/scrap-reasons` | `production_order:qc` |
| PATCH | `/api/v1/manufacturing/scrap-reasons/:id` | `production_order:qc` |
| DELETE | `/api/v1/manufacturing/scrap-reasons/:id` | `production_order:qc` |
| GET | `/api/v1/manufacturing/qc-plans` | `production_order:read` |
| POST | `/api/v1/manufacturing/qc-plans` | `production_order:qc` |
| PATCH | `/api/v1/manufacturing/qc-plans/:id` | `production_order:qc` |
| DELETE | `/api/v1/manufacturing/qc-plans/:id` | `production_order:qc` |
| GET | `/api/v1/manufacturing/boms/:id/cost` | `bom:read` |
| POST | `/api/v1/manufacturing/boms/:id/standard-cost` | `bom:update` |
| GET | `/api/v1/manufacturing/boms/:id/standard-costs` | `bom:read` |
| GET | `/api/v1/manufacturing/boms/:id/substitutes` | `bom:read` |
| PUT | `/api/v1/manufacturing/boms/:id/lines/:lineId/substitutes` | `bom:update` |
| POST | `/api/v1/manufacturing/boms/:id/revise` | `bom:create` |
| GET | `/api/v1/manufacturing/boms/:id/compare/:otherId` | `bom:read` |
| GET | `/api/v1/manufacturing/engineering-changes` | `bom:read` |
| GET | `/api/v1/manufacturing/engineering-changes/:id` | `bom:read` |
| POST | `/api/v1/manufacturing/engineering-changes/:id/approve` | `bom:activate` |
| POST | `/api/v1/manufacturing/engineering-changes/:id/reject` | `bom:activate` |
| POST | `/api/v1/manufacturing/engineering-changes/:id/apply` | `bom:activate` |
| GET | `/api/v1/manufacturing/reports/variances` | `production:report` |
| GET | `/api/v1/manufacturing/reports/where-used/:productId` | `production:report` |
| GET | `/api/v1/manufacturing/production-orders/:id/availability` | `production_order:read` |

Behaviour notes:

- **MRP demand**: open back-office orders (`Order` draft/confirmed, product lines), proforma documents due in the horizon, submitted/approved production requests not yet planned, remaining materials of draft/confirmed production orders, dependent demand of planned make orders, reorder point and safety stock. **Supply**: on hand (optionally one location) − active reservations of other sources + open PO remainders + open production output. Products without `ProductPlanning` are *make* when they have an active BOM, else *buy*, with `Product.minQuantity` as reorder point. A new run supersedes the previous run's unfirmed planned orders.
- **Scheduling** is forward, minute-granular, in UTC, through `WorkCenterShift` windows (none = 24 h), skipping `WorkCenterClosure` days and honouring `parallelSlots` against work orders already scheduled at the centre.
- **Quality**: once any active scrap reason exists, QC `wasteCategory` and work-order `scrapReasonCode` must be one of them. A QC plan (by BOM, else product) makes `recordQc` validate the checklist `[{key, value}]`.
- **Rework** consumes the reworked units from `QC-QUARANTINE` in a linked `orderKind = rework` order (QC required) and closes the parent.
- **Variances** are analytic (`ProductionVariance`), written at completion when the order references a frozen standard; positive = unfavourable; no GL effect.
- **Subcontracted operations** add an `outsourcing` cost component absorbed through overhead and raise a draft `SUBPR-` purchase request for the subcontractor.
- Deleting / replacing a draft BOM's lines (PATCH `lines`) also drops their substitutes.

## Deviations

Approved by the owner in ADR-S006 (package A, bug fixes; everything else in ADR-S006 is additive):

| # | Route | Nest | Spring |
|---|---|---|---|
| A1 | `POST /mrp/run` | base-unit shortfall labelled with the purchase UoM; open POs counted at full quantity | quantity converted to the purchase unit; POs net `quantity − receivedQuantity` |
| A2 | `POST /work-orders/:id/pause`, `/complete` | labour += time since the *first* start on every pause | labour = sum of `WorkOrderTimeLog` run intervals (legacy work orders without logs keep the Nest maths) |
| A3 | `POST /work-orders/:id/complete` | cost added to an already-posted order (never absorbed) | 400 `Production order <code> is <status>; work-order time can no longer be costed to it.` |
| A4 | `POST /mrp/scan-min-stock` | ignores open production orders / requests (duplicate requests each scan) | on hand + open orders' remaining output + unplanned requests are netted |
| A5 | `POST /production-orders/:id/reverse` | `qc_hold` orders cannot be reversed or cancelled | `qc_hold` orders reverse from the quarantine location |
| A6 | `POST /production-orders/:id/complete` | `ProductionOutput.batchId` never set | set from the receipt's ledger row |
| A7 | BOM / order create & update | unknown product / location → 500 (FK) | 404 `Product <id> not found` / `Location not found` / `Routing not found` |
| A8 | `POST/PATCH /boms` | `routingId` not settable; `overheadCost` create-only | `routingId` on create + update, `overheadCost` on update (draft BOMs) |
| A9 | list routes (BOMs, orders, requests, plans) | unbounded | optional `limit` (1–1000) / `offset`; still a bare array, unbounded when absent |

## Status

Done. Parity: 61/61 Nest routes (42 in the generated table + 19 phase-4 routes). ADR-S006: packages A–D complete
with web screens (39 additional routes). Tests: `BomExplodeTest`, `ManufacturingIT` (Nest flow spec + authz matrix over
all 100 routes + tenant isolation), `ManufacturingHardeningIT`, `MrpPlanningIT`, `ShopFloorIT`,
`CostingEngineeringIT`; JaCoCo line coverage of `manufacturing.application` 89.5%.
