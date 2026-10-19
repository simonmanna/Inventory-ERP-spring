<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Expenses — `expenses`

| | |
|---|---|
| Phase | P3b |
| Nest source | `reference/nest-api/src/modules/expenses` |
| Routes | 18 — full table: [generated/expenses.md](generated/expenses.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.expenses` |

## Purpose

Expense categories and expenses with approval, payment (from cash drawer, bank, or petty cash), void, audit trail and stats.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| categories | CRUD |
| expenses | CRUD, stats, meta/accounts, meta/suppliers, audit, approve, reject, pay (idempotent), void (idempotent) |

## Business rules to reproduce exactly

1. Status: draft → pending approval → approved → paid; rejected; void reverses GL and cash.
2. Paying from a cash drawer creates a cash movement in the open session and cannot overdraw.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`expense.*`

## Scheduled jobs

—

## Notes and pitfalls

- Web uses `web/src/lib/api/*` for expenses.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/expenses-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
