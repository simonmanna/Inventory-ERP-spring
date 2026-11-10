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
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/inventory-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

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
    no second one while it is open) and an in-app alert *Inventory does not tie to the GL*. Note: an open exception
    in a period blocks closing it, like the stock-posting exceptions.
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
    of the schema owner, so the API role (`DB_USER` ≠ `FLYWAY_USER`) cannot switch the guards off.
26. **Stock events only for committed moves.** `stock.received|issued|adjusted|transferred` are published with
    `EventBus.publishOnCommit`: dropped when the transaction rolls back or the `Savepoints` block they were raised in
    rolls back (a failed POS drain line). Other events keep the Nest fire-and-forget semantics (published on commit and
    on rollback), which failure notifications rely on.
27. **Activation jobs on the organisation calendar.** Cost policy and standard cost activation (by hand and the jobs)
    compare `effectiveFrom` with today in the organisation's time zone (`Organization.timezone`, UTC when unset) instead
    of `APP_TIMEZONE` / the database `CURRENT_DATE`; the jobs run hourly (`:15` / `:20` UTC) and skip anything no longer
    approved.

Same as Nest, kept on purpose:

- Prisma tenancy emulation: explicit `organizationId` + `deletedAt IS NULL` on soft-delete models; relation
  includes are not filtered (Prisma's extension does not filter includes either).
- Period-lock and posting-rule resolution (negative stock: see 2–8 above) follow `stock.service.ts` /
  `stock-posting.service.ts` line by line; all money/qty math is `BigDecimal` with decimal.js semantics (`Money.div`, `JsNumbers.dec`).

Kernel fix found while porting: `SequenceService` now creates per-org sequences outside the caller's transaction
(Nest uses the raw client). A business rollback used to drop a freshly created sequence while the in-memory
cache still considered it present (500 on the next `nextval`).

## Status

P3a: 69/69 routes. Tests: `InventoryIT` (15: locations, items, ledger, stock-out / waste / adjustment / transfer
document flows incl. approve + reverse, counts, reservations + ATP, posting rules, reports, period lock,
idempotent replay, authorization matrix = 69 routes, tenant isolation), `InventoryUnitTest` (ports of
`cost-resolver.spec.ts`, `stock-posting.service.spec.ts`, decimal formatting). Parity scenario:
`contract-tests/scenarios/inventory-stock.json`.

Deferred to the phase that owns the other side of the flow: the `test/integration/audit/inv-*`,
`procurement-flow`, `pos-*` and `sync-*` Jest suites listed in the generated spec drive POS / procurement /
sync routes (P4–P6) and are ported there.

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

Status: built, not compiled or run. `InventoryLocation.branchId` (V62), accepted on create and update. Reads and stock
documents go through `LocationScope`; `StockService` does not.

## INV-1 reservation screen (built, unverified)

Additive `GET /inventory/reservations/overview` (`StockReservationService.overview`, paged, with names and on-hand). The
Nest list route is unchanged and POS does not call the new method. Screen: `/inventory/reservations`.

## INV-3 replenishment (built, unverified)

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
