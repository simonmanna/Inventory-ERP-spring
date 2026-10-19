<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Accounting, treasury and cash — `accounting`

| | |
|---|---|
| Phase | P2 |
| Nest source | `reference/nest-api/src/modules/accounting` |
| Routes | 110 — full table: [generated/accounting.md](generated/accounting.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.accounting` |

## Purpose

Chart of accounts and categories, account mappings (posting keys → accounts), journals and journal entries (post/reverse), cost centers, currencies and FX rates/revaluation, fiscal period close/lock/reopen, bank accounts, treasury transfers, bank reconciliation, financial reports and snapshots, money operations (`accounts/cash-flow`), POS payment methods, cash registers, cash sessions (shifts), tender settlements.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| chart of accounts | `/accounts` CRUD, tree, reorder, deprecate; `/account-categories` read + usage; `/account-mappings` registry/missing/PUT |
| journals | `/journals` CRUD; `/journal-entries` CRUD + post + reverse; posting engine in `accounting/posting` |
| periods | `/fiscal-periods/:id/close|lock|reopen` (period close checks) |
| fx | `/currencies`, rates, revaluation (`FxRevaluation`) |
| bank | `/bank-accounts`, `/treasury/transfer` (idempotent), `/bank-reconciliation` import/match/unmatch/status |
| reports | `/reports/accounting/*` (trial balance, GL, account ledger, P&L, balance sheet (+detailed), cash flow, tieout, pos-gl-reconciliation, rebuild snapshots), `/reports/export/*.csv|pdf`, `/reports/inventory/valuation|gl-tieout`, `/reports/dashboard-kpi|dashboard-activity` |
| money operations | `/accounts/cash-flow`: overview, activity, operation types, transactions, deposit, withdraw (idempotent) — `money-activity*`, `money-overview`, `money-settlement` |
| payment methods | `/accounts/pos-payment-methods` CRUD (tender → account) |
| cash registers | `/cash-registers` CRUD + drawer balance |
| cash sessions | `/cash-sessions/*`: open, close, movement, force-close, banking, reconcile, daily report/reconcile, variance, expected, movements, history, tender-settlements |

## Business rules to reproduce exactly

1. Journal entry must balance (Σdebit = Σcredit, `approxEqual` 0.0001) and date must fall in an open period; `postingKey` unique per org makes postings idempotent; reversal creates a mirrored entry linked by `reversalOfId`.
2. Account mappings: every automatic posting resolves accounts through mapping keys (`packages/shared/src/accounting/account-mappings.ts`); missing mapping → error listing the key (verbatim).
3. Period close/lock: closed periods reject postings; lock is irreversible except `reopen` with permission `fiscal_period:reopen`.
4. Cash session open: register must have its own active drawer cash account; opening count may not be below the drawer ledger balance (unless `autoAdjust`); added float requires a funding account (safe/bank) with sufficient recorded funds and a reason.
5. Movements: `pay_in | pay_out | adjustment`, non-zero amount, reason, counterpart account, cannot overdraw the drawer, only the session owner (or manager); pay_out requires manager sign-off (`approvedById`/`managerPin`); corrections to a closed shift = adjustment with `correctionOfSessionId` and permission `cash_session:correct`.
6. Close: expected cash computed per payment method; counted cannot be negative; a variance needs a reason and approval from a holder of `cash_session:approve_variance` (manager PIN); `autoAdjustCash` records the difference as pay-in/pay-out; closing first drains pending stock postings and is blocked by open sync dead letters; variance and bank deposit post to GL.
7. Force-close: requires `cash_session:force_close`, a reason, and must target another cashier's shift. Any holder of `cash_session:close` may close an open shift on a shared terminal (recent change, commit 93c3414).
8. Handover (`/pos/shift/handover`): different incoming cashier; approving manager must be neither cashier and hold `cash_session:approve_variance`.
9. Closing amounts visibility is controlled by a setting (commit 17126b5) — respect it in responses.
10. Status machine: `open → closed → reconciled`.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`journal.posted`, `journal.reversed`, `cash.received`, `cash.paid`, `bank.transfer`, `cash.session.opened/closed`, …

## Scheduled jobs

report snapshot cron (`snapshot-cron.worker.ts`).

## Notes and pitfalls

- Port `accounting/treasury` specs (5) and `accounting/posting` specs (3) first — they pin the cash rules.
- Trial balance / P&L queries are heavy SQL: port the raw SQL as-is via `JdbcClient`.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/accounting-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
