# Offline sync — `sync`

| | |
|---|---|
| Phase | P6 |
| Nest source | `reference/nest-api/src/modules/sync` |
| Routes | 8 — full table: [generated/sync.md](generated/sync.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.sync` |

## Purpose

Device registration and revocation, incremental pull of catalog/state per scope, batched push of offline operations with per-op idempotency, dead letters.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| devices | register, list, revoke |
| pull | `GET /sync/pull?cursor=&scopes=` |
| push | `POST /sync/push` |
| dead letters | list, counts, resolve |

## Business rules to reproduce exactly

1. Authority: the LAN Postgres is the source of truth; "a sale must never be blocked" — ambiguous cases apply the sale and flag the discrepancy.
2. Pull: opaque base64 JSON cursor with one server-clock watermark per scope; returns rows with `updatedAt > watermark − 5 s`; tombstones via `deletedAt`. Delta scopes: menuItems (aggregate), menuCategories, modifierGroups, taxes, posTables, cashRegisters, staff (incl. bcrypt pinHash), settings, products, productCategories, productPackagings, partners, reservations, stockLevels, suppliers. Full every time: paymentMethods, ledgerAccounts (with roles + balances), expenseCategories, stockLocations.
3. Push: ops strictly increasing `deviceSeq`; each op runs through `IdempotencyService.executeWithKey(opId)` with the actor's current permissions and calls the same services as the online endpoints; result per op `{ opId, status, httpStatus, error, mapping, finalNumbers }`; client-minted ids mapped to server ids; dependent op whose dependency failed → 424; `provisionalNumber` (e.g. `D1-000042`) replaced by final numbers; failures → `SyncOpDeadLetter`; open dead letters block cash-session close; device `lastPushSeq` tracked.
4. Op types: `SYNC_OP_TYPES` in the Nest source (cash_session.open/close/movement, sale.checkout, tab.settle, sale.refund, sale.void, reservation.*, customer/setting/menu*/modifierGroup/accompanimentGroup/tax/product/productCategory/cashRegister/posTable upsert|delete, staff.pinChange, message.send/markRead, stock.in/out/count, expense.create).

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Notes and pitfalls

- Spec: `docs/legacy/sync-protocol.md`. Android client in the legacy repo `apps/android` uses Retrofit against these exact shapes.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/sync-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

- Post-parity hardening (owner-approved 2026-11-10): a dead-lettered push operation is stored with `managerPin`,
  `incomingPin`, `overridePin`, `pin`, `password` and `approvalToken` replaced by `[redacted]` (Nest stored the raw
  payload). `cash_session.open` replays pass no shortage approval, so an offline open that would remove cash from the
  drawer is not auto-adjusted. See `docs/spec/pos.md` "Post-parity hardening".

- **Phase I6 (Android POS integrity), additive wire changes:** pull gains the `productVariants` scope (id,
  productId, sku, barcode, name, effective `price` = `ProductVariantService.PRICE_SQL`, isActive, `deletedAt`
  tombstones from the variant or its product), `tax.isInclusive` on product rows, and a top-level
  `currency: {code, decimalPlaces}` (null when the organisation has none). Push gains the `kitchen.void` op
  (reason + manager approval for the `void` override kind, PIN re-verified; wastes the menu item's recipe / the product
  at the shift's register location via `KitchenWaste.recordDeviceVoid`, ledger-deduplicated per op line; audit
  `PosDevice cancel item_void`). `sale.refund` lines may name a sale line by `lineNumber` (1-based position) instead of
  the invoice-item id. Older devices are unaffected. Tests `SyncDeviceIntegrityIT`.

- **LP2b, additive wire change:** push gains the `cash_drawer.open` op (payload `{ reason, sessionId?,
  cashRegisterId?, note?, referenceType?, referenceId? }`, `occurredAt` from the op). It runs
  `PosCashDrawerService.record` as the actor, exactly like `POST /pos/cash-drawer/openings`; `sessionId` may be the
  client id of a `cash_session.open` in the same batch, and a failed open fails it with 424. The Android "No sale"
  action queues it with reason `no_sale`. Older devices are unaffected. Test `PosCashDrawerIT#syncPush`.

- **`stock.count` is one transaction (Phase I5, audit S-5).** Opening the spot count, entering the device's figures and
  submitting commit together; a refused count (e.g. "Choose a variant for …") leaves no draft count open. Test
  `SyncIT.stockCountPerVariant`.

## Extensions (ADR-S083 O0, owner-approved 2026-11-20)

Spring-only, additive. Every Nest route and member is unchanged. Full rules are in
`docs/adr/ADR-S083-offline-hybrid.md`; migration `V593__sync_o0_foundations.sql`.

- **Applied-op ledger** `SyncAppliedOp` (permanent, unique per org + opId), anchored to each op's first business
  commit (`SyncLedger`).
  - Replays survive the loss of idempotency records.
  - The same opId with a different payload → `OP_ID_REUSED`.
  - A failed attempt that committed nothing frees its key, so the same opId can run again.
- **Push results** carry `code` and `retryable` on failed ops.
  - Ops may send `originChain` and `supersedesOpId`.
  - A batch `Idempotency-Key` is honoured.
  - A `stock.count` 409 is `COUNT_CONFLICT`, definitive.
- **Dead-letter workflow** `open → investigating → resolved | retried | discarded`.
  - New routes `POST /sync/dead-letters/{id}/investigate` and `/retry` (POS__OVERRIDE).
  - Additive list and count members.
- **Device lifecycle.**
  - Token expiry (`sync.deviceTokenTtlDays`, default 30): an expired token cannot pull.
  - Protocol-2 rotation on pull, with a 24 h grace for the previous token.
  - Revoked or expired devices may still push ops recorded before the cut-off.
  - `POST /sync/devices/{id}/policy` lowers the offline window per device.
- **Pull** adds `protocolVersion`, `serverId`, `offlinePolicy`, `deviceTokenExpiresAt`, `deviceBranchId`, and
  `deviceToken` (protocol 2 only).
  - `X-Sync-Protocol` is checked: an unsupported version gets 426 on pull, while push always accepts.
  - Protocol 2 deltas include tombstones for soft-deleted rows. This supersedes the "kept on purpose" note below for
    protocol-2 clients only.
- **Snapshots:** `POST /sync/snapshot`, `GET /sync/snapshot/{id}/page`, `POST /sync/snapshot/{id}/complete`. Paged,
  checksummed, resumable, and expiring after 1 h. Completion returns the delta cursor.
- **Menu-child triggers** bump `MenuItem.updatedAt` when its variants, modifier or accompaniment links, modifiers or
  options change.
- Tests: `SyncLedgerIT`, `SyncO0IT`; the `SyncIT` authorization matrix now covers 14 routes.

## Notes
## Notes

- Push ops run in a tenant context built from the verified actor (current role permissions) and through
  `IdempotencyService.executeWithKey(opId)`, calling the same Java services as the online routes.
- `product.upsert` hands `ProductService` a record built from the payload; `EntityPatch.withPresentKeys` makes the
  explicit nulls Nest sends (`sku: payload.sku ?? null`) clear columns exactly as Prisma would.
- Like Nest, delta scopes read through the tenancy predicate, so soft-deleted rows of `SOFT_DELETE` models never reach
  a device (only `staff` reads raw and ships tombstones). Kept on purpose.

## Status

8/8 Nest routes + 6 ADR-S083 extension routes. `SyncController` + `SyncDevicesService`, `SyncPullService`, `SyncPushService`, `SyncDeadLetterService`
(`com.inventoryerp.sync.application`). Device-token transport auth reuses the kernel `AuthenticationFilter`
(`X-Device-Token` → sha256 lookup); the guard (401) runs before push-body validation, as in Nest.
Tests: `SyncIT` (8 — registry, revoke, pull scopes + cursor + staff revocation (`sync-staff-revocation.spec.ts`),
push shift/sale/close with provisional → final numbers, replay, 424 dependency, dead letters, master data, stock ops
with approver PIN, push validation, authz matrix) and `SyncInventoryReplayIT` (5 — `pos-offline-inventory-replay.spec.ts`).
`sync-cash-inventory.spec.ts` is covered by both against the shared DEMO org.
