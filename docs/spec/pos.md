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
- [ ] Web screens for this module work against Spring (no console errors) — not exercised
- [x] `docs/PORT_STATUS.md` updated

## Deviations

_None in the ported Nest contract._

Post-parity additions (no Nest counterpart, explicitly labelled):

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

Same as Nest, kept on purpose:

- `/menu/public/*` ("public" digital menu) is **not** `@Public()` in Nest: the global JWT guard applies, so a bearer
  token is required (no permission). Unauthenticated callers get 401.
- `PATCH /pos/menu/orders/:id/status`: `UpdateOrderStatusDto.status` has no class-validator decorator, so the global
  whitelist rejects it (`property status should not exist`); an empty body bumps `updatedAt` only (unknown id → 500).
- `POST /pos/tables/:id/split-bill` always answers 409 (`This operation conflicts with one that was just completed…`):
  the child orders are raised while the source tab still holds the table, which the
  `Order_one_open_dine_in_tab_per_table` index rejects. Split bills work through `/pos/tabs/:tableId/split*`.
- `POST /pos/tabs/:tableId/split/equal` answers 500: Nest writes split type `'equal'`, which the `SplitType` enum
  does not have.
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
- Web POS terminal: blocked by a client bug (see below); customer display and QR menu screens not exercised.
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
  Nest too; not fixed here (`web/` is no-change) — owner decision.
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
