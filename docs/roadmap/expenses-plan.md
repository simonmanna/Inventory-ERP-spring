# Expenses to ERP grade — plan and status

Owner request 2026-11-02. Decisions: [ADR-S051](../adr/ADR-S051-expense-accrual-recognition.md) (EX-0),
[ADR-S052](../adr/ADR-S052-expenses-erp-grade.md) (EX-1 … EX-6). Spec deviations: [expenses.md](../spec/expenses.md).

| Phase | Scope | Status | Tests |
|---|---|---|---|
| EX-0 | Recognise CREDIT expenses at approval (Dr expense / Cr AP), partial payments | done | `ExpenseAccrualIT` |
| EX-1 | Category controls; tax, branch / department / cost centre / project, currency, receipt, due date, paid-by; allocation lines; capitalise | done (V270) | `ExpenseControlsIT` |
| EX-2 | Approval snapshot with dimensions (workflow conditions + ordered steps), category approval rule, returned / resubmit, payer SoD, GL budget check, spending limits, settings + notifications | done (V271) | `ExpenseControlsIT`, `ApprovalControlsIT` |
| EX-3 | Employee claims, reimbursement payable, cash advances (disburse, settle, top-up, refund) | done (V272) | `ExpenseClaimsIT` |
| EX-4 | Petty cash funds: float, custodian, cap, replenish, count with over / short, statement | done (V273) | `ExpenseTreasuryIT` |
| EX-5 | Recurring expenses with reminders, prepaid release schedules, period-end accruals with auto-reversal, daily job | done (V274) | `ExpenseTreasuryIT` |
| EX-6 | Reports (dimension summary, trend, pipeline, reimbursements, advances, petty cash, schedules, capital purchases) + web screens | done | `ExpenseTreasuryIT`, web typecheck |

## Gap list (2026-11-02 review) → where it landed

| Gap | Phase |
|---|---|
| Submitted / returned / pending finance / partially paid | EX-0 (partial), EX-2 (RETURNED, pipeline report) |
| Approval rules on category / branch / department / cost centre, ordered levels, saved rule copy | EX-2 (snapshot → workflow conditions; category rule in the category) |
| Payer vs raiser / approver | EX-2 (SoD pair `expense_payment`) |
| Category fields | EX-1 |
| Budget check on approve / pay | EX-2 |
| Recurring expenses + reminder | EX-5 |
| Currency fields | EX-1 |
| Receipt / invoice no., due date, paid by, branch | EX-1 |
| Capital purchases | EX-1 (asset account) + EX-6 report |
| Reports | EX-6 |
| Web tax / allocation fields | EX-6 |
| Employee claims | EX-3 |
| Cash advances + settlement | EX-3 |
| Petty cash | EX-4 |
| Prepaid + monthly release | EX-5 |
| Accrued expenses + auto-reversal | EX-5 |
| Allocation lines | EX-1 |
| Limits per employee / category / branch | EX-2 |
| Notification settings | EX-2 |
| Expense roles | EX-2 (permission sets documented, no new permissions) |

## Open follow-ups

- Web phrases of the new screens are not yet translated (ti, lg, sw); `pnpm web:i18n` reports them.
- Register a fixed asset from a capitalised expense (fixed-asset module).
- Category as a condition inside the generic approval workflow engine (today: category approval rule).
- POS drawer accounts stay refused for expense payments (`assertNotDrawerAccount`), unlike the original spec text.
