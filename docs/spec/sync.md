<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
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

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/sync-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
