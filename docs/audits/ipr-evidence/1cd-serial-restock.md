# IPR 1c / 1d evidence: serial and lot integrity, location guards, linked restocks

Stream: engine owner E2, branch `fix/ipr-1cd` (worktree `/home/ierp-ipr-p0-checks`, off `fix/ipr-p15` with Phase 0,
1b and 3). No migration. Deviations: `docs/spec/inventory.md` (Deviations 60–65) and `docs/spec/pos.md` (Deviation 16).

Paths are relative to `backend/src/main/java/com/inventoryerp/`. Root-cause lines are for the branch base (`HEAD`
before this stream, `08eb2d9c`). Tests are in `backend/src/test/java/com/inventoryerp/inventory/audit/`.

## How red and green were produced

- **Red:** `backend/src/main` set aside (`git stash`), tests kept, run
  `fastTest --tests '*IprSerialRestockIT' '*IprPosRefundRestockIT'` (log `scratchpad/e2/red1.log` of the stream).
  Four methods need API that only exists after the fix (`StockMove.manual()` / `quarantineFlow()` /
  `honourReservations()`, `StockReversalService.issueBackReceipt`) and cannot compile red: `virtualLocationGuard`,
  `salesReservationsHonoured`, `parallelReserveAndIssue`, `issueBackReceipt`. They were dropped from the red copy only.
  Result: `15 tests completed, 14 failed`. FAILED lines:
  - `returnAndReversalKeepLot`: `expected: 2 but was: 3.000000` (lot quantity: the serial issue never left the lot)
  - `serialIssueDecrementsLot`: `expected: 2 but was: 3.000000`
  - `transferLotsFollowSerials`: `[the named serial moved] expected: <alt> but was: <main>`
  - `moreSerialsThanQuantityRefused`: `Expecting code to raise a throwable.`
  - `fewerSerialsAutoPick`: `[oldest unit picked] expected: "issued" but was: "in_stock"` (the named serial was
    ignored, two others issued)
  - `parallelSameSerial`: `expected: 2 but was: 3.000000` (lot quantity)
  - `unknownMoveTypeRefused`: `Expecting code to raise a throwable.` (posted as COGS)
  - `setupChangeBlockedByPendingWork`: the PATCH answered 200 (product JSON) instead of 400
  - `billReceiptKeepsSourceLine`: `Result value is null but no null value expected`
  - `unlinkedRestockAfterLinkFails`: `"key":"unlinked_restocks","ok":true … "informational: …"`
  - `residueSweptWhenCallerPostsJournal`: `[residue swept once] expected: 1L but was: 0L`
  - `parallelLastUnit`: `[integrity checks] quant_vs_lots [… C1-ISS …]`: the race itself held (one winner); the class
    org's integrity was already broken by the C-1 defect of an earlier method
  - `IprPosRefundRestockIT.voidThenRefundRestocksOnce`: `[the refund returned the 2 billed units only] expected: 10
    but was: 11.000000` (the double restock)
  - `IprPosRefundRestockIT.refundRestoresLot`: `400 "Tracked returns require an identified stock return; …"` (a
    lot-tracked line could not be restocked at all)
  - `IprPosRefundRestockIT.refundRacesOrderCancel` passed red too: the order-cancel guard already refused a billed
    order, so refund + cancel was never a double restock (see below).
- **Green:** the same classes plus the regression set, on a freshly reset DB (`docker rm -f -v erp-itdb-ipr0`);
  per-class counts in the last section.

## Findings

| Finding | Root cause (file:line) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity / GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| C-1 serial issue leaves its lot untouched | `inventory/application/StockService.java:756-772` (serial loop flips the serial, writes the row with its `batchId`, never decrements `InventoryBatch`); `:786` (overflow written lot-less while lots still hold stock) | `732f74f8` | `IprSerialRestockIT.serialIssueDecrementsLot` | — | `assertIntegrityGreen` (quant_vs_lots, quant_vs_serials, serial_state) + `assertGlTieOut` | L4 quant, L5 StockItem, L6 serial row (`UPDATE … WHERE status='in_stock'`), then its lot row | inventory.md 60 |
| C-1 / C-7 transfer: lots do not follow serials; `serialNumbers` ignored | `StockService.java:1309-1322` (serials chosen after the lot loop moved lots FEFO, by `receivedAt` only; `dto.serialNumbers` never read) | `732f74f8` | `IprSerialRestockIT.transferLotsFollowSerials` | — | yes | L4 both quants (sorted), L5, L6 serials then lots | inventory.md 60 |
| C-1 returns / reversals keep serial + lot | `inventory/application/StockReversalService.java` (outflow reversal re-received into a new lot row; inflow reversal of a serial receipt issued any serial) | `732f74f8` | `IprSerialRestockIT.returnAndReversalKeepLot` | — | yes | L2 original rows FOR UPDATE ORDER BY id, then L3–L6 per move | inventory.md 60 |
| C-2 serial count vs quantity | `StockService.java:756` (`LIMIT qty` silently ignored extra serials; named serials not at the location silently replaced) | `732f74f8` | `IprSerialRestockIT.moreSerialsThanQuantityRefused`, `fewerSerialsAutoPick` | fleet `setSerials` call-site check deferred (engine check now protects it) | yes | none new | inventory.md 60 |
| C-9 oldest-first pick | `StockService.java:759` (`ORDER BY "receivedAt"` only) | `732f74f8` | `fewerSerialsAutoPick` (oldest picked) | — | yes | none | assessed: oldest-first by receipt is a deliberate default; only a deterministic tie-break (`serialNumber`) added |
| X-3 quarantine / rental guard | `StockService.java:627, 1173`, `receiveCore`, `adjust` (no check of the location type at all) | `732f74f8` | `IprSerialRestockIT.virtualLocationGuard` | QC: `QualityLotService.quarantineStock` / release use source `quality_lot` (allowed); rental flows are not manual (allowed) | yes | none (reads the location row already looked up) | inventory.md 61; owner sign-off: PLM rework release path |
| Unknown move types (stream C) | `StockService.java:878, 921` (`INV_MOVE_TYPES.getOrDefault(moveType, "STOCK_OUT")`) | `732f74f8` | `IprSerialRestockIT.unknownMoveTypeRefused` | grep of every non-`skipGl` issue caller: all use mapped types; `adjustment_out` / `reversal_out` callers are all `skipGl` | journal count unchanged on refusal | none | inventory.md 62 |
| C-8 setup change vs pending work | `core/application/ProductService.java:540, 569` (only `hasStockOnHand`, no lock) | `a699ddc8` | `IprSerialRestockIT.setupChangeBlockedByPendingWork` | PATCH /products | — | L3 `costing:<org>` + `costing:<org>:<product>` exclusive (`CostingLocks.lockProduct`) | inventory.md 63 |
| E-1a reservations honoured by other issuers | `StockService.java:627` (issue never reads `StockReservation` of sales orders) | `732f74f8` | `IprSerialRestockIT.salesReservationsHonoured`, `parallelReserveAndIssue` | direct stock-out route refused (400) | yes | read under L4/L5 already held | inventory.md 64 |
| E-2a `returnBaseQuantity` public | `StockService.java:225` (package-private) | `732f74f8` | compile (call-site switch by the integrator) | — | — | none | — |
| SW-1 / X-5 POS refund restock | `pos/application/PosInvoiceService.java:1435` (restock = gross issued × refunded / sold: a void + refund restocks the voided units twice); `:1393` (components into the sale location, new lot, no link) | `43b009de` | `IprPosRefundRestockIT.voidThenRefundRestocksOnce`, `refundRestoresLot` | refund route | yes + GL | L1 invoice FOR UPDATE (unchanged), L2 issue rows FOR UPDATE ORDER BY id, then L3–L6 per return | pos.md 16 |
| SW-3 fleet WO part return | `fleet/application/FleetWorkOrderCostService.java:173` (`stock.receive` return_in, unlinked) | `0515bfc3` | covered by unlinked_restocks + existing fleet ITs | existing fleet WO ITs | — | L2 then L3–L6 | inventory.md 65 |
| SW-3 quarry explosive return | `quarry/application/QuarryExplosiveService.java:552` (unlinked, new `RET-` lot) | `0515bfc3` | `IprSerialRestockIT.unlinkedRestockAfterLinkFails` (engine path) + existing quarry ITs | existing quarry ITs | — | L2 then L3–L6 | inventory.md 65 |
| SW-3 project cost reversal | `projects/application/ProjectCostService.java:450` (receive at `rate` from the first issue row's location) | `0515bfc3` | existing project ITs | existing project ITs | — | L2 then L3–L6 | inventory.md 65 |
| SW-5 bill line on bill receipts | `StockService.java:160` (`receiveFromBill` dropped `sourceLineId`) | `732f74f8` | `IprSerialRestockIT.billReceiptKeepsSourceLine` | — | — | none | inventory.md 65 |
| F6 engine helper | (new) `StockReversalService.issueBackReceipt` | `732f74f8` | `IprSerialRestockIT.issueBackReceipt` | caller (vendor-bill cancel) deferred | integrity + GL (test posts the caller's journal) | L2 receipt rows FOR UPDATE ORDER BY id, then L3–L6 per issue (pinned lot) | — |
| unlinked_restocks can fail | `inventory/application/InventoryIntegrityService.java:86, 861` (informational, always ok) | `4d3fdb7d` | `IprSerialRestockIT.unlinkedRestockAfterLinkFails`; `InventoryIntegrityP0IT.unlinkedRestocks` stays ok (no linked row in its org) | — | the check itself | read-only | inventory.md 65 |
| GL-7 residue sweep skipped on skip-GL moves (stream F) | `StockService.java:924` (issue: `residueAt != null && !dto.skipGlPosting`), `:1488` (transfer: same skip) | `732f74f8` | `IprSerialRestockIT.residueSweptWhenCallerPostsJournal` | skip-GL callers checked (below) | integrity (valuation_vs_subledger) + GL tie-out | none new (in the move's transaction, under the L4/L5 already held) | inventory.md 66 |
| R2 races | — | — | `IprSerialRestockIT.parallelSameSerial`, `parallelLastUnit`, `parallelReserveAndIssue`; `IprPosRefundRestockIT.refundRacesOrderCancel` | — | integrity + GL after each | as above; 40P01 fails the test (`race`) | — |

## Verified claim: refund + cancel / void double restock

- Refund + order cancel cannot overlap: the order cancel guard `notBilled` (`PosWorkflowsInitializer`) refuses a
  billed order, and a refund needs the invoice. `refundRacesOrderCancel` races them: the cancel is refused, the stock
  comes back once.
- Void + refund **did** double-restock: an order issued at order time (3 units), one unit voided (`voidItem` returns 1,
  linked), then billed for 2 and refunded: the old restock took the gross issued quantity (3) × 2 / 2 = 3. Now the
  refund returns what the line still has out (2), linked to the issue rows.

## GL-7: why dropping the skip does not double-post

The sweep writes a value-only `cost_adjustment` row (−residue) and its own `COGS_CORRECTION` journal, posting key
`inventory:cost_correction:<org>:residue:<code>`. A skip-GL caller posts its own journal for **the issue row's value
only**: fleet fuel (`FleetFuelPostingService:103`), tyres (`FleetTyreService:526`), work-order parts, quarry explosives
and stockpiles, projects, quality downgrade (`QualityDecisionService:209`), debit notes / supplier claims, production
and stage execution (`StageExecutionService:1240, 1291`), process costing all use `Issued.totalValue()`; the branch
transfer (`DistributionCostingService:358`) reads the `transfer_in` row by type. None sums value-only rows by its
reference, so none includes the residue (which only exists after the issue, as Σ ledger value beyond it). The sweep
journal is therefore the only posting of the residue, and ledger and GL both reach zero with the quant.
`StageExecutionService:1091` counts ledger rows by its posting id as an idempotency guard (> 0); the extra value-only
row does not change that answer. POS sale issues do not skip the GL. No conflict found, so the skip was dropped in
`issue` and in `transfer` (the same skip, source quant).

## Caller classes (E-1a defaults)

| Caller | Honours sales-order reservations |
|---|---|
| Stock-out approve, immediate transfer, dispatch, direct stock-out | yes (refused) |
| Project issue, quarry explosive issue, fleet WO part issue | yes (refused) |
| POS sale drain (4 issue sites) | only where its availability rule refuses (block policy, requireAvailable, lot block) |
| Waste, adjustments, counts, reversals, returns | no (physical event / undo) |
| Production consume, sales-order delivery, fleet dispatch | not wired (other streams' files): `StockMove.honourReservations(holderIds)` |

## Deferred call sites (not done here) and the helper each uses

- F6 / SW-2 vendor-bill cancel → `StockReversalService.issueBackReceipt` (pinned to the bill's own lot rows, on-hand
  check, shortfall message with `shortfallLabel` / `shortfallAdvice`; the caller posts the journal from `totalValue`).
- GRN / PO receive and bill auto-receive `sourceLineId` → `StockMove.sourceLine(lineId)` on `receiveForDocument` /
  `receiveFromBill` (the engine now stores it on both).
- SW-4 production reverse `.reverses` and production `sourceLineId` → `returnFromSource` / `StockMove.reverses` and
  `sourceLine`.
- Fleet `setSerials` call-site check → the engine's C-2 check in `issue` / `transfer` already refuses more serials than
  units.
- Production consumption reservation check → `StockMove.honourReservations(holderIds)`.
- Stream B `InvoiceGoodsService` → public `StockService.returnBaseQuantity`.

## Report-only checks for the new scenarios

The new ITs assert `assertIntegrityGreen` (all checks, `feature_risk` / `negative_quants` excepted where the org
setting is changed) after every scenario, so `issued_serial_located`, `over_returned` and `unlinked_restocks` are 0
for them.

## Test runs

Tests: new ITs in commit `8dc5760d`. Run 1 (green): `spotlessApply fastTest` on a freshly reset DB
(`erp-itdb-ipr0`), 39 classes, **343 tests, 343 ok, 0 failed** (`BUILD SUCCESSFUL in 16m 5s`):

| Class | Tests | OK | Failed |
|---|---|---|---|
| `inventory.audit.IprPosRefundRestockIT` | 3 | 3 | 0 |
| `inventory.audit.IprSerialRestockIT` | 16 | 16 | 0 |
| `core.ProductVariantIT` | 10 | 10 | 0 |
| `costing.DistributionBranchScenarioIT` | 2 | 2 | 0 |
| `fleet.FleetCostingIT` | 8 | 8 | 0 |
| `fleet.FleetMaintenanceIT` | 8 | 8 | 0 |
| `inventory.CountCutoffIT` | 10 | 10 | 0 |
| `inventory.InventoryEngineGapsIT` | 13 | 13 | 0 |
| `inventory.InventoryEngineHardeningIT` | 13 | 13 | 0 |
| `inventory.InventoryIT` | 21 | 21 | 0 |
| `inventory.InventoryIntegrityIT` | 12 | 12 | 0 |
| `inventory.InventoryIntegrityP0IT` | 16 | 16 | 0 |
| `inventory.InventoryLedgerImmutabilityTest` | 2 | 2 | 0 |
| `inventory.InventoryReturnsIdentityIT` | 8 | 8 | 0 |
| `inventory.NegativeStockPolicyIT` | 8 | 8 | 0 |
| `inventory.StockConcurrencyIT` | 6 | 6 | 0 |
| `inventory.StockEffectiveTimeIT` | 8 | 8 | 0 |
| `inventory.StockReservationOverviewIT` | 3 | 3 | 0 |
| `inventory.audit.InventoryAuditConcurrencyIT` | 8 | 8 | 0 |
| `inventory.audit.InventoryAuditConservationIT` | 13 | 13 | 0 |
| `inventory.audit.InventoryAuditRemediationIT` | 13 | 13 | 0 |
| `inventory.audit.IprStockControlsIT` | 9 | 9 | 0 |
| `manufacturing.ProductionMaterialsOnHandIT` | 1 | 1 | 0 |
| `manufacturing.StageIntegrityIT` | 7 | 7 | 0 |
| `plm.PlmErpIntegrationIT` | 4 | 4 | 0 |
| `pos.PosFlows2IT` | 5 | 5 | 0 |
| `pos.PosMoneyIT` | 8 | 8 | 0 |
| `pos.PosStockPostingIT` | 10 | 10 | 0 |
| `pos.PosVariantIntegrityIT` | 16 | 16 | 0 |
| `pos.SalesReturnsIT` | 3 | 3 | 0 |
| `procurement.ReadinessBlockersIT` | 9 | 9 | 0 |
| `projects.ProjectCostScenarioIT` | 2 | 2 | 0 |
| `quality.QualityLotsIT` | 7 | 7 | 0 |
| `qualitycontrol.QualityControlFlowIT` | 14 | 14 | 0 |
| `quarry.QuarryIT` | 9 | 9 | 0 |
| `rental.RentalIT` | 10 | 10 | 0 |
| `rental.RentalLifecycleIT` | 8 | 8 | 0 |
| `repair.RepairIT` | 11 | 11 | 0 |
| `sync.SyncInventoryIT` | 9 | 9 | 0 |

Run 2 (green, second freshly reset DB): `test --tests '*ArchitectureTests' --tests '*ModularityTests'` plus 12 more
fastTest classes, **108 tests, 108 ok, 0 failed** (`BUILD SUCCESSFUL in 11m 1s`):

| Class | Tests | OK | Failed |
|---|---|---|---|
| `ArchitectureTests` | 5 | 5 | 0 |
| `ModularityTests` | 2 | 2 | 0 |
| `fleet.FleetDispatchIT` | 12 | 12 | 0 |
| `integrity.IntegrityI2IT` | 11 | 11 | 0 |
| `inventory.InventoryBranchScopeIT` | 7 | 7 | 0 |
| `inventory.WarehouseIT` | 11 | 11 | 0 |
| `manufacturing.MultiStageProductionIT` | 12 | 12 | 0 |
| `manufacturing.Phase2ExitIT` | 1 | 1 | 0 |
| `manufacturing.ProcessingIT` | 19 | 19 | 0 |
| `manufacturing.ProductionReversalOverheadIT` | 1 | 1 | 0 |
| `plm.PlmIT` | 8 | 8 | 0 |
| `pos.PosStockPostingRecoveryIT` | 2 | 2 | 0 |
| `qualitycontrol.QualityControlIT` | 10 | 10 | 0 |
| `sync.SyncInventoryReplayIT` | 7 | 7 | 0 |

The full build (`./gradlew build` / `check` / bare `test`) was not run (CLAUDE.md build rule).
