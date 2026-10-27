# ADR-S005: Accounting completion — opening balances, tax report, budgets, statements, invoice e-mail, branch consolidation

- Status: accepted (2026-10-27, requested by the owner). Additive extension beyond the NestJS reference.
- Related: ADR-S001 (frozen wire contract), `V8__accounting_extensions.sql`, `docs/spec/accounting.md`,
  `docs/spec/invoicing.md` "Extensions"

## Context

A gap review of the finance features against a standard "accounting product" checklist found the core in place
(CoA, double-entry GL, AR/AP, treasury, bank reconciliation, P&L, balance sheet, trial balance, cash flow,
aging, payroll, multi-currency, audit log, backups). Six things were missing or only partial:

1. **Opening balances.** There was no way to bring in balances when migrating from another system. The only
   option was a hand-made journal, which does not create AR/AP open items, so aging and statements missed them.
2. **Tax report.** Tax rates existed, but no report showed output tax, input tax and the net amount payable for a
   period.
3. **Budgets.** Accounts had an `allowBudgeting` flag, but there was no budget and no budget-vs-actual report.
4. **Customer statements.** Statements existed only in POS. Back-office AR had none, and there was no
   supplier statement in statement form.
5. **Sending invoices.** Only POS receipts could be e-mailed. There was no invoice PDF and no invoice e-mail.
6. **Multi-branch.** Reports could filter by one branch, but there was no side-by-side, per-branch
   consolidated view.

## Decision

Every extension is **additive**. It adds new paths and new tables, and no existing route, payload or row changes.
Nest-era clients are unaffected.

| # | Routes | Module | Permission |
|---|---|---|---|
| 1 | `GET/POST /opening-balances` (POST requires `Idempotency-Key`) | invoicing | `opening_balance:read` / `opening_balance:post` |
| 2 | `GET /reports/tax/summary?from&to&branchId` | invoicing | `report:accounting` |
| 3 | `GET/POST /budgets`, `GET/PATCH/DELETE /budgets/:id`, `PUT /budgets/:id/lines`, `POST /budgets/:id/approve`, `POST /budgets/:id/reopen`, `GET /budgets/:id/vs-actual` | accounting | `budget:read/create/update/delete/approve` |
| 4 | `GET /reports/ar/customers/:partnerId/statement[.pdf]`, `POST …/statement/email`, `GET /reports/ap/suppliers/:partnerId/statement[.pdf]` | invoicing | `report:ar` |
| 5 | `GET /invoices/:id/pdf`, `POST /invoices/:id/email` | invoicing | `invoice:read` / `invoice:send` |
| 6 | `GET /reports/accounting/consolidated?from&to&statement=pl\|tb` | accounting | `report:accounting` |

Rules:

- **Opening balances.**
  - GL lines post as **one** `GEN` journal entry (`sourceType = opening_balance`). Any difference posts to the
    `retained_earnings` mapping, which acts as opening-balance equity.
  - AR and AP control accounts are refused as plain GL lines. Customer and supplier balances use their own
    sections instead: each becomes a **posted** sales invoice or vendor bill with one line on the equity
    account. That makes them real open items, so they show in aging and statements and payments can settle them.
- **Tax report.**
  - Output tax comes from posted sales invoices minus credit notes (`DocumentLine`), plus POS invoices.
  - Input tax comes from posted vendor bills minus debit notes.
  - Both are grouped by tax. A GL cross-check of the tax accounts is included.
- **Budgets.**
  - A budget has monthly lines (`YYYY-MM`) per postable account. Lines can be edited only while the budget is
    `draft`.
  - `approve` locks the budget, and `reopen` returns it to draft.
  - Actuals come from posted GL lines, in each account's natural sign. They are filtered by the budget's
    branch or cost centre when it has one.
  - A variance is favourable when income is above budget or an expense is below it.
- **Statements.**
  - Statements are built from the GL: AR or AP control-account lines that carry the partner. They show the
    opening balance, a running balance, the closing balance, and aging of open documents.
  - PDFs use OpenPDF, which is already on the stack.
- **E-mail.**
  - E-mail goes through `NotificationsService.sendExternalEmail`, using the existing SMTP config.
  - Without `SMTP_HOST` the message is logged instead of sent, as other notifications already do.
- **Consolidation.**
  - Each posted line is attributed to its line `branchId`. Lines without one go to an `unassigned` column.
  - Inter-branch eliminations are out of scope. A single legal entity has no intercompany accounts.

## Consequences

- New tables: `Budget` and `BudgetLine` (V8). New permission keys, which V8 grants to every existing
  `Administrator` role.
- The parity harness has no Nest counterpart for these routes. They are covered by Spring integration tests only.
- The web app gains pages for budgets, opening balances, the tax report, statements and the consolidated
  report, plus an e-mail button on the invoice detail page.
