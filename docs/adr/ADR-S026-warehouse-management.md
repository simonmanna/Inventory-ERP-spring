# ADR-S026 — Warehouse management (bins, putaway, pick / pack / ship, waves, ABC cycle counts)

Status: **accepted** (owner instruction "build it production ready", 2026-10-31; roadmap 2.1, PR-4). Implemented in
`V218`, `WarehouseStore`, `WarehouseSetupService`, `WarehouseTaskService`, `WarehouseOutboundService`,
`WarehouseAbcService`, `AbcClassifier`, `WarehouseController` (`/warehouse`) and the web page `/inventory/warehouse`.

## Context

Stock is kept per location: `StockItem` holds the quantity and running average, `InventoryLedger` every movement and
its value, and the posting engine turns movements into GL lines. Locations are flat (`InventoryLocation`), with no
bins, zones, putaway logic or outbound picking — the 2026-10-27 assessment scored WMS 2 / 10. Many flows already
issue stock (POS sale, stock-out, transfer, fleet dispatch of a sales order, production consumption) and must keep
their Nest-parity behaviour.

## Decision

1. **Bins are a sub-ledger inside one location, not new ledger locations.** `WarehouseBinStock` holds the quantity per
   bin, product, variant and batch. The location ledger stays the single source of quantity and value, so moving
   stock between bins never writes a ledger row or a GL line, and no existing route changes. Inside a location:
   `location quantity = binned + staged (picked, not shipped) + unbinned (received, not yet put away)`.
   `GET /warehouse/locations/:id/overview` shows the split per product and flags a negative remainder (bins holding
   more than the ledger, e.g. after an issue that bypassed the WMS).
2. **Bin tree.** `WarehouseBin` rows form a tree per location: `zone` / `aisle` / `rack` / `bin`; only `bin` holds stock.
   A bin has a usage (`storage`, `receiving`, `output`, `quarantine`), a pick sequence, an optional capacity
   (`maxQty`, in the product's stock unit) and a single-product flag. A bin with stock cannot be deactivated, turned
   into a container or shrunk below its quantity; a bin with task history is deactivated instead of deleted.
3. **Putaway is pull-based.** Receipts keep posting to the location as today. `POST /warehouse/putaway/generate`
   creates putaway tasks for everything unbinned (minus open putaway tasks); a manual putaway task and
   `GET /warehouse/putaway/suggest` use the same planner. Rules (`WarehousePutawayRule`) are tried product first, then
   category, then the location default, by priority; a rule targets one bin or a zone (any active storage bin below
   it). Within the candidates: bins already holding the product, then empty bins, then the rest, each by pick
   sequence. Capacity counts the stock in the bin plus open tasks heading there; a quantity larger than one bin is
   split over several. Without a matching rule any active storage bin of the location qualifies.
4. **Tasks** (`WarehouseTask`: `putaway`, `move`, `pick`) are the floor work. Completing one may use a lower quantity
   (short pick, partial putaway) and override the destination; capacity, single-product bins and availability are
   checked at completion under a per-(location, product) advisory lock, and bin stock can never go negative (check
   constraint plus conditional update).
5. **Outbound in one to three steps** (`WarehouseSettings.outboundSteps`, overridable per pick order): 1 = pick and the
   order ships itself on the last pick; 2 = pick → ship; 3 = pick → pack (`WarehousePackage`, partial packing allowed)
   → ship. Releasing a pick order allocates bin stock FEFO (batch expiry) then by bin pick sequence, net of open picks
   and moves, and refuses a shortage (`Not enough binned stock to pick …`). A pick order references its source
   (`manual`, `sales_order`, `delivery_order`, `transfer`, `stock_out`) by id / reference only.
6. **Shipping does not post the ledger issue.** The source document keeps posting it exactly as today; the pick order
   moves stock from bins to staging and records carrier, tracking and packages. This keeps every parity flow
   untouched and avoids double issues. Cancelling a picked order requires a return bin and puts the stock back.
7. **Waves** group draft pick orders of one location; releasing the wave releases them highest priority first and
   exposes one consolidated pick list (per bin and product, by pick sequence). A wave is `done` when every order is
   shipped or cancelled.
8. **ABC cycle counts.** `POST /warehouse/locations/:id/abc/classify` ranks products by consumption value over the
   look-back window (ledger value of issues, transfers out, production consumption and internal use) with Pareto
   thresholds (default A < 80 %, B < 95 % cumulative share before the item). Each class has a count interval (default
   30 / 90 / 180 days). `POST /warehouse/locations/:id/cycle-counts` opens a standard blind `cycle` session of
   `InventoryCountService` for the due products, so counting, variance approval and posting stay where they are.
   `lastCountedAt` follows the latest submitted count of the product at the location (any count type).
9. **Permissions**: `warehouse:read`, `warehouse:manage` (settings, bins, rules, classification), `warehouse:operate`
   (tasks, pick orders, packing, shipping, waves; cycle counts also need `inventory_count:count`). Administrator gets
   all three through the permission sync. Every route checks the caller's branch scope on the location (`LocationScope`)
   and every table carries `organizationId` with the tenant RLS policy.

## Amendment — production hardening (2026-11-10, `phase/inv-hardening`, owner-approved)

- **Manual pick orders issue stock.** Shipping a `manual` pick order (no source document) issues the picked quantities
  through `StockService.issue` (per product / variant / lot, lot pinned, `requireAvailable`, move type `issue` →
  `STOCK_OUT` GL; ledger and journal reference `WarehousePickOrder` / order code) in the shipping transaction.
  Orders with a source document (`sales_order`, `delivery_order`, `stock_out`, `transfer`) still leave the issue to it.
  Decision 6 holds for those only.
- **No empty shipments.** When every pick task of an order ends with nothing picked (cancelled / done with 0), the
  order returns to `draft` (release again or cancel); `ship` refuses an order with nothing picked (`Nothing was picked
  on this pick order; release it again or cancel it`).
- **Bin corrections.** `POST /warehouse/bins/:id/adjust` (`warehouse:manage`; `productId`, `variantId?`, `batchId?`,
  exactly one of `newQty` / `delta`, `reason`) changes bin quantities only (no ledger, no GL), audited as
  `WarehouseBinStock` / `adjust`. An increase must come from unbinned stock (on-hand − binned − staged − open putaway);
  a decrease cannot take quantity held by open pick / move tasks.
- **Reconciliation.** The overview adds `excessBinnedQty` per product and `mismatches`. `POST
  /warehouse/locations/:id/reconcile` (`warehouse:manage`, optional `productId`) takes binned + staged − on-hand out of
  the bins: storage, then receiving, then other bins, by pick sequence and code, earliest lot expiry first, quantity
  not held by open tasks before held quantity. Audited as `reconcile`.
- **Release capped by the ledger.** Release allocates at most location on-hand − staged − open pick tasks (400 `Not
  enough stock on hand to pick …`), besides the bin availability check.
- **`enabled` is enforced.** With settings stored and `enabled = false`, every operate / manage route except the
  settings themselves answers 400 `Warehouse management is switched off for this location; turn it on in the warehouse
  settings first`; reads keep working. A location that never saved settings behaves as before.
- **Locking.** `addStock` locks the bin row (`FOR NO KEY UPDATE`) before the capacity / single-product checks; task
  completion and cancellation lock the pick order before the task, the same order as order cancel / ship.

Still open: automatic bin decrement on ledger issues (POS, stock-outs, transfers, production, counts) — drift is now
visible and fixable with reconcile / adjust; release allocation is capped per product, not per lot.

## Consequences

- WMS is optional per location; a location without bins behaves exactly as before.
- Bin balances can drift from the ledger when stock is issued without a pick order (e.g. a POS sale from a warehouse
  location); the overview shows it (`mismatch`, `excessBinnedQty`) and reconcile / bin adjust fix it (amendment above).
  Automatic bin decrement on ledger issues is not part of this ADR.
- Not in this ADR: receipt-driven putaway (an event from goods receipts), two-step receiving through a `receiving` bin
  with automatic follow-up tasks, lot-level unbinned quantities (putaway of unbinned stock carries no batch),
  carrier integrations, labels for packages and a scheduled ABC job. The mobile scanner app is roadmap 2.2 (PR-14).
