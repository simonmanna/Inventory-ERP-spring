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

## Consequences

- WMS is optional per location; a location without bins behaves exactly as before.
- Bin balances can drift from the ledger when stock is issued without a pick order (e.g. a POS sale from a warehouse
  location); the overview shows it (`mismatch`) and a move / count fixes it. Automatic bin decrement on ledger issues
  is not part of this ADR.
- Not in this ADR: receipt-driven putaway (an event from goods receipts), two-step receiving through a `receiving` bin
  with automatic follow-up tasks, lot-level unbinned quantities (putaway of unbinned stock carries no batch),
  carrier integrations, labels for packages and a scheduled ABC job. The mobile scanner app is roadmap 2.2 (PR-14).
