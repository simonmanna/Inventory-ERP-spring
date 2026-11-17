# Auditor report: inventory stock engine (read-only audit of main 46281ee2, 2026-11-17)

Line numbers refer to 46281ee2. Findings fixed since then are listed in [HANDOFF.md](../HANDOFF.md).

VERDICT (inventory stock engine, documents, reversals, reservations, counts): Ready after fixes — locking, the append-only ledger and claim-guarded documents hold and no P0 was found, but five P1 gaps can put the stock GL account out of line with the sub-ledger or let an approval control be bypassed (F1, F2, F3 and F5 match Nest, so fixing them needs an owner-approved deviation).

FINDINGS (ranked, most severe first):

F1 [P1] Serial items moved without serials are issued at master costPrice
  where: backend/src/main/java/com/inventoryerp/inventory/application/StockService.java:628-636; :268-284 and :293-295 (receipt without serials allowed, at actual cost); :343-352 (a re-received serial keeps its old unitCost); backend/src/main/java/com/inventoryerp/kernel/settings/SettingRegistry.java:61-64 (capture_optional is the default)
  what: The default policy lets a serial-tracked item be received without serials at actual cost. When it is issued no serial is in stock, so the units leave at Product.costPrice and no correction entry is posted.
  scenario: costPrice 90. Receive 1 at 100 without a serial (Dr Inventory 100), then sell it (Cr Inventory 90). On hand is 0 but the stock account holds 10. This repeats for every unit, and the tie-out then blocks period close.
  confidence: confirmed; test: none; known: described but not raised in docs/audits/2026-11-15-sales-variant-uom-inventory-audit.md:307; Nest parity (reference/nest-api/src/modules/inventory/stock.service.ts:831-874).

F2 [P1] Money-banded approvals never fire on stock-outs and compare units on adjustments and transfers
  where: backend/src/main/java/com/inventoryerp/inventory/application/StockDocService.java:151-157 (totalValue stays at its default 0, backend/src/main/resources/db/migration/V1__baseline.sql:8494), :179-180, :546-553 (Σ|qtyDiff|), :764-768 (Σ qtyRequested); backend/src/main/java/com/inventoryerp/kernel/approvals/ApprovalsService.java:184; backend/src/main/java/com/inventoryerp/kernel/approvals/SegregationOfDutiesService.java:31-42
  what: The stock-out gate always sends amount 0, so no workflow with minAmount > 0 matches. The adjustment and transfer gates send a unit count instead of a value. Waste and count submit (deviation 11) send value; these three do not. No separation-of-duties pair covers stock-out or waste approval either.
  scenario: A workflow requires approval for stock_out at minAmount 1,000. A "complimentary" stock-out of 40 bottles at 60 is approved with amount 0 and posts 2,400 of expense with no approval request.
  confidence: confirmed; test: none; known: new; Nest parity (reference/nest-api/src/modules/inventory/stock-doc.service.ts:127, 516-520, 676-680).

F3 [P1] A posting rule that resolves no account silently skips the journal
  where: backend/src/main/java/com/inventoryerp/inventory/application/InventoryPostingRuleService.java:73-77, :183-234; backend/src/main/java/com/inventoryerp/inventory/application/StockPostingService.java:115, :698-704; backend/src/main/java/com/inventoryerp/inventory/application/PostingRuleAdminService.java:190-213 (validates literal accounts only)
  what: When every rule line resolves to null (no key, or a field key that is neither set nor mapped), resolve() returns an empty list and the move commits with no GL entry. The other cases are safe: with no rule it falls back to AccountMapping, and one null side or an inactive account fails the move.
  scenario: An admin saves a STOCK_OUT rule pair with accountSource category_field and no key, and it is accepted. From then on every STOCK_OUT issue of a product with a category moves stock and posts nothing until the tie-out flags it.
  confidence: confirmed; test: none (InventoryIT.postingRuleValidation only checks the request shape); known: new; Nest parity (reference/nest-api/src/modules/inventory/posting/posting-rule.service.ts:89).

F4 [P1] Transit receive is not idempotent, contrary to spec deviation 16
  where: StockDocService.java:900-1001 (row lock and outstanding cap at :929-934, no claim); backend/src/main/java/com/inventoryerp/inventory/web/InventoryController.java:534-540 (Idempotency-Key optional); web/src/lib/api.ts:73-87 (new key per request); web/src/features/inventory/transfers-api.ts:152; docs/spec/inventory.md:133-137
  what: A partially received transfer accepts the same request body again, capped only by the quantity still in transit. A resubmit carries a new key, so nothing is replayed.
  scenario: Dispatch 10. Post a receipt of {received 4, short 1}; the response is lost and the user resubmits. The destination now books 8 but holds 4, a second unit is written off, and the transfer closes before the rest arrives.
  confidence: confirmed; test: InventoryIT.transit (over-receipt only); known: new.

F5 [P1] Direct stock-in accepts a negative unit cost
  where: InventoryController.java:87; backend/src/main/java/com/inventoryerp/inventory/application/DirectStockService.java:113-118; StockService.java:259-260, :293-295; StockPostingService.java:446-449
  what: The negative cost feeds the AVCO average or a lot. The stock-gain GL path returns silently on a non-positive value; only postReceipt rejects it.
  scenario: AVCO, 10 on hand at 10. A direct-in of 10 at −10 gives an average of 0, a ledger value of −100 and no journal. The stock account holds 100 against a sub-ledger of 0, and later issues cost 0.
  confidence: confirmed; test: none; known: new; Nest parity (reference/nest-api/src/modules/inventory/dto/direct-stock.dto.ts:25-27).

F6 [P2] Adjustment approve checks for stock drift outside the quant lock, then writes an absolute count
  where: StockDocService.java:577-608 (unlocked read), :610-617; StockService.java:865-888
  what: A sale that commits between the drift read and the quant lock is overwritten. This is the same pattern already fixed with deltas as A-2/A-3 in backend/src/main/java/com/inventoryerp/manufacturing/application/CrushingSurveyService.java:227-233 and backend/src/main/java/com/inventoryerp/fleet/application/FleetFuelTankService.java:410-419.
  scenario: An adjustment counts 8 against a system 10. During a long approval the POS sells 1, so the adjust takes 9 to 8. On hand ends at 8 when the truth is 7, and the loss is under-posted.
  confidence: likely; test: none concurrent; known: new for this path.

F7 [P2] Count variance is computed from separate unlocked reads
  where: backend/src/main/java/com/inventoryerp/inventory/application/InventoryCountService.java:594-610, :657, :686
  what: The delta (counted + moves since the count − on-hand) comes from three separate queries run before any quant lock. A move that commits between the moves query and the StockItem query appears only in on-hand.
  scenario: A sale commits between those two queries. The delta comes out 1 too high and the sale is erased.
  confidence: likely (window of milliseconds); test: InventoryIT.countsAgainstCountTime (sequential); known: new.

F8 [P2] Count cancel has no status check in its update
  where: InventoryCountService.java:852-860 (assertDraft at :466-475 is unlocked), compared with the submit claim at :733-739
  what: A cancel that read "draft" before a concurrent submit committed still writes "cancelled".
  scenario: A cancel runs at the same time as a submit. The adjustment is posted, but the session reads cancelled and drops out of count reporting.
  confidence: confirmed; test: InventoryIT.counts at :396 (sequential only); known: new.

F9 [P2] Transit reversal moves written-off units out of transit without lot pins
  where: backend/src/main/java/com/inventoryerp/inventory/application/StockReversalService.java:169-176
  what: Written-off units are restored in the shared TRANSIT location, then moved back to the source oldest lot first. This ignores the transfer's StockTransferLayer pins and its carried cost.
  scenario: T1 (lot L1, 1 short) is completed while T2 (older lot L0) is in transit. Reversing T1 sends L0 back to the source, and T2's pinned receive no longer finds it.
  confidence: likely; test: none (no transit reversal test); known: new.

F10 [P2] A transfer line's lot and pick strategy are ignored
  where: StockDocService.java:788-793 (approve), :879-884 (dispatch)
  what: The oldest lots move whatever lot the line names, so lot and expiry records at both locations stop matching the shelves. Nest parity (stock-doc.service.ts:703-712).
  confidence: confirmed; test: none; known: new.

F11 [P2] Document lists are unbounded, with queries per row
  where: StockDocService.java:1132-1166
  what: Every document is loaded, then its items, location and receipts row by row. Spec deviation 23 makes this deliberate, but it is a real production risk: a year of documents means thousands of queries per screen load.
  confidence: confirmed; test: n/a; known: docs/spec/inventory.md deviation 23.

RE-CHECKED EARLIER ITEMS (docs/audits/2026-11-15-* unless noted):
- U-3: fixed — StockService.java:855-856, :1006.
- C-1: fixed — StockService.java:416-429. SPECIFIC products always get serial or batch tracking (backend/src/main/java/com/inventoryerp/core/application/ProductService.java:427-429), so the ledger and the GL value the same layer.
- R-1: fixed — StockService.java:1408-1429, backend/src/main/resources/db/migration/V487__inventory_ledger_traceability.sql.
- S-2: fixed — backend/src/main/java/com/inventoryerp/inventory/application/StockMove.java:24-34, StockReversalService.java:222-227, backend/src/test/java/com/inventoryerp/inventory/StockLockOrderTest.java.
- S-3: fixed, but not proven under concurrency — backend/src/main/java/com/inventoryerp/inventory/application/LayerCostWriter.java:40-54.
- S-8 / D5: fixed — StockPostingService.java:461-468, backend/src/test/java/com/inventoryerp/inventory/OpeningStockIT.java.
- docs/audits/2026-11-17-accounting-costing-review.md #12: still open — StockService.java:264-266 (with no active standard, receipts post at actual cost) vs :700-705 (issues post at costPrice).

STRENGTHS:
- (Q1) Each quantity change writes one InventoryLedger row under the quant lock (StockService.java:1408-1429). A BEFORE UPDATE/DELETE trigger keeps the ledger append-only (V1__baseline.sql:2204-2218, :17176). Its purge bypass is role-gated and no app code sets it (backend/src/main/resources/db/migration/V470__inventory_ledger_hygiene.sql:21-30).
- There is one lock protocol: advisory lock, then StockItem FOR UPDATE (StockService.java:1288-1303), with lines sorted (StockMove.java:24-34). Revaluation and lineage reuse it (backend/src/main/java/com/inventoryerp/inventory/application/InventoryRevaluationService.java:334-336, backend/src/main/java/com/inventoryerp/inventory/application/LineageFollower.java:391-398). Deadlocks surface as 409.
- (Q4, Q5) Approve, reverse and count submit each claim their row inside the transaction that moves stock and posts (StockDocService.java:1173-1177, StockReversalService.java:97-103, InventoryCountService.java:733-739). Direct stock and count submit require an Idempotency-Key. reverseLedgerSource has no guard of its own, but its inventory and goods-receipt callers claim first.
- (Q6) The period lock is checked at every engine entry (StockService.java:172, 232, 479, 843, 985) and again in backend/src/main/java/com/inventoryerp/accounting/application/PostingService.java:133.
- (Q7, Q8) Sales ATP locks the cell before measuring (backend/src/main/java/com/inventoryerp/sales/application/SalesAtpService.java:232-270), and processing reservations are checked under the quant lock (StockService.java:750-765). Direct reserve is soft, as in Nest. A missing rule falls back to AccountMapping; an unmapped key or a summary/inactive account fails the whole move (except F3).

TEST GAPS:
- GL vs sub-ledger tie-out for serial items moved without serials, negative or zero-cost direct-in, and rules that resolve no account.
- Concurrency: a retried transit receive, count cancel vs submit, adjustment approve vs a sale, count submit vs a sale, and two simultaneous reversals (InventoryIT only reverses twice in sequence, :236-240).
- Reversal of a transit transfer with lots; a transfer that names a lot.
- Approval workflows with a minAmount on stock-out, adjustment and transfer.
- Revaluation, landed cost and cost-policy activation running against the engine (backend/src/main/java/com/inventoryerp/inventory/application/InventoryCostPolicyService.java:315-318 row-locks without the quant advisory lock).
