<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Orders (generic sales orders) — `orders`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/orders` |
| Routes | 11 — full table: [generated/orders.md](generated/orders.md) |
| Feature flag | `ENABLE_ORDERS` |
| Java package | `com.inventoryerp.orders` |

## Purpose

Non-POS sales orders: settings, CRUD, items, cancel, reopen, invoice.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~11 routes | see generated table |

## Business rules to reproduce exactly

1. Shares Order/OrderItem tables with POS; reuse pos/invoicing services.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/orders-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
