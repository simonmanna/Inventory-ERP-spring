# IPR Phase 1a — delivery, returns and the procurement/sales lock order (evidence)

| | |
|---|---|
| Branch | `fix/ipr-1a` (worktree `/home/ierp-ipr-p0-docs-matrix`), off `fix/ipr-p15` `fb91d736` |
| Migrations | none (no V706–V707 needed); no ADR |
| Engine core | untouched (`StockService`, `StockMove`, `StockReversalService`, `StockPostingService`, `CostResolver`, `LayerCostWriter`, `InventoryLedger`). Returns use `StockReversalService.returnFromSource` / `returnableByProduct` only |
| Ledger | append-only: no UPDATE/DELETE of `InventoryLedger` or posted journals; corrections are linked returns (`reversesLedgerId`). The only fixture row is an INSERT in `InventoryIntegrityP0IT.deliveryReturnOverlap` (legacy put-back) |
| Test DB | `erp-itdb-ipr1` (port 5701), owner-split Postgres 16, RLS on |

Root-cause line numbers are from the base commit `fb91d736`. "Red" is the regression IT run on this branch with the
main-source fixes stashed (tests in place); "green" is the same IT with the fixes. Red logs:
`ipr1a-red2.log` (scratchpad); the Postgres server log of the red run shows the LK-1 cycle:
`ERROR: deadlock detected … Process 643 waits for ExclusiveLock on advisory lock […]; blocked by process 642. Process
642 waits for ShareLock on transaction 5135; blocked by process 643.`

## Findings

| Finding | Root cause (file:line @ fb91d736) | Fix commit | Regression IT (red → green) | Workflow IT | Integrity/GL asserted | Lock levels | Deviations |
|---|---|---|---|---|---|---|---|
| **F1 (D4)** posted invoice whose DO is cancelled / closed short never issues its goods | `FleetDeliveryOrderService.java:598` (cancel frees the mark of drafts only), `:608` (close ignores the invoice), `:410` (`assertNoLiveOrder` + `fromInvoice` creates an `invoiced`-mode DO for a posted invoice) | `c4cad09f` | `DeliveryLifecycleIT.cancelAfterPostThenRedeliver` — red: `FAILED …cancelAfterPostThenRedeliver(): [the cancelled order's mark is released]`; `shortCloseThenCollect`, `invoiceCancelAfterCollection` — red: `Cannot POST /api/v1/invoices/…/issue-undelivered … 404` → green | `DeliveryLifecycleIT` (redeliver, short close + collection, draft cancel unchanged, invoice cancel after collection); `DeliveryConcurrencyIT.collectionVersusNewDelivery` | `assertIntegrityGreen(true, "feature_risk")` + `assertGlTieOut()` after each; `invoice_undelivered` = 0 at the end of every scenario (listed while awaiting, by design) | cancel/close: DO FOR UPDATE (L1) → invoice `Document` FOR UPDATE (L1) → read issue rows; `from-invoice`: invoice FOR UPDATE (L1) → DO insert; `issue-undelivered`: invoice FOR UPDATE (L1) → reservation check cell (L3–L5) → engine issue (L3–L6) → journal (L7) | `data.stockAwaitingDelivery`; new route `POST /invoices/:id/issue-undelivered` (200, `invoice:post`, key required); `from-invoice` on an awaiting invoice = dispatch mode, 409 while an order is open (`fleet.md`, `invoicing.md`) |
| **F1** credit-note restock reads one kind of issue row | `CreditNoteService.java:263` (`invoiceIssueSource` picks `sales_invoice` or fleet rows from the mark) | `c4cad09f` | covered by `podPutBackThenCreditNote` (CN of the 8 delivered restocks against the trip rows after the mark moved) | `DeliveryLifecycleIT.podPutBackThenCreditNote`, `returnCappedByIssued` | same | CN post: invoice (L1) → original issue rows (L2, in `returnFromSource`) → cells → journal | restock source = all rows (`invoicing.md`) |
| **F3** POD / trip-return put-back is an unlinked receipt; a later credit note restocks the same units again | `FleetDispatchService.java:1685` (`stock.receiveReturn(m)` with no source link) | `c4cad09f` | `DeliveryLifecycleIT.podPutBackThenCreditNote` — red: `FAILED …podPutBackThenCreditNote(): [the put-back names the trip's issue row]` → green (CN of 10 refused "Cannot return more than was issued", CN of 8 restocks) | `DeliveryConcurrencyIT.creditNoteCancelAndPutBack` — red: `FAILED …creditNoteCancelAndPutBack(): [units back for 10 issued; outcomes [200, 400, 200]]` (12 back for 10 issued) → green | integrity + GL after; `delivery_return_overlap` = 0 (detector now counts a linked put-back once) | trip (L1, caller `lockTrip`) → original issue rows (L2) → cells (L3–L6) → journal (L7) | put-back over what is still out is refused (`fleet.md`) |
| **F2** sales return restocks goods that never left stock; returnable not capped by the issue | `SalesReturnService.java:252` (legacy `receiveReturn` when no issue codes), `:176` (`returnable` ignores what the issue still has out) | `ff463c3b` | `DeliveryLifecycleIT.returnWithoutDelivery` — red: `FAILED …returnWithoutDelivery(): [nothing left stock, so nothing comes back]`; `returnCappedByIssued` — red: request of 7 accepted (201) after a CN restocked 4 of 10 → green (400; 6 accepted) | `SalesReturnIT.returnWithoutIssueRestocksNothing` (DEMO, projection-only delivery); `SalesReturnIT` fixtures now deliver through a real sales delivery | integrity + GL after both lifecycle scenarios | return receive: return (L1) → sales order + lines FOR UPDATE (L1, LK-3) → issue rows (L2) → cells → journal | financial credit only without an issue; cap (`sales.md`) |
| **F5** sales-order reservations ignored by other issuers | `FleetDispatchService.java:1130` (reservation check only when the DO source has an issue port), invoice post issue loop, `CashSaleService` confirm loop | `c4cad09f` (guard, invoice, collection, dispatch), `ff463c3b` (cash sale) | no dedicated red IT (not re-proven here; behaviour pinned by the guard's refusal path) | `SalesReadinessR6IT.reservationsHonoured` (unchanged, green), lifecycle ITs (no reservations: no refusal) | — | cell advisory (L4) + `StockItem` FOR UPDATE (L5) before the engine's own (re-entrant) | refusal "Only X of P is free in L; Y is reserved for sales orders …" (`sales.md`, `fleet.md`); **partly done**: POS, stock documents, transfers, production, quarry not covered |
| **M-27** `POST /sales/orders/{id}/reserve` without a transaction over-reserves | `SalesAtpService.java:227` (no `@Transactional`; `@Transactional` at :208 sat above the Javadoc of `followOrder`) | `ff463c3b` | `DeliveryConcurrencyIT.reservationsDoNotOverReserve` — red: `FAILED …reservationsDoNotOverReserve(): [round 0]` (three orders of 8 on 10 on hand reserved > 10) → green | same | integrity + GL after | sales order FOR UPDATE (L1) → per line, engine lock order: cell (L3 → L4 → L5) → reservation rows (L6) | none |
| **LK-1 (P1)** receipt updates PO rows before the supplier lock; bill post takes the supplier lock first → deadlock | `GoodsReceiptsService.java:502` (`applyReceiptToPO`) before `:549` (`lockSupplier`); `PurchaseOrdersService.java:916` before `:928` | `7ac19e10` | `ReceiptBillLockOrderIT.poReceiveVersusBillPost`, `goodsReceiptPostVersusBillPost` — forced interleaving (owner connection holds the first receipt's lines). Red: `FAILED …poReceiveVersusBillPost(): [{"message":"This operation collided with a concurrent update and was not applied. Please retry.","statusCode":409…}]` with `deadlock detected` (40P01) in the server log → green (both 200) | `PurchaseReadinessBillsIT`, `PurchaseReadinessReceiptsIT`, `ProcurementIT` (unchanged, green) | stock 10 received, PO line billed 5 | receipt: supplier advisory (L1b) → GRN claim → PO lines → PO → stock; bill post: supplier (L1b) → receipt lines → PO lines → PO | none |
| **LK-3** `SalesOrder` vs `SalesOrderLine` lock order | `SalesFulfilmentService.java:81` (lines locked, then `refreshStatus` locks the order); `SalesBillingService.recomputeInvoiced` (lines updated, then order); `SalesReturnService` receive/close (order read unlocked, lines updated) | `ff463c3b` | no deterministic red IT (the inverted pair needs two event-driven paths on one order; not forced here) | `SalesFulfilmentIT`, `SalesBillingIT`, `SalesReturnIT`, `SalesReturnFlowIT` (green) | — | sales order FOR UPDATE (L1) → lines FOR UPDATE (L1, by id) → stock | none; **partly done** (no race proof) |
| **M-29** bill cancel vs concurrent receipt absorb: no supplier lock, unlocked read | `InvoicingWorkflowsInitializer.java:355` (`absorbingReceipt` read with no lock) | `7ac19e10` | `ReceiptBillLockOrderIT.billCancelVersusAbsorb` — red: `FAILED …billCancelVersusAbsorb(): [cancel {… "documentType":"vendor_bill" …}` (the cancel succeeded while the receipt absorbed the bill's stock) → green (cancel 400, absorbed 4, on hand 6) | `PurchaseReadinessBillsIT.receiptAbsorbsBillReceivedStock` (green) | on hand = 6 (counted once) | supplier advisory (L1b) → `VendorBillAutoReceipt` FOR UPDATE → read → return/release | none |

## Detector changes (report-only, Phase 0 checks)

- `invoice_undelivered` also lists invoices with `data.stockAwaitingDelivery` (until a new DO or a collection issues
  the goods).
- `delivery_return_overlap` counts a put-back without `reversesLedgerId` as before, and a linked put-back once (with
  the linked returns). `InventoryIntegrityP0IT.deliveryReturnOverlap` now builds the defect with a legacy (pre-F3)
  fixture row; `InventoryIntegrityP0IT.invoiceUndelivered` asserts the awaiting flag.

## Commits

| Commit | Scope |
|---|---|
| `c4cad09f` | fix(fleet,invoicing): F1 / F3 / F5 — hand-back, `issue-undelivered`, linked put-backs, CN issue source, detectors; `DeliveryLifecycleIT`, `DeliveryConcurrencyIT`, P0 / invoicing test updates |
| `ff463c3b` | fix(sales): F2, M-27, LK-3, F5 cash sale; `SalesReturnIT` real deliveries + F2 test |
| `7ac19e10` | fix(procurement): LK-1, M-29; `ReceiptBillLockOrderIT` |
| (docs commit) | specs (fleet, invoicing, sales, procurement) and this file |

## Test runs

**Red (fixes stashed, tests in place), 2026-11-27:** `DeliveryLifecycleIT` 1/8 ok (only `draftCancelUnchanged`, the
unchanged path, passes), `DeliveryConcurrencyIT` 0/3, `ReceiptBillLockOrderIT` 0/3. The FAILED lines are quoted in the
table above.

**Final run, freshly reset `erp-itdb-ipr1`, `fastTest`, per-class counts from the init script: 198/198 passed.**

| Class | Tests |
|---|---|
| DeliveryLifecycleIT (new) | 8 |
| DeliveryConcurrencyIT (new) | 3 |
| ReceiptBillLockOrderIT (new) | 3 |
| InventoryIntegrityP0IT | 16 |
| FleetDispatchIT | 12 |
| InvoicingIT (authorization matrix now 82 invoicing routes) | 8 |
| SalesReturnIT | 10 |
| SalesReturnFlowIT | 6 |
| SalesReadinessR6IT | 12 |
| SalesAtpIT | 4 |
| SalesDeliveryIT | 8 |
| SalesDeliveryManagementIT | 8 |
| SalesFulfilmentIT | 2 |
| SalesBillingIT | 7 |
| CashSaleIT | 25 |
| ProcurementIT | 22 |
| PurchaseReadinessBillsIT | 14 |
| PurchaseReadinessReceiptsIT | 9 |
| InventoryReturnsIdentityIT | 8 |
| DocumentReversalIT | 9 |
| Acc0ReversalIT | 4 |

`ArchitectureTests` 5/5 and `ModularityTests` 2/2 ran once via `test --tests`. The full build did not
run; CI runs it on push.

## Not done / partly done

- **F5:** only the invoice auto-issue, `issue-undelivered`, non-sales-order fleet dispatch and cash-sale confirm honour
  sales-order reservations. POS (`PosInvoiceService`, out of scope), stock documents (direct out, transfers, waste,
  adjustments), production, quarry and project issues do not. A full fix belongs in the engine (engine request E-1a
  below).
- **LK-3:** the lock order is fixed, but there is no forced-interleaving red test.
- **F5 regression IT:** the guard's refusal is not exercised by a dedicated IT.

## Engine requests

- **E-1a:** an opt-in reservation check inside `StockService.issue` (a move flag "respect reservations of other
  sources"), so every issuer honours sales-order reservations under the engine's own cell lock. Today the check
  (`StockReservationService.assertNotReservedForSalesOrders`) runs just before the engine, under the same advisory and
  row locks, which the engine then re-takes (re-entrant).
- **E-2a:** a public `StockService.baseQuantity(move)` (today `returnBaseQuantity` is package-private, so
  `InvoiceGoodsService` repeats the snapshot → product unit → 1 factor rule).
