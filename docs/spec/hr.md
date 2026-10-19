<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# HR and payroll — `hr`

| | |
|---|---|
| Phase | P7 |
| Nest source | `reference/nest-api/src/modules/hr` |
| Routes | 140 — full table: [generated/hr.md](generated/hr.md) |
| Feature flag | `ENABLE_HR` |
| Java package | `com.inventoryerp.hr` |

## Purpose

Departments, positions, employees (lifecycle, documents, transfers, PIN/user link), shifts and assignments, attendance and logs, timesheets, leave types/balances/requests, holidays, payroll (components, tax tables/brackets, periods, runs, items, payslips, bank payments), salary advances, loans, performance reviews, training, self-service and team views, reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| ~140 routes | see generated table |

## Business rules to reproduce exactly

1. Payroll calculation: gross → allowances → deductions → tax by bracket → net; rounding as Nest; run states and GL posting (HR accounts backfill script exists).
2. Leave balance accrual and request approval.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

—

## Notes and pitfalls

- Split into PRs: org structure → employees → time (shifts/attendance/timesheets) → leave → payroll → loans/advances → reviews/training → self-service → reports.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/hr-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
