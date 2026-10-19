<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Core master data — `core`

| | |
|---|---|
| Phase | P2 |
| Nest source | `reference/nest-api/src/modules/core` |
| Routes | 81 — full table: [generated/core.md](generated/core.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.core` |

## Purpose

Organizations, branches, partners (customers/suppliers/employees) with contacts and addresses, products (variants, packaging, UoM conversions, barcodes), product categories, taxes, units of measure, fiscal periods/positions, payment terms.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| organizations | bootstrap (create org + admin), accept-invite, me, me/settings, me/branch, users, invite, deactivate |
| branches | CRUD; user default branch |
| partners | CRUD + categories, contacts, addresses; flags isCustomer/isSupplier/isEmployee; credit limit/hold; opening balance; receivable/payable account overrides; membership tiers |
| products | CRUD, `search`, `resolve-barcode`, restore; variants, packaging, UoM conversions, brand, costing method, tax, reorder levels |
| uom | `/uoms` CRUD + `POST convert`; `/uom-categories` CRUD; conversion factors exact (BigDecimal) |
| taxes | CRUD; inclusive/exclusive, rate, accounts |
| fiscal | `/fiscal-periods` CRUD (core wins over accounting duplicate, see CONTRACT.md §1); `/fiscal-positions` CRUD + deleted + restore; `/payment-terms` CRUD + deleted + restore |

## Business rules to reproduce exactly

1. Codes (partner/product) unique per organization; error messages verbatim from services.
2. Product delete is soft and blocked when stock or open documents reference it (check service).
3. Barcode resolution order: product barcode → variant barcode → packaging barcode (`resolve-barcode`).
4. Partner credit: `creditHold` blocks credit sales in POS (fail closed); `creditLimit` checked against open AR.
5. Emits `partner.created/updated/deleted`, `product.created/updated/deleted`.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`partner.*`, `product.*` (consumed by sync pull watermarks, CRM, search).

## Scheduled jobs

—

## Notes and pitfalls

- `organizations/bootstrap` is public in some flows — check `@Public()` in the route table.
- UoM conversion rounding uses 6 dp (`qtyMul`).

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/core-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
