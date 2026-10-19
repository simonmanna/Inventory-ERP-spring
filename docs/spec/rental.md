<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Rental — `rental`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/rental` |
| Routes | 47 — full table: [generated/rental.md](generated/rental.md) |
| Feature flag | `ENABLE_RENTAL` |
| Java package | `com.inventoryerp.rental` |

## Purpose

Rental catalog, rates, packages, units, availability, reservations, agreements (checkout, deposits collect/refund/forfeit), extensions, swaps, returns (inspect, settle), damages, service orders, customer scores, reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~47 routes | see generated table |

## Business rules to reproduce exactly

1. Availability excludes overlapping reservations/agreements per unit.
2. Deposit ledger movements post to GL (rental accounts backfill).

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

rental-housekeeping 1 min.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/rental-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
