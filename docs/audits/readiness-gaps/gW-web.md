# gW: web screens for inventory and procurement (auditor report h-06)

Branch `phase/gaps-web`. Web only: no backend code, no migration, no Gradle run.

Scope: h-06 findings F2, F5, F7, F8, F9, F10, F11, F12 and F14. F1, F3 and F4 were fixed before this phase. F6
belongs to gD and F13 to gF.

**How this was checked.** The web app has no unit tests, and nothing was run in a browser.
- `cd web && pnpm exec tsc --noEmit -p tsconfig.json`: clean.
- `pnpm exec eslint` on every changed and new file: 0 errors. The 21 warnings are all pre-existing
  `no-explicit-any`; every touched file has as many or fewer than before.
- `node web/scripts/i18n-phrases.cjs --check`: passes. Two phrases this change removed from the source
  ("Filter by product ID…", "PO id (auto-fills lines)") were dropped from `ti`/`lg`/`sw`.
- The "Checks" line under each finding records what was verified by reading the code.

## Shared pieces

New files:
- `web/src/lib/quantity.ts`: exact quantity maths at 6 decimals (`subtractQty`, `roundQty`), using integer
  micro-units.
- `web/src/lib/use-search-picker.ts`: a debounced server search for a picker. When the server holds more matches
  than it sent, the picker footer says so.
- `web/src/features/products/product-search.ts`: a product picker over `GET /products`.
- `web/src/features/inventory/locations.ts`: every stock location (`pageSize=1000`, sorted by code). It says when
  even that is capped.
- `web/src/features/procurement/supplier-picker.ts`: a debounced supplier search over
  `GET /procurement/suppliers/options`.
- `web/src/components/confirm-dialog.tsx`: a confirmation dialog that cannot be dismissed while its action runs.
- `web/src/lib/use-client-page.ts`: client-side paging for lists the server returns whole.

Additive changes to existing files:
- `SearchableSelect` takes an optional `footer`.
- `lib/api.ts` exports `wasNotified`.
- `lib/api-error.ts` adds `describeApiError` and `notifyApiError`.

## h06-F2: Transfer receipts and inventory-document actions get a new Idempotency-Key on every attempt

- **Status:** fixed on the client.
  - Waste, adjustment and transfer routes are `@Idempotent` on the server today, so a retry is now replayed there.
  - GRN draft create, PO create, debit-note create and claim create/accept are not `@Idempotent` yet. They send
    the key, but the server ignores it until it adds the annotation (cross-stream note 1).
- **Verified:** these calls used plain `api.post`, so every attempt got a fresh key:
  - `features/inventory/transfers-api.ts`;
  - `WastePage.tsx` and `StockAdjustmentsPage.tsx`;
  - `goods-receipt-create.tsx`, `purchase-order-create.tsx` and `debit-note-create.tsx`;
  - `claimApi.create` and `claimApi.accept`.
- **Behaviour change:** these calls now use `idempotentPost`.
  - It keeps one key per payload in sessionStorage until a success or a definitive 4xx.
  - So a retry after a lost answer, a double click or a page reload sends the same key.
  - Routes covered:
    - transfers: create, approve, dispatch, receive, recall, cancel and reverse. Receive therefore already sends the
      key that gA makes required.
    - waste: create, approve (both "post now" and the list button), cancel and reverse.
    - adjustments: create, approve, cancel and reverse.
    - GRN draft create, PO create, debit-note create, and supplier claim create and accept.
- **Direct stock in/out** keeps its per-dialog key from `useIdempotencyKey`.
  - Reason: its body carries the approver PIN, which must not feed a payload fingerprint kept in sessionStorage.
  - `useIdempotencyKey.settle` now also keeps the key after a 409 `OPERATION_PENDING` or the 409
    "…is being processed concurrently…".
  - Before, any answered error dropped the key. The next click could then post again while the first attempt was
    still open.
  - The same hook keys the count submit on `InventoryCountPage`, so the fix applies there too.
- **Dialogs** close only on success.
  - The transfer create and receive dialogs show the error inline (`role="alert"`) and stay open. Reopening a
    dialog clears the old error.
  - The waste and adjustment create dialogs stay open next to the error toast.
- **Explicit payloads:** GRN create and debit-note create build each line field by field, so client-only fields
  (row ids, variant names) are never sent. `CreateDebitNoteRequest` rejects unknown fields.
- **Deviation from Nest:** none; this is client only.
- **Checks:**
  - `IdempotencyInterceptor.preHandle` returns before reading the header when a handler has no `@Idempotent`. So
    sending the key to the unannotated routes is harmless today.
  - Each keyed payload is built deterministically from the form: the same form gives the same key, and an edited
    form gives a new one.
  - Grep finds no `api.post`/`api.patch` left for these writes in the touched screens. The remaining calls are:
    - direct stock, keyed by `useIdempotencyKey`;
    - the budget check, which is read-only;
    - purchase-request submit/approve/reject and PO cancel, which move no stock or money.

## h06-F5: "Receive all remaining" float maths trips the over-receipt check

- **Status:** fixed.
- **Behaviour change:**
  - **PO receive page:** "receive all" and the Remaining column subtract exactly (10.3 − 10.1 = 0.2, not
    0.20000000000000107). Quantities sent are rounded to 6 decimals.
  - **GRN create from a PO:** the remaining quantity uses the same exact subtraction, and the quantities sent are
    rounded to 6 decimals.
  - **Transfer receive:** outstanding = dispatched − received − damaged − short − recalled, computed exactly.
  - **PO receive no longer sends `unitCost`.** The page has no cost field and used to send the PO line price. The
    server values a PO receipt at the PO line price when none is sent, which is the rule gD makes.
- **Deviation from Nest:** none. The receipt value is unchanged, since it was already the PO line price.
- **Checks:**
  - `subtractQty`/`roundQty` were exercised in a throwaway Node run (not committed): 10.3−10.1, 1−0.9,
    "5.000000"−"4.999999", exponent strings and non-numbers.
  - The receive payload filters to quantities > 0 before rounding.

## h06-F7: After a 5xx, every retry of a keyed call returns 409 OPERATION_PENDING

- **Status:** fixed on the client. The server half (releasing an attempt that rolled back) is gA's.
- **Verified:** `idempotent-request.ts` kept the key after every non-4xx and treated the 409 as non-final. So each
  click showed the same "needs recovery" message until the tab was closed.
- **Behaviour change** (`lib/idempotent-request.ts`, `lib/api-error.ts`): the client records, per key, whether
  earlier attempts failed with a server answer (5xx) or went unanswered.
- **On 409 `OPERATION_PENDING`:**
  - **Every earlier attempt failed with a server answer**, or the server sends `safeToRetry: true` in the body or in
    `recovery`. The toast reads "The last attempt of this action failed on the server, but the server still holds
    it as unfinished. Check the list: if it is not there, start a fresh attempt."
    - It offers a "Start a fresh attempt" button.
    - The button drops the key and says "Submit it again to make a fresh attempt."
  - **Otherwise** (an attempt went unanswered and may have committed), the key is kept. The message reads "An
    earlier attempt of this action has no final result yet and may already be saved. Check the list before you try
    again."
- **Other outcomes:**
  - **409 "…is being processed concurrently…":** the key is kept. The message reads "This action is already being
    processed. Wait a moment, then check the list before you try again."
  - **No answer (timeout or network):** the key is kept. The message reads "The server did not answer, so this may
    already be saved. Check the list before you submit it again." It shows inline in dialogs, and as a toast unless
    the connection-lost toast already showed.
  - **Any other 4xx, including the lock-conflict 409 ("…was not applied. Please retry."):** the key is dropped, so a
    corrected form gets a new one.
- **Key release** happens only while the slot still holds that key, so overlapping attempts cannot bring back a
  released key.
- **Residual risk:** a commit followed by a 500 (the record fails to complete after the business commit) looks like
  a failed attempt. The "check the list" wording covers it.
- **Deviation from Nest:** none; this is client only.
- **Checks:** I walked through `classify`/`send` for each case against `IdempotencyService.begin`:
  - cases: 2xx; 400; lock-conflict 409; `OPERATION_PENDING` after a 500, after a timeout, and after a timeout then a
    500; the concurrent 409; a 500; no response.
  - server behaviour checked:
    - a pending or indeterminate record gets the 409 body `{code, message, operationKey, recovery}`;
    - a hash mismatch gets the 409 "different request body";
    - an insert race gets "being processed concurrently".

## h06-F8: Pickers silently truncated

- **Status:** fixed.
- **Locations.** These pickers now load every location into a searchable picker:
  - waste, adjustments, transfers, direct stock and the ledger filter;
  - PO receive, GRN create, PO create and the debit note.

  Warehouse pickers filter with `type=warehouse` on the server. Above 1,000 locations the picker says "Only the
  first {{n}} locations are listed."
- **Products** (PO create, GRN create, debit note, transfers, direct stock, ledger filter): server search on code,
  SKU or name, 25 per search. When more match, the footer reads "Showing {{shown}} of {{total}}: keep typing to
  narrow the list."
- **Partners** (debit note): server search on code, name, email, phone or tax number, with the same footer.
- **Suppliers** (PO create, GRN create, claims): debounced search on `/procurement/suppliers/options`. That route
  answers at most 50 and gives no total, so a full page shows "Showing the first {{n}} matches: keep typing to narrow
  the list."
- **Deviation from Nest:** none.
- **Checks:**
  - `/inventory/locations` uses `QueryParams.page(query, "type")` (pageSize up to 1000, `sortBy` on an entity
    column).
  - `ProductService.searchFields` = code, sku, name.
  - `PartnerService.searchFields` = code, name, email, phone, taxNumber.
  - Supplier options use `LIMIT 50`.
  - Only the ledger page gained a new dependency on `GET /products` (`product:read`). Its product filter is offered
    only with that permission.

## h06-F9: Product dropdowns keyed by index and left uncontrolled

- **Status:** fixed.
- **Behaviour change:**
  - PO create and debit-note create lines carry a stable row id, used as the React key.
  - The product picker is controlled by the line's own `productId` (`value`, not `defaultValue`).
  - Every line update targets a row id.
  - The same row ids are used on GRN create, transfer create and direct stock lines.
  - Removing line 1 of 3 now shows B, C and saves B, C.
- **Deviation from Nest:** none.
- **Checks:**
  - No `key={idx}` or `defaultValue` remains on these line editors.
  - The row id is never sent: each payload maps line fields explicitly.

## h06-F10: Irreversible actions with no confirmation or pending guard

- **Status:** fixed for debit notes, GRN post, transfers and claims. Landed-cost Post/Cancel is gF's file.
- **Debit notes:** Post and Cancel ask first.
  - Titles: "Post {{noteNumber}}?" / "Cancel {{noteNumber}}?".
  - Post text: "Posting books the note to the ledger and applies it to the partner's balance; a return to the
    supplier also takes the goods out of stock. Undoing it later needs a reversal."
  - Cancel text: "The draft is closed without posting anything. A cancelled note cannot be posted later."
  - Buttons: "Post note" / "Cancel note", showing "Posting…" / "Cancelling…" while the action runs.
  - The list buttons are disabled while an action runs.
- **GRN detail:** Post asks "Post {{receiptNumber}}?" with "Posting moves the received quantities into stock and
  books the receipt to the ledger. Undoing it later needs a reversal." The button is "Post receipt".
- **Transfers:** Approve and Cancel ask "Approve {{transferCode}}?" / "Cancel {{transferCode}}?", with one of these
  texts:
  - immediate approve: "Stock moves from {{from}} to {{to}} as soon as it is approved. Undoing it later needs a
    reversal.";
  - transit approve: "The transfer can then be dispatched. Stock leaves the source location when it is
    dispatched.";
  - cancel: "The transfer is closed without moving any stock. A cancelled transfer cannot be reopened."

  The buttons are "Approve transfer" / "Cancel transfer", showing "Approving…" / "Cancelling…" while the action
  runs.
- **Supplier claims (`ControlsPages.tsx`):**
  - Raise, Accept (Save) and Settle are disabled while any claim action runs, and show "Saving…" / "Settling…".
  - The settle dialog adds "Settling posts the supplier credit to the ledger. It cannot be undone from this screen."
  - Cancelling an open claim asks "Cancel {{claimNumber}}?" with "The claim is closed without asking the supplier
    for anything. A cancelled claim cannot be reopened."
  - Send, Reject and Cancel are disabled while an action runs.
- **All confirmations** close on success and stay open on failure, next to the error toast.
- **Deviation from Nest:** none.
- **Checks:**
  - Debit-note post requires `draft`, and cancel refuses `posted`/`reversed` (`DebitNotesService`).
  - Claim cancel requires `open` (`SupplierClaimService.cancel`), and no route reopens a claim.
  - Transit approve moves no stock; dispatch moves it.

## h06-F11: Gaps in UI permission gating

- **Status:** fixed.
- **Actions** are shown only with the permission their route enforces:
  - **Adjustments:** New needs `inventory_doc:create`. Approve, cancel and reverse need `inventory_doc:approve`.
  - **Debit notes:** New needs `debit_note:create`, Post needs `debit_note:post`, Cancel needs `debit_note:cancel`.
    Reverse was already gated.
  - **PO detail:** Receive needs `purchase_order:receive`, Pay needs `purchase_order:pay`, Cancel needs
    `purchase_order:cancel`.
  - **Direct stock:** hidden without `inventory:move`, and it then makes no product, location or settings requests.
  - **GRN create:** the purchase-order picker needs `purchase_order:read` (the PO list route).
- **Routes** (`App.tsx`) now use `RequirePermission` with the permission of the page's main list route, which is
  also the sidebar's:

  | Route | Permission |
  |---|---|
  | `/inventory`, `/inventory/items`, `/inventory/items/:productId` | `inventory:read` |
  | `/inventory/adjustments`, `/inventory/waste`, `/inventory/transfers` | `inventory_doc:read` |
  | `/inventory/count` | `inventory_count:read` |
  | `/inventory/ledger`, `/inventory/ledger/:id`, `/inventory/reservations`, `/inventory/replenishment` | `inventory:read` |
  | `/inventory/locations` | `inventory_location:read` |
  | `/inventory/cost-policies` | `costing_policy:read` |
  | `/procurement/budgets` | `procurement_budget:read` |
  | `/procurement/delegations` | `procurement_delegation:read` |

- **One toast per failure:**
  - The API client marks the errors it already toasted: a 403, a 5xx, a licence 402, and the first network failure
    of an outage.
  - `notifyApiError` skips those.
  - Every touched call site uses it, or shows `describeApiError` inline.
  - A 403 now gives one "Permission denied" toast instead of two.
- **Validation arrays:** call sites now go through `apiErrorMessage`, which joins `message` arrays with "; ".
- **Deviation from Nest:** none. The server enforces the same strings.
- **Checks:** each permission was read from the route's `@RequirePermissions` in:
  - `InventoryController`, `InventoryCountController`, `InventoryCostPolicyController`;
  - `StockReservationController`, `ReplenishmentController`;
  - `DebitNotesController`, `PurchaseOrdersController`, `ProcurementControlController`;
  - and the sidebar entries in `app-shell.tsx`.

## h06-F12: Lists capped or unpaginated

- **Status:**
  - partly fixed: a pager wherever the server pages, and a visible notice on every capped or unpaged list;
  - deferred: server-side paging (cross-stream note 3).
- **Supplier claims:** a pager (25/50/100 a page) on the server's `page`/`limit`. Before, only page 1 was shown.
- **Debit notes** (server `LIMIT 200`, newest first):
  - New Direction and Status filters, using query params the route already had.
  - When 200 come back, the page says "Showing the {{n}} most recent debit notes only. Filter by direction or
    status to find older ones."
- **Transfers and adjustments** (the server returns the whole list):
  - A status filter, using the server's `status` param.
  - A client pager (25/50/100).
  - Above one page, a notice: "All {{count}} transfers are loaded at once. Filter by status to narrow the list." (or
    "…adjustments…").
- **Waste** (server `LIMIT 500`, newest first): when 500 come back, the page says "Showing the newest {{n}} records
  only. Narrow the dates or filters to see older ones."
- **Deviation from Nest:** none.
- **Checks:** read the list SQL in `DebitNotesService.list`, `InventoryController` (waste/transfers/adjustments) and
  `ProcurementControlsController.listClaims` (limit 1..200).

## h06-F14: Dead link and raw-UUID inputs

- **Status:** fixed.
- **PO detail:** the purchase-request link now points to `/procurement/purchase-requests?highlight=<id>`. Requests
  have no detail route.
  - The list rings the request and scrolls it into view.
  - If the request is not in the list (it shows the newest 200), it says "The linked purchase request is not among
    the requests listed here."
- **GRN create:** a purchase-order picker replaces the raw UUID box.
  - It lists receivable orders (`active` and `partially_received`), searched on the server by order number,
    supplier, description or notes.
  - The order number is shown with the supplier.
  - Picking an order fills the lines. Clearing it ("No purchase order") resets lines that came from the order.
- **Stock ledger:** the typed product id is replaced by a product picker. The ledger is fetched once per pick, not
  once per keystroke.
- **Deviation from Nest:** none.
- **Checks:**
  - The PO list route takes one `status` at a time, so the picker asks for both receivable statuses and merges them.
  - The `search` columns were read in `PurchaseOrdersService.list`.

## Cross-stream notes

1. **Missing `@Idempotent` (gD / gE / coordinator).** The web now sends a stable key to these routes, but the
   server does not deduplicate them:
   - `POST /procurement/purchase-orders`;
   - `POST /procurement/goods-receipts`;
   - `POST /procurement/debit-notes` and `PATCH /procurement/debit-notes/{id}/cancel`;
   - `POST /procurement/claims` and `/procurement/claims/{id}/accept|submit|reject|cancel`.

   Adding `@Idempotent` makes a retry after a lost answer replay instead of creating a duplicate.
2. **F7 server half (gA).** Releasing the record of an attempt that rolled back removes most `OPERATION_PENDING`
   cases. The client also reads `safeToRetry: true` from the 409 body or from `recovery`. If the server sets it when
   it knows the attempt rolled back, the client offers a fresh attempt even after an unanswered try.
3. **Server paging wanted:**
   - `GET /inventory/transfers` and `/inventory/adjustments`: unbounded, with items loaded per row.
   - `GET /inventory/waste`: `LIMIT 500`.
   - `GET /procurement/debit-notes`: `LIMIT 200`.
   - `GET /procurement/purchase-requests`: `LIMIT 200`; a get-by-id would also let the PO link open the request
     directly.
   - `GET /procurement/suppliers/options`: 50, with no total.
   - The PO list's `status` takes one value; a multi-status filter would let the GRN picker ask once.

   All should take `page`/`pageSize` and answer `{data, meta}`, as an additive change. The web would then swap its
   client pager for the server one.
4. **`useIdempotencyKey`** (`lib/idempotency.ts`) is shared. Its `settle` change keeps the key on a pending or
   concurrent 409; besides direct stock, `InventoryCountPage` uses it.
5. **Landed cost:** confirmation and pending guard on landed-cost Post/Cancel (h06-F10) are in
   `features/procurement/landed-cost-panel.tsx`, which is gF's.
6. **Shared web files touched, additive only:**
   - `components/ui/searchable-select.tsx`: optional `footer`.
   - `lib/api.ts`: `wasNotified`, plus a flag set where the interceptor already toasts.
   - `features/procurement/api.ts`: `useClaims` takes `limit`; claim create/accept use `idempotentPost`.
   - `App.tsx`: route guards.
   - i18n `ti`/`lg`/`sw`: two stale phrases removed.
7. **Permissions:** no new permissions. The location pickers need `inventory_location:read`, which the screens that
   use them already relied on.
