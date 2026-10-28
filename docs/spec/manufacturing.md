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

Ported: 61/61 routes (42 in the generated table + 19 phase-4 routes). `BomExplodeTest` (port of
`bom.service.spec.ts`) and `ManufacturingIT` (port of `manufacturing-flow.spec.ts`: WIP-flat bake, idempotency
guards, cancel, under-yield, zero-yield, QC, reverse, overhead; plus BOM rules, requests/plans/MRP, work orders,
reports, tenant isolation and the authorization matrix).
