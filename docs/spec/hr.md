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

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/hr-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Same as Nest, kept on purpose (owner may want these fixed after parity)

1. `POST /hr/leave/balances/adjust` adds the delta to the stored Decimal by **string concatenation** (`Decimal + number`
   in JS): `0` + `2` stores `2`, but `2.5` + `1` stores `2.51`, and a negative delta (`"0-2"`) is a 500.
2. `HrPayrollItem.overtimeHours` is an `Int` column but receives `round(h·100)/100`; any non-whole overtime makes
   `POST /hr/payroll/runs/:id/calculate` fail with a 500 (Prisma rejects the float).
3. `GET /hr/attendance/summary` starts its window **30 days before** `from`; `GET /hr/reports/attendance-register`
   starts **6 days before** `from`.
4. `POST /hr/reviews` without `employeeId` answers **404** `employeeId is required`.
5. `GET /hr/team/today` uses the server-local (UTC) day, not the organisation's attendance day.
6. Payroll `date: { gte: start, lt: end }` excludes attendance on the period's last day.

## Notes

- Untyped bodies (`dto: any`) are taken as `Map` and never validated (Nest skips `any` metatypes); only the identity /
  lifecycle DTOs are typed.
- Shared helper `HrDb` mirrors the Prisma tenancy extension: org + soft-delete scope from the entity's Hibernate filters
  (the generated `SOFT_DELETE` / `ORG_SCOPED` sets), unfiltered includes, `_count`, JS `Number()` / `take` / `skip`.
- `HrPosAttendanceSubscriber` listens for `pos.pin.login` / `pos.pin.logoff` (bean only with `ENABLE_HR=true`).

## Track C additions (not in Nest)

### HR-1 recruitment and onboarding (built, unverified)

New controller `HrRecruitmentController`, service `HrRecruitmentService`, rules `HrRecruitmentRules`, migration V90.
Permission `hr:recruitment` (hire also needs `hr:employee`). Routes (22): `/hr/recruitment/openings` (list, get, create,
patch, delete, `{id}/status`), `/hr/recruitment/applicants` (list, get, create, patch, `{id}/stage`, `{id}/hire`,
`{id}/interviews`), `/hr/recruitment/interviews/{id}`, `/hr/onboarding/templates` (list, create, patch, delete),
`/hr/onboarding/tasks` (list, create, patch), `/hr/onboarding/employees/{employeeId}/generate`.
Applicant stages: applied → screening → interview → offer → hired, or rejected / withdrawn with a reason; `hired` is
reached only by the hire route, which creates the employee through `HrOrgService.createEmployee` and copies the active
onboarding templates. Screens: `/hr/recruitment`, `/hr/onboarding`.

### HR-2 contract records (built, unverified)

`HrContractController` (9 routes on `/hr/contracts`: list, `expiring`, get, create, patch, delete, `{id}/activate`,
`{id}/terminate`, `{id}/renew`), `HrContractService`, `HrContractExpiryJob` (01:20 daily), migration V91, permission
`hr:contract`. One active contract per employee (unique partial index, activation locks the employee row).
`HrEmployee.contractEndDate` follows the active contract. Screen: `/hr/contracts`.

### HR-3 configurable overtime (built, unverified)

`HrPayrollConfigController` (`/hr/payroll/overtime-rules`, 4 routes, `hr:payroll`), `HrOvertimeService`,
`HrOvertimeCalculator`, migration V92. `HrPayrollService.calculateRun` uses the employee's rule when one applies; with no
active rule the calculation is the Nest one. `overtimeHours` is an Int column: with a rule it stores the rounded hours.

### HR-4 performance review screen (built, unverified)

Screen `/hr/reviews` over the existing `/hr/reviews` routes; no backend change. `useHrReviews` also takes `take` / `skip`.

## Status

140/140 routes (`HrController`, 11 services in `com.inventoryerp.hr.application`).
Tests: `HrRulesTest` (ports `hr-dates.spec.ts`, `hr-employee-projection.spec.ts`), `HrIT` (9 scenarios: org masters,
compensation redaction, identity spine + lifecycle (covers `hr-access.service.spec.ts` rules end to end), attendance
maths in the org zone, leave balance / overlap / approval stamping, timesheets, payroll calculate → approve (balanced
GL, payslips, loan / advance balances) → bank payment → reverse, documents / training / reviews / reports, and the
140-route authorization matrix). Parity scenarios and web screens not yet run against Nest.
