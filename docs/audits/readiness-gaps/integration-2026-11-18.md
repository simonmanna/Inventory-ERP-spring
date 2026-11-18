# Phase B readiness gaps: integration (2026-11-18)

This picks up the work stopped in [HANDOFF.md](HANDOFF.md). The six backend WIP streams (gA–gF) are merged into
`phase/inv-proc-gaps` (branched from `main` at `1dd43608`). They compile, and their test classes and the existing ITs
for the code they touch pass. **The branch is local: it is not merged to `main` and not pushed.**

## Merge

Branches were merged in this order: gD `phase/gaps-p2p-core`, gA `phase/gaps-inv-engine`, gB `phase/gaps-warehouse`,
gC `phase/gaps-valuation`, gE `phase/gaps-proc-controls`, gF `phase/gaps-landed-cost`.

Conflicts and how they were resolved:

| File | Resolution |
|---|---|
| `kernel/approvals/SegregationOfDutiesService` | All new pairs kept: `supplier_claim_settlement` (gD), `stock_write_off` (gA), `landed_cost_approval` (gF). gC adds `inventory_valuation` in its own static block. That makes 11 pairs (7 before). |
| `ApprovalControlsIT.sodCatalogue` | Now expects 11 pairs. |
| `ApiTestSupport.KEY_REQUIRED` | Union of gD's `procurement/goods-receipts/adhoc` and gA's `inventory/transfers/{id}/receive`. |
| `InventoryIT.authorizationMatrix` | Stays at 144 (no new inventory route). |
| `inventory/application/StockService` | Both kept: gA's `lockQuants` (h00-F7) and gC's costing-lock helpers (h02-F2). |

## Fixes made during integration

- **V548** `DebitNoteReceiptMatch` RLS policy aligned with the house pattern. The stream's policy refused every row when
  `app.org_id` was empty, which breaks tenantless jobs. It now uses
  `coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = …` with `WITH CHECK`.
- **V544 / V556**: the handoff called them unfinished. Both were read and are complete as written; nothing was changed.
- **gF `LandedCostVoucherService.linkedElsewhere`**: ordered by `c."createdAt"`, but `LandedCostCharge` has no such
  column, so every voucher with a linked bill answered 500. It now orders by the voucher's `createdAt`.
- **gF `LandedCostReconciliationService`** (h05-F15): an organisation without a `cogs` / `wip` mapping got a 400
  ("Account mapping 'cogs' is not configured"). The report is read-only, so a missing mapping now reads as zero.
- **gD `ProcureToPayGapsIT`**: the test read a non-existent `StockItem."totalValue"`. It now uses
  `quantity × runningAverageCost`.
- **gB `WarehouseReplenishmentGapsIT`** (h04-F10): the expectation was wrong. A position equal to the minimum is not
  below it (min/max rule, Nest parity), so the suggestion is 0. The test now uses 1 pack (position 10 < min 20 →
  suggest 40).
- **`ProcurementIT.authorization`**: 200 routes (+1: gD's `GET /procurement/purchase-orders/{id}/payable`, h06-F6).

## Test classes written during integration

- `inventory/ValuationGapsIT` (gC; the stream never wrote it). It covers h02-F3 duplicate pool lines, F4 reverse
  after a later value change, F5 maker-checker / cash offset account / policy edit only while a draft, F7 standard
  without `convertMethod`, and F9 6-decimal amount.
- `procurement/ProcurementControlsGapsIT` (gE; the stream never wrote it). It covers h04-F6 zero-quantity offer, sealed
  bids and quotes after the deadline, and h04-F11 supplier approval maker-checker.

## Tests run (fastTest, Testcontainers Postgres, fresh reused container)

| Run | Result |
|---|---|
| The 6 gap ITs + `ApprovalControlsIT` (after fixes) | all green |
| 76 existing ITs covering inventory, procurement, landed cost, costing, approvals, idempotency, POS flows, expenses, FX payments (474 tests) | 473 green; the 1 failure was the route count above, fixed and rerun green |
| Web: `tsc --noEmit` | 0 errors |
| Web: eslint on the 4 changed web files | 0 errors, 3 `no-explicit-any` warnings |

Not run: the full `./gradlew build` (CI runs it), the seed / architecture suites (`fastTest` skips them), JaCoCo,
and the parity harness.

## Coverage follow-up (2026-11-18, after the merge)

The findings first merged without a dedicated test now have one. Every test below passes on `main`.

| Finding | Test |
|---|---|
| h01-F7 indexes (V540) | `WarehouseReplenishmentGapsIT.warehouseLookupIndexes`: the indexes exist, and the open-task lookup plans on `WarehouseTask_from_bin` |
| h01-F8 drift detection | Covered by the h01-F2 tests: goods awaiting a pick count as still in the location, and only in-progress pick orders count as staged. Automatic bin decrement stays deferred (ADR-S026 "Still open"). |
| h02-F2 costing locks | `ValuationGapsIT.activationWaitsForInFlightMove`: a standard activation waits while a move holds the product's costing key |
| h02-F6 STOCK_IN account | `ValuationGapsIT.recostingUsesItemStockAccount`: the re-costing posts to the product rule's stock account, not `stock_valuation` |
| h02-F7 effective date | `ValuationGapsIT.policyEffectiveDateMustBeOpen`: refused inside the books lock (the convertMethod check was already tested) |
| h02-F10 assign release | `LandedCostLineageIT.unpairedThenAssigned`: the assignment writes a `CostValueAdjustmentRelease` row (`assigned`, 600) |
| h04-F2 branch on sourced orders | `ProcurementControlsGapsIT.sourcedOrderCarriesBranch`: the order takes the request's branch and its budget counts it |
| h04-F3 award approval | `ProcurementControlsGapsIT.awardApprovalGatesOrders`: no orders while the award is pending or after it was rejected |
| h04-F4 bank details | `ProcurementControlsGapsIT.supplierBankDetails`: masked out, a masked round-trip keeps the stored value, a supplier's account cannot be added through the profile |
| h04-F5 inspection | `inspectionCancel` (reason, not the receiver, recorded), `billHoldByLines` (a bill without `purchaseOrderIds` is held), `rejectedQuantitiesOnClaim` (rejected units become claim quantity lines) |
| h04-F7 portal | `ProcurementControlsGapsIT.portalConfirm`: a bad date is a 400, the supplier confirms once, the first promised date is kept |
| h04-F8 RFQ order lock | `ProcurementControlsGapsIT.concurrentAwardOrders`: two concurrent order calls → one 200, one 400, one PO |
| h04-F9 budget consumption | `ProcurementControlsGapsIT.budgetIgnoresCancelledQuantity` |
| h04-F11 policy approver | `ProcurementControlsGapsIT.policyApproverIsNotLastEditor` |

Still without a dedicated test: h02-F7 job-failure metrics (`CostActivationFailures`), which need the scheduled
activation job to run.

The tests were written against the fixed code. They are not re-run against the pre-fix code; each assertion targets
the behaviour the fix introduced.

## Still open

1. Merged and pushed: `d7442438`, CI green. `docs/PORT_STATUS.md` is ticked.
2. The owner decided on 2026-11-18 that the Spring app is independent of the NestJS reference, so these fixes record no
   "Nest deviations".
3. The readiness audit (`../2026-11-17-inventory-procurement-readiness.md`) is not rewritten. This file is its
   follow-up.
4. `phase/LP0-exception-intelligence`: migrations renumbered V520–V522 → V560–V562.
