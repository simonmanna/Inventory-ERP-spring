# Phase P2 — Core master data + Accounting

---

You are implementing **phase P2** of Inventory-ERP-spring. Prerequisite: P1 merged (auth, tenancy,
idempotency, permissions, audit, settings, sequences, outbox). Read `AGENTS.md`, `docs/CONTRACT.md`,
`docs/spec/core.md`, `docs/spec/accounting.md` and their generated tables. The NestJS code in
`reference/nest-api/src/modules/core` and `.../accounting` is the specification.

## Deliverables
1. **Core** (81 routes): organizations (bootstrap/invite/me/branch), branches, partners (+categories,
   contacts, addresses), products (+search, resolve-barcode, restore, variants, packaging, UoM
   conversions), product categories, taxes, UoM + categories (+convert), fiscal periods (core
   behaviour wins over the accounting duplicate), fiscal positions, payment terms. Port all 21 specs.
2. **Posting engine**: account mapping resolution (`packages/shared/src/accounting/*`), journal entry
   create/post/reverse with balance + open-period checks and `postingKey` idempotency, used by every
   later module. Expose it as `accounting.application.PostingService` (the module's public API).
3. **Chart of accounts**, categories, mappings, journals, cost centers, currencies/rates/FX revaluation,
   period close/lock/reopen, bank accounts, treasury transfer (idempotent), bank reconciliation.
4. **Cash**: POS payment methods, cash registers (+drawer balance), **cash sessions** (open, close,
   movement, force-close, banking, reconcile, daily report/reconcile, variance, expected, movements,
   history, tender settlements) with every rule in `docs/spec/accounting.md` §rules; money operations
   (`/accounts/cash-flow/*`).
5. **Reports**: trial balance, GL, account ledger, P&L, balance sheet (+detailed), cash flow, tie-out,
   POS-GL reconciliation, snapshots + rebuild, CSV/PDF exports, dashboard KPIs/activity. Port the
   SQL from the Nest services verbatim through `JdbcClient`.
6. Port all 30 accounting specs (treasury and posting first). Seed: chart of accounts / mappings part.

## Exit criteria
- `./gradlew build` green; ported specs pass.
- Business simulation subset (cash session open → movements → close with variance → reconcile) gives
  the same GL as Nest; trial balance balances.
- Web: master data screens, chart of accounts, journals, cash registers/sessions, money screens and
  accounting reports work against Spring.
- Parity zero diffs for core + accounting scenarios; PORT_STATUS updated.
