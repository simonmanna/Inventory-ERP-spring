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
- **Generic quality (ADR-S013, module `quality`, `docs/spec/quality.md`)**: versioned specifications, inspection lots with per-sample results (sieve grading), hold / quarantine / release / reject / downgrade, NCR / CAPA, CoA and traceability. The checklist QC above is unchanged; both share the `QC-QUARANTINE` location. Stage gates (2.1) call `QualityGateService.gate`.
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

## ADR-S008 extensions — standard costing in production, process costing

- Completing an order whose output product has an **active product standard** receives the output at standard and
  posts the WIP residual as variances (`ProductionOrder.varianceJournalEntryId`); reversal reverses it.
- `POST /manufacturing/boms/{id}/product-standard` (`standard_cost:manage`): BOM roll-up → draft product standard.
- Process costing (`process_costing:read` / `manage` / `close`):

| Method | Path |
|---|---|
| GET, POST | `/manufacturing/process-costing/processes` |
| GET, PATCH | `/manufacturing/process-costing/processes/{id}` |
| POST | `/manufacturing/process-costing/processes/{id}/periods` |
| GET | `/manufacturing/process-costing/periods/{id}`, `/{id}/preview` |
| PATCH | `/manufacturing/process-costing/periods/{id}/units` |
| POST | `/manufacturing/process-costing/periods/{id}/materials`, `/costs`, `/close` (Idempotency-Key) |
| GET | `/manufacturing/process-costing/reports/cost-of-production?from&to` |

Tests: `ProcessCostCalculatorTest`, `StandardProcessCostingIT`. Web: Manufacturing → Standard Costs, Process Costing.

## ADR-S011 — 2.1 multi-stage production & WIP between stages

Design: [ADR-S011](../adr/ADR-S011-advanced-multistage-manufacturing.md) (covers all of Phase 2). Migration
`V26__manufacturing_multistage.sql` (placeholder). Additive: orders without stages keep every Nest / ADR-S006 / S008
behaviour; the single-leg `start`, `complete`, `qc` and `reverse` answer 400 `Order <code> is a multi-stage order; use
its stage routes.` for a staged order, `cancel` only while it has no open stage posting. `ProductionOutput` gains
`outputRole`; `GET /reports/wip-reconciliation` counts a staged order at its stage WIP.

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/manufacturing/routings/:id/stage-setup` | `routing:read` |
| PUT | `/api/v1/manufacturing/routings/:id/stage-setup` | `routing:create` |
| GET | `/api/v1/manufacturing/boms/:id/stage-setup` | `bom:read` |
| PUT | `/api/v1/manufacturing/boms/:id/stage-setup` | `bom:update` |
| GET | `/api/v1/manufacturing/cost-allocation-rules` | `bom:read` |
| PUT | `/api/v1/manufacturing/cost-allocation-rules/default` | `bom:update` |
| POST | `/api/v1/manufacturing/multistage-orders` | `production_order:create` |
| POST | `/api/v1/manufacturing/production-orders/:id/stages/initialize` | `production_order:create` |
| GET | `/api/v1/manufacturing/production-orders/:id/stages` | `production_order:read` |
| POST | `/api/v1/manufacturing/production-orders/:id/stages/reverse` (Idempotency-Key) | `production_order:cancel` |
| POST | `/api/v1/manufacturing/production-orders/:id/child-orders` | `production_order:create` |
| GET | `/api/v1/manufacturing/production-orders/:id/order-tree` | `production_order:read` |
| POST | `/api/v1/manufacturing/production-stages/:id/issue` (Idempotency-Key) | `production_order:start` |
| POST | `/api/v1/manufacturing/production-stages/:id/output` (Idempotency-Key) | `production_order:complete` |
| POST | `/api/v1/manufacturing/production-stages/:id/conversion-costs` (Idempotency-Key) | `production_order:complete` |
| POST | `/api/v1/manufacturing/production-stages/:id/qc-decision` (Idempotency-Key) | `production_order:qc` |
| POST | `/api/v1/manufacturing/stage-postings/:id/reverse` (Idempotency-Key) | `production_order:cancel` |
| GET | `/api/v1/manufacturing/reports/stage-wip` | `production:report` |

Behaviour notes:

- **Stages** = routing operations with an optional `RoutingStageSetup` (issue mode backflush / manual, intermediate
  product + quantity per order unit, stage location, expected yield %, QC gate + checkpoint code, materials consumed).
  Unassigned BOM components are consumed at the first stage; the last stage produces the order output.
- **Postings** (`ProductionStagePosting` + lines) carry `sourceType = production_stage`, `sourceId = posting id` on
  every stock move and journal; totals are recomputed from the non-reversed lines. Output postings may be partial;
  `complete: true` closes the stage (earlier stages must be complete) and relieves all its WIP.
- **Backflush** issues `qtyPlanned × processed ÷ planned gross` per posting (the rest at completion); manual issues and
  intermediates are strict (never below zero). A posting relieves its own backflushed value plus the carried WIP pro
  rata to planned gross units; normal loss (1 − expected yield) is absorbed, abnormal scrap written off.
- **Intermediates** land in the stage location (or `QC-QUARANTINE` when gated) as stock; pass-through stages move cost
  in the WIP sub-ledger. **Co-/by-products / waste** from `BomCoProduct` (per stage) valued by `JointCostAllocator`
  (NRV / zero / legacy % carve-outs, then quantity / weight / market value / fixed %; sums exact).
- **QC gate**: later stages blocked until `qc-decision` passed (or `StageCheckpointResults.recordResult` from the
  quality module); `StageQcCheckpointHook` beans are told when a gated lot waits.
- **Multi-level**: `child-orders` creates pegged child orders (recursive, net of stock optional) delivering into the
  parent's production location; `order-tree` rolls status / progress up.
- **Reversal**: last in, first out per order; `stages/reverse` reverses all and cancels the order.

Web: order detail → Stages panel (timeline, per-stage Issue / Output / Cost / Pass-Fail QC, postings with reversal,
child-order tree), BOM detail → stage set-up + co-products + allocation rule, Manufacturing → Stage WIP
(`/manufacturing/stage-wip`: WIP per stage vs GL, intermediate stock, default allocation rule).

Tests: `JointCostAllocatorTest`, `StageYieldCalculatorTest`, `OrderRollupTest`, `MultiStageProductionIT` (3-stage
order with intermediate + co-products + GL, partial completion, LIFO reversal, stage gate + idempotent replay,
multi-level pegging, concurrency on stage issue, allocation settings, tenant isolation, authz matrix of the 18 routes).

Status (2.1): done. Gaps: per-lot gate release, curing-day hold (2.4), MRP awareness of stages / co-products (2.5),
standard-governed outputs of a staged order post their difference to 5950 without per-element variances (2.8).

## ADR-S014 — 2.4 Concrete block / precast manufacturing

Design: [ADR-S014](../adr/ADR-S014-precast.md). Migration `V29__manufacturing_blocks_precast.sql` (placeholder).
Additive: no existing route changes; the 2.1 engine gains a minimum-curing-days hold on QC gates
(`RoutingStageSetup.minCuringDays`, `ProductionOrderStage.minCuringDays` / `qcPassDueAt`, `qcStatus = curing`) and an
in-module `StageGateListener`. No new permission keys; `ManufacturingIT` matrix now 157 routes.

| Method | Path (`/api/v1/manufacturing/precast`) | Permission |
|---|---|---|
| GET | `/product-specs` (`search, page, pageSize`) | `bom:read` |
| PUT | `/product-specs/:productId` | `bom:update` |
| GET | `/curing-areas` (`active`) | `bom:read` |
| POST | `/curing-areas` | `bom:update` |
| PATCH | `/curing-areas/:id` | `bom:update` |
| GET | `/mix-designs` (`search, productId, status, page, pageSize`) | `bom:read` |
| GET | `/mix-designs/:id` | `bom:read` |
| POST | `/mix-designs` | `bom:create` |
| PATCH | `/mix-designs/:id` (draft only) | `bom:update` |
| POST | `/mix-designs/:id/activate` | `bom:activate` |
| POST | `/mix-designs/:id/revise` | `bom:create` |
| GET | `/mix-designs/:id/scale` (`basis = units / batches / cement_bags / volume_m3 / cycles / pallets, value, wholeBatches`) | `bom:read` |
| GET | `/orders` (`status, productId, search, page, pageSize`) | `production_order:read` |
| POST | `/orders` (mix design + basis + value → staged, confirmed order) | `production_order:create` |
| GET | `/orders/:id` (stages, curing batches, costing, variance) | `production_order:read` |
| POST | `/orders/:id/pressing-runs` (**Idempotency-Key**) | `production_order:complete` |
| GET | `/curing-batches` (`status, curingAreaId, orderId, ready, search, page, pageSize`) | `production_order:read` |
| GET | `/curing-batches/:id` | `production_order:read` |
| POST | `/curing-batches/:id/readings` | `production_order:complete` |
| POST | `/curing-batches/:id/move` | `production_order:complete` |
| POST | `/curing-batches/:id/cube` (**Idempotency-Key**) | `production_order:complete` |
| POST | `/curing-batches/:id/gate-decision` (**Idempotency-Key**) | `production_order:qc` |
| POST | `/curing-batches/:id/final-inspection` | `production_order:qc` |
| GET | `/curing-batches/:id/labels` | `production_order:read` |
| GET | `/reports/cost-per-block` (`from, to, productId`) | `production:report` |
| GET | `/reports/breakage` (`from, to, productId`) | `production:report` |

Data model (V29): `PrecastProductSpec` (product 1:1 — block type, L × W × H mm, strength class / MPa, weight,
units per pallet / per machine cycle, second-grade product), `PrecastCuringArea` (code, chamber / yard / shed,
capacity in pallets, optional inventory location), `PrecastMixDesign` (BOM 1:1 — cement product + units per bag,
batch m³, w/c ratio, minimum curing days, checkpoint code, default area, maturity target / datum),
`PrecastCuringBatch` (`<order>-C01`; order, curing stage, pressing / cubing postings, batch number, quantity, broken at
press, cycles, pallets, area, bay, shift, started / curing until, status `curing → cubed → released | failed`,
`cancelled` when the pressing posting is reversed, maturity, good / second grade / broken, final lot),
`PrecastCuringReading` (temperature readings and pallet moves). Every table has `organizationId` + RLS.

Flow: mix design (draft → activate, BOM approvals apply) → `POST /orders` scales it (`BatchScaler`) and creates the
staged order (pressing stage pass-through, curing stage gated with the mix design's checkpoint and minimum days) →
pressing runs (stage output: backflush of the batch materials, breakage as scrap; one curing batch each; the last run
completes pressing) → readings / moves → cubing after the minimum days (good → QC quarantine, second grade → NRV
by-product, broken → scrap) → strength gate: quality lot release (bridge) or manual decision; a pass before curing
ends waits as `curing` until `PrecastCuringJob` passes it → released blocks in finished goods → optional final lot
(`final` plan, e.g. 28-day) → reports. Order status, WIP and GL are the 2.1 engine's.

Tests: `BatchScalerTest`, `MaturityCalculatorTest` (incl. `CuringHold`), `PrecastManufacturingIT` (end to end with
quality lot, GL balanced, WIP back to zero, cost per acceptable block, variance, labels, reports; curing hold +
deferred gate + job; mix design versions and validation; idempotent replay, key required, contended cubing; tenant
isolation; authorization matrix of the 26 routes).

Web: Manufacturing → Precast: curing board (`/manufacturing/precast/curing`, phone layout: readings, moves, cube,
gate, labels), precast orders (`/manufacturing/precast/orders`, pressing-run form, costing), mix designs with the
scaling calculator (`/manufacturing/precast/mix-designs`), set-up (specs, curing areas), reports.

Status (2.4): done. Gaps: per-batch (rather than per-stage) strength gate; maturity-based early release; seed data
(Phase 7); reversing only a gate release leaves the batch `released`.
## ADR-S015 — 2.7 shop-floor execution & data capture

Design: [ADR-S015](../adr/ADR-S015-shop-floor.md) (final form of ADR-S011 §11). Migration
`V30__manufacturing_shop_floor.sql` (placeholder). Additive: new tables `ShopFloorShift`, `ShopFloorDowntimeReason`,
`ShopFloorWorkCenterProfile`, `ShopFloorShiftReport`, `ShopFloorClockEntry`, `ShopFloorDowntime`,
`ShopFloorProductionReport`, `ShopFloorMeterReading`, `ShopFloorMachineEvent` (RLS, `organizationId` on every row). No
existing route or table changes; the terminal calls `WorkOrderService` and `StageExecutionService`.

All routes under `/api/v1/manufacturing/shop-floor`, flag `ENABLE_MANUFACTURING`:

| Method | Path | Permission |
|---|---|---|
| GET | `/dashboard` (live board) | `work_order:read` |
| GET | `/work-centers/:id/queue` (terminal) | `work_order:read` |
| POST | `/work-orders/:id/start` (start / resume; ends the pause stop) | `work_order:manage` |
| POST | `/work-orders/:id/pause` (`reasonCode` → opens downtime) | `work_order:manage` |
| POST | `/work-orders/:id/complete` (Idempotency-Key; labour costed) | `work_order:manage` |
| GET / POST | `/clock-entries` (clock on; closes the person's open entry) | `work_order:read` / `work_order:manage` |
| POST | `/clock-entries/:id/clock-off` | `work_order:manage` |
| GET | `/production-reports` | `work_order:read` |
| POST | `/production-reports` (Idempotency-Key; staged order → stage output posting) | `production_order:complete` |
| POST | `/production-reports/:id/confirm` (Idempotency-Key; machine draft) | `production_order:complete` |
| POST | `/production-reports/:id/reject` | `production_order:complete` |
| GET / POST | `/downtime` (log open or closed stop) | `work_order:read` / `work_order:manage` |
| POST | `/downtime/:id/end`, `/downtime/:id/confirm`, `/downtime/:id/reject` | `work_order:manage` |
| GET / POST | `/meter-readings` (monotonic unless `isReset`) | `work_order:read` / `work_order:manage` |
| GET | `/shift-reports`, `/shift-reports/:id` | `work_order:read` |
| POST | `/shift-reports` (open for work centre + shift + date) | `work_order:manage` |
| POST | `/shift-reports/:id/submit` | `work_order:manage` |
| POST | `/shift-reports/:id/approve`, `/shift-reports/:id/reject` (approver ≠ submitter) | `shop_floor:approve` |
| GET | `/oee?from&to&workCenterId` (≤ 93 days) | `production:report` |
| GET | `/machine-adapters` | `work_center:read` |
| GET | `/machine-events` | `work_order:read` |
| POST | `/machine-data/poll` (`simulator` / `rest`), `/machine-data/push`, `/machine-data/csv` | `shop_floor:machine_data` |
| GET / POST | `/shifts`; PATCH `/shifts/:id`; POST `/shifts/defaults` | `work_order:read` / `work_center:update` |
| GET / POST | `/downtime-reasons`; PATCH `/downtime-reasons/:id`; POST `/downtime-reasons/defaults` | `work_order:read` / `work_center:update` |
| GET / PUT | `/work-center-profiles`, `/work-center-profiles/:workCenterId` | `work_center:read` / `work_center:update` |

Behaviour notes:

- **Shift report** per work centre × shift × date (org time zone), created on first record; records are refused once
  it is submitted ("… is submitted; reject it to reopen before changing its records.") or approved ("… is approved and
  locked."). Submission needs no pending machine draft; approval by another user than the submitter.
- **Production report**: multi-stage work order (its routing operation = a stage) → `StageExecutionService.output`
  (`completesStage`, co-product `outputs` passed through; posting id kept); single-leg → `WorkOrder.qtyGood/qtyScrap`
  incremented, scrap reason validated against the ADR-S006 scrap reasons. Stock / GL only in the stage engine.
- **Machine data**: events unique per `(adapter, externalId)`; count → draft production report (single running work
  order, else unassigned), `downtime` / `downtime_start` → draft machine stop (one open per work centre),
  `downtime_stop` closes it; unknown machine / locked shift → event `error`. Confirming a count posts like an operator
  report (row lock, idempotent).
- **OEE**: `OeeCalculator` + `ShiftClock` (see ADR-S015 §10); snapshot stored on submit / approve; live for open
  reports (elapsed part of the window).

Web (tablet-first, large touch targets): Manufacturing → **Operator Terminal** (`/manufacturing/terminal`: pick work
centre, clock on/off, start / stop with reason grid / resume / finish, report output + scrap, issue material on manual
stages, log downtime, meter reading, confirm / reject machine drafts), **Shop-floor Live** (`/manufacturing/floor-board`,
30 s refresh), **Shift Reports** (`/manufacturing/shift-reports`, list with filters + pagination, detail with OEE,
output, downtime, crew, meters, submit / approve / reject), **OEE** (`/manufacturing/oee`), **Shop-floor Setup**
(`/manufacturing/floor-setup`: shifts, reasons, work-centre profiles, machine data poll / CSV / events).

Tests: `OeeCalculatorTest`, `ShiftClockTest`, `MachineEventParserTest`, `MachineAdaptersTest` (simulator, REST against
a local HTTP server), `ShopFloorExecutionIT` (single-leg terminal flow with downtime, clock, idempotent output replay,
meters, shift report four-eyes approval + locking, OEE; multi-stage output through the stage engine with GL balanced;
simulator / push / CSV drafts, concurrent confirmation, rejection; set-up rules; tenant isolation; authz matrix of the
41 routes); `ManufacturingIT` matrix now 172 routes.

Status (2.7): done. Gaps / next: MQTT client not embedded (bridge to the push route); scheduled polling of the REST
adapter (manual poll / gateway push for now); crew ↔ HR attendance linkage (Phase 4); OEE excludes shifts without any
record; 2.8 can read run / down / output per shift from these tables.
## ADR-S016 — 2.3 Crushing, screening & aggregate processing

Design: [ADR-S016](../adr/ADR-S016-crushing.md). Migration `V31__manufacturing_crushing.sql` (placeholder; 10 tables +
6 permissions granted to Administrator). Additive: no change to the 2.1 engine or any existing route. A posted crushing
run **is** a multi-stage production order of the grading profile's system-managed BOM (feed issued from the feed
stockpile, conversion per stage, sized fractions into their stockpiles at joint cost).

Routes (36, all under `/api/v1/manufacturing/crushing`; **I** = `Idempotency-Key` required, *i* = accepted):

| Method | Path | Permission |
|---|---|---|
| GET | `/lookups`, `/lookups/products?search` | `crushing:read` |
| GET | `/plants?includeInactive`, `/plants/:id` | `crushing:read` |
| POST, PUT, DELETE | `/plants`, `/plants/:id` (whole form incl. circuit stages + units) | `crushing:setup` |
| GET | `/grading-profiles?plantId&status`, `/grading-profiles/:id` | `crushing:read` |
| POST, PUT, DELETE | `/grading-profiles`, `/grading-profiles/:id` (drafts) | `crushing:setup` |
| POST | `/grading-profiles/:id/activate`, `/new-version`, `/archive` | `crushing:setup` |
| GET | `/runs?plantId&status&shift&from&to&search&page&pageSize`, `/runs/:id`, `/runs/:id/preview` | `crushing:read` |
| POST *i*, PUT, DELETE | `/runs`, `/runs/:id` (drafts) | `crushing_run:record` |
| POST **I** | `/runs/:id/post`, `/runs/:id/reverse` (`{reason}`) | `crushing_run:post` |
| GET | `/stockpiles?kind`, `/stockpile-surveys?stockpileId&status&page&pageSize`, `/stockpile-surveys/:id` | `crushing:read` |
| POST *i*, PUT, DELETE | `/stockpile-surveys`, `/stockpile-surveys/:id` (drafts) | `crushing_survey:record` |
| POST | `/stockpile-surveys/:id/submit` | `crushing_survey:record` |
| POST **I** / POST / POST **I** | `/stockpile-surveys/:id/approve`, `/reject` (`{reason}`), `/reverse` (`{reason}`) | `crushing_survey:approve` |
| GET | `/reports/yield`, `/reports/cost-per-tonne`, `/reports/plant-hours` (`from&to&plantId`, default month to date) | `production:report` |

Data model: `CrushingPlant` (site, default feed stockpile, design t/h, diesel / kWh / labour rates, routing + circuit
version), `CrushingPlantStage` (sequence → routing operation), `CrushingPlantUnit` (kind, work centre, t/h, kW, meter
kind), `CrushingGradingProfile` (plant × feed, version, draft → active → archived, allocation method, expected recovery,
BOM) + `CrushingGradingLine` (product, size label, role, stage, expected %, stocked, stockpile, valuation / NRV),
`CrushingRun` (date, shift, operator, feed stockpile + tonnes + method, output method, recirculated t, hours, downtime,
crew hours, draft → posted → reversed, production order, posted snapshot of yield / loss / cost) + `CrushingRunOutput`
(expected vs actual t / %, value, unit cost) + `CrushingRunMeter` (engine hours / kWh start-end, diesel) +
`CrushingRunCost` (manual + derived lines, stage posting), `CrushingStockpileSurvey` (volume × density, book snapshot,
variance, draft → submitted → posted / rejected → reversed). All org-scoped with the `tenant_isolation` RLS policy.

Business rules: a profile has exactly one main fraction (stocked, last stage, allocated), distinct products none the
feed, Σ expected % ≤ 100 (rest = normal loss), a stocked line needs a stockpile at activation, co-products share the
joint cost; one active profile per plant × feed. A run needs an active plant and the active profile version, a date not
in the future, units of its own plant, meters that do not run backwards; on post the per-stage mass balance must hold
(fractions of a stage ≤ what it receives) and the feed must be on hand (engine strict issue). Survey approval: never the
submitter; the adjustment applies (surveyed − book at submission) to today's stock and is refused below zero.

GL (posting engine): run → stage engine journals (`production_stage`): Dr WIP / Cr ROM stock (feed), Dr WIP / Cr overhead
absorbed (diesel, electricity, labour, wear parts, maintenance), Dr stock / Cr WIP per stocked fraction, Dr scrap / Cr WIP
for abnormal loss; reversal mirrors them. Survey → Dr stock / Cr stock adjustment income (gain) or Dr stock adjustment
expense / Cr stock (loss), source `crushing_survey` / `crushing_survey_reversal`. Event `crushing.run.posted`. Quality:
each stocked fraction of a posted run opens a final inspection lot (batch = run code, `stockControl = source`) when an
active final plan exists for the product (sieve-analysis specification).

Web: Production → Crushing Runs (`/manufacturing/crushing`, list + filters + pagination; `/runs/new`, `/runs/:id/edit`
phone / tablet operator entry with sticky output / loss / yield totals; `/runs/:id` detail with post / reverse, GL),
Crushing Plants (`/plants`, `/plants/:id` circuit editor + profiles), grading profile (`/profiles/new?plantId`,
`/profiles/:id`: lines, activate, new version, archive), Aggregate Stockpiles (`/stockpiles`: stock per pile, surveys
with submit / approve / reject / reverse), Crushing Reports (`/reports`). i18n for ti / lg / sw.

Tests: `CrushingCalculatorTest` (split, meters, per-stage balance, yield, performance, survey variance, cost kinds),
`CrushingIT` (circuit → routing / work centres / new routing version; profile rules, versioning, system BOM; run draft →
preview → post with NRV by-product, unstocked waste, abnormal loss, GL balanced, idempotent replay, reports, reversal;
validation; concurrent posts on scarce feed; survey with SoD, adjustment + GL, reversal, reject; tenant isolation; authz
matrix of the 36 routes + operator cannot post). `ManufacturingIT` matrix now 167 routes.

Status (2.3): done (2026-10-30). Gaps: no automatic feed from `quarry.extraction.posted`; belt-scale / PLC capture via
2.7's SPI; fleet cost per hour / payroll actuals per run (2.8); normal loss is measured at the last stage's input (fractions
leaving at an earlier stage are excluded from it); the quality lot of a reversed run is not cancelled automatically.

## ADR-S019 — 2.5 Advanced planning & scheduling

Status: built, **not compiled or run**. Migration `V40__manufacturing_planning.sql`. Tables `ProductionDemandPeg`,
`ProductionOrderPlanning`, `ProductionShiftPlan`, `ProductionTarget`. No new permissions.

| Route under `/manufacturing/planning` | Permission | Purpose |
|---|---|---|
| `GET /demand` | `production_plan:read` | Requirements netted against supply, per row and per product |
| `POST /plans/from-demand` | `production_plan:create` | Draft plan with pegs for what stays uncovered |
| `GET /pegs` | `production_plan:read` | Pegs by order, plan, customer or product |
| `GET /orders`, `PUT /orders/{id}` | `production_plan:read`, `:update` | Priority board; due date and priority |
| `POST /orders/{id}/schedule-backward` | `work_order:manage` | Backward schedule, forward fallback when late |
| `POST /schedule-open-orders` | `work_order:manage` | All open orders with a due date, by priority |
| `POST /work-orders/{id}/reschedule` | `work_order:manage` | Gantt drag |
| `GET /gantt` | `work_order:read` | Lanes, bars, closed dates, unscheduled work |
| `GET /capacity`, `GET /aggregates` | `production_plan:read` | Load vs capacity; aggregate crushing plan |
| `GET, POST /shift-plans`, `PUT, DELETE /shift-plans/{id}`, `POST /shift-plans/{id}/publish` | `production_plan:*` | Shift plans |
| `GET, POST /targets`, `PUT, DELETE /targets/{id}` | `production_plan:*` | Period targets |
| `GET /reports/shortfall`, `/production-vs-plan`, `/plan-adherence`, `/production-vs-target` | `production:report` | Reports |

Screens: `/manufacturing/planning-board`, `/gantt`, `/capacity`, `/shift-plans`, `/plan-performance`.

## ADR-S018 — 2.8 Manufacturing costing & analytics

Status: built, **not compiled or run**. Migration `V41__manufacturing_costing.sql`. Tables `WorkCenterCostRate`,
`ProductionCostAbsorption`. No new permissions.

| Route under `/manufacturing/costing` | Permission | Purpose |
|---|---|---|
| `GET /rates` | `work_center:read` | Every active centre with its rates |
| `PUT, DELETE /rates/{workCenterId}` | `standard_cost:manage` | Set or remove rates |
| `GET /orders/{id}` | `production_order:read` | Actual cost, absorption due, block or tonne unit cost |
| `POST /orders/{id}/absorb` | `production_order:complete` | Post what is due. Requires `Idempotency-Key` |
| `GET /orders/{id}/variances`, `GET /variances` | `production:report` | Price, usage, efficiency, overhead, yield |
| `GET /reports/summary?groupBy=shift\|plant\|product\|month` | `production:report` | Output, cost, unit cost |
| `GET /reports/margin?groupBy=product\|customer` | `production:report` | Gross margin |

Screens: `/manufacturing/costing`, `/manufacturing/costing/orders/:id`, linked from the production order page.

## Demand forecast (Phase 5)

`GET /manufacturing/planning/forecast?from=&to=&weeks=&family=` (`production_plan:read`), Forecast tab on
`/manufacturing/planning`. Plans nothing by itself.

- **History:** net weekly sales per product from posted sales invoices less credit notes, in base units, plus POS sales
  less refunds. Complete Monday-based weeks only, 12 by default, 2 to 104. The older `/manufacturing/reports/forecast`
  reads POS invoices only and is unchanged, because aggregates and blocks sell on sales invoices it never sees.
- **Products:** active grading profile lines are `aggregate`, mix designs are `block`, plus anything else with an active
  BOM.
- **Method:** double exponential smoothing, alpha 0.3, beta 0.1 (`DemandForecastCalculator`). Never below zero. No
  seasonal term. Confidence is `low` under 4 weeks with sales, `medium` when the mean error exceeds half the level.
- **Planning:** `forecastMethod=smoothed` on `GET /manufacturing/planning/demand` and on
  `POST /manufacturing/planning/plans/from-demand`. The smoothed horizon quantity, less firm demand, becomes a `forecast`
  requirement. `run_rate` stays the default, so existing behaviour is unchanged. Without `forecastWeeks`, smoothed uses 12
  weeks and run-rate 4.
- **Aggregates without a BOM** show their demand and shortfall but are reported as skipped by plan-from-demand with
  "No active BOM". Crushing is planned from that shortfall by hand.

## ADR-S027 — replenishment & MRP completion (roadmap 2.3, PR-5)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/manufacturing/mrp/runs/:id/capacity` | `production_plan:read` |
| GET | `/api/v1/manufacturing/mrp/settings` | `production_plan:read` |
| PUT | `/api/v1/manufacturing/mrp/settings` | `production_plan:update` |

- Planning parameters accept `procurement: subcontract` (supplier = subcontractor). Planned order `kind: subcontract`
  explodes like make; firming creates the draft production order and a draft `SUBPR-` service purchase request
  (`subcontractRequestId`). Run `summary.subcontract` counts them (`make` excludes them).
- Rough-cut capacity: routing load `setupMins + durationMins × qty ÷ BOM output` per in-house operation, spread from
  release to due, over `GET /manufacturing/planning/capacity` data; `bucket` `week` (default) or `day`.
- Settings: `autoRun`, `horizonDays` (1–730, 400 `horizonDays must be between 1 and 730`), `locationId`; nightly run at
  02:30 UTC (`MrpAutoRunJob`), plans only.
- Replenishment `manufacture` lines → one submitted production request, `sourceType = replenishment`,
  `ProductionRequestLine.deliveryLocationId` set (`ManufacturingReplenishmentService`).

Tests: `ReplenishmentMrpIT`.

## ADR-S063 — Product lifecycle management (extension: no Nest counterpart)

[ADR-S063](../adr/ADR-S063-product-lifecycle-management.md). Migrations `V440__plm_product_lifecycle.sql`, `V441__plm_dispositions.sql`. Services
`PlmLifecycleService`, `PlmStageService`, `PlmChangeService`, `PlmRequestService`, `PlmDocumentService`,
`PlmDispositionService`, `PlmGate`,
`PlmEffectivityJob`; controller `PlmController`. Web: `/manufacturing/plm/*`.

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/manufacturing/plm/overview` | `plm:read` |
| GET | `/api/v1/manufacturing/plm/items` | `plm:read` |
| POST | `/api/v1/manufacturing/plm/items` | `plm:manage` |
| POST | `/api/v1/manufacturing/plm/items/register-active` | `plm:manage` |
| GET | `/api/v1/manufacturing/plm/items/:id` | `plm:read` |
| PATCH | `/api/v1/manufacturing/plm/items/:id` | `plm:manage` |
| POST | `/api/v1/manufacturing/plm/items/:id/transition` | `plm:manage` |
| GET | `/api/v1/manufacturing/plm/stages` | `plm:read` |
| POST | `/api/v1/manufacturing/plm/stages` | `plm:manage` |
| PATCH | `/api/v1/manufacturing/plm/stages/:id` | `plm:manage` |
| DELETE | `/api/v1/manufacturing/plm/stages/:id` | `plm:manage` |
| PUT | `/api/v1/manufacturing/plm/stages/:id/approvers` | `plm:manage` |
| GET | `/api/v1/manufacturing/plm/requests` | `plm:read` |
| GET | `/api/v1/manufacturing/plm/requests/:id` | `plm:read` |
| POST | `/api/v1/manufacturing/plm/requests` | `plm:request` |
| PATCH | `/api/v1/manufacturing/plm/requests/:id` | `plm:request` |
| POST | `/api/v1/manufacturing/plm/requests/:id/review` | `plm:approve` |
| POST | `/api/v1/manufacturing/plm/requests/:id/convert` | `plm:change` |
| GET | `/api/v1/manufacturing/plm/changes` | `plm:read` |
| POST | `/api/v1/manufacturing/plm/changes` | `plm:change` |
| GET | `/api/v1/manufacturing/plm/changes/:id` | `plm:read` |
| PATCH | `/api/v1/manufacturing/plm/changes/:id` | `plm:change` |
| GET | `/api/v1/manufacturing/plm/changes/:id/impact` | `plm:read` |
| POST | `/api/v1/manufacturing/plm/changes/:id/stage` | `plm:change` |
| POST | `/api/v1/manufacturing/plm/changes/:id/decisions` | `plm:approve` |
| POST | `/api/v1/manufacturing/plm/changes/:id/comments` | `plm:read` |
| POST | `/api/v1/manufacturing/plm/changes/:id/documents` | `plm:change` |
| PUT | `/api/v1/manufacturing/plm/changes/:id/routing-operations` | `plm:change` |
| POST | `/api/v1/manufacturing/plm/changes/:id/apply` | `plm:apply` |
| POST | `/api/v1/manufacturing/plm/changes/:id/cancel` | `plm:apply` |
| GET | `/api/v1/manufacturing/plm/documents` | `plm:read` |
| GET | `/api/v1/manufacturing/plm/documents/:id` | `plm:read` |
| POST | `/api/v1/manufacturing/plm/documents` | `plm:manage` |
| PATCH | `/api/v1/manufacturing/plm/documents/:id` | `plm:manage` |
| POST | `/api/v1/manufacturing/plm/documents/:id/release` | `plm:manage` |
| POST | `/api/v1/manufacturing/plm/documents/:id/revise` | `plm:manage` |
| POST | `/api/v1/manufacturing/plm/documents/:id/obsolete` | `plm:manage` |
| DELETE | `/api/v1/manufacturing/plm/documents/:id` | `plm:manage` |

Rules (verbatim messages are in the services):

- **Gates**, only for products registered in PLM:
  - A production order for a concept, design or obsolete product is refused: "Product X is design in its lifecycle
    and cannot be produced."
  - A BOM cannot be activated for an obsolete output, or with obsolete components ("BOM X uses obsolete
    component(s): …").
- **Transitions.** Only the listed ones are allowed.
  - Release needs an active BOM when the product has any BOM.
  - Obsolete is refused while the product is a component of a non-obsolete active BOM, or has open production
    orders.
- **Stages.** Ordered by `sequence`, and the last one means approved (defaults are created lazily).
  - Forward moves need the current approval stage satisfied: required approvers approved, or one `plm:approve`
    holder when the stage has no named approvers, and no rejection outstanding.
  - Approval stages cannot be skipped.
  - The approval that completes a stage advances the ECO one stage.
  - Backward moves void the approvals from the target stage on.
- **Legacy ECO routes** (`/engineering-changes/:id/approve|reject|apply`) keep their paths, permissions and messages:
  approve fast-tracks to the last stage, reject cancels, apply forces (no use-up wait).
- **Apply.**
  - Queued (`applyQueued`, response `pending`) while the effective date is in the future on the org calendar
    (`Organization.timezone`), or while a BOM change with `stockDisposition=use_up` still has stock of a removed
    component. `PlmEffectivityJob` (hourly at :05) applies queued / date-effective ECOs once ready. Body
    `{"force": true}` skips the use-up wait only.
  - BOM changes run the dispositions for removed components not used by another active BOM: scrap → draft stock
    adjustment to zero, rework → draft transfer to QC quarantine, return_to_supplier → draft debit note to the
    preferred supplier. `wipDisposition` update / rework replaces draft and confirmed orders on the old BOM with new
    orders on the new BOM (pegs and plan lines re-pointed); rework also flags in-progress orders for QC. Multi-stage /
    pegged orders are kept. Result in `dispositionResult`.
  - Releases the attached draft documents.
  - Bumps the PLM revision (A → B; explicit `newRevision` wins; product changes only bump with `newRevision`).
- **Cancel** soft-deletes the draft BOM version and the inactive routing copy, and detaches draft documents.
- **Documents.** One draft per number. Releasing supersedes the previous released version. A draft attached to an
  open ECO is released only by that ECO.
- `POST /boms/:id/revise` refuses a second open ECO on the same BOM.

Tests: `PlmIT`, `CostingEngineeringIT`.

## Cost Management CM-4 (ADR-S034)

Stage standard-cost differences post through `StandardCostService.postStageVariance` (same 5950 / WIP lines, now in
the variance register). Normal capacity and the IAS 2.13 absorption settlement live in `costing`
(`/costing/normal-capacities`, `/costing/absorption-settlements`, `/costing/cost-flow-bridge`).
