# ADR-S048: HR & payroll to ERP grade (HP-1 … HP-10)

- Status: Accepted (owner ordered HP-0 … HP-10 built in one go, 2026-11-02; first statutory packs Uganda + UAE)
- Plan: [`docs/roadmap/hr-payroll-plan.md`](../roadmap/hr-payroll-plan.md) · Foundations: [ADR-S047](ADR-S047-payroll-foundations.md)
- Spec: [`docs/spec/hr.md`](../spec/hr.md) § "HP-1 … HP-10"
- Migrations: `V242` compensation · `V243` engine v2 · `V244` statutory packs + settings · `V245` leave policies ·
  `V246` time to pay · `V247` separation · `V248` pay out · `V249` self-service · `V250` talent

## Context

The HR module was a faithful Nest port: payroll in `double`, no proration, no employer contributions, statutory logic
by component-code substring, leave granted in full on first touch, termination as a status flag, no payslip PDF, no
bank files, no remittance schedules. The owner wants ERP-grade HR & payroll, first for Uganda and the UAE.

## Decisions

1. **Additive, opt-in.** Every new behaviour sits behind saved payroll settings (ADR-S047) or new routes. An
   organisation that never saves settings, or keeps `engine = v1`, runs the Nest calculation unchanged. Nest JSON of
   existing routes is unchanged: new columns on Nest tables (`HrAttendance.overtimeApproved`, `HrPayrollRun.engine`)
   are not mapped on the Nest entities.
2. **New tables use JDBC rows, not JPA** (`HrRows`, like `reporting` / `dms`): org-scoped, soft-deleted, jsonb parsed
   to maps / lists, every parameter bound through one converter.
3. **Compensation (HP-1).** Pay element catalogue (EARNING / DEDUCTION / EMPLOYER_CONTRIBUTION / INFO; FIXED,
   % of basic, % of gross, FORMULA), salary grades with bands, salary structures (element lists), effective-dated
   employee compensation. A salary revision is PENDING until approved by **another user** (`hr:compensation_approve`);
   approval closes the previous row and, once effective, syncs `HrEmployee.baseSalary` (daily job for future dates).
4. **Formulas are a whitelisted expression language** (`HrFormula`: numbers, 14 payroll variables, `+ - * /`,
   comparisons, `MIN MAX ROUND IF ABS`) — no scripting engine, validated on save.
5. **Engine v2 (HP-2)** is a pure decimal calculator (`HrPayrollCalculator`, `BigDecimal`, `HALF_UP`, organisation
   scale) fed by `HrPayrollEngineV2`: proration on calendar days / working days / fixed 30 for joiners, leavers,
   approved unpaid leave and (optionally) absences; hours × rate for hourly staff; overtime by rule (approved only
   when required); recurring elements; one-off inputs; retro arrears for back-dated revisions over paid periods;
   lateness deduction; statutory pack; loans, advances and deduction inputs only while net stays positive. It writes
   the Nest item / allowance / deduction rows (existing screens and payslips keep working) plus `HrPayrollResultLine`s.
   Totals are sums of rounded lines, so the journal balances to the cent. The approval journal for a v2 run credits
   each statutory payable by its mapping key and adds employer contributions (Dr expense / Cr payable).
   Fixes the Nest pitfalls for v2 runs: integer overtime hours 500, last period day excluded, bonus always 0.
6. **Statutory packs (HP-3) are data.** `HrStatutoryRate` holds effective-dated parameters per pack and code; the
   Java (`HrStatutory`) only interprets them. Seeded: **UG** — PAYE resident monthly bands (0 / 10 / 20 / 30 % at
   235k / 335k / 410k, +10 % above 10 M), NSSF 5 % employee + 10 % employer on gross, Local Service Tax (annual band
   amount in four instalments July–October); **AE** — no income tax, GPSSA for UAE nationals (11 % / 12.5 %, 2.5 %
   government shown for information, base floor 1,000 and cap 100,000), end-of-service gratuity (21 days' basic per
   year for five years, 30 after, two years' cap, none under one year). Weekly and bi-weekly pay apply monthly bands to
   the monthly equivalent. A new rate is a new row with a later `effectiveFrom`, never an edit. Remittance schedules per
   period and code, and remittance payment (Dr payable / Cr bank).
7. **Leave (HP-4).** Policy per leave type: monthly or up-front accrual, pro-rata on hire, eligibility months, tenure
   tiers, carry-forward cap with expiry, encashment (taxable payroll input at basic ÷ 30), weekend / holiday counting,
   half days. The ledger (`HrLeaveLedger`, idempotent per period key) explains the balance; with a policy the accrued
   days of a balance are the sum of its ACCRUAL entries. Monthly accrual and the 1 January rollover run as jobs.
   `POST /hr/leave/balances/adjust` now adds decimals (Nest concatenated strings) — listed deviation.
8. **Time to pay (HP-5).** Overtime approval per attendance row, pre-payroll period review, and the period lock also
   refuses manual attendance edits on locked days (only once settings are saved).
9. **Separation (HP-6).** Clearance checklist must be complete; final settlement = salary to the last day after the
   last paid period, encashment of paid leave, notice pay in lieu or short-notice recovery, gratuity (AE statutory,
   otherwise contractual days per year from settings; none on DISMISSAL), statutory deductions on the taxable part,
   recovery of loans and advances. Approval by another user posts the journal (gratuity first from the provision),
   closes recoveries and terminates the employee through the lifecycle service; payment clears net pay payable.
   The gratuity provision is accrued monthly (IAS 19 on the statutory formula, Dr gratuity expense / Cr provision).
10. **Pay out (HP-7).** Payslip PDF (OpenPDF; earnings, deductions, employer contributions, YTD, masked account),
    e-mail of a run's payslips, bank CSV, mobile-money CSV, **UAE WPS SIF** (EDR per employee, SCR trailer; refuses
    with the list of missing ids), IBAN mod-97 validation, net-pay settlement (Dr net pay payable / Cr bank; run and
    payslips PAID), annual earnings and tax certificate PDF.
11. **Self-service (HP-8).** Employees (login linked to an employee record) download their payslips and certificate,
    submit profile changes from a whitelist (HR applies them) and pay claims (approved by HR or the direct manager,
    paid as a non-taxable payroll input), and see their team's pending leave and claims.
12. **Talent (HP-9).** Competencies, review cycles (scope by department), weighted goals (≤ 100 % per employee),
    appraisals (self, manager = supervisor, peers). Overall = goal score × goal weight + competency score × the rest.
    Closing a cycle writes each submitted manager appraisal as a Nest performance review (rating = rounded score).
13. **Reports (HP-10).** Payroll register (+ CSV), variance against the previous run of the pay group (threshold from
    settings), payroll cost by period and by department (with employer contributions), headcount / hires / leavers /
    turnover by month, salary change audit, statutory totals by month, leave liability.
14. **Controls.** Maker-checker for runs, salary revisions and settlements; audit rows for settings, revisions,
    settlements, payments, claims and profile changes; permissions `hr:compensation_approve`, `hr:separation`,
    `hr:talent` (granted to Administrator); four optional GL mapping keys `employer_contribution_expense`,
    `local_service_tax_payable`, `gratuity_expense`, `gratuity_provision` (fallbacks documented in the registry).

## Consequences

- 108 new routes (HR authorization matrix 301 routes); suites `HrPayrollEngineTest` (12), `HrPayrollV2IT` (3),
  `HrPeopleOpsIT` (7) on top of HP-0's.
- Verify the statutory rates before each tax year and add rows, never edit. GCC-national pension (non-UAE GCC
  staff), Uganda gratuity / severance tax treatment and the WPS bank-specific header variants are not covered yet.
- New web screens: Compensation, Payroll operations, Leave policies, Time review, Separations, Employee requests,
  My pay & requests, Performance cycles, Workforce analytics; General payroll settings gained the engine, deduction
  and WPS fields. Phrases for ti / lg / sw are added empty (English fallback) until translated.
