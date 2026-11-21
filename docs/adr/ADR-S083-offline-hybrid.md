# ADR-S083 — Offline / online hybrid (one sync protocol for web, Android and branch servers)

- Status: accepted by the owner (2026-11-20, plan revision 3). O0–O7 are built on stacked branches
  (`phase/O0-sync-foundations` … `phase/O7-branch-server`), none merged; see each "as built" section.
- Plan: phases O0 (foundations) → O1 (web sync engine) → O2 (web POS on `/sync`) → O3 (read-only cache of the whole web
  app) → O4 (inventory) → O5 (sales and field orders) → O6 (Android alignment) → O7 (branch server).
- Extension: no NestJS counterpart. Every `/sync` route and response member that Nest has is unchanged; O0 adds 6
  routes and some response members (§9).
- Migration: `V593__sync_o0_foundations.sql`.
- Related:
  - `docs/legacy/sync-protocol.md` (the original protocol)
  - `docs/spec/sync.md`
  - ADR-S072 (offline replay holds stock posting; R-B: each stock line commits on its own)
  - ADR-S002 (licence; `/sync/push` stays writable in read-only mode)
  - ADR-S077 (the `late_sync` signal)

## Context

The owner wants the whole system usable offline, syncing when online:
- web POS hardening
- inventory and warehouse
- sales and field orders
- a read-only cached view of every screen

This works on devices now and through branch servers later.

The Android app already syncs through `/sync/pull` and `/sync/push`. The web POS has its own REST replay queue.

Guiding principle (owner): **offline availability is not guaranteed consistency.** We keep what users actually did
offline, and still enforce accounting, inventory, security and business rules when those operations reach the
authoritative server.

## Invariants

1. **One protocol.** Web and Android both use `/sync/pull` and `/sync/push`. A branch server speaks the same protocol
   to its devices and, as a device of HQ, upward.
2. **GL is posted only by the authoritative server**, when an op is applied. No device or branch posts its own journals.
3. **Offline writes only create or append.**
   - Allowed: sales, payments, cash movements, GRNs, counts, transfers, orders.
   - Never offline: editing a posted document, a period change, a price or other master-data change, a permission change.
   - Facts: the device wins. Master data: HQ wins.
4. **Stable operation identity.**
   - An `opId` is minted once per logical transaction. It never changes across retries (timeout, 5xx, lost response,
     app restart, re-push).
   - A definitive business rejection becomes a dead letter and is never retried automatically.
   - A correction is a new op created by a person, with a new `opId` and `supersedesOpId` naming the rejected op.
   - Android today re-keys a rejected op (`wireOpId`); O6 removes that.
5. **The server re-checks everything on replay** (permissions, credit, stock policy, period lock). A real-world fact
   is never silently dropped: it is applied, applied and flagged, or dead-lettered.
6. **Identity:** `serverId`, `branchId`, `deviceId`, `deviceSeq`, `protocolVersion`, and `originChain` (the hops an
   op travelled, e.g. `device:<id>` → `server:<branch>` → `server:hq`).

## S1 / C1 — Retry semantics and the applied-operation ledger

| Situation | Client | Server |
|---|---|---|
| Timeout / no response | retry with the same `opId` and backoff | dedup by `opId` |
| 5xx, or 409 `OPERATION_PENDING` | retry with the same `opId` | — |
| Repeated push | — | `replayed` with the stored result |
| Definitive 4xx (business rule) | mark `rejected`, stop | dead letter |
| A person corrects a rejection | new op with `supersedesOpId` | the dead letter becomes `resolved`, linked by `resolvedByOpId` |

Each failed result now also carries `code` and `retryable` (additive members).

### Codes

- `BUSINESS_RULE`
- `PERMISSION_DENIED`
- `ACTOR_NOT_ALLOWED`
- `DEPENDENCY_FAILED` (424, retryable)
- `SERVER_ERROR` (5xx, retryable)
- `COUNT_CONFLICT` (a `stock.count` 409, definitive)
- `OP_ID_REUSED`
- `OPERATION_PENDING` (retryable, never dead-lettered)
- `OPERATION_INCOMPLETE`
- `PARTIAL_COMMIT`
- `DEVICE_REVOKED`
- `DEVICE_TOKEN_EXPIRED`

### Ledger (`SyncAppliedOp`, permanent)

Columns: `opId`, `payloadHash`, `opType`, `deviceId`, `deviceSeq`, `serverId`, `originChain`, `supersedesOpId`,
`state` (`committed` | `applied`), `httpStatus`, `resultJson`. Unique on `(organizationId, opId)`. RLS uses the V214
tenant policy.

### Atomicity (owner decision 2026-11-20; O0 correction 1, 2026-11-21)

Every op type is classified in `SyncOpCatalog`. An unclassified type is refused before its handler runs, and
`SyncOpCatalogTest` fails if the dispatch switch, the push DTO's type list and the catalog ever disagree (41 types).

| Boundary | How the ledger row commits |
|---|---|
| `OP_TRANSACTION` (23 types) | The push service opens one transaction (`erp.sync.op`) around the whole handler. The business rows and the ledger row commit or roll back together, and the row commits as `applied` **with the original result** (staged before commit). A failure leaves nothing; a crash after commit replays the original result. |
| `SERVICE_TRANSACTIONS` (18 types) | The handler calls an online service that owns its transactions (some commit in several steps on purpose: ADR-S072 R-B per-line stock commits, the checkout's order → invoice → payment). The ledger row rides on the **first business commit** (`state = committed`) and is completed with the result when the op returns. A failure after that first commit is `PARTIAL_COMMIT`; a crash before completion is `OPERATION_INCOMPLETE`. Either way the op is **never run again automatically**. |

How the commit hook works (`SyncLedger`):
- A `TransactionExecutionListener` registers a `beforeCommit` callback on every new write transaction that starts while
  an op runs. Read-only transactions and `BusinessOutcome.RETRY_SAFE_TX` are excluded.
- The first such transaction to commit inserts the row on the same connection. If the insert fails, the business commit
  fails with it.
- Idempotent pre-work that is safe to run again (the claimed stock-posting drain before `cash_session.close`) runs
  under `SyncLedger.outside(...)` so its commits do not anchor the row.

The payload hash is **key-order independent** (maps sorted at every depth). A stored dead-letter payload comes back from
`jsonb` with its keys reordered, and a supervisor retry or recovery must still be recognised as the same op when the
device later re-pushes it. An idempotency record written before O0 (as-sent hash) is still accepted, so ops pushed
across the deploy replay.

What the ledger tells the server:
- **Row present:** something committed, so the op never runs again. An `applied` row is replayed (and a dead letter left
  by a crash after commit is closed as `retried`). A `committed` row is `OPERATION_INCOMPLETE`, listed for
  reconciliation.
- **No row:** nothing committed. The idempotency key of a failed attempt is released at once, or after two minutes if
  an attempt may still be in flight, so the same `opId` can run again.
- **Same `opId`, different payload hash:** refused (`OP_ID_REUSED`), dead-lettered, never applied.
- **Concurrent pushes of one `opId`:** the idempotency lock plus the unique index mean exactly one applies. The others
  are `replayed`, or `OPERATION_PENDING` (retry).

#### Handler audit (all 41 op types)

"Side effects" lists everything outside the database transaction. Domain events go through the transactional outbox
(they publish only if the business commit does). No handler calls an external service (no HTTP, printer, fiscal device
or e-mail) during the op.

| Op type | Boundary | Before O0 correction 1 | Side effects outside the transaction |
|---|---|---|---|
| `cash_session.open` | service (`CashSessionService.open`, one tx) | transactional | `deviceId` stamp after commit (idempotent metadata) |
| `cash_session.close` | service (`close`, one tx) | drain anchored the row before the close | stock-posting drain first (claimed per job, keyed per line; now outside the ledger) |
| `cash_session.movement` | service (one tx) | transactional | — |
| `cash_drawer.open` | service (`PosCashDrawerService.record`, one tx) | transactional | — |
| `sale.checkout` | service (order, invoice, payment: several txs) | transactional, multi-commit | outbox events; `deviceId`/provisional stamp after commit (idempotent); crash recovery by the existing sale recovery (`clientOperationKey`) |
| `tab.settle` | service (several txs) | transactional, multi-commit | as `sale.checkout` |
| `sale.refund`, `sale.void` | service (`PosInvoiceService.refund`) | transactional | outbox events |
| `reservation.create/seat/cancel/noShow` (4) | service (one tx each) | transactional | — |
| `product.upsert`, `product.delete` | service (`@Transactional`) | transactional | — |
| `cashRegister.delete` | service (`@Transactional`) | transactional | — |
| `stock.in`, `stock.out` | service (`DirectStockService`, `@Transactional`) | transactional | — |
| `expense.create` | service (`@Transactional`) | transactional | — |
| `stock.count` | op transaction (already one tx, audit S-5) | transactional | — |
| `kitchen.void` | op transaction; waste lines stay REQUIRES_NEW, keyed per line (retry-safe) | **audit row and failure rows in auto-commit** | — |
| `setting.set` | op transaction | **two upserts in auto-commit** | — |
| `customer.upsert`, `customer.delete` | op transaction | **auto-commit** (duplicate-key retry now `ON CONFLICT`) | — |
| `menuCategory.upsert/delete`, `modifierGroup.upsert/delete`, `accompanimentGroup.upsert/delete`, `tax.upsert/delete`, `menuItem.upsert/delete`, `productCategory.upsert/delete`, `posTable.upsert/delete` (14) | op transaction | **auto-commit** (a `menuItem.upsert` failing on a variant left the item behind) | — |
| `cashRegister.upsert` | op transaction | **auto-commit on update** | — |
| `staff.pinChange` | op transaction | **auto-commit** | — |
| `message.send` | op transaction | **auto-commit** (duplicate id now `ON CONFLICT`) | — |
| `message.markRead` | op transaction | **auto-commit** | — |

After the correction no handler has a known duplicate-execution path. Remaining, stated plainly:
- A `SERVICE_TRANSACTIONS` op that crashes between its first and last commit is not re-run; it waits for reconciliation
  (`OPERATION_INCOMPLETE`) rather than replaying. `sale.checkout` / `tab.settle` recover at once through the existing
  sale recovery.
- The internal stock-posting drain inside `CashSessionService.close` (as opposed to the push service's own drain) can
  still anchor the row before the close validates. The op is then reported as `PARTIAL_COMMIT` instead of
  `BUSINESS_RULE`: conservative (never run twice), but a less precise code.
- Ops dead-lettered before V593 are classified `LEGACY_UNKNOWN` (V594). They have no ledger evidence and are never
  retried server-side: resolve, discard, or correct and resubmit.

### Batch idempotency

`/sync/push` honours `Idempotency-Key` (scoped to the device). A resent batch with the same body returns the stored
per-op results. A different body under the same key is 409.

## S2 / C4 — Offline authentication and authorization

Three separate concepts, each configured, enforced and tested on its own.

| Concept | Where | Default | Enforcement |
|---|---|---|---|
| User offline session | org setting `sync.offlineWindowHours` (1–720); device policy `POST /sync/devices/{id}/policy` may only lower it | 72 h | client (O1/O6) from `offlinePolicy` on every pull; also `idleTimeoutMinutes` (30), `pinMaxAttempts` (5), `pinLockoutMinutes` (5) |
| Device token expiry | org setting `sync.deviceTokenTtlDays` (1–365) | 30 days | server: an expired token cannot pull (401) |
| Device revocation | `POST /sync/devices/{id}/revoke` (`revokedAt`) | — | server |

### Token lifecycle

- **Protocol 2:** every successful pull rotates the token (`deviceToken` in the response). The replaced token stays
  valid for 24 h, so a lost response doesn't lock the device out. A pull with that previous token is answered with a
  fresh token again.
- **Protocol 1 clients** (current Android, which cannot store a new token): the expiry slides forward on each pull.
- Devices registered before V593 get their first expiry on their first pull.

### Cut-off for revoked or expired devices (O0 correction 2, 2026-11-21)

The device clock is never evidence. Five separate values:

| Value | Controlled by | Use |
|---|---|---|
| `occurredAt` | device | business event time only (reports, dating); never acceptance |
| `receivedAt` | server | first arrival (`SyncAppliedOp`, `SyncOpDeadLetter`) |
| `revokedAt` | server | revocation time |
| `deviceSeq` | device | monotonic op sequence |
| `lastAcceptedSeq` / `revokedAtSeq` | server (`PosDevice`) | highest applied sequence; frozen at revocation |

Such a device can no longer pull. Every push it makes arrives after its cut-off (`receivedAt > revokedAt`, or after
`tokenExpiresAt`), so **nothing new is applied**, whatever `occurredAt` claims:
- an op the server already applied (ledger row, same payload) replays its stored result: no new effect
- anything else is quarantined: dead letter `DEVICE_REVOKED` / `DEVICE_TOKEN_EXPIRED`, not retryable

**Supervisor recovery** (`POST /sync/dead-letters/{id}/recover`, POS__OVERRIDE, note required) for genuine ops recorded
before revocation but delivered after it. The server checks its own state, not the device clock:
1. The op's `deviceSeq` must be above the device's `lastAcceptedSeq` (an op the server has never seen).
2. Held ops are recovered in device order (no earlier held op of that device still open).
3. Every sequence between `lastAcceptedSeq` and the op must have reached the server (applied or dead-lettered). A gap is
   refused unless the supervisor sends `acknowledgeGap: true`; the acknowledgement is stored on the dead letter.
4. The op runs once under its original `opId`, with the actor's **current** permissions re-checked.
5. The note, the evidence (sequence state, `revokedAt`, `receivedAt`, the device-claimed `occurredAt`, missing
   sequences) and the outcome are audited. The dead letter becomes `resolved` with `recoveredById` / `recoveredAt`.

Active devices are not refused for sequence *regressions* (an earlier op retried after later ones applied is legitimate).
Since O6, a protocol-2 client's sequence *reuse* — the same `deviceSeq` under another opId — is refused (`SEQ_REUSED`,
see "O6 as built"); protocol-1 clients (older Android builds, which re-key) are not.

### Browser storage (O1)

The device token is encrypted with a non-extractable AES-GCM WebCrypto key kept in IndexedDB (`lib/offline/vault.ts`).
PIN hashes are bcrypt hashes from the `staff` scope; the PIN itself is never stored. The key defends against reading the
IndexedDB files and copying them to another profile. Script running on our own origin can
still *use* that key. The mitigations are the strict CSP, no third-party script on POS routes, and the short offline
window.

PINs and tokens are never logged (the existing redaction rules apply).

## O1 — Web sync engine (`web/src/lib/offline/`)

Built on branch `phase/O1-web-sync-engine` (stacked on O0). Engine only: no screen uses it yet (POS moves onto it in
O2), so O1 changes no existing behaviour.

| File | Role |
|---|---|
| `store.ts` | IndexedDB per organization (`erp-offline:<orgId>`): `ops` outbox, `rows`, `staging`, `idmap`, `meta`. Enqueue + sequence counter, pull rows + cursor, snapshot activation are each one transaction. |
| `engine.ts` | One pass = push, then pull (snapshot first when there is no cursor). Web Locks (`ifAvailable`) so one tab syncs; BroadcastChannel tells the others. |
| `retry.ts` | S1 rules: same opId on no answer / 5xx / 429 / `retryable`; `rejected` never retried; `held` for quarantine; exponential backoff with jitter (2 s → 5 min); device order kept (a not-yet-due op holds back later ops). |
| `snapshot.ts` + `checksum.ts` | C5 client: verified pages (raw bytes) staged with their progress; resume after a break; 410/409/404 restart once; activation only after `complete`. |
| `session.ts` | C4: offline PIN login (bcrypt), persisted throttling (failures → lockout, doubling), offline window from the last sync (clock rolled back → read-only), idle timeout, permission snapshot stamped with the sync time. |
| `vault.ts` | Encrypted device token. |
| `storage.ts` | `navigator.storage.persist()`; eviction detected by a `localStorage` marker without a `device` entry. |
| `web.ts` | Browser wiring: engine per org, `registerThisBrowser`, `startAutoSync` (online, visible, after enqueue, interval). |

Crash rules:
- An op is marked `in_flight` before it is sent; a pass that dies leaves it `in_flight` and the next pass (any tab)
  re-sends it under the same opId, so the server ledger replays it.
- The batch `Idempotency-Key` is sha256 of device, opIds and attempt round: an identical re-send replays the stored
  batch; a retry after a failure gets a fresh key (per-op dedup still applies).
- A 400 for a whole batch is narrowed to single ops, so one malformed op is rejected locally (`VALIDATION`) and the
  rest still sync.
- A rotated token is stored before the pulled rows; the replaced token stays valid for the server's grace period.

Device states:
- **token expired** (401 "expired" on pull): pull stops, queued ops are still pushed (the server holds them), local data kept.
- **revoked** (401 on pull, token still recognised — checked with an empty push, which the server authenticates
  before validating): every op not applied is exported to `meta.revokedExport` for supervisor import, then the store
  is wiped.
- **unknown token** (401 on both): `unregistered`, nothing wiped.
- **426** on pull: `upgrade_required`; the push before it still ran.

Browser support: Chrome / Edge 69+, Android WebView (Chrome 69+), Safari 16+ (Web Locks 15.4, BroadcastChannel
15.4, non-extractable CryptoKey in IndexedDB). Firefox 96+ works but is not a supported POS browser. Missing Web Locks
falls back to an in-tab lock; missing BroadcastChannel only loses cross-tab status.

Tests: Vitest with `fake-indexeddb` (`web/src/lib/offline/__tests__/`, 41 tests) against a fake server with an
applied-op ledger: lost response, crash mid-push, batch replay, retryable vs rejected vs held, 400 isolation, multi-tab
lock, token expiry, revocation export + wipe, unknown token, 426, offline; snapshot checksum retry, resume, 410/409
restart, failed completion; PIN throttling, offline window, clock rollback, idle timeout; vault; store atomicity.

## O2 — Web POS on the protocol

Built on branch `phase/O2-web-pos-sync` (stacked on O1). Only a browser registered as a sync device (Devices page →
"This browser") takes part; an unregistered browser keeps the pre-O2 behaviour exactly.

### Routing rule (one per write)

| Situation | Path |
|---|---|
| Server reachable | REST, unchanged (immediate receipt and numbers, Nest parity) |
| A REST attempt got no answer | finished over REST with the same `Idempotency-Key` and body (the legacy write-ahead queue) |
| Server known unreachable (a request failed in the last 15 s), registered device | sync op with a provisional number |
| Online, but the body references a client id that has not synced (an offline shift) | sync op, behind the op that creates the reference |

The REST queue is **narrowed, not removed**: it now holds only REST attempts whose outcome is unknown. Converting
those into ops is unsafe — the REST idempotency hash covers method + path + raw body, the op hash covers the op
payload, so the server could not match the two and would refuse the op as a reused id (safe, but a sale that did post
would need a person). Each POS sync pass drains that queue first, then pushes ops, so device order holds across both.

### Writes with an offline form

`/pos/checkout` → `sale.checkout`; `/pos/tabs/{id}/settle` → `tab.settle`; `/cash-sessions/open|close|movement` →
`cash_session.*`; `/pos/cash-drawer/openings` → `cash_drawer.open`; POS customer create (`/partners`) →
`customer.upsert` (the device-minted id is the server id). Everything else needs a connection, as before (order
settle, refunds and voids, tab saves, holds, split bills, loyalty).

Refused offline with a 409-shaped message (the cart stays, nothing is "saved"):
- a write carrying a manager approval (`overridePin`, `managerPin`, `incomingPin`, `approvalToken`, `approverEmail`) —
  a PIN is never stored on the device;
- the offline window lapsed, the device never synced, or its clock reads earlier than the last sync;
- an offline sign-in that idled out.

### References between offline writes

The op `clientId` equals its `opId` (the cart's idempotency key for sales). Payloads are never rewritten after they
are first queued (the hash must stay stable). The server resolves a client id within the batch as before and, new in
O2, **across batches** through the applied-op ledger: the creating op's `opId` (unique index) or a shift-open's
`mapping` key (partial index, V595) — same organization and device only (`SyncCrossBatchIT`). This also closes the gap
for Android, whose retries could land in a later batch than the op they depend on.

### Screens

- **Sale finished offline:** the receipt shows `OFF-<device>-<seq>`; the badge next to the connection indicator lists
  offline work with provisional → final numbers; refused work points to Sync dead letters (never retried).
- **Reads offline:** menu and payment methods from the pulled scopes; payment accounts and POS settings from the last
  good answer kept on the device. A refusal (any HTTP status) is never masked.
- **Offline PIN login:** the staff list and bcrypt hashes come from the `staff` scope; throttling, idle timeout and the
  permission snapshot are O1's. Back online, a cashier signed in offline signs in again so REST writes carry a
  server-issued POS token.
- **Shifts:** a shift opened offline is the open shift until the server has it. With the server unreachable the close
  is a short form (counted cash + each tracked tender balance); expected cash, variance and every close rule are the
  server's at sync — a close that needs a manager is refused there and goes to a supervisor. Before an online close,
  the device pushes its offline ops. Recovery both ways (opened offline / closed online and the reverse) is covered by
  `SyncCrossBatchIT`; rejected money ops still block the close server-side (O0 correction 3).

### Not in O2

The `reference/e2e` sell loop is read-only (`reference/`), so the offline leg is covered by the Vitest suites and
`SyncCrossBatchIT` instead; a browser-driven offline shift on org DEMO is the manual check. Tab saves, order settle,
refunds/voids, reservations and expenses stay online-only on the web (their ops exist; their screens are not wired).

## S3 — Cache policy (O3)

- An explicit allowlist (`web/src/lib/offline/cache-policy.ts`). Every query-key family is classified:
  - `cache`: catalogue
  - `cache-permissioned`: customers, suppliers, stock snapshots, invoices
  - `cache-strict`: financial reports, role-gated, 24 h retention
  - `never`: auth, licence, secrets, password reset, tokens
- An unclassified key defaults to `never`. A unit test fails if a GET hook uses an unclassified key.
- Keys carry org and user. The cache is wiped on logout, org switch, a permission-snapshot change and device revoke.
- Size cap with LRU eviction.

### O3 as built (branch `phase/O3-web-readonly-cache`)

- **Policy:** `cache-policy.ts`, ordered rules (exact names and patterns), first match wins. Retention: `cache`
  7 days, `cache-permissioned` 3 days, `cache-strict` 24 h. Besides auth, licence and secrets, `never` also holds live
  state that is wrong when stale (open shift, tabs, holds, kitchen tickets, approvals, store credit), previews and
  pickers, signed file URLs, personal messages and alerts. `cache-policy.test.ts` scans `src/` and fails on any
  literal query-key family (and `*KEY` / `*Key` constants) without a rule, and on dead or contradictory entries.
  Keys built at run time with no literal are not seen by the scan; they fall to `never`.
- **Storage:** `query-cache.ts`. One IndexedDB entry per organization + user (`erp-query-cache:<org>:<user>`),
  stamped with a permission fingerprint. The pre-O3 single entry (`pos-query-cache`) is deleted.
  - Sign-out or an organization/user switch deletes the old entry and clears memory.
  - A permission change drops `cache-permissioned` and `cache-strict` from memory (the next save rewrites the disk);
    a restore under different permissions keeps catalogue only.
  - Device revocation clears everything.
  - Size cap (20 M characters serialized): the most recently fetched results are kept; "LRU" is approximated by
    fetch time.
- **Reachability:** `reachability.ts`, one sticky answer. Unreachable from a request with no answer until any
  answered request or a health probe (every 10 s while unreachable) succeeds. `navigator.onLine` events only trigger a
  probe. `serverUnreachable()` (used by the POS routing of O2) now reads this sticky state instead of a 15 s window.
- **React Query follows it** (`onlineManager`): while unreachable, queries pause and keep the cached data on screen
  instead of turning into error screens; they refetch when the server answers. Mutations run with
  `networkMode: 'always'`, so every write reports its outcome. This also fixes a latent O2 issue: with the browser
  offline, React Query used to pause POS writes and offline reads silently. The POS queries that read local data
  themselves (menu, payment methods and accounts, POS settings, open shift) use `networkMode: 'always'`.
- **Write guard** (axios): while unreachable, a back-office write is refused at once with
  `503 NEEDS_CONNECTION` "Needs connection — changes cannot be saved while the server is unreachable" (one toast),
  instead of a timeout. Exempt so their behaviour is unchanged: the POS screens (`/pos/*`; O2's replay queue and
  sync ops run only there) and sign-in.
- **Screens:** `OfflineDataBanner` above every back-office page: "Offline: data as of HH:MM" (the oldest result on
  the current screen). Form submit buttons (`<Button type="submit">`) are disabled with "Needs connection" while
  unreachable, outside the POS, and so are the confirming actions of `ConfirmDialog` and `AlertDialogAction` (post,
  approve, delete…), all through `useWritesBlocked()`. Remaining one-click write buttons rely on the guard.
- **Run-time keys:** in development, a query family with no rule is named once in the console
  (`[offline cache] … has no rule`), covering keys the source scan cannot see.
- **Not in O3:** no new server routes. The offline view is only as complete as what the user opened while online
  (no prefetch of the whole app).

## S4 — Inventory conflict and reconciliation (O4)

- A physical fact that happened offline is always kept (`recordedOffline`, `occurredAt`).
- **Negative stock allowed:** post, and raise a `stock_discrepancy`.
- **Negative stock disallowed:** keep the document and hold the posting (the ADR-S072 pattern) for a supervisor.
- **Transfers:** two steps, `transfer.dispatch` → transit location → `transfer.receive`. A receive whose dispatch
  hasn't synced yet waits in `awaiting_dispatch`.
- **Counts:** variance = counted − server on-hand as at `occurredAt`.
- **Count conflicts:** a count against an open draft is a definitive dead letter (`COUNT_CONFLICT`, built in O0).
- **Reports and blocks:** an inventory discrepancy report. Open stock dead letters block period close for the location.

### O4 as built (branch `phase/O4-inventory-offline`)

**Ops** (all `SERVICE_TRANSACTIONS`: each reuses the existing inventory/procurement service, so the ledger row rides
on the first business commit and a later failure reports `PARTIAL_COMMIT`). Code: `sync/application/SyncStockService`.

| Op | Payload | What the server does |
|---|---|---|
| `transfer.dispatch` | `transferId` (an existing transfer, or the `clientId` of one made offline), **or** `clientId` + `fromLocationId` + `toLocationId` + `items[{productId, variantId?, unit?, batchNumber?, quantity}]` | Creates a transit transfer if needed (`StockDocService`), approves it when the actor may (approval gate unchanged), dispatches it to the transit location, then applies any receipt waiting for it |
| `transfer.receive` | `transferId` + `lines[{itemId, received, damaged?, short?}]` | Not yet dispatched on the server → kept as `offline_awaiting_dispatch` and applied when the dispatch arrives (in the same push, or by `SyncStockJob` every 2 min); otherwise `receiveTransfer` |
| `stock.adjust` | `clientId`, `locationId`, `reason`, `lines[{productId, variantId?, unit?, batchNumber?, qtyChange}]` | Creates a **pending** adjustment with counted = on-hand + `qtyChange`. Approval, SoD and the approval gate stay in the back office |
| `grn.receive` | `clientId`, `purchaseOrderId`, `warehouseId` or `locationId`, `lines[{purchaseOrderLineId, productId, variantId?, description, quantity, uomId?, batchNumber?, expiryDate?}]` | `GoodsReceiptsService.createDraft` (`receivedAt` = `occurredAt`, branch = the device's); posts it when the actor has `goods_receipt:post`, otherwise holds it |

Permissions per op: `inventory:move` (dispatch, receive, adjust); `goods_receipt:create` (grn). A missing permission is
a definitive 403 dead letter. Every document made offline is stamped `recordedOffline = true` and `occurredAt` (V596).

**The push result gains an additive `outcome` member** for these ops: `{state, reason?}`, where state is
`dispatched | received | posted | pending_approval | awaiting_dispatch | held`. The op itself is `applied`: the fact
is kept even when its posting is held.

**S4 as built — deviations to approve:**
1. **Holds use `InventoryException`, not a new `stock_discrepancy` table.** Kinds `offline_negative_stock`,
   `offline_held_posting` and `offline_awaiting_dispatch`. They appear in the existing Posting Monitor (resolve with
   POS__OVERRIDE), and an open exception in a period already blocks period close.
2. **A business refusal of a posting step (400/409/422) is a hold, not a dead letter.** The document stays, the
   reason is recorded, and a supervisor completes or reverses it in the back office.
3. **Transfers never take their source below zero** (existing `StockDocService` rule), whatever
   `inventory.allowNegativeStock` says: a short dispatch is held. The allow-negative path (post + flag) applies to
   `stock.out` and the other direct issues.
4. **Count as-at `occurredAt`** is done by carrying later movements forward: the counted figure submitted to the
   count = counted + Σ ledger movements after `occurredAt` at that location. The variance posted is therefore
   counted − on-hand-when-counted.
5. **Offline adjustments stay pending approval** (as any adjustment does online); they are not posted on sync.
6. **Serial numbers on GRN lines** are not taken offline. A receipt of a serial-tracked item is held for the back
   office.

**Period close** (`PeriodCloseService`): an `open` / `investigating` `SyncOpDeadLetter` of a stock op type
(`SYNC_STOCK_OP_TYPES`, kept equal to `SyncOpCatalog.STOCK_TYPES` by `SyncOpCatalogTest`) created up to period end
blocks the close, naming the locations.

**Scopes** — sent only when named in `scopes` (a default pull is unchanged), limited to the device's branch
(locations with that `branchId` or none): `openPurchaseOrders` (approved / sent / acknowledged / partially received,
with lines), `transfers` (transit; approved / in transit / partially received, with items), `lots` (quantity > 0),
`serials` (`in_stock`), `uomConversions` (product units with factors). `stockLocations` gains `branchId` (additive).

**Report:** `GET /sync/stock-discrepancies` (`inventory:read`; filters `branchId`, `deviceId`, `locationId`, `kind`,
`status`; newest 500) — web page `/inventory/offline-discrepancies`.

**Web — warehouse tablet** `/warehouse/tablet` (`inventory:move`; registered device required): Receive PO, Dispatch
(an approved transfer, or scanned goods), Receive transfer (received / damaged / missing per line; warns when the
dispatch has not synced), Adjust, Count, and the recent work on the device with its server outcome. Scanning: a
keyboard-wedge scanner into the scan box, or the camera via `BarcodeDetector` where the browser has it. Codes resolve
offline against serials, variant barcode/SKU, packaging barcode (counts the pack), product barcode/SKU/code, then
lots. The tablet path is treated like the POS by the O3 write guard (`isOfflineCapablePath`). Pure logic:
`web/src/features/warehouse/offline-stock.ts`.

**Tests:** `SyncInventoryIT` (9: ad-hoc dispatch then receive by client id across batches; a receipt waiting for its
dispatch; held when negative stock is blocked; flagged when allowed; a short transfer held even when allowed;
pending adjustment; GRN posted with stock; count as-at; scopes only when named), `SyncOpCatalogTest` (45 types, stock
types = period close list), `SyncIT` authorization matrix (16 routes); Vitest `features/warehouse/__tests__/offline-stock.test.ts` (6).

**Not done in O4:** a browser run of the tablet on DEMO; a period-close IT for the stock dead-letter block (covered
by the constant test only); camera scanning on Safari/Firefox (no `BarcodeDetector`, scanner input works).

## S5 — Dead-letter workflow (O0 server side; UI in O1/O2)

Statuses: `open` → `investigating` → `resolved` (correction op linked) | `retried` | `discarded`. Every transition is
audited.

Columns (additive):
- `failureCode`
- `retryable`
- `attempts`
- `lastAttemptAt`
- `branchId`
- `resolvedByOpId`
- `investigatingById` / `investigatingAt` / `investigationNote`
- `receivedAt`, `recoveredById`, `recoveredAt`, `recoveryGapAcknowledged` (V594)

List items also carry `legacy` (failure code `LEGACY_UNKNOWN`) and `recoverable` (quarantined).

Routes:
- `POST /sync/dead-letters/{id}/investigate` — note required; POS__OVERRIDE.
- `POST /sync/dead-letters/{id}/retry` — note required; POS__OVERRIDE. Re-runs the stored op under its original
  `opId`. Only for retryable failures. Refused when a PIN/password was redacted from the stored payload (now also
  `approverPin`), and for `LEGACY_UNKNOWN` dead letters.
- `POST /sync/dead-letters/{id}/recover` — note required; POS__OVERRIDE. Only for quarantined ops
  (`DEVICE_REVOKED` / `DEVICE_TOKEN_EXPIRED`); see S2.
- `POST /sync/dead-letters/{id}/resolve` — the Nest route, unchanged. It now also accepts `investigating`. Discard
  needs a note.

Automatic transitions:
- An op that finally applies (retried with the same `opId`) closes its own dead letter as `retried`.
- An op carrying `supersedesOpId` closes the superseded one as `resolved`.

### Cash-session close (O0 correction 3, 2026-11-21)

A shift does not close while a **money** op that belongs to it was rejected by sync: a dead letter `open` or
`investigating` whose type is in `SyncOpCatalog.moneyTypes()` (`sale.checkout`, `tab.settle`, `sale.refund`,
`sale.void`, `cash_session.movement`, `expense.create`), legacy and quarantined ones included. It belongs to the shift
when its payload names the shift (`cashSessionId` / `sessionId`) or it came from the device that opened the shift.
The close answers 409 `SYNC_DEAD_LETTERS_OPEN` with the dead-letter ids.

- **Pending** ops (not yet pushed; `pendingSyncCount` reported by the client) are not failures: they only warn.
- **Supervisor override:** `syncOverrideReason` on `POST /cash-sessions/close` or `/{id}/force-close` (and on the
  `cash_session.close` sync op). Needs `pos:override` and a non-blank reason. The override is audited on the cash
  session with the reason, the approver and every dead letter it passed. The dead letters stay open for follow-up.

This restores the rule the plan agreed. Before O0 the close only logged a warning (Nest parity); the block is a
deliberate, owner-requested deviation, recorded in `docs/spec/sync.md`.

## S6 / C3 — Branch identity, authority, relay contract (built in O7 as a relay node — see "O7 as built")

| Data | Authority |
|---|---|
| Branch sales, cash, local stock movements | Branch transaction record, relayed to HQ |
| Consolidated GL, financial statements | HQ |
| Products, prices, taxes, users, roles, permissions, period locks | HQ (pulled down, read-only at the branch) |
| Local operational availability | Branch |
| Cross-branch transfers | Two-step workflow (S4), coordinated by HQ |

### Relay

- The business rows and a relay-outbox row commit in one branch transaction.
- A ShedLock job pushes them to HQ with the original `opId`s and the extended `originChain`.
- HQ dedups through its own ledger.

### Relay states (`relay_state` on every relayed document)

- `accepted_local`
- `pending_hq`
- `hq_posted` (GL references come back)
- `hq_rejected` (dead letter at HQ)

### Branch accounting

- **Cash-session close** at a branch reconciles against local facts. It is allowed while documents are `pending_hq`,
  and blocked by `hq_rejected` money documents.
- **Branch operational reports** (sales, cash, stock) come from local data, labelled "includes N pending HQ posting".
- **GL and P&L** are HQ-only.
- **Period locks** come from HQ. A branch op dated into a locked period is a dead letter at HQ, never posted into the
  next period.
- **Master data** is never edited at a branch, so two branches cannot conflict on it.

## S7 / C5 — Cursor, paging and snapshots

Delta pulls are unchanged. Two additions:

- **Protocol 2 tombstones:** deltas also ship soft-deleted rows (with `deletedAt`) for `SOFT_DELETE` models.
  Protocol 1 is unchanged, because current Android would otherwise upsert them as live rows.
- **Menu-child triggers (V593):** a change to a variant, modifier link, accompaniment link, modifier or option bumps
  `MenuItem.updatedAt` (and the group's), so the aggregate is re-sent.

### Snapshot protocol (first sync and resync)

1. `POST /sync/snapshot {scopes?}` → `{snapshotId, snapshotAt, expiresAt (1 h), scopes, pageSize (500), pages,
   pageCount}`. `messages` is excluded; it pages by its own sequence cursor.
2. `GET /sync/snapshot/{id}/page?scope&after=` → `{rows, nextAfter, hasMore, pageChecksum}`.
   - Keyset by `id`.
   - The checksum is sha256 of the page's `rows` exactly as served: the server serializes the rows once and writes
     those bytes into the response (O1 fix — the HTTP mapper's own output differed from the hashed bytes, so no
     client could verify a page). The web client hashes the raw `rows` text of the body; re-serializing parsed JSON
     in a browser does not reproduce Jackson's output. `SyncProtocolContractIT` asserts the byte equality.
   - Resumable from the last `after`.
   - An expired snapshot is 410.
3. `POST /sync/snapshot/{id}/complete {scopes: {name: {rowCount, checksum}}}`.
   - `checksum` = sha256 of the scope's page checksums joined by `,`, in page order.
   - The server follows the chain of pages it served. A missing page, a count mismatch or a checksum mismatch is 409.
   - On success: `status: completed` plus the delta cursor with watermarks at `snapshotAt`.
4. **Atomic activation (client, O1/O6):** load into a staging store and swap it in only after the acknowledgement. A
   partial snapshot never replaces the active dataset.

Consistency: rows changed or deleted during the download come back through the deltas that start at `snapshotAt`
(5-second overlap; tombstones on protocol 2).

The four full-set scopes (paymentMethods, ledgerAccounts, expenseCategories, stockLocations) page through the snapshot.
Delta pulls still send them in full, because Android prunes local rows against that full set.

## S8 / C2 — Protocol versioning and disaster recovery

### Versioning

- Clients send `X-Sync-Protocol: <major.minor>`. None means `1.0`. The server is `2.0` and supports majors 1–2.
- Pull answers 426 for an unsupported version. Push is always accepted, so queued ops are never lost.
- The device's declared version is recorded (`PosDevice.protocolVersion`).

### Disaster recovery

| | HQ | Branch (O7) |
|---|---|---|
| Backup | continuous WAL archiving + PITR | nightly base backup + WAL |
| RPO | ≤ 5 min | ≤ 15 min |
| RTO | ≤ 4 h | ≤ 4 h |

These are owner-adjustable defaults. WAL/PITR is documented (OPERATIONS.md §7c), not deployed by O0.

**The ledger is not a backup.** Lost business records are restored only from database backups. After a restore,
devices and branches re-push their unacknowledged ops, and normal dedup decides apply or replay:
- an op whose ledger row was lost with the restore is applied again (its business rows were lost too)
- an op whose row survived is replayed

The post-restore reconciliation job (designed here, built with O7) lists:
- `lastPushSeq` and branch relay watermarks against the restored ledger
- `state = committed` ledger rows
- GL integrity (balanced journals; subledger vs control accounts)
- stock (`StockItem` vs the sum of movements)

## S9 — Observability (later phase)

- A `/sync/status` dashboard: pending, failed and synced counts; last sync per device and branch; open dead letters;
  protocol versions.
- Micrometer metrics: `sync_push_ops_total{status}`, `sync_lag_seconds{device}`, `sync_deadletters_open`.

Not built in O0 — **deferred, tracked** (see "Deferred work").

## C6 — Variance tolerances

- Org settings `sync.varianceTolerance.{salesOrder,quote,posSale}` (percent, default 0.5), read by
  `SyncPolicyService.varianceTolerancePercent`. Used from O5.
- **Payments have no pricing tolerance.** `payment.receive` records the amount actually received. Allocation is
  reconciled separately, and any difference stays unallocated or on account; it is never auto-adjusted.

### O5 as built (branch `phase/O5-sales-offline`) — sales and field orders

**Ops** (all `SERVICE_TRANSACTIONS`, each reusing the online service; `payment.receive` is a money op). Code:
`sync/application/SyncSalesService`.

| Op | Payload | What the server does |
|---|---|---|
| `salesOrder.create` | `clientId`, `partnerId`, `lines[{productId, variantId?, uomId?, quantity, discountPercent?, description?, sourceLocationId?}]`, `expectedTotal`, optional `paymentMode`, `paymentTermId`, `requestedDate`, `customerRef`, `notes`, `deliveryAddress`, `locationId`, `currencyCode`, `confirm` (default true) | `SalesOrderService.create` with **server prices** (any device `unitPrice` is dropped; a manual `discountPercent` is kept and its approval rules apply), then the price audit, then `confirm` (approvals, stock policy and credit check as online), then the offline cap |
| `quote.create` | as above with `validUntil`, `submit` (default true) | `SalesQuotationService.create` with server prices, the price audit, then `submit` (auto-approve or approval) |
| `payment.receive` | `clientId`, `partnerId`, `amount`, `paymentMethod` (cash, bank, mobile_money, card, cheque), `reference?`, `paymentDate?` (default the `occurredAt` date), `allocations[{documentId, amount}]` | `PaymentService.createReceipt` for the **amount received**. Each allocation is applied up to what is still open on that invoice at sync; the rest stays unallocated (on account). If the posting refuses the allocations (an invoice changed in between), the whole payment is taken on account. No tolerance (C6) |

Permissions per op: `sales:order:write` (+ `sales:order:confirm` to confirm), `sales:quotation:write`,
`payment:create`. Documents are stamped `recordedOffline` + `occurredAt` (V597); payments also carry
`offlineActorId` (the rep), because `Payment` has no creator column.

**Outcome** (additive member of the push result): `{state, reason?, total}` with state `confirmed | on_hold |
pending_approval | credit_approval | draft | price_review` (orders), `approved | pending_approval | draft |
price_review` (quotes), `posted | partly_unallocated` (+ `allocated`) (payments).

**Review queue** — new table `SyncReviewItem` (V597), kinds `price_variance`, `confirm_refused`, `offline_cap`,
`payment_allocation`. `GET /sync/sales-reviews` (`sales:order:read`), `POST /sync/sales-reviews/{id}/resolve
{note}` (`sales:order:hold_release`). Resolving records what a person did; it never changes the document. Web:
`/sales/offline-review`.

**Payment reconciliation** — `GET /sync/payment-reconciliation?from&to&userId&deviceId` (`payment:read`): per rep
and day (by `occurredAt`, UTC) the payments, amount received, allocation asked, allocation applied, left on account,
voided, and `payment.receive` dead letters (count, amount claimed, still open). Web: `/sales/offline-payments`.

**Scopes** — sent only when named, always in full, limited to the device's branch (customers whose home branch it
is, or none): `priceLists` (active pricelists with current rules), `paymentTerms`, `customerCredit` (per customer:
pricelist, payment term, salesperson, limit, hold, and where a limit applies the exposure / headroom / overdue at
pull time), `openSalesOrders` (last 90 days, not done or cancelled, with owner), `openInvoices` (customer invoices
with a balance). `offlinePolicy` gains `offlineOrderCap` and `varianceTolerance` (additive).

**Web — field sales** `/sales/field` (`sales:order:write`; registered device; offline-capable like the POS):
New order (customer search with the credit snapshot, product search, the device's price estimate, manual
discount, payment mode / term, a local credit warning and the cap), Quote, Collect payment (open invoices,
oldest-due auto-allocation the rep can change), My orders & invoices, and the server outcome of recent work. Pure
logic: `web/src/features/sales-field/offline-sales.ts`.

**O5 as built — deviations to approve:**
1. **The device price is an estimate.** It uses the customer's pricelist (else the default) — fixed / discount /
   surcharge rules by product, category or all, quantity breaks, dates, branch. Agreements, promotions, site
   pricelists, named price lists, partner-category rules and cost-plus rules are the server's alone; the audit
   catches the difference. The device's prices are never posted.
2. **The audit compares document totals (tax included), not lines.** Beyond the tolerance the document is kept as a
   **draft** (not confirmed / submitted) with a `price_variance` item, rather than confirmed and put on hold.
3. **The server prices as of the sync**, not as of `occurredAt` (the sales services price "today"). A price change
   between the two shows up as a variance.
4. **"The rep's own" orders and invoices are filtered on the device.** A pull is authenticated by the device, not the
   user, so the scopes cover the device's branch; the page shows the signed-in rep's own (owner / salesperson).
5. **Offline order cap** (`sync.offlineOrderCap`, org setting, base currency, 0 = none): per rep, the confirmed
   offline orders whose `occurredAt` is within the offline window. The device refuses to record past it (counting
   its unsynced orders); the server puts the order on hold with an `offline_cap` item.
6. **Credit**: confirmation runs the normal credit check — a hold or breach puts the order on hold as online; an
   org whose policy is `block` gets the order kept as a draft with a `confirm_refused` item; `approve` opens a
   credit request (`credit_approval`).
7. **Field payments have no cash session.** An open `payment.receive` dead letter from a device blocks closing a
   POS shift opened on that device (it is a money op, `CashSessionService.SYNC_MONEY_OP_TYPES`).
8. **The payment evidence trigger is redefined** (V597, `guard_payment`): everything is as in V1, plus exactly one
   permitted change — `recordedOffline` false → true with `occurredAt` / `offlineActorId` set from nothing. The
   stamp is immutable afterwards (tested).

**Tests:** `SyncSalesIT` (10: order confirmed; price variance → draft + review, queue listing and resolve; credit
hold; offline cap → hold + review + policy on pull; quotes submitted / price review; payment with an invoice settled
meanwhile → partly on account + review + reconciliation report; payment in full + the stamp immutable; scopes only
when named; unknown customer → dead letter), `SyncOpCatalogTest` (48 types; money list = cash close), `SyncIT`
authorization matrix (19 routes); Vitest `features/sales-field/__tests__/offline-sales.test.ts` (7).

**Not done in O5:** a browser run of the field-sales page on DEMO; per-line price variance; offline creation of a new
customer from the field page (`customer.upsert` exists but the page does not offer it); a sales-review alert to
supervisors through the alerts module.

### O6 as built (branch `phase/O6-android-alignment`) — Android alignment

**Android now speaks protocol 2** (`X-Sync-Protocol: 2.0` on every request, `SyncApi.kt` / `AppModule`):

| Area | Change (`android/app/.../data/repo/SyncRepository.kt` unless noted) |
|---|---|
| S1 retries | A failed result with `retryable` (or, from an older server, 5xx / 424 / 429 / 409 "being processed") stays **queued under the same opId** with exponential backoff (2 s → 5 min); queued ops behind it wait (device order, as on the web). After 8 automatic attempts it is parked for a person, who may **Retry** it — same opId. |
| S1 rejections | A definitive failure is marked failed with its `failureCode`; it is **never re-sent or re-keyed**. The sync screen offers **Resubmit…** (manager PIN, `pos:override`): a correction op with a new opId, the next sequence, the same payload and `supersedesOpId`; the rejected op stays as `superseded`, and the result lands on the original sale/refund/expense row (`rowId`). Held codes (`DEVICE_REVOKED`, `DEVICE_TOKEN_EXPIRED`, `OPERATION_INCOMPLETE`, `PARTIAL_COMMIT`) are not offered: a supervisor recovers them. Discard is unchanged. |
| Legacy re-keyed ops | An op an older build re-keyed (`wireOpId`) keeps going out under that key; Room v15 gives the unfinished ones a fresh sequence above everything queued, so the server does not see a used sequence under a new id. |
| Sequences | `DeviceConfig.seqHighWater` records the highest sequence sent. After a backup restore (`renumberPending`), unsent ops are renumbered above it once. |
| Token (C4) | A protocol-2 pull's rotated `deviceToken` is committed **before** the rows are applied. A 401 whose message says the token **expired** no longer wipes the till: nothing is deleted, the push before the pull still runs (the server holds those ops), and the till shows "re-enroll". Any other 401 after a first sync is still revocation (wipe of staff + enrolment; unsynced data kept). |
| Version (S8) | A 426 on pull is reported ("update the app"); pushes still go through. |
| Tombstones (S7) | Protocol-2 deltas carry soft-deleted rows: menu items (with their children) and cash registers are now removed on a tombstone; the other scopes already were. |
| Offline session (C4) | `offlinePolicy` from every pull is stored. **Offline window:** past `offlineWindowHours` since the last pull — or with the clock earlier than the last pull — the terminal refuses new sales ("sync first"); the queue still pushes (`OfflineWindow.kt`, checkout in `TerminalViewModel`, banner `SyncHealthBanner`). **PIN throttling** follows `pinMaxAttempts` / `pinLockoutMinutes` (was a fixed 3 failures / 15 s doubling); the permanent lock at 10 (server parity) stays. **Idle timeout:** a signed-in cashier idle past `idleTimeoutMinutes` returns to the PIN screen (`MainActivity`). |
| Room v15 | `op_queue`: `supersedesOpId`, `rowId`, `failureCode`, `retryable`, `nextAttemptAt` (additive; `MIGRATION_14_15`, schema `15.json`). |

**Server — `SEQ_REUSED`** (`SyncPushService`, V598 index): for a protocol-2 device, an op whose `deviceSeq` was already
used by another opId of the same device (in the applied-op ledger or the dead letters) is refused 409 `SEQ_REUSED`,
dead-lettered, never applied, not retryable. Checked after the ledger, so a retry of the same opId still replays.
Protocol-1 devices are exempt. The web client already never reuses a sequence (persistent counter).

**Web/Android parity:** `contract-tests/sync-protocol/parity/stream.json` is one logical op stream (shift open, cash
sale, customer, credit-customer sale, pay-in, close). Each client's own unit test turns it into that client's real
wire batch and keeps a golden file current — `android.json` (`SyncParityGoldenTest`, through Room + `SyncRepository`)
and `web.json` (`parity.test.ts`, through `SyncStore` + `SyncEngine`). `SyncClientParityIT` pushes both from two devices
and asserts identical invoices, payments, GL by account, shift totals, cash movements and customer. (The goldens
differ, for example `occurredAt` precision: Android sends seconds, the web milliseconds.)

**O6 as built — deviations to approve:**
1. **No new ops or scopes on the Android till.** The O4 warehouse and O5 field-sales flows are web pages that work
   offline in a browser registered as a device (including Chrome on Android tablets). The till keeps its POS op set;
   Room v15 carries only the protocol-2 fields. Building native warehouse / field-sales screens is a separate phase.
2. **No snapshot protocol on Android yet** (C5). The first sync remains a cursor-less full pull (still served,
   protocol unchanged); paged, checksummed snapshot staging stays web-only for now.
3. **The parity test covers the protocol layer**, not each client's screen code: both clients wrap the same
   payloads. The payload builders are covered by each client's own tests.
4. **Behaviour changes on the till**: PIN lockout now starts after 5 failures for 5 minutes (org policy) instead of
   15 s after 3; a signed-in cashier is signed out after 30 idle minutes (org policy; 0 disables); sales stop after
   72 h without a sync (org policy).
5. **`SEQ_REUSED` applies to the web client too** (it declares protocol 2). It never reuses a sequence, so nothing
   changes for it, but an IndexedDB restored from an old copy would be refused rather than applied twice.

**Tests:** Android `SyncRepositoryTest` (S1 retry/backoff/order, parking + manual retry, resubmit as a correction,
legacy re-keyed op, restore renumbering, token expiry without wipe, 426, rotation + policy, tombstones),
`AuthRepositoryTest` (policy lockout, idle timeout), `OfflineWindowTest`, `SyncParityGoldenTest`; all Android unit
tests pass (`./gradlew :app:testDebugUnitTest`). `MigrationTest` gained a 14→15 case (on-device; not run here).
Backend `SyncSeqReuseIT` (3), `SyncClientParityIT`, the full `*Sync*` suite. Web `parity.test.ts`, full Vitest.

**Not done in O6:** running the app on a phone against DEMO; Android snapshot staging; native O4/O5 screens; the
on-device `MigrationTest` (its dependencies are not in this machine's offline Gradle cache).

### O7 as built (branch `phase/O7-branch-server`) — branch server as a relay node

Owner decision (2026-11-21): a branch server is a **relay node**, not a full ERP with GL switched off. It is the same
application started with `erp.sync.mode=branch` on its own PostgreSQL; no business module changed. HQ stays the only
place that applies ops and posts GL (invariant 2).

```
till / browser ──LAN──▶ BRANCH (erp.sync.mode=branch)             HQ
                         mirror of HQ pull scopes  ◀── /sync/pull ──
                         relay outbox (accepted_local)
                         /sync/push answered after commit ── /sync/push (original opIds) ──▶ ledger dedup, GL
                         outbox states, devices, watermark ◀── /sync/relay/feed ──
```

| Part | As built |
|---|---|
| Configuration | `erp.sync.mode=branch`, `erp.sync.server-id` (distinct from `hq`), `erp.sync.branch.hq-url`, `erp.sync.branch.token`, `erp.sync.branch.organization-id`; `app.jobs.enabled=false`. Startup fails if one is missing. |
| Branch identity at HQ | A device of platform `branch`, bound to its branch (`POST /sync/devices/register` with `platform=branch` requires `branchId`). Its token is a server credential: **not rotated on pull** (expiry renewed), so a restored branch still authenticates; revoke it like any device. |
| Tills | Registered at HQ as usual, bound to the branch. The branch mirrors them (token hashes, expiry, revocation, offline-window override) from the feed and authenticates them locally with the existing filter. **No token rotation through a branch**; HQ renews the expiry of tills the branch reports as seen. A till HQ no longer lists for the branch stops authenticating there. |
| Master data (S6) | `SyncBranchMirror` holds every pull scope except messages; a pull at the branch is answered from it with the normal envelope (full sets whole, deltas by storage time with the 5 s overlap, tombstones to protocol-2 clients), HQ's `currency`, `offlinePolicy` and `license`. Snapshots (C5) work on the mirror unchanged. Messages are not relayed. |
| Push at the branch | Every op is committed to `SyncRelayOutbox` (`accepted_local`) before the till is answered; same opId + same payload replays, different payload `OP_ID_REUSED`. The branch then relays at once: **when HQ answers, the till gets HQ's own result** (mapping, final numbers, rejection); **when HQ is unreachable**, `status: applied`, `relayState: accepted_local`, no mapping — the branch now owns delivery. |
| Relay (C3) | `SyncBranchJob` (ShedLock, `erp.sync.branch.interval`, default 10 s): feed → pending ops → mirror pull (`pull-interval`, 30 s). Ops go in acceptance order under their original opIds; `deviceSeq` = the branch's own relay sequence; `originChain` = `device:<till>`, `server:<branch>` (HQ adds its hops). States: `accepted_local` → `pending_hq` → `hq_posted` (HQ result stored) / `hq_rejected` (HQ dead letter; its later status comes back by the feed). Retryable HQ failures and an unreachable HQ back off 2 s → 5 min; once HQ answers again, ops that never reached it go at once. |
| HQ feed | `GET /sync/relay/feed` (branch devices only, else 403): ledger rows and dead letters of the branch device changed since the cursor (outcomes survive lost responses), `maxDeviceSeq` (relay watermark), the branch's tills. |
| HQ checks on relayed ops | Each op must name a till of the same branch (`ORIGIN_NOT_ALLOWED` otherwise, dead letter). A `hold:<code>` hop the branch adds is dead-lettered under that code: `DEVICE_REVOKED` / `DEVICE_TOKEN_EXPIRED` (the branch received the op after the till's cut-off — the branch's receive time decides, as HQ's does for direct pushes) and `SEQ_REUSED` (a protocol-2 till reused a sequence, detected at the branch). The till is told the same failure at once. |
| Branch shift close (C3) | Allowed while ops are pending HQ; refused at the branch (`SYNC_DEAD_LETTERS_OPEN`, the HQ wording) while a money op of that shift is `hq_rejected` with its HQ dead letter still open or investigating. HQ's own close check applies again when the close arrives. |
| Branch reports (C3) | `GET /sync/branch/status` (any till of the branch): outbox counts, pending HQ posting, open rejections, last HQ contact and error. `GET /sync/branch/shift-report?cashSessionId=<client id>`: opening float, cash sales, pay-ins/outs, expected cash, "Includes N operation(s) pending HQ posting". GL and P&L stay HQ-only. |
| Back office at a branch | Every route except the sync protocol, `/sync/branch/*` and health answers 503 ("use HQ for back-office work"). |
| Crash / restore (S8) | Tills keep queuing while the branch is down. A branch restored from an older backup reads HQ's feed before sending anything: it recovers outcomes and HQ's relay watermark; rows never sent from the restored database get sequences above the watermark (no `SEQ_REUSED`); tills re-push unacknowledged ops and HQ's ledger replays the ones it already applied (no double posting). |
| Cross-branch transfers | Unchanged S4 two-step ops (`transfer.dispatch` / `transfer.receive`), relayed to HQ like any op; HQ coordinates. |
| Migration | `V599__sync_o7_branch_relay.sql`: `SyncRelayOutbox`, `SyncBranchMirror`, `SyncBranchState` (RLS like the other sync tables; empty at HQ) and a ledger index for the feed. |

**O7 as built — deviations to approve:**
1. **Relay node, not a full branch ERP** (owner decision above): branch users do back-office work at HQ; a branch
   offers tills (Android and browser devices) only.
2. **Final numbers while HQ is down:** a sale relayed later keeps its provisional number on the till; the final
   number and invoice id exist at HQ (and in the branch outbox) but are not pushed back to the till.
3. **Tokens through a branch are not rotated.** HQ renews their expiry while the branch reports the till as seen; a
   till idle past the token TTL needs re-enrolling. Rotation stays in force for tills that pull from HQ directly.
4. **Messages** (staff chat) are not relayed through a branch.
5. **Branch-wide close block at HQ:** HQ's existing close check matches open money dead letters by the shift's
   device, which for relayed shifts is the branch device. A rejected money op from one till therefore blocks closing
   any shift of that branch at HQ until it is resolved (safe, stricter than per till). The branch's own check is per
   shift.
6. **Not built:** S9 branch dashboard tiles. (The C2 reconciliation job and the docker-compose HQ + branch stack
   followed on the same branch; see "C2 reconciliation and the compose stack, as built".)

**Tests:** `SyncBranchRelayIT` (a second application context on its own database as the branch, HQ = the test
server): mirror served to the till without rotation; inline pass-through with HQ's result and originChain; HQ
unreachable → `accepted_local`, pending counts and shift report, relayed on reconnect with exactly one posting; HQ
rejection blocking the branch close until the dead letter is discarded at HQ, then the close posts with
`closingExpected` 190; restore from an empty backup (watermark, no `SEQ_REUSED`, replay not re-post); a till revoked
at HQ held as `DEVICE_REVOKED`; boundaries (feed 403/401, branch registration needs `branchId`, `ORIGIN_NOT_ALLOWED`,
503 for back office, branch routes 404 at HQ). Plus the full `*Sync*` suite, cash sessions, tenant RLS, schema
validation, modularity and architecture tests.

### C2 reconciliation and the compose stack, as built (branch `phase/O7-branch-server`)

**Post-restore reconciliation** (`SyncReconciliationService`, `SyncReconciliationJob`, V600 `SyncReconciliationRun`).
Read-only; runs a few minutes after every start (a restore means a restart), then daily
(`erp.sync.reconciliation.initial-delay` PT3M, `erp.sync.reconciliation.interval` P1D; with `app.jobs.enabled`, never on
a branch node), and on demand. Checks, per organisation:

| Key | Issue when |
|---|---|
| `watermarks` | a device or branch node's `lastPushSeq` is ahead of the highest sequence the ledger and dead letters hold for it (acknowledged ops the restore lost that have not come back). Devices with no ledger row at all (pre-O0 history) are not compared. |
| `committedOps` | a ledger row is still `committed` 5 minutes after it was written |
| `unbalancedJournals` | a posted or reversed journal's base debits ≠ base credits (> 0.005). The V513 trigger prevents this for new writes; a restore of older data or corruption can still show it. |
| `receivables`, `payables` | the reporting engine's `ar.reconciliation` / `ap.reconciliation` checks fail (subledger total vs control accounts, every partner ties). Called through the new `reporting.spi.SubledgerReconciliation`; recorded as `VERIFY` report runs. |
| `stock` | `StockItem` on-hand ≠ the sum of `InventoryLedger` movements per product / variant / location |
| `deadLetters` | information only: open dead letters by failure code |

Each run is stored with its report (status `clean` / `issues` / `failed`). A run whose issues differ from the previous
run's sends an in-app alert (category `sync`) to up to 20 users with `organization:update`. Routes (Extension):
`POST /sync/reconciliation/run` (`organization:update`), `GET /sync/reconciliation/runs` and `/runs/{id}`
(`organization:read`). No web screen yet: the alert points to the API and the report JSON.

**Compose HQ + branch** (`docker-compose.yml` profile `branch`, `infra/docker/branch/`): `branch-db` (own Postgres,
host port 5435), `branch-enroll` (one-shot: logs in to HQ, finds the branch by `BRANCH_CODE`, registers a `branch`
device, keeps the token in the `branchenroll` volume; needs `apk add curl jq`, so internet on first start) and
`branch-api` (same image, `erp.sync.mode=branch`, jobs off, port `BRANCH_PORT` 3002 on all interfaces for the LAN).
HQ is this file's `api` by default; `BRANCH_HQ_URL` points a branch machine at a remote HQ.
`infra/docker/branch/drill.sh` runs the O7 drill on that stack: trade online, back up the branch database, stop HQ and
trade, restart HQ and drain, restore the branch backup, re-push a lost op and a new one, then run the reconciliation and
count HQ invoices (expected 4: every sale once).

**Not verified here:** the compose stack was validated (`docker compose --profile branch config`) but not started, and
the drill was not run (both need an image build). Browser devices at a branch would need a web container proxying to
`branch-api`; not included (Android tills connect to the branch API directly).

**Tests:** `SyncReconciliationIT` (plants a device watermark ahead of the ledger, a stale committed ledger row, an
unbalanced posted journal written with the triggers off as the schema owner, and on-hand with no movements; the run
reports each, alerts once, does not alert again for the same issues, reports nothing after cleanup, is read-only,
404 for another organisation, 403 without permission). The `SyncIT` authorization matrix now covers 25 routes.

## Additive wire changes in O0

| Where | Change |
|---|---|
| `POST /sync/push` result | `code`, `retryable` on failed ops; optional request fields `originChain[]`, `supersedesOpId` per op; `Idempotency-Key` honoured |
| `GET /sync/pull` | `protocolVersion`, `serverId`, `offlinePolicy`, `deviceTokenExpiresAt`, `deviceBranchId`; `deviceToken` on protocol 2; tombstones on protocol 2; 426 for unsupported versions; 401 for an expired token |
| `GET /sync/devices` | `serverId`, `tokenExpiresAt`, `protocolVersion`, `lastPullAt`, `offlineWindowHours` |
| `GET /sync/dead-letters` | `failureCode`, `retryable`, `attempts`, `lastAttemptAt`, `branchId`, `resolvedByOpId`, `investigationNote`, `investigatingAt`, `receivedAt`, `legacy`, `recoverable`, `recoveredById`, `recoveredAt`, `recoveryGapAcknowledged` |
| `POST /cash-sessions/close`, `/{id}/force-close` | optional `syncOverrideReason`; new 409 `SYNC_DEAD_LETTERS_OPEN` (behaviour change, S5) |
| `GET /sync/dead-letters/counts` | `investigating`, `retried` |
| New routes | `POST /sync/devices/{id}/policy`; `POST /sync/dead-letters/{id}/investigate`; `POST /sync/dead-letters/{id}/retry`; `POST /sync/dead-letters/{id}/recover`; `POST /sync/snapshot`; `GET /sync/snapshot/{id}/page`; `POST /sync/snapshot/{id}/complete` |
| Config | `erp.sync.server-id` (default `hq`) |

## Consequences

- Android keeps working unchanged: the new members are ignored (`ignoreUnknownKeys`), and protocol-1 behaviour is
  preserved, with no rotation and no tombstones.
- A device offline for longer than the token TTL must be re-registered before it can pull again. Its queued ops can
  still be pushed, but they are held for supervisor recovery (S2), never applied on the device's word.
- Rejected money ops now block a shift close (S5) unless a supervisor overrides with a reason.

## Contract tests

The protocol-2 extensions have no Nest counterpart, so the Nest parity harness cannot cover them. The wire contract
lives in `contract-tests/sync-protocol/contract.json` (shapes, failure codes, protocol header and versions) and is
checked against the live API by `SyncProtocolContractIT` on every CI run.

## Deferred work (tracked; not in O0)

| Item | Where it lands |
|---|---|
| S9 `/sync/status` dashboard and Micrometer metrics | after O2 (needs the web engine's pending counts) |
| C2 post-restore reconciliation job (device/branch watermarks vs ledger, GL and stock checks, report) | built after O7 (see "C2 reconciliation and the compose stack, as built") |
| S9 branch tiles on the sync dashboard | with S9 (the branch exposes `GET /sync/branch/status` meanwhile) |
| Web and Android clients (snapshot staging, token rotation, `supersedesOpId`, quarantine UX) | O1, O2, O6 |
| Refusing sequence regressions from active devices | done in O6 as `SEQ_REUSED` for protocol-2 clients (sequence reuse; a regression alone stays legitimate) |
