# ADR-S083 — Offline / online hybrid (one sync protocol for web, Android and branch servers)

- Status: accepted by the owner (2026-11-20, plan revision 3). **O0 is built** on branch `phase/O0-sync-foundations`.
  O1–O7 are not started; each needs separate owner approval.
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

Active devices are not refused for sequence regressions (current Android re-sends a rejected op under a new id with
its old sequence); `lastAcceptedSeq` is recorded for evidence only until O6 aligns Android.

### Browser storage (O1)

The token and PIN hashes are encrypted with a non-extractable WebCrypto key. Script running on our own origin can
still *use* that key. The mitigations are the strict CSP, no third-party script on POS routes, and the short offline
window.

PINs and tokens are never logged (the existing redaction rules apply).

## S3 — Cache policy (O3)

- An explicit allowlist (`web/src/lib/offline/cache-policy.ts`). Every query-key family is classified:
  - `cache`: catalogue
  - `cache-permissioned`: customers, suppliers, stock snapshots, invoices
  - `cache-strict`: financial reports, role-gated, 24 h retention
  - `never`: auth, licence, secrets, password reset, tokens
- An unclassified key defaults to `never`. A unit test fails if a GET hook uses an unclassified key.
- Keys carry org and user. The cache is wiped on logout, org switch, a permission-snapshot change and device revoke.
- Size cap with LRU eviction.

## S4 — Inventory conflict and reconciliation (O4)

- A physical fact that happened offline is always kept (`recordedOffline`, `occurredAt`).
- **Negative stock allowed:** post, and raise a `stock_discrepancy`.
- **Negative stock disallowed:** keep the document and hold the posting (the ADR-S072 pattern) for a supervisor.
- **Transfers:** two steps, `transfer.dispatch` → transit location → `transfer.receive`. A receive whose dispatch
  hasn't synced yet waits in `awaiting_dispatch`.
- **Counts:** variance = counted − server on-hand as at `occurredAt`.
- **Count conflicts:** a count against an open draft is a definitive dead letter (`COUNT_CONFLICT`, built in O0).
- **Reports and blocks:** an inventory discrepancy report. Open stock dead letters block period close for the location.

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

## S6 / C3 — Branch identity, authority, relay contract (contract only; built in O7)

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
   - The checksum is computed by the server over the page's rows. The client keeps it as the page's identity and
     echoes it back.
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
| C2 post-restore reconciliation job (device/branch watermarks vs ledger, GL and stock checks, report) | before O7; runbook already in `docs/OPERATIONS.md` §7c |
| Web and Android clients (snapshot staging, token rotation, `supersedesOpId`, quarantine UX) | O1, O2, O6 |
| Refusing sequence regressions from active devices | O6 (after Android stops re-keying) |
