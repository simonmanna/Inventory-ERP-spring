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
