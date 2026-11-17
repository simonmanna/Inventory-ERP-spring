# Inventory and procurement readiness gaps: handoff (2026-11-17)

The owner asked for the inventory and procurement modules to be made production-ready in two phases:

> "fix all the blockers first and then commit and merge and then push to remote and then fix and enhance the gaps
> and then commit and merge and push to remote"

That request counts as owner approval of the Nest-parity deviations these fixes need. Record each one in the module's
spec Deviations section as "Approved by the owner 2026-11-17 (inventory and procurement readiness gaps)".

Later the owner stopped the work midway and asked for it to be pushed as it stood: "another ai will take over". This
document is where to start.

## State

**Phase A: blockers. Done.**
- B1–B6, UB1 and UB2 are on `main`: `fe0793f4`, merged as `0772acb2`; the CI fix is `82c0a98d`.
- CI is green on `82c0a98d` (run 44106688976).
- Record: [the audit](../2026-11-17-inventory-procurement-readiness.md), which includes the data-check SQL to run once
  after deploying V535.

**Phase B: the P1 / P2 gaps. Partly done.** The work was split into seven streams, all branched from `0772acb2`:
- **Web stream (`gW`): finished, reviewed and on `main`.**
  - It passed type checking, lint and the i18n check.
  - Its route guards were checked against the server permissions, and its new query parameters against the server
    whitelists.
  - Notes: [gW-web.md](gW-web.md).
- **The six backend streams: stopped mid-work.**
  - Each one's work is committed as a single WIP commit on its branch and pushed.
  - **None of them is compiled and tested to completion. Do not merge them as they are.**

| Stream | Branch (WIP head) | Scope (auditor report → findings) | Migrations | Test class | Size vs `0772acb2` | Where it stopped |
|---|---|---|---|---|---|---|
| gA inventory engine and documents | `phase/gaps-inv-engine` (`e87082d0`) | h00 F1–F11; h02 F8; h06 F2 server side; h06 F7 server side | V536–V539 (none yet) | `inventory/InventoryEngineGapsIT` (written) | 17 files, +1181 −61 | code and test written; never compiled. New `kernel/idempotency/IdempotencyTransactionListener.java` (h06-F7) |
| gB warehouse, replenishment, inventory reads | `phase/gaps-warehouse` (`d9f14495`) | h01 F2–F12 + minor items; h04 F10 | V540–V543 (`V540__warehouse_lookup_indexes.sql`) | `inventory/WarehouseReplenishmentGapsIT` (written) | 18 files, +1888 −365 | rewriting the stock-levels path in `InventoryQueryService` (h01-F6) |
| gC valuation | `phase/gaps-valuation` (`93eef53f`) | h02 F2–F7, F9, F10; h05 F9 (ACR-14) | V544–V547 (`V544__revaluation_pool_lines_and_precision.sql`, **unfinished**) | `inventory/ValuationGapsIT` (not written) | 15 files, +771 −123 | writing V544 (F6/F9, quant locks), posting-date helpers, effective-date validation. New `CostingLocks`, `CostActivationFailures` |
| gD procure-to-pay core | `phase/gaps-p2p-core` (`58221087`) | h03 F3–F10, with F6 limited to bill post, PR self-approval and PO activate; Phase A residuals; h06 F6 | V548–V551 (`V548__debit_note_receipt_match.sql`) | `procurement/ProcureToPayGapsIT` (written; was being run) + `TestApprovers`, `TestSuppliers` | 36 files, +2003 −225 | had compiled and run its tests; fixing assertions in its test class |
| gE procurement controls and sourcing | `phase/gaps-proc-controls` (`60e57ee1`) | h04 F2–F9, F11; the PO-change part of h03 F6 | V552–V555 (`V552__procurement_controls_gaps.sql`) | `procurement/ProcurementControlsGapsIT` (not written) | 23 files, +969 −167 | about to write its test class |
| gF landed cost | `phase/gaps-landed-cost` (`d9b53d68`) | h05 F1–F8, F10–F15; h06 F13 | V556–V559 (`V556__landed_cost_gaps.sql`, **unfinished**) | `procurement/LandedCostGapsIT` (written) | 14 files, +1563 −142 | writing V556 for F14 (FK and indexes). New `LandedCostBillGuard` |
| gW web | `phase/gaps-web` (`16064176`) | h06 F2 client side, F5, F7–F12, F14 | — | — (web only) | 33 files | **done, on main** |

The Phase A residuals in gD's scope are:
- require a supplier server-side on goods receipts without a PO, and update the test helpers that create
  supplier-less receipts;
- serialize PO payments per supplier, because the B2 cap reads the supplier's AP ledger.

The auditor reports are in [auditor-reports/](auditor-reports/): h00 stock engine, h01 warehouse, h02 valuation,
h03 procure-to-pay core, h04 procurement controls, h05 landed cost, h06 web. Their line numbers refer to `46281ee2`.

Already fixed, so out of scope for Phase B:
- h03 F1/F2 (B1/B2) and h01 F1 (B3);
- h02 F1 (B4) and h04 F1 (B5);
- h06 F1 (B6), F3 (UB1) and F4 (UB2).

## How to continue

The rules, the test setup (an external Postgres, environment variables, `fastTest`) and the notes format are in
[BRIEF.md](BRIEF.md). CLAUDE.md's build rule applies: no local `./gradlew build` unless the owner asks, because CI runs
it.

For each backend stream:
1. Check out its branch and read the WIP diff against `0772acb2` (`git diff 0772acb2 <branch>`).
2. Read the auditor report for the stream's scope.
3. Verify what the WIP already does. Nothing in it has been verified; gC and gF stopped while writing a migration.
4. Finish the remaining findings. Verify each finding in the code before fixing it; defer only with a written reason.
5. Compile with `./gradlew compileJava compileTestJava -q`.
6. Complete the stream's test class. Every fixed P1 needs a test that fails without the fix.
7. Run that test class plus the existing ITs for the code it changed, with `fastTest`.
8. Write `docs/audits/readiness-gaps/<stream>.md` (format in BRIEF.md).
9. Run `spotlessApply`, then commit. Replace the WIP commit if you prefer.

Then integrate:
1. Merge every stream into one branch from `main`.
2. Resolve the conflicts (see below).
3. Run all the new test classes and the affected existing ITs.
4. Consolidate the notes files into the specs and `docs/PORT_STATUS.md`. Under "Inventory and procurement readiness
   blockers", the open item "P1 / P2 gaps listed in the audit" is still unticked.
5. Update the readiness audit.
6. Merge to `main`, push, and watch CI.

### Expected merge conflicts

Each stream owned its files, but these were shared:
- `kernel/approvals/SegregationOfDutiesService`: several streams add SoD pairs.
- `inventory/application/StockService`: gA changes serial costing, adjustments and counts; gC adds an engine-side
  re-read of the costing config under the lock.
- `invoicing/application/VendorBillService`: gD owns the ordinary post/approval path; gF owns the voucher-generated bill
  path.
- `procurement/application/BillControls`: gE owns the inspection hold; gF owns the duplicate check.
- `inventory/application/StockReversalService`: gA owns transit reversal; gD owns the foreign-currency GRN mirror.
- `InventoryIT.authorizationMatrix`: it expects **144** inventory routes on `main`. Add any inventory routes a stream
  creates.
- Other authorization-matrix counts for procurement and core may need the same update.

### What each stream was asked to do

**gA: inventory engine and documents**
- F1: GL = sub-ledger for serial items moved without serials; never issue at `Product.costPrice` when the quant carries
  the actual cost.
- F2:
  - Set the stock-out value before the approval gate.
  - Send money values, not unit counts, for adjustments and transfers.
  - Add an SoD pair so the approver of a stock-out or waste document cannot be its creator, like `STOCK_ADJUSTMENT`.
- F3 + h02-F8:
  - Refuse at save the rules that cannot resolve an account: a missing key, or one-sided overrides.
  - At post, a rule set that resolves no account fails loudly.
- F4: transit receive requires an Idempotency-Key. Add `@Idempotent` on the document actions that move stock.
- F5: a negative direct-in unit cost gets a 400; zero is allowed.
- F6: adjustment approve applies the delta under the quant lock, like the A-2/A-3 pattern.
- F7: count variance is computed under the quant lock.
- F8: count cancel claims the row with a status predicate.
- F9: transit reversal respects the `StockTransferLayer` pins.
- F10: the transfer line's lot is honoured.
- F11: document lists without per-row queries, with the same response shape.
- h06-F7:
  - A request whose transaction rolled back must release its idempotency record, so a retry with the same key
    re-executes.
  - Read why the POS/cash/expense/payment routes are special-cased first.
  - The web already handles `safeToRetry: true` in a 409 body.

**gB: warehouse, replenishment and inventory reads**
- F2: sourced pick orders keep bins in step with the ledger in either order; fix the reconcile advice in the 400.
- F3: a manual ship is a stock-out, so it is valued, goes through approvals, and the responsible person is not the
  approver.
- F4: replenishment honours branch / LocationScope, and a 403 does not roll back the org scan.
- F5: per-rule isolation in the scan, plus rules whose source location was deleted.
- F6: `locationBreakdown` is scoped; the lowStock/outOfStock filters run in SQL.
- F7: indexes, and no full-history scans.
- F8: cheap drift detection improvements; automatic bin decrement may be deferred (an open ADR item).
- F9: the stock health value is method-correct, using `InventoryValuationService`.
- F10: no N+1 in the ledger detail, reconciliation or item-movement opening sums.
- F11: `@Idempotent` on ship and task complete; `warehouse-api.ts` sends stable keys.
- F12: concurrent redelivery cannot create two draft requests.
- Minor items: validate the putaway `batchKey`; lock the bin in `updateBin`.
- h04-F10:
  - per-location on-order and requested quantities, with unit-of-measure conversion;
  - validate supplier and branch ids;
  - an org filter on the Partner join.

**gC: valuation**
- F2: activation takes the engine's `avco:` advisory keys, in the engine order; the engine reads method, tracking and
  standard under its lock.
- F3: no duplicate pool lines in one revaluation or NRV document (400, plus a unique index if possible).
- F4: reverse never overwrites later value-only changes (refuse, or reverse by delta); post re-checks STANDARD and
  serial.
- F5:
  - Maker-checker (an SoD pair).
  - The offset account is checked against the V518 control-account policy.
  - Policy update uses a status predicate.
- F6: re-costing posts to the item's resolved STOCK_IN account.
- F7:
  - Activation job failures are visible (`metrics.jobFailed`).
  - Standard approve checks `convertMethod`.
  - `effectiveFrom` is not earlier than the lock date.
- F9: value-only ledger rows per quant, with consistent rounding.
- F10 + ACR-14: an assign records a release, uses the same account as the automatic path, and accrual clearing handles
  it.

**gD: procure-to-pay core**
- F3: foreign GRN reversal balances; `settleCashReceipt` uses the currency-tagged overload.
- F4 (debit notes):
  - A return for unbilled goods clears GRNI and the receipt's open quantity; a return against a posted bill reduces AP
    and the bill.
  - Posting honours the note's currency and rate.
  - `documentId` is validated.
  - The location is deterministic.
- F5 (GRN reversal):
  - Refused, or correctly unwound, when claims, returns to vendor or accruals reference the receipt.
  - The checks run inside the transaction, under the matcher's lock order.
  - The received quantity is reduced in PO units.
- F6:
  - Bill post refuses a rejected bill, or one changed since approval, even without a workflow.
  - No self-approval of a purchase request or a PO.
- F7: claims are capped against the document, with maker-checker; a reversal route is optional.
- F8: PO receipts value at the PO price; a different receiver cost is refused or allowed within a documented
  tolerance.
- F9:
  - PO delete is transactional, with `returnOpenBalance` and `commitments.sync`.
  - activate has a status guard.
  - Typed GRN draft bodies.
  - Bounded debit-note quantity and price.
  - A rate ≤ 0 gets a 400.
  - `@Idempotent` where a retry duplicates.
- F10: no N+1 in lists.
- Phase A residuals: listed under State above.
- h06-F6: expose the server's payable figures for the PO Pay screen (`PurchaseOrderPayPage.tsx`, gD owns it), and send
  `method` / `cashRegisterId` / `paidAt`.

**gE: procurement controls and sourcing**
- F2: sourcing, RFQ and agreement orders carry their department and branch; budget control (with its lock) on those
  paths and on applied PO changes; delegation does not skip a null branch.
- F3:
  - The `rfq_award` amount is functional-currency.
  - No orders after a rejection.
  - A re-award refreshes a pending request.
  - The approved snapshot is compared with the current award.
- F4:
  - Bank details go through the `SupplierBankAccount` dual control, and account numbers are masked.
  - SoD refuses, rather than skips, when no user is present.
  - Unverified accounts are blocked where a payment path knows the account.
- F5:
  - The bill hold finds bills by PO and receipt.
  - Cancelling a hold needs a reason and SoD.
  - Rejected quantities do not stay sellable while the claim credits PPV.
- F6: sealed bids, no quotes after the deadline, and no zero-quantity ranking.
- F7: portal dates are validated (400, never 500); tokens are masked in logs; the first promised date is kept.
- F8: RFQ orders lock the RFQ row, with guarded award linking and the transition in the same transaction.
- F9: budget consumption counts open balances, and a multi-request PO releases every request.
- F11: the policy approver is not the last editor; supplier approval is maker-checker; no SoD skip for OAuth tokens.
- The PO-change part of h03 F6: an applied change passes amount bands, delegation and budget, like a new PO.

**gF: landed cost**
- F1: a generated bill cannot duplicate an ordinary bill, even with the policy at its default.
- F2: the receipt screen moves to the voucher engine, or the legacy path is made correct and controlled. Pick the
  proportionate option and document it.
- F3: a linked bill is validated (clearing account, amount, currency, not over-linked) and goes through the duplicate
  registry.
- F4: SoD on voucher approval, duplicate overrides and generated bills.
- F5: credit vouchers need their original still posted.
- F6: the legacy post locks the GRN row, like the reversal does.
- F7: a STANDARD reversal credits the accounts its releases actually went to.
- F8: LC-3 uses `remainingAmount` over posted and partially_cleared.
- F10: receipt rows are matched by product AND variant, in a deterministic order.
- F11: lot lineage only counts this receipt's batches.
- F12: Idempotency-Key required on voucher money routes.
- F13: the split is computed under the stock lock.
- F14: the customs link is validated and audited.
- F15: the reconciliation uses batch queries in a read-only transaction.
- h06-F13: a Reverse action in `landed-cost-panel.tsx`.

## Cross-stream notes so far

From the web stream ([gW-web.md](gW-web.md)):
- The web now sends stable Idempotency-Keys to these routes, which do not have `@Idempotent` yet, so they do not
  dedupe:
  - PO create;
  - `POST /procurement/goods-receipts`;
  - debit-note create and cancel;
  - claim create, accept, submit, reject and cancel.
  Add it in gD and gE.
- h06-F7 needs gA's server change. The client offers a fresh attempt after an API-reported 5xx, or when a 409 says
  `safeToRetry: true` (in the body or in `recovery`).
- Server paging is wanted for:
  - transfers and adjustments, which are unbounded;
  - waste, capped at 500;
  - debit notes and purchase requests, capped at 200;
  - supplier options, which return 50 with no total;
  - the PO list's `status` filter, which takes only one value.
- `useIdempotencyKey.settle` keeps its key after a pending or concurrent 409. This also affects `InventoryCountPage`.
- Two pickers are now hidden without a permission:
  - the stock ledger's product filter needs `product:read`;
  - goods-receipt create's order picker needs `purchase_order:read`.
- Direct stock keeps one key per dialog, because its body carries the approver PIN.

Other notes:
- **Migration numbering.** V536–V559 are reserved for these streams. The `phase/LP0-exception-intelligence` work
  (another session) must renumber its migrations above V559.
- **Phase A residual risks**, listed in the audit, are both in gD's scope:
  - supplier-less receipts are still accepted server-side;
  - the B2 supplier ledger cap is not serialized.

## On the original machine

- Worktrees `/home/Inventory-ERP-spring-1-g{A,B,C,D,E,F,W}` hold the branches above.
- `/home/Inventory-ERP-spring-1-gaps` holds the integration branch `phase/inv-proc-gaps`, which equals `main` after
  this handoff.
- The per-stream test databases were removed; recreate one as in BRIEF.md.
- The main checkout `/home/Inventory-ERP-spring-1` holds another session's uncommitted loss-prevention work. Do not
  touch it.
