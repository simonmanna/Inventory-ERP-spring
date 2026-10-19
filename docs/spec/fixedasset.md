<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Fixed assets — `fixedasset`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/fixed-asset` |
| Routes | 54 — full table: [generated/fixedasset.md](generated/fixedasset.md) |
| Feature flag | `ENABLE_ASSETS` |
| Java package | `com.inventoryerp.fixedasset` |

## Purpose

Asset register with categories, acquisition, assignment, check-in/out, depreciation runs and posting, disposal, inspections, insurance, maintenance, repairs, revaluations, transfers, warranties, dashboard.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| assets | CRUD, search, by-code, by-barcode, restore |
| sub-resources | `/fixed-assets/:assetId/*` (12 kinds) |
| dashboard | `/fixed-assets-dashboard/*` |
| categories | `/asset-categories` |

## Business rules to reproduce exactly

1. Depreciation methods and GL posting exactly as the Nest depreciation service; posted runs immutable.
2. Disposal posts gain/loss.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Notes and pitfalls

- No Nest specs — write tests from the service logic.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/fixedasset-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
