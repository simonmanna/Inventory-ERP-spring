# Auditor report: warehouse replenishment reports (read-only audit of main 46281ee2, 2026-11-17)

Line numbers refer to 46281ee2. Findings fixed since then are listed in [HANDOFF.md](../HANDOFF.md).

VERDICT (inventory operations): Ready after fixes. The stock engine, ledger, GL, valuation and tenant isolation hold, but three things block go-live: warehouse outbound can issue the same goods twice from its own screen, sourced pick orders put bin balances out of step with the ledger, and replenishment ignores branch scope. Keep WMS outbound switched off until F1–F3 are fixed.

FINDINGS (Java paths are under backend/src/main/java/com/inventoryerp/; a bare file name is in inventory/application/)

F1 [P0] Pick orders made on the warehouse screen are always `manual`, so shipping one for a customer order issues stock that its sales document issues again
  where: web/src/pages/inventory/WarehousePage.tsx:436-438; inventory/application/WarehouseOutboundService.java:154, 365-369, 387-411
  what: The only pick-order form sends locationId, sourceRef and lines, so the order is stored as `manual`. Shipping a manual order posts an `issue` (STOCK_OUT). No other module reads the warehouse tables, so nothing stops the invoice, cash sale or dispatch from issuing the same goods.
  scenario: 20 on hand. Invoice INV-1 for 10 issues stock (→10). Staff create a pick order for 10 with Reference "INV-1", pick it and ship it. That posts a second issue (→0), Dr stock-out expense / Cr inventory. The shelf holds 10, the ledger says 0, and the expense is doubled. The same happens if the ship comes first.
  confidence: confirmed code path. The trigger is staff using the screen for customer orders, which is what it is for. test: none (`WarehouseIT.manualShipIssuesStock` covers a single issue only); known: new

F2 [P1] Sourced pick orders put bins out of step whichever document moves first, and the error message sends users to a reconcile that deletes good bin stock
  where: inventory/application/WarehouseStore.java:185-205; WarehouseOutboundService.java:209-217, 370-371; WarehouseSetupService.java:412-414, 512-573
  what: Ship sets shippedQty = pickedQty without issuing. Staged = picked − shipped. Release is capped by ledger on-hand.
  scenario:
   - (a) Invoice first: ledger 10→0, bins 10. Release answers 400 "Not enough stock on hand … reconcile the bins". Reconcile empties the bins. Release then fails "Not enough binned stock" while the goods are still on the shelf.
   - (b) Ship first: ledger 10, bins 0, staged 0, so 10 shows as "unbinned". `putaway/generate` creates putaway tasks for goods that have already left.
   - Ledger and GL are unaffected in both cases.
  confidence: confirmed; test: none (`WarehouseIT.outbound` never checks the state between ship and stock-out); known: new

F3 [P1] Shipping a manual pick order is a stock-out with no approval and no segregation of duties
  where: WarehouseOutboundService.java:399-402 (`people(user(), user())`); inventory/web/WarehouseController.java:345-350, 397-401; compare StockDocService.approveStockOut:170-204
  what: One user with `warehouse:operate` can create, release, pick and ship; a one-step order ships itself on the last pick. That user is recorded as both responsible and approver for the expensed stock. A regular stock-out needs `inventory_doc:approve` plus the approval engine.
  scenario: an operator writes off 50 units of a high-value item as a manual pick order, and nobody approves it.
  confidence: confirmed; test: none; known: new (the ADR-S026 amendment approved the issue, not this control)

F4 [P1] Replenishment ignores branch scope
  where: inventory/application/ReplenishmentService.java:130, 160, 196, 222, 338, 356, 377, 472; inventory/web/ReplenishmentController.java:55-105
  what: A branch-restricted user with `inventory:replenish` can:
   - see every location's rules and alerts (on-hand, available);
   - create, edit or delete rules for any location;
   - dismiss alerts;
   - raise purchase or production requests for other branches.
  Only transfers are checked (`createTransfer` → `assertEither`). When that check returns 403, it rolls back the user's whole org scan.
  scenario: a branch A clerk deletes branch B's minimum rule or dismisses its alert, and B is never restocked.
  confidence: confirmed; test: none (`ReplenishmentIT` covers tenant isolation and permissions only); known: new

F5 [P2] One stale auto transfer rule stops the organisation's hourly scan for good
  where: ReplenishmentService.java:382-383, 446-453, 541-554; StockDocService.java:718-719; ReplenishmentScanJob.java:38-45
  what: Automatic requests run inside the scan transaction with no catch. `createTransfer` answers 404 for a soft-deleted source location, and deleting a location does not check for rules that use it. The whole scan rolls back, new alerts included, and every later run repeats the failure. Scale: about eight queries per rule, in one transaction per org.
  scenario: delete warehouse W, the source of an auto transfer rule. Once that product drops below minimum, the org gets no new alerts for any product, and the job only logs a warning.
  confidence: likely (traced, not run); test: none; known: new

F6 [P2] Product stock levels show other branches' stock, and the low/out-of-stock filter loads the whole catalogue
  where: inventory/application/InventoryQueryService.java:145-168, 200-214
  what: `locationBreakdown` lists every location's quantity to a restricted user; only the total is scoped. With `lowStock` or `outOfStock`, every product is loaded and mapped with two or more queries each, using doubles.
  confidence: confirmed; test: none (`UserDataScopeIT` does not cover this route); known: new

F7 [P2] Warehouse queries slow down as history grows, and some run while holding locks
  where: backend/src/main/resources/db/migration/V218__warehouse_management.sql:147, 177-178; WarehouseStore.java:185-190, 214-230; WarehouseOutboundService.java:190; WarehouseTaskService.java:85-102; WarehouseSetupService.java:109-122, 530
  what:
   - Task lookups by `fromBinId`/`toBinId` have no index and no org/location prefix, so they scan every task ever written.
   - `stagedQty` (`status <> 'cancelled'`) reads every shipped order of the location.
   - Bin lists aggregate the org's whole bin stock on every call.
   - Release and task completion run these per line while holding order and advisory locks.
  confidence: likely (no query plan run); test: none; known: new

F8 [P2] Bin drift detection is partial, and reconcile trims the wrong bins
  where: WarehouseSetupService.java:408-416, 512-573; WarehouseStore.java:202-205
  what: POS sales, stock-outs, waste, transfers, production and counts never touch bins. `mismatch` fires only when unbinned < 0, so dock stock awaiting putaway hides drift. Reconcile removes stock by usage, pick sequence and expiry, not from the bin the goods actually left, and there is no bin-level count.
  impact: pickers are sent to empty bins, picks come up short and releases get blocked; the books are unaffected.
  confidence: confirmed; test: `WarehouseIT.binDriftAdjustReconcile` (happy path only); known: ADR-S026 "Still open"

F9 [P2] Stock health and movement report values disagree with valuation and the GL
  where: inventory/application/InventoryReportsService.java:921-925, 346-352, 633, 51-58; compare accounting/application/InventoryValuationService.java:210-222
  what: Stock health is not method-correct, while valuation is:
   - Serial products are valued at the running average, not their own serial costs.
   - Lot/FIFO products are valued at open-lot value only, with no remainder at the average.
   - CM-1 adjunct components are left out.
  Separately, value-only `cost_adjustment` rows (quantity 0, signed value) count as "in" and fall into the group "other" in the movement reports. Negative rows come from procurement/application/LandedCostLayers.java:418 and inventory/application/CostLineageService.java:462.
  confidence: likely (stock health); suspected (movement totals; a landed-cost reversal fixture would confirm it); test: none; known: same problem as C-2, not fixed here

F10 [P2] Unbounded and N+1 reads
  where: StockDocService.java:1133-1167; LedgerDetailService.java:66-69, 90-104; InventoryQueryService.java:642-705; InventoryReportsService.itemMovements (opening and closing sums)
  what:
   - Document lists return every document, with one query per document.
   - A ledger entry loads all its sibling lines, with four queries each.
   - Reconciliation aggregates the whole ledger and flags negative lot-tracked quants as batch drift (false positives).
   - Item movements sum from the first ledger row.
  confidence: confirmed; test: none; known: deviation 23 (approved, but a real production risk at volume)

F11 [P2] No `@Idempotent` on warehouse routes that move stock
  where: inventory/web/WarehouseController.java:345-350, 397-401 (no `@Idempotent` anywhere in the class)
  what: The order lock and status claim prevent a double issue. But a retry after a lost response gets 400 instead of the original result, so the client cannot tell success from failure.
  confidence: confirmed; test: none; known: deviation 16 lets keys be optional, but these routes don't accept one at all

F12 [P2] A concurrent event redelivery can create a duplicate draft request
  where: ReplenishmentService.java:558-575; procurement/application/ProcurementReplenishmentService.java:46-66 (the manufacturing handler has the same pattern)
  what: `stillRequested` does not lock, and `markRequisitioned` ignores its update count. A claim older than 30 s can be re-claimed, so two deliveries of one event can both create a request.
  confidence: suspected (needs a first delivery slower than 30 s); test: none; known: new

Minor:
- A manual putaway's `batchKey` is not validated (WarehouseTaskService.java:248-299).
- `updateBin` checks quantity without locking the bin (WarehouseSetupService.java:167-218).

RE-CHECKED EARLIER ITEMS:
- ADR-S026 "Still open" (no bin decrement on ledger issues; release capped per product, not per lot): still open — WarehouseOutboundService.java:209-211.
- V-10 (reorder/replenishment per product): fixed — ReplenishmentService.java:390-391; InventoryQueryService.java:487.
- C-2 (value by costing method): fixed for item list, detail and movements (InventoryQueryService.java:215; InventoryReportsService.java:332); still open in stock health (F9).
- C-1 (variant cost fallback): fixed — accounting/application/InventoryValuationService.java:192-194.
- S-2 (lock order): fixed in this area; the warehouse issue sorts its lines — WarehouseOutboundService.java:394.
- Deviation 23 (no list caps): still open (F10).

STRENGTHS:
- A manual ship issues exactly once: order row lock, status claim and posting key. A second ship answers 400 (`WarehouseIT.manualShipIssuesStock`).
- Bin stock cannot go negative (check constraint plus conditional update). `addStock` locks the bin before checking capacity, and locks are always taken order first, then task.
- Every warehouse route checks LocationScope and organisation, and RLS covers all V218 tables.
- Replenishment alerts cannot duplicate: partial unique index on live alerts, ON CONFLICT, FOR UPDATE claims, and the transfer is created in the same transaction. All three inventory jobs use ShedLock and per-org TenantContext, and a failure in one org doesn't stop the others.
- The register (SQL, two-year window, 5,000-row cap) and valuation (method-correct, matches accounting; `InventoryValuationReportIT`) are production-grade. Ledger indexes match the report filters.

TEST GAPS:
- A pick order without `sourceType` for goods that a sales document also issues (F1).
- Overview, release and reconcile in the window between a sourced ship and the source document's issue, in both orders (F2).
- Branch-restricted users on the warehouse, replenishment and product-stock-levels routes.
- Concurrency: parallel ship/complete of one order, scan against request, duplicate event delivery.
- An auto transfer rule with a deleted source location; release and `stagedQty` on a large history.

Audit was read-only: no files changed, no gradle/pnpm/docker, only read-only git.
