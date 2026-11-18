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

## Coverage gaps: changed code without a dedicated test

The stream code for these findings is merged and the existing ITs pass over it, but no test was written that fails
without the fix:
- gB: h01-F7 (indexes, V540), h01-F8 (drift detection).
- gC: h02-F2 (costing locks between activation and moves), h02-F6 (re-costing posts to the STOCK_IN account),
  h02-F7 (job failures counted, effective date in an open period), h02-F10 / ACR-14 (assign release).
- gE: h04-F2 (budget and branch on sourcing / RFQ / agreement orders), F3 (award approval snapshot), F4 (bank details
  dual control and masking), F5 (inspection hold and cancel reason), F7 (portal dates and first promised date), F8
  (RFQ order locking), F9 (budget consumption). The existing `SourcingIT`, `PlanningIT`, `SourceToPayEndToEndIT` and
  `PartnerProfileIT`, as updated by gE, exercise parts of these.

## Still open (from HANDOFF.md "Then integrate")

1. Per-stream notes files (`gA…gF.md`) were never written by the streams. This file stands in for them. A
   per-finding status with the Nest deviations still has to go into the module specs' Deviations sections
   ("Approved by the owner 2026-11-17").
2. `docs/PORT_STATUS.md`: the item "P1 / P2 gaps listed in the audit" is still unticked.
3. The readiness audit is not updated yet.
4. Merge to `main`, push, and watch CI.
5. Migration numbering: `phase/LP0-exception-intelligence` uses V520–V522, which collide with V520 on `main`. It must
   be renumbered above V559 before it merges.
