# Beverage (bar control) — `beverage`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/beverage` |
| Routes | 10 — full table: [generated/beverage.md](generated/beverage.md) |
| Feature flag | `ENABLE_BEVERAGE` |
| Java package | `com.inventoryerp.beverage` |

## Purpose

Bottle weigh-count sessions and bar variance, yield, bartender and dashboard reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| counts | `/beverage/counts` |
| reports | variance, yield, bartender, dashboard |

## Business rules to reproduce exactly

1. Bottle math in `kernel/common/beverage-math.ts` (weight → volume via measurements) — port with its tests.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/beverage-*.json` — zero unexplained diffs (scenario written; not yet run against Nest)
- [ ] Web screens for this module work against Spring (no console errors) (not yet smoke-tested)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Any deviation from Nest behaviour must be listed here with the owner's approval.

| # | Change | Status | Detail |
|---|---|---|---|
| 1 | `BottleCountLine.reasonCode` variance cause codes (draft input, line + variance-report output) | approved by owner 2026-10-26 | [ADR-S004](../adr/ADR-S004-beverage-controls.md), `V6__beverage_controls.sql` |
| 2 | Opt-in unweighed-bottle exception on submit (`beverage.requireAllBottlesWeighed`, `unweighedReason` + manager approval; `unweighedReason`/`unweighedCount` on the session) | approved by owner 2026-10-26 | [ADR-S004](../adr/ADR-S004-beverage-controls.md) |
| 3 | Per-product double shot `Product.doublePourMl` (null = 2 × single); yield report adds `doublePourMl`, `doublesPerBottle` | approved by owner 2026-10-26 | [ADR-S004](../adr/ADR-S004-beverage-controls.md) §3, `V7__beverage_double_pour.sql` |

## Status

Ported (10/10 routes). Controllers load only with `ENABLE_BEVERAGE=true` and are gated per tenant by
`@RequiresModule("beverage")`. Tests: `BeverageIT`, `BeverageCountMathTest`, `CommonMathTest$Beverage`.
Parity: `contract-tests/scenarios/beverage-control.json`.
