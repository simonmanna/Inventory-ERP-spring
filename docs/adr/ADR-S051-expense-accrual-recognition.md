# ADR-S051: Accrual recognition of credit expenses, and partial expense payments (EX-0)

- Status: Accepted (owner request, 2026-11-02). Built and tested (`ExpenseAccrualIT`).
- Roadmap: Expenses gap analysis, phase EX-0
- Spec: [`docs/spec/expenses.md`](../spec/expenses.md) (Deviations). No migration.

## Context

The Nest port books an expense only when it is paid: a CREDIT (supplier-credit) expense posts nothing at approval and
its payment posts Dr expense / Cr cash-bank. Approved but unpaid expenses are missing from the P&L and from accounts
payable, month-end cut-off is wrong, and the supplier's AP sub-ledger never shows what the company owes. Payments
always cleared the whole residual, so `PARTIALLY_PAID` was unreachable.

## Decision

1. **Recognise at approval.** When a CREDIT expense becomes `APPROVED` (auto-approved on create when no approval
   policy applies, or `POST /expenses/:id/approve` once the approval request is decided) the service posts, on the
   expense date, journal `GEN`, source `expense_accrual` / the expense id:
   - Dr the expense account: the source module's account and tags (cost centre, branch, dimensions — fleet fuel,
     fleet fines) when present, else the category ledger, the `default_expense` mapping, then the first expense account;
   - Cr the supplier's `payableAccountId` override, else the `accounts_payable` mapping, with the supplier as the
     line's partner (so it appears on the supplier's AP statement).
   The posting key is `expense_accrual:<id>:<n>`, so a retry never books twice.
2. **Payments clear the payable.** Paying an accrued expense posts Dr that payable (same partner) / Cr cash-bank, with
   the existing treasury guards. A CASH expense keeps posting Dr expense / Cr cash-bank at once and never accrues.
3. **Partial payments.** `POST /expenses/:id/pay` accepts an optional `amount` (positive). Absent = the whole residual
   (the old behaviour, so the web and Android clients are unchanged). More than the residual is refused with
   `Payment amount <a> exceeds the outstanding balance of <r>`. Status becomes `PARTIALLY_PAID` until fully paid.
4. **Edits, void and delete keep the GL straight.** Editing amount, date, supplier, category or title of an accrued,
   unpaid expense reverses the accrual and posts a new one; void reverses every payment and the accrual; deleting an
   unpaid accrued expense reverses the accrual.
5. **Legacy rows.** A CREDIT expense approved before this change has no `expense_accrual` entry; it keeps the old
   pay-time Dr expense / Cr cash-bank posting. No back-fill: those amounts are already in the ledger at payment.
6. **Period control** is the posting engine's: approving an expense dated in a closed period is refused, like any
   other posting into it.

## Consequences

- P&L shows approved expenses in the period incurred; AP balance and the supplier statement include unpaid expenses.
- Approving an expense now needs a resolvable expense account and an `accounts_payable` mapping (both seeded).
- Parity deviation from Nest (GL rows written at approval; an extra optional `amount` field on pay). The response
  shapes are unchanged. `contract-tests/scenarios/expenses-lifecycle.json` touches no posting route and is unaffected.
- Employee reimbursement payable (expense paid by an employee) is not part of this ADR; it comes with employee
  claims (EX-3). The web pay dialog does not offer a partial amount yet (EX-6).
