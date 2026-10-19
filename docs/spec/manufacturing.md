<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Manufacturing — `manufacturing`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/manufacturing` |
| Routes | 42 — full table: [generated/manufacturing.md](generated/manufacturing.md) |
| Feature flag | `ENABLE_MANUFACTURING` |
| Java package | `com.inventoryerp.manufacturing` |

## Purpose

BOMs, production requests/plans, production orders (confirm, start, complete, QC, cancel, reverse), material consumption and outputs, cost components, work centers, resources, routings, work orders, MRP, reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~42 routes | see generated table |

## Business rules to reproduce exactly

1. Completing a production order consumes materials (production_consume) and receives outputs (production_output) through StockService with cost roll-up; reverse undoes both.
2. WIP account handling (backfill-wip-account script).

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
- [ ] Parity scenarios `contract-tests/scenarios/manufacturing-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
