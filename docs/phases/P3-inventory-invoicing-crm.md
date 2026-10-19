# Phase P3 — three parallel tracks

Prerequisite: P2 merged. The three tracks touch different modules and can run in parallel
sessions/branches. Each track: read `AGENTS.md`, `docs/CONTRACT.md`, its module specs and generated
tables; the Nest module source is the specification.

---

## P3a — Inventory (`docs/spec/inventory.md`, 69 routes, 28 specs)

1. `StockService` (receive/issue/transfer/adjust) with costing AVCO/FIFO/STANDARD/SPECIFIC, row
   locks, stock-period lock, append-only `InventoryLedger`, batches/serials/expiry. Public API of
   the module — POS, procurement and manufacturing call it.
2. Posting rules (`/inventory/posting-rules`, defaults from `packages/shared/src/inventory-posting-rules.ts`)
   → GL via `PostingService`.
3. Locations, direct stock in/out, items/levels/ledger/stats, stock-outs, waste, adjustments,
   transfers (dispatch/receive/recall), counts, reservations/ATP, 10 reports.
Exit: specs ported; inventory valuation report equals GL stock accounts (tie-out); parity 0.

## P3b — Invoicing + Expenses (`docs/spec/invoicing.md`, `docs/spec/expenses.md`, 48 routes)

1. Invoices (journal-preview/post/cancel), credit notes, vendor bills (+receipt match, supplier ledger),
   payments + allocations + withholding + void (idempotent), supplier payments, tax calculation,
   AR/AP reports. Public API `InvoicingService` used by POS billing.
2. Expenses + categories with approve/reject/pay/void (idempotent) and cash-drawer payment.
Exit: specs ported (14 + 2); AR/AP aging parity; parity 0.

## P3c — CRM, Tasks, Documents (`docs/spec/crm.md`, `task.md`, `documents.md`, 65 routes)

1. CRM deals/activities/analytics + `invoice.created` listener.
2. Tasks (behind `ENABLE_TASKS`): kanban, checklist, comments, labels, templates, auto-rules on events.
3. DMS: documents, types, lifecycles/transitions, templates (Thymeleaf + OpenPDF), relations/chain,
   sources, attachments, versions/snapshots, timeline, actions, print logs; seed DMS registry.
Exit: specs ported (documents 36); parity 0; web CRM kanban, tasks views, document screens work.
