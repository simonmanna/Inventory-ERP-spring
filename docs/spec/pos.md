# POS (cafe vertical) — `pos`

| | |
|---|---|
| Phase | P5 |
| Nest source | `reference/nest-api/src/modules/pos` |
| Routes | 219 — full table: [generated/pos.md](generated/pos.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.pos` |

## Purpose

Terminal checkout, open tabs per table, orders with kitchen firing, split bills, table management (merge/transfer/split), reservations, menu (categories, items, variants, recipes, accompaniments), modifiers and combos, kitchen stations and KDS, holds, PIN login and manager overrides, shift handover, loyalty, store credit and customer tabs, stock posting jobs and monitor, receipts/bills/KOT printing (ESC/POS), POS reports (X/Z and 15 others), digital QR menu with public ordering.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| terminal | `/pos`: settings, operation-approval, payment accounts/methods, checkout (idem), sales/:id/void (idem), lookup |
| tabs | `/pos/tabs/:tableId`: get, items (append), save (version lock), fire-kitchen, settle (idem); `/pos/orders/:id/resume|settle` |
| orders | `/pos/orders`: list, by-table, open, get, milestones, quote, create, PUT/POST items, void item, fire-kitchen, move, merge, cancel, reopen, invoice |
| billing | `/pos/invoices/:id/payments|credit|refund|write-off` |
| split bills | `/pos/tabs/:tableId/split*`, `/pos/split-bills/:billId/*` (item/even/percent) |
| tables | `/pos/tables`: CRUD, stats, stream (SSE), status, assign-waiter, merge/unmerge, transfer, transfer-items, split-bill, attach-sale; zones CRUD + restore |
| reservations | `/pos/reservations`: CRUD, seat, cancel, no-show, complete (+ crons) |
| menu | `/pos/menu`: categories, items (availability, bundle, hard delete, restore), variants, accompaniment groups/options + sales report; QR sessions and online orders; `/menu/public/*` (public) |
| modifiers | `/pos/modifiers`: groups, modifiers, combos, product/menu-item bundles, sales report |
| kitchen | `/pos/kitchen-stations` CRUD/seed/reorder; `/pos/kds` tickets, stream (SSE), transitions, priority, assign, send-to-kitchen; `/pos/kds/reports` |
| staff on terminal | `/pos/auth` staff, pin-login, logoff, change-pin/password; `/pos/override` pin/verify; `/pos/shift/handover` (idem) |
| holds | `/pos/holds`: create, list, get, recall, delete, notes |
| loyalty & credit | `/pos/loyalty/*` program/balance/earn/redeem, store credit issue/redeem, customer tab open/charge/settle; `/pos/customers/:id/statement|credit`; `/pos/credit/open` |
| posting monitor | `/pos/posting-monitor`: counts, jobs, retry, exceptions assign/resolve |
| reports | `/pos/reports/*` (x-report, z-report, sales-by-hour, top-items, sales-summary, sold-items, sales-report, order-report, cashier-report, cashier-shift-summary, waiter-report, item-sales, items-by-server, items-by-group, filter-options), tables utilization/revenue, reservations |
| receipts | `/pos/receipts/*`: list, get, pdf, text, print, email, reprint, print-bill, print-additional-bill, reprint-bill, print-kot, print-closing-statement, receipt settings |

## Business rules to reproduce exactly

1. Every order/checkout requires an open cash session for the register (verbatim error).
2. Order mutation policy (`order/order-mutation-policy.ts`): removing lines already sent to the kitchen (void, cart edit, cancel, empty save, item transfer) requires `pos:void`, a meaningful reason (≥ 3 chars, not a placeholder like "n/a"), and a manager PIN override with `pos:override` (grant bound to the operation key).
3. Billed orders cannot be edited or reopened; only cancelled orders can be reopened.
4. Merge (tables or orders): both open and unbilled, no order-level discount on the merged order, no split bills in progress, same open session, not into itself, not onto an out-of-service table. Whole-order void lives in the "More" menu (commit a6444fa).
5. One open tab per table (DB constraint) — map violation to the Nest message. `PUT /orders/:id/items` uses `version` (409 on mismatch); offline replay uses append-only `POST items`.
6. Line signature (`order/line-signature.ts`): identity of a line = item + variant + modifiers + accompaniments + notes; used to merge quantities and compute KOT deltas.
7. Pricing policy (`pricing-policy.ts`): discount authority by permission/limits, price override needs `pos:price_override`; credit sales check `creditHold`/`creditLimit` and fail closed.
8. Stock policy per item/setting: `block` rejects when insufficient, `warn` returns warning, `silent` proceeds. Stock is posted asynchronously: `StockPostingJob` with recipe snapshot (`InvoiceItemRecipeIngredient`), drained every 30 s with claim token + SKIP LOCKED, retries, failures → `InventoryException` (posting monitor).
9. Refund/void: reverse GL, restock per recipe snapshot, take cash out of the drawer (cash movement), write `PosRefund`; partial line refunds online only.
10. Printing lifecycle (`pos-print-lifecycle.service.ts`): copy numbers, `kotPrintedQty`/`billPrintedQty` deltas, cancel-KOT on void after firing, closing statement asked inside shift-close dialog (commit fb03b13).
11. KDS ticket status machine: new → preparing → ready → served; cancelled. Bulk transitions validate each ticket.
12. Reservations: no-show job marks overdue reservations; seat links reservation to table/order.
13. Table status: available, occupied, reserved, out_of_service (cleaning removed by last migration — check).
14. Receipts settings and printer config are `Setting` keys (`pos.printerHost`, `pos.printerPort`, `pos.printerWindowsName`, ...).
15. System connection mode "offline (local-only)" skips connectivity checks (commit 4ad9735) — a setting the client reads.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`order.*`, `invoice.*`, `kitchen.ticket.*`, `table.*`; table and KDS SSE streams are fed from after-commit events.

## Scheduled jobs

reservation-no-show 1 min, reservation-refresh 5 min, stock-posting-drain 30 s, stock-posting-health 5 min.

## Notes and pitfalls

- Largest module (219 routes, 21k LOC). Split into PRs: settings/lookup → menu/modifiers → tables/zones/reservations → orders/tabs → checkout/billing → split bills → KDS → receipts/printing → loyalty/credit → reports → posting monitor → public menu.
- Read `docs/legacy/POS_TRANSACTION_FLOW.md` and `docs/legacy/POS_RUNBOOK.md` before starting.
- Port the 37 POS spec files; then run `reference/e2e/pos-sell-loop.spec.ts` against Spring.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — none needed: services are JdbcClient SQL
- [x] DTO records with Bean Validation mirroring class-validator rules (untyped Nest bodies stay unvalidated maps)
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests (see Status for what is not ported)
- [ ] Parity scenarios `contract-tests/scenarios/pos-*.json` — written, not yet run against a live Nest
- [x] Web screens for this module work against Spring (no console errors) — walkthrough 2026-10-24, see
  "Exit-criteria runs"; QR ordering (`/menu/public/*`) still needs a JWT, see below
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Approved by the owner 2026-11-23 (inventory hardening R4, workflow integrity, release gate G1;
[review](../audits/2026-11-22-inventory-review.md) P1-11, P1-12):

- **`at_confirmation` and `at_fulfillment_complete` are deprecated.** `PUT /settings/inventory.stockPostingTiming` with
  either value → 400 `Setting 'inventory.stockPostingTiming' value '<v>' can no longer be selected: …` (the same
  registry mechanism as `at_fulfillment_start`, but a stored value still reads as itself: `SettingRegistry.coerceStored`).
  `GET /settings/effective` adds a `warning` field to the row when the effective value is deprecated. Data check (h2)
  lists the organisations still on one. Known limits that stay with these timings: a line added after the order's job
  posted, or a quantity raised on a posted line, is not issued; a merged / split order keeps its original issue (the
  orders that take its lines issue nothing for them).
- **One stock-posting job per sale.** Key `sale:<orderId>` (`sale:<invoiceId>` without an order) whatever the trigger.
  Before inserting, a job of the same sale in any status and keyed either way (also the old `<trigger>:<invoice|order>`
  keys, which are not migrated and keep running) prevents a second one; when the invoice arrives it is attached to the
  order's job instead. A timing change between the order and its bill can no longer issue twice.
- **Order cancel** (every path — `POST /pos/orders/:id/cancel`, `/orders/:id/cancel`, sync — through a side effect of the
  `order.cancel` workflow transition, in the cancel transaction, lock order order → job → stock):

  | state at cancel | behaviour |
  |---|---|
  | job pending | abandoned (`status = abandoned`, `lastError` `Order cancelled; nothing was issued`); no stock moves |
  | job processing | the cancel waits for the job row lock (`FOR UPDATE`), then applies the rule for what is posted; a runner still holding the old claim posts no further line (`postOnce` requires `processing`) and cannot complete the job |
  | posted | every issue row of the order (`pos_order` / `menu_recipe` under the order id, `pos_invoice_combo` / `pos_invoice_extra` under its line ids) comes back via `StockReversalService.returnFromSource` at its original cost, lot and serial, `reversal_in` rows under `pos_order_cancel` naming the row (`reversesLedgerId`), GL Dr stock / Cr COGS at that cost; the job is abandoned with the count |
  | partially fulfilled | only the lines the job issued come back; the failed lines never issue and their open exceptions are discarded |
  | partially returned | issued − already returned (the `reversesLedgerId` netting of `returnFromSource`) |

  The job worker re-checks under the job lock: a job whose order is cancelled (not superseded) is abandoned, never
  posted. Reopen revives the jobs a cancel abandoned (pending, from the start). A split / merge (`supersede`) does not
  reverse: its goods are still sold through the orders that took its lines.
- **Kitchen-voided line: issued once.** Voiding a line the order's job already issued returns the voided share of what
  the line still has issued (`pos_order_void`, original cost / lot / serial, needs the new `sourceLineId` = order line
  on POS issue rows, written from R4 on); the fired part then goes to kitchen waste as before. Net: a fired, voided unit
  is issued once (as waste), an unfired one is returned. A job running while a line is voided re-reads the line under
  the job lock and issues its current quantity (none when voided). Lines issued before R4 carry no `sourceLineId` and
  are not returned by a void (the order cancel still returns them).
- POS Z / GL reconciliation nets `pos_order_cancel` / `pos_order_void` returns like refund restocks.
- Tests: `OrderCancelStockIT` (one per row above, job key, deprecated timings), `SyncInventoryReplayIT` 7 (held
  refund), `KernelUnitTest.settingRegistry`.

Approved by the owner 2026-11-17 (accounting and costing review,
[audit](../audits/2026-11-17-accounting-costing-review.md)):

- **ACR-17: abandon a failed stock-posting job.** New route `POST /pos/posting-monitor/jobs/:id/abandon` with body
  `{note}` (`pos:override`). Nest had no way out: resolving the exceptions left the job failed, and that blocked the
  period close for ever.
  - Only a `failed` job can be abandoned (compare-and-set).
  - The note records how the sale's stock and cost of sales were corrected instead.
  - The job keeps its error and history and is never claimed again. Its open exceptions are discarded with the note.
  - Messages:
    - 400 `A note of at least 10 characters is required — record how the sale's stock and cost of sales were corrected`
    - 404 `Stock posting job not found`
    - 400 `Only failed jobs can be abandoned (this one is <status>)`
    - 409 `Stock posting job changed while abandoning (now <status>)`
  - Web: the Posting Monitor's Abandon dialog and an `abandoned` filter.

Approved by the owner 2026-11-20 (landed-cost report corrections, discrepancy 2):

- **POS refund journal in the receipt's currency.** `POST /pos/invoices/:id/refund` posts its journal (`pos_refund`)
  with the receipt's `currencyId` and `exchangeRate`, as the sale journal (`pos_invoice`) does. Nest posted it with no
  currency, so a foreign-currency receipt's refund reversed revenue, tax and AR at nominal = base. POS checkout only
  issues base-currency receipts (rate 1), so the change only affects legacy or imported receipts; the payout payments
  are unchanged. Test: `SalesReturnsIT.refundJournalAtReceiptRate`.

Owner-approved 2026-11-27:

1. **Invoice-item unit snapshot (ADR-S003, V5).** `InvoiceItem` gains `uomId` + `uomFactor` (the product's
   sales unit at billing); stock issue uses the snapshot. Order lines are unchanged. Quote/prepared-line
   payloads gain `uomId` / `uomFactor`.

Approved by the owner (`phase/inv-hardening`, [inventory deviation](inventory.md#deviations) 19):

2. **Stock-posting drain dates COGS at the sale.** Stock moves and COGS of a drained sale use the invoice `issueDate`
   (else the order's `createdAt`); a date in a closed / locked period posts today with a ledger note.

Approved by the owner (decision D4, 2026-11-15, [ADR-S072](../adr/ADR-S072-pos-variant-and-posting-integrity.md)):

3. **POS product variants (V-1, V-2).**
   - A product line's `variantId` (a `ProductVariant`, ADR-S058) is issued and restocked as that variant. Nest
     issued the parent product.
   - Its price is the variant's fixed price, else the product price plus the attribute extras. Nest used the fixed
     price only.
4. **Per-line stock posting (S-1 / R-B).**
   - Nest ran one transaction per job with a savepoint per line. A line failing inside `StockService.issue` rolled
     back the whole sale.
   - Each line, and each paid modifier or accompaniment, now commits on its own together with its key in
     `postedLineKeys`.
   - A failed line is an open `InventoryException`, and the job stays retryable.
5. **Kitchen waste (V-3).**
   - A plain product is wasted in the line's sales unit and with its variant.
   - Each line runs in its own transaction.
   - A failure is recorded as an `InventoryException` (kind `kitchen_waste`). Nest only logged it.
6. **POS posting timing and refund (T-1).** Built in Phase I5 (ADR-S072 addendum): an order-timed job takes its
   order's invoice when there is one, else posts against the order (`pos_order`); refunds restock from either.
7. **Retry a pending posting job (owner request, 2026-11-15).**
   - `POST /pos/posting-monitor/jobs/{id}/retry` also accepts a `pending` job: it runs on the next drain with a fresh
     attempt budget. Lines already posted stay posted once (`postedLineKeys`).
   - Nest accepted failed jobs only. The refusal message is now "Only failed or pending jobs can be retried (this one
     is <status>)".
   - The retry is a compare-and-set: a job that changed in between gets 409 "Stock posting job changed while retrying
     (now <status>); refresh and try again" (review R3).
8. **Availability check and reservations per variant, in base units (review R2).** The `strict` / `pre_invoice` check
   and invoice reservations use the line's product-variant quant and base-unit quantities; the "need N" in "Low stock
   for …" is in base units. Nest read the parent product and compared sale units with base units.
9. **Every posting runner claims the job (review R3).** The shift-close and sync close drains claim a job before
   running it, like the worker; a job held by another runner is skipped.
10. **A retried line closes its exception (review R4).** The line's open `InventoryException` is resolved
    automatically (note "Posted automatically when the stock posting job retried this line") when the retry posts it.
11. **A counter checkout fires the kitchen after its invoice (review N7).** A checkout refused at invoicing leaves an
    unfired order; cancelling it records no kitchen waste. ADR-S072 item 10.
12. **Offline sale with an unconvertible unit is kept (owner decision 1).** A `sale.checkout` replayed from the
    device keeps the line's unit with no frozen factor; the availability check and reservations skip it and its stock
    line fails at posting (retry, then quarantine with an `InventoryException`) until the unit converts. Online POS
    still refuses such a sale. ADR-S072 addendum (decision 1).
13. **An online sale of a product with variants names the variant (review M-9).** 400 "Choose a variant for
    <product name>" unless the till's location still holds no-variant stock. Offline replays, rental and repair lines
    are not checked.
14. **A refund restocks the variant the sale issued (review M-10).** A pre-ADR-S072 sale that issued no variant
    restocks the no-variant stock.
15. **Offline sale priced differently by the device is kept (owner decision 1, Phase I6).** On a `sale.checkout`
    replay (`OfflineReplay`), a non-credit sale whose `expectedTotal` or tender sum differs from the server total is
    applied at the server's catalog price instead of failing on every retry: the stale `expectedTotal` is dropped; an
    overpay is trimmed off the cash legs (then other legs, last first) and `amountTendered` kept for change; a shortfall
    is settled partially (`allowPartial`, replay only) and the invoice is created with `paymentMode = credit`, so the
    residual stays on account and does not block the shift close. Order and invoice notes get "Offline price
    difference: device charged X, catalog price Y", an `AuditLog` row (`Invoice update`, `kind:
    offline_price_variance`) records the variance, and the push result carries `priceVariance`. Online checkout still
    refuses with "The price changed. Refresh the quote before payment." Test `SyncDeviceIntegrityIT`.

Post-parity additions (no Nest counterpart, explicitly labelled):

- **Rental POS mode** (owner-approved): `PATCH /pos/settings` accepts `posMode` = `rental` in addition to Nest's
  `cafe | retail`, so the web Rental terminal can be selected. The 400 message for other values lists all three
  (`posMode must be one of the following values: cafe, retail, rental`).

- **Sales returns register** (`SalesReturnsController`, `/returns`): read-only routes over the immutable `PosRefund`
  events — `GET /returns` (paged, filters `search`, `channel` = `retail|restaurant`, `stockDisposition`, `dateFrom`,
  `dateTo`; extra `summary` {count, amount}), `GET /returns/:id` (lines, payouts, journal entry),
  `GET /returns/invoices?search=` and `GET /returns/invoices/:invoiceId` (returnable sales with remaining qty per line),
  `GET /returns/registers` (open cash sessions for a cash payout). Reads need `pos:read`; preparation routes need
  `pos:refund`. Creating a return still goes through `POST /pos/invoices/:id/refund` — no new write path, no schema
  change. Channel is derived: a sale with any menu-item line is `restaurant`, otherwise `retail`. `returnNumber` is
  `<invoiceNumber>-R<n>` (n = order of the refund on that invoice). Web: "Returns" sidebar group.
  The 5 routes live in the `pos` package, so the `PosSaleIT` authorization matrix covers 224 routes
  (219 ported + 5 returns).

- **Cash-drawer openings** (LP2b, owner decision 2026-11-19; ADR-S077). `POST /pos/cash-drawer/openings`,
  `cash_session:open` (the permission of `POST /cash-sessions/movement`), `Idempotency-Key` required, answers 200 with
  the stored row. Body `{ reason, sessionId?, cashRegisterId?, note?, referenceType?, referenceId?, occurredAt? }`:
  `reason` is one of `sale`, `refund`, `cash_movement`, `no_sale`, `float`, `other`; `note` at most 500 characters;
  `referenceType` (`invoice`, `refund`, `cash_movement`) and `referenceId` name the sale, refund or movement the opening
  belongs to and come together ("referenceType and referenceId are given together"; an unknown one answers 404 "The
  referenced invoice was not found"). The till is the session's register, else `cashRegisterId`, else the caller's one
  open shift ("No open cash session" 404; "You have shifts open on more than one register; choose the shift" 400).
  A `no_sale` needs the caller's own open shift on that till: "No open cash session" (404), "Session is not open"
  (400), "This cash session belongs to a different cashier" (403), "No open session on this register" (404). Any other
  reason is linked to the shift open on the till when there is one, and may be recorded with none when
  `cashRegisterId` is given ("Cash register not found" 404 for another organization's till). Each opening is one
  append-only `CashDrawerOpening` row (V578, tenant policy), audited (`CashDrawerOpening create`), and announced on
  commit as `pos.cash_drawer.opened` (`organizationId`, `openingId`, `cashRegisterId`, `cashSessionId`, `userId`,
  `reason`). Offline tills push the same thing as the sync op `cash_drawer.open` (`docs/spec/sync.md`). Loss
  prevention projects every row as `cash_drawer.opened` (`docs/spec/loss-prevention.md`). Web: "No sale" on the cash
  register page while a shift is open; Android: "No sale (open drawer)" on the More screen. Test `PosCashDrawerIT`.
  The `PosSaleIT` authorization matrix counts 226 routes.

Post-parity fixes (found in the 2026-10-24 web walkthrough, explicitly labelled):

- `POST /pos/tabs/:tableId/split/equal` works: the bills are written with split type `even` (Nest wrote `'equal'`,
  which the `SplitType` enum rejects, so the route always answered 500). Allocation is Nest's
  (`total / n` to 6 dp, the last bill takes the remainder). `PosSaleIT#tabsAndSplit` covers it.
- `GET /pos/reports/sales-summary` without `groupBy` answers 400 `Unknown groupBy "undefined"` like Nest (was a 500
  NPE). The same null guard was added where a missing request value reached `List.of(..).contains` (items-by-server,
  cash movement type, tender method, stock-posting movement type, sync pull account roles).
- `APP_TIMEZONE` (default `UTC`) replaces the host zone wherever Nest reads local time (receipt / bill / KOT date and
  time, report days, date-only filters) — Nest follows the host `TZ`, Spring pins the JVM to UTC for Prisma parity.
- SSE clients that disconnect (`/pos/tables/stream`, `/pos/kds/stream`) no longer log an ERROR with a stack trace and
  a failed JSON error write per disconnect.
- Web: `SplitBillDialog` refetches the split state when it opens (the terminal caches the same query when the table
  opens, so a freshly filled tab showed "No open tab on this table" for 30 s). Same client bug in POS-CAFE.

Post-parity hardening (owner-approved 2026-11-10, after the end-to-end POS review on the DEMO data; explicitly
labelled, each a deliberate change from Nest):

- **PIN lockout that holds.** Every PIN check (PIN login, change-PIN, own-PIN verify, manager override / operation
  approval, void / refund / cancel with a manager PIN, cash-session approvals, shift handover) counts the attempt
  with one atomic upsert on the new `CredentialThrottle` table (V442, RLS) **before** the hash compare, in its own
  transaction (`kernel/auth/CredentialGuard`). Nest read-modify-wrote `User.failedLoginCount`, so parallel guesses
  overwrote each other's increments (60 parallel wrong PINs never locked), and the void/refund routes rolled the
  increment back with the business transaction (the manager-PIN lockout never engaged). Limits: own PIN 10 attempts /
  15 min → 15 min lock (same message `Account locked. Try again in N min`); approver PIN 5 / 10 min → 10 min lock
  (same `Manager overrides are temporarily locked…` message; cash-session approvals answer `Manager approvals are
  temporarily locked after repeated failed attempts. Try again in N min`). Wrong PINs no longer lock the user's
  back-office password login (`User.lockedUntil` is only for passwords). `POST /users/:id/unlock` also clears the PIN
  counters. `@Throttle` added on `pin-login` (30/min), `change-pin` and `change-password` (10/min), override
  `verify-pin` (30/min).
- **Shift handover verifies PINs only** (incoming PIN + approver PIN through the same guard); it no longer issues a
  POS token or touches the incoming user's login counters. Open orders and holds of the outgoing session move to the
  incoming session (`carriedOpenOrders` in the audit row).
- **Expired or invalid POS token → 401.** An `X-Pos-User` token that does not verify for the bearer's organization
  (expired after `JWT_POS_TTL`, forged, other org) answers 401 with `X-Pos-Session: revoked` and
  `Your POS sign-in has expired. Sign in again with your PIN.` Nest silently fell back to the bearer identity, so the
  till kept selling as the back-office user who opened it (often a manager). The PIN screens
  (`/pos/auth/pin-login`, `/pos/auth/staff`) still fall back. The web already handles the header (PIN screen).
- **Opening below the drawer ledger.** `POST /cash-sessions/open` with `autoAdjust` and a count below the drawer's
  ledger (cash removed between shifts) now needs `adjustmentReason` (400 `OPENING_SHORTAGE_REASON_REQUIRED`) and a
  `cash_session:approve_variance` holder: the opener, or a manager via `approvedById` / `approverEmail` + `managerPin`
  (400 `MANAGER_APPROVAL_REQUIRED` `Removing cash from the drawer at opening requires manager approval`). The reason
  and approver are written to the audit row and the posting description. New optional body fields:
  `adjustmentReason`, `approvedById`, `approverEmail`, `managerPin`. Nest let any opener move the shortage to the
  clearing account silently (the DEMO review moved 107,500 that way with a 0 float).
- **Auto-adjusted close keeps the variance review.** `autoAdjustCash` at close still books the difference as a drawer
  movement but no longer skips the reason / approval threshold or clears `varianceStatus`.
- **Variance threshold per organization.** Setting `cash.varianceApprovalThreshold` overrides the
  `CASH_VARIANCE_APPROVAL_THRESHOLD` env value (default 20000).
- **Ambiguous shift.** Close / cash movement without a session id, for a user with open shifts on more than one
  register, answers 400 `You have shifts open on more than one register; choose the shift` (Nest picked one).
  The close advisory lock is per organization + branch; the open-orders check is scoped to the session's branch.
- **Selling.** Settling an order re-checks under the order lock that no split is in progress (`This order has a split
  in progress — settle each split bill instead.`). Tender sums and change use `BigDecimal` (Nest used JS numbers). A
  refund of the last remaining units refunds the exact remaining value (no rounding residue). Credit settlement needs
  `pos:credit` and an existing customer of the organization (`Customer not found`). The catalog tax's
  `isInclusive` wins over a client `taxInclusive` flag. Moving an order to a table that already has an open order is
  refused (`Table already has an open order (…)`).
- **Split routes have permissions.** `GET /pos/tabs/:tableId/split` needs `pos:read`; every other `/split*` route needs
  `pos:checkout` (Nest had none, so any signed-in user could split or settle a tab).
- **Receipts.** The original receipt is claimed with a conditional update (`receiptPrintCount = 0`), so two
  simultaneous prints cannot both print an unmarked original; the loser gets the existing 403 reprint message.
- **Reports.** A report range over 366 days answers 400 `Report range cannot exceed 366 days`. Sales summary and
  items-by-server bucket a sale on its `businessDate` (the column the range filter already used) instead of
  `issueDate`, so a late-shift sale after midnight is counted in the day it was filtered into.
- **Posting monitor.** `GET /pos/posting-monitor/counts` adds `unpaidSales {count, oldestInvoice}` (POS invoices left
  unsettled for over 5 minutes, credit sales excluded) and reports health `attention` when any exist. The session
  drain handles at most 200 jobs / 15 s per call.
- **Live streams.** `/pos/tables/stream` and `/pos/kds/stream`: changes are debounced (250 ms) and rendered once per
  organization + channel, writes run per client on virtual threads, streams end after 30 min (the browser
  reconnects and re-authenticates). KDS active tickets are limited to the last 24 h (500 max).
- **Sync dead letters** store the payload with PINs, passwords and approval tokens replaced by `[redacted]`.
- **Outbox claim** sets `status = 'claimed'` (it stayed `pending`, so a second API instance could dispatch the same
  event again).
- **Web** (`web/`): the open-shift dialog pre-fills the drawer's ledger balance instead of 50,000, leaves
  auto-adjust off, and asks for the reason and manager login when cash is removed; the close dialog leaves
  auto-adjust off and prints the closing statement from the frozen Z-report; a 401 on a PIN request is no longer
  refreshed and re-sent (that counted a second failed attempt).

Tests: `PosHardeningIT` (parallel PIN guesses lock after exactly 10 compares, void-PIN lockout survives the rollback,
opening shortage approval, one original receipt under a race, range cap and business-day bucketing),
`SyncDeadLetterRedactionTest`, `KernelIT#posRevocation` (expired / forged / foreign POS token → 401).

Same as Nest, kept on purpose:

- `/menu/public/*` ("public" digital menu) is **not** `@Public()` in Nest: the global JWT guard applies, so a bearer
  token is required (no permission). Unauthenticated callers get 401.
- `PATCH /pos/menu/orders/:id/status`: `UpdateOrderStatusDto.status` has no class-validator decorator, so the global
  whitelist rejects it (`property status should not exist`); an empty body bumps `updatedAt` only (unknown id → 500).
- `POST /pos/tables/:id/split-bill` always answers 409 (`This operation conflicts with one that was just completed…`):
  the child orders are raised while the source tab still holds the table, which the
  `Order_one_open_dine_in_tab_per_table` index rejects. Split bills work through `/pos/tabs/:tableId/split*`.
- `CreateReservationBody` has no `id`, so the offline client-minted reservation id is rejected by the whitelist.
- `POST /pos/tables/:id/transfer/:targetId` checks the target's status before the source's open orders.
- Reports read "local" days in the server time zone (UTC), like the Nest host; `toLocaleTimeString()` /
  `toLocaleDateString()` columns use the en-US ICU format of Node 22 (`3:04:05 PM` with U+202F).
- `GET /pos/posting-monitor/jobs?status=` / `/exceptions?status=` with an unknown status → 500 (Prisma enum/where error).
- Business timestamps Nest sets with `new Date()` use the database clock (`clock_timestamp()`), as in every module.

## Status

P5: 219/219 routes in 30 controllers (`pos.web`). Services (`pos.application`): settings/lookup, kitchen stations,
menu / variants / accompaniments / modifiers / combos, PIN auth + overrides, orders (F10 line signature, mutation
policy, kitchen waste), tabs / checkout / settle (`PosService`), billing (`PosInvoiceService`: invoice, payments,
credit, write-off, refund, stock-posting jobs), split bills, tables / zones (SSE), reservations (+ no-show and
refresh crons in `PosReservationWorker`), KDS (SSE, reports), print lifecycle + receipts (text / HTML / PDF / ESC/POS
over TCP 9100 or the Windows spooler), loyalty / store credit / customer tabs, holds, shift handover, customer
statement + open credit, posting monitor (`PosStockPostingService`) and the 30 s drain / 5 min health worker
(`StockPostingWorker`, ShedLock), stock-posting timing subscribers, POS reports (X/Z, 13 range reports), table
reports, digital menu. `PosCashSessionHooks` plugs the shift-close drain and the Z-report lookup into accounting.

Kernel / cross-module changes made in P5: `kernel.web.EmptyBodyAdvice` (a bodiless request reads as `{}` like
Express), `kernel.common.Savepoints` (Prisma nested writes as JDBC savepoints — `PROPAGATION_NESTED` is not supported
by the Hibernate JpaDialect; also used by `CreditNoteService`).

Tests:
- `PosUnitTest` — `order-mutation-policy.spec.ts`, `pricing-policy.spec.ts`, `order/line-signature.spec.ts`.
- `PosMenuIT` — settings, lookup, stations, menu lifecycle, variants, accompaniments, modifiers, combos.
- `PosSaleIT` — `pos-sale-pipeline.spec.ts` (checkout → Order/Invoice/Receipt, balanced GL, over-tender change,
  tableless order resume/settle, discounts through save/resume, tab version guard, split settle atomicity +
  replay), checkout idempotency (key required, replay = one sale), KDS lifecycle, posting monitor, holds, loyalty,
  statements, reservations, all reports, digital menu, shift close + Z snapshot, tenant isolation,
  authorization matrix (219 routes).
- `PosStockPostingIT` — `stock-posting-job.spec.ts` (F-COGS exception, exactly-once under the reclaim race,
  period-close guard, recipe snapshot) + the shift drain.
- `PosTablesIT` — `pos-table-zones.spec.ts`, `pos-tables.spec.ts`, `pos-table-tab-uniqueness.spec.ts` (incl. the
  concurrent-open race), merge / unmerge / transfer / transfer-items / split-bill / table revenue.
- `PosMoneyIT` — refund cases of `pos-money-foundations.spec.ts`, sale void, `pos-orders.void.spec.ts` (fired-line
  void + cancel), `pos-credit-settlement.spec.ts` (credit sale, collection, statement, write-off),
  `pos-store-credit-issuance.spec.ts`, `pos-kot-print-delta.spec.ts`, `test/unit/pos-tender-validation.spec.ts`,
  shift handover.
- `PosTablesIT#recomputeTableStatus` — `table-status.util.spec.ts` against real rows.
- `PosLakeviewSimIT` — reduced port of the Lakeview simulations: deterministic trading days (`mulberry32` +
  `planDay`) on two tills; every day the X/Z cash and net sales, drawer ledgers, trial balance, on-hand, stock
  valuation and recipe COGS must tie out to an independent oracle (`-Dsim.days`, `-Dsim.ops`).

Not ported / not done (owner decision or later phase):
- The procurement / transfer / landed-cost / release-preflight days of `lakeview-15day`, `lakeview-d0-d1`,
  `lakeview-m3` (P4 services + `scripts/pos-release-preflight.cjs`); the shadow-pilot comparison.
- `pos-offline-inventory-replay.spec.ts` and the audit `inv-audit-*` POS cases that drive the sync queue → P6.
- Mock-heavy unit specs of `pos-orders.service`, `pos-invoice.service`, `pos-kds.service`, `pos-receipts.service`
  (golden ESC/POS bytes), `pos-reports.service`, `pos-print-lifecycle.service`, `pos.workflows`,
  `pos-tables.split-lifecycle` are covered behaviourally by the ITs above, not line by line.
- k6 p95 < 150 ms at 50 terminals: **not met** (see "Exit-criteria runs" below).
- QR menu ordering: `/menu/public/*` needs a JWT (Nest bug kept), so a customer's phone cannot load or order. Opening
  it means running a checkout with no staff user, drawer or verified payment — owner decision, not done.
- Parity scenarios not run against a live Nest.

### Exit-criteria runs (2026-10-20, Windows 11 + Docker Desktop Postgres 16, fresh `seed` database)

- **Sell loop e2e**: `contract-tests/e2e/pos-sell-loop.spec.ts` (= `reference/e2e/` copy + Idempotency-Key on
  `/cash-sessions/open|close`, which Nest also requires — the reference copy fails against Nest too): 5/5 green, twice
  on the same database.
- **Offline kill-and-replay**: `tools/perf/kill-replay.mjs` — checkout in flight, API hard-killed after 20–300 ms,
  restarted, same request replayed twice with the same key: exactly one `Order` per key, both replays identical, 7/7
  timings. A replay answers 201 when the sale committed but the response was lost (record `business_completed`,
  statusCode 201 — same as Nest `business-outcome.ts`).
- **Emulated ESC/POS printer**: `tools/perf/escpos-capture.mjs` on TCP with `pos.printerHost/printerPort` set: a
  checkout prints the KOT and the customer receipt (CP437 text, feed, `GS V 0` cut, `ESC p` drawer kick on the
  receipt; no `ESC @`, like Nest); `print` on an already printed receipt → 403 like Nest.
- **Web screens** (Vite dev server proxied to Spring): dashboard, tables (zones, stats), KDS (tickets + a transition)
  work, every API call 2xx. The terminal page crashes client-side: `ShiftCloseDialog.tsx` calls `usePosSettings()`
  after `if (!session) return null;` ("Rendered more hooks than during the previous render") whenever an open shift
  exists. The file is identical in POS-CAFE (regression from its commit `17126b5`, 2026-10-18), so it fails against
  Nest too. Fixed later in commit b46233c.
- **Web walkthrough** (2026-10-24, fresh `seed` database, Vite → Spring, failed-request recorder in the page): PIN
  login, open shift, table tab → KOT → bill → cash settle with change, item split with mobile-money + cash bills,
  equal split, fired-line void with manager override, sales return with manager approval, close shift (cash and MoMo
  tie out), KDS start → ready → served, customer display, and 19 back-office POS pages (reports, stations, KDS
  reports, registers, tables / reservations / zones / table reports, menu / modifiers / combos / accompaniments,
  receipts, receivables, returns, posting monitor, GL reconciliation, staff, dashboard): no failed request and no
  console error after the fixes listed under "Post-parity fixes". GL balanced, every stock-posting job done, no
  inventory exception.
- **Load smoke** (`tools/perf/checkout-smoke.mjs`, one register + session per virtual till, `THROTTLE_ENABLED=false`
  because the throttle is per user and every till shares the admin): 1 till p50 ≈ 240 ms; 10 tills ≈ 13 checkouts/s,
  p95 ≈ 0.9 s; 50 tills ≈ 20/s, p95 ≈ 3 s. One checkout runs ~400 SQL statements in ~36 transactions (Order → KOT →
  Invoice → GL → Payment → receipt/print lifecycle → outbox, as in Nest); Postgres CPU is the limit on this host.
  The outbox worker drains 50 rows/s (Nest default `OUTBOX_BATCH`/`OUTBOX_POLL_MS`) while a checkout writes ~13
  events, so under sustained load the backlog grows — same as Nest.
- Fixed on the way (all found by the load smoke): pool-starvation deadlocks — `EventBus.publish` and
  `AuditService.record` opened a `REQUIRES_NEW` transaction (second pooled connection) while the caller held one, and
  `SequenceService` did the same on the first use of a sequence (`order:YYYYMMDD` is daily, `invoice:YYYY` yearly).
  Fire-and-forget writes now run after the caller's transaction completes (`kernel.common.DetachedWrites`; Nest does
  not await them either) and a missing sequence is created in the caller's transaction under a savepoint. The
  workflow engine cached column sets but queried `information_schema` for the status type on every update; now
  cached too.
- Same as Nest, kept: the first concurrent sales of a fresh organization can race on creating the `WALKIN` partner
  (find-then-create, `Partner_organizationId_code_key` → 409 for the loser).

### Checkout DB-work reduction (2026-10-20, no behaviour change)

Profiled with `ALTER DATABASE … SET log_min_duration_statement = 0` on a fresh `seed` database: 5 warm-up + 10
sequential `POST /pos/checkout` (1 cash line, 1 cash tender), statements grouped by text, per checkout (includes the
outbox worker's share):

| | before | after |
|---|---|---|
| SQL statements | 379 | 247 |
| transactions (`BEGIN`) | 33 | 14 |
| write commits on the request path | 24 | 12 (+1 detached batch) |
| outbox worker statements | ~23 (claim + per row `SELECT attempts` + `UPDATE shipped`) | ~2 per batch |

Changes (all internal; responses, rows written, GL, events, audit rows and error messages unchanged — verified by
diffing normalized checkout responses and per-table / per-event / per-audit-action row counts, before vs after):

- `kernel.common.ReadMemo`: a per-request, thread-confined memo opened around `checkout` / `settleTab` /
  `settleOrder`. Inside it, org currency / time zone, currency decimals, account mappings, org-level settings (receipt
  header/footer, printer keys), tax rows and `PosDb.first` of Product / Tax / ProductCategory / Partner are read once.
  Misses are never memoized; settings and account-mapping writes (and `PosDb` writes of those tables) clear it.
  Outside a scope every lookup hits the database as before.
- `DetachedWrites.batch`: the fire-and-forget outbox / audit writes of one sale (≈ 10 single-row transactions) run in
  one detached transaction when the sale returns; if that transaction fails, every write is retried on its own, so a
  failing write still cannot take the others down. `EventBus` / `AuditService` now open their transaction with
  `REQUIRED` (they never run inside a caller's transaction, so this equals the old `REQUIRES_NEW` outside a batch).
- `OutboxWorker`: `attempts` comes back from the claim's `RETURNING`; dispatched rows are marked shipped with one
  `UPDATE … WHERE id IN (…)` per batch instead of one per row (a crash before it leaves them claimed; the 30 s
  stale-claim rule re-ships them — at-least-once, as before).
- Receipts: one `resolveInvoice` for the customer + cashier copies in `printReceipt` (marking the receipt printed only
  bumps its print counters) and one for the text + HTML receipt in the sale response; print-target lookups
  (`Invoice` / `Order` / `Document`) memoized.

Not changed on purpose: the Order → kitchen → Invoice → Payment transaction boundaries, row locks (`FOR UPDATE` on
cash session / order / invoice, `FOR SHARE` on the fiscal period), the fiscal-period / books-lock reads, and the
print-counter / print-log autocommit writes — they follow Nest's call structure.

Load smoke, same host, fresh database per run, interleaved before / after (the host was shared with other heavy
processes; run-to-run variance is large):

| tills | before r1 | after r1 | before r2 | after r2 |
|---|---|---|---|---|
| 1 | p50 612 / p95 1068 ms | 526 / 849 ms | 575 / 993 ms | 245 / 339 ms |
| 10 | 7.3/s, p95 1.9 s | 10.2/s, p95 1.5 s | 6.9/s, p95 2.3 s | 17.1/s, p95 0.89 s |
| 50 | 9.7/s, p95 10.5 s | 16.7/s, p95 4.1 s | 7.0/s, p95 36 s | 21.0/s, p95 3.3 s |

The 150 ms p95 at 50 tills is still **not met** here. Postgres-side time per checkout dropped (commit fsyncs were
most of it: 137 ms → 10 ms of `COMMIT` in the profile), but on this Windows + Docker Desktop host most of the wall time
is between statements (JVM ↔ Postgres round trips through the Docker port forward, host CPU contention): ~245
round trips per sale remain, most of them Nest's per-step reads/writes.
