# Phase P4 — Procurement

---

Prerequisite: P3a + P3b merged. Read `AGENTS.md`, `docs/CONTRACT.md`, `docs/spec/procurement.md`,
`docs/spec/generated/procurement.md`. Nest source: `reference/nest-api/src/modules/procurement` and
`reference/nest-api/src/kernel/three-way-match`.

## Deliverables
1. Purchase requests (CRUD, submit/approve/reject via approval workflows).
2. Purchase orders (CRUD, activate, receive, pay, cancel; `@Version`; status machine).
3. Goods receipts (ad-hoc, create, post → StockService receipt + GR/IR accrual, reverse).
4. Landed costs (value/quantity/equal allocation; on-hand share → AVCO + stock valuation; consumed
   share → COGS; STANDARD → expense; `LC-` numbering; open period).
5. Debit notes (return to supplier, post/cancel).
6. Three-way match (`/procurement/three-way-match`: list, recompute, validate-bill) + GRNI report.
7. Port the 11 procurement specs; landed-cost scenarios in parity harness.

## Exit criteria
- PO → GRN → vendor bill → supplier payment flow produces identical GL/stock/AP to Nest.
- Landed-cost AVCO tests pass; parity 0; web procurement screens (incl. landed-cost panel) work.
