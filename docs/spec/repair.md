# Repair — `repair`

| | |
|---|---|
| Phase | P7 (roadmap item 0.2) |
| Nest source | `reference/nest-api/src/modules/repair` |
| Routes | 64 — full table: [generated/repair.md](generated/repair.md) |
| Feature flag | `ENABLE_REPAIR` → `app.modules.repair` (controller, services and cron are only created when it is `true`) |
| Java package | `com.inventoryerp.repair` |

## Purpose

Repair & Maintenance Management (RMMS): one engine for customer-item repair, own-asset maintenance, SLA contract
maintenance and preventive maintenance. Repair orders and items, diagnosis, quotations, jobs (work orders), parts,
labour types, technicians, preventive schedules, warranties and claims, service contracts, attachments, notes,
billing through the POS Order → Invoice spine, dashboard and reports.

## Functional areas

| Area | Routes | Service |
|---|---|---|
| Orders, items, attachments, notes, lifecycle transitions, technician assignment | 11 | `RepairService` |
| Diagnosis and quotations (create / submit / approve / reject / revise / delete) | 10 | `RepairDiagnosisService` |
| Jobs (create / update / start / test / complete / cancel / checklist) | 9 | `RepairJobService` |
| Parts (reserve / issue through the stock engine / delete / list) | 4 | `RepairPartsService` |
| Billing and payment | 2 | `RepairPostingService` |
| Labour types and technicians | 8 | `RepairCatalogService` |
| Warranties and claims | 5 | `RepairWarrantyService` |
| Service contracts and preventive schedules | 10 | `RepairContractService` |
| Dashboard and reports | 6 | `RepairReportsService` |

Shared data access (`RepairDb`): Prisma-shaped reads under the tenancy scope, Prisma input coercion for the untyped
bodies, row locks, JS number semantics on `BigDecimal`, in-app notifications.

## Business rules reproduced

1. Order lifecycle `repair.order` (registered with the kernel `LifecycleRegistry` by `RepairLifecycles`): received →
   diagnosis → waiting_approval → approved → repairing → testing (→ repairing on rework) → ready_pickup → delivered →
   closed; `cancelled` from any non-terminal state; closed / cancelled are terminal. Illegal transitions are plain
   errors in Nest → 500. A transition without `action` resolves the canonical action for (from → to).
2. Every order transition writes a `RepairStatusHistory` row and stamps the matching timestamp (`diagnosedAt`,
   `quotedAt`, `approvedAt`, `startedAt`, `testedAt`, `readyAt`, `deliveredAt`, `closedAt`, `cancelledAt`).
3. Numbers: `RPR-` (orders, 6), `QT-` (quotations, 6), `JOB-` (jobs, 6), `CTR-` (contracts, 6), `TECH-` (technicians,
   4), POS `OR-` (billing orders, 6).
4. Quotation lines: labour priced from the labour type (name, price), parts from the product (name, sales price); an
   explicit `unitPrice` wins; `amount = Math.round(qty × price × 100) / 100`; totals per kind; tax total 0. Approving
   moves a `waiting_approval` order to `approved` with a history row.
5. Parts: reserving moves no stock; issuing relieves stock through `StockService.issue` (inventory ledger
   `repair_part_issue` / repair order id, COGS journal Dr expense / Cr stock valuation) and flips the part to
   `issued`; issued parts cannot be deleted.
6. Billing: one POS Order (`transactionKind = repair`, `sourceDocumentType = repair_order`, `repairOrderId`) with one
   item per line (`repairOrderLineId` = repair order, `repairFeeType`), invoiced through
   `PosInvoiceService.generateInvoice` (sales journal Dr receivable / Cr revenue + output tax via the posting engine).
   Part lines carry `repairOrderLineId`, so the POS reservation and stock-posting paths skip them (stock was relieved
   at issue time). Walk-in orders bill against the `WALKIN` partner. Payment delegates to
   `PosInvoiceService.receivePayment` (payment journal Dr tender account / Cr receivable).
7. Warranties: `warrantyPeriodMonths` without `coverageEnd` derives the end with JS `setMonth` overflow semantics; a
   linked order records the period and expiry. A claim sets the warranty to `claimed`; a rejected claim sets it back
   to `active`; expired warranties accept no claim.

Error messages are copied verbatim from the generated spec's "Error messages" table.

## Events

None. Nest's repair module publishes no domain events; every flow sends an in-app notification (category `repair`).
Billing publishes the POS `pos.order.invoiced` / payment `pos.invoice.settled` events through the POS invoice service,
as in Nest.

## Scheduled jobs

`repair-housekeeping` (`RepairCronWorker`, every 10 minutes, ShedLock `repair-housekeeping`, off with
`app.jobs.enabled=false`), per organization: every active schedule with `nextDueAt <= now` generates a `preventive`
repair order (+ history row) and rolls forward by `intervalDays`; active warranties with `coverageEnd <= now` expire.

## Permissions

`repair:read`, `repair:create`, `repair:manage`, `repair:diagnose`, `repair:quote`, `repair:approve`,
`repair:assign`, `repair:work`, `repair:test`, `repair:parts`, `repair:warranty`, `repair:contract`,
`repair:schedule`, `repair:report` (`repair:deliver` / `repair:close` exist in the catalog but no route uses them, as
in Nest).

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned — none needed: services are JdbcClient SQL (generated entities are only the column catalog)
- [x] DTO records with Bean Validation mirroring class-validator rules — every Nest body and query is untyped (`any`),
      so they bind as maps and are coerced like JavaScript (no validation, like Nest)
- [x] Services: rules above + verbatim errors + transaction/locking boundaries (hardened, see Deviations 2–4)
- [x] Controllers: same paths/verbs/status/permissions/response shapes (money routes: Deviation 1)
- [x] Events published/consumed as in Nest (none of its own)
- [x] Jest specs ported (none exist); integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/repair-lifecycle.json` — written and kept in step with the routes;
      not yet run against a live Nest (expected diffs below)
- [x] Web screens call only served routes (code review of `web/src/pages/repair/*.tsx` and
      `web/src/features/repair/api.ts`; the shape mismatches the client already has against Nest are listed below);
      no browser walkthrough yet
- [ ] `docs/PORT_STATUS.md` updated (coordinator)

## Deviations (autonomous decisions, pending owner review)

1. **Idempotency on the money routes.** `POST /repair/orders/:id/bill` and `POST /repair/invoices/:invoiceId/pay`
   are `@Idempotent(required = true)` (AGENTS.md §7/§8). Nest has no `@Idempotent` there, so they answer **200**
   instead of 201 and **400** `An Idempotency-Key header is required for this operation so a retry can never charge
   twice.` without the header. The web client sends a key on every write, so the screens are unaffected.
2. **Write guards on untyped bodies.** Nest spreads request bodies into Prisma `data` (`PATCH` of diagnosis, jobs,
   labour types, technicians, warranties, contracts, schedules; `POST .../items`; the `extra` of a transition).
   - Every key must be a column of the model (Prisma's "Unknown argument" → 500, as in Nest); column names from a body
     are checked before any SQL is built.
   - `organizationId` can only name the caller's organization (Nest without RLS would move the row to another
     tenant; under its RLS policy the write fails) → 500.
   - Parent references in a body (`repairOrderId`, `quotationId`, `diagnosisId`, `warrantyId`, `jobId`) must name a
     row of the caller's organization, else the foreign-key error a missing parent gives (500). Nest would accept
     another tenant's parent, and the row would then appear in that tenant's unfiltered `include`s.
   - `POST /repair/orders/:id/notes` and `.../attachments` on an order of another organization answer 500 (the
     foreign-key error a missing order gives in both); Nest wrote the row onto the foreign order (201).
3. **No half-commits.**
   - Billing: Order, Invoice (with its journal) and the repair order's link and totals commit in one transaction with
     the repair order row locked and the billed state re-checked. In Nest a failing invoice left the Order created
     and the repair order marked billed, so it could never be billed again; two concurrent bills could both pass.
   - Part issue: stock relief and the part's flip to `issued` commit together under a row lock (Nest: two separate
     steps; a concurrent second issue relieved stock twice).
   - `POST /repair/warranties` with a missing / foreign order: the warranty rolls back with the failing order update
     (Nest kept an orphan warranty and answered 500). Claim creation / resolution update claim and warranty together.
   - Order transitions lock the order row and assert the lifecycle against the locked status; approving a quotation
     re-checks its status under a lock; the preventive cron generates each order, its history row and the schedule
     roll-forward in one transaction (a failure can no longer leave an order behind a still-due schedule).
4. **Exact decimals.** Quotation lines, parts and billing totals use `BigDecimal` (AGENTS.md §8). JavaScript's
   `Math.round(q × p × 100) / 100` is reproduced exactly (half-cent rounds towards +∞), without binary floating-point
   error: `1.005` rounds to `1.01` where Node gives `1`. A non-numeric `unitPrice` / `unitCost` (JS `NaN`) answers 500
   instead of storing `NaN`.
5. Notifications are sent after the transaction commits (same title, body and payload).

## Same as Nest, kept on purpose (owner may want these fixed after parity)

- Rejecting a quotation while its order is `waiting_approval` fails with 500 and rolls back: Nest asserts
  `waiting_approval --revision--> diagnosis`, which the lifecycle does not define (it has
  `waiting_approval --revision--> waiting_approval`). The web "Reject" button therefore fails in that state.
- After billing, the repair order's `totalAmount` is the pre-tax line total: Nest reads `invoice.grandTotal ??
  invoice.total`, which do not exist on `Invoice` (`taxTotal` does hold the invoice tax). Dashboard `revenue30` and
  the reports sum that value.
- Billing is allowed in any non-closed / non-cancelled status (the web offers it at `ready_pickup`); a labour line
  without `productId` resolves its billing product by `labourTypeId`, else by case-insensitive name, else (no
  description) the first labour type of the organization.
- `POST /repair/quotations/:id/submit` sets `pending` whatever the status (also on approved / cancelled quotations);
  quotations can be created on closed / cancelled orders; approving a quotation whose order is not `waiting_approval`
  only approves the quotation.
- Jobs enforce no order among pending / in_progress / testing; only completed and cancelled are terminal.
- `POST /repair/orders/:id/parts` without `productId` matches an arbitrary product of the organization and stores a
  part without product (issuing it then fails with `Part has no linked product — stock cannot be relieved`).
- Deletes: quotations are cancelled (`status: cancelled`); items, attachments and parts are hard-deleted and answer
  `{count}` (0 for an unknown id, never 404); labour types, technicians, contracts and schedules are soft-deleted.
- `POST /repair/orders/:id/notes` needs only `repair:read`.
- Only a rejected claim returns the warranty to `active`; approved / settled claims leave it `claimed`, and the cron
  only expires `active` warranties.
- Lists take `take` / `skip` (default 50, max 200; schedules 100 / 300); non-numeric values answer 500. The web sends
  `page` / `pageSize`, which are ignored. Unknown enum values in filters answer 500.
- The transition notification reads `"<partner> — undefined"` when the order has a partner and neither `note` nor
  `action` was sent; its payload carries `status` only for the `to` spelling.
- Web client vs Nest (client unchanged, behaves the same against Spring): the orders, jobs and warranties pages and
  the dashboard read `data.items` (Nest returns `{rows, total}` / plain arrays), so those lists render empty; the
  order page reads `order.quotation`, `order.history`, `order.technician`, `order.partner` (not included by
  `getOrder`) and treats `diagnosis` as an object (it is an array); the diagnosis form's `estimatedTimeHours` is
  ignored; the attachment form stores `fileId: ""`.

## Parity

Scenario `contract-tests/scenarios/repair-lifecycle.json` (fresh seed, `ENABLE_REPAIR=true` on both sides). Expected
diffs, all explained above:

| Step(s) | Nest | Spring | Why |
|---|---|---|---|
| `bill: no key (unknown order)` | 404 `Repair order not found` | 400 (key required) | Deviation 1 |
| `bill: bad line`, `bill`, `bill: replay`, `bill again`, `pay invoice`, `pay invoice again` | 201 (4xx unchanged) | 200 (4xx unchanged) | Deviation 1 |
| `bill: replay` | 400 `Repair order already billed` | 200, first response replayed | Deviation 1 |

## Tests

- `RepairMathTest` (unit): JS `setMonth` / `setDate` arithmetic, `take` / `skip` / date coercions, JS `Number()` on
  exact decimals, `Math.round` to cents, the write guards (unknown / injected keys, tenant hop).
- `RepairIT` (Postgres): catalog (labour types, technicians: defaults, filters, soft delete, 404); orders (items,
  history, search, attachments, notes, transitions, assignment, notifications); diagnosis and quotations (catalog
  pricing, exact totals, approve / reject / revise / delete, the lifecycle 500 kept from Nest); jobs (checklist,
  status guards); parts (reserve, issue through the stock engine with ledger row and balanced COGS journal, rollback on
  a failing relief, concurrent issue → exactly one relief); billing (guards, rollback of a failing invoice, Order /
  Invoice links, balanced sales journal, idempotency: missing key, replay, key reuse 409; walk-in; payment with
  balanced payment journal, replay, already paid); warranties and claims; contracts, schedules and the housekeeping
  cron (generation, roll-forward, no regeneration on the next run, warranty expiry); dashboard and reports; tenant
  isolation (reads and writes by id from another organization, cross-tenant parent references, tenant hop, unknown and
  injected keys); 401/403 matrix over all 64 routes. JaCoCo line coverage of `repair.application`: 97.9 %.

## Status

64/64 routes implemented and verified by `RepairIT` + `RepairMathTest` (2026-10-28). Open: parity run against a live
Nest, browser walkthrough of the repair screens, owner review of the deviations above.
