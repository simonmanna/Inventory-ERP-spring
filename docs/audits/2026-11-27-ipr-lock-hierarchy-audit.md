# IPR lock-hierarchy audit (T04b)

| | |
|---|---|
| Date | 2026-11-27 |
| Code audited | `main` 0e670f12 (live), read-only static review |
| Program | Inventory Production Readiness (IPR), Phase 0, task T04b, requirement R2 |
| Companion | `docs/spec/inventory-transaction-matrix.md` (T03): every call site named here has a matrix row |
| Method | Each call site's transaction was read end to end and its lock acquisitions listed in order. No test was run. |

Paths are relative to `backend/src/main/java/com/inventoryerp/` unless they start with `backend/` or `docs/`.

## Summary

The engine itself keeps the L3 → L7 order on every call. The problems are in callers: business rows taken in
different orders on two paths, and transactions that make several engine calls without sorting their cells. There is
no in-process retry anywhere. A deadlock aborts one whole transaction and the client gets 409. Nothing half-commits.

| Id | Sev | Finding |
|---|---|---|
| LK-1 | P2 (borderline P1) | GRN / PO receive vs bill post: PO rows against the supplier advisory lock |
| LK-2 | P2 | GRN post vs GRN reverse on one PO: PO lines against the PO header |
| LK-3 | P2 | SalesOrder against SalesOrderLine: fulfilment subscriber vs sales delivery routes |
| LK-4 | P2 | Reservation cells locked after dispatch engine locks, out of order |
| LK-5 | P2 | 19 multi-cell paths neither sort nor pre-lock their cells |
| LK-6 | P2 | Quarry blast cancel vs explosive issue |
| LK-7 | P2 | Extraction reverse locks the blast after the engine |
| LK-8 | P2 | `LineageFollower.raiseAverage` writes before locking; nodes unsorted |
| LK-9 | P2 | Original ledger rows (L2) locked after quant locks of an earlier line |
| LK-10..LK-19 | P3 | Rows after the engine, journals before the engine, REQUIRES_NEW under locks, value writers without locks, RLS without L2, location-blind sort, no lock timeout, minor notes |
| LK-R1 | P3 | The plan's retry text is wrong for outcome / evidence routes |

## 1. The hierarchy as implemented

Every path must take locks top-down only. A path that holds a lower level and then asks for a higher one is an
**inversion**. Postgres detects a deadlock cycle after `deadlock_timeout` (default 1 s) and aborts one transaction
with SQLSTATE 40P01. There is no data corruption: the loser rolls back. The cost is a failed request, a failed POS job
attempt or a dead-lettered sync op (section 3).

| Level | Lock | How it is taken | Code evidence |
|---|---|---|---|
| L1 | Business document row | `SELECT … FOR UPDATE` on the header, or an atomic status claim (`UPDATE … WHERE status = 'draft'` / `postedAt IS NULL`) | e.g. `StockReversalService.reverseDocument` :150 + claim :166; `StockDocService.claim` :1376; POS `postOnce` job row :2059 |
| L2 | Original `InventoryLedger` issue rows | `SELECT id … WHERE id = ANY(?) ORDER BY id FOR UPDATE` | `StockReversalService.returnFromSource` :579 (the only L2 site) |
| L3 | Costing advisory keys, organisation then product | `pg_advisory_xact_lock_shared(costing:<org>)`, then `(costing:<org>:<product>)`. Exclusive only for cost policy activation (org key) and standard / cost-price activation (product key) | `StockService.holdCostingShared` :1527; `CostingLocks.lockOrganisation` :29, `lockProduct` :35, `shareProduct` :42 |
| L4 | Quant advisory key | `pg_advisory_xact_lock(avco:<org>:<product>:<variantKey>:<location>)`; several cells sorted | `StockService.lockQuantForAvco` :1534; `lockCells` :1493 (product, variant, location); `lockQuants` :1467; transfers lock the two locations in `locationId` order :1189-1195; `LayerCostWriter.lockQuants` :42 |
| L5 | `StockItem` row | `INSERT … ON CONFLICT DO NOTHING` then `SELECT … FOR UPDATE` | `StockService.lockQuant` :1564-1579 (javadoc :1555 "THE lock order of the stock engine"); reservations `StockReservationService.reserve` :73-76, `lockCell` :108-114 |
| L6 | Lot, serial, reservation and bin rows | `UPDATE "InventoryBatch"` (:970, :1245), `UPDATE "InventorySerial"` (:759, :1359), `SELECT … FOR UPDATE` serials on receipt (:394), `StockReservation` upsert (:79) or plain update (`close` :185), `WarehouseBinStock` (warehouse services) | as listed |
| L7 | Journal | `PostingService.post` joins the caller's transaction (`StockPostingService` is `Propagation.MANDATORY` :40). A posting key takes `pg_advisory_xact_lock(hashtextextended(org‖key))` :129; the period check is a read | `accounting/application/PostingService.java` :110-200 |

**Properties of the engine itself (verified):**
- Every engine move takes L3 → L4 → L5 → L6 → L7 in that order: `issue` :625 → :669 → :759/:970 → :866; `receiveCore`
  :271 → :342 → :371/:394 → :452-461; `adjust` :999 → :1021; `transfer` :1159 → :1189-1195 → :1245/:1359 → :1380.
- Re-entry is safe. An advisory or row lock already held by the transaction is granted again at once, so a caller that
  pre-locks with `lockCells` / `lockQuants` and then calls the engine does not wait on itself.
- `SequenceService.next` uses native `nextval` (`kernel/sequence/SequenceService.java:77`). It takes no row lock, so
  the per-move ledger code is not a serialisation point.
- Shared L3 locks never conflict with each other. L3 only matters against the two exclusive holders: cost policy
  activation (`InventoryCostPolicyService`, organisation key) and standard / cost-price activation
  (`StandardCostService`, product key). Both take L3 before their quant locks (`CostingLocks` javadoc :21-22).
- Reservation **writes** that create or resize a reservation take L3 + L4 + L5 first (`reserve` :73). Reservation
  **release / consume** (`StockReservationService.close` :185) is a plain `UPDATE "StockReservation"`: L6 with no L4/L5.
- `StockMove.inLockOrder` (:24) sorts by (productId, variantId) only, not by location. That is enough for a
  single-location document. A multi-location document must also sort by location, or pre-lock with `lockCells`.
- No `lock_timeout` or `statement_timeout` is configured (`backend/src/main/resources/application.yml`; pool size 20
  at :29). A lock wait that is not a deadlock waits without limit.

## 2. Observed acquisition order, per path

Row ids refer to the transaction matrix. "OK" means the path takes locks top-down only. "Doc" is a business row lock
(L1 class) on a row other than the path's own header. The engine part of every path is L3 → L4 → L5 → L6 → L7 (section 1),
so the table records only what the caller does around it.

| Path (matrix rows) | Observed order | Cells sorted? | Verdict |
|---|---|---|---|
| POS sale line, worker or drain (POS-1..4) | job claim → own tx: job FOR UPDATE (:1750) → per line `postOnce`: job FOR UPDATE (:2059) → engine → job UPDATE → InventoryException UPDATE | combo / recipe yes (:2110, :2257); one line per tx | OK. The worker never locks Order, Invoice or CashSession. |
| POS `generateInvoice` (POS-8) | CashSession :490 → Order :498 → L7 → job insert / attach | – | OK |
| Order cancel / void (ORD-1, ORD-2) | CashSession :1323 → Order :1331 → workflow `lockStatus` :118 → jobs ORDER BY id :165 → L2 :579 → engine (RFS sorted :638) → job → Order status | yes | OK |
| POS refund (POS-6, POS-7) | CashSession :1149 → Invoice :1152 → L7 :1263 → Payments sorted :1275 → per selection and ingredient `receiveReturn` (L3–L7 each) → InvoiceItem / Invoice → **Order** (`closeOrderForInvoice` :1487) | **no** (:1326-1330, :1373-1394) | LK-5; LK-10 |
| Kitchen waste (KW-1, KW-2) | order tx commits → REQUIRES_NEW per line → engine per component | **no** (:146-149) | LK-5 |
| Count submit (INV-4, SYN-2) | approval REQUIRES_NEW :697 (no locks yet) → `lockQuants` :703 → session claim :720 → `adjust` sorted | yes | LK-10 (P3) |
| Stock documents (INV-1..5) | approval REQUIRES_NEW :130 → claim → engine in `lockOrder` :124 → own item / header rows | yes | OK |
| Transit dispatch / receive / recall / reverse (INV-6..10) | `ensureTransitLocation` REQUIRES_NEW (outside the tx) → StockTransfer FOR UPDATE :930 → engine; reverse pre-locks `lockCells` :280 | yes | OK (dispatch fragile if a caller ever wraps it in a tx) |
| Warehouse ship (INV-14) | pick order FOR UPDATE → approval REQUIRES_NEW :514 **under that lock** → engine sorted → own lines → WarehouseWave :578 | yes | LK-12, LK-10 (P3) |
| Revaluation (VAL-1) | doc FOR UPDATE :131 → per pool sorted: L3 → L4 :495 → L7 → `setUnitCost` (L5 / L6 after L7) | yes | OK (L5 after L7 is safe: engine paths always take L4 first) |
| Standard cost / cost policy (VAL-3, VAL-5) | document row → **exclusive** L3 (product :491 / org :286) → StockItem FOR UPDATE (`lockQuants(advisory=false)`) | yes | OK. Exclusive L3 excludes every engine move on that product / org. |
| Material ledger (VAL-7) | run row read **unlocked** → L7 → value rows, no L3 / L4 | – | M-23; LK-15 |
| GRN post (PRC-1) | claim :482 → **PO lines + PO** UPDATE (`applyReceiptToPO` :502 → `PurchaseOrdersService` :580/:597) → GRN lines → **supplier advisory** :549 → per line `inLockOrder`: auto-receipt absorb (VendorBillAutoReceipt FOR UPDATE + GRN / PO line UPDATE, `BillReceiptMatcherService` :208-232) → engine → QC transfers (control-point order) → L7 voucher → PurchaseOrder / CashSession (`settleCashReceipt`) | receipt yes; QC transfers no | **LK-1**, **LK-2**, LK-5, LK-10 |
| PO receive (PRC-2) | GRN insert → PO lines + PO (:916) → supplier advisory :928 → engine in **input order** (:921) | **no** | **LK-1**, LK-5 |
| GRN reverse (PRC-3) | GRN :673 → GRN lines :687 → **PO** FOR UPDATE :693 → claim :703 → `qc:` advisory + QcCheck → QC transfers → IncomingInspection :721 → RLS (sorted) → **PO lines** UPDATE :767 → PO :787 | yes | **LK-2**; LK-10 |
| Debit-note RTV (PRC-4) | claim :587 → bill Document (`assertBillReturnable` :249) → per note line: GRN lines FOR UPDATE :776 → engine → next line … → L7 :708 | **no** | LK-5 (GRN lines taken between engine calls) |
| Debit-note reversal (PRC-5) | Document :949 → DebitNote :954 → **L7 reversals :971** → RFS (L2 → L3–L6) → GRN lines :864 | yes | LK-11, LK-10 (P3) |
| Supplier claim (PRC-6) | SupplierClaim :523 → bill / GRN / PO → claim + GRN lines :843 → engine sorted by (product, variant) only :883 → L7 | partly | LK-17 (P3) |
| Branch transfer, project issue (PRC-7, PRC-8) | engine → L7 → adjunct value rows | single move | OK |
| Project reversal (PRC-9) | ProjectCostLine :432 → **L7 `reverseAt` :441** → bare receive | single | LK-11 (P3) |
| Landed-cost voucher (LC-1, LC-2) | GRN FOR SHARE ORDER BY id :2064 → claim :570 → `lockPools` (one sorted `LayerCostWriter` call) :593 → layers / StockItem / value rows → L7 :619 → bill inserts | yes | OK; `lockPools` follows lineage before locking (LCL :153-160), LK-18 |
| Lineage follow (LC-6) | **value row :318** → L3 :409 → L4 :410 → StockItem :411; nodes in follow order | **no** | **LK-8** |
| Accrual unwind, capitalisation (LC-4, LC-5) | own row → value rows, **no L3 / L4** | – | LK-15 |
| Quality decisions (QL-2..QL-5) | `lockLot` FOR UPDATE (QLS :382) → engine → decision → L7; downgrade issues A then receives B | downgrade **no** | LK-5 |
| QC legs (QC-1) | QcCheck FOR UPDATE or `qc:` advisory → unlocked on-hand read :221 → transfer | single | M-15 |
| Cash sale confirm (SAL-1) | SalesSubmission :1687 → engine `inLockOrder` :926 → deposit JE, accrual | yes | OK |
| Cash sale void (SAL-2) | fiscal REQUIRES_NEW (no locks yet) → SalesSubmission :1483 → L7 accrual :1486 → RFS (L2 → sorted) → CustomerDeposit (`CustomerDepositService` :316/:329) | yes | LK-11, LK-10 (P3); M-28 |
| Sales return receive (SAL-3) | SalesReturn :505 → per line, DB order: RFS [**L2 of that line** → L3–L7] → SalesOrderLine UPDATEs (:499) | **no** | LK-5, **LK-9**, LK-10 |
| Sales return inspect (SAL-5..7) | SalesReturn → per line: issue and / or transfer | **no** (:310) | LK-5 |
| Sales delivery issue route + fulfilment (FLT-1 via `SalesDeliveryService.issue` :888) | **SalesOrder** :890 → DO → trip → per line `lockCell` + issue → FleetLoadLine / DOL → `rebuild`: **SalesOrderLine** FOR UPDATE (`SalesFulfilmentService` :82) → SalesOrder `refreshStatus` (:1196) → `followOrder` → `lockCell` per open line | dispatch yes; follow-up cells no | **LK-3**, **LK-4** |
| `SalesFulfilmentSubscriber` (after a fleet-route event) | **SalesOrderLine** :82 → **SalesOrder** :1196 → `followOrder` cells | – | **LK-3** |
| Fleet dispatch, fleet route (FLT-1) | DO FOR UPDATE → trip (`lockTrip` :1581-1585) → per line `inLockOrder` :1116: `lockCell` :1130 → issue :1146 → own lines → trip → DO | yes (single yard) | OK on its own |
| POD / return load (FLT-2, FLT-3) | DO → trip → per line in createdAt order: `receiveReturn` → own lines → delivery-loss L7; `VirtualLocationService.ensure` REQUIRES_NEW on first use | **no** (:1226, :1497) | LK-5, LK-12 |
| Fuel, tyre, dip, work order (FLT-4..10) | log → vehicle → one cell → L7; dip → tank → `adjust`; tyre → vehicle → issue; WO → lines → one cell | single cell | OK |
| Invoice post (INVC-1) | Document :723 → Partner :725 → sequence → workflow Document :255 → per line `inLockOrder` :176 → issue → L7 | by product only | LK-17 (P3) |
| Invoice cancel (INVC-2) | approval `newTx` (no locks yet) → Document :849 → deposit void → workflow lock → **L7 reverse :250** → RFS (L2 → sorted) | yes | LK-11 (P3) |
| Bill post (INVC-3) | workflow Document → **supplier advisory :427** → GRN lines (matcher :75) → L7 :589 → per line `inLockOrder`: **PO line** UPDATE (`consume` :607 → matcher :144/:234) → `receiveFromBill` → **PurchaseOrder** FOR UPDATE :718 | yes | **LK-1**; LK-10 |
| Bill cancel (INVC-4) | Document :631 → workflow lock → `absorbingReceipt` **unlocked** :355 → L7 reverse → GRN / PO line UPDATEs → issue ORDER BY createdAt :315 → variance L7 | effectively yes | M-29 (no supplier lock) |
| Credit-note post (INVC-6) | approval `newTx` → invoice + CN ORDER BY id :534 → sequence → L7 :573 → per CN line, **document order** :603: RFS (L2 → L3–L7) | **no** | LK-5, LK-9 |
| Credit-note reverse (INVC-7) | CN + invoices ORDER BY id :704 → L7 reversals → issue rows ORDER BY product, variant, location | yes | OK |
| Production start / confirm / complete / reverse (MFG-1..9) | adv(order) → per material / output in child order: engine; confirm opens `uom.toBase` REQUIRES_NEW :390 inside the loop; reverse opens `ensure` :895; RFS loop takes L2 of material n+1 after L4 of material n | **no** | LK-5, LK-9 (P3), LK-12 |
| Stage paths (MFG-10..15) | adv → `ProductionOrderStage` FOR UPDATE :1770 → engine (backflush `sequence` order, intermediate, receives) → L7 → own rows → reservation release :1228; `quarantineLocation()` :522 after backflush locks | **no** | LK-5, LK-12 |
| Crushing run, survey (QRY-1..3) | run / survey row → pile advisory → adv → stage → engine | single or via stage | OK |
| Processing job (QRY-4, QRY-5) | create: reservations sorted :299. Start: job :385 → adv → per input in createdAt order: reservation `consume` :466 → issue | start **no** | LK-5, LK-13 |
| Process costing (MFG-17, MFG-18) | period FOR UPDATE :530 → issue → L7. Close: period → L7 :393 → **next process's period** :406 → receive | single | LK-18 (P3) |
| Explosive issue (QRY-6) | **issue :456 → blast :470** → lines in lineNumber order → engine → L7 → blast UPDATE | **no** | **LK-6**, LK-5 |
| `QuarryBlastService.cancel` | **blast :250 → issues :260-261** | – | **LK-6** |
| Extraction post (QRY-8) | log :285 → **blast :513** → receive :319 (L4 ROM) → L7 → blast UPDATE :349 | single | **LK-7** |
| Extraction reverse (QRY-9) | log :386 → RLS (**L4 ROM**) → L7 :396 → **blast** FOR UPDATE :400 | single | **LK-7** |
| Repair (RPR-1, RPR-2) | part :97/:153 → engine → part UPDATE | single | OK |
| Rental (RNT-1, RNT-2, RNT-4, RNT-5) | `RentalLocationConfig` upsert of all 5 rows (:57-65) → transfer (two L4 in locationId order) → deposit insert → fee invoice → units / lines → agreement UPDATE :338 last | lines **no** | LK-5 (masked by LK-14); M-11 |
| Reservations (SAL-8, INV-16, POS-5) | `reserve`: L3 + L4 + L5 → reservation row. Release / consume: plain UPDATE. `/sales/orders/{id}/reserve`: each call its own tx | sorted for POS and processing; sales per line | M-27 |

## 3. Inversions and risks

Severity for a lock finding:
- **P1**: a deadlock that normal daily use will hit, or a lock gap that corrupts stock or GL.
- **P2**: a real deadlock cycle under plausible concurrency. The loser gets 409 (or a POS / sync retry); nothing half-commits.
- **P3**: a hierarchy violation with no cycle found today, or a pool / latency risk.

No finding here corrupts data by itself. Every deadlock aborts one whole transaction. The data risks found while
tracing are the matrix findings M-23, M-27, M-28 and M-29.

### LK-1 (P2, borderline P1): goods receipt vs supplier-bill post, PO rows against the supplier lock

- **Receipt side.** `GoodsReceiptsService.post` updates PurchaseOrderLine and PurchaseOrder rows in `applyReceiptToPO`
  (`GoodsReceiptsService` :502 → `PurchaseOrdersService` :580, :597). It takes the supplier advisory
  `bill-receipt:<org>:<partner>` only afterwards (:549, `BillReceiptMatcherService.lockSupplier` :159-163).
  `PurchaseOrdersService.receive` does the same (:916 → :928).
- **Bill side.** `InvoicingWorkflowsInitializer.postBill` takes the supplier lock first (:427). Then it updates
  PurchaseOrderLine `billedQuantity` (`consume` :607 → `BillReceiptMatcherService` :144 / :234) and locks
  PurchaseOrder FOR UPDATE (:718).
- **Deadlock.** The clerk posts GRN-2 on PO-1 while AP posts the bill that matches GRN-1 of PO-1. Tx A holds PO-1's
  line rows and waits for the supplier lock. Tx B holds the supplier lock and waits for a PO-1 line, or for PO-1 at :718.
- **Why borderline P1.** Receiving and billing against the same open PO in the same minutes is a normal workflow, and
  both routes are money routes. The loser's work rolls back cleanly. On outcome routes the 409 is cached against the key
  (section 4).
- **Fix direction.** Take `lockSupplier` before `applyReceiptToPO` in both receipt paths. That gives one order everywhere:
  supplier → PO → PO line → engine.

### LK-2 (P2): GRN post vs GRN reverse on one purchase order, PO header against PO lines

- **Post.** PurchaseOrderLine UPDATE, then PurchaseOrder UPDATE (`PurchaseOrdersService` :580 → :597).
- **Reverse.** PurchaseOrder FOR UPDATE (`GoodsReceiptsService` :693). After RLS it updates PurchaseOrderLine (:767).
- **Deadlock.** GRN-2 post holds PO-1 line X and waits for PO-1. GRN-1 reverse holds PO-1 and waits for line X.
  The two GRNs are different headers, so their own L1 locks do not serialise them.
- **Related.** `applyReceiptToPO` updates PO lines in receipt-line order (`PurchaseOrdersService` :552-585), not by id.
  Two posts on one PO with reversed line order deadlock (409) instead of returning the 400 "modified by another user"
  (LK-18).
- **Fix direction.** Lock PurchaseOrder first on every path (post included), then the lines in id order.

### LK-3 (P2): SalesOrder against SalesOrderLine, fulfilment subscriber vs sales delivery routes

- **Subscriber path.** `SalesFulfilmentSubscriber` :57 → `SalesFulfilmentService.rebuild` locks SalesOrderLine rows
  (:82), then SalesOrder in `SalesOrderService.refreshStatus` (:1196).
- **Route path.** `SalesDeliveryService.issue` locks SalesOrder first (:890; comment "sales order → delivery order →
  trip → order lines"). It reaches `rebuild` → SalesOrderLine at :907 → :82. POD and return routes do the same.
- **Deadlock.** T1, the subscriber for trip A dispatched on the fleet route, holds SO-1's lines and waits for SO-1.
  T2, a sales-route issue, POD or return of trip B on SO-1, holds SO-1 and waits for its lines.
- **Also.** `SalesReturnService.receive` updates every SalesOrderLine of the order after its engine locks (:499). The
  subscriber holds those lines and then takes cells in `followOrder`. That is a second cycle (lines ↔ L4).
- **Fix direction.** `rebuild` locks the SalesOrder before its lines.

### LK-4 (P2): reservation cells taken after the dispatch engine locks, out of product order

- **Where.** `SalesDeliveryService.issue` :907 → `rebuild` → `SalesAtpService.followOrder` → `reserveOrder` → `lockCell`
  per open line (:272). This runs while the transaction still holds L4 for the cells it just dispatched.
- **Deadlock.** The trip dispatched P1 and P2 and now reserves open line P0. A cash sale or invoice that issues P0 then
  P2 at the same location holds L4(P0) and waits for L4(P2). The dispatch holds L4(P2) and waits for L4(P0).
- **Fix direction.** Defer `followOrder` to after commit (it is soft state), or pre-lock all cells, open lines
  included, with `lockCells` before dispatch.

### LK-5 (P2): multi-cell transactions that neither sort nor pre-lock their cells

- **The defect.** The engine sorts within one call only. A caller that makes several engine calls for different
  products in one transaction must call them in (product, variant, location) order, or pre-lock with
  `StockService.lockCells` (:1493).
- **Deadlock.** Tx A posts cells [P2, P1] and tx B posts [P1, P2] at the same location. Any sorted path (cash sale,
  invoice post, GRN post, stock document) has B's order. Each holds one L4 and waits for the other.

These paths do not sort:

| Path | Evidence |
|---|---|
| POS refund restock | `PosInvoiceService` :1326-1330, :1373-1394 |
| Kitchen waste components | `KitchenWaste` :146-149, :99-117 |
| PO receive | `PurchaseOrdersService` :921 |
| Debit-note RTV (also locks GRN lines between engine calls) | `DebitNotesService` :640-672, :776 |
| Quality downgrade (A → B) | `QualityDecisionService` :207 / :223 |
| QC quarantine transfers inside GRN post | `QcGate` :195-205 → `QcQuarantineService` :79 |
| Sales return receive | `SalesReturnService` :227 |
| Sales return inspect | `SalesReturnService` :310 |
| POD | `FleetDispatchService` :1226 |
| Return load | `FleetDispatchService` :1497 |
| Credit-note post | `CreditNoteService` :603 |
| Production start | `ProductionService` :449-490 |
| Production confirm | `ProductionService` :383-400 |
| Production reverse | `ProductionService` :936-953 |
| Stage backflush | `StageExecutionService` :417-433 |
| Stage reverseOrder | `StageExecutionService` :1043 |
| Processing job start | `ProcessingJobService` :465-475 |
| Explosive issue | `QuarryExplosiveService` :479 |
| Rental checkout and swap | `RentalAgreementService` :299-302; `RentalSwapService` :104-106 (masked by LK-14) |

- **Impact.** The whole transaction aborts with 409:
  - a POS refund loses its money and GL legs;
  - a POS job line becomes a backoff retry;
  - a sync op is dead-lettered as retryable.
- **Fix direction.** This is one engine helper, not nineteen local fixes (engine request E-4).

### LK-6 (P2): quarry blast cancel vs explosive issue

- **Issue.** `QuarryExplosiveService.issueStock` locks the explosive issue (:456), then the blast (:470).
- **Cancel.** `QuarryBlastService.cancel` locks the blast (:250), then the issues (:260-261).
- **Deadlock.** Issuing explosives for a planned blast while the same blast is cancelled.
- **Fix direction.** Blast first on both paths.

### LK-7 (P2): extraction reverse locks the blast after the engine

- **Reverse.** `QuarryExtractionService` reverse: log (:386) → RLS, which holds L4 of the ROM cell → `postings.reverse`
  → blast FOR UPDATE (:400).
- **Post.** Posting another log of the same blast: log → blast (:513) → receive into the same ROM cell (:319).
- **Deadlock.** The reverse holds L4(ROM) and waits for the blast. The post holds the blast and waits for L4(ROM).
- **Fix direction.** Lock the blast before the RLS.

### LK-8 (P2): `LineageFollower.raiseAverage` writes before its lock and locks nodes unsorted

- **Write before lock.** The value-only ledger row is inserted (`LineageFollower` :318) before L3 / L4 / L5 are taken
  (:409-411).
- **Unsorted nodes.** Pool nodes are locked in follow order (source → destination), not sorted. This applies outside a
  pre-locked voucher, e.g. `LineageExceptionService.assign` :180.
- **Deadlock.** Following pool L9 then L2 against an engine transfer of the same product that locks L2 then L9.
- **Race.** The amount is computed before the lock, so units issued in between keep a capitalised share on hand.
- **Fix direction.** Take `LayerCostWriter.lockQuants` over all nodes first, then compute and write.

### LK-9 (P2): original ledger rows (L2) taken after quant locks of an earlier line

- **Sales return receive.** RFS runs once per line (`SalesReturnService` :227 → :240). Line n+1's L2 rows are locked
  after line n's L4.
- **Deadlock.** Return R (lines P2, P1) against credit note C on the same invoice, whose source is the same fleet_trip
  issue rows. C locks L2 over both rows in id order: it gets the P1 row and waits for the P2 row. R holds the P2 row
  and L4(P2), and waits for the P1 row. **P2.**
- **Credit-note post** has the same per-line shape (:603).
- **Production cancel / reverse** has the same shape (`ProductionService` :1237), but adv(order) already serialises
  the only contenders. **P3.**
- **Fix direction.** Engine request E-5: lock all L2 rows for a document once, in id order, before any engine call.

### LK-10 (P3): business rows locked after engine locks

| Path | Business row taken after the engine locks | Evidence |
|---|---|---|
| POS refund | Order (workflow close) | `PosInvoiceService` :1352 → :1487 |
| Count submit | session claim, after `lockQuants` | `InventoryCountService` :703 → :720 |
| Sales return receive | SalesOrderLine | `SalesReturnService` :499 (cycle in LK-3) |
| GRN post | PurchaseOrder and CashSession | `settleCashReceipt` |
| GRN reverse | PO lines | :767 (cycle in LK-2) |
| Bill post | PurchaseOrder | `InvoicingWorkflowsInitializer` :718 (cycle in LK-1) |
| Debit-note reversal | GRN lines | `DebitNotesService` :864 |
| Landed-cost post | bill inserts | LCV :634 |
| Cash sale void | CustomerDeposit | `CustomerDepositService` :316 / :329 |
| Warehouse ship | WarehouseWave | `WarehouseOutboundService` :578 |

Beyond the cycles named in LK-1..LK-3, no path was found that locks these rows first and then an engine cell.

### LK-11 (P3): journal (L7) before the engine

These paths post or reverse a journal before L2–L6:
- `DebitNotesService` :971;
- `ProjectCostService` :441;
- cash-sale void accrual (`CashSaleService` :1486);
- invoice cancel (`InvoicingWorkflowsInitializer` :250);
- credit-note post (`CreditNoteService` :573).

Posting-key advisories are unique per document and sequences use `nextval`, so no cycle exists. This is a hierarchy
violation to remove when those paths are touched.

### LK-12 (P3): REQUIRES_NEW opened while locks are held

The inner transaction never waits on the outer one's locks: it inserts a new location or approval row. So there is no
self-deadlock, but each needs a second pooled connection while the first holds locks:

| Call | Where it runs | Evidence |
|---|---|---|
| `uom.toBase` | inside the production-confirm reservation loop | `ProductionService` :390 |
| `VirtualLocationService.ensure` (first use per org only) | production reverse; stage output; POD reject location; QC hold on receipt; quality lot quarantine | `ProductionService` :895; `StageExecutionService` :522; POD reject location; `QcQuarantineService` :57; `QualityLotService` :212 |
| pick-order approval | under the pick-order row lock | `WarehouseOutboundService` :514 |
| count approval | inside the sync `stock.count` op transaction | `InventoryCountService` :697 |

The pool has 20 connections (`application.yml` :29), so N concurrent callers can starve it. This is the same family as
the 2026-11-23 CI hang, which was a REQUIRES_NEW inside an advisory lock. Prefer savepoints, or create the row before
taking locks.

### LK-13 (P3): reservation row written before its quant lock

- `ProcessingJobService` start runs `consume` (StockReservation UPDATE, :466) before the issue's L3–L5 for that cell
  (:469).
- No other path writes that reservation row while holding L4, so this is a note only.

### LK-14 (P3): rental config upsert is an organisation-wide mutex

- Every rental transfer first upserts all five `RentalLocationConfig` rows (`RentalLocationConfigService` :57-65, :91).
- That row-locks the same rows for every rental move, which serialises all rental stock per organisation.
- It hides the LK-5 rental cases today. It will surface them if the upsert is ever made conditional.

### LK-15 (P3): value-only writers take no quant lock

These writers take no L3 / L4:
- `MaterialLedgerService.valueRows` (:312-345);
- `LandedCostAccrualService` unwind (:562);
- `LineageCapitalisation.record` / `reverse` (:146-200).

`CostLineageService.recordValueOnly` reads the quant quantity unlocked (:176-179). A concurrent issue makes the
row's `qtyBefore` / `balanceAfter` and the adjunct split stale. Combined with M-23, the material-ledger rows can also
double.

### LK-16 (P3): `reverseLedgerSource` takes no L2 lock

- RLS (`StockReversalService` :363) reads the original rows without FOR UPDATE.
- It relies on the caller's L1 claim to stop a second reversal of the same source. Every caller checked has one:
  document claim, GRN claim, posting `reversedAt` check, part status, log status, survey status.
- A future caller without a claim could reverse twice. RFS, by contrast, locks L2 (:579) and nets earlier returns.

### LK-17 (P3): `StockMove.inLockOrder` ignores location

- `StockMove.inLockOrder` (:24) sorts by (productId, variantId) only.
- Paths whose lines can name different locations for one product are not fully ordered:
  - invoice post with per-line `lotWarehouse` (`InvoicingWorkflowsInitializer` :176);
  - supplier claim (`SupplierClaimService` :883).
- Two such documents for one product at two locations, in opposite order, can deadlock.

### LK-18 (P3): minor ordering notes

- PO lines are updated in receipt-line order, not id order (`PurchaseOrdersService` :552-585).
- Process-costing close takes a second process's period row after a journal (`ProcessCostingService` :393 → :406).
  Process chains are acyclic, so there is no cycle.
- Landed-cost `lockPools` follows lineage before locking (`LandedCostLayers` :153-160). A pool created by a transfer
  that commits in between is locked later, out of order.

### LK-19 (P3): no lock timeout

- No `lock_timeout` or `statement_timeout` is set (`backend/src/main/resources/application.yml`).
- A lock wait that is not a deadlock (for example behind a long revaluation, or a REQUIRES_NEW waiting for a pool
  connection) waits without limit while holding its own locks and a connection.
- Postgres catches deadlock cycles after `deadlock_timeout`, but nothing bounds plain waits.
## 4. Retry policy (40P01 / 40001 / 55P03)

A grep of `backend/src/main/java` for `40P01`, `40001`, `CannotAcquireLock`, `DeadlockLoser`,
`PessimisticLockingFailure`, `ConcurrencyFailure`, `@Retryable`, `RetryTemplate`, `SERIALIZABLE` and `lock_timeout`
finds **no in-process retry loop anywhere**. No transaction runs at SERIALIZABLE, so 40001 is not expected; 40P01
(deadlock) and 55P03 (lock not available, only with `NOWAIT`/`lock_timeout`, neither used) are the relevant states.

| Entry point | What happens on 40P01 | Retries? | Evidence |
|---|---|---|---|
| Any HTTP route | `GlobalExceptionHandler` maps `PessimisticLockingFailureException` and any exception whose cause chain carries 40P01/40001/55P03 to **409** "This operation collided with a concurrent update and was not applied. Please retry." | No server retry | `kernel/web/GlobalExceptionHandler.java:210-233`, `:255-258`; test `kernel/web/LockConflictMappingTest` |
| HTTP route with `Idempotency-Key`, **not** an outcome route (all inventory document routes: approve, dispatch, receive, count submit, direct stock, reversals) | The work rolled back and nothing committed, so the interceptor **releases** the key. A retry with the **same** key runs again. | Client | `kernel/idempotency/IdempotencyInterceptor.java:190-195`; `IdempotencyService.release` :301-310 |
| HTTP **outcome / evidence** route (`IdempotencyService.OUTCOME_ROUTES` :52-56, `EVIDENCE_ROUTES` :64-70: POS checkout, POS refund, invoice post/cancel, credit-note post/reverse, cash-sale void, sales-order deliveries/invoices, payments) | `IdempotencyService.fail` :274-290 caches any status < 500 for that key as `completed` with `safeToRetry: true`. The 409 from a deadlock is < 500, so it is **cached**: a retry with the **same** key replays the 409. The client must start again with a **new** key. POS web screens do this (`web/src/pages/pos/CancelOrderDialog.tsx:63`, `web/src/features/pos/offline-queue.ts:245`). | Client, new key only | as listed |
| POS stock-posting worker (`StockPostingWorker.drainBatch`, shift drain `PosStockPostingService.drainSession` :258, sync drains `SyncPushService` :1592/:1606) | Each job line runs in its own short transaction (`postOnce`). A deadlock in a line is caught per line (`issueStockForItems` :2027) and recorded as a line failure; a deadlock elsewhere in the job is caught at :1799. The job goes back to `pending` with backoff `min(60 s × attempts, 15 min)` (:1821, :1950), and becomes `failed` after `maxAttempts`; then only a supervisor retry (`PosStockPostingService.retryJob` :119) or abandon moves it. | Yes, by backoff (bounded) | `pos/application/PosInvoiceService.java:1730-1827`, `:1937-1955`, `:2056-2076` |
| Sync push ops (`SyncPushService.applyOp`) | A non-`ApiException` is status 500 (:461). `retryable(500)` is true (:295-297), so the op is dead-lettered with `retryable = true` and the response tells the device it may resend the **same** `opId`. A supervisor can also retry the dead letter (`retry` :627). | Device / supervisor, same opId | `sync/application/SyncPushService.java:460-483` |
| Scheduled jobs that move stock or value (`SyncStockJob.tick` :34, `StandardCostService` activateDue :630, `InventoryCostPolicyService` :307, `ReplenishmentScanJob` :32, `RentalCronWorker` :40) | Per-org or per-item try/catch logs the failure; the next tick runs it again. | Next tick | as listed |
| Internal `REQUIRES_NEW` helpers (`VirtualLocationService.ensure`, `StockDocService.ensureTransitLocation`, approval requests) | They lock nothing the caller holds, so they cannot deadlock with the caller. They need a **second pooled connection** while the caller holds the first (pool 20). | – | LK-12 |

**Consequences for the plan (R2 text).** The plan says "HTTP routes do not retry; the client retries with the same
`Idempotency-Key`". That is true only for routes outside `OUTCOME_ROUTES` / `EVIDENCE_ROUTES`. On the money and
evidence routes a deadlock 409 is cached against the key, so a same-key retry replays the 409. The web client already
copes: `web/src/lib/idempotent-request.ts` `classify` (:139-151) treats every 4xx as `rejected` and releases the key
(:185-187), and `useIdempotencyKey.settle` (`web/src/lib/idempotency.ts:29-32`) drops the key on any answered error,
so the user's next attempt gets a new key. Finding **LK-R1** (P3) corrects the plan text; any other HTTP client of
those routes must mint a new key after a 409 that carries `safeToRetry: true`.

## 5. Recommendations and engine requests

The engine core (`StockService`, `StockMove`, `StockReversalService`, `StockPostingService`, `CostResolver`,
`LayerCostWriter`, `InventoryLedger`) is frozen in Phase 0 (rule R8). These are requests to the engine owner. Phase 0
changes nothing.

| Id | Request | Fixes |
|---|---|---|
| E-1 | `receiveFromBill` keeps the move's `sourceLineId`, `reverses` and source fields instead of rebuilding the move (`StockService` :158-162). | matrix 5.2 (INVC-3) |
| E-2 | `adjust` and `transfer` honour `dto.date` for the period check and the journal date, and bare `receive` uses `dto.date` for its period check (:270, :998, :1051, :1137, :1155, :1380). | matrix 5.3 |
| E-3 | An effective-time column on `InventoryLedger` beside `createdAt` (a migration, so Phase 1 or later). | M-30, X-1, matrix 5.3 |
| E-4 | A multi-move entry point, or a documented pre-lock contract, that locks every cell of a document in (product, variant, location) order before the first move. Also make `StockMove.inLockOrder` sort by location. | LK-5, LK-17 |
| E-5 | `returnFromSource` for a whole document: one L2 lock over all source rows in id order, then all moves sorted. | LK-9 |
| E-6 | `reverseLedgerSource` locks the source rows (L2) itself and nets earlier reversals. | LK-16 |
| E-7 | A stable engine posting key for `issue` (today `inventory:issue:<org>:<ledgerCode>`, new per call, so it never matches a retry). | retry safety |

Caller-side fixes for Phase 1, in priority order:
1. LK-1: `lockSupplier` before `applyReceiptToPO` (`GoodsReceiptsService` :502 / :549; `PurchaseOrdersService` :916 / :928).
2. LK-2: PurchaseOrder before PurchaseOrderLine on GRN post; PO lines in id order.
3. LK-3: `SalesFulfilmentService.rebuild` locks the SalesOrder before its lines.
4. LK-4: `followOrder` after commit, or pre-lock its cells.
5. LK-6, LK-7: blast row first in the quarry paths.
6. LK-8: pre-lock lineage nodes in `LineageFollower`.
7. LK-5: adopt E-4 in the 19 unsorted paths.
8. LK-12, LK-19: replace REQUIRES_NEW under locks with savepoints or pre-created rows; set a `lock_timeout` for HTTP
   transactions (needs an owner decision on the value).
9. LK-R1: correct the plan's retry text (section 4).

Each Phase 1 fix should ship with a two-transaction concurrency IT for its pair. No such test exists today
(matrix 5.4).
