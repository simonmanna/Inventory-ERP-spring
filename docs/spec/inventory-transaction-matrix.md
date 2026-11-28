# Inventory cross-module transaction matrix

| | |
|---|---|
| Status | Phase 0 deliverable (IPR plan task T03). Describes the code as it is; it changes no behaviour. |
| Code | `main` 0e670f12 (live). Every row was checked against the calling method in a read-only review (four parallel module reviews; the key claims were re-read by the author). Line numbers are for that commit. |
| Seed | The 2026-11-27 call-site sweep. Rows it got wrong are corrected here and listed in section 4. |
| Companion | `docs/audits/2026-11-27-ipr-lock-hierarchy-audit.md` (T04b) gives the lock order of each row. |

Paths are relative to `backend/src/main/java/com/inventoryerp/` unless they start with `backend/` or `docs/`.
Test classes are under `backend/src/test/java/com/inventoryerp/`.

## 1. Buckets

The plan defines four quantity buckets. This section says how the code implements each one and where it differs.

| Bucket | Definition in the plan | As implemented | Where reality differs |
|---|---|---|---|
| **On-hand** | Physical quantity per cell | `StockItem.quantity` per (product, variantKey, location), written only by `StockService` under L4 + L5 (`lockQuant` :1564). Lots (`InventoryBatch`) and serials (`InventorySerial`) are sub-ledgers of it. | On-hand includes every location type: transit, quarantine, rental and production locations hold ordinary `StockItem` rows. Item lists, stock levels and the valuation do not filter by location type (`InventoryQueryService`, `accounting/application/InventoryValuationService`). |
| **Saleable on-hand** | On-hand excluding virtual quarantine and transit locations | Only sales-order ATP filters: `SalesAtpService.atp` :133 excludes location types `virtual`, `production` and `transit`. | (a) The generic ATP `StockReservationService.availableToPromise` :229 does **not** filter by type; with no `locationId` (`GET /inventory/reservations/atp`) it sums all locations, transit and quarantine included. (b) No issue path checks the location type: a manual stock-out, waste, transfer or direct-out can take stock from a quarantine or rental location (audit X-3). (c) A sales-return "quarantine" disposition moves goods to any location the user picks (`SalesReturnService.inspect` :334-341), which may be an ordinary warehouse; `SalesSettings.rejectedQuarantineLocationId` can also name an ordinary location (`SalesDeliverySource.rejectedGoodsLocation` :84-92). (d) A quality "hold" without stock control moves nothing (audit X-2), so held stock stays saleable. |
| **Reserved** | Soft reservations | `StockReservation` rows with `status = 'active'` (`StockReservationService`). Reserving takes L3 + L4 + L5 (`reserve` :70-76); release and consume are plain updates (`close` :185). | Reservations are soft everywhere except one case: `StockService.issue` only refuses stock reserved for `processing_job_input` (`assertNotReservedForProcessing` :905-920). Sales-order reservations are enforced only at fleet dispatch (`SalesDeliverySource.assertIssuable` :58-82; audit F5). Manual routes accept any `sourceType` (audit C-5 / S-7). Repair "reserve" creates no reservation (audit X-11). |
| **In-transit** | Stock in a transit location | Transit transfers move stock to the organisation's `transit` location (`StockDocService.ensureTransitLocation` :895-917, created in its own transaction), and `StockTransferItem` keeps the dispatched / received / lost quantities. The integrity check `transit` compares Σ open transfer quantity with the transit location's quantity (`InventoryIntegrityService` :463-470). | Only the inventory transit transfer uses it. Fleet trips issue stock out of the warehouse at dispatch (no transit bucket; goods on a truck are already off on-hand). Branch transfers in `DistributionCostingService` move straight to the destination. Sync pull hides transit locations from devices (`SyncPullService` :353). |
| **Quarantine** | QC / sales quarantine virtual locations | `QC-QUARANTINE` (`VirtualLocationService.QC_QUARANTINE`, type `virtual`, created on first use in its own transaction, `ensure` :40-58) is shared by manufacturing QC, quality lots, quality control and PLM. `SALES-QUARANTINE` (type `virtual`) is the default for rejected deliveries. Rental `RENT-*` locations are also `virtual`. | Quarantine is a location, not a status. Nothing stops a manual move out of it (audit X-3), and `QcCheck.quarantinedQty` is not updated when that happens. Sales-return quarantine and a configured sales quarantine location may be ordinary locations (see saleable on-hand). |

**Ledger time.** `InventoryLedger.createdAt` is the server time of the insert (`clock_timestamp()`, `StockService`
:1758-1760). There is no effective-time column. The business time of each event exists only on the source document
(column "Time" below), and it reaches the ledger only through the journal date, never the ledger row.

## 2. Engine primitives

Every row below ends in one of these calls. Their behaviour is fixed by the engine, so the rows do not repeat it.
"Period check" is the date the accounting-period guard tests. All line numbers are `inventory/application/StockService.java`
unless stated.

| Primitive | Period check | Ledger | Engine GL and date | Notes |
|---|---|---|---|---|
| `issue` :621 | `dto.date ?: now` :623 | one row per lot / serial; keeps `reverses` and `sourceLine` from the move | `postIssue` by move type (default STOCK_OUT), dated `dto.date ?: now`, unless `skipGl` or value 0 (:865). Key `inventory:issue:<org>:<ledgerCode>`: the ledger code is new per call, so the key never matches a retry. | Refuses stock reserved for `processing_job_input` only (`assertNotReservedForProcessing` :905). `pos*` / `menu_recipe` sources bypass `allowNegativeStock=false` (:1653). |
| `receive` :146 (bare) | **now** (`receiveCore` :270 ignores `dto.date`) | yes | none (only a negative-stock cost correction, :467) | The caller must post its own journal. |
| `receiveForDocument` :152 | `glCtx.date` | yes | `postReceipt` (Dr stock / Cr GRNI), or `postAdjustment` for `stockGain`; STANDARD posts PPV. No key. Dated `glCtx.date`. | |
| `receiveFromBill` :158 | bill date | **rebuilds the move :159-161 and drops `sourceLineId`, `reverses` and the source fields** | receipt to GRNI, dated bill `issueDate` | Only caller: `InvoicingWorkflowsInitializer.postBill` :628. |
| `receiveReturn` :170 | `dto.date ?: now` | yes; keeps `reverses`, `sourceLine`, lot | `postReturnRestock` (Dr stock / Cr COGS), no key, dated `dto.date ?: now` | |
| `adjust` :996 | **now** :998 | yes | `postAdjustment` gain / loss (opening → retained earnings), no key, dated **now** (:1051, :1137) | `dto.date` is ignored. |
| `transfer` :1153 | **now** :1155 | out + in rows and `InventoryTransferLineage` | `postTransfer`, no key, dated **now** (:1380) | Always refuses a short source, whatever the negative-stock policy (:1198-1201). |
| `StockReversalService.reverseLedgerSource` (RLS) :363 | per inner move | inverse rows with `.reverses`; `sourceLine` copied | Mirrors each source journal dated **now**, key `inventory:reversal:<org>:<jeId>` (:485-488). A source journal of another `sourceType` is **not** mirrored. | Strict (`requireAvailable`) only when the caller passes a shortfall label (:437-462). Moves sorted (:409). No L2 lock. |
| `StockReversalService.returnFromSource` (RFS) :573 | `p.date ?: now` | `.reverses(row)`, original lot / serial / cost; `.sourceLine` only when the caller passes lines with a sourceLineId | `receiveReturn` GL when `postRestockGl`, else bare `receive` + STANDARD return variance | L2 lock :579; refuses "Cannot return more than was issued" :632; moves sorted :638. |
| `StockReservationService.reserve` :70 / `close` :185 | – | – | – | Reservations are soft (see bucket table). |

Abbreviations used in the tables:
- **Eng** = the engine posts the journal. **Caller** = the calling service posts its own journal (the engine call has `skipGl`, or is a bare `receive`).
- **SLI** = `InventoryLedger.sourceLineId` is set (Y / N).
- **Reversal link**: `reverses` = `reversesLedgerId` set; `pin` = exact lot / serial / cost pinned; `prefer` = preferred lots, with fallback; `none`.
- **Buckets**: OH = on-hand, R = reserved, T = in-transit, Q = quarantine. `+` / `−` is the change; `–` is no change.
- **Guard** = what stops a double post. `@Idem` = `@Idempotent` on the route (same key only). `claim` = atomic status `UPDATE … WHERE status = …`.
- **adv(order)** = `MfgDb.lockProductionOrder` :224, an advisory lock `production:<org>:<id>`; the order row is not locked.
- **Time** = the business timestamp on the document, and whether the posting uses it.
- Finding ids: F1–F11 (procurement / sales audit), X-1..X-11 (POS / sync / manufacturing), C-1..C-9 (engine),
  S1–S14 (stock documents), GL-1..GL-11 (GL), S-1..S-10 (security). New gaps are M-1..M-35 (section 5.5).
  Lock findings LK-n are in the lock-hierarchy audit.

## 3. The matrix, by module

Columns, in order: ID · event · class.method (file:line) · engine call · buckets · GL (rule and date) · source ref
(referenceType ; SLI) · reversal link · double-post guard · cancel and partial · time · covering IT · findings.

### 3.1 POS (`pos/application/PosInvoiceService.java` unless stated)

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| POS-1 | Sale, product line (stock job) | `issueLine` :2155 | `issue`, till location (:1960-1978) | OH − at till; R consumed later (POS-5) | Eng STOCK_OUT · `datedForStock` = `Invoice.issueDate`, else `Order.createdAt`, clamped by `openDateOrToday` (:1890-1911) | `pos_invoice`, or `pos_order` before an invoice exists (:2151) ; **Y, but the OrderItem id** (:2153) | none | Job claim (worker `UPDATE … FOR UPDATE SKIP LOCKED`, `StockPostingWorker` :58-70; drains `claim()` :1827-1834), then `postOnce`: job row FOR UPDATE + `postedLineKeys` (:2056-2066) | Order cancel / void (ORD-1/2); refund (POS-6) | Invoice `issueDate` (used) | `PosStockPostingIT.cogsDatedAtSale`, `.cogsOfClosedPeriodSalePostsToday`, `.reclaimRace`; `PosVariantIntegrityIT` | X-8; M-2, M-4 |
| POS-2 | Sale, combo line | :2126, sorted :2110 | `issue` per component | as POS-1 | as POS-1 | `pos_invoice_combo`, sourceId `<orderItem>:<comboItem>` ; Y (OrderItem) | none | as POS-1 | as POS-1 | as POS-1 | `PosStockPostingIT.recipeSnapshotAndDrain` | X-8 |
| POS-3 | Sale, recipe line | `issueMenuItemRecipe` :2272, sorted :2257 | `issue` per ingredient | as POS-1 | as POS-1 | `menu_recipe` ; Y (OrderItem) | none | as POS-1 | as POS-1 | as POS-1 | `PosStockPostingIT.recipeSnapshotAndDrain`, `.recipeLessMenuItem` | X-8 |
| POS-4 | Sale, extras | `moveExtra` :2353 | `issue`; own `postOnce` per `<orderItem>:<component>` | as POS-1 | as POS-1 | `pos_invoice_extra` ; Y (OrderItem) | none | as POS-1 | as POS-1 | as POS-1 | `PosVariantIntegrityIT` | X-8 |
| POS-5 | Sale reservations | `reserveForInvoice` → `reserve` :1581 (after the invoice commits, :620; skipped if the job is done, :1532); `consume` :1794; `release` :1812 | reservation writes | R + then − | – | `invoice` | – | none: reserve after commit, consume outside the completion transaction | release when the job is exhausted | – | **none** | M-3 |
| POS-6 | Refund restock, product line | `refundAs` (tx :1146) → `restockLine` → `receiveReturn` :1437 | `receiveReturn` at the sale's ledger location; refuses an ambiguous location and lot / serial products (:1418-1427). Qty and cost pass through `double` (:1431-1433) | OH + | Eng `postReturnRestock` · **now**. Refund revenue JE also now (:1263, key `pos_refund:<id>`) | `pos_refund` ; **N** | **none** | CashSession FOR UPDATE :1149 → Invoice FOR UPDATE :1152 + `refundedQty` check :1191-1194 + unlocked pending-job read :1320-1324 | Partial by quantity; no undo | refund time | `PosVariantIntegrityIT.variantSaleIssuesAndRestocksTheVariant`, `.legacyVariantRefundRestocksWhereIssued`, `.paymentTimedPostingAndRefund` | X-6; M-2, M-6; LK-5, LK-10 |
| POS-7 | Refund restock, recipe / combo / extra | :1393 per ingredient snapshot | `receiveReturn` at the **re-resolved** till location (:1366), not the issue location; `double` (:1382-1389) | OH + | Eng · now | `pos_refund` ; N | **none** | as POS-6 | as POS-6 | refund time | **none** | X-5; M-2, M-6; LK-5 |
| POS-8 | Job enqueue (no stock) | `enqueueStockPosting` :352-356; `attachInvoiceToOrderJob` :381; from `generateInvoice` :605/:608 and `PosEventSubscribers` :48/:63 | – | – | – | – | – | key `sale:<order|invoice>` + duplicate-key catch | – | – | `OrderCancelStockIT.oneJobPerSale` | X-8 |

### 3.2 POS orders and kitchen

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORD-1 | Order cancel | `OrderStockReversalService.onOrderCancelled` :78 (workflow side effect, `PosWorkflowsInitializer` :71, before the status update `WorkflowService` :152) | RFS, lines null, `postRestockGl` | OH + at the original cells, lot, cost | Eng restock · now | `pos_order_cancel` ; N | reverses | CashSession → Order FOR UPDATE (`PosOrdersService` :1323/:1331) + workflow `lockStatus` :118 + jobs ORDER BY id FOR UPDATE :165 + L2 net-returnable | Reopen re-pends the jobs (ORD-3) | now | `OrderCancelStockIT.postedIsReturnedAtOriginalCostAndLot`, `.processingJobIsWaitedFor`, `.partiallyPostedReturnsIssuedLinesOnly`, `.workerSkipsCancelledOrder` | – |
| ORD-2 | Item void | `onItemVoided` :139 | RFS with `ReturnLine.of(product, variant, share)` (no sourceLineId), or null for a whole line | OH + | Eng · now | `pos_order_void` ; N | reverses | as ORD-1 (`voidItem` `PosOrdersService` :512) | partial by share | now | `OrderCancelStockIT.kitchenVoidedLineIssuedOnce`; `PosMoneyIT` | – |
| ORD-3 | Order reopen | `onOrderReopened` :153 (via `PosOrdersService.reopenOrder` :600) | none directly: resets `postedLineKeys`; the jobs issue everything again | – then OH − | as POS-1 | as POS-1 | – | as ORD-1 | – | – | `OrderCancelStockIT.reopenedOrderIssuesAgain` | – |
| KW-1 | Kitchen waste after cancel / void | `KitchenWaste.record` → `issue` waste :109, **REQUIRES_NEW** per line (:46/:98), after the order commits (`PosOrdersService` :498/:567); components unsorted (:146-149) | `issue` waste | OH − at register location | Eng WASTE · now | `pos_kitchen_waste`, sourceId `<item>:<voidedQty>` ; N | none | unlocked ledger-exists check :75-80, outside the transaction | none | now | `OrderCancelStockIT.kitchenVoidedLineIssuedOnce` | X-10; M-5; LK-5 |
| KW-2 | Device tab void | `recordDeviceVoid` → `issue` waste :223, key `device:<opId>:<i>` (:195-200); sync op `kitchen.void` (`SyncOpCatalog` :69) | `issue` waste | OH − | Eng WASTE · now | `pos_kitchen_waste` ; N | none | op id + unlocked check | none | now (device time ignored) | `SyncDeviceIntegrityIT.kitchenVoidWastesStartedFood` | X-10; M-1, M-5 |

### 3.3 Sync (`sync/application/SyncPushService.java`, `SyncStockService.java`)

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SYN-1 | `stock.in` / `stock.out` | `SyncPushService` :1102 → INV-12 / INV-13 | as INV-12 / INV-13 | OH ± | Eng · **now** | `direct_stock_in` / `_out` ; N | none | op id (`executeWithKey` :450-452) | none | device `occurredAt` **ignored** | `SyncInventoryIT.stockIn`, `.negativeFlaggedWhenAllowed` | X-4; M-1 |
| SYN-2 | `stock.count` | `SyncPushService` :1116 → `counts.start` :1530, `saveDraft` :1573, `submit` :1574 → INV-4 | `adjust` | OH ± | Eng · now | `stock_adjustment` ; N | none | session claim (INV-4) | – | counted = device count + `movedSince` (ledger `createdAt > occurredAt`, `SyncStockService` :385-393) | `SyncInventoryIT.countMeasuredWhenCounted` | X-1, S4; LK-10, LK-12 |
| SYN-3 | `transfer.dispatch` | `SyncStockService.dispatch` :115 → approve + dispatch (INV-6), separate transactions; a refusal is held (:458-473) | `transfer` | OH − source, T + | Eng · now | `stock_transfer` ; N | none | INV-6 guard | INV-9 | `occurredAt` ignored | `SyncInventoryIT.dispatchThenReceive` | M-1 |
| SYN-4 | `transfer.receive` | `receiveTransfer` :183; early receipt → AWAITING exception; `applyAwaiting` → `applyOne` :261; also `SyncStockJob.tick` :44 | as INV-7 | T −, OH + | Eng · now | `stock_transfer` ; N | pin (transit) | exception row FOR UPDATE SKIP LOCKED + key `offline-receipt:<exId>` | partial | `occurredAt` ignored | `SyncInventoryIT.receiptWaitsForDispatch`; `SyncInventoryRecoveryIT.concurrentApplyOnce`, `.crashBetweenReceiptAndResolve` | S3; M-1 |
| SYN-5 | `stock.adjust` | `createAdjustment` :317, pending only | none until approved (INV-3) | – | – | – | – | op id | – | counted = on-hand today + change | `SyncInventoryIT.adjustmentWaitsForApproval` | M-1 |
| SYN-6 | `grn.receive` | `SyncStockService.receiveGoods` :337 → `createDraft` :346 + `post` :355 | as PRC-1 | OH + | as PRC-1, dated device `occurredAt` (:343) | `goods_receipt` ; N | none | op ledger + PRC-1 claim | PRC-3 | `occurredAt` (used) | `SyncInventoryIT.goodsReceiptPosts` | LK-1 |
| SYN-7 | Offline refund | `drainSaleStockPostings` :1606, then `refund` (POS-6) or `refundWithHeldStock` + `holdRefundRestock` (`SyncStockService` :252-268, exception row only) | as POS-6, or none (held) | OH + or – | as POS-6 | `pos_refund` ; N | none | op id | – | now | `SyncInventoryReplayIT`; `SyncInventoryIT`, `SyncInventoryRecoveryIT` (held) | X-6; M-2 |
| SYN-8 | Stock job drains | `drainStockPostings` :1592, `drainSaleStockPostings` :1606 (no outer transaction, `ledger.outside` :801/:878) | as POS-1..4 | – | – | – | – | `claim()` | – | – | as POS-1 | – |

### 3.4 Beverage

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BEV-1 | Bottle count submit | `beverage/application/BeverageCountService.submit` → `createAdjustment(embedded)` :477 + `approveAdjustment(embedded)` :480 → INV-3 | `adjust` per line (ml) | OH ± | Eng ADJUSTMENT_GAIN / LOSS · now | `stock_adjustment` ; N | none | BottleCountSession FOR UPDATE + draft (:447-452), then the adjustment claim | RLS via INV-11 | now; basis `qtySystem` read unlocked (`StockDocService` :577) | `BeverageIT` | X-1(c) |

### 3.5 Inventory documents, reversal, direct stock, warehouse (`inventory/application/StockDocService.java` unless stated)

None of the document rows set SLI at post time, although every document has line ids.

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| INV-1 | Stock-out approve | `issue` :243; move type from category (sample → `promo_sample`, damaged → `waste`, expired → `expiry_write_off`, …) | `issue` | OH − | Eng by move type · now | `stock_out` ; N | none | claim pending/draft → approved where `postedAt` null (:229, :1379-1383); approval REQUIRES_NEW before the claim (:130) | no cancel route (S8); reverse INV-11 | now | `InventoryIT`, `InventoryEngineGapsIT` | S2, S8, S9 |
| INV-2 | Waste approve | `issue` :342 (MANUAL when a batch is named) | `issue` waste / `expiry_write_off` | OH − | Eng WASTE · now | `waste` ; N | none | claim :329 | `cancelWaste` before post (:355); INV-11 after | now | `InventoryIT` | S2, S9, S13 |
| INV-3 | Adjustment approve | `adjust` :677 | `adjust` delta | OH ± | Eng ADJUSTMENT · now | `stock_adjustment` ; N | none | claim :630 | `cancelAdjustment`; INV-11 | basis = on-hand read unlocked (:640-645) or embedded `qtySystem` | `InventoryIT` | S6, C-4 |
| INV-4 | Count post | `InventoryCountService` submit → `lockQuants` :703 → session claim :720 → `postCountVariances` → `adjust` :758 | `adjust` (opening counts → retained earnings) | OH ± | Eng · now | `stock_adjustment` ; N | none | session claim :720, taken **after** `lockQuants` | none for the session; adjustment reversible (S10) | count time used for measuring only | `InventoryIT.counts`, `.countsAgainstCountTime`; `InventoryEngineHardeningIT.countSubmitTakesCostingLockFirst`, `.countAdjustsTheCountedLot` | S1, S4, S7, S10, X-1; LK-10 |
| INV-5 | Immediate transfer approve | `transfer` :872 | `transfer` | OH − source / + dest | Eng · now | `stock_transfer` ; N | none | claim :858 | `cancelTransfer` :1144 (claim, no stock); INV-10 | now | `StockConcurrencyIT.oppositeOrderDocuments` | – |
| INV-6 | Transit dispatch | `ensureTransitLocation` (REQUIRES_NEW, :958, outside the tx) → `transfer` source → transit :972; lots / cost into `StockTransferLayer` | `transfer` | OH −, T + | Eng · now | `stock_transfer` ; N | MANUAL batch only | StockTransfer FOR UPDATE :930 + approved and `dispatchedAt` null :961 | recall INV-9; reverse INV-10 | now | `InventoryIT.transit` | – |
| INV-7 | Transit receive, received | `transfer` transit → dest :1049, pinned (`pinTransit`) | `transfer` | T −, OH + | Eng · now | `stock_transfer` ; N | pin | FOR UPDATE + outstanding check :1016 | partial | now | `InventoryIT.transit`; `StockConcurrencyIT.transitMovesOwnLots` | S3 |
| INV-8 | Transit receive, damaged / short | damaged: `issue` waste, pinned, `requireAvailable` :1055; short: `issue` `adjustment_out` skipGl :1061 + caller `postAdjustment` :1066 | `issue` | T − | damaged Eng WASTE · now; short **Caller** · now, **no key** | `stock_transfer` ; N | pin | as INV-7 | partial | now | `InventoryIT.transit` | S3, S13 |
| INV-9 | Transit recall | `transfer` transit → source :1113, pinned | `transfer` | T −, OH + | Eng · now | `stock_transfer` ; N | pin | FOR UPDATE + outstanding | partial | now | `InventoryIT`, `NegativeStockPolicyIT` | – |
| INV-10 | Reverse transfer document | `StockReversalService.reverseDocument` :145: immediate `transfer` back with `prefer` (:259); transit `transfer` :324 + RLS of losses :329 + pinned `transfer` :348; all cells pre-locked `lockCells` :280 | `transfer`, RLS | OH / T inverse | Eng · now; RLS mirrors now | `stock_transfer_reversal` ; N | prefer / pin / reverses | header FOR UPDATE :150 + claim :166 + open pick-order check :190 | whole document | now | `InventoryEngineHardeningIT.transferReversalReturnsTransferredLot`; `GenericReversalAllowlistIT` | S10 |
| INV-11 | Reverse stock-out / waste / adjustment | `reverseDocument` → RLS :180 | RLS | inverse | journals mirrored · **now**, key `inventory:reversal:<org>:<jeId>` | `<kind>_reversal` ; copied | reverses | as INV-10 | whole document | now | `GenericReversalAllowlistIT`; `InventoryIT` | S10 |
| INV-12 | Direct stock in | `DirectStockService.directIn` :122 | `receiveForDocument(stockGain(…, Instant.now()))` | OH + | Eng Dr stock / Cr adjustment income · now | `direct_stock_in` ; N | none | `@Idem(required)` (`InventoryController` :272); sync op id | **no document row, no reversal path** | now | `InventoryAuditConcurrencyIT`, `InventoryBranchScopeIT`, `SyncInventoryIT.stockIn` | S-4, X-4 |
| INV-13 | Direct stock out | `directOut` :161 | `issue` FEFO, `requireAvailable(!allowNegative)` | OH − | Eng STOCK_OUT · now | `direct_stock_out` ; N | none | `@Idem(required)` (:279); op id | none | now | as INV-12 | S-4, X-4, X-3 |
| INV-14 | Manual pick-order ship | `warehouse/application/WarehouseOutboundService.issueShipped` :608 (MANUAL orders only, :563-568) | `issue` pinned by batchKey, `requireAvailable` | OH − | Eng STOCK_OUT · now | `WarehousePickOrder` ; N | pin | pick order FOR UPDATE (:107 via :423) + status; approval REQUIRES_NEW under that lock (:514) | no undo after ship | now | `WarehouseIT.manualShipIssuesStock` | S11; LK-12 |
| INV-15 | Bin writes (no engine) | `WarehouseStore.addStock` / `removeStock` :500-538 from `WarehouseTaskService` :344-363, `WarehouseOutboundService.cancel` :637 | none | bins only | – | – | – | – | – | – | – | S11 (bins drift from the ledger) |
| INV-16 | Manual reservation | `StockReservationController` → `reserveForUser` :53 / `releaseForUser` :61 / `consumeForUser` :68 | reservation writes | R ± | – | any `sourceType` | – | none | – | – | `InventoryIT.reservations`; `StockConcurrencyIT.reservationsAndGuards` | S14, C-5, S-7 |

### 3.6 Valuation and costing (value only; on-hand quantity unchanged)

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| VAL-1 | Revaluation post | `InventoryRevaluationService.post` :212; pools sorted :477; L3 + **L4 only** :495; `setUnitCost` :276 | value rows (`recordValueOnly` :307) | value | Caller, key `inventory:<src>:<org>:<id>`, dated `revaluationDate` at **UTC** start of day (:219, :299) | revaluation ; – | VAL-2 | doc FOR UPDATE :131 + draft | VAL-2 | `revaluationDate` (GL) | `CostingAuditIT.revaluation`, `.revaluationDown`; `ValuationIntegrityIT.revaluation` | GL-9, C-6 |
| VAL-2 | Revaluation reverse | :328 → `reversals.reverse` :397; value rows :408 | value rows | value | journal reversal | – | reverses JE | doc FOR UPDATE + posted | whole | now | `CostingAuditIT.reversalAfterTheCostMoved` | – |
| VAL-3 | Standard cost activate | `StandardCostService.activate` :472 (TransactionTemplate :473); `activateDue` :632 under ShedLock | value rows :586 | value | Caller, key `inventory:standard_cost:<org>:<id>` · now | standard_cost ; – | – | ProductStandardCost FOR UPDATE → product **exclusive** L3 :491 → Product FOR NO KEY UPDATE → `CostingLocks.lockQuants(advisory=false)` :550; status approved | – | now | `CostingAuditIT.standardActivation` | – |
| VAL-4 | Cost-price change revaluation | `StandardCostService.revalueCostPriceChange` :599 (before `ProductService` :253 flushes the Product row) | value rows | value | Caller, key ends in a uuid · now | – | – | product exclusive L3 | – | now | **none found** | – |
| VAL-5 | Cost policy activate | `InventoryCostPolicyService.activate`: policy FOR UPDATE :271 → `cost-policy` advisory :282 → org **exclusive** L3 :286 → `lockQuants(rows only)` :363 → `recordValueOnly` :452; `OPEN-` lot insert and StockItem / lot updates :470-497 | value rows | value | Caller, key `inventory:cost_policy:<org>:<code>` (:570) | cost_policy ; – | – | policy row + status | – | now | `InventoryCostPolicyIT` | – |
| VAL-6 | Replenishment | `ReplenishmentService.raiseTransfer` :668 | none (creates a pending INV-5) | – | – | – | – | – | – | – | `ReplenishmentMrpIT.transferRoute` | – |
| VAL-7 | Material-ledger run post | `costing/application/MaterialLedgerService.post` :228 → `valueRows` :270 (:312-345) | value rows spread over the product's quants; **no quant lock** | value | Caller GEN, key `material_ledger:<id>`, dated `periodTo` at **UTC** midnight (:266) | `material_ledger_run` ; – | reverse :281 | **plain status read :229-231; the UPDATE :272 has no `status = 'draft'`** | reverse | `periodTo` (GL), now (rows) | `AdvancedCostingIT.materialLedger` | M-23; LK-15 |

### 3.7 Procurement (`procurement/application/`)

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| PRC-1 | GRN post | `GoodsReceiptsService.post`: claim :482 → `applyReceiptToPO` :502 → supplier lock :549 → per line `inLockOrder` :551 → `receiveForDocument` :580 → QC `QcGate.onCompleted` :593 | `receiveForDocument(grni("goods_receipt", id, date))`; batch / expiry from line; cost × FX | OH + at GRN warehouse; Q + when a QC receipt point applies (PRC-9) | Eng Dr stock / Cr GRNI, no key. Caller (PO receipts): `postReceiptVoucher` :597 with **null key**, then `settleCashReceipt` :601. All dated `receivedAt` (:537) | `goods_receipt` ; **N** | none | claim draft → posted :482-489 + `@Idem(required)` (`GoodsReceiptsController` :54) | PRC-3 (whole receipt); draft cancel :914 | `receivedAt`, which **defaults to the draft's creation time** (:232) | `ProcurementIT.goodsReceipts`; `PurchaseReadinessReceiptsIT.productOnlyLineResolvesOrderLine`; `QualityControlFlowIT.quarantineOnReceipt`, `.receiptRollsBackWithQuarantine` | F8; M-17, M-18; LK-1, LK-2 |
| PRC-2 | One-shot PO receive | `PurchaseOrdersService.receive`: `applyReceiptToPO` :916 → supplier lock :928 → engine :957 in **input order** (:921) | as PRC-1 | as PRC-1 | Eng as PRC-1; voucher :969 key `receipt_voucher:goods_receipt:<grn>`; cash :974. Dated `dto.receivedAt ?: now` (:905) | `goods_receipt` ; N | none | `@Idem(required)` (`PurchaseOrdersController` :174); PO line / PO version UPDATEs (:580-602) | PRC-3 | `dto.receivedAt` | `ProcurementIT.creditPurchase`, `.cashPurchase`, `.receiveIdempotency` | F8; LK-1, LK-5 |
| PRC-3 | GRN reversal | `GoodsReceiptsService.reverse`: GRN FOR UPDATE :673 → lines :687 → PO FOR UPDATE :693 → claim :703 → QC `unwindForReversal` :716 → RLS :735 → PO lines :767 | QC leg `transfer` Q → warehouse (`QcQuarantineService.returnOnReversal` :139), then RLS **strict** | OH −; Q − | RLS mirrors every `goods_receipt` journal (receipt and voucher) · **now** | `goods_receipt_reversal` ; N | reverses | GRN FOR UPDATE + claim posted → reversed; `@Idem` optional (:76) | whole receipt; PO `receivedQuantity` rolled back :767 | now | `ProcurementIT.poLinkedReceiptReversal`; `PurchaseReadinessReceiptsIT.reversalNeedsGoodsOnHand`, `.concurrentIssueAndReversal`, `.reversalUnwindsQuarantine` | M-19; LK-2 |
| PRC-4 | Return to vendor (debit note) | `DebitNotesService.postSupplierReturn` :672, lines **unsorted**; `consumeOpenReceipts` locks GRN lines :776 between engine calls | `issue` `return_to_supplier`, skipGl, `requireAvailable`, **no lot pin**; location chosen, else first receipt's warehouse, else first warehouse | OH − | Caller Dr AP / GRNI, Cr stock, PPV, VAT, `posting.post` :708, key `debit_note_rtv:<id>`, dated `DebitNote.issueDate` | `debit_note` ; N | none | claim UPDATE … RETURNING :585-592 + `@Idem(required)` (:103) | PRC-5 | `issueDate` for GL only; move, period check and ledger at now | `ProcurementIT.returnToVendor`; `ProcurementIntegrityIT.debitNoteConcurrentPostIssuesStockOnce`; `PurchaseReadinessReturnsIT.concurrentReturnsOnePasses` | F7, GL-7, GL-8; M-20, M-21; LK-5 |
| PRC-5 | Debit-note reversal | `DebitNotesService.reverse` → journals reversed :971 → `bringBackReturnedStock` :1056 | RFS, `postRestockGl=false` (bare receive + STANDARD variance), no lines | OH + (original lot, location, cost) | Caller `posting.reverse` of every debit_note journal (:971): original date if open, else now | `debit_note_reversal` ; N | reverses | Document FOR UPDATE :949 + DebitNote FOR UPDATE + status :952-958 + ledger-exists :1043 + `@Idem(required)` (:122) | whole only | now (stock) / original (GL) | `ProcureToPayGapsIT.returnOfUnbilledGoods`; `PurchaseReadinessReturnsIT.doubleReturnAgainstBillRefused`; `DocumentReversalIT.debitNoteReversal` | LK-11 |
| PRC-6 | Supplier claim, stock adjustment | `SupplierClaimService.stockAdjustment` :942 (from settle :516), lines sorted by (product, variant) :883 | `issue` `return_to_supplier`, skipGl, `requireAvailable`; MANUAL only when the GRN line has a batch | OH − at GRN warehouse | Caller `posting.post` :566, key `supplier_claim:<id>`, dated **now** | `supplier_claim` ; **Y** (claim line) | pin (batch) | SupplierClaim FOR UPDATE :523 + status + `@Idem(required)` | **no reversal** (settled is terminal) | now | `SupplierClaimSettlementIT.stockAdjustmentLeavesStock`, `.stockAdjustmentAgainstOpenGrni` | GL-7; LK-17 |
| PRC-7 | Branch transfer | `costing/application/DistributionCostingService.transfer` :357 | `transfer` skipGl | OH − from / + to | Caller `posting.post` :389, key `costing:branch_transfer:<org>:<id>` · now; freight adjunct `capitalisation.record` :397 | `branch_transfer` ; N | none | `@Idem(required)` only (new id per call) | **no reversal or cancel** | now | `DistributionBranchScenarioIT.branchTransfer` | M-24 |
| PRC-8 | Project material issue | `projects/application/ProjectCostService.issue` :170 | `issue` skipGl, `.date(in.date)` (UTC start of day, else now); **no variant** | OH − | Caller `post` :744, key `projects:project_issue:<org>:<id>`, dated `in.date` | `project_issue` ; N | none | `@Idem(required)` only (`ProjectController` :286) | PRC-9 | `in.date` (used) | `ProjectCostScenarioIT.projectScenario` | GL-7 |
| PRC-9 | Project issue reversal | `ProjectCostService.reverse`: journal `reverseAt(now)` :441 → bare `receive` :449 | `receive` `reversal_in` at the issue average; no lot, serial or variant; first ledger row's location | OH + | Caller journal reversal · now | `project_issue_reversal` ; N | **none** | ProjectCostLine FOR UPDATE :432 + status + `@Idem(required)` (:320) | whole only | now | `ProjectCostScenarioIT.projectScenario` (:218) | M-25; LK-11 |

### 3.8 Landed cost and value-only writers

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LC-1 | Landed-cost voucher post | `LandedCostVoucherService.post`: GRN FOR SHARE :2064 → claim :570 → `lockPools` :593 → `LandedCostLayers.applyLineage` → `LineageFollower.record` / `raiseAverage`, lot rows :386, `bumpAverage` :844 | value rows (ledger type `cost_adjustment`) + StockItem / lot / serial cost | value | Caller `postLandedVoucher` :619, dated `LandedCost.date` | `landed_cost_voucher` ; – | LC-2 | claim approved → posted + GRN FOR SHARE + `@Idem(required)` | LC-2 | `LandedCost.date` (GL); value rows now | `LandedCostVoucherIT.laptopImport`; `LandedCostAuditIT.concurrentPosts` | M-22 |
| LC-2 | Voucher / legacy reversal | `reverse` :644 / `reverseReceipt` :691 → `lockAllocations` :735, `remove` :744, value rows :771/:775 or `reverseLineage` :800 → `lockReversal` :650 | value rows − | value | `reverseAt(now)`; reclass `postLandedReclass` now | `reversal` / `landed_cost_voucher_reversal` | reverses value | LandedCost FOR UPDATE :646 + status + `@Idem(required)` | – | now | `LandedCostAuditIT.reverseAfterTransfer`, `.reverseAfterPartialSale*` | – |
| LC-3 | Legacy historical landed cost | `LandedCostService.postHistorical` :304 (`lockPools`), :312 (`apply`) | as LC-1 | value | – | – | – | GRN line FOR UPDATE OF g :240 | – | – | **none** | – |
| LC-4 | Accrual clear / close / reverse | `LandedCostAccrualService` unwind :562 | value rows −, **no quant lock** | value | `post` :816 · now | `landed_cost_accrual_clearing` | – | accrual FOR UPDATE :726 | – | now | `LandedCostAccrualManagementIT.partialClears` | LK-15 |
| LC-5 | Capitalisation | `LineageCapitalisation.record` :170 / `reverse` :189; callers `LandedCostAccrualService` :340/:374/:605, `AbsorptionSettlementService` :463/:584/:628, PRC-7 :397, `QuarryCostingService`, `CostAdjustmentService` | `ADJUNCT` value rows, **no L3 / L4** | value | caller | per source | `reverse` | caller's row or advisory lock | – | now | **none** (only through callers) | LK-15 |
| LC-6 | Lineage follow, average raise | `inventory/application/LineageFollower.record` :318 → `raiseAverage` :406-422 (also from `LineageExceptionService.assign` :180, `closeParked` :283) | value row **before** L4; then L3 :409 → L4 :410 → StockItem FOR UPDATE :411 → UPDATE :420 | value | caller | – | – | caller | – | now | `LandedCostLineageIT` | LK-8 |

### 3.9 Quality, quality control and PLM

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| QL-1 | Quality lot quarantine | `quality/application/QualityLotService.quarantineStock` :211 (from `openLot` :177, `QualityDecisionService.quarantine` :94, and **post-commit** from GRN: `QualityIncomingService.openForGoodsReceipt` :71, one tx per line, errors logged and swallowed) | `transfer` location → QC-QUARANTINE | OH → Q | Eng · now | `quality_lot` ; N | none | `qlot` advisory or `lockLot` FOR UPDATE + status | QL-5 | now | `QualityLotsIT.incomingFailRejectDowngrade` | X-3; M-19 |
| QL-2 | Release | `QualityDecisionService.release` :129 | `transfer` Q → location, no `requireAvailable` | Q → OH | Eng · now | `quality_lot` ; N | none | `lockLot` FOR UPDATE + quantity cap; `@Idem` optional | partial; no undo | now | `QualityLotsIT.incomingPassRelease` | X-3 |
| QL-3 | Reject | `reject` :156 | `issue` waste / `return_to_supplier`, `requireAvailable`, MANUAL batch; **engine GL** | Q or OH − | Eng WASTE / RETURN_TO_SUPPLIER · now; **no debit note or AP credit** | `quality_lot` ; N | pin (batch) | `lockLot` + cap + `@Idem(required)` | partial; no undo | now | `QualityLotsIT.incomingFailRejectDowngrade`, `.contendedReject` | GL-4; M-35 |
| QL-4 | Downgrade | `downgrade` :207 (`issue` A) / :223 (`receive` B) | `issue` `adjustment_out` skipGl + bare `receive` `adjustment_in`, both `.date(now)`; A → B order | OH − A / + B | Caller `postDowngrade` :312, key `quality:downgrade:<org>:<decision>` · now | `quality_downgrade` ; N | pin (out) | `lockLot` + cap + `@Idem(required)` | partial; no undo | now | `QualityLotsIT.incomingFailRejectDowngrade` | GL-2; LK-5 |
| QL-5 | Lot cancel | `cancel` :313 | `transfer` Q → location | Q → OH | Eng · now | `quality_lot` ; N | none | `lockLot` + "no dispositions" | – | now | `QualityLotsIT.stageGateAndConcession` | – |
| QL-6 | Hold | `hold` :72-81 | **none** (status only) | – (held stock stays saleable) | – | – | – | `lockLot` | – | – | `QualityLotsIT.stageGateAndConcession` | X-2 |
| QC-1 | QC quarantine legs | `qualitycontrol/application/QcQuarantineService.transfer` :210 from `holdOnReceipt` :79, `releaseOnDecision` :113 (`QcCheckService` :420/:563), `returnOnReversal` :139, `quarantineFailure` :179 (`QcGate.onCompleted` :121; retry :194) | `transfer` | Q ± | Eng · now | `qc_check`, `<id>:receipt` / `:<rev>:release` / `:reversal` / `:failed` ; N | none | ledger-exists `moved(ref)` :214 under QcCheck FOR UPDATE or `qc:` advisory; on-hand pre-check :221 unlocked | – | now | `QualityControlFlowIT.quarantineOnReceipt`, `.productionOrderQuarantine`; `PurchaseReadinessReceiptsIT.reversalUnwindsQuarantine` | X-3; M-15; LK-5 |
| PLM-1 | ECO disposition | `plm/application/PlmDispositionService.execute` :195/:205 (also `cancelOrder` :295, `confirmOrder` :320 → MFG-1/MFG-10) | none directly: pending adjustment / transfer documents and a draft debit note | – until approved | – | – | – | – | – | – | `PlmIntegrityIT` | – |

### 3.10 Sales (`sales/application/`)

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SAL-1 | Cash sale confirm | `CashSaleService.confirm` :878 → `issue` :930, lines `inLockOrder` :926 | `issue` | OH − at `s.locationId` | Eng STOCK_OUT · `postDate` :924 = now if `saleDate` is today, else `saleDate` 00:00 **UTC**. Caller deposit JE :941, accrual :953 | `cash_sale` ; Y (line) | none | SalesSubmission FOR UPDATE :1687 + `requireDraft` :880 + `@Idem` (`CashSaleController` :151) | SAL-2; no partial | `saleDate` (used, UTC) | `CashSaleIT.cashSaleEndToEnd`, `.insufficientStockAndTotals` | F5; M-34 |
| SAL-2 | Cash sale void | `voidSale` :1465 → accrual reversal :1486 → RFS :1492 | RFS, lines null, `postRestockGl`, date null | OH + (original location, lot, serial) | Eng restock · now; Caller `posting.reverse` of the accrual | `cash_sale_void` ; N | reverses | FOR UPDATE :1483 + `confirmed`; the "invoiced" check reads the **pre-lock** row (:1477-1479) | whole sale | now | `CashSaleIT.voidSale`, `.voidLinksTheIssueRows` | M-28 |
| SAL-3 | Return receive (issue rows exist) | `SalesReturnService.receive` :212 → RFS :240 per line, **unsorted** (:227); SalesOrderLine projection after the engine (:260, :499) | RFS against `FleetLoadLine.stockLedgerCode` codes, chosen location, `postRestockGl` | OH + at receive location | Eng restock · now | `sales_return` ; Y (return line) | reverses | SalesReturn FOR UPDATE :505 + `approved` :511 + `@Idem` (`SalesReturnController` :82) | no un-receive; partial `receivedQty` | now | `SalesReturnFlowIT.issueCostReversal`; `SalesReturnIT.restockCreditNote` | F10; LK-5, LK-9, LK-10 |
| SAL-4 | Return receive (no issue rows, legacy) | :252 | `receiveReturn`, cost = trip issue cost or current cost | OH + | Eng restock · now | `sales_return` ; Y | **none** | as SAL-3 | as SAL-3 | now | **none** | F2 |
| SAL-5 | Inspect: rejected | `inspect` :301 → `issue` :329, lines unsorted (:310) | `issue` move type issue, not pinned | OH − at receive location | Eng STOCK_OUT (Dr COGS) · now | `sales_return` ; N | none | FOR UPDATE + `received` :302 | none | now | `SalesReturnFlowIT.perLineReject` | F10, C-9; M-33; LK-5 |
| SAL-6 | Inspect: quarantine / damaged | :341 | `transfer` to the chosen target (any location, :334-341) | OH → Q (or any location) | Eng `postTransfer` · now, no key | `sales_return` ; N | none | as SAL-5 | none | now | `SalesReturnFlowIT.dispositions`; `SalesReturnIT.scrapQuarantineRefund` | C-7, X-3, F10; M-33 |
| SAL-7 | Inspect: scrap | :344 | `issue` waste | OH − | Eng WASTE · now | `sales_return` ; N | none | as SAL-5 | none | now | `SalesReturnIT.scrapQuarantineRefund` | C-9; M-33 |
| SAL-8 | Sales-order reservation | `SalesAtpService.reserveOrder` :227 (`lockCell` :272, `releaseOtherCells` :285, `reserve` :287 / `release` :290); from `followOrder` :215 (`@Transactional` :208), `SalesFulfilmentService.rebuild` :128, `pickLocation` :369-381; route `POST /sales/orders/{id}/reserve` (`SalesController` :346-351) calls `reserveOrder` **with no transaction** | reservation writes | R ± | – | `sales_order_line` | – | upsert under the cell lock only when a caller transaction exists | `releaseLine` :385; `releaseOrder` on cancel (`SalesOrderService` :1135) and done (:1225) | – | `SalesAtpIT.concurrency`, `.backorderLifecycle` | S-7, C-5, S14, F5; M-27; LK-4 |
| SAL-9 | Dispatch free-stock check | `SalesDeliverySource.assertIssuable` :58-82 (`lockCell` :63), only caller `FleetDispatchService` :1130 | `lockCell` (no write) | – | – | – | – | refuses only when **other** reservations exist (:77) | – | – | `SalesReadinessR6IT.reservationsHonoured` | F5 |

### 3.11 Fleet (`fleet/application/`)

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FLT-1 | Trip dispatch (mode `dispatch`, :1105) | `FleetDispatchService.dispatchAt` :1082 → per line `inLockOrder` :1116: SAL-9 :1130 → `issue` :1146; sales route `SalesDeliveryService.issue` :888 locks the SalesOrder first (:890) | `issue`; MANUAL when the line names a batch; serials from FleetLoadLine (no count check, `setSerials` :1602) | OH − at yard (no T bucket) | Eng STOCK_OUT · `at` (now on the route) | `fleet_trip` ; N | pin (batch / serial) | DO FOR UPDATE → trip FOR UPDATE (`lockTrip` :1581-1585) + status + `stockIssuedAt` null :1096 + `@Idem` (`FleetTripController` :131) | no un-dispatch; FLT-2 / FLT-3 | `dispatchedAt` (used) | `FleetDispatchIT.salesOrderToInvoice`, `.concurrentDispatch`; `SalesReadinessR6IT.doubleIssue` | C-2, C-9, F1, S11; LK-3, LK-4 |
| FLT-2 | Proof of delivery, returned goods | `proofOfDelivery` :1197 → `putBack` :1253 → :1685, lines unsorted (:1226); `syncDeliveryLoss` :1266 | `receiveReturn` at FleetLoadLine cost (current cost in `invoiced` mode); rejection reasons → SALES-QUARANTINE (:1254, :1697) | OH + at yard or Q | Eng restock · **now**; Caller `postDeliveryLoss`, key `delivery-loss:<line>:<target>` · now (:1730) | `fleet_trip_return` ; N | **none** | `lockTrip` + dispatched / in_transit → delivered once (`TripStatusPolicy` :21-23) + `@Idem` :145 | one POD; partial per line | `receivedAt` (**not used**, :1203) | `FleetDispatchIT.returnsPutStockBack`; `SalesDeliveryManagementIT.shortageDamagedRejected`; `SalesReadinessR6IT.lossAndQuarantine` | F3, X-3; M-32; LK-5, LK-12 |
| FLT-3 | Whole load returned | `returnLoad` :1488 → `putBack` :1501, unsorted (:1497) | `receiveReturn` | OH + | Eng restock · now | `fleet_trip_return` ; N | **none** | `lockTrip` + → returned + `@Idem` :199 | whole load | now | `FleetDispatchIT.returnsPutStockBack`; `SalesDeliveryManagementIT.wholeLoadReturned`, `.failAndReschedule` | F3; LK-5 |
| FLT-4 | Tank fuel issue | `FleetFuelPostingService.post` :95 → `issue` :106 | `issue` `internal_use`, skipGl, `requireAvailable`, date `fuelledAt` | OH − at tank | Caller "INV", key `fleet:fuel:<log>` (:111) · `fuelledAt` | `fleet_fuel_issue` ; N | – | FleetFuelLog FOR UPDATE + draft → vehicle FOR UPDATE (`FleetFuelLogService` :383, :391) + JE key | FLT-5 | `fuelledAt` (used) | `FleetFuelIT.tankIssue`, `.concurrentTankIssues` | GL-7 |
| FLT-5 | Fuel reversal | `reverse` → RLS :163 | RLS: bare receive `reversal_in`; mirrors **no** journal (it looks for `fleet_fuel_issue`; the JE is `fleet_fuel_log`, :51-53) | OH + | Caller `posting.reverse(je, now)` | `fleet_fuel_issue_reversal` ; N | reverses | log FOR UPDATE + posted (`FleetFuelLogService` :464-465) | whole log | now | `FleetFuelIT.tankIssue` | – |
| FLT-6 | Tank dip reconcile | `FleetFuelTankService.apply` :419 (from reconcile :315, `applyDecision` :366) | `adjust` (dip − book) | OH ± | Eng `postAdjustment` · **now**, no key | `fleet_tank_dip` ; N | – | FleetTankDip FOR UPDATE :316 + tank FOR UPDATE :317 + `open` :318 (or `pending_approval` :367) | none | `dippedAt` used only for the book level, read by ledger `createdAt ≤ dippedAt` (:230-233) | `FleetFuelIT.dipReconciliation` | M-30 |
| FLT-7 | Tyre fitted from stock | `FleetTyreService.issueFromStock` :509 → `issue` :531 | `issue` `internal_use`, skipGl, serial pinned, date `at` | OH − 1 | Caller "INV", key `fleet:tyre:issue:<id>` · `at` | `fleet_tyre` ; N | – | FleetTyre FOR UPDATE :305 + vehicle FOR UPDATE :314 + `stockIssuedAt` null :341 + key | no return path | fit time (used) | `FleetTyresIT.stockIssueOnce`, `.concurrentFitment` | GL-7 |
| FLT-8 | Work-order part issue | `FleetWorkOrderCostService.issuePartAt` :122 → `issue` :131 | `issue` `internal_use`, skipGl, `requireAvailable`, date `at` | OH − | Caller "INV", key `fleet:wo_part:<random>` (never matches a retry) · `at` | `fleet_work_order`, **referenceId = WO code** ; N | – | WO FOR UPDATE :123 + status; retry only `@Idem` (`FleetWorkOrderController` :165) | FLT-9 / FLT-10 | `at` (used) | `FleetMaintenanceIT.partsIssueReturnReverse`, `.concurrentPartIssue` | GL-7; M-31 |
| FLT-9 | Unused part returned | `returnPart` :160 → `receive` :175 | bare `receive` `return_in` at line cost | OH + | Caller · **now** (not `at`), random key | as FLT-8 ; N | **none** | WO + line FOR UPDATE + remaining check :166-169 | partial | now | as FLT-8 | M-31 |
| FLT-10 | Reverse part / part-return line | `reverse`: `receive` `reversal_in` :309; `issue` skipGl `requireAvailable` at current layers :319 | `receive` / `issue` | OH ± | Caller `posting.reverse(je, now)` | as FLT-8 ; N | none | WO + line FOR UPDATE + posted | whole line | now | as FLT-8 | M-31 |
| FLT-11 | Demo seeds | `FleetDemoSeedContributor` :171-174, `FleetConstructionDemoSeed` :305-309 | `receive` / `issue` | OH ± | various | demo | – | seed run | – | – | `FleetDemoSeedIT`, `ConstructionDemoSeedIT` (no route) | – |

### 3.12 Invoicing (`invoicing/application/`)

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| INVC-1 | Invoice auto-issue (skipped when `stockIssuedBy` is set, :173) | `InvoicingWorkflowsInitializer.postInvoice` :168 → per line `inLockOrder` :176 → `issue` :191 | `issue`; location = branch warehouse or `lotWarehouse` per line | OH − | Eng STOCK_OUT · `issueDate`; Caller invoice JE :237 (no key) · `issueDate` | `sales_invoice` ; Y | – | workflow `lockStatus` Document FOR UPDATE (`WorkflowService` :118/:255) + status | INVC-2; INVC-6 | `issueDate` (used) | `InvoicingIT.invoices`; `InventoryReturnsIdentityIT.invoiceCancelRestoresTheSameLots` | F9, F1, S11; LK-17 |
| INVC-2 | Invoice cancel | `cancelDocument` :247 → JE reversal :250 → `returnIssuedStock` :264 → RFS :278 | RFS, lines null, `postRestockGl`, date `reversalDate(issueDate)` | OH + | Eng restock · reversalDate; Caller JE reversal | `sales_invoice_cancel` ; N | reverses | Document FOR UPDATE (`InvoiceService` :849) + workflow lock + ledger-exists :266 | whole; refused once a credit note is posted (:149-160) | issueDate / now | `Acc0ReversalIT.cancelRestoresStock`, `.cancelAfterInsufficientStock`; `DocumentReversalIT.invoiceCancelLifecycle` | F3; LK-11 |
| INVC-3 | Bill auto-receive | `postBill` :408: supplier lock :427 → matched GRN lines (`BillReceiptMatcherService` :75) → bill JE :589 → per line `inLockOrder` :599: `consume` :607 → `receiveFromBill` :628 → PurchaseOrder FOR UPDATE :718 | `receiveFromBill`, unit cost via `double` (:619) | OH + at matched GRN location or branch warehouse | Eng receipt to GRNI · bill `issueDate`; Caller bill JE key `vendor_bill:<id>` | `vendor_bill` ; **N (dropped by the engine)** | – | workflow Document lock + supplier advisory + GRN lines FOR UPDATE | INVC-4 | `issueDate` | `PurchaseReadinessBillsIT.receiptAbsorbsBillReceivedStock`; `ProcurementIT.billMatching` | F11, GL-1; LK-1, LK-10 |
| INVC-4 | Bill cancel | `VendorBillService.cancel` :609 → workflow → `returnBillReceivedStock` :305 → `issue` :321 (rows ORDER BY createdAt :315); `absorbingReceipt` read unlocked :355 | `issue` `return_to_supplier`; no `requireAvailable`, no pin, no `.reverses` | OH − from current layers | Eng RETURN_TO_SUPPLIER · `reversalDate(issueDate)`; Caller variance JE key `vendor_bill_cancel:variance:<id>` :332 | `vendor_bill_cancel` ; N | **none** | Document FOR UPDATE (`VendorBillService` :631) + workflow lock + ledger-exists :307; **no supplier lock** | whole bill | issueDate / now | `ProcurementIT.billMatching`; `DocumentReversalIT.billVoidLifecycle` (none covers cancel after the goods were sold) | F6, GL-4; M-29 |
| INVC-5 | `stockIssuedBy` marking (no engine call) | `InvoiceService.setStockIssuedByDelivery` :758 (`UPDATE … WHERE status = 'draft'`); callers `SalesBillingService` :242, `FleetDeliveryOrderService` :443/:599, `FleetDeliveryInvoiceService` :182, cash-sale invoice :474 | – | suppresses INVC-1 | – | – | – | the UPDATE waits on the post's Document lock, then matches 0 rows | DO cancel clears the flag only on a draft (:595-600) | – | `FleetDispatchIT` :595-615 (draft only) | F1 |
| INVC-6 | Credit-note restock | `CreditNoteService.post` :517 → CN JE :573 → per CN line, **document order** :603 → RFS :623 (source `invoiceIssueSource` :262: sales_invoice, cash_sale or fleet_trip rows) | RFS, `postRestockGl`, date CN `issueDate` | OH + at original location | Eng restock · CN `issueDate`; Caller CN JE | `credit_note` ; Y (CN line) | reverses | invoice + CN FOR UPDATE ORDER BY id :534 + status :546 | INVC-7; per-line cap (`assertWithinInvoice`) | CN `issueDate` | `InventoryReturnsIdentityIT.partialReturnAcrossLotsAndOverReturnRefused`; `CashSaleIT.creditNoteOnCashSaleInvoiceRestocksAtTheSaleIssue`; `FleetDispatchIT.invoicedSourceAndCreditNote` | F3, F4; LK-5 |
| INVC-7 | Credit-note reversal | `reverse` :687 → JE reversals :735 → `takeBackRestock` :808 → `issue` :844 (rows ORDER BY product, variant, location) | `issue`, `requireAvailable`, pin (lot, serial, cost), `.reverses` | OH − | Eng STOCK_OUT · `reversalDate(CN issueDate)` | `credit_note_cancel` ; N | reverses + pin | CN + allocated invoices FOR UPDATE ORDER BY id :704 + status + ledger-exists :809 | whole note | – | `InventoryReturnsIdentityIT.creditNoteReversalTakesBackTheRestockedLot`; `DocumentReversalIT.creditNoteReversal` | – |

### 3.13 Manufacturing (`manufacturing/application/`; PS = `ProductionService`, SES = `StageExecutionService`)

No class in this section calls `lockCells`, `lockQuants` or `StockMove.inLockOrder`.

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| MFG-1 | Order confirm, reservations | PS.`confirmOrder` → `reserveSoft` :396 in a savepoint, failures swallowed (:397-399); `uom.toBase` REQUIRES_NEW :390 | `reserveSoft` | R + (soft) | – | `production_order` | – | adv :373 + `draft` :377 | `releaseSoft` on cancel :828 | – | `ProductionUnitsIT.reservationsInBaseUnitsUntilConsumed` | C-5, S14; LK-5, LK-12 |
| MFG-2 | Order start, consume materials | PS.`startOrder` :474, materials in `materials()` order (no ORDER BY, :1437-1450); `consume` :492 after the loop | `issue` `production_consume`, FEFO, `.date(date)`, `requireAvailable = materialsMustBeOnHand()` | OH −; R consumed | Eng PRODUCTION_CONSUME (Dr WIP) · `dto.date ?: now` :430 | `production_order` ; **N** (ProductionMaterial id exists, :468) | none (the line keeps the ledgerCode for RFS) | adv :432 + `confirmed && consumedAt null` :437 | cancel → MFG-9; no partial or extra issue | `StartOrder.date` (GL and period check) | `ManufacturingIT.bake`; `ManufacturingIntegrityIT.cancelRacingStart`; `ProductionMaterialsOnHandIT` | GL-4, X-9; M-12; LK-5 |
| MFG-3 | Complete, by-products | PS.`completeOrder` :604 → `receiveOutput` :1196 | bare `receive` `production_output` at the allocated cost, batch / expiry | OH + (or Q) | Caller `postProductionOutput` :1198 · `dto.date ?: now` :522; no key | `production_order` ; **N** (lineId only on ProductionOutput) | – | adv :533 + `in_progress && postedAt null` :538 | MFG-7; one-shot | `CompleteOrder.date` (GL only; period check now) | `ProductionUnitsIT.reversalIssuesByproducts`; `ManufacturingIntegrityIT.byproductAllocationCap` | **GL-2**; M-12 |
| MFG-4 | Complete, main output | :648 → :1196 (STANDARD at standard); variances :691; overhead `absorbOverhead` :584 | bare `receive` | OH + (or Q) | Caller :1198 at `outputValue` · as MFG-3 | `production_order` ; N | – | as MFG-3 | MFG-7; one-shot | as MFG-3 | `ManufacturingIT.bake`; `StandardProcessCostingIT.productionAtStandard` | X-9, GL-11 |
| MFG-5 | Complete, zero yield | :550-575 | none | R released :571 | Caller `postProductionScrap` :557 + overhead | – | – | as MFG-3 | `reverseZeroYield` :1112 | as MFG-3 | `ManufacturingIT.zeroYield`; `ManufacturingCoreIntegrityIT.zeroYieldOverhead` | – |
| MFG-6 | QC pass / fail | PS.`recordQc`: `transfer` :761 (pass), `issue` waste :765 (fail) | `transfer` Q → output; `issue` waste | Q −, OH + / Q − | Eng · **now** | `production_qc` ; N | – | adv :745 + `qc_hold` :747 + cap ≤ `producedOnHand` :754 | rework keeps qc_hold; partial | `Instant.now()` :737 | `ManufacturingCoreIntegrityIT.qcBoundedByQuarantine`; `ManufacturingIT.qcReverseOverhead` | X-3 |
| MFG-7 | Reverse, outputs back out | PS.`reverseOrder`: FG `issue` :936, by-products :944 | `issue` skipGl `.prefer(producedLots)`, **no `requireAvailable`** | OH − (or Q) | Caller `postProductionReversal` :940/:949 · **now** :925 | `production_reversal` ; N | **prefer only** | adv :880 + completed / qc_hold + `reversedAt null` :885-891; on-hand read unlocked :900/:916 | full only | now | `ProductionUnitsIT.reversalInBaseUnits`, `.reversalIssuesByproducts`; `ManufacturingIntegrityIT.reverseCountsOwnOutput`; `ManufacturingHardeningIT.reverseHeldAndGenealogy` | M-15; LK-5, LK-12 |
| MFG-8 | Materials back (cancel / reverse) | PS.`returnMaterials` → RFS :1237 per material | RFS by ledgerCode, lines null, `postRestockGl=false` | OH + at source | Caller `postProductionOutput` :1245 (Dr stock / Cr WIP) at line cost · now (:843, :925) | `production_order_cancel` / `production_reversal` ; **N** | reverses | cancel: adv :809 + `postedAt null` :845; reverse as MFG-7 | whole order | now | `ProductionUnitsIT.cancelReturnsBaseUnits`; `ManufacturingIT.cancelAndUnderYield` | LK-9 |
| MFG-9 | Same, legacy line without ledgerCode | :1241 | bare `receive` `receipt` | OH + | Caller :1245 · now | as MFG-8 ; N | **none** | as MFG-8 | – | now | **none** | – |
| MFG-10 | Stage material issue (manual / backflush) | SES.`issueMaterial` :1241 from :333 / :420; backflush in `sequence` order (:417-433) | `issue` `production_consume` skipGl, `.date`, `requireAvailable` (strict or must-be-on-hand) | OH − | Caller `postProductionConsume` :1242 · `dto.date ?: now` (:1833) | `production_stage` ; N (materialId on the posting line) | – | adv + `ProductionOrderStage` FOR UPDATE (:1165-1166, :1770) + status + `@Idem(required)` (`MultiStageController` :147) | MFG-15 (LIFO); partial | `dto.date` (GL and period check) | `MultiStageProductionIT.threeStageOrder`, `.concurrentIssue`, `.stageMaterialsMustBeOnHand` | GL-7; M-12; LK-5 |
| MFG-11 | Stage intermediate issue | SES.`issueIntermediate` :1293 | `issue` skipGl, `requireAvailable`, `.pin(batchIds, carried unit)` :1288-1290 | OH − at stage location | Caller :1294 · as MFG-10 | `production_stage` ; N | pin | as MFG-10 | as MFG-10 | as MFG-10 | `StageIntegrityIT.intermediateCarriesItsOwnCost`, `.intermediateDrawsItsOwnLot` | GL-7 |
| MFG-12 | Stage output | SES `receive` :1411 from :525 / :553; `quarantineLocation()` :522 (REQUIRES_NEW `ensure`) after the backflush locks | bare `receive` `production_output`, `.date` (ignored by the period check); STANDARD at standard | OH + (stage / output / Q when gated) | Caller `postProductionOutput` :1413 + `postStageVariance` :1417; overhead :437 | `production_stage` ; N | – | as MFG-10 + `@Idem(required)` :154 | partial | `dto.date` (GL only) | `MultiStageProductionIT.threeStageOrder`, `.jointCostByNrv` | M-12; LK-12 |
| MFG-13 | Stage QC release | SES.`release` → `transfer` :786 (from `recordResult` :658; also `PrecastProductionService` :557/:664, :664 from the scheduled `PrecastCuringJob` tick) | `transfer` Q → destination, pinned | Q −, OH + | Eng · **now** | `production_stage` ; N | pin | adv :621 + stage FOR UPDATE :622 + per-lot status | per lot | now (curing tick uses now, not `qcPassDueAt`) | `MultiStageProductionIT.stageGatePerLot`, `.stageGateWithQualityLot` | X-3 |
| MFG-14 | Stage QC scrap | SES.`qcScrap` → `issue` :853; release :867 | `issue` waste, `requireAvailable`, pinned | Q −; R released on completion | Eng WASTE · now | `production_stage` ; N | pin | authorise (:818) → adv :822 → stage :823 → lot `failed && held > 0` | LIFO reversible | now | `StageIntegrityIT.scrapFailedLot` | – |
| MFG-15 | Stage reverse | SES.`reverseOne` → RLS :1095 (from `reversePosting` :1021, `reverseOrder` :1034/:1043); variance JEs first :1089; no-ledger postings `posting.reverse` :1101 | RLS **non-strict** | inverse | RLS mirrors `production_stage` JEs · now | `production_stage_reversal` ; copied | reverses | adv + `reversedAt null` + latest-posting (LIFO) check :1060-1067; **no posting row lock** | LIFO, one at a time | now | `StageIntegrityIT.reversalChecksTheLot`, `.lifoBySequence`, `.reversedStageVarianceOffset` | M-15; LK-5 |
| MFG-16 | Other stage-engine callers | `CrushingRunService` :630/:706; `ProcessingJobService` :677/:898; `PrecastProductionService` :244/:286/:525; `ShopFloorExecutionService` :369; `PlmDispositionService` :295/:320 | via MFG-10..15 | – | as the called row | – | – | caller's document lock, then adv | – | – | per module ITs (`CrushingIT`, `ProcessingIT`, `PrecastManufacturingIT`, `ShopFloorExecutionIT`, `PlmIntegrityIT`) | – |
| MFG-17 | Process costing issue | `ProcessCostingService.issueMaterial` :268 | `issue` skipGl `production_consume`, **no date**, no `requireAvailable` | OH − | Caller `postProductionConsume` :271 · min(periodTo 12:00 **UTC**, now) (:543-547) | `process_costing` ; N | – | `ProcessCostPeriod` FOR UPDATE + open (:529-537) + `@Idem(required)` (:166) | **no reversal** | period date (GL only) | `StandardProcessCostingIT.processCosting`; `ProcessCostingGuardsIT` | GL-7; M-14 |
| MFG-18 | Process costing close | `close` → `receive` :427; abnormal loss JE key `manufacturing:process_abnormal:<org>:<period>` :393; next period FOR UPDATE :406 | bare `receive` `production_output` (STANDARD at standard) | OH + | Caller `postProductionOutput` :430 + variances · postingDate | `process_costing` ; N | – | period FOR UPDATE + open + `@Idem` (not required) :182 | no reopen | period date | `StandardProcessCostingIT.processCosting` | M-14; LK-18 |

### 3.14 Quarry, crushing and processing

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| QRY-1 | Crushing run post | `CrushingRunService.post` → `stages.issue` :583, `stages.output` ×n :630 | via MFG-10 / MFG-12, date = `runDate` | stockpile −, fraction piles + | via MFG-10 / 12 · `runDate` | `production_stage` ; N | – | CrushingRun FOR UPDATE :523 + draft :524 + `@Idem(required)` | reverse :706 → `stages.reverseOrder` | `runDate` (GL) | `CrushingIT.runLifecycle`, `.concurrentPosts` | – |
| QRY-2 | Survey approve | `CrushingSurveyService.approve` :218 → `adjust` :338; negative check after the call :339-344 | `adjust` | stockpile ± | Eng `postAdjustment` · **now** | `crushing_survey` ; N | – | survey FOR UPDATE :190 + status :191 + pile advisory :195 + "posted later" check :197-204 + `@Idem(required)` | QRY-3 | `surveyDate` exists, **not used** | `CrushingIT.survey`, `.surveyIntegrity` | – |
| QRY-3 | Survey reverse | RLS :257; on-hand pre-check :251 unlocked | RLS non-strict | inverse | RLS mirror · now | `crushing_survey_reversal` | reverses | survey FOR UPDATE :243 + status + pile advisory :245 | full | now | `CrushingIT.surveyIntegrity` | M-15 |
| QRY-4 | Processing job create, reservations | `ProcessingJobService` reserve :318, sorted by product :299; insufficient-unreserved check :325 | `reserve` (hard: the engine refuses reserved `processing_job_input`) | R + | – | `processing_job_input` | – | job insert + `@Idem(required)` | cancel → release :359 (job FOR UPDATE + draft) | – | `ProcessingIT.reservations`, `.raceForStock` | C-5, S-7 |
| QRY-5 | Processing job start | `consume` :466 then `stages.issue` :469, inputs in createdAt order (:387) | `consume`, then MFG-10 with the named lot, date `jobDate` | R consumed; OH − | via MFG-10 · `jobDate` | `production_stage` ; N | – | job FOR UPDATE :385 + draft + `@Idem(required)` | reverse :898 → `stages.reverseOrder` | `jobDate` | `ProcessingIT.goldenChain`, `.namedLotRefused`, `.lotSaleNeverGoesShort` | LK-5, LK-13 |
| QRY-6 | Explosive issue | `QuarryExplosiveService.issueStock` :487, lines in lineNumber order (:479) | `issue` `internal_use`, skipGl, `requireAvailable`, `.date(now)` | magazine − | Caller `postExplosives` :495, key `quarry:explosive-issue:<id>` · now | `quarry_explosive_issue` ; N | – | issue FOR UPDATE :456 + `approved` :457 + blast FOR UPDATE :470 + `@Idem(required)` | QRY-7 | now | `QuarryIT.explosiveIssueAndBlastAbsorption`, `.concurrentExplosiveIssues` | GL-7; LK-5, LK-6 |
| QRY-7 | Explosive return | `reconcile` → `receive` :557; lot-tracked items into a **new lot `RET-<code>`** (:554) | bare `receive` `return_in` at line cost | magazine + | Caller `postExplosives(…, true)` :565, key `quarry:explosive-return:<id>` · now | `quarry_explosive_return` ; N | **none** | issue FOR UPDATE :509 + `issued` :510 + per line ≤ issuedQty :543 + `@Idem(required)` | partial per line; once | now | `QuarryIT.explosiveIssueAndBlastAbsorption` | M-13 |
| QRY-8 | Extraction post | `QuarryExtractionService.doPost` → `receive` :319 | bare `receive` `production_output` at computed cost; lot = log code | ROM pile + | Caller `postExtraction` :320, key `quarry:extraction:<id>` · min(end of `extractedOn` in the business zone, now) (:538-541) | `quarry_extraction` ; N | – | log FOR UPDATE :285 + draft + blast FOR UPDATE :513 + `@Idem(required)` | QRY-9 | `extractedOn` (GL only) | `QuarryIT.extractionIntoRomStock` | M-13; LK-7 |
| QRY-9 | Extraction reverse | RLS :393 → `postings.reverse` :396 → blast FOR UPDATE :400 | RLS non-strict, **no on-hand check** | ROM − | RLS mirrors nothing (JE type `quarry_extraction_log` ≠ `quarry_extraction`); `postings.reverse` · now | `quarry_extraction_reversal` | reverses | log FOR UPDATE :386 + `posted` + `@Idem(required)` | full | now | `QuarryIT.reverseReleasesEvidence` | M-10; LK-7 |
| QRY-10 | Stockpile consume | `QuarryStockpileService.consume` :121 | `issue` skipGl `requireAvailable` | – | – | – | – | – | – | – | **none (no caller in main or test)** | – |

### 3.15 Repair

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RPR-1 | Part issue | `repair/application/RepairPartsService.issuePart` :127 | `issue`, `.sourceLine(partId)`, no date | OH − | Eng STOCK_OUT (Dr COGS) · now | `repair_part_issue` ; **Y** (part) | – | `db.inTx` + RepairPart FOR UPDATE :97 + status (:101-106); **no `@Idem`** on the route | RPR-2; no partial | now | `RepairIT.parts` | X-11 |
| RPR-2 | Part return | RLS :172 | RLS non-strict | OH + | RLS mirrors the COGS JE · now | `repair_part_return` | reverses | part FOR UPDATE :153 + `issued` + rows-exist (:159-165) + `@Idem(required)` | whole part | now | `RepairIT.partReturn` | – |

### 3.16 Rental (`rental/application/`)

Every rental transfer first upserts all five `RentalLocationConfig` rows (`RentalLocationConfigService` :57-65), which
serialises rental stock moves per organisation (LK-14). Quantities pass through `double` (`RentalPostingService`
:65-69, :113; M-16).

| ID | Event | Call site | Engine call | Buckets | GL (rule · date) | Ref ; SLI | Reversal link | Guard | Cancel / partial | Time | IT | Findings |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RNT-1 | Checkout | `RentalAgreementService.checkout` :301 → `RentalPostingService.move` :127, lines :299-302 | `transfer` RENT-STOCK → RENT-OUT, `.prefer(serial)` | STOCK −, OUT + (all virtual) | Eng · now | `rental_checkout` ; N | prefer | status read **unlocked** :271; `@Idem(required)`; real backstop = unique index `RentalDeposit(org, agreementId)`, hit after the transfers | none | now | `RentalIT.lifecycle` | X-7, X-3; M-16; LK-5 |
| RNT-2 | Swap | `RentalSwapService.swap` :104 / :106 | `transfer` OUT → CLEAN (from unit), then STOCK → OUT (to unit) | OUT ±, CLEAN +, STOCK − | Eng · now | `rental_return` / `rental_checkout` ; N | prefer | **no row locks** (:70-93); `@Idem(required)` | – | now | `RentalIT.swapMovesTheLineQuantity` | X-7; M-11; LK-5 |
| RNT-3 | Return (no stock) | `RentalReturnService.createReturn` :107-160 | **none** | – | – | – | – | no lock, no cap against the line or earlier returns | – | – | `RentalIT.lifecycle` | M-7, M-8 |
| RNT-4 | Inspect, clean | `inspect` :208 | `transfer` **RENT-CLEAN → RENT-STOCK** | CLEAN −, STOCK +, **OUT unchanged** | Eng · now | `rental_inspect` ; N | prefer | **none**; no `@Idem` (`RentalController` :393) | – | now | `RentalIT.lifecycle` :762-794 (asserts `out = 1` after a clean return) | **M-7**, M-8, X-7 |
| RNT-5 | Inspect, damaged | :211 | `transfer` OUT → DAMAGED | OUT −, DAMAGED + | Eng · now | `rental_return` ; N | prefer | none | – | now | `RentalIT.lifecycle` | M-8 |
| RNT-6 | Inspect, missing | skipped (:206-213) | **none** | stays in OUT | – | – | – | – | no write-off path | – | none | M-9 |
| RNT-7 | Extension, cron | `RentalExtensionService` :119, `RentalCronWorker` | **none** (fee invoice only) | – | – | – | – | – | – | – | – | sweep listed them as callers: wrong |

## 4. Corrections to the call-site sweep

The sweep was a grep-based seed. These rows were wrong or incomplete; the tables above hold the corrected values.

| Area | Sweep said | Code says |
|---|---|---|
| POS issue rows | sourceLineId = invoice line | It is the `OrderItem.id` (:2124, :2153, :2269, :2350). sourceType is `pos_invoice` **or `pos_order`** (:2151). |
| POS reservations | reserved in the job | Reserve runs after the invoice commit (:620); consume / release run outside the issue transactions (:1794, :1812). |
| POS job runners | "FOR UPDATE SKIP LOCKED claim" | True only for `StockPostingWorker`. `drainSession` and both sync drains use the atomic `claim()` UPDATE (:1827-1834). |
| POS refund | guard = refundedQty | Also the unlocked pending-job read (:1320-1324). Recipe lines restock at the re-resolved till location (:1366). Cells unsorted. |
| Order reversal | cancel and void only | `onOrderReopened` (:153) also exists and re-issues everything. The cancel side effect runs before the status update. |
| Kitchen waste | – | REQUIRES_NEW per line (:46), after the order commit; the dedupe check is unlocked. |
| Count post | "count-session flow" | The session claim (:720) is taken after `lockQuants` (:703). |
| Transit transfer | all legs pinned, engine GL | Only receive / damaged / short / recall are pinned. The short leg is skipGl + caller `postAdjustment`, dated now, no key (:1061-1067). `cancelTransfer` (:1144) was missing. |
| Sync | – | `applyAwaiting` / `applyOne` (:261) and `SyncStockJob.tick` (:44) were missing. Offline ops ignore `occurredAt`. |
| Direct stock | reversible | No document row and no reversal route (`reverseDocument` covers only stock_out, waste, stock_adjustment, stock_transfer, :131-133). |
| Warehouse ship | all pick orders | MANUAL pick orders only (:563-568); `requireAvailable`; approval REQUIRES_NEW under the pick-order lock (:514). |
| Revaluation | GL now | GL dated `revaluationDate` at UTC start of day (:219). L4 advisory only, no StockItem FOR UPDATE (:495). |
| Standard cost | `@Transactional` | A TransactionTemplate (:473); `lockQuants(advisory=false)` relies on the exclusive product key (:491, :550). |
| Cost policy | ledger INSERT at :470-497 | Ledger write is `recordValueOnly` (:452); :470-497 are lot / StockItem writes. Same for `LineageFollower` :420 (a StockItem UPDATE). |
| GRN post | engine only | Also the caller voucher JE (:597, null key) and cash settlement; QC quarantine transfers; `receivedAt` defaults to draft creation. |
| GRN reverse | – | Guard is GRN FOR UPDATE :673 + claim :703; QC `returnOnReversal` runs first; PO lines rolled back after the engine. |
| PO receive | sorted | Not sorted (:921); voucher key `receipt_voucher:goods_receipt:<grn>`; date `dto.receivedAt ?: now`. |
| RTV | – | Stock move dated now; GL dated `issueDate`; no lot pin. |
| DN reversal | "caller JE" | `posting.reverse` of the original journals, dated original or now. Guard adds the ledger-exists check (:1043). |
| Landed cost layers | referenceType `cost_adjustment`, guard "pool locks" | `cost_adjustment` is the ledger **type**; referenceType is `landed_cost_voucher` / `reversal` / `landed_cost_voucher_reversal`. Guard is the claim + GRN FOR SHARE. LCV :735/:744/:771/:775/:800 and `LandedCostService.postHistorical` :304/:312 were missing. |
| CostLineageService callers | short list | Also LF :318/:386, LCV :771/:775, `LineageExceptionService` :283, `InventoryCostPolicyService` :452, `InventoryRevaluationService` :307/:408, `StandardCostService` :586. `LineageCapitalisation` callers also include `AbsorptionSettlementService`, `DistributionCostingService`, `QuarryCostingService`, `CostAdjustmentService`. |
| Project reversal | engine GL | Bare `receive` (no engine GL); JE reversed at now; issue GL dated `in.date`; issue guard is `@Idem` only. |
| Quality | reject skipGl | Reject is an engine-GL issue; release / cancel / quarantine are engine transfers; downgrade is a caller JE; hold moves no stock. `QualityDecisionService.quarantine` and the post-commit GRN path were missing. |
| QC legs | :72/:105/:132/:169 | Those are the `moved()` checks; the transfers are :79/:113/:139/:179; `retry` :194 and `QcCheckService` :420/:563 were missing. |
| Branch transfer | – | Freight adjunct via `LineageCapitalisation.record` :397; no reversal. |
| Cash sale confirm | GL `@saleDate` | now if today, else `saleDate` 00:00 **UTC** (:924). |
| Cash sale void | FOR UPDATE, confirmed → voided | The "invoiced" check uses the pre-lock row (:1477-1479). The accrual is reversed (:1486). |
| Sales return | – | Returns against `FleetLoadLine.stockLedgerCode`; SLI = return line; lines unsorted; SalesOrderLine projection after the engine. Inspect: reject = `issue` (Dr COGS), scrap = waste, quarantine = transfer; none pinned. |
| SalesAtp | – | Writes also come from `rebuild` → `followOrder`, `pickLocation`, `releaseOrder`. The `/reserve` route runs with no transaction. |
| Fleet dispatch / POD | – | The DeliveryOrder is locked first; the sales route locks the SalesOrder before that. POD posts a caller delivery-loss JE; rejection reasons go to SALES-QUARANTINE. |
| Tank dip | "no row claim found" | FleetTankDip FOR UPDATE :316 + tank FOR UPDATE :317 + `open` :318. |
| Fuel reversal | RLS mirrors the JE | RLS mirrors nothing (type mismatch, :51-53); only the caller `posting.reverse(now)`. |
| Work order | referenceId = WO id | referenceId = WO **code**; returnPart JE dated now; reverse issue at current layers. |
| Bill post / cancel | workflow lock | Post also takes the supplier lock :427 and GRN lines; PO FOR UPDATE after the engine (:718). Cancel has no supplier lock. |
| Credit note | lock order | RFS per CN line in document order (:603); `takeBackRestock` sets `requireAvailable`. |
| Production | row locks | The "lock" is the advisory `production:<org>:<id>`; GL dates `dto.date ?: now`; reservations via `reserveSoft` with failures swallowed; cancel uses `releaseSoft`. |
| Stage engine callers | SES only | Also `CrushingRunService` :630/:706, `ProcessingJobService` :677/:898, `PrecastProductionService` :244/:286/:525/:557/:664, `ShopFloorExecutionService` :369, `PlmDispositionService` :295/:320. |
| Process costing | GL now | min(periodTo 12:00 UTC, now); no reversal for issue or close. |
| Explosives / extraction | – | Explosive issue `internal_use`, both keys, now; return into a new `RET-` lot. Extraction GL min(end of `extractedOn`, now); reverse mirrors nothing and uses `postings.reverse(now)`; guard needs `posted`. |
| Rental | "agreement lifecycle" guard; extension and cron move stock | `LifecycleService.transition` only validates and inserts an event. Backstops are `@Idem` (same key) and the `RentalDeposit` unique index. Inspect has no guard. Extension and cron move no stock. |
| Missing rows | – | `SalesDeliverySource.assertIssuable`; `rebuild` / `followOrder`; `syncDeliveryLoss`; `setStockIssuedByDelivery` callers; fleet demo seeds; `generateInvoice` / `PosEventSubscribers` enqueue; `WarehouseOutboundService.cancel` bin write; `retryJob` / `abandonJob`; `SyncStockService.receiveGoods`; `LandedCostVoucherService.reverseReceipt` :691. |

## 5. Gaps

### 5.1 Restocks with no reversal link

Stock comes back in, but the new ledger row does not point at the issue row it undoes (`reversesLedgerId` null). RFS
cannot net these against the original issue, so a later RFS could return the same quantity twice, and the lot / cost
identity of the original issue is lost.

| Row | Restock | Evidence | What is lost |
|---|---|---|---|
| POS-6, POS-7, SYN-7 | POS refund restock (product and recipe / combo / extra) | `PosInvoiceService` :1437, :1393 | issue link, lot (lot / serial products are refused), issue location for recipe lines |
| SAL-4 | Legacy sales-return receive | `SalesReturnService` :252 | issue link |
| FLT-2, FLT-3 | POD returns and whole-load returns | `FleetDispatchService` :1685 | issue link, lot, serial |
| FLT-9, FLT-10 | Work-order part return; reverse of a part line | `FleetWorkOrderCostService` :175, :309 | issue link; value (M-31) |
| MFG-9 | Production materials, legacy line | `ProductionService` :1241 | issue link |
| QRY-7 | Explosive return | `QuarryExplosiveService` :557 (new `RET-` lot) | issue link, original lot and expiry |
| PRC-9 | Project issue reversal | `ProjectCostService` :449 | issue link, lot, serial, variant (M-25) |

Outbound reversals with no link: INVC-4 bill cancel (`InvoicingWorkflowsInitializer` :321, current layers, not the
received lot); MFG-7 production reverse (`prefer` only); FLT-10 part-return reverse (:319, current layers).

### 5.2 Movements with no sourceLineId

Only these rows set SLI: SAL-1, SAL-3, SAL-4, INVC-1, INVC-6, PRC-6, RPR-1, POS-1..4 (the OrderItem id, not the invoice
line), and RLS copies (it keeps the original row's value). **Every other row has SLI = N**, although a line id exists
in every case. The ones that matter most for Phase 1 line-level reversal:

| Rows | Line id that exists but is not written | Evidence |
|---|---|---|
| INVC-3 | vendor bill line | the engine drops it: `StockService.receiveFromBill` :158-162 (engine request E-1) |
| PRC-1, PRC-2, SYN-6 | GoodsReceiptLine | `GoodsReceiptsService` :580, `PurchaseOrdersService` :957 |
| PRC-4 | DebitNoteLine | `DebitNotesService` :672 |
| FLT-1 | FleetLoadLine (the code relies on `stockLedgerCode` instead) | `FleetDispatchService` :1146 |
| FLT-2, FLT-3 | FleetLoadLine | :1685 |
| INV-1..INV-9 | stock document lines | `StockDocService` :243, :342, :677, :758, :872, :972, :1049-1113 |
| MFG-2..MFG-18 | ProductionMaterial, ProductionOutput, stage posting lines | M-12 |
| SAL-5..SAL-7 | SalesReturnLine | `SalesReturnService` :329-344 |
| POS-6, POS-7 | InvoiceItem / OrderItem | M-2 |
| QL-1..QL-5, QC-1 | quality lot / QC check | per row |
| FLT-4..FLT-10, PRC-7..PRC-9, QRY-*, RNT-* | document id only | per row |

### 5.3 Journals dated now instead of the document date

Rows whose document carries a business date but whose stock journal (or period check, or ledger time) uses the
server clock. The engine causes the first three groups; the rest are caller choices.

| Cause | Rows | Document date not used |
|---|---|---|
| Engine `adjust` ignores `dto.date` (:998, :1051, :1137) | INV-3, INV-4, BEV-1, SYN-2, QRY-2, FLT-6 | count time, survey `surveyDate`, tank `dippedAt` |
| Engine `transfer` always now (:1155, :1380) | INV-5..INV-10, SAL-6, QL-1/2/5, QC-1, MFG-6, MFG-13, PRC-7, RNT-* | transfer / receipt dates |
| Engine bare `receive` period check now (:270) | MFG-3, MFG-4, MFG-12, MFG-18, QRY-7, QRY-8, PRC-9, FLT-9 | `CompleteOrder.date`, stage `dto.date`, `extractedOn` (GL uses them, period check does not) |
| RLS mirrors every journal at now (:485-488) | INV-11, PRC-3, MFG-15, QRY-3, RPR-2 | none needed (reversal time), but the mirror is not dated with the reversal document either |
| Caller: move at now, journal at document date | PRC-4 RTV (M-21); LC-1 value rows vs `LandedCost.date` (M-22); MFG-17 (M-14) | `issueDate`, `LandedCost.date`, period |
| Caller: everything at now despite a date | FLT-2 POD (`receivedAt`, M-32); FLT-9 (`at`, M-31); SYN-1, SYN-3, SYN-4, KW-2 (`occurredAt`, M-1); PRC-6 claim (settle time); INV-8 short leg | as listed |
| Caller: date at UTC midnight, not the org's business day | SAL-1 past-dated cash sale (M-34); VAL-1; VAL-7; PRC-8 `in.date`; MFG-17 periodTo noon UTC | as listed |
| Ledger time | **all rows**: `InventoryLedger.createdAt` = `clock_timestamp()` (:1758-1760); there is no effective-time column | every document date |

### 5.4 Rows with no covering IT

| Row | Missing test |
|---|---|
| POS-5 | POS reservation lifecycle (reserve after commit, consume, release) |
| POS-7 | Refund restock of recipe / combo / extra lines |
| SAL-4 | Legacy sales-return receive without issue rows |
| MFG-9 | Production return of a legacy material line without a ledgerCode |
| QRY-10 | `QuarryStockpileService.consume` has no caller at all (dead code) |
| LC-3 | Legacy `LandedCostService.postHistorical` |
| LC-5 | `LineageCapitalisation` record / reverse directly |
| VAL-4 | Cost-price-change revaluation |
| RNT-6 | Missing rental items |
| INVC-4 | Bill cancel after the bill-received goods were sold |
| INVC-5 | `stockIssuedBy` set after the invoice was posted |
| Concurrency | No IT exercises any of the deadlock pairs LK-1..LK-9, nor the races M-8, M-11, M-23, M-27, M-28, M-29, M-30 |

### 5.5 New findings (M-n)

Severity uses the plan's scale (P1 = wrong stock or GL in normal use; P2 = wrong under concurrency, retry or an edge
path; P3 = traceability, dating or hygiene). Lock-order findings are numbered LK-n in the lock-hierarchy audit and are
not repeated here.

| ID | Sev | Finding | Evidence |
|---|---|---|---|
| M-1 | P2 | Offline `stock.in` / `stock.out`, `transfer.dispatch` / `receive`, `stock.adjust` and `kitchen.void` ignore the op's `occurredAt`; ledger and GL are dated at sync. Only `grn.receive` passes it. | `SyncPushService` :1091-1120; `DirectStockService` :122 |
| M-2 | P3 | POS ledger rows point `sourceLineId` at OrderItem ids; refund restocks carry neither sourceLine nor `reverses`. | `PosInvoiceService` :2153, :1437 |
| M-3 | P3 | POS reservation lifecycle is not atomic with the issue (orphan reservation if the job finishes between check and reserve; consume outside the completion tx). | :620, :1532, :1794 |
| M-4 | P3 | Order-timed jobs date COGS at `Order.createdAt`; revenue is dated at `Invoice.issueDate`. COGS and revenue can fall in different periods. | :1893-1900 |
| M-5 | P3 | Kitchen-waste dedupe is an unlocked check-then-act outside the line transaction. | `KitchenWaste` :75-80, :195-200 |
| M-6 | P3 | POS refund restock passes quantity and unit cost through `double` (`PosSql.decOf(x.doubleValue())`). Breaks the no-double rule. | `PosInvoiceService` :1382, :1388-1389, :1431, :1433 |
| M-7 | P1 candidate (Nest parity; owner decision) | A clean rental return moves RENT-CLEAN → RENT-STOCK, but nothing ever moved the unit OUT → CLEAN. RENT-OUT stays inflated. The transfer either fails "Insufficient stock at source" (`StockService` :1198) or moves another unit that is in cleaning. `RentalIT` asserts the behaviour; Nest does the same (`rental-return.service.ts` :223). | `RentalReturnService` :107-160, :208 |
| M-8 | P2 | Rental inspect has no guard (no `inspectedAt` check, no `@Idempotent`); a second inspect transfers again. `createReturn` has no lock and no cap. | `RentalReturnService` :206-213; `RentalController` :393 |
| M-9 | P2 | Missing rental items never leave RENT-OUT; no write-off path. | `RentalReturnService` :206-213 |
| M-10 | P2 | Extraction reverse uses non-strict RLS with no on-hand check, so consumed stone is reversed into negative ROM stock. | `QuarryExtractionService` :391-394 |
| M-11 | P2 | Rental swap reads the target unit and line without locks; two swaps can assign one unit. | `RentalSwapService` :70-93 |
| M-12 | P3 | No sourceLineId on production moves although material, output and stage line ids exist. | PS :468, :1191; SES postings |
| M-13 | P3 | Explosive return is a bare receive into a new `RET-` lot (no link, original lot and expiry lost). For STANDARD products the explosive return and extraction receive put the ledger at standard while the GL takes the computed cost (GL-2 pattern). | `QuarryExplosiveService` :550-557; `QuarryExtractionService` :319-320 |
| M-14 | P3 | Process costing: ledger and period check at now, GL at min(periodTo noon UTC, now); no reversal for issue or close. | `ProcessCostingService` :268, :427, :543-547 |
| M-15 | P2 | On-hand pre-checks read without the quant lock, followed by a move that does not require availability: PS `producedOnHand` :900/:916 → issue :936/:944; SES `receiptOnHand` :1124-1149 → non-strict RLS; survey :251 → RLS :257; QC `onHand` :221 → transfer. | as listed |
| M-16 | P3 | Rental quantities pass through `double` (Nest parity). | `RentalPostingService` :65-69, :113 |
| M-17 | P2 | GRN `receivedAt` defaults to the draft's creation time, so a draft posted days later is dated (GL and period check) at creation. | `GoodsReceiptsService` :232, :537 |
| M-18 | P3 | GRN post receipt voucher has no posting key (PO receive has one); only the claim guards it. | `GoodsReceiptsService` :597 vs `PurchaseOrdersService` :969 |
| M-19 | P2 | GRN reversal does not unwind quality-module incoming lots: a quarantined lot makes the strict RLS refuse; a held lot stays open on a reversed GRN. The lot itself is opened post-commit with errors swallowed, so a GRN can post with no lot. | `QualityIncomingService` :71-83; PRC-3 |
| M-20 | P2 | RTV that consumes receipt lines still does not pin their lot; the oldest lot is returned. Extends F7. | `DebitNotesService` :666-670 |
| M-21 | P3 | RTV move (period check, ledger) at now; journal at `issueDate`. | `DebitNotesService` :672, :708 |
| M-22 | P3 | Landed-cost value rows dated now; journal dated `LandedCost.date`. | `LandedCostVoucherService` :619 |
| M-23 | P2 | Material-ledger run double post: plain status read (:229-231) and the UPDATE has no `status = 'draft'` (:272). Two posts with different idempotency keys both pass. The journal key `material_ledger:<id>` dedupes the JE, but `valueRows` (:270) runs twice, so value rows double and the ledger no longer ties to GL. | `MaterialLedgerService` :228-275 |
| M-24 | P3 | Branch transfers have no reversal or cancel path. | `DistributionCostingService` :357 |
| M-25 | P2 | Project issue reversal is a bare receive at the average rate into the first row's location: no `.reverses`, no lot / serial / variant, no over-return check beyond the line status. | `ProjectCostService` :441-453 |
| M-26 | P3 | Supplier claims and quality release / reject / downgrade cannot be undone. | PRC-6, QL-2..QL-4 |
| M-27 | P2 | `POST /sales/orders/{id}/reserve` calls `reserveOrder` with no transaction (`@Transactional` :208 belongs to `followOrder`). `lockCell` commits at once, the free-stock read is unlocked, each `reserve` is its own transaction. Two concurrent reserves can both reserve the same free stock; a failure leaves partial writes. | `SalesAtpService` :208-227; `SalesController` :346-351 |
| M-28 | P2 | Cash-sale void checks "invoiced" on the row read before the lock and does not re-check after locking; `generateInvoice` can commit in between. | `CashSaleService` :1477-1483 |
| M-29 | P2 | Bill cancel takes no supplier lock and reads `absorbingReceipt` unlocked (:355). A concurrent GRN post can absorb the bill's auto-receipt (`BillReceiptMatcherService` :203-208) while the cancel issues the same stock out: stock ends understated and the GRN is billed against a cancelled bill. | `InvoicingWorkflowsInitializer` :305-355 |
| M-30 | P2 | Tank-dip book level = ledger `createdAt ≤ dippedAt`. A fuel log posted after the dip with `fuelledAt` before it is deducted twice (issue and dip loss). Same class as X-1. | `FleetFuelTankService` :230-233 |
| M-31 | P2 | Work-order reversal value drift: the part-return reverse issues at current cost with skipGl while the GL mirrors the return JE at line cost; `returnPart` and the part reverse receive at line cost (a STANDARD item enters at standard). referenceId is the WO code; retry key is random. | `FleetWorkOrderCostService` :131, :175, :309, :319 |
| M-32 | P3 | POD and load-return moves and journals are dated now, ignoring `receivedAt`; the delivery loss uses `Instant.now()`. | `FleetDispatchService` :1203, :1266 |
| M-33 | P3 | Return-inspect reject / scrap issues and the quarantine transfer are not pinned to the lot or serial the return restocked. | `SalesReturnService` :329-344 |
| M-34 | P3 | Past-dated cash sale GL is `saleDate` 00:00 UTC, not the org's business day; west of UTC it lands on the previous local day. | `CashSaleService` :924 |
| M-35 | P3 | Quality reject with `return_to_supplier` posts the engine journal but raises no debit note or AP credit. | `QualityDecisionService` :156 |
