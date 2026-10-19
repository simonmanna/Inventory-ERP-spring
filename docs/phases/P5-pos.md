# Phase P5 — POS (cafe vertical)

---

Prerequisite: P3a + P3b merged (P4 recommended). Read `AGENTS.md`, `docs/CONTRACT.md` (esp. §6
idempotency, §7 POS token, §9 SSE, §11 offline queue), `docs/spec/pos.md`,
`docs/spec/generated/pos.md` (219 routes, 37 specs), `docs/legacy/POS_TRANSACTION_FLOW.md`,
`docs/legacy/POS_RUNBOOK.md`. Nest source: `reference/nest-api/src/modules/pos`.

## PR sequence (each mergeable on its own)
1. **Settings & lookup**: `/pos/settings`, `/pos/lookup`, payment methods/accounts, kitchen stations.
2. **Menu**: categories, items (availability, variants, recipes `MenuProduct`, accompaniments, bundle,
   restore, hard delete), modifiers, combos, sales reports.
3. **Tables**: zones, tables CRUD/status/assign/merge/unmerge/transfer/transfer-items/split/attach,
   stats, **SSE stream** (snapshot on connect + on change, heartbeat); reservations + 2 crons.
4. **Terminal staff**: `/pos/auth` (staff list, PIN login → POS token, logoff, change PIN/password),
   `/pos/override` (manager PIN → `PosApprovalGrant`), `/pos/operation-approval`, `/pos/shift/handover`.
5. **Orders & tabs**: create/quote/items (PUT with version, POST append), void item (mutation policy),
   fire kitchen (KOT deltas), move/merge/cancel/reopen, tabs save/items/fire/settle, holds.
6. **Checkout & billing**: `/pos/checkout` (idempotent; invoice + payments + GL + cash movement +
   stock posting job in one tx), `/pos/invoices/:id/payments|credit|refund|write-off`, sale void,
   resume/settle, pricing policy, credit checks, stock policy.
7. **Split bills**: item/even/percent, assign/unassign/move/merge/settle/delete.
8. **KDS**: tickets, transitions (single/bulk), priority, assign, send-to-kitchen, **SSE**, reports.
9. **Stock posting**: `StockPostingJob` drain (30 s, SKIP LOCKED, batch `STOCK_POSTING_BATCH`), health
   job, `InventoryException`, `/pos/posting-monitor`; cash-session close drains first.
10. **Receipts & printing**: ESC/POS encoder (CP437, raster logo, cut), TCP 9100 + Windows spooler,
    receipt/bill/additional bill/KOT/KOT delta/cancel KOT/closing statement, PDF/text/HTML, email,
    print lifecycle counters; golden-byte tests comparing to Nest output for the same receipt.
11. **Loyalty & credit**: programs, earn/redeem, store credit, customer tabs, statements, open credit.
12. **Reports**: X/Z (+ by session), 15 report endpoints, tables utilization/revenue, reservations.
13. **Digital menu**: QR sessions, online orders, `/menu/public/*` (public, rate-limited).

## Exit criteria
- 37 POS specs ported and green; business simulations (`reference/nest-api/test/business-simulation`)
  ported and green (GL ties out after simulated trading days).
- `reference/e2e/pos-sell-loop.spec.ts` green against Spring; offline queue replay test (kill API
  mid-sale, restart, replay with same keys → exactly one sale).
- Web POS terminal, tables, KDS, customer display, QR menu work against Spring; printing verified on a
  real or emulated ESC/POS printer (e.g. `escpos-netprinter` container).
- p95 checkout < 150 ms at 50 concurrent terminals (k6 smoke).
- Parity 0 for POS scenarios; PORT_STATUS updated.
