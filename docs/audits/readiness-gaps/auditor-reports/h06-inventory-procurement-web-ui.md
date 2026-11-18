# Auditor report: inventory procurement web UI (read-only audit of main 46281ee2, 2026-11-17)

Line numbers refer to 46281ee2. Findings fixed since then are listed in [HANDOFF.md](../HANDOFF.md).

VERDICT (web: inventory + procurement): Ready after fixes. I found no P0, and the riskiest money and stock calls send stable keys while the server enforces permissions and quantities, but four P1s must be fixed first: supplier-return pricing, unkeyed transfer receipts, staff pickers that need `user:read`, and a likely Stock Ledger crash.

FINDINGS (ranked, most severe first, max 15):

F1 [P1] Supplier-return debit-note lines default to the sales price
  where: web/src/pages/procurement/debit-note-create.tsx:73-86; backend/src/main/java/com/inventoryerp/procurement/application/DebitNotesService.java:443-491
  what: Picking a product sets `unitPrice = salesPrice ?? costPrice` in both directions. Only changing the unit switches to the purchase price (:87-93). Posting a return to vendor (inbound + returned_goods) debits AP and lowers the linked bill by that price, then books price − stock cost as "Return price variance".
  scenario: Return 10 kg of beans that cost 8 and sell for 15, keeping the default price → AP and the supplier bill drop by 150 + tax instead of 80, a gain of 70 is booked, and the supplier is short-paid.
  confidence: confirmed; known: new

F2 [P1] Transfer receipts and inventory-document actions get a new Idempotency-Key on every attempt
  where: web/src/features/inventory/transfers-api.ts:49-173 (receive :152), web/src/lib/api.ts:79-88; backend/src/main/java/com/inventoryerp/inventory/web/InventoryController.java:534-536, backend/src/main/java/com/inventoryerp/inventory/application/StockDocService.java:929-934
  what: Plain `api.post` gets a fresh key per attempt, so the server's `@Idempotent` never recognises a retry. Partial receipts are accepted until the open quantity runs out. Buttons are disabled while pending, so this is triggered by lost responses, not double-clicks.
  scenario: Receive 5 of 10 in transit → the request times out after 30 s but the server has committed → the dialog stays open (it closes only on success, web/src/pages/inventory/StockTransfersPage.tsx:509) → the user clicks Receive again → 10 are booked at the destination, 5 of them phantom. Waste, adjustment, transfer, GRN, PO, debit-note and claim creates have the same gap (e.g. web/src/pages/inventory/WastePage.tsx:211, web/src/pages/procurement/goods-receipt-create.tsx:88). After a timeout they leave a duplicate pending document, one unconfirmed "Approve" away from a double movement.
  confidence: confirmed (static, both sides); known: same class as P1-15

F3 [P1] Stock forms need `user:read`, which the inventory roles lack
  where: web/src/features/inventory/staff-options.ts:15-31; web/src/pages/inventory/WastePage.tsx:206, StockAdjustmentsPage.tsx:155, StockTransfersPage.tsx:169, DirectStockActions.tsx:43; backend/src/main/java/com/inventoryerp/kernel/staff/UsersController.java:68-69, backend/src/main/java/com/inventoryerp/inventory/web/InventoryController.java:96,140-162, backend/src/main/java/com/inventoryerp/kernel/seed/SeedRunner.java:71-79, backend/src/main/java/com/inventoryerp/kernel/seed/ConstructionDemoRoles.java:54-59
  what: Responsible and Approved-by are mandatory on both client and server, and the pickers load from `GET /users`, which needs `user:read`. No seeded non-admin role has that permission.
  scenario: The seeded Supervisor opens Waste → a "Permission denied" toast, empty staff lists, and Post stays disabled. The same happens for adjustments, transfers and stock in/out. The only workaround is to grant read access to the whole user directory.
  confidence: confirmed; known: new

F4 [P1] Stock Ledger likely renders a blank page
  where: web/src/pages/inventory/StockLedgerPage.tsx:118,130,142; pnpm-lock.yaml:1261; web/src/pages/products.tsx:45
  what: The page uses `<SelectItem value="">` with @radix-ui/react-select 2.4.1, which throws on an empty item value; the codebase itself notes this at products.tsx:45. There is no error boundary, and BrowserRouter has no error element, so the throw unmounts the whole app.
  scenario: Inventory → Stock Ledger → white screen, at the latest when a filter is opened.
  confidence: likely (not run in a browser; web/src/pages/products/ProductEditPage.tsx:586 has the same pattern, so opening either page settles it); known: new

F5 [P2] "Receive all remaining" float maths trips the over-receipt check
  where: web/src/pages/procurement/PurchaseOrderReceivePage.tsx:97-104, web/src/pages/procurement/goods-receipt-create.tsx:79; backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:496-512
  what: The page sends `Number(qty) − Number(received)` with its floating-point noise. The server compares BigDecimals exactly, with a default tolerance of 0. Transfers allow a small epsilon; POs do not. The server recomputes totals and the payable amount, but on receive it trusts the client's `unitCost` over the PO price (:718-719).
  scenario: Ordered 10.3 kg, received 10.1 → the button fills in 0.20000000000000107 → 400 "Over-receiving PO line" for a correct receipt.
  confidence: confirmed; known: new

F6 [P2] PO Pay screen shows its own balance and always pays from bank, dated today
  where: web/src/pages/procurement/PurchaseOrderPayPage.tsx:34-47,62-64,136; backend/src/main/java/com/inventoryerp/procurement/web/PurchaseOrdersController.java:99-105, backend/src/main/java/com/inventoryerp/procurement/application/PurchaseOrdersService.java:905-926,944
  what: The page computes "Remaining" as total − paid, ignoring the vouchered and billed amounts the server uses. If the amount is left blank, the server pays its own remaining figure. The page never sends method, cashRegisterId or paidAt.
  scenario: A 1,000 PO with a 400 bill already posted → the button reads "Pay 1,000"; with the amount blank, 600 is paid. A supplier paid from the till is booked to bank.
  confidence: confirmed; known: new

F7 [P2] After a 5xx, every retry of a keyed call returns 409 OPERATION_PENDING
  where: web/src/lib/idempotent-request.ts:50-66; backend/src/main/java/com/inventoryerp/kernel/idempotency/IdempotencyService.java:151-172,248-278
  what: The client keeps its key after a 5xx. After any failure the server leaves the record pending, except on POS/cash/expense/payment routes, which are released automatically. It then answers "…needs recovery… Retry with the same key." The client treats that as non-final and keeps the key again.
  scenario: A transient 500 on PO receive or GRN post → every later click shows the same message until the tab is closed or a field is changed.
  confidence: likely (both sides read, not run); known: new

F8 [P2] Pickers silently truncated
  where:
    - `/inventory/locations` without pageSize returns only the 25 newest (backend/src/main/java/com/inventoryerp/kernel/crud/BaseCrudService.java:64,83). Inventory callers: web/src/pages/inventory/WastePage.tsx:156, StockAdjustmentsPage.tsx:108, StockTransfersPage.tsx:124, DirectStockActions.tsx:38.
    - Procurement location callers: web/src/pages/procurement/debit-note-create.tsx:48, PurchaseOrderReceivePage.tsx:47, goods-receipt-create.tsx:54, purchase-order-create.tsx:70.
    - `/products?pageSize=200`: purchase-order-create.tsx:63, goods-receipt-create.tsx:65, debit-note-create.tsx:44, StockTransfersPage.tsx:116.
    - `/partners?pageSize=200`: debit-note-create.tsx:40.
  what: Plain selects over a single page, with no search and no warning.
  scenario: With 30 locations, the oldest (usually the main warehouse) cannot be picked for waste or transfers, and product #201 cannot go on a PO.
  confidence: confirmed; known: new

F9 [P2] Product dropdowns keyed by index and left uncontrolled
  where: web/src/pages/procurement/purchase-order-create.tsx:281-287; web/src/pages/procurement/debit-note-create.tsx:179-185
  what: Rows use `key={idx}` with `defaultValue`. After a line is removed, each dropdown still shows the old row's product while state holds the next row's.
  scenario: Delete line 1 of 3 → the screen shows A, B but the PO saves B, C.
  confidence: confirmed in code (not run); known: new

F10 [P2] Irreversible actions with no confirmation or pending guard
  where: web/src/pages/procurement/debit-notes.tsx:119-124; web/src/pages/procurement/goods-receipt-detail.tsx:198-207; web/src/features/procurement/landed-cost-panel.tsx:130-139; web/src/pages/inventory/StockTransfersPage.tsx:226,236; web/src/pages/procurement/s2p/ControlsPages.tsx:233,253-255,284
  what:
    - Debit-note Post and Cancel have no confirmation and stay clickable while pending.
    - GRN Post, landed-cost Post/Cancel and transfer Approve/Cancel fire on a single click.
    - Claim Raise, Accept and Settle stay clickable while pending; create and accept use plain `api.post`.
  scenario: Double-click "Raise claim" → two claims. Double-click debit-note Post → "Updated" plus an OPERATION_PENDING error.
  confidence: confirmed; known: new

F11 [P2] Gaps in UI permission gating
  where: web/src/pages/inventory/StockAdjustmentsPage.tsx (no checks at all); web/src/pages/procurement/PurchaseOrderDetailPage.tsx:124-145; web/src/pages/procurement/debit-notes.tsx:77-79,117-126; web/src/pages/inventory/DirectStockActions.tsx; web/src/App.tsx:547-554,622-626,628-629
  what: The server enforces the same permission strings, so nothing leaks. But users see actions they cannot run, and a 403 is toasted twice (web/src/lib/api.ts:171 plus the call site). Call sites that read `e.response.data.message` directly run validation-message arrays together (web/src/features/inventory/transfers-api.ts:137).
  scenario: A clerk without `inventory_doc:approve` clicks Approve on an adjustment → two "Permission denied" toasts.
  confidence: confirmed; known: new

F12 [P2] Lists capped or unpaginated
  where: backend/src/main/java/com/inventoryerp/procurement/application/DebitNotesService.java:727-728 (LIMIT 200), with no pager at web/src/pages/procurement/debit-notes.tsx:36-39; web/src/pages/procurement/s2p/ControlsPages.tsx:86 (claims: page 1 only); waste, adjustment and transfer lists unpaginated end to end.
  what: No total, no pager and no warning.
  scenario: Debit note #201 never appears; a year of transfers loads in one response.
  confidence: confirmed; known: new

F13 [P2] A landed cost posted on a receipt cannot be reversed from the web
  where: web/src/features/procurement/landed-cost-panel.tsx:130-139; backend/src/main/java/com/inventoryerp/procurement/web/LandedCostController.java:103-109, backend/src/main/java/com/inventoryerp/procurement/application/GoodsReceiptsService.java:428-435
  what: `POST /procurement/landed-costs/{id}/reverse` exists but has no button, and GRN reverse is refused once a landed cost is posted.
  scenario: Freight posted to the wrong receipt can only be undone through the API.
  confidence: confirmed; known: new

F14 [P2] Dead link and raw-UUID inputs
  where: web/src/pages/procurement/PurchaseOrderDetailPage.tsx:349 vs web/src/pages/procurement/routes.tsx:53-54 and web/src/App.tsx:654; web/src/pages/procurement/goods-receipt-create.tsx:131-142; web/src/pages/inventory/StockLedgerPage.tsx:110-111
  what: The PO's purchase-request link points to an `:id` route that does not exist. GRN create asks for a PO UUID. The ledger filters by a typed product UUID and fires one request per keystroke.
  scenario: Clicking the request number on a PO lands on the home page. A storekeeper opening "New receipt" has to paste a UUID.
  confidence: confirmed; known: new

RE-CHECKED EARLIER ITEMS: P1-15: fixed. Receive and pay now call `idempotentPost` (web/src/pages/procurement/PurchaseOrderReceivePage.tsx:54-70, web/src/pages/procurement/PurchaseOrderPayPage.tsx:34-38). Each payload gets one random key, held in sessionStorage until success or a definitive 4xx (web/src/lib/idempotent-request.ts:38-66). Both buttons are disabled while pending (PurchaseOrderReceivePage.tsx:219-225, PurchaseOrderPayPage.tsx:131-134). Both routes are `@Idempotent(required = true)` (backend/src/main/java/com/inventoryerp/procurement/web/PurchaseOrdersController.java:157,169). The same class of bug is still open on transfers and inventory documents (F2).

STRENGTHS:
- Stable keys on PO receive/pay, GRN post/reverse, debit-note post/reverse, landed-cost create/post/cancel, claim settle, count submit and direct stock in/out. Keys from `idempotentPost`/`idempotentPatch` survive a page reload.
- No endpoint drift found: every call I traced matches an existing route's verb, query parameter names (including the strictly checked ledger parameters) and response envelope.
- The 409 lock-conflict message reaches users verbatim, and a retry correctly gets a new key.
- Where the web gates actions, its strings match `@RequirePermissions` (e.g. transfers, web/src/pages/inventory/StockTransfersPage.tsx:89-91). Every procurement route sits behind RequirePermission.
- Reversals and forced submits require a reason. A failed GRN post leaves the user on the saved draft.

GAPS:
- There are no web tests under web/src, and the only e2e test is reference/e2e/pos-sell-loop.spec.ts. None of these screens has been run against Spring in a browser.
- F4, F7 and F9 are inferred from code; a short click-through would confirm or clear them.
- Not field-checked: WarehousePage response shapes, the S2P RFQ/agreement/quality/sourcing pages, replenishment and the reports.
- Warehouse actions (web/src/features/inventory/warehouse-api.ts:216-223) send no stable key, and the controller has no `@Idempotent`. I did not trace their state guards.
- How far F3 reaches depends on how customers set up roles; I checked only the seeded roles.
