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

- `InventoryIntegrityJob` (daily 03:10 UTC, ShedLock `inventory-integrity-check`, off with `app.jobs.enabled=false`):
  see "Integrity checks (R0)". The POS stock posting drain lives in pos.

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

IPR Phase 2, stream C (branch `fix/ipr-2`, **pending owner sign-off**; evidence
[2-accounting-mfg](../audits/ipr-evidence/2-accounting-mfg.md)):

- **GL-4: posting fallbacks.** Without a matching posting rule, an issue of type `RETURN_TO_SUPPLIER` debits GRNI
  (`grni_accrued`) and `PRODUCTION_CONSUME` debits WIP (`wip`, else stock valuation) instead of COGS; a movement type
  with no known default fails closed (400 "No posting rule or default account is configured for stock movements of
  type <T>; add a posting rule for it under Inventory > Posting rules"). Nest fell back to COGS for every type.
- **GL-4: system posting rules.** `DELETE /inventory/posting-rules/:id` refuses an organisation-wide rule that matches
  a system default (movement type, line, side): 400 "This is a system default posting rule for <T> and cannot be
  deleted. Change its account instead, or use Restore defaults." Product and category rules delete as before.
- **GL-1 / GL-5: base currency.** `GET /reports/inventory/gl-tieout` and the integrity checks `valuation_vs_gl` /
  `valuation_vs_gl_by_product` sum `baseDebit − baseCredit` (Nest summed the transaction-currency `debit − credit`).
- **GL-11: `wip_tieout` report-only integrity check** (new key at the end of `reportOnlyChecks`): GL WIP per cost
  object against the WIP sub-ledger (docs/spec/manufacturing-wip-reconciliation.md). Report-only until the owner
  promotes it to a value check.
- **GL-11 refinement (stream H): WIP accounts of the tie-out.** `wip_tieout` reads the mapped `wip` account plus every
  account the active `PRODUCTION_CONSUME` (debit) / `PRODUCTION_OUTPUT` (credit) rules resolve to, resolved as the
  postings resolve them (`InventoryPostingRuleService.ruleAccounts`): a `category_field` rule per category of the
  products it covers, a `product_field` rule per product (override, category field, mapping). Accounts that are stock
  accounts (the `stock_valuation` mapping, a category's inventory account, a product's inventory override) are left
  out: a rule falling back to them is a misconfiguration the stock tie-outs report.
- **D-1 (stream H): the base seed journals its opening stock.** `InventorySeedContributor` (order 300) posts the
  journal of the widget's opening stock (Dr stock valuation / Cr retained earnings, 600, entry `SEED-OPEN-INV-STK`,
  posting key `seed_opening_inventory:<org>:inventory`) in the same step as the ledger row, so an organisation seeded
  through `SeedRunner.base()` ties out. `PosSeedContributor` (order 400) journals only the opening stock it seeds
  (`SEED-OPEN-INV`). A fully seeded new organisation therefore has two opening-stock entries instead of one. Existing
  organisations are not touched (no auto-repair); the GQDEMO repair is an owner action (evidence 12-residuals §D-1).
- **GL-10: setting `inventory.closeGate.liveValueChecks`** (organisation, default on): the period close runs the
  inventory value checks live (see accounting.md).

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
    write pending documents. Web screens send one key per action. (R5a: transfer approve / dispatch, adjustment and waste approve and
    warehouse ship now require it — deviation 41.)

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

Approved by the owner 2026-11-23 (inventory hardening R1, corruption blockers, release gate G1):

- **Returns restore the original identity and cost.** `StockReversalService.returnFromSource` returns goods against
  their original outflow rows: each unit comes back with the lot (into the very `InventoryBatch` row when it returns to
  the location it left, never a new lot), expiry, serial, location and unit cost of the row it left on. The new ledger
  row names that row in `InventoryLedger.reversesLedgerId` (V611, FK + index); `reverseLedgerSource` sets it too. The
  quantity is allocated across the rows in their original order, net of what earlier returns brought back (net of
  their own reversals); more is refused with 400 `Cannot return more than was issued`, a source without issue rows
  with 400 `No stock issue was found to return against`. Callers: cash-sale void, invoice cancel, credit-note restock,
  sales return (into the chosen receiving location: same lot number / expiry / serial / cost), production
  cancel / reversal (`returnMaterials`, GL still the line's WIP clearance), debit-note reversal. `receiveReturn`
  forwards `batchNumber` / `expiry` / `serials` it is given. Rows written before V611 carry no link, so returns made
  before this change are not netted (the callers' own guards still apply). POS refunds are unchanged (they already
  restock at the issue location and cost and refuse tracked items). Tests `InventoryReturnsIdentityIT`,
  `CashSaleIT.voidLinksTheIssueRows`.
- **Credit-note reversal takes back what the note restocked**, row by row: the same lot / serial, at the cost it came
  back at (the carried cost of an unlotted AVCO quant), naming the restock row, so the invoice's issue becomes
  returnable again.
- **Fee lines never move stock.** A POS bill line with `rentalFeeType` set is skipped by the stock issue, the
  reservation, the variant check and the ATP check, whatever product it names (see rental.md).
- **A credit note refuses to restock without an issue to return against** (see invoicing.md).
- **Offline refund / void drains or holds; offline receipt applies exactly once** (see sync.md).

Approved by the owner 2026-11-23 (inventory hardening R3, financial integrity, release gate G2):

- **A STANDARD product without an active standard cost is governed by its cost price.** Issues and the valuation
  already used `costPrice`; receipts now enter stock (quant, lot, serial, ledger row) at it too, and a valued receipt
  (goods receipt, bill, direct stock-in) posts Dr stock at the cost price, Cr GRNI at actual, the difference to purchase
  price variance (5905) through `StandardCostService.postReceipt`, recorded in the variance register. A cost price of 0
  is a valid standard (everything to PPV). With neither a standard nor a cost price, a valued receipt is refused with
  400 `Set a cost price or an active standard cost for STANDARD product <name> before receiving it`; unvalued internal
  receipts (transfers, returns) keep the old behaviour. Production output (single-leg, stage and process costing) uses
  the same governing standard (`StandardCostService.governingStandard`).
- **STANDARD returns enter at the standard; the difference goes to PPV.** `receiveReturn` and `returnFromSource`
  (also with `postRestockGl=false`, where the caller reverses the original cost) bring the unit back at the governing
  standard and post `original cost − standard` against the stock account (positive: Dr PPV / Cr stock; negative: Dr
  stock / Cr PPV), so the stock account ends at the standard while COGS is reversed at the original issue cost. The
  difference is recorded in the variance register (kind `purchase_price`, the return's source). `reverseLedgerSource`
  is unchanged: it already squares a value difference as a stock adjustment.
- **Serial tracking and STANDARD costing are exclusive.** Refused with 400: creating or updating a product to
  STANDARD + serial (`Serial-tracked products cannot use STANDARD costing: each unit keeps its own cost (use SPECIFIC,
  AVCO or FIFO)`; turning serial tracking — or SERIAL picking — on for a STANDARD product: `A STANDARD-costed product
  cannot become serial-tracked: ...`), activating a standard cost for a serial-tracked product, and a STANDARD cost price
  change (revaluation) of a serial-tracked product. A legacy product that already has both is left as it is (an
  unrelated edit still saves) and is listed by block (i) of `docs/audits/2026-11-22-inventory-data-checks.sql`.
- **Stock journal lines carry their product.** Every journal line on an inventory control account posted by the stock
  engine (`StockPostingService`: issues, receipts, return restocks, adjustments, transfers, production, cost
  corrections, landed cost — one voucher line per product now), standard costing (receipt PPV entry, revaluations,
  return variance), revaluation / NRV, cost-policy rounding (one line per product now), material ledger, quality
  downgrade (gross lines per product when two products are involved), project / fleet / quarry issues and
  `reverseLedgerSource` (which keeps the original line's keys, or stamps the reversed product), supplier-claim stock
  adjustments and debit-note returns (stock credits per product and warehouse now), lineage-exception releases of a
  voucher's on-hand share (one line per product) and branch transfers carries
  `dimensions.inventoryProductId` (and `inventoryLocationId` when the posting has one location). Both keys are reserved:
  they cannot be analytic dimension codes, so dimension validation and reports ignore them.
- **Per-product GL check and its cutover.** The integrity report gains `valuation_vs_gl_by_product` (a value check: the
  monitor raises `integrity_valuation_vs_gl_by_product` and it holds the period close; not raised while
  `inventory.glTieOut.enabled` is off). Per product: Σ (debit − credit) of the stock-account lines carrying the product
  = Σ signed ledger value of the rows recorded since the cutover, tolerance 0.01; rows show costing method, quantity,
  sub-ledger value, GL value and difference. The cutover is the setting `inventory.productGlCutoverAt` written by V613
  (data only) for every organisation that existed at deploy; an organisation created later has no marker, so its whole
  history counts. History before the cutover is reconciled by source document through the existing tie-out. A line
  without the key is attributed through its source document when all ledger rows of that document are one product;
  what is left is one `unattributed` failing row (a posting path that does not stamp; landed-cost accrual entries
  post only to the 1396 adjunct and to consumption accounts, so they never reach a checked account). The CM-1 adjunct accounts (price differences
  1396, NRV allowance 1397) and their value-only components stay with the organisation-level tie-out, and so does a
  branch split (revaluations and landed cost post per product, not per location). Test `StandardCostingIntegrityIT`.

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
      `transfer.dispatch` / `transfer.receive` inherit the same rules (a 403 is not a business refusal: the op is
      dead-lettered for a supervisor, not held).
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

Approved by the owner — hardening R5a, 2026-11-23 ([review](../audits/2026-11-22-inventory-review.md), migration
`V615`). Spring-only; Nest has the same gaps:

33. **Report day bounds.** `GET /inventory/ledger` (`dateFrom` / `dateTo`) and `GET /inventory/reports/movements`
    (`start` / `end`): a date-only value is the organisation's business day (start of day / end of day inclusive), as
    on the other inventory reports; a full ISO instant is used as given; an unparseable value is 400 `Invalid date:
    …` (was UTC midnight, losing the last day; a bad value was a 500).
34. **Batched includes.** `/inventory/ledger` and `GET /inventory/items/:productId` read each include (product,
    variant, location, lot, menu item, purchase order, partner) with one query per relation for the whole page; the
    JSON is unchanged.
35. **Reconciliation tolerance.** `GET /inventory/reports/reconciliation?tolerance=` non-numeric, infinite or negative
    → 400 `tolerance must be a non-negative number` (was a 500). Other numeric query parameters of the inventory
    controllers already fall back to their defaults.
36. **Stock-health age.** A quant never consumed within the scan horizon is aged from its last true receipt at the
    location (`receipt`, `return_in`, `production_output`, `opening_balance`), or, when it only ever arrived by
    transfer, from its last `transfer_in`; Nest marked every never-consumed quant `dead`, even one received today. A
    transfer or reversal into a location with a known receipt does not make old stock look fresh. The movement scan
    reads only from the earlier of the period start and now − `deadDays`; `lastConsumedAt` / `lastInboundAt` are
    reported within that window. The aging buckets still date transferred stock from its arrival at the location.
37. **Closing value of a past period.** `GET /inventory/reports/item-movements`: when the period ends before today,
    `closingValue` is Σ signed ledger value up to its end (was closing quantity × today's average cost). A period ending
    today keeps the value by costing method.
38. **Cost fields need `inventory_valuation:read`.** `GET /inventory/reports/valuation` (`avgCost`, `value`,
    `locations[].value`, `totals.value`, `byCategory[].value` / `share`) and `GET /inventory/reports/stock-health`
    (`unitCost`, `value`, `consumedValue`, `summary.totalValue|activeValue|slowValue|deadValue`) answer those fields
    `null` to a caller without `inventory_valuation:read`; quantities, statuses and retail values stay. The screens
    show a dash. The analytics inventory dashboard reads stock health internally with values, as before.
39. **Request limits.** Stock-out lines `qty` must be positive; stock documents, direct stock and transfer receipts
    take at most 1,000 lines (`items must contain no more than 1000 elements`), notes 2,000 and reasons 1,000
    characters; warehouse pick orders and packs 1,000 lines, waves 200 pick orders, notes 2,000, bin-adjust reasons
    1,000; replenishment rules: ids ≤ 64 characters, quantities ≤ 99,999,999,999,999 (the column range), `leadTimeDays`
    0–3,650; dismiss reasons 1,000; requisitions ≤ 500 alert ids. The web forms enter lines by hand, far below these
    caps.
40. **Bounded stock-document lists.** `GET /inventory/stock-outs`, `/adjustments` and `/transfers` stay plain arrays
    (the screens filter them in the browser) of the newest 500 documents; `limit` (1–2,000, else 400 `limit must be a
    positive number`) and `dateFrom` / `dateTo` (business days or ISO instants) reach older ones. The branch scope is
    applied in SQL before the limit. `GET /inventory/waste` already had its 500 cap.
41. **Idempotency-Key required** on `POST /inventory/transfers/:id/approve`, `/transfers/:id/dispatch`,
    `/adjustments/:id/approve`, `/waste/:id/approve` and `/warehouse/pick-orders/:id/ship` (400 `An Idempotency-Key
    header is required …`). The web sends one on each (`idempotentPost`); Android does not call them.
    `POST /inventory/stock-outs/:id/approve` keeps the key optional: no client calls it yet.
42. **Warehouse.** A move task takes only what open pick / move tasks leave in its bin (`reservedInBin`), at creation
    and at completion (`Not enough stock in bin X for P; open picks hold part of it`). With warehouse management
    switched off, open work can still be finished: task complete / cancel and pick-order pack / ship / cancel; new
    tasks, pick orders, releases, waves, putaway generation and reconcile stay refused. Reconcile's second pass may take
    stock held by open move tasks and cancels those moves (newest first, until the remaining moves fit); it never
    takes stock held by open picks — excess only pick-held stock could cover stays and is reported in `heldByPicks`
    (response also lists `cancelledTasks`). `GET /warehouse/locations/:id/abc` is read-only (the latest submitted
    count is read, and stored by classify / cycle-count generation). ABC consumption no longer counts `transfer_out`
    (a central store feeding branches classifies by its own issues). `createWave` locks its orders in id order.
43. **Index (V615)** `StockTransferItem(organizationId, productId)` for the incoming-transfer supply of replenishment.

Approved by the owner — owner-approved hardening R5b 2026-11-23 ([review](../audits/2026-11-22-inventory-review.md),
P2-1 / P2-2 / P2-3 / P2-7 / P2-11, no migration). Spring-only; Nest has the same gaps. Test:
`InventoryEngineHardeningIT`.

44. **Engine lock order everywhere.** Every path that locks quants takes the costing locks first (organisation key
    shared, product key shared, products sorted), then the per-quant advisory locks, then the `StockItem` rows, cells
    sorted by (product, variant, location): count submit (`StockService.lockQuants`), landed-cost and lineage layer
    writers (`LayerCostWriter.lockQuants`, `LineageFollower.raiseAverage`, `CostingLocks.shareProduct`), transfer and
    transit reversals (`StockService.lockCells` over the received cells, the losses and the source; the transit
    reversal used to lock the destination before the losses). Reservations (`StockReservationService.reserve` /
    `lockCell`, `SalesAtpService`) take the quant's advisory lock before its row; multi-line reservations (sales
    orders, POS invoices, processing-job inputs) run in that sorted order.
45. **Approval gates before locks.** Count submit measures the variance without locks, runs the approval gate (its
    own transaction), then locks and measures again. When the locked variance differs and the gate could judge it
    differently (it grew past an approval granted for less, or a workflow would now apply), the submit is refused with
    409 `Stock moved while the count was being submitted; submit it again.`; otherwise the locked variance posts.
    Adjustment, stock-out, waste and transfer approvals, revaluation, lineage write-off and warehouse manual ship
    already gate before any quant lock (they hold only their own document row while the gate runs); kept as is.
46. **Reversals and production bring back their own lots and serials.** A transfer reversal issues, at the
    destination, the lots and serials the transfer delivered there (recorded in `StockTransferLayer` from R5b on for
    immediate and transit receipts; transfers received before R5b fall back to oldest-first). A production reversal
    issues the produced lot (the `production_output` ledger lots, else the lots with the produced batch numbers). Both
    prefer these lots and fall back to the normal order for any remainder (`StockMove.prefer`).
47. **Count lot lines post by lot id.** A count line of one lot adjusts that lot row (loss and gain), even when two lots
    share a batch number; the "no lot" line of a lot-tracked product keeps the engine's normal order.
48. **Serial receipt race.** A new serial is inserted with `ON CONFLICT DO NOTHING`; when another receipt won, the
    receipt is refused with 400 `Serial <number> is already in stock at <location>` (or `Serial number(s) already exist
    for this product: <number> (<status>)` when it is not in stock) instead of a 500. Returns and reinstated serials
    keep their update path.
49. **Transfer values.** A serial transfer is valued at the moved serials' own costs (plus the average for any
    unserialised part), not at the source average. A STANDARD quant's average is the governing standard on receipt
    (PPV takes the difference) and a transfer of a non-lot STANDARD product moves at that standard.
50. **AVCO residue at zero.** When a non-lot, non-serial AVCO quant reaches zero through an issue, a non-layer
    adjustment or a transfer, a rounding residue of the quant's ledger value below 1.00 is cleared in the same
    transaction: a value-only `cost_adjustment` row (quantity 0, notes `AVCO rounding residue at zero stock (…)`) and
    its journal (Dr COGS / Cr stock or the reverse, cost-correction posting key, not mirrored by reversals). Only when
    the move posts GL; a residue of 1.00 or more is left for the integrity report.
51. **Rental units move their variant and serial.** Rental checkout, return, swap and cleaning transfers carry the
    rental unit's `variantId` and prefer its `inventorySerialId`.
52. **Ledger detail audit trail (additive JSON).** `GET /inventory/ledger/:id` entries and lines add `reverses`
    (`{id, ledgerCode}` of the row named by `reversesLedgerId`, else `null`), `performedByName`, `responsibleByName`,
    `approvedByName` and `recordedOffline` (from the source transfer, adjustment or goods receipt; `false` otherwise).
53. **More cost fields need `inventory_valuation:read`.** Without it `GET /inventory/ledger` and `/inventory/ledger/:id`
    answer `unitCost` / `totalValue` (and the detail's `totals.totalValue`) as `null`;
    `GET /inventory/reports/item-movements` answers `valueIn`, `valueOut`, `valueAdjusted`, `avgCost`, `closingValue`
    and the same totals as `null`; `GET /inventory/reports/movements` answers `netValue` and `byType.*.value` as
    `null`. Quantities stay. The movement report, ledger and movement-detail screens show a dash.
54. **Negative-stock cost corrections are ledgered (post-merge fix 2026-11-23).** A receipt or transfer-in into a
    negative AVCO quant at a different cost still posts the correction journal (Dr COGS / Cr stock or the reverse);
    it now also writes a value-only `cost_adjustment` ledger row (quantity 0, value = −correction, balance = the quant
    balance) with the same reference, as the AVCO residue sweep does, so Σ ledger value = valuation = GL. Reversals
    skip zero-quantity rows and keep not mirroring the journal.
55. **Effective time and count cut-off (IPR 1b, ADR-S088, count spec; integrator decisions P15-1/P15-2, owner
    sign-off pending).** Every new ledger row records `effectiveAt` (when the goods moved; ADR-S088 "Implementation").
    Counts carry movements forward by `COALESCE("effectiveAt","createdAt")` instead of `createdAt`
    (`InventoryCountService.postings`, `SyncStockService.movedSince`, `BeverageCountService.submit`, fleet tank dip
    `levelAt`). `adjust` / `transfer` post their journals on `StockMove.date` (default now) and their memos end with
    the ledger code (SW-6).
56. **Count submit drains, measures and reconciles late rows (IPR 1b T11).** `POST /inventory/counts/:id/submit` first
    processes the location's not-done POS stock-posting jobs (each in its own transactions, before any lock), then
    locks the session row and the cells, and answers **409** "`<n>` sale stock posting(s) at this location are not
    done; resolve them in the Posting Monitor, then submit the count again" while a job of the location that sells a
    counted product (or a menu item) is failed or retrying; a device count (offline op) is not refused for this — the
    stuck sale becomes a late row of the count and is corrected when it finally posts. Each counted line stores `measuredSeq`. A movement that happened before a submitted count's cut-off but
    is written after the count was measured gets exactly one linked correction row (`referenceType`
    `inventory_count_late_correction`, `referenceId` = the late row, `sourceLineId` = the count line,
    `reversesLedgerId` = the count variance row; V701 unique index). Not corrected, listed for a person: serial cells
    (P15-2), lot/FIFO receipts, reversals, clock-skewed rows, counts submitted before V700. `GET /inventory/counts/:id`
    adds `lateTransactions` for a submitted count (additive). Spec deviations: the correction is valued at the
    current AVCO (not the variance row's unit cost, so valuation stays = sub-ledger) and posted today (not on the count
    date); forced lines are corrected like any other line.
57. **Count freeze (IPR 1b).** `POST /inventory/counts/start` accepts `freezeLocation` (additive). While such a draft is
    open, a server-side manual move at the location (direct stock, adjustment, transfer from/to, GRN, any engine move)
    answers 409 "Location `<name>` is frozen for count `<code>`; submit or cancel the count before moving stock there".
    Exempt: the session's own posting, sales (POS jobs: `pos*` / `menu_recipe`; back-office sales, deliveries and
    returns: `cash_sale`, `sales_invoice`, `sales_order(_line)`, `invoice`, `delivery_order`, `sales_delivery`,
    `sales_return`, `credit_note(_cancel)`; P15-2), late count corrections and offline device work. Lock level L3b `freeze:<org>:<location>` (shared per move, exclusive at
    start).
58. **Count guards (IPR 1b T12).** S4: one draft count per location, any type (V701 unique index); starting another
    type answers 409 "Location `<name>` already has an open `<type>` count `<code>`; submit or cancel it before
    starting another count there", and `POST /inventory/adjustments/:id/approve` answers 409 while a count is open at
    the location. S1: the default count type is `cycle` (was `opening`; a start without type and scope now answers 400
    "A cycle count needs a scope: pick categories or products."); an `opening` count needs
    `inventory_valuation:manage`, a location with no ledger rows, and no earlier submitted opening count (400
    otherwise). S7: `saveDraft` and `submit` lock the session row.
59. **Goods receipt backdating limit (ADR-S088 limit 2, P15-6).** A goods receipt dated more than
    `inventory.maxBackdateDays` (default 7, 0 = off) before today answers 400 "Goods receipt date is more than N days in
    the past". Offline receipts are exempt (flagged `lateSync` instead).

### Negative stock policy (as built; R5a evidence for gate G3)

- `inventory.allowNegativeStock` (`SettingRegistry`) defaults to **on**: every issue may take a quant below zero
  unless the item's `stockPolicy` is `block` (and a lot-tracked `block` item never goes short).
- **Off ("strict")**: back-office issues (stock-outs, waste, direct stock out, production, repair parts …) are refused
  with `Insufficient stock for …`; direct stock-out also checks availability up front.
- **POS is never blocked at the till.** Checkout enqueues a `StockPostingJob`; the drain (`pos*` / `menu_recipe`
  sources) is exempt from the strict setting and follows the item stock policy only. With the default policy `warn`
  two terminals selling the last unit both post and the quant ends at −1, strict or not. With policy `block` the
  second sale's line is refused inside its job: the job goes back to `pending` with `lastError` `1 line(s) could not
  be deducted and need review: Insufficient stock …`, an alert is raised, it retries with back-off and becomes
  `failed` after `maxAttempts`; it is handled in the Posting Monitor (`/pos/posting-monitor`). Stock never goes below
  zero then.
- **Offline issues** (`stock.out` pushed through `/sync/push`) reuse direct stock-out. Allowed: the issue posts and an
  `offline_negative_stock` exception records the shortfall (`/sync/stock-discrepancies`). Strict: the op **fails**
  (`status: failed`, `httpStatus 400`, `code BUSINESS_RULE`, not retryable, dead-lettered) and nothing moves — unlike
  offline transfer dispatches, which are kept and held (`offline_held_posting`).
- **Transfers** never take their source below zero, whatever the setting (`Insufficient stock at source`), but they
  ignore sales reservations: a dispatch may leave a location over-reserved (only `processing_job_input`
  reservations block an issue); the integrity `reservations` check reports it as `over_reserved`.
- Tests: `NegativeStockPolicyIT` (two terminals permissive / strict-warn / strict-block, offline after online sale
  permissive / strict, dispatch over reservations, partial receipts + damage + short + recall with the integrity
  report green after each step), `SyncInventoryIT`, `StockConcurrencyIT`.

Approved by the owner 2026-11-23 (inventory hardening R4, workflow integrity, release gate G1):

- **Pick orders net the reversal of their source document.** `WarehouseStore.DOC_ISSUES` (what a sourced pick order's
  document has taken out at the location) now also counts the document's own reversal rows (`stock_out_reversal`,
  `stock_transfer_reversal`) and every return that names one of its issue rows (`reversesLedgerId`: credit notes, sales
  returns), net of the rows that in turn took such a return back (a cancelled credit note). A pick order sourced to a
  reversed stock-out therefore has nothing to ship against (`Post stock-out … before shipping …`).
- **A stock-out or transfer with a live pick order cannot be reversed.** `reverseDocument` refuses with 400
  `<Stock-out|Transfer> <code> has open pick order(s) <codes>; ship or cancel them before reversing it` while a pick
  order sourced to it is draft / released / picking / picked / packed. Test `WarehouseIT.pickOrderAndDocumentReversal`.
- **Order-timed POS stock follows the order** (cancel, void, reopen) and **POS jobs are one per sale** — see pos.md.
  `StockReversalService.returnableByProduct` (new, read) gives what a source can still return per product.

Same as Nest, kept on purpose:

- Prisma tenancy emulation: explicit `organizationId` + `deletedAt IS NULL` on soft-delete models; relation
  includes are not filtered (Prisma's extension does not filter includes either).
- Period-lock and posting-rule resolution (negative stock: see 2–8 above) follow `stock.service.ts` /
  `stock-posting.service.ts` line by line; all money/qty math is `BigDecimal` with decimal.js semantics (`Money.div`, `JsNumbers.dec`).

Kernel fix found while porting: `SequenceService` now creates per-org sequences outside the caller's transaction
(Nest uses the raw client). A business rollback used to drop a freshly created sequence while the in-memory
cache still considered it present (500 on the next `nextval`).

### IPR Phase 3: security and operational controls (2026-11-27, awaiting owner approval)

Evidence: [3-security-controls.md](../audits/ipr-evidence/3-security-controls.md). These items differ from Nest. None
of them changes a path, verb, permission string or response shape. Refusals are new 400/403 messages.

- **S-1: branch-restricted reads cover the caller's locations.** A user restricted to two or more locations who sends
  no `locationId` no longer gets 400 "Choose a location". The read covers all of their locations instead: stock
  levels, item detail, ledger, every `/inventory/reports/*` read and the integrity report. A `locationId` outside the
  access is 403 "This location is outside your data access". A restricted user with no location at all is 403
  "Your data access has no location".
  - The integrity report for several locations is the merge of the per-location reports: a check is ok only if it is
    ok everywhere, and its `locationId` is null.
  - Valuation over several locations is read org-wide and then filtered by location (`ScopedValuation`).
  - The register (`/inventory/reports/register/{kind}`) still refuses restricted users with 403, as before.
- **S-2: costs hidden without `inventory_valuation:read`.** The cost and value fields come back as `null`, with the
  same keys:
  - item list `runningAverageCost`;
  - stock levels `averageCost` and `totalValue`;
  - item detail: product `costPrice`, item costs, batch and ledger costs, PO line prices and `totalValue`;
  - the register's value fields;
  - waste and stock-out lists, detail and summary: `unitCost`, `totalCost`, `totalValue`, ledger values and journal
    debit/credit;
  - transfer detail.

  Quantities stay. The web hides the stock-level cost columns and shows a dash, never a zero.
- **S-4: direct stock needs a named approver.** `POST /inventory/direct-stock/in|out` without `approvedById` is 400
  "Name the approver (approvedById) to post a direct stock movement". In Nest, a null approver plus a PIN matched any
  active user holding that PIN.
  - When the SoD pair is on (stock adjustment for in, stock write-off for out), self-approval is 403 "Separation of
    duties: you cannot approve your own direct stock movement; name another approver and enter their PIN".
- **S3: transit loss approval.** `POST /inventory/transfers/{id}/receive` takes the optional fields `lossReason`,
  `approvedById` and `approverPin`. A damaged plus short value above the setting
  `inventory.transitLossApprovalThreshold` (default 0, base currency, value at the transit location's cost) needs:
  - a reason;
  - an approval: either the engine workflow `stock_transfer_loss` (single-use, consumed with the receipt code), or a
    named approver (self with `inventory_doc:approve`, otherwise PIN).

  Under the stock write-off pair the receiver cannot approve their own loss. The receipt notes carry "Transit loss X:
  reason". The audit row carries the loss value, reason, approver and request.
  - An offline `transfer.receive` (sync) carries no approval. A loss above the threshold is refused, and the push is
    held for review like any other refusal. Below the threshold it posts as before.
- **C-5 / S-7 / S14: manual reservations only.** `POST /inventory/reservations` and `/reservations/consume` accept
  only `sourceType: "manual"`. Any other source type is 400 "Only manual reservations can be created|consumed here
  (sourceType must be "manual"); a document's reservations follow the document". `/release` still accepts any source
  type, so an operator can free a stuck document reservation; that choice needs owner confirmation. The web offers
  Consume only on active manual rows.
- **S5: a replenishment alert re-opens.** When the transfer, purchase request or production request answering an
  alert is cancelled, rejected or gone, the next scan sets the alert back to `open`. It refreshes the figures, sets
  `statusReason` "Re-opened: <doc> was cancelled or rejected", and counts the alert as raised (automatic request and
  notification again). Before, the alert stayed `requisitioned` forever and the rule never fired again.
- **S-8 (web): CSV export** keeps plain numbers such as `-3` or `-12.50` as numbers. Text that starts like a formula
  is still prefixed with `'`.

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

## Integrity checks (R0)

Hardening batch R0 ([review](../audits/2026-11-22-inventory-review.md)). Spring-only, read-only.

`GET /inventory/reports/integrity?locationId=` (`inventory_valuation:read`). The location goes through the user's data
scope like `/reports/reconciliation`. Response: `{checkedAt, locationId, ok, rowLimit: 5000, checks: [{key, ok,
skipped, invariant, tolerance, failures, truncated, rows, note}]}`. `failures` counts every breaking row; at most 5,000
rows per check come back. Decimals in rows are exact decimal strings.

| key | invariant | tolerance |
|---|---|---|
| `ledger_vs_quant` | Σ ledger `quantityChange` = `StockItem.quantity` per (product, variant, location) | 0.000001 |
| `quant_vs_lots` | lot-tracked: max(0, qty) = Σ `InventoryBatch.quantity` | 0.000001 |
| `quant_vs_serials` | in-stock serials ≤ max(0, qty); = qty where `inventory.serialPolicy` is `required` | exact |
| `serial_state` | issued serial: latest movement an outflow; in-stock serial: latest movement an inflow | exact |
| `valuation_vs_subledger` | per product: current valuation by costing method = Σ signed ledger `totalValue` | 0.01 per product |
| `valuation_vs_gl` | the GL tie-out (organisation level; skipped under a location filter) | `inventory.glTieOut.tolerance` |
| `transit` | per open transit transfer and product: dispatched − received − damaged − short − recalled = ledger balance at the transit location; Σ open per product = transit-location quantity (organisation level) | 0.000001 |
| `reservations` | none negative; reserved ≤ on-hand + incoming per cell; none on a missing / closed / cancelled `invoice`, `sales_order_line`, `production_order` or `processing_job_input` | exact |
| `offline_queue` | no open `offline_awaiting_dispatch` older than 24 h on an `in_transit` / `partially_received` transfer | — |

The quantity checks share the reconciliation CTE (`InventoryQueryService.reconciliationCells`); the value checks call
`accounting.InventoryValuationService` (`currentValueByQuant`, `glTieOut`).

`InventoryIntegrityMonitor` (run daily by `InventoryIntegrityJob`, every organisation holding stock): one open
`InventoryException` of kind `integrity_<key>` per failing check (payload: check, invariant, tolerance, failures, first
20 rows, product ids), one in-app alert per run that raised something, never a duplicate while one of the kind is open,
never a correction. `integrity_valuation_vs_gl` is not raised while `inventory.glTieOut.enabled` is off. The value kinds
(`integrity_valuation_vs_subledger`, `integrity_valuation_vs_gl`) hold the period close and are the blocking checklist
item `inventory_integrity_value`; the quantity kinds never hold a close. Test: `InventoryIntegrityIT`,
`PeriodCloseChecklistIT`.

### Report-only checks (IPR Phase 0, 2026-11-27)

Additive field on the same response: `reportOnlyOk` and `reportOnlyChecks` (same item shape). `ok` and `checks` are
unchanged: report-only checks never make `ok` false. The monitor raises a failing one as `integrity_<key>` (alert payload
`reportOnlyKinds`); none is in `InventoryTieOutMonitor.INTEGRITY_VALUE_KINDS`, so none can hold a period close.
`unlinked_restocks` and `period_shifted` are informational (always `ok`; rows show the scale).
Organisation-level checks are skipped under a location filter.

| key | flags |
|---|---|
| `negative_quants` | `StockItem.quantity` < 0 |
| `issued_serial_located` | serial `issued` whose latest outflow row was reversed by ≥ 1 unit net (serial issue does not clear `locationId`) |
| `over_returned` | a ledger row reversed (net, via `reversesLedgerId`) by more than its own quantity |
| `unlinked_restocks` | informational: undo / restock rows without `reversesLedgerId`, per source type |
| `gl_tieout_by_account` | per inventory account: valuation (by costing method) vs Σ `baseDebit − baseCredit` (organisation level) |
| `stock_posting_jobs` | `StockPostingJob` failed, or pending / processing over 6 h (organisation level) |
| `transit_age` | transit transfer open over 7 days |
| `invoice_undelivered` | F1: posted invoice whose delivery orders are all cancelled / closed and invoiced − credited > delivered |
| `delivery_return_overlap` | F3: fleet put-back + linked returns > quantity the trips issued, per delivery group and product |
| `quality_hold_unquarantined` | quality lot on hold still in a non-quarantine location |
| `period_shifted` | informational: movements whose journal falls in another fiscal period than the movement (org time zone) |
| `feature_risk` | P2 findings promoted by feature use (organisation level), see [feature-risk map](inventory-feature-risk-map.md) |

`GET /inventory/reports/feature-usage` (`inventory_valuation:read`, refused for branch-restricted users): features in
use with bounded counts and the promoted P2s. Read-only data checks for a restored copy:
`docs/audits/2026-11-27-inventory-data-checks.sql`. Tests: `InventoryIntegrityP0IT`, `InventoryLedgerImmutabilityTest`.
