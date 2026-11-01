# Inventory — `inventory`

| | |
|---|---|
| Phase | P3a |
| Nest source | `reference/nest-api/src/modules/inventory` |
| Routes | 69 — full table: [generated/inventory.md](generated/inventory.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.inventory` |

## Purpose

Locations, stock items and levels, append-only inventory ledger, costing (AVCO, FIFO layers, STANDARD, SPECIFIC/serial), batches/serials/expiry, direct stock in/out, stock-outs, waste, adjustments, transfers (dispatch/receive/recall), stock counts, reservations/ATP, GL posting rules, inventory reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| locations | CRUD (warehouse, register location, bar, kitchen) |
| stock | `direct-stock/in|out`, items, item by product, stats, product-stock-levels, ledger |
| documents | stock-outs, waste (+summary), adjustments, transfers — create/list/approve/cancel/reverse; transfers dispatch/receive/recall |
| counts | `/inventory/counts`: preview, start, draft, submit, review, cancel |
| reservations | `/inventory/reservations`: create, release, consume, atp |
| posting rules | `/inventory/posting-rules`: CRUD, overview, movement types, resolve-preview, restore defaults (`packages/shared/src/inventory-posting-rules.ts`) |
| reports | expiring, reorder, movements, item-movements, valuation, movement-analysis, register/:kind, reconciliation, stock-health, negative-stock |

## Business rules to reproduce exactly

1. Every quantity change goes through `StockService` (receive/issue/transfer/adjust) and writes one `InventoryLedger` row: `qtyBefore`, `quantityChange`, `balanceAfter`, `unitCost`, `type` (StockMoveType), `referenceType/Id`, `ledgerCode`. Ledger is append-only (DB guards in V1 enforce).
2. AVCO: lock the `StockItem` row (`FOR UPDATE`), new avg = (qty×avg + inQty×inCost)/(qty+inQty) at 6 dp; issues at current avg. FIFO consumes `InventoryBatch` layers oldest first. STANDARD uses product standard cost (variance to GL). SPECIFIC uses serial cost.
3. Stock period lock: movements dated in a locked period are rejected (verbatim message).
4. Negative stock: allowed/blocked per settings and POS stock policy (`block|warn|silent`).
5. Reversals create opposite moves (`reversal_in/out`) linked to the original; double reversal rejected.
6. GL posting per movement type via `InventoryPostingRule` (debit/credit account resolution, overridable per location/category).
7. Counts: submitted count posts adjustments for differences; review shows variance value.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`stock.received`, `stock.issued`, `stock.adjusted`, … (see `DomainEvents`).

## Scheduled jobs

— (POS stock posting drain lives in pos).

## Notes and pitfalls

- Port `stock.service.ts` with all locking; port the 28 inventory spec files listed in generated spec.
- `STOCK_POSTING_BATCH` env controls drain batch size.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/inventory-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Pending owner approval:

1. **`GET /inventory/items?lowStock=true`**: Nest passes a `Prisma.sql` fragment as a `where` field value,
   which Prisma rejects at runtime (500). Java applies the intended filter
   (`quantity > 0 AND minQuantity > 0 AND quantity <= minQuantity`).

Same as Nest, kept on purpose:

- Prisma tenancy emulation: explicit `organizationId` + `deletedAt IS NULL` on soft-delete models; relation
  includes are not filtered (Prisma's extension does not filter includes either).
- Period-lock, negative-stock and posting-rule resolution follow `stock.service.ts` / `stock-posting.service.ts`
  line by line; all money/qty math is `BigDecimal` with decimal.js semantics (`Money.div`, `JsNumbers.dec`).

Kernel fix found while porting: `SequenceService` now creates per-org sequences outside the caller's transaction
(Nest uses the raw client). A business rollback used to drop a freshly created sequence while the in-memory
cache still considered it present (500 on the next `nextval`).

## Status

P3a: 69/69 routes. Tests: `InventoryIT` (15: locations, items, ledger, stock-out / waste / adjustment / transfer
document flows incl. approve + reverse, counts, reservations + ATP, posting rules, reports, period lock,
idempotent replay, authorization matrix = 69 routes, tenant isolation), `InventoryUnitTest` (ports of
`cost-resolver.spec.ts`, `stock-posting.service.spec.ts`, decimal formatting). Parity scenario:
`contract-tests/scenarios/inventory-stock.json`.

Deferred to the phase that owns the other side of the flow: the `test/integration/audit/inv-*`,
`procurement-flow`, `pos-*` and `sync-*` Jest suites listed in the generated spec drive POS / procurement /
sync routes (P4–P6) and are ported there.

## ADR-S007 extensions — configurable costing policy (roadmap 1.8)

`/inventory/cost-policies` (GET list, GET `/current`, GET `/{id}`, GET `/{id}/preview`, POST, PATCH `/{id}`,
POST `/{id}/approve`, `/{id}/activate` (Idempotency-Key), `/{id}/cancel`); permissions `costing_policy:read`,
`manage`, `approve`. Daily job `inventory-cost-policy-activation` (00:15 UTC, ShedLock) activates approved policies
whose `effectiveFrom` has come. With an active policy `costingMethod` on products follows the policy (deviation from
Nest only for organisations that create a policy). Tests: `InventoryCostPolicyIT`. Web: Inventory → Costing Policy.

## ADR-S008 extensions — standard costing

`/inventory/standard-costs` (GET list `?productId&status`, GET `/variances?from&to&kind&productId`, GET `/{id}`,
POST, POST `/{id}/approve`, `/{id}/activate` (Idempotency-Key), `/{id}/cancel`); permissions `standard_cost:read`,
`manage`, `approve`. Daily job `standard-cost-activation` (00:20 UTC). Receipts of standard-governed products enter
stock at standard with purchase price variance (account 5905); activation revalues on-hand stock (5960).

## Locations and branches (BR-1, ADR-S017)

Status: built, not compiled or run. `InventoryLocation.branchId` (V62), accepted on create and update. Reads and stock
documents go through `LocationScope`; `StockService` does not.

## INV-1 reservation screen (built, unverified)

Additive `GET /inventory/reservations/overview` (`StockReservationService.overview`, paged, with names and on-hand). The
Nest list route is unchanged and POS does not call the new method. Screen: `/inventory/reservations`.

## INV-3 replenishment (built, unverified)

`ReplenishmentController` (8 routes on `/inventory/replenishment`: rules CRUD, `alerts`, `alerts/{id}/dismiss`, `scan`,
`requisition`), `ReplenishmentService`, `ReplenishmentCalculator`, `ReplenishmentScanJob` (hourly), migration V97, permission
`inventory:replenish`, event `inventory.replenishment.requested`. Procurement listens
(`ProcurementReplenishmentSubscriber`) and raises draft purchase requests. Stock, ledger and POS are not touched: the scan
only reads `StockItem` and `StockReservation`. Screen: `/inventory/replenishment`.

## ADR-S026 extension — warehouse management (roadmap 2.1, PR-4)

Bins, putaway rules and tasks, pick / pack / ship in one to three steps, packages, waves and ABC cycle counts under
`/warehouse` (`warehouse:read|manage|operate`). Bins are a sub-ledger inside a location (`WarehouseBinStock`); the
ledger, costing and GL are unchanged and the source document still posts every issue. Cycle counts open ordinary
`cycle` sessions of the count service. Details and limits: `docs/adr/ADR-S026-warehouse-management.md`.
