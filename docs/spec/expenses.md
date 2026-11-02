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

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/expenses-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

- **ADR-S051 (EX-0, owner-approved 2026-11-02): accrual recognition + partial payments.** An approved CREDIT
  expense posts Dr expense / Cr accounts payable (supplier as partner, source `expense_accrual`); its payments post
  Dr payable / Cr cash-bank. `POST /expenses/:id/pay` takes an optional positive `amount` (default = whole residual;
  more than the residual → `Payment amount <a> exceeds the outstanding balance of <r>`). Edits re-post the accrual,
  void / delete reverse it. Expenses approved before the change keep the pay-time Dr expense posting. Nest posts
  nothing until payment and always pays the full residual. Test: `ExpenseAccrualIT`.

## Status

P3b: 18/18 routes. Tests: `InvoicingIT#expenses` (categories CRUD + ledger validation, CREDIT expense →
pay from bank 1200 → void, CASH expense paid on create, SoD 403 on self-approval, reject, soft delete,
list / stats / meta / audit), `InvoicingIT#tenantIsolation`, authorization matrix = 18 routes. Money routes
(`POST /expenses`, `/:id/pay`, `/:id/void`) require `Idempotency-Key`. Parity scenario:
`contract-tests/scenarios/expenses-lifecycle.json`.
