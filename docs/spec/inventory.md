<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
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

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/inventory-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
