<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
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

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/pos-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
