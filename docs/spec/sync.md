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

## Notes

- Push ops run in a tenant context built from the verified actor (current role permissions) and through
  `IdempotencyService.executeWithKey(opId)`, calling the same Java services as the online routes.
- `product.upsert` hands `ProductService` a record built from the payload; `EntityPatch.withPresentKeys` makes the
  explicit nulls Nest sends (`sku: payload.sku ?? null`) clear columns exactly as Prisma would.
- Like Nest, delta scopes read through the tenancy predicate, so soft-deleted rows of `SOFT_DELETE` models never reach
  a device (only `staff` reads raw and ships tombstones). Kept on purpose.

## Status

8/8 routes. `SyncController` + `SyncDevicesService`, `SyncPullService`, `SyncPushService`, `SyncDeadLetterService`
(`com.inventoryerp.sync.application`). Device-token transport auth reuses the kernel `AuthenticationFilter`
(`X-Device-Token` → sha256 lookup); the guard (401) runs before push-body validation, as in Nest.
Tests: `SyncIT` (8 — registry, revoke, pull scopes + cursor + staff revocation (`sync-staff-revocation.spec.ts`),
push shift/sale/close with provisional → final numbers, replay, 424 dependency, dead letters, master data, stock ops
with approver PIN, push validation, authz matrix) and `SyncInventoryReplayIT` (5 — `pos-offline-inventory-replay.spec.ts`).
`sync-cash-inventory.spec.ts` is covered by both against the shared DEMO org.
