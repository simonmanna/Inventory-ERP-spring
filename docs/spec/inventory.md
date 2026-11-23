# Inventory — `inventory`

| | |
|---|---|
| Phase | P3a |
| Nest source | `reference/nest-api/src/modules/inventory` |
| Routes | 69 — full table: [generated/inventory.md](generated/inventory.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.inventory` |

## Purpose

Locations, stock items and levels, append-only inventory ledger, costing (AVCO, FIFO layers, STANDARD, SPECIFIC/serial), batches/serials/expiry, direct stock in/out, stock-outs, waste, adjustments, transfers (dispatch/receive/recall), stock counts, reservations/ATP, GL posting rules, inventory reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| locations | CRUD (warehouse, register location, bar, kitchen) |
| stock | `direct-stock/in|out`, items, item by product, stats, product-stock-levels, ledger |
| documents | stock-outs, waste (+summary), adjustments, transfers — create/list/approve/cancel/reverse; transfers dispatch/receive/recall |
| counts | `/inventory/counts`: preview, start, draft, submit, review, cancel |
| reservations | `/inventory/reservations`: create, release, consume, atp |
| posting rules | `/inventory/posting-rules`: CRUD, overview, movement types, resolve-preview, restore defaults (`packages/shared/src/inventory-posting-rules.ts`) |
| reports | expiring, reorder, movements, item-movements, valuation, movement-analysis, register/:kind, reconciliation, stock-health, negative-stock |

## Business rules to reproduce exactly

1. Every quantity change goes through `StockService` (receive/issue/transfer/adjust) and writes one `InventoryLedger` row: `qtyBefore`, `quantityChange`, `balanceAfter`, `unitCost`, `type` (StockMoveType), `referenceType/Id`, `ledgerCode`. Ledger is append-only (DB guards in V1 enforce).
2. AVCO: lock the `StockItem` row (`FOR UPDATE`), new avg = (qty×avg + inQty×inCost)/(qty+inQty) at 6 dp; issues at current avg. FIFO consumes `InventoryBatch` layers oldest first. STANDARD uses product standard cost (variance to GL). SPECIFIC uses serial cost.
3. Stock period lock: movements dated in a locked period are rejected (verbatim message).
4. Negative stock: allowed/blocked per settings and POS stock policy (`block|warn|silent`).
5. Reversals create opposite moves (`reversal_in/out`) linked to the original; double reversal rejected.
6. GL posting per movement type via `InventoryPostingRule` (debit/credit account resolution, overridable per location/category).
7. Counts: submitted count posts adjustments for differences; review shows variance value.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`stock.received`, `stock.issued`, `stock.adjusted`, … (see `DomainEvents`).

## Scheduled jobs

— (POS stock posting drain lives in pos).

## Notes and pitfalls

- Port `stock.service.ts` with all locking; port the 28 inventory spec files listed in generated spec.
- `STOCK_POSTING_BATCH` env controls drain batch size.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests — see "Jest specs" under Status for what
  is ported where and what is deliberately not
- [ ] Parity scenarios `contract-tests/scenarios/inventory-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Approved by the owner 2026-11-17 (inventory and procurement readiness blockers,
[audit](../audits/2026-11-17-inventory-procurement-readiness.md)):

- **B3: manual WMS pick orders cannot reference a stock document.**
  - `POST /warehouse/pick-orders` refuses a `manual` order (the default) whose `sourceRef` is the number of a sales
    order, sales invoice, delivery order, stock-out or transfer, with
    `Reference X is a <kind>, which takes the stock out itself when it is posted. …`.
  - Why: shipping a manual order issues the stock itself, so the document would take the goods out a second time.
  - The web form asks what the pick is for (a document type or "Manual issue") and explains which side issues the
    stock.
  - Spring-only feature ([ADR-S026](../adr/ADR-S026-warehouse-management.md)); test `WarehouseIT`.
- **UB1: `GET /inventory/staff-options`** (new, `inventory:read`).
  - Returns the organization's active users, with `id`, `firstName` and `lastName` only, for the stock forms'
    responsible and approver pickers.
  - Those forms called `GET /users`, which needs `user:read`.
  - Test `ReadinessBlockersIT`.

Pending owner approval:

1. **`GET /inventory/items?lowStock=true`**: Nest passes a `Prisma.sql` fragment as a `where` field value,
   which Prisma rejects at runtime (500). Java applies the intended filter
   (`quantity > 0 AND minQuantity > 0 AND quantity <= minQuantity`).

Approved by the owner — stock engine hardening (`phase/inv-hardening`, migration `V460`); Nest has the same defects:

2. **One quant lock order.** Every engine path (receipt, issue, count, transfer) takes the per-quant advisory lock
   and then the `StockItem` row `FOR UPDATE` before reading quantity / average; transfers lock both quants in
   locationId order. Nest computes the receipt average without the row lock (a concurrent issue could change the
   quantity it averages over).
3. **Lines posted in lock order.** Stock-out, waste, adjustment, immediate transfer, transit receive / recall, direct
   stock-in and reversals feed the engine sorted by (productId, variantKey[, locationId]); document rows and
   responses keep the entered order. Ledger rows of one document are written in that sorted order.
4. **Lock conflicts → 409.** Postgres deadlock / serialization failure / lock timeout (40P01 / 40001 / 55P03) answer
   409 `"This operation collided with a concurrent update and was not applied. Please retry."` instead of 500.
5. **Negative-stock cost correction on every receipt** (production output, returns, reversals, quality moves,
   adjustments-in, transfer destinations), not only document receipts with a GL context. Posting key
   `inventory:cost_correction:<org>:<ledgerCode>`; reversals do not mirror these entries (the inverse move posts its
   own).
6. **Lot-tracked shortfall netting.** A receipt / transfer into a lot-tracked quant that is negative covers the
   shortfall first: the lot is created for the remainder only (fully covered: quantity 0, inactive) and the covered
   units are trued up from their issue cost (kept as the quant's average while negative) to the receipt cost. Open
   lots always sum to max(0, on-hand). A named (MANUAL) lot that is short draws the other lots before going short.
7. **`inventory.allowNegativeStock=false` blocks every issue** (`Insufficient stock for …`) except POS / offline sale
   draining (`pos*`, `menu_recipe` sources), which keeps following the item stock policy. Nest only blocks when the
   item policy is `block`. The setting still defaults to `true`.
8. **Transit lines move their own units.** Dispatch records the transit lots, serials and unit cost per line
   (`StockTransferLayer`); receipt, in-transit loss and recall move exactly those (FIFO within the line), at their
   cost. Lines dispatched before V460 keep the oldest-in-transit behaviour.
9. **Guards.** Transfers check whole serial quantities; a `variantId` must belong to the product and organisation
   (404 `Variant not found`); one active reservation per source line + cell (partial unique index, concurrent
   re-reserve updates it); `GET /inventory/reservations/atp` requires `productId` (400 `productId is required`) and
   sums all locations when `locationId` is absent.

Approved by the owner — counts, warehouse and idempotency hardening (`phase/inv-hardening`, migration `V465`); Nest
has the same count / idempotency gaps (the warehouse module is a Spring extension, see ADR-S026):

10. **Count variance posts against the count time.** A counted line posts `counted + net ledger change on its cell
    (or lot) after countedAt − on-hand at submit`, applied as a delta under the quant lock (`StockMove.deltaQuantity`),
    so sales made while counting are kept. Movements after the count no longer block submit (Nest: 400 "… moved after
    they were counted …" unless `force`); `force=true` now means "the count is the on-hand of now" (overwrites later
    movements, reason required when lines moved, `[FORCED]` note as before). `countedAt` / `countedById` change only
    when a line's counted quantity changes. A reason is required on lines whose posting delta is not zero (Nest: snapshot
    variance). Draft `GET /inventory/counts/:id[/review]` lines also carry `onHandNow`, `movementsSinceCount`,
    `postingDelta`; `variance` stays counted − systemQty.
11. **Count approval sees the variance.** The `inventory_count_submit` snapshot carries `amount` = `varianceValue`
    (Σ |posting delta| × lot cost / running average / cost price, 2 dp HALF_UP), `varianceQty`, `lines`, `countCode`,
    `locationId`, `makerIds`, so value rules apply; no matching rule still posts at once. All validation (nothing
    counted, reasons, serials) runs before the gate, so a rejected submit leaves no approval request.
12. **Count maker-checker.** With the `stock_adjustment` separation-of-duties pair on, whoever started or counted the
    session cannot submit a count that posts a variance (403 `Separation of duties: …`). Offline device counts
    (`stock.count` sync op) are subject to it too.
13. **Per-lot counts.** Counts started from the screen (`POST /inventory/counts/start`, preview, ABC cycle counts)
    give a batch-tracked product one line per open lot (`batchId`, `batchNumber`, `lotLine=true`) plus a "no lot" line
    (system = on-hand − open lots; a gain there goes to the newest lot / a new `ADJ-` lot). Each lot posts to its own
    lot (`batchNumber`, MANUAL). Device counts stay one line per product; a device count cannot resume a per-lot screen
    draft (409 as for an uncovered product).
14. **Serial-tracked count lines** carry `missingSerials` / `foundSerials` (`PATCH …/draft`; omitted keeps, `[]`
    clears). A loss must list exactly the missing units, which must be in stock at the location (400 at submit, before
    anything posts: `… is serial-tracked: a loss of N needs the N missing serial number(s) on the count line, got M` /
    `Serial number(s) of … not in stock at this location: …`); a gain may list the found units (then as many as the gain).
15. **Count branch scope.** `GET /inventory/counts/preview`, `PATCH …/draft`, `POST …/submit` and `POST …/cancel`
    check the session location against the caller's data scope (403 `This location is outside your data access`), like
    start. Offline sync is not scoped (as before).
16. **Idempotency-Key required** on `POST /inventory/direct-stock/in`, `/direct-stock/out` and
    `/inventory/counts/:id/submit` (400 `An Idempotency-Key header is required for this operation so a retry can never
    charge twice.`); Nest accepts a missing key. Document approve / reverse / receive routes keep the key optional:
    each posts behind an atomic status claim (a second run answers 400/409 and posts nothing), and the creates only
    write pending documents. Web screens send one key per action.

Approved by the owner — valuation, reports and infrastructure hardening (`phase/inv-hardening`, migration `V470`); Nest
has the same gaps:

17. **Costing method fixed while stock is on hand.** `PATCH /products/:id` with a different `costingMethod` while any
    quant of the product is non-zero → 400 `The costing method of <name> cannot be changed while it has stock on hand;
    re-cost the stock through a cost policy (/inventory/cost-policies) or a standard cost (/inventory/standard-costs)
    instead` (re-sending the current method is not a change; the policy / standard-cost flows are unaffected).
18. **STANDARD cost price change revalues stock.** A changed `costPrice` on a STANDARD product with stock on hand
    revalues it in the same transaction like a standard activation: quant averages and open lots take the new cost,
    Dr / Cr stock valuation against 5960 *Standard Cost Revaluation* (source `product_cost_price`, sourceId = product)
    plus value-only ledger rows per quant, audit `cost_price_revaluation`. A product governed by an active standard cost
    → 400 `The cost price of <name> is set by its active standard cost; change it through a new standard cost
    (/inventory/standard-costs)`. AVCO / FIFO / SPECIFIC cost prices stay a free fallback.
19. **POS COGS dated at the sale.** Drained POS stock moves (product, combo, recipe, extras) and their COGS use the
    invoice `issueDate` (else the order's `createdAt`) as movement / GL date, so COGS lands in the revenue's period.
    When that date can no longer be posted to (books lock, closed / locked period) they post today
    (`PostingService.openDateOrToday`, as journal reversals do) and the ledger notes say `Stock and COGS dated <day>:
    the sale date <day> is in a closed or locked period`. The ledger `createdAt` stays the drain time.
20. **GL tie-out on one date basis.** `GET /reports/inventory/gl-tieout` (and the historical `GET
    /reports/inventory/valuation?asOf=`) date a stock ledger row by the posting date of its source's inventory journal
    entry (matched on ledger code, else reference id, when that source's entries fall on one day), else `createdAt`;
    the GL side keeps `postingDate`. Daily job `inventory-gl-tieout-check` (02:40 UTC, ShedLock, per organisation with
    stock) runs the same tie-out; settings `inventory.glTieOut.enabled` (default on) and `inventory.glTieOut.tolerance`
    (default 1). A variance above tolerance opens one `InventoryException` (kind `gl_tieout_variance`, Posting Monitor;
    no second one while it is open) and an in-app alert *Inventory does not tie to the GL*. The open variance shows on
    the period-close checklist as its own advisory item `inventory_gl_tieout` (*Inventory ties to the GL*), like the
    other reconciliation items; it does not block `POST /fiscal-periods/:id/close`, and the blocking
    `inventory_exceptions` check / close guard (sale lines whose stock or COGS was not posted) leave it out.
21. **Register reports in SQL.** `GET /inventory/reports/register/:kind`: classification, filters, breakdowns, ordering
    and the 5000-row cap (`truncated`) run in the database (no multi-year ledger load). Same-timestamp lines are
    ordered by id; source-document lookups take one match.
22. **Valuation report is method-correct.** `GET /inventory/reports/valuation` values every quant exactly like
    `GET /reports/inventory/valuation` (lots, serials, STANDARD cost price, average; CM-1 adjunct components), set-based
    and organisation-filtered; `avgCost` = value of the positive quants ÷ positive quantity (cost price fallback). Nest
    multiplies quantity by the running average for every method.
23. **Decimal report arithmetic.** Valuation, item movements, movement analysis (trend aggregated in SQL by business
    day), stock health values, reorder, expiring, ledger detail totals, waste summary, register and approval magnitudes
    sum in `BigDecimal` and round HALF_UP (Nest: JS doubles and `Math.round`, so an exact negative half can differ by
    one unit in the last place). Same JSON numbers otherwise. Item list, expiring and reorder lookups are batched (no
    per-row queries). No row caps were added to the list routes: the web screens use the full lists (waste deep link,
    counts, transfers, client-side expiring / negative-stock totals).
24. **Validated request bodies.** Posting rules: unknown fields, an unknown `movementType` / `debitOrCredit` /
    `accountSource`, a missing `movementType` / `lineIndex` / `debitOrCredit` → 400 (Nest: 500 at insert or posting);
    an omitted `accountSource` defaults to `account_mapping`. On save a `literal` rule needs a `literalAccountId` of the
    organisation that is postable, active and not deprecated (`A literal posting rule needs a literalAccountId`,
    `literalAccountId <id> is not an account of this organization`, `Account <code> is a summary/group account and
    cannot be posted to`, `… is inactive …` / `… is deprecated and cannot be posted to`). Replenishment rule / dismiss /
    requisition bodies are records (unknown fields 400, business messages unchanged; a non-numeric number is Jackson's
    400).
25. **Ledger hygiene (V470).** Dropped the duplicate `InventoryLedger_pool_idx` and the prefix index
    `InventoryLedger_organizationId_idx`. `evidence_purge_enabled()` (the `app.evidence_purge` bypass of every
    evidence guard: stock ledger, cash movements, posted journals, …) now also requires the session user to be a member
    of the schema owner, so the API role (`DB_USER` ≠ `FLYWAY_USER`) cannot switch the guards off. Superseded by V513:
    only a superuser or a member of the NOLOGIN role `erp_evidence_purger` may (docs/DATABASE.md section 6).
26. **Stock events only for committed moves.** `stock.received|issued|adjusted|transferred` are published with
    `EventBus.publishOnCommit`: dropped when the transaction rolls back or the `Savepoints` block they were raised in
    rolls back (a failed POS drain line). Other events keep the Nest fire-and-forget semantics (published on commit and
    on rollback), which failure notifications rely on.
27. **Activation jobs on the organisation calendar.** Cost policy and standard cost activation (by hand and the jobs)
    compare `effectiveFrom` with today in the organisation's time zone (`Organization.timezone`, UTC when unset) instead
    of `APP_TIMEZONE` / the database `CURRENT_DATE`; the jobs run hourly (`:15` / `:20` UTC) and skip anything no longer
    approved.
28. **Opening stock against opening equity (owner decision D5, Phase I5).** Opening stock enters only through an
    approved opening count (`countType = opening`). Its variance posts the inventory side as before, but the other
    side goes to the `retained_earnings` mapping (the opening-balance equity the GL opening balance balances against,
    line text "Opening stock") instead of `stock_adjustment_income` / `_expense`. Every other count, and every manual
    adjustment, posts ADJUSTMENT_GAIN / LOSS as before. `POST /opening-balances` refuses any account in the inventory
    account set (`InventoryValuationService.inventoryAccountIds`) with 400 `Account <code> holds inventory value.
    Enter opening stock with an approved opening count so the stock ledger and the GL agree.` Nest posts the opening
    count as a stock gain and accepts inventory accounts on the GL opening balance. Test: `OpeningStockIT` (also
    checks that the GL tie-out variance does not move when the count posts).
29. **Lock order everywhere (Phase I5, audit S-2).** Cash sale handover, invoice auto-issue, vendor-bill receipt,
    credit-note restock, goods receipt, fleet dispatch and POS recipe / combo components also feed the engine in
    (productId, variantId) order (`StockMove.inLockOrder`), extending item 3. Landed cost takes the same quant locks
    before re-costing lots, serials or averages (`LayerCostWriter`, S-3).

Approved by the owner — accounting and costing review 2026-11-17
([audit](../audits/2026-11-17-accounting-costing-review.md)); an extension, Nest has no such route:

30. **Cost explanation.** `GET /inventory/cost-explanation?productId&locationId&limit` (`inventory_valuation:read`;
    limit 1–500, default 100): a read-only explanation of an item's carrying cost from the stock sub-ledger (method,
    value per location, price-difference and NRV adjuncts, value-only changes, every movement with running quantity,
    value and unit cost). Messages: `productId should not be empty`, `limit must not be less than 1`, `limit must be
    an integer number`, 404 `Product not found` / `Location not found`. Test: `CostExplanationIT`.

Owner-approved hardening 2026-11-23, batch R2 of the inventory hardening plan (branch security and master data,
gate G3). The Spring app is independent of Nest since 2026-11-18, so these are hardening rules, not parity gaps:

31. **Branch scope on stock actions.** A user with a data scope (BR-1) is checked on the side of the document the
    action belongs to. A refusal is 403 `This location is outside your data access`.
    - Reversals (`POST /inventory/{stock-outs|waste|adjustments|transfers}/:id/reverse`): the document's location, for a
      transfer its source. The check runs inside the reversing transaction after the header row lock. Engine reversals
      other modules run (`reverseLedgerSource` from procurement, fleet, quarry, manufacturing) are not user-scoped.
    - Transfers: approve, dispatch, recall and cancel need the source; receive needs the destination; reads and create
      stay reachable from either end. Dispatch, receive, recall and cancel check after the header lock; approve checks
      before its approval gate (which runs in its own transaction) and the status claim serialises it. Offline
      `transfer.dispatch` / `transfer.receive` inherit the same rules (a refusal becomes a held posting).
    - Reservations (`/inventory/reservations`): reserve needs the location in scope and 404s `Product not found` /
      `Location not found`; release, consume, the source list and the overview touch or show only the caller's
      locations, and an overview `locationId` outside them is 403. `GET /inventory/reservations` without both
      `sourceType` and `sourceId` is paged (`page`, `pageSize`, at most 200 rows). The engine methods sales, POS and
      manufacturing call are unchanged (no user scope).
    - Replenishment: a transfer rule's `sourceLocationId` must be in scope when it is set or changed; a kept source is
      not re-checked, so a branch user can still edit the levels of a rule an unrestricted user set up.
32. **Location guards.** `DELETE /inventory/locations/:id`, `isActive: false`, a `type` change or a `branchId` change
    is refused (400 `Location <code> cannot be deleted|deactivated|retyped|moved to another branch while it still has
    <what> (<n>); move or close them first`) while the location has non-zero stock, stock in warehouse bins, open
    transfers (draft, pending, approved, in transit, partially received; from, to or through it), draft counts, active
    reservations, or active replenishment / reorder rules (as location or transfer source). The system transit
    location is never deleted, deactivated or retyped (400 `The transit location is system-managed; it cannot be
    deleted|retyped|deactivated`); no user can type a location `transit` (unchanged DTO rule). Manufacturing's QC
    quarantine and rental's role locations are created through `VirtualLocationService.ensure` instead of direct
    inserts. Tests: `InventoryBranchScopeIT`; `WarehouseReplenishmentGapsIT.scanSurvivesABrokenRule` now soft-deletes
    the source in SQL to simulate legacy data.

Same as Nest, kept on purpose:

- Prisma tenancy emulation: explicit `organizationId` + `deletedAt IS NULL` on soft-delete models; relation
  includes are not filtered (Prisma's extension does not filter includes either).
- Period-lock and posting-rule resolution (negative stock: see 2–8 above) follow `stock.service.ts` /
  `stock-posting.service.ts` line by line; all money/qty math is `BigDecimal` with decimal.js semantics (`Money.div`, `JsNumbers.dec`).

Kernel fix found while porting: `SequenceService` now creates per-org sequences outside the caller's transaction
(Nest uses the raw client). A business rollback used to drop a freshly created sequence while the in-memory
cache still considered it present (500 on the next `nextval`).

## Status

P3a: 69/69 routes. Tests: `InventoryIT` (locations, items, ledger, document flows incl. approve + reverse, transit,
counts incl. count-time posting / approval / lots / serials / scope, reservations + ATP, posting rules, reports, period
lock, idempotency, authorization matrix = 69 routes, tenant isolation), `InventoryUnitTest`, `StockConcurrencyIT`,
`InventoryHardeningIT`, `InventoryReadsIT`, `InventoryRegisterIT`, `InventoryValuationReportIT`,
`InventoryCostPolicyIT`, `WarehouseIT`, `StockReservationOverviewIT`, `ReplenishmentIT`, `ReplenishmentCalculatorTest`,
and the audit ports in `inventory/audit/` (below). Parity scenario: `contract-tests/scenarios/inventory-stock.json`.

Jest specs (generated list):

- Ported here: `cost-resolver` + `stock-posting.service` → `InventoryUnitTest`. The `test/integration/audit/inv-*`
  suites → `inventory/audit/*IT`, each on its own isolated organisation (seeded chart, two warehouses) so GL balances
  and the tie-out are exact: `InventoryAuditConcurrencyIT` (inv-audit-concurrency; INV-002 of inv-p0; P0-3 and P2 of
  inv-readiness), `InventoryAuditConservationIT` (inv-audit-conservation; INV-010/011/013 of inv-p2; N1/N2 of inv-p1;
  P1-04 of inv-audit3; final whole-org check: Σ ledger = on-hand, open lots = max(0, on-hand), unbroken chain, tie-out
  variance 0), `InventoryAuditCrashBoundaryIT` (inv-audit-crash-boundaries H3/H8/D-E, inv-audit-period-lock H9, the
  drain cases of inv-audit-pos-lifecycle on hand-built jobs, plus mid-transaction crashes injected with a schema-owner
  trigger into stock-out / transfer / direct stock-in GL / count submit: nothing half-commits, the retry posts once),
  `InventoryAuditTenancyLocationIT` (inv-audit-tenancy-location, incl. an RLS probe), `InventoryAuditRemediationIT`
  (P1-02/P1-03/P2-03/P2-04 of inv-audit3; INV-008/N5 of inv-p1; P1-1 of inv-readiness). Where a deviation changed the
  asserted behaviour the test follows the deviation (P0-3 runs in strict mode, deviation 7; concurrent count submits
  each carry their own key, deviation 16).
- Covered by existing tests instead of a second port: inv-audit-purchasing H1/H18, purchase-bill-matching,
  supplier-payment-withholding, INV-003 (inv-p0), INV-010 GRN reversal (inv-p2), N3 / INV-012 (inv-p1), P0-2 / P1-3
  (inv-readiness), P2-02 landed cost (inv-audit3) → `procurement/ProcurementIT`; crash C/F and H8 job state →
  `pos/PosStockPostingIT` (`reclaimRace`, `recipeLessMenuItem`); N4 adjustment drift → `InventoryIT.documents`; P1-2
  approver PIN and P1-6 duplicate serial → `InventoryIT.directStockGuards` / `serials`; `stock-posting-job` →
  `PosStockPostingIT`; `pos-offline-inventory-replay` → `sync/SyncInventoryReplayIT`; `sync-cash-inventory` →
  `sync/SyncIT`; `manufacturing-flow` → `manufacturing/ManufacturingIT`; lakeview simulations → `pos/PosLakeviewSimIT`
  (reduced); `inventory-engine` → `InventoryIT` + `StockConcurrencyIT` (scenario-equivalent, not line by line).
- Not ported (no Java port yet; they drive POS cash / refund routes, not inventory ones): `pos-cash-flow-adversarial`,
  `pos-cash-flow-day`, and inv-readiness P0-1 (refund restock of sale-unit / recipe-unit quantities).

## ADR-S007 extensions — configurable costing policy (roadmap 1.8)

`/inventory/cost-policies` (GET list, GET `/current`, GET `/{id}`, GET `/{id}/preview`, POST, PATCH `/{id}`,
POST `/{id}/approve`, `/{id}/activate` (Idempotency-Key), `/{id}/cancel`); permissions `costing_policy:read`,
`manage`, `approve`. Hourly job `inventory-cost-policy-activation` (:15 UTC, ShedLock) activates approved policies
whose `effectiveFrom` has come. With an active policy `costingMethod` on products follows the policy (deviation from
Nest only for organisations that create a policy). Tests: `InventoryCostPolicyIT`. Web: Inventory → Costing Policy.

## ADR-S008 extensions — standard costing

`/inventory/standard-costs` (GET list `?productId&status`, GET `/variances?from&to&kind&productId`, GET `/{id}`,
POST, POST `/{id}/approve`, `/{id}/activate` (Idempotency-Key), `/{id}/cancel`); permissions `standard_cost:read`,
`manage`, `approve`. Hourly job `standard-cost-activation` (:20 UTC; organisation calendar, deviation 27). Receipts of standard-governed products enter
stock at standard with purchase price variance (account 5905); activation revalues on-hand stock (5960).

## Locations and branches (BR-1, ADR-S017)

Status: built and tested — `core/UserDataScopeIT` (no grant = unchanged access, branch / location grants, reports
outside the scope refused), `costing/DistributionBranchScenarioIT` (branch transfer with inter-branch clearing), count
scope in `InventoryIT.countScope`. `InventoryLocation.branchId` (V62), accepted on create and update. Reads and stock
documents go through `LocationScope`; `StockService` does not.

## INV-1 reservation screen (built, tested: `StockReservationOverviewIT`)

Additive `GET /inventory/reservations/overview` (`StockReservationService.overview`, paged, with names and on-hand). The
Nest list route is unchanged and POS does not call the new method. Screen: `/inventory/reservations`.

## INV-3 replenishment (built, tested: `ReplenishmentIT`, `ReplenishmentCalculatorTest`, `manufacturing/ReplenishmentMrpIT`)

`ReplenishmentController` (8 routes on `/inventory/replenishment`: rules CRUD, `alerts`, `alerts/{id}/dismiss`, `scan`,
`requisition`), `ReplenishmentService`, `ReplenishmentCalculator`, `ReplenishmentScanJob` (hourly), migration V97, permission
`inventory:replenish`, event `inventory.replenishment.requested`. Procurement listens
(`ProcurementReplenishmentSubscriber`) and raises draft purchase requests. Stock, ledger and POS are not touched: the scan
only reads `StockItem` and `StockReservation`. Screen: `/inventory/replenishment`.

## ADR-S026 extension — warehouse management (roadmap 2.1, PR-4)

Bins, putaway rules and tasks, pick / pack / ship in one to three steps, packages, waves and ABC cycle counts under
`/warehouse` (`warehouse:read|manage|operate`). Bins are a sub-ledger inside a location (`WarehouseBinStock`); the
ledger, costing and GL are unchanged and the source document still posts every issue. Cycle counts open ordinary
`cycle` sessions of the count service. Details and limits: `docs/adr/ADR-S026-warehouse-management.md`.

## ADR-S027 extension — replenishment routes (roadmap 2.3, PR-5)

Rules take `route` (`buy` default, `manufacture`, `transfer` + `sourceLocationId`). Errors: 400 `route must be one of:
buy, manufacture, transfer`, `A transfer rule needs a sourceLocationId`, `The source location must differ from the
rule's location`, 404 `Source location not found`. A transfer alert is answered here with a pending stock transfer;
buy / manufacture lines go out on `inventory.replenishment.requested` tagged with `route` and `locationId` (procurement
and manufacturing each take their own). Alerts carry `route`, `documentType`, `documentId`, `documentNumber`. Incoming
supply adds `TransferIncomingSupply` (inventory) and `ProductionIncomingSupply` (manufacturing). Details:
`docs/adr/ADR-S027-replenishment-mrp-completion.md`.
