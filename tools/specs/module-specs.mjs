#!/usr/bin/env node
// Writes the hand-maintained module specs docs/spec/<module>.md from the data below, merged with
// counts from docs/spec/routes.json. Edit the data here (or edit the .md directly and delete the
// marker line to take ownership). Run: node tools/specs/module-specs.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const routes = JSON.parse(fs.readFileSync(path.join(root, 'docs/spec/routes.json'), 'utf8'));
const MARK = '<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->';

const S = {
  kernel: {
    title: 'Kernel (platform)',
    phase: 'P1',
    nest: 'src/kernel, src/health, src/observability',
    purpose: 'Identity, staff, tenancy, authorization, idempotency, settings, audit, events/outbox, approvals, files, notifications, feature flags & module loader, recurring documents, webhooks, global search, reports access, three-way-match service, sequences, workflow/lifecycle registries, health and metrics.',
    areas: [
      ['auth', '`/auth/*`: login, mfa-login, refresh, forgot/reset/change password, mfa enroll/verify/disable, me, sessions (list/revoke), permissions catalog'],
      ['staff', '`/roles` CRUD; `/users` CRUD + reset-password, PIN set/clear, unlock'],
      ['settings', '`/settings*`: scoped key/value (org/branch/user) with registry + resolver (`setting-registry.ts`, `setting-resolver.service.ts`), company, features, developer, inventory-defaults, effective view'],
      ['approvals', '`/approvals`, `/approval-policies`, `/approval-workflows`: multi-step approval engine used by expenses, procurement, cash variance'],
      ['audit', '`/audit-logs` read; `AuditService.log(entity, entityId, action, old, new)` called by every write service'],
      ['files', '`/files`: upload (multipart), list, signed URL, download, delete'],
      ['notifications', '`/notifications`, `/push`: in_app/email/sms/push, preferences, VAPID subscribe'],
      ['feature flags / modules', '`/feature-flags`, `/modules/*`: per-org module enablement (`OrganizationModule`), `@RequiresModule` guard'],
      ['recurring', '`/recurring`: recurring documents with pause/resume/end; tick job every 10 min'],
      ['webhooks', '`/webhooks/endpoints`, deliveries; HMAC-signed delivery, retry job every minute, secret rotation'],
      ['search', '`GET /search` global search across partners/products/documents'],
      ['reports access', '`/reports/access/*`: which report keys (`packages/shared/src/reports.ts`) each role may see'],
      ['three-way match', '`/procurement/three-way-match/*` (service lives in kernel in Nest; port into procurement module, keep paths)'],
      ['health', '`/health`, `/health/live|ready|startup` (done in P0)'],
    ],
    rules: [
      'Login requires `organizationCode`; user must belong to that org, be active, not locked. 10 failed attempts → `lockedUntil = now + 15 min`; successful login resets the counter. Record every attempt in `LoginAttempt` (ip, user agent, success, reason).',
      'MFA: if `mfaEnabled`, login returns `{ mfaToken, requiresMfa: true }`; the mfaToken (24 random bytes, base64url) is stored hashed as a `Setting` row `mfa:<sha256>` with short expiry; `mfa-login` validates TOTP then issues tokens.',
      'Refresh token rotation: each refresh marks the old row `replacedById`; presenting an already-replaced token revokes the whole chain (reuse detection) and returns 401.',
      'POS session check (`pos-session-check.ts`): valid `X-Pos-User` overrides actor; revoked/deactivated POS user → 401 + `X-Pos-Session: revoked` except on PIN routes.',
      'POS eligibility (`pos-eligibility.ts`): which roles/users may PIN-login on a terminal (port spec `pos-eligibility.spec.ts`).',
      'Role policy (`role-policy.spec.ts`): system roles cannot be deleted/renamed; a user cannot grant permissions they do not hold; last administrator cannot be removed.',
      'Permissions guard requires ALL listed permissions; reads permissions from DB unless `PERMISSIONS_DB_LOOKUP=false`.',
      'Idempotency: see CONTRACT.md §6; port `idempotency.service.ts`, `business-outcome.ts` and both specs exactly.',
      'Tenancy: port `tenancy.extension.ts` semantics — which models are org-scoped, soft-delete filter, and the explicit escape hatches (`raw` client) — into the Hibernate filter setup.',
      'Sequences: per-org per-document-type Postgres sequences with optional yearly key `:YYYY`; formatted `prefix + padStart(padding, "0")` (`sequence.service.ts`).',
      'Read-only middleware: `READ_ONLY_MODE=true` rejects non-GET.',
      'Rate limiting: `StaffAwareThrottlerGuard` 100/60 s, 600/10 min keyed by staff id when authenticated.',
      'Encryption service: AES-GCM with IV + tag columns for MFA secrets — must decrypt existing data.',
    ],
    events: 'Emits `user.registered`, `user.logged_in`, approval events; outbox worker ships `EventOutbox` rows to webhooks.',
    jobs: 'outbox-ship 30 s, webhooks-retry 1 min, recurring-tick 10 min, cleanup-notifications hourly, cleanup-tokens daily.',
    gotchas: [
      'Access tokens must NOT carry permissions (header size); login body still returns `permissions[]`.',
      'Seed (`SeedRunner`) belongs to this phase so the web login works on a fresh Spring DB.',
      'Organization bootstrap (`POST /organizations/bootstrap`) lives in core but creates kernel rows (roles, admin user, settings) — coordinate.',
    ],
  },
  core: {
    title: 'Core master data',
    phase: 'P2',
    nest: 'src/modules/core',
    purpose: 'Organizations, branches, partners (customers/suppliers/employees) with contacts and addresses, products (variants, packaging, UoM conversions, barcodes), product categories, taxes, units of measure, fiscal periods/positions, payment terms.',
    areas: [
      ['organizations', 'bootstrap (create org + admin), accept-invite, me, me/settings, me/branch, users, invite, deactivate'],
      ['branches', 'CRUD; user default branch'],
      ['partners', 'CRUD + categories, contacts, addresses; flags isCustomer/isSupplier/isEmployee; credit limit/hold; opening balance; receivable/payable account overrides; membership tiers'],
      ['products', 'CRUD, `search`, `resolve-barcode`, restore; variants, packaging, UoM conversions, brand, costing method, tax, reorder levels'],
      ['uom', '`/uoms` CRUD + `POST convert`; `/uom-categories` CRUD; conversion factors exact (BigDecimal)'],
      ['taxes', 'CRUD; inclusive/exclusive, rate, accounts'],
      ['fiscal', '`/fiscal-periods` CRUD (core wins over accounting duplicate, see CONTRACT.md §1); `/fiscal-positions` CRUD + deleted + restore; `/payment-terms` CRUD + deleted + restore'],
    ],
    rules: [
      'Codes (partner/product) unique per organization; error messages verbatim from services.',
      'Product delete is soft and blocked when stock or open documents reference it (check service).',
      'Barcode resolution order: product barcode → variant barcode → packaging barcode (`resolve-barcode`).',
      'Partner credit: `creditHold` blocks credit sales in POS (fail closed); `creditLimit` checked against open AR.',
      'Emits `partner.created/updated/deleted`, `product.created/updated/deleted`.',
    ],
    events: '`partner.*`, `product.*` (consumed by sync pull watermarks, CRM, search).',
    jobs: '—',
    gotchas: ['`organizations/bootstrap` is public in some flows — check `@Public()` in the route table.', 'UoM conversion rounding uses 6 dp (`qtyMul`).'],
  },
  accounting: {
    title: 'Accounting, treasury and cash',
    phase: 'P2',
    nest: 'src/modules/accounting',
    purpose: 'Chart of accounts and categories, account mappings (posting keys → accounts), journals and journal entries (post/reverse), cost centers, currencies and FX rates/revaluation, fiscal period close/lock/reopen, bank accounts, treasury transfers, bank reconciliation, financial reports and snapshots, money operations (`accounts/cash-flow`), POS payment methods, cash registers, cash sessions (shifts), tender settlements.',
    areas: [
      ['chart of accounts', '`/accounts` CRUD, tree, reorder, deprecate; `/account-categories` read + usage; `/account-mappings` registry/missing/PUT'],
      ['journals', '`/journals` CRUD; `/journal-entries` CRUD + post + reverse; posting engine in `accounting/posting`'],
      ['periods', '`/fiscal-periods/:id/close|lock|reopen` (period close checks)'],
      ['fx', '`/currencies`, rates, revaluation (`FxRevaluation`)'],
      ['bank', '`/bank-accounts`, `/treasury/transfer` (idempotent), `/bank-reconciliation` import/match/unmatch/status'],
      ['reports', '`/reports/accounting/*` (trial balance, GL, account ledger, P&L, balance sheet (+detailed), cash flow, tieout, pos-gl-reconciliation, rebuild snapshots), `/reports/export/*.csv|pdf`, `/reports/inventory/valuation|gl-tieout`, `/reports/dashboard-kpi|dashboard-activity`'],
      ['money operations', '`/accounts/cash-flow`: overview, activity, operation types, transactions, deposit, withdraw (idempotent) — `money-activity*`, `money-overview`, `money-settlement`'],
      ['payment methods', '`/accounts/pos-payment-methods` CRUD (tender → account)'],
      ['cash registers', '`/cash-registers` CRUD + drawer balance'],
      ['cash sessions', '`/cash-sessions/*`: open, close, movement, force-close, banking, reconcile, daily report/reconcile, variance, expected, movements, history, tender-settlements'],
    ],
    rules: [
      'Journal entry must balance (Σdebit = Σcredit, `approxEqual` 0.0001) and date must fall in an open period; `postingKey` unique per org makes postings idempotent; reversal creates a mirrored entry linked by `reversalOfId`.',
      'Account mappings: every automatic posting resolves accounts through mapping keys (`packages/shared/src/accounting/account-mappings.ts`); missing mapping → error listing the key (verbatim).',
      'Period close/lock: closed periods reject postings; lock is irreversible except `reopen` with permission `fiscal_period:reopen`.',
      'Cash session open: register must have its own active drawer cash account; opening count may not be below the drawer ledger balance (unless `autoAdjust`); added float requires a funding account (safe/bank) with sufficient recorded funds and a reason.',
      'Movements: `pay_in | pay_out | adjustment`, non-zero amount, reason, counterpart account, cannot overdraw the drawer, only the session owner (or manager); pay_out requires manager sign-off (`approvedById`/`managerPin`); corrections to a closed shift = adjustment with `correctionOfSessionId` and permission `cash_session:correct`.',
      'Close: expected cash computed per payment method; counted cannot be negative; a variance needs a reason and approval from a holder of `cash_session:approve_variance` (manager PIN); `autoAdjustCash` records the difference as pay-in/pay-out; closing first drains pending stock postings and is blocked by open sync dead letters; variance and bank deposit post to GL.',
      'Force-close: requires `cash_session:force_close`, a reason, and must target another cashier\'s shift. Any holder of `cash_session:close` may close an open shift on a shared terminal (recent change, commit 93c3414).',
      'Handover (`/pos/shift/handover`): different incoming cashier; approving manager must be neither cashier and hold `cash_session:approve_variance`.',
      'Closing amounts visibility is controlled by a setting (commit 17126b5) — respect it in responses.',
      'Status machine: `open → closed → reconciled`.',
    ],
    events: '`journal.posted`, `journal.reversed`, `cash.received`, `cash.paid`, `bank.transfer`, `cash.session.opened/closed`, …',
    jobs: 'report snapshot cron (`snapshot-cron.worker.ts`).',
    gotchas: ['Port `accounting/treasury` specs (5) and `accounting/posting` specs (3) first — they pin the cash rules.', 'Trial balance / P&L queries are heavy SQL: port the raw SQL as-is via `JdbcClient`.'],
  },
  inventory: {
    title: 'Inventory',
    phase: 'P3a',
    nest: 'src/modules/inventory',
    purpose: 'Locations, stock items and levels, append-only inventory ledger, costing (AVCO, FIFO layers, STANDARD, SPECIFIC/serial), batches/serials/expiry, direct stock in/out, stock-outs, waste, adjustments, transfers (dispatch/receive/recall), stock counts, reservations/ATP, GL posting rules, inventory reports.',
    areas: [
      ['locations', 'CRUD (warehouse, register location, bar, kitchen)'],
      ['stock', '`direct-stock/in|out`, items, item by product, stats, product-stock-levels, ledger'],
      ['documents', 'stock-outs, waste (+summary), adjustments, transfers — create/list/approve/cancel/reverse; transfers dispatch/receive/recall'],
      ['counts', '`/inventory/counts`: preview, start, draft, submit, review, cancel'],
      ['reservations', '`/inventory/reservations`: create, release, consume, atp'],
      ['posting rules', '`/inventory/posting-rules`: CRUD, overview, movement types, resolve-preview, restore defaults (`packages/shared/src/inventory-posting-rules.ts`)'],
      ['reports', 'expiring, reorder, movements, item-movements, valuation, movement-analysis, register/:kind, reconciliation, stock-health, negative-stock'],
    ],
    rules: [
      'Every quantity change goes through `StockService` (receive/issue/transfer/adjust) and writes one `InventoryLedger` row: `qtyBefore`, `quantityChange`, `balanceAfter`, `unitCost`, `type` (StockMoveType), `referenceType/Id`, `ledgerCode`. Ledger is append-only (DB guards in V1 enforce).',
      'AVCO: lock the `StockItem` row (`FOR UPDATE`), new avg = (qty×avg + inQty×inCost)/(qty+inQty) at 6 dp; issues at current avg. FIFO consumes `InventoryBatch` layers oldest first. STANDARD uses product standard cost (variance to GL). SPECIFIC uses serial cost.',
      'Stock period lock: movements dated in a locked period are rejected (verbatim message).',
      'Negative stock: allowed/blocked per settings and POS stock policy (`block|warn|silent`).',
      'Reversals create opposite moves (`reversal_in/out`) linked to the original; double reversal rejected.',
      'GL posting per movement type via `InventoryPostingRule` (debit/credit account resolution, overridable per location/category).',
      'Counts: submitted count posts adjustments for differences; review shows variance value.',
    ],
    events: '`stock.received`, `stock.issued`, `stock.adjusted`, … (see `DomainEvents`).',
    jobs: '— (POS stock posting drain lives in pos).',
    gotchas: ['Port `stock.service.ts` with all locking; port the 28 inventory spec files listed in generated spec.', '`STOCK_POSTING_BATCH` env controls drain batch size.'],
  },
  procurement: {
    title: 'Procurement',
    phase: 'P4',
    nest: 'src/modules/procurement (+ kernel/three-way-match)',
    purpose: 'Purchase requests, purchase orders, goods receipt notes (incl. ad-hoc), landed costs, debit notes, three-way match (PO/GRN/bill), GRNI report.',
    areas: [
      ['purchase requests', 'CRUD, submit, approve, reject (approval workflows)'],
      ['purchase orders', 'CRUD, receive, pay, cancel, activate; `version` optimistic lock'],
      ['goods receipts', 'adhoc, create, post (stock receipt + GRNI accrual), reverse'],
      ['landed costs', 'CRUD, post, cancel'],
      ['debit notes', 'CRUD, post, cancel (return to supplier)'],
      ['three-way match', 'GET, recompute/:orderId, validate-bill/:billId'],
      ['reports', 'GRNI'],
    ],
    rules: [
      'PO status machine: draft → submitted → approved → sent → acknowledged → partially_received → received → billed → closed; cancelled; `active` for blanket orders.',
      'Receipt quantities cannot exceed ordered (tolerance per settings); posting a GRN increases stock (inventory) and credits GR/IR (GRNI).',
      'Landed cost only on a posted GRN; charge total > 0; allocation by value | quantity | equal; share for units still on hand raises AVCO and debits Stock Valuation; share for units already consumed is expensed to COGS; STANDARD-cost items expense the whole share; posting period must be open; code prefix `LC-`.',
      'Three-way match: bill qty/price vs GRN vs PO within tolerances; override requires `three_way_match:override`.',
    ],
    events: '`goods_receipt.posted`, `purchase_order.*`, …',
    jobs: '—',
    gotchas: ['Landed-cost frontend panel: `web/src/features/procurement/landed-cost-panel.tsx`.'],
  },
  invoicing: {
    title: 'Invoicing, payments, AR/AP',
    phase: 'P3b',
    nest: 'src/modules/invoicing',
    purpose: 'Customer invoices, credit notes, vendor bills (+ receipt matching, supplier ledger), customer payments with allocations and withholding, supplier payments, tax calculation, AR/AP reports.',
    areas: [
      ['invoices', 'CRUD, journal-preview, post, cancel'],
      ['credit notes', 'CRUD, post'],
      ['vendor bills', 'CRUD, post, cancel, supplier ledger'],
      ['payments', 'list/get/create/void (idempotent), allocations; supplier payments'],
      ['reports', 'AR aging, customer balances, outstanding, revenue by customer/period; AP aging'],
    ],
    rules: [
      'Posting an invoice creates its journal entry (AR / revenue / tax) in the same tx; posted invoices are immutable except cancel (reversal).',
      '`amountResidual` = total − paid − credited; `PaymentStatus` not_paid/partial/paid/overpaid derived exactly as Nest.',
      'Payment allocation cannot exceed residual; void reverses allocations and GL.',
      'Tax calc: inclusive/exclusive per tax, rounding per line then total as Nest (`tax` service).',
    ],
    events: '`invoice.created/posted/paid/cancelled`, `creditnote.issued`, `payment.received/allocated`.',
    jobs: '—',
    gotchas: ['POS invoices are created by the pos module through these services (`pos/billing`).'],
  },
  expenses: {
    title: 'Expenses',
    phase: 'P3b',
    nest: 'src/modules/expenses',
    purpose: 'Expense categories and expenses with approval, payment (from cash drawer, bank, or petty cash), void, audit trail and stats.',
    areas: [['categories', 'CRUD'], ['expenses', 'CRUD, stats, meta/accounts, meta/suppliers, audit, approve, reject, pay (idempotent), void (idempotent)']],
    rules: ['Status: draft → pending approval → approved → paid; rejected; void reverses GL and cash.', 'Paying from a cash drawer creates a cash movement in the open session and cannot overdraw.'],
    events: '`expense.*`',
    jobs: '—',
    gotchas: ['Web uses `web/src/lib/api/*` for expenses.'],
  },
  pos: {
    title: 'POS (cafe vertical)',
    phase: 'P5',
    nest: 'src/modules/pos',
    purpose: 'Terminal checkout, open tabs per table, orders with kitchen firing, split bills, table management (merge/transfer/split), reservations, menu (categories, items, variants, recipes, accompaniments), modifiers and combos, kitchen stations and KDS, holds, PIN login and manager overrides, shift handover, loyalty, store credit and customer tabs, stock posting jobs and monitor, receipts/bills/KOT printing (ESC/POS), POS reports (X/Z and 15 others), digital QR menu with public ordering.',
    areas: [
      ['terminal', '`/pos`: settings, operation-approval, payment accounts/methods, checkout (idem), sales/:id/void (idem), lookup'],
      ['tabs', '`/pos/tabs/:tableId`: get, items (append), save (version lock), fire-kitchen, settle (idem); `/pos/orders/:id/resume|settle`'],
      ['orders', '`/pos/orders`: list, by-table, open, get, milestones, quote, create, PUT/POST items, void item, fire-kitchen, move, merge, cancel, reopen, invoice'],
      ['billing', '`/pos/invoices/:id/payments|credit|refund|write-off`'],
      ['split bills', '`/pos/tabs/:tableId/split*`, `/pos/split-bills/:billId/*` (item/even/percent)'],
      ['tables', '`/pos/tables`: CRUD, stats, stream (SSE), status, assign-waiter, merge/unmerge, transfer, transfer-items, split-bill, attach-sale; zones CRUD + restore'],
      ['reservations', '`/pos/reservations`: CRUD, seat, cancel, no-show, complete (+ crons)'],
      ['menu', '`/pos/menu`: categories, items (availability, bundle, hard delete, restore), variants, accompaniment groups/options + sales report; QR sessions and online orders; `/menu/public/*` (public)'],
      ['modifiers', '`/pos/modifiers`: groups, modifiers, combos, product/menu-item bundles, sales report'],
      ['kitchen', '`/pos/kitchen-stations` CRUD/seed/reorder; `/pos/kds` tickets, stream (SSE), transitions, priority, assign, send-to-kitchen; `/pos/kds/reports`'],
      ['staff on terminal', '`/pos/auth` staff, pin-login, logoff, change-pin/password; `/pos/override` pin/verify; `/pos/shift/handover` (idem)'],
      ['holds', '`/pos/holds`: create, list, get, recall, delete, notes'],
      ['loyalty & credit', '`/pos/loyalty/*` program/balance/earn/redeem, store credit issue/redeem, customer tab open/charge/settle; `/pos/customers/:id/statement|credit`; `/pos/credit/open`'],
      ['posting monitor', '`/pos/posting-monitor`: counts, jobs, retry, exceptions assign/resolve'],
      ['reports', '`/pos/reports/*` (x-report, z-report, sales-by-hour, top-items, sales-summary, sold-items, sales-report, order-report, cashier-report, cashier-shift-summary, waiter-report, item-sales, items-by-server, items-by-group, filter-options), tables utilization/revenue, reservations'],
      ['receipts', '`/pos/receipts/*`: list, get, pdf, text, print, email, reprint, print-bill, print-additional-bill, reprint-bill, print-kot, print-closing-statement, receipt settings'],
    ],
    rules: [
      'Every order/checkout requires an open cash session for the register (verbatim error).',
      'Order mutation policy (`order/order-mutation-policy.ts`): removing lines already sent to the kitchen (void, cart edit, cancel, empty save, item transfer) requires `pos:void`, a meaningful reason (≥ 3 chars, not a placeholder like "n/a"), and a manager PIN override with `pos:override` (grant bound to the operation key).',
      'Billed orders cannot be edited or reopened; only cancelled orders can be reopened.',
      'Merge (tables or orders): both open and unbilled, no order-level discount on the merged order, no split bills in progress, same open session, not into itself, not onto an out-of-service table. Whole-order void lives in the "More" menu (commit a6444fa).',
      'One open tab per table (DB constraint) — map violation to the Nest message. `PUT /orders/:id/items` uses `version` (409 on mismatch); offline replay uses append-only `POST items`.',
      'Line signature (`order/line-signature.ts`): identity of a line = item + variant + modifiers + accompaniments + notes; used to merge quantities and compute KOT deltas.',
      'Pricing policy (`pricing-policy.ts`): discount authority by permission/limits, price override needs `pos:price_override`; credit sales check `creditHold`/`creditLimit` and fail closed.',
      'Stock policy per item/setting: `block` rejects when insufficient, `warn` returns warning, `silent` proceeds. Stock is posted asynchronously: `StockPostingJob` with recipe snapshot (`InvoiceItemRecipeIngredient`), drained every 30 s with claim token + SKIP LOCKED, retries, failures → `InventoryException` (posting monitor).',
      'Refund/void: reverse GL, restock per recipe snapshot, take cash out of the drawer (cash movement), write `PosRefund`; partial line refunds online only.',
      'Printing lifecycle (`pos-print-lifecycle.service.ts`): copy numbers, `kotPrintedQty`/`billPrintedQty` deltas, cancel-KOT on void after firing, closing statement asked inside shift-close dialog (commit fb03b13).',
      'KDS ticket status machine: new → preparing → ready → served; cancelled. Bulk transitions validate each ticket.',
      'Reservations: no-show job marks overdue reservations; seat links reservation to table/order.',
      'Table status: available, occupied, reserved, out_of_service (cleaning removed by last migration — check).',
      'Receipts settings and printer config are `Setting` keys (`pos.printerHost`, `pos.printerPort`, `pos.printerWindowsName`, ...).',
      'System connection mode "offline (local-only)" skips connectivity checks (commit 4ad9735) — a setting the client reads.',
    ],
    events: '`order.*`, `invoice.*`, `kitchen.ticket.*`, `table.*`; table and KDS SSE streams are fed from after-commit events.',
    jobs: 'reservation-no-show 1 min, reservation-refresh 5 min, stock-posting-drain 30 s, stock-posting-health 5 min.',
    gotchas: [
      'Largest module (219 routes, 21k LOC). Split into PRs: settings/lookup → menu/modifiers → tables/zones/reservations → orders/tabs → checkout/billing → split bills → KDS → receipts/printing → loyalty/credit → reports → posting monitor → public menu.',
      'Read `docs/legacy/POS_TRANSACTION_FLOW.md` and `docs/legacy/POS_RUNBOOK.md` before starting.',
      'Port the 37 POS spec files; then run `reference/e2e/pos-sell-loop.spec.ts` against Spring.',
    ],
  },
  sync: {
    title: 'Offline sync',
    phase: 'P6',
    nest: 'src/modules/sync',
    purpose: 'Device registration and revocation, incremental pull of catalog/state per scope, batched push of offline operations with per-op idempotency, dead letters.',
    areas: [['devices', 'register, list, revoke'], ['pull', '`GET /sync/pull?cursor=&scopes=`'], ['push', '`POST /sync/push`'], ['dead letters', 'list, counts, resolve']],
    rules: [
      'Authority: the LAN Postgres is the source of truth; "a sale must never be blocked" — ambiguous cases apply the sale and flag the discrepancy.',
      'Pull: opaque base64 JSON cursor with one server-clock watermark per scope; returns rows with `updatedAt > watermark − 5 s`; tombstones via `deletedAt`. Delta scopes: menuItems (aggregate), menuCategories, modifierGroups, taxes, posTables, cashRegisters, staff (incl. bcrypt pinHash), settings, products, productCategories, productPackagings, partners, reservations, stockLevels, suppliers. Full every time: paymentMethods, ledgerAccounts (with roles + balances), expenseCategories, stockLocations.',
      'Push: ops strictly increasing `deviceSeq`; each op runs through `IdempotencyService.executeWithKey(opId)` with the actor\'s current permissions and calls the same services as the online endpoints; result per op `{ opId, status, httpStatus, error, mapping, finalNumbers }`; client-minted ids mapped to server ids; dependent op whose dependency failed → 424; `provisionalNumber` (e.g. `D1-000042`) replaced by final numbers; failures → `SyncOpDeadLetter`; open dead letters block cash-session close; device `lastPushSeq` tracked.',
      'Op types: `SYNC_OP_TYPES` in the Nest source (cash_session.open/close/movement, sale.checkout, tab.settle, sale.refund, sale.void, reservation.*, customer/setting/menu*/modifierGroup/accompanimentGroup/tax/product/productCategory/cashRegister/posTable upsert|delete, staff.pinChange, message.send/markRead, stock.in/out/count, expense.create).',
    ],
    events: '—',
    jobs: '—',
    gotchas: ['Spec: `docs/legacy/sync-protocol.md`. Android client in the legacy repo `apps/android` uses Retrofit against these exact shapes.'],
  },
  documents: {
    title: 'Documents (DMS)',
    phase: 'P3c',
    nest: 'src/modules/documents',
    purpose: 'Generic document registry: documents with lines, types and lifecycles (state machines), templates, relations and relation chains, sources, attachments, versions/snapshots, timeline, actions, print logs, PDF/HTML preview and printing.',
    areas: [['documents', 'list, get, patch, preview, print, timeline, actions/:action'], ['templates', 'list, create, activate'], ['relations', 'list, chain, create, delete'], ['attachments/sources', 'per document'], ['types/lifecycles', 'CRUD']],
    rules: ['Lifecycle transitions validated against `DocumentLifecycle`/`DocumentTransition`; each transition writes `DMSWorkflowLedger` + `LifecycleEvent`.', 'Template rendering: Nest uses a headless browser pool; Spring uses Thymeleaf → HTML and OpenPDF (or Playwright-Java if pixel parity is required — ADR needed).', 'DMS registry seeded by `seedDmsRegistry` (port into SeedRunner).'],
    events: 'document lifecycle events.',
    jobs: '—',
    gotchas: ['36 spec files — port them; they define lifecycle semantics.'],
  },
  crm: {
    title: 'CRM',
    phase: 'P3c',
    nest: 'src/modules/crm',
    purpose: 'Deals pipeline, activities, analytics.',
    areas: [['deals', 'CRUD, deleted, stage, restore'], ['activities', 'CRUD, upcoming, deleted, complete, restore'], ['analytics', 'pipeline, win-rate, forecast, top-customers, partner/:id']],
    rules: ['Stages lead → qualified → proposal → negotiation → won | lost.', 'Moving to won marks the partner as customer; a won deal cannot be deleted.', 'Listener on `invoice.created` links invoices to open deals of the partner.'],
    events: 'consumes `invoice.created`.',
    jobs: '—',
    gotchas: ['Kanban UI: `web/src/features/crm/kanban.tsx`.'],
  },
  task: {
    title: 'Tasks',
    phase: 'P3c',
    nest: 'src/modules/task',
    flag: 'ENABLE_TASKS',
    purpose: 'Kanban tasks with checklists, comments, labels, templates, recurring templates, auto-rules triggered by domain events, verification, dashboard.',
    areas: [['tasks', 'CRUD, dashboard, reorder, checklist item toggle, comments, verify'], ['labels/templates/auto-rules', 'CRUD']],
    rules: ['Status: DRAFT, PENDING, ASSIGNED, IN_PROGRESS, WAITING, REVIEW, VERIFIED, COMPLETED, CANCELLED, SKIPPED, OVERDUE (uppercase DB values).', 'Verification only when `requiresVerification`; verifier must hold `task:verify` and differ from assignee if Nest enforces.', 'Auto-rules subscribe to events and create tasks from templates.'],
    events: 'consumes many domain events (auto-rules).',
    jobs: 'recurring task generation (check service).',
    gotchas: [],
  },
  backup: {
    title: 'Backup',
    phase: 'P6',
    nest: 'src/modules/backup',
    purpose: 'Full/incremental/files/config backups, encryption, checksums, secondary + offsite copies, retention, restore, restore verify, restore test into a scratch DB, health status, schedules, notifications.',
    areas: [['admin', '`/admin/backups`: status, health, list, settings (GET/PUT), full, incremental, files, config, cleanup, restore, restore/verify, restore/test']],
    rules: ['Shell out to `pg_dump`/`psql`/`pg_restore` via `ProcessBuilder` (PG_BIN), never through a shell; stream output, timeouts, exit-code checks.', 'Encryption AES-256-CBC with PBKDF2-SHA256 600,000 iterations, `Salted__` header — byte-compatible with `openssl enc -aes-256-cbc -pbkdf2 -iter 600000` (port exactly; existing backups must decrypt).', 'Verify: archive entry count + sha256; restore test into scratch DB and compare row counts.', 'Retention cleanup, disk-space check, healthchecks.io ping, email on failure, dynamic cron from settings.', 'Backup health feeds `/health/ready` advisory checks.'],
    events: '—',
    jobs: 'dynamic backup schedule.',
    gotchas: ['Recent hardening (Oct 2026) in the Nest module — port the latest code in `reference/`, not older docs.'],
  },
  beverage: { title: 'Beverage (bar control)', phase: 'P7', nest: 'src/modules/beverage', flag: 'ENABLE_BEVERAGE', purpose: 'Bottle weigh-count sessions and bar variance, yield, bartender and dashboard reports.', areas: [['counts', '`/beverage/counts`'], ['reports', 'variance, yield, bartender, dashboard']], rules: ['Bottle math in `kernel/common/beverage-math.ts` (weight → volume via measurements) — port with its tests.'], events: '—', jobs: '—', gotchas: [] },
  fixedasset: { title: 'Fixed assets', phase: 'P7', nest: 'src/modules/fixed-asset', flag: 'ENABLE_ASSETS', purpose: 'Asset register with categories, acquisition, assignment, check-in/out, depreciation runs and posting, disposal, inspections, insurance, maintenance, repairs, revaluations, transfers, warranties, dashboard.', areas: [['assets', 'CRUD, search, by-code, by-barcode, restore'], ['sub-resources', '`/fixed-assets/:assetId/*` (12 kinds)'], ['dashboard', '`/fixed-assets-dashboard/*`'], ['categories', '`/asset-categories`']], rules: ['Depreciation methods and GL posting exactly as the Nest depreciation service; posted runs immutable.', 'Disposal posts gain/loss.'], events: '—', jobs: '—', gotchas: ['No Nest specs — write tests from the service logic.'] },
  hr: { title: 'HR and payroll', phase: 'P7', nest: 'src/modules/hr', flag: 'ENABLE_HR', purpose: 'Departments, positions, employees (lifecycle, documents, transfers, PIN/user link), shifts and assignments, attendance and logs, timesheets, leave types/balances/requests, holidays, payroll (components, tax tables/brackets, periods, runs, items, payslips, bank payments), salary advances, loans, performance reviews, training, self-service and team views, reports.', areas: [['~140 routes', 'see generated table']], rules: ['Payroll calculation: gross → allowances → deductions → tax by bracket → net; rounding as Nest; run states and GL posting (HR accounts backfill script exists).', 'Leave balance accrual and request approval.'], events: '—', jobs: '—', gotchas: ['Split into PRs: org structure → employees → time (shifts/attendance/timesheets) → leave → payroll → loans/advances → reviews/training → self-service → reports.'] },
  manufacturing: { title: 'Manufacturing', phase: 'P7', nest: 'src/modules/manufacturing', flag: 'ENABLE_MANUFACTURING', purpose: 'BOMs, production requests/plans, production orders (confirm, start, complete, QC, cancel, reverse), material consumption and outputs, cost components, work centers, resources, routings, work orders, MRP, reports.', areas: [['~42 routes', 'see generated table']], rules: ['Completing a production order consumes materials (production_consume) and receives outputs (production_output) through StockService with cost roll-up; reverse undoes both.', 'WIP account handling (backfill-wip-account script).'], events: '—', jobs: '—', gotchas: [] },
  rental: { title: 'Rental', phase: 'P7', nest: 'src/modules/rental', flag: 'ENABLE_RENTAL', purpose: 'Rental catalog, rates, packages, units, availability, reservations, agreements (checkout, deposits collect/refund/forfeit), extensions, swaps, returns (inspect, settle), damages, service orders, customer scores, reports.', areas: [['~47 routes', 'see generated table']], rules: ['Availability excludes overlapping reservations/agreements per unit.', 'Deposit ledger movements post to GL (rental accounts backfill).'], events: '—', jobs: 'rental-housekeeping 1 min.', gotchas: [] },
  repair: { title: 'Repair', phase: 'P7', nest: 'src/modules/repair', flag: 'ENABLE_REPAIR', purpose: 'Repair orders and items, diagnosis, quotations, jobs, parts, labour types, technicians, schedules, warranties and claims, service contracts, attachments, notes, billing, dashboard, reports.', areas: [['~64 routes', 'see generated table']], rules: ['Status history recorded on every transition (`RepairStatusHistory`).', 'Billing creates an invoice through invoicing services.'], events: '—', jobs: 'repair-housekeeping 10 min.', gotchas: [] },
  orders: { title: 'Orders (generic sales orders)', phase: 'P7', nest: 'src/modules/orders', flag: 'ENABLE_ORDERS', purpose: 'Non-POS sales orders: settings, CRUD, items, cancel, reopen, invoice.', areas: [['~11 routes', 'see generated table']], rules: ['Shares Order/OrderItem tables with POS; reuse pos/invoicing services.'], events: '—', jobs: '—', gotchas: [] },
  communication: { title: 'Communication', phase: 'P7', nest: 'src/modules/communication', flag: 'ENABLE_COMMUNICATION', purpose: 'Conversations, messages, attachments, templates, rules, channels (internal, Telegram bot webhook, WhatsApp Cloud API), SSE stream, dispatch worker with rate limits.', areas: [['~22 routes', 'see generated table']], rules: ['Dispatch worker polls `CommunicationDispatch` (COMM_DISPATCH_POLL_MS), respects `WA_MIN_GAP_MS`/`WA_DAILY_CAP`.', 'Channel credentials encrypted with `COMM_ENCRYPTION_KEY`.', 'Telegram webhook is public, verified by channel secret.'], events: '—', jobs: 'communication dispatch poller.', gotchas: ['**Deviation (approved in plan):** Baileys (unofficial WhatsApp Web) has no Java port — support WhatsApp Cloud API only; `WHATSAPP_TRANSPORT=baileys` is rejected at startup with a clear message.'] },
};

const counts = {};
for (const r of routes) counts[r.module] = (counts[r.module] ?? 0) + 1;

for (const [m, s] of Object.entries(S)) {
  const out = [MARK, `# ${s.title} — \`${m}\``, ''];
  out.push('| | |', '|---|---|');
  out.push(`| Phase | ${s.phase} |`);
  out.push(`| Nest source | \`reference/nest-api/${s.nest.split(', ').join('`, `reference/nest-api/')}\` |`);
  out.push(`| Routes | ${counts[m] ?? 0} — full table: [generated/${m}.md](generated/${m}.md) |`);
  out.push(`| Feature flag | ${s.flag ? '`' + s.flag + '`' : 'always on'} |`);
  out.push(`| Java package | \`com.inventoryerp.${m}\` |`, '');
  out.push('## Purpose', '', s.purpose, '');
  out.push('## Functional areas', '', '| Area | Endpoints / scope |', '|---|---|');
  for (const [a, d] of s.areas) out.push(`| ${a} | ${d} |`);
  out.push('', '## Business rules to reproduce exactly', '');
  s.rules.forEach((r, i) => out.push(`${i + 1}. ${r}`));
  out.push('', 'Error messages: copy verbatim from the "Error messages" table in the generated spec.', '');
  out.push('## Events', '', s.events, '', '## Scheduled jobs', '', s.jobs, '');
  if (s.gotchas.length) {
    out.push('## Notes and pitfalls', '');
    for (const g of s.gotchas) out.push(`- ${g}`);
    out.push('');
  }
  out.push('## Porting checklist', '');
  out.push('- [ ] Read every file under "Source files to port" in the generated spec');
  out.push('- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited');
  out.push('- [ ] DTO records with Bean Validation mirroring class-validator rules');
  out.push('- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries');
  out.push('- [ ] Controllers: same paths/verbs/status/permissions/response shapes');
  out.push('- [ ] Events published/consumed as in Nest');
  out.push('- [ ] Jest specs ported; integration tests; authz + tenant isolation tests');
  out.push('- [ ] Parity scenarios `contract-tests/scenarios/' + m + '-*.json` — zero unexplained diffs');
  out.push('- [ ] Web screens for this module work against Spring (no console errors)');
  out.push('- [ ] `docs/PORT_STATUS.md` updated', '');
  out.push('## Deviations', '', '_None. Any deviation from Nest behaviour must be listed here with the owner\'s approval._', '');
  out.push('## Status', '', 'Not started.', '');
  const file = path.join(root, 'docs/spec', `${m}.md`);
  if (fs.existsSync(file) && !fs.readFileSync(file, 'utf8').startsWith(MARK)) continue;
  fs.writeFileSync(file, out.join('\n'));
}
console.log('module specs:', Object.keys(S).length);
