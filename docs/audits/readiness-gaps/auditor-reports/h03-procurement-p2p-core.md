# Auditor report: procurement P2P core (read-only audit of main 46281ee2, 2026-11-17)

Line numbers refer to 46281ee2. Findings fixed since then are listed in [HANDOFF.md](../HANDOFF.md).

VERDICT (procure-to-pay core): Not ready. Receipts entered on the Goods Receipts screen carry no supplier, so the supplier's bill receives and charges the goods a second time. The PO pay route also pays for returned goods and for service lines that are billed separately. Both lose stock or money in normal use.

FINDINGS (ranked, most severe first):

F1 [P0] Receipts from "Goods receipts → New" have no supplier, so the bill receives and charges them again
  where: web/src/pages/procurement/goods-receipt-create.tsx:86-98; backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:172; backend/src/main/java/com/inventoryerp/invoicing/application/BillReceiptMatcherService.java:59-65; backend/src/main/java/com/inventoryerp/invoicing/application/InvoicingWorkflowsInitializer.java:418-470, 577-597
  what: createDraft takes partnerId only from the request body, never from the PO, and the form sends none. The bill matcher requires GRN.partnerId to equal the bill's supplier, so these receipts never match.
  scenario:
  - PO 10×100 → New receipt → post: stock +10, voucher Cr AP 1,000.
  - Supplier bill 10×100 linked to the PO finds no match. auto_receive (the default) receives 10 again: stock 20, AP 2,000.
  - Three-way match still shows "matched" because it counts by PO line (backend/src/main/java/com/inventoryerp/kernel/threewaymatch/ThreeWayMatchService.java:115-118).
  - PO pay offers 1,000 and the bill shows 1,000 open, so the supplier is paid twice.
  - Ad-hoc receipt (no PO): double stock, plus a GRNI credit that nothing clears.
  - The PO page's Receive route does set the supplier and is safe.
  confidence: confirmed; test covering it: none (ProcurementIT always sends partnerId, lines 559/640); known: new (Nest parity, reference/nest-api/src/modules/procurement/goods-receipts.service.ts:116)

F2 [P0] PO pay pays for returned goods and for service lines that are also billed
  where: backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:906-909, 973-979; backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:353; backend/src/main/java/com/inventoryerp/invoicing/application/InvoicingWorkflowsInitializer.java:663
  what: vouchered() sums every posted GRN line that has a productId, including non-stock lines that raised no AP. Nothing deducts RTVs or claim credits. PO payments are applied to bills only through vouchered receipt matches.
  scenario:
  - Service: credit PO for a 300 service → receive → PO Pay defaults to 300. The bill for 300 posts with a 300 residual, and the PO payment is never applied to it. Paid twice.
  - Return: receive 10×100, return 2 by RTV (Dr AP 200). PO Pay "remaining" is still 1,000, so the supplier is overpaid by 200.
  confidence: confirmed; test covering it: none (ProcurementIntegrityIT.serviceLinesAreNotStocked stops before pay); known: new (A3 residue)

F3 [P1] Foreign currency: GRN reversal always fails, and a cash-PO settlement leaves the payable open in the foreign currency
  where: backend/src/main/java/com/inventoryerp/inventory/application/StockReversalService.java:269-271; backend/src/main/java/com/inventoryerp/accounting/application/PostingService.java:878, 915; backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:657-660; backend/src/main/java/com/inventoryerp/accounting/application/RevaluationService.java:162-170
  what:
  - A currency-tagged journal line stores the foreign amount in debit/credit. The reversal re-posts those amounts as base, with no currency.
  - settleCashReceipt uses the postPurchasePayment overload without currency; pay() (lines 949-955) uses the tagged one.
  scenario: UGX organisation, USD PO at a rate of 3,700, receive 10×2 (Cr AP 20 USD = 74,000 UGX).
  - Reverse the GRN: the mirror posts Dr AP 20 against Cr GRNI 74,000 and fails with 400 "Unbalanced entry". The receipt can never be reversed.
  - Same PO as a cash purchase: after settlement AP still shows −20 USD. Every revaluation (payables are reconcilable) books FX on a payable that is already settled; at 3,800 that is Cr AP 2,000 / Dr FX loss.
  confidence: confirmed by reading, not executed; test covering it: none (ProcurementIntegrityIT.foreignCurrencyReceiptConverts covers credit pay only); known: reversal is new; settlement means the G-1/P-4 fix is incomplete

F4 [P1] Debit notes bypass the receipt and bill sub-ledgers (A5 still open, plus more)
  where: backend/src/main/java/com/inventoryerp/procurement/application/DebitNotesService.java:135, 141-142, 356-367, 381-399, 482, 519; web/src/pages/procurement/debit-note-create.tsx:52-59
  what:
  - An RTV always debits AP and never touches GRNI or the receipt's open quantity.
  - Posting ignores the note's currency and exchange rate.
  - documentId is never checked against the supplier, and the web never sends it.
  scenario:
  - Ad-hoc GRN 10×100, return 2, then a bill for 8: GRNI 200 stranded and AP 200 in debit.
  - A return entered on the web leaves the bill fully payable.
  - Through the API, supplier A's note can lower supplier B's bill.
  - EUR 1,000 at a rate of 4,000 posts as 1,000 UGX.
  - Without a location, stock leaves an arbitrary warehouse (LIMIT 1 with no ORDER BY).
  confidence: confirmed; test covering it: none; known: A5 + new

F5 [P1] GRN reversal ignores claims, returns and accruals, and can race bill posting
  where: backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:428-469, 484, 508-510; backend/src/main/java/com/inventoryerp/procurement/application/SupplierClaimService.java:762-763, 787-799; backend/src/main/java/com/inventoryerp/invoicing/application/BillReceiptMatcherService.java:63
  what:
  - Only LandedCost blocks a reversal. Settled stock-adjustment claims, RTVs and LandedCostAccrual do not.
  - The bill, link and payment checks run before the transaction. The transaction then locks only the GRN header, while the matcher locks the lines.
  - PO received quantity is reduced by the raw GRN quantity, although posting added it in PO units.
  scenario:
  - A claim returned 2 of 10. The reversal issues all 10 and reverses the full GRNI/AP: stock ends 2 short, and GRNI or AP is left 200 in debit.
  - A bill posting between the check and the commit matches a receipt that is then reversed, so AP is understated.
  - 48 Piece received against a Box-24 line added 2; the reversal subtracts 48, so received quantity is floored to 0.
  confidence: claims and units confirmed, race likely; test covering it: none; known: P1-6 (partial), new

F6 [P1] Approval gates can be bypassed
  where: backend/src/main/java/com/inventoryerp/invoicing/application/VendorBillService.java:277-303, 380-385; backend/src/main/java/com/inventoryerp/procurement/application/PoChangeService.java:111-123, 193-255; backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:256, 291, 1150-1172; backend/src/main/java/com/inventoryerp/procurement/application/PurchaseRequestsService.java:276-280; backend/src/main/java/com/inventoryerp/kernel/approvals/ApprovalsService.java:302-304
  what:
  - Bill post blocks only a pending vendor_bill request. A rejected bill, or one whose amount was raised after approval, still posts unless supplier_bill_approval is configured (Nest parity).
  - A PO change needs approval from any other po_change:approve holder, and applying it skips workflow amount bands, delegation limits and budget control.
  - PR approve without a workflow, and PO activate, let creators approve their own documents. The kernel blocks self-approval only when the step names users or permissions, or an SoD rule is on.
  scenario:
  - PO approved at 10,000 → changed to 10,000,000 → approved by any po_change:approve holder. The budget "block" setting is never consulted.
  - Bill approved at 1,000 → edited to 100,000 → posts.
  confidence: confirmed; test covering it: none; known: P1-4 residue, P1-10

F7 [P1] Supplier claims: one user, uncapped credit, no reversal
  where: backend/src/main/java/com/inventoryerp/procurement/application/SupplierClaimService.java:172-178, 397-452, 581-617
  what:
  - accept takes any amount.
  - Price-variance and expense credits are not capped against the document; only VAT is (620-645).
  - Without quantity lines, no second person is needed.
  - There is no reversal route.
  scenario: one user with supplier_claim:manage accepts 5,000,000 on a 50,000 bill and settles it: Dr AP / Cr PPV 5,000,000.
  confidence: confirmed; test covering it: none; known: new

F8 [P1] The receiver sets the purchase price
  where: backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:718-719; backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:211, 359
  what: unitCost in the receive or GRN body overrides the PO price with no tolerance. Stock, voucher AP and the PO pay cap all follow it.
  scenario: PO 10×100 received with unitCost 1,000 → stock and AP 10,000.
  confidence: confirmed; test covering it: none; known: new

F9 [P2] Check-then-act, validation and idempotency gaps
  where: backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:271-272, 498-512, 839-851, 1121-1147, 1169-1171; backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:250-255; backend/src/main/java/com/inventoryerp/procurement/web/DebitNotesController.java:44-45; backend/src/main/java/com/inventoryerp/procurement/web/PurchaseOrdersController.java:139-196
  what:
  - PO delete is a hard DELETE after a check made outside the transaction. It skips returnOpenBalance and commitments.sync, so PR lines stay ordered and Commitment rows keep holding budget.
  - activate's UPDATE has no status='draft' guard.
  - GRN draft bodies are untyped. A negative quantity on a non-stock line lowers the PO received quantity; on a stock line it ends in a 500.
  - Debit-note quantity and price are unbounded.
  - Currency defaults to "USD", and a rate ≤0 silently becomes 1.
  - No Idempotency-Key on PO create/update/cancel/activate/delete, PR routes, GRN draft, or debit-note create/cancel.
  confidence: confirmed; test covering it: none; known: P1-14 (partial), new

F10 [P2] Per-row queries in lists
  where: backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:387-390; backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:544-573; backend/src/main/java/com/inventoryerp/procurement/application/DebitNotesService.java:716-735; backend/src/main/java/com/inventoryerp/kernel/threewaymatch/ThreeWayMatchService.java:115-118, 227-230
  what: N+1 lookups; a 1,000-row GRN page runs thousands of queries.
  confidence: confirmed; test covering it: none; known: new

RE-CHECKED EARLIER ITEMS:
- A5: still open. backend/src/main/java/com/inventoryerp/procurement/application/DebitNotesService.java:482 always debits AP; currency stored at 141-142 is never used.
- P1-4: fixed in the kernel (backend/src/main/java/com/inventoryerp/kernel/approvals/ApprovalsService.java:412-430). It reaches bills only through supplier_bill_approval (backend/src/main/java/com/inventoryerp/invoicing/application/VendorBillService.java:428-439). Still open for vendor_bill requests and PO changes (F6).
- P1-5: fixed. backend/src/main/java/com/inventoryerp/invoicing/application/VendorBillService.java:386-399.
- P1-6: partly fixed. The GRN is locked and landed cost re-checked inside the transaction (backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:457-469). But backend/src/main/java/com/inventoryerp/procurement/application/LandedCostService.java:212-214 and backend/src/main/java/com/inventoryerp/procurement/application/LandedCostVoucherService.java:835-837 read the GRN without a lock, and the bill and payment checks are still outside the transaction (438-455).
- P1-8: fixed in the procurement services. Double round-trips remain in backend/src/main/java/com/inventoryerp/invoicing/application/VendorBillService.java:234-235, 314 and backend/src/main/java/com/inventoryerp/invoicing/application/InvoicingWorkflowsInitializer.java:586-587.
- P1-9: mitigated. The BillControls duplicate check exists, but bill.duplicateCheck defaults to off (backend/src/main/java/com/inventoryerp/procurement/application/ProcurementPolicyRegistry.java:95-98).
- P1-10: still open for PRs (backend/src/main/java/com/inventoryerp/procurement/application/PurchaseRequestsService.java:276-280). The landed-cost voucher part is fixed (backend/src/main/java/com/inventoryerp/procurement/application/LandedCostVoucherService.java:415-422).
- P1-13: still open. backend/src/main/java/com/inventoryerp/manufacturing/application/MrpPlanningService.java:355, 375 and backend/src/main/java/com/inventoryerp/manufacturing/application/MrpService.java:173 insert PurchaseRequest rows directly.
- P1-14: partly open (see F9). Keys are optional on GRN reverse and ad-hoc, and on debit-note post and reverse.

STRENGTHS:
- Atomic status claims (backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:311, 473; backend/src/main/java/com/inventoryerp/procurement/application/DebitNotesService.java:410-420) and version-checked PO and PO-line updates (backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:515-538). No double post or over-receipt under concurrency.
- The bill matcher locks receipt lines and handles units and variants. ACR-2/ACR-3 keep bill AP equal to the bill's residual.
- Cash-PO settlement is atomic. The PO lock, payment row, GL entry and drawer pay-out (session locked, overdraw refused) share the receipt's transaction (backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:625-664; backend/src/main/java/com/inventoryerp/accounting/application/CashSessionService.java:805-816).
- GRN post honours pending and rejected approvals (backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:267-286). Receive, pay, GRN post and claim settle all require an Idempotency-Key.
- Every reviewed query filters by organizationId and ids from request bodies are checked against the organisation; no cross-tenant path found.

TEST GAPS:
- A GRN created through POST /procurement/goods-receipts without partnerId, followed by a bill and a PO payment (F1).
- PO pay after an RTV or a claim credit, or for a service line that is also billed (F2).
- Foreign-currency GRN reversal; a foreign cash PO followed by revaluation (F3).
- GRN reversal after a settled claim, RTV or accrual; a reversal racing a bill post (F5).
- A bill posted after a rejected approval or after an edit made post-approval; a PO change above the workflow band or budget (F6).

No files were modified, and no gradle, pnpm, docker or state-changing git commands were run.
