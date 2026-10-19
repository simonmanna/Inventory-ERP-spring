<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Repair — `repair`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/repair` |
| Routes | 64 — full table: [generated/repair.md](generated/repair.md) |
| Feature flag | `ENABLE_REPAIR` |
| Java package | `com.inventoryerp.repair` |

## Purpose

Repair orders and items, diagnosis, quotations, jobs, parts, labour types, technicians, schedules, warranties and claims, service contracts, attachments, notes, billing, dashboard, reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~64 routes | see generated table |

## Business rules to reproduce exactly

1. Status history recorded on every transition (`RepairStatusHistory`).
2. Billing creates an invoice through invoicing services.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

repair-housekeeping 10 min.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/repair-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
