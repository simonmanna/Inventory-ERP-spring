# HR & Payroll to ERP grade: audit and phased plan (rev 1)

> **Status (2026-11-02):** owner chose **Uganda + UAE** as the first statutory packs (§5 Q1). Q2–Q4 run on the
> recommended defaults until the owner says otherwise: engine v2 is a listed deviation with v1 selectable per
> organisation; maker-checker is a setting, default on; phase order as proposed. **HP-0 built** (ADR-S047);
> **HP-1 … HP-10 built 2026-11-02 in one go at owner request** (ADR-S048).

## 1. What exists today (audit)

| Area | State | Where |
|---|---|---|
| Org structure | Departments, positions, supervisor chain, branches | `HrOrgService`, `/hr/departments`, `/hr/positions` |
| Employee master | Identity, bank / mobile money, tax / pension / SSF numbers, lifecycle (confirm, suspend, transfer, terminate, reactivate), status history, POS PIN / user link, compensation redaction | `HrOrgService`, `HrLifecycleService`, `HrAccessService` |
| Contracts | One active contract per employee, renew / terminate, expiry job | HR-2, `HrContractService` |
| Recruitment + onboarding | Openings, applicants, interviews, hire → employee, onboarding templates/tasks | HR-1 |
| Time | Shifts, assignments, clock / manual attendance, POS PIN clock-in, timesheets | `HrAttendanceService`, `HrTimesheetService` |
| Leave | Types, balances, requests, approval, holidays | `HrLeaveService` |
| Payroll | Components (fixed / %), one PAYE table, periods, runs (DRAFT → CALCULATED → APPROVED → PAID / REVERSED), payslip rows, GL journal, bank payment batches, loans, advances, overtime rules (HR-3), cost allocation by cost centre / branch (HR-5), cross-module payroll inputs (fleet fines etc.) | `HrPayrollService` (1,128 lines) |
| Talent | Performance reviews (single rating), training programmes | `/hr/reviews`, `/hr/training` |
| Reports / ESS | Dashboard, headcount / attendance register / payroll summary, self-service, team view | `HrReportsService`, `HrSelfService` |
| Web | 27 screens under `/hr/*` (7.2k lines) | `web/src/pages/hr` |

Tests: `HrIT` (9 scenarios + 140-route authz matrix), `HrContractIT`, `HrRecruitmentIT`, `HrPayrollExtensionsIT`,
`HrUpdatesIT`, `HrRulesTest`, `HrOvertimeCalculatorTest`, `HrPayrollCostAllocatorTest`.

The module is a faithful **Nest port**. Breadth is good. Depth of the payroll engine and leave is not ERP grade.

## 2. Gaps (why it is not ERP grade yet)

### 2.1 Payroll engine — critical

| # | Gap | Effect |
|---|---|---|
| P1 | Calculation uses `double` (`HrPayrollService.calculateRun`) | Breaks AGENTS.md hard rule; cent drift on large runs; journal can be off by rounding |
| P2 | No proration | Joiner on the 20th or leaver on the 5th gets a full month |
| P3 | No unpaid-leave / absence deduction (`absenceDays` always 0) | Unpaid leave is paid |
| P4 | No employer contributions | Employer NSSF / pension / GOSI cost never expensed or accrued as a liability |
| P5 | Components are org-wide only (`appliesTo` text) | No per-employee pay elements, no salary structures / grades, no effective dating |
| P6 | No salary revision history | Raises overwrite `baseSalary`; no audit, no approval, no retro pay |
| P7 | Statutory logic hard-coded by **code substring** (`PENSION`, `SSF`, `INSURANCE`) and one `PAYE` table | No reliefs, caps, tiers, levies, per-country rules; no effective-dated rates |
| P8 | Calculation picks **all active employees** | No pay groups (monthly vs weekly vs casual), no off-cycle / bonus runs |
| P9 | Approve = calculate-reviewer can post | No maker-checker / segregation of duties, no variance check vs last run |
| P10 | Nest bugs kept for parity: Int `overtimeHours` 500, period end-day excluded, `bonusAmount`/`commissionAmount` always 0 | Real users hit 500s and miss last-day hours |
| P11 | Period not locked after approval | Attendance / leave can change under an approved run |

### 2.2 Leave

No accrual (full `daysPerYear` granted on first touch), no pro-rata on hire, no carry-forward rollover / expiry job,
no encashment, no half-days, weekends / holidays not excluded from day counts, balance-adjust string-concatenation bug,
no tenure tiers, no approval-workflow engine integration.

### 2.3 Separation

Termination is a status change only: no final settlement (pro-rata pay, leave encashment, notice pay, gratuity /
end-of-service benefit, loan recovery), no clearance checklist, no IAS 19 gratuity provision.

### 2.4 Payments and payslips

No payslip PDF, no payslip distribution (email / ESS download), bank batch is a list not a bank file (no generic CSV,
no UAE WPS SIF, no mobile-money bulk file), net-pay payable clearing not reconciled to bank.

### 2.5 Statutory compliance and reporting

No monthly remittance schedules (PAYE, NSSF, LST, GOSI …), no payroll register / variance report, no year-end
employee tax certificates, no remittance payment flow from the payables created by the run.

### 2.6 Talent and ESS

Reviews are a single score; no goal / KPI cycles. ESS cannot download payslips, request profile changes, or claim
expenses against payroll.

## 3. Target architecture

- **Engine v2 (`hr.payroll.engine`)**: pure, deterministic `PayrollCalculator` (BigDecimal, currency scale,
  `HALF_UP`) fed by a `PayrollContext` snapshot (employee, effective compensation, attendance, leave, inputs,
  statutory pack). Unit-testable without Spring, mirrors `HrOvertimeCalculator` / `HrPayrollCostAllocator` style.
- **Pay elements**: component catalogue with `calcMethod` FIXED / PERCENT / FORMULA (safe expression evaluator, whitelisted
  variables `BASIC`, `GROSS`, `DAYS_IN_PERIOD`, `DAYS_PAID`, `HOURS`, `OT_HOURS`, `YEARS_OF_SERVICE`; no scripting
  engine), classification (earning / deduction / employer contribution / info), tax treatment, GL account per element.
- **Statutory packs** behind an SPI `StatutoryPack` (`UG`, `KE`, `AE`, `SA`, … per §5). Rates, bands, caps and reliefs are
  **effective-dated data** seeded by migration, never constants in code. Org picks its pack in payroll settings.
- **Pay groups + calendars**: employees belong to one pay group (frequency, calendar, cut-off, bank). Runs are per pay
  group; off-cycle runs (bonus, final settlement, correction).
- **Run lifecycle**: DRAFT → CALCULATED → **REVIEWED** (variance report, exceptions) → APPROVED (different user;
  journal; period locked) → PAID (bank file reconciled) / REVERSED. Uses the existing approval-workflow engine.
- **GL**: one journal per run with per-element accounts, employer contributions (Dr expense / Cr liability), cost
  allocation (HR-5 kept), remittance payments clear the liabilities; gratuity provision accrued monthly.
- **Nest compatibility**: v1 (Nest calculation) stays selectable per org until the owner retires Nest; v2 is the
  default for new orgs. Every v1 → v2 behaviour change is listed under "Deviations" in `docs/spec/hr.md`.
- **Security**: compensation redaction kept; new permissions `hr:payroll:approve` (checker), `hr:payroll:settings`,
  `hr:compensation`; bank details masked in lists and logs.

## 4. Phases

| Phase | Scope | Exit gate | Size |
|---|---|---|---|
| **HP-0** Foundations ✅ | Payroll settings per org (engine v1/v2, statutory pack, proration basis: calendar / working / fixed-30 days, rounding), pay groups + pay calendars, period lock, new permissions, ADR | Settings + pay group CRUD tested; v1 runs unchanged (existing ITs green) | M |
| **HP-1** Compensation ✅ | Salary grades / bands, salary structures (element templates), per-employee pay elements with effective dates, salary revisions with approval + history, employee compensation tab | Revision effective mid-period visible to engine context; authz + tenancy tests | M |
| **HP-2** Engine v2 ✅ | BigDecimal calculator, FORMULA elements, proration (join / leave / unpaid leave / absence), employer contributions, one-off inputs (bonus, commission, arrears), retro pay from back-dated revisions, fixes P10, maker-checker REVIEWED step, variance report, off-cycle runs, per-element GL | `PayrollCalculatorTest` golden cases; journal balanced to the cent on 500-employee run; v1 vs v2 parity on the simple case | L |
| **HP-3** Statutory packs ✅ | SPI + first packs (§5 Q1), effective-dated rate tables, reliefs / caps / tiers, remittance schedules, remittance payment posting, year-end tax certificate | Published-rate worked examples per pack as golden tests | L |
| **HP-4** Leave pro ✅ | Accrual policies (monthly / annual, pro-rata, tenure tiers), working-day counting (weekends, holidays, half-days), carry-forward + expiry year-end job, encashment (to payroll input), approval workflow, leave calendar, fix balance-adjust bug | Accrual / rollover golden tests; ESS leave flow | M |
| **HP-5** Time → pay ✅ | Attendance / timesheet lock per period, overtime approval, late / absence policies → payroll inputs | Locked period rejects edits; approved OT reaches the run | M |
| **HP-6** Separation ✅ | Offboarding checklist, final settlement (pro-rata, leave encashment, notice, gratuity / EOSB per pack, loan / advance recovery), settlement off-cycle run, IAS 19 gratuity provision accrual | Settlement worked examples per pack; provision reconciles to GL | M |
| **HP-7** Pay out ✅ | Payslip PDF (OpenPDF, org branding, multi-language), ESS download + email, bank files (generic CSV, WPS SIF, mobile-money bulk), payment reconciliation clearing net-pay payable | PDF snapshot test; SIF format test; payable cleared to zero | M |
| **HP-8** ESS + workflows ✅ | Profile change requests with approval, payslip / tax cert downloads, claims → payroll input, manager approvals inbox | Web click-through clean | M |
| **HP-9** Talent ✅ | Review cycles, goals / KPIs, weighted scoring, 360 input, training needs from reviews | ITs + screens | M |
| **HP-10** Reports ✅ | Payroll register, variance, statutory schedules, headcount / turnover / attrition, cost by department, salary audit trail; all in the FR report engine (ADR-S043) | Reports reconcile to GL | S–M |

Order: HP-0 → HP-1 → HP-2 → HP-3 are the critical path (a correct, compliant payroll). HP-4 … HP-10 may be reordered
by the owner. Each phase: migration `V24x__hr_*.sql`, ADR, spec section, ITs extending `ApiTestSupport`, JaCoCo ≥ 80 %
on `hr.application`, web screens, i18n phrases (ti / lg / sw), `PORT_STATUS.md`.

## 5. Owner decisions needed before HP-0

1. **Statutory packs first.** Which countries go live first? Candidates from current users / demo data: Uganda (PAYE,
   NSSF 5 % + 10 %, LST), Kenya (PAYE + relief, NSSF tiers, SHIF, Housing Levy), UAE (no PIT, GPSSA for nationals,
   EOSB, WPS SIF), Saudi Arabia (GOSI, EOSB), Eritrea / Ethiopia.
2. **Nest parity.** Approve engine v2 as a **deviation** from Nest (different, correct results for proration, unpaid
   leave, employer contributions, fixed 500s), with v1 kept selectable per org until Nest is retired?
3. **Maker-checker.** Make the separate approver mandatory for v2 runs, or a setting (default on)?
4. **Phase order after HP-3.** Default proposed: HP-4 leave → HP-6 separation → HP-7 pay out → HP-5 → HP-8 → HP-10 → HP-9.
