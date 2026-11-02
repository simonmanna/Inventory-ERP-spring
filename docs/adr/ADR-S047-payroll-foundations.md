# ADR-S047: Payroll foundations (HP-0)

- Status: Accepted (owner chose Uganda + UAE as the first statutory packs, 2026-11-02; HP-0 built)
- Plan: [`docs/roadmap/hr-payroll-plan.md`](../roadmap/hr-payroll-plan.md)
- Spec: [`docs/spec/hr.md`](../spec/hr.md) § "HP-0 payroll foundations"
- Migration: `V241__hr_payroll_foundations.sql`

## Context

The payroll engine is the Nest port: one run per period over every active employee, no organisation-level payroll
settings, no pay calendars, no segregation of duties, and nothing stops a run being created under a period whose
payroll was already approved. The ERP-grade engine (HP-2) and the statutory packs (HP-3) need a place for these
settings first, and the first real customers (Uganda: weekly casuals and monthly staff; UAE) need more than one pay
calendar.

## Decision

1. **`HrPayrollSettings`**, one row per organisation: `engine` (`v1` Nest / `v2` decimal, refused until HP-2),
   `statutoryPack` (`NONE`, `UG`, `AE`; packs ship in HP-3), `prorationBasis` (calendar days / working days / fixed 30),
   `weekendDays`, `standardHoursPerDay`, `roundingScale` (default from the organisation's currency: UGX 0, AED 2),
   `requireSeparateApprover`, `lockPeriodOnApproval`, `payslipNote`. Audited.
2. **No settings row = Nest behaviour.** `GET /hr/payroll/settings` reports the defaults with `configured: false`;
   the controls below apply only once the organisation saves its settings. Existing organisations and the Nest parity
   suites are unaffected.
3. **Pay groups** (`HrPayGroup`): MONTHLY (payment day of month, 0 = last day), BIWEEKLY and WEEKLY (anchor date,
   payment N days after the period end). Exactly one default group; employees without a group belong to it.
4. **Membership and period ownership are extra columns not mapped on the Nest entities** (`HrEmployee.payGroupId`,
   `HrPayrollPeriod.payGroupId / paymentDate / lockedAt / lockedBy`), so the Nest JSON of employees and periods does not
   change. The HP-0 routes read them with JDBC.
5. **Calendar generation** creates the group's periods for a year (codes `<GROUP>-<yyyy>-<MM>`, `-W<nn>`, `-B<nn>`),
   idempotent. A run on a group period pays only that group's members and takes the period's payment date when the
   request has none. A period without a group still pays every active employee.
6. **Maker-checker**: with `requireSeparateApprover` the user who calculated a run (`processedById`) gets 403 on
   approve. No new approve permission: the Nest permission matrix of `/hr/payroll/runs/*` stays as it is.
7. **Period lock**: with `lockPeriodOnApproval` approval sets the period `LOCKED`; reversing the approved run reopens
   it. Periods can be locked by hand (`hr:payroll`) and unlocked only with a reason and `hr:payroll_settings`, and
   never while an approved or paid run exists. A locked period refuses run creation and calculation.
8. **Permission `hr:payroll_settings`** for settings, pay group writes and unlock (granted to Administrator by V241).

## Consequences

- 14 new routes under `/hr/payroll` (`HrPayrollSetupController`); the HR authorization matrix grows to 193 routes.
- Deviation from Nest (listed in the spec): a period whose status is `LOCKED` refuses create / calculate run. Nest
  ignored the period status.
- HP-1 (compensation) and HP-2 (engine v2) read the settings and pay groups; HP-5 extends the lock to attendance and
  timesheets.
