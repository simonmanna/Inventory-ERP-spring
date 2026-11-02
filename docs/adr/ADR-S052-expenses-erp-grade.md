# ADR-S052: Expenses to ERP grade (EX-1 … EX-6)

- Status: Accepted (owner request, 2026-11-02). Built and tested (`ExpenseControlsIT`, `ExpenseClaimsIT`,
  `ExpenseTreasuryIT`; `ExpenseAccrualIT` for EX-0 / ADR-S051).
- Roadmap: [`docs/roadmap/expenses-plan.md`](../roadmap/expenses-plan.md)
- Spec: [`docs/spec/expenses.md`](../spec/expenses.md) (Deviations). Migrations V270 – V274.

## Context

After EX-0 (ADR-S051: accrual at approval, partial payments) the expenses module still lacked what an ERP expense
process needs: dimensions and tax on an expense, category rules, multi-level approval with conditions, budget and
limit checks, employee claims and advances, petty cash, recurring / prepaid / accrued expenses, and reports.

## Decisions

### EX-1 — fields, tax, allocations, category controls (V270)

- `Expense` gains `branchId`, `departmentId`, `costCenterId`, `projectId`, `receiptNumber`, `dueDate`,
  `paidBy` (`COMPANY` | `EMPLOYEE`), `claimantId`, `currencyCode`, `exchangeRate`, `foreignAmount`, `taxId`,
  `taxAmount`, `capitalize`. `amount` stays the gross base-currency amount. When `foreignAmount` and `exchangeRate`
  are both sent, `amount` must equal their product (±0.01). `taxAmount` defaults to the tax-inclusive share of the
  tax's rate (`amount × rate / (100 + rate)`); the category's `defaultTaxId` / `defaultCostCenterId` apply on create.
- `ExpenseAllocation` lines split the net (`amount − taxAmount`) by amount or percent over accounts, cost centres
  (or a cost allocation rule), branches, departments and projects. Amount lines must add up to the net; percent lines
  must add up to 100 and are re-spread on every edit.
- Postings (accrual and immediate cash payment) debit one line per allocation (or one line with the expense's own
  tags), plus `tax_receivable` for the tax. Department and project travel as `departmentId` / `projectId` keys in the
  line dimensions.
- `ExpenseCategory` gains `code` (unique per org), `defaultTaxId`, `defaultCostCenterId`, `receiptRequired`,
  `approvalRequired`, `approvalThreshold`, `reimbursable`, `budgetControl` (`none` | `warn` | `block`),
  `canCapitalise`, `assetAccountId`. A capitalised expense debits the category asset account (else the
  `fixed_asset_valuation` mapping) and is listed under capital purchases; registering the asset stays a fixed-asset
  task.
- An expense paid by an employee is CREDIT and accrues Cr **2189 Employee Reimbursements Payable**
  (`employee_reimbursement_payable`, created on first use) with no partner.

### EX-2 — approval, controls (V271)

- The approval snapshot now carries amount, category, branch, department, cost centre, project, paid-by, claimant and
  supplier, so existing workflow conditions (amount bands, branch, department, department-manager steps, ordered steps,
  approval limits) apply to expenses; the request keeps the snapshot as the record of what was approved.
- Category rule: `approvalRequired`, or `amount ≥ approvalThreshold`, keeps a CREDIT expense DRAFT for a manual
  approval when no workflow applies; a CASH expense in such a category is refused.
- New status `RETURNED`: `POST /expenses/:id/return` (approver, not the raiser) cancels the pending request; the raiser
  edits and `POST /expenses/:id/submit` re-runs the approval gate. "Pending finance" is APPROVED + unpaid.
- Payer separation of duties: new SoD pair `expense_payment` (off by default, switched on under approval controls) —
  raiser, approver or claimant cannot pay the expense, claim or advance.
- GL budget check when an expense is recognised (accrual at approval, or immediate payment) for categories with
  `budgetControl`: approved `Budget` lines on the debit account for the expense month (budget branch / cost centre
  empty or equal) must hold posted actual + this expense. `block` → 409 `EXPENSE_BUDGET_EXCEEDED`; `warn` → audit.
- Spending limits (`ExpenseLimit`): optional user / category / branch scope, per expense or per calendar month,
  `block` (409 `EXPENSE_LIMIT_EXCEEDED`) or `warn`. Checked on create, relevant edits and claim submission.
- `ExpenseSettings` per organization: notify on decision / payment, recurring reminder days, claim receipts required.
  Notifications are in-app.
- Expense roles: no new permissions. Employee = `expense:read` + `expense:create`; Finance officer = + `expense:approve`;
  Cashier = + `expense:post`; Auditor = `expense:read`. Role templates are left to the roles admin screen.

### EX-3 — employee claims and cash advances (V272)

- `ExpenseClaim` groups employee-paid expenses (one `Expense` per line, `claimId` set). draft → submitted (approval
  engine, entity type `expense_claim`; else a manual approval by someone other than the claimant) → approved: every
  line accrues to the reimbursement payable; a linked disbursed advance is settled first (Dr 2189 / Cr advance) →
  paid / partially paid by reimbursing the rest (Dr 2189 / Cr cash, line by line). Approvers can return or reject.
  Claim lines cannot be approved or paid through `/expenses`.
- `ExpenseAdvance`: requested → approved (not by the employee) → disbursed (Dr **1357 Employee Expense Advances**,
  `expense_advance` / Cr cash) → settled by claims and refunds (Dr cash / Cr advance). A claim larger than the advance
  is the top-up: the rest is reimbursed. `ExpenseAdvanceMovement` records every movement.

### EX-4 — petty cash (V273)

- `PettyCashFund` on a cash-equivalent GL account (one fund per account): custodian, float, optional cap per payment.
  Paying an expense, claim or advance from the fund account requires the custodian (or `expense:approve`) and respects
  the cap. Replenish = Dr fund / Cr bank (default float − balance). Counts compare with the GL balance; a difference
  posts to `cash_short_over`. The statement is read from the ledger.

### EX-5 — recurring, prepaid, accruals (V274)

- `ExpenseRecurrence` templates raise CREDIT expenses through the normal approval path (as the template owner) on
  their dates; weekly / monthly / quarterly / yearly × interval, optional end date and due days; a reminder goes to
  the owner `reminderDays` (else the settings default) before each run. A separate table, not the invoicing
  `RecurringDocument`, because that one builds invoicing documents.
- Prepaid: an expense with `prepaidMonths` debits **1385 Prepaid Expenses** (`prepaid_expense`) when recognised;
  `ExpensePrepayment` releases one month at a time (Dr expense account with the expense's branch / cost centre /
  Cr prepaid), the last month taking the rounding. A prepaid expense cannot be capitalised or have allocation lines;
  voiding it reverses the releases; editing it after a release is refused.
- `ExpenseAccrual`: period-end accrual Dr expense / Cr **2158 Accrued Expenses** (`accrued_expense`) on the accrual
  date, reversed on the reversal date (default the first day of the next month).
- `ExpenseScheduleJob` (daily, ShedLock, `app.jobs.enabled`, cron `app.expenses.schedule-cron`, default 01:20) runs
  reminders, recurring runs (catch-up up to 12 per template), accrual reversals and prepaid releases per organization;
  each item commits on its own. `POST /expense-schedules/run` does the same by hand.

### EX-6 — reports and web

- `/expense-reports/summary` (group by category, branch, department, cost centre, project, supplier, claimant,
  paid-by, month; allocation lines split the dimension views), `/trend`, `/pipeline`, `/reimbursements`,
  `/advances`, `/petty-cash`, `/schedules`, `/capital-purchases`.
- Web: expense form section "Accounting, tax & controls", return / resubmit actions, partial pay amount; category
  controls; new pages Claims & Advances, Petty Cash, Recurring & Accruals, Expense Analysis, Limits & Settings.

## Consequences

- 55 new Spring-native routes (expenses package now 73). All additive: the Nest-shaped responses keep their fields;
  new fields are added to `Expense` / `ExpenseCategory` JSON.
- New GL accounts are created on first use with their mappings: 1357, 1385, 2158, 2189.
- Not done here: registering a fixed asset from a capitalised expense; category conditions inside the generic
  approval workflow engine (category rules are in the category instead); web translations for the new phrases.
