# Accounting and costing review — 2026-11-17

- Branch: `phase/acr-costing-integrity` (worktree `Inventory-ERP-spring-1-acr`), migrations `V530`–`V534`.
- Owner approval: the owner asked for every fix and enhancement in the review (2026-11-17). That covers the deviations
  from the Nest reference listed here. Each deviation is also recorded in its spec's "Deviations" section.
- Markers in the code: `Review 2026-11-17 (ACR-n)`. The tables below give the ACR number of each fix.

## Verdict at review time

**Not production-ready.** Two parts were already sound:

- **Ledger core:** balanced, immutable, idempotent postings.
- **Valuation engine:** AVCO, FIFO, standard and specific identification.

But several parts produced wrong books:

- purchasing postings;
- period controls;
- costing reversals;
- single-step production costing.

Costing coverage was broad, but some techniques were missing or half-wired, and many routes had no screen.

## Findings and outcome

### Accounting flow

| # | Finding | Outcome |
|---|---|---|
| A1 | A closed period can be reopened by a plain `PATCH /fiscal-periods/{id}` (status, dates, delete) | **Not fixed here.** `FiscalPeriodService` / controller belong to the parallel `phase/acc-erp-controls` work |
| A2 | "Reverse" works on any journal entry, including sub-ledger documents | **Not fixed here.** `JournalEntryService` / controller belong to `phase/acc-erp-controls` |
| A3 | PO receipt + supplier bill = two open payables (AP ageing, PO pay and bill pay) | **Fixed (ACR-2, V530).** A billed receipt is the bill's open item only. PO payments are applied to the bill that takes the receipt over (`PurchaseOrderBillApplication`). The PO pay route refuses billed receipts. `ApAgingService`, `OpenItems` and `ReportSnapshotService` use the same measure. Test: `PoBillApplicationIT` |
| A4 | A bill priced or taxed differently from the receipt leaves a permanent AP difference | **Fixed (ACR-3).** The difference is posted both ways (PPV, input VAT, AP), so the ledger's AP equals the bill's residual. Test: `PoBillApplicationIT` (bill below the receipt price) |
| A5 | Return to supplier on an unbilled receipt debits AP; foreign-currency debit notes post document amounts as base | **Not fixed here.** `DebitNotesService` belongs to `phase/acc-erp-controls` |
| A6 | A bill auto-receives any quantity not matched to a receipt | **Option added (ACR-4).** Setting `procurement.billWithoutReceipt`: `auto_receive` (default, the Nest behaviour) or `block` (three-way match). Test: `PoBillApplicationIT` |
| A7 | A second reopen can pick the already-reversed closing entry | **Fixed (ACR-1).** It now uses the period's own posted closing entry. Test: `PeriodReopenIT` |
| A8a | Recurring entries post with no second approver | **Not fixed here** (`RecurringJournalService`, `phase/acc-erp-controls`) |
| A8b | Manual journals can hit inventory / AR / AP / WIP control accounts | **Not fixed here** (`JournalEntryService`, `phase/acc-erp-controls`) |
| A8c | A failed POS stock-posting job blocks period close for ever | **Fixed (ACR-17).** New `POST /pos/posting-monitor/jobs/{id}/abandon` (note ≥ 10 characters, failed jobs only). Both the close gate and the checklist count only stock documents raised up to the period end. Tests: `PostingMonitorAbandonIT`, `PeriodReopenIT` |
| A8d | Business events are written after commit, so a crash can lose one | **Not addressed** |

### Costing

| # | Finding | Outcome |
|---|---|---|
| C1 | Reversing a completed single-step production order leaves its overhead in WIP | **Fixed (ACR-5).** The reversal takes the absorbed overhead back out (Dr Overhead Absorbed / Cr WIP). Test: `ProductionReversalOverheadIT` |
| C2 | The costing-period lock is not checked on reversals | **Fixed (ACR-6).** The new `CostingReversals` is used by 14 engines. It checks the costing lock and takes the shared side of the cost-close advisory lock. Test: `CostingReversalIT` |
| C3 | Late costs on goods transferred to another warehouse stay on 1396 and are never released | **Fixed (ACR-9).** Each share follows its goods through transfers (`LineageCapitalisation.spreadFollowing`), also in absorption settlement. Test: `LateCostWipCascadeIT` |
| C4 | Reversing a cost run that was already cascaded credits WIP twice | **Fixed (ACR-8, ACR-14, V531).** Cleared amounts are recorded per WIP share. Releases are recorded per target. An accrual clearing takes the released part back from where the release put it. The cascade carries only what is still in WIP. Reversals are refused while a later run or clearing depends on them. Test: `LateCostWipCascadeIT` (5 scenarios) |
| C5a | Costing-run reversals are dated today, not in the original period | **Fixed (ACR-6).** The reversal is dated in the original period while it is open (owner decision D-B6), otherwise today. Test: `CostingReversalIT` |
| C5b | Allocation cycles can allocate the same cost twice through overlapping date ranges | **Fixed (ACR-7).** An overlapping posted run blocks the new one (409). The reciprocal method is now solved exactly by Gaussian elimination instead of a 100-round iteration. Tests: `CostingReversalIT`, `AllocationCalculatorTest` |
| C6 | Standard-cost variances split with mixed units (BOM unit against base unit) | **Fixed (ACR-10).** The split is in base units. Test: `StandardUnitsYieldIT` |
| C7 | Process costing strands cost in WIP when a period closes with cost but no units | **Fixed (ACR-11).** Such a close is refused. A carried-forward opening is locked. Cost recorded without a credit account must name the posted journal entry that put it in WIP, and only up to what that entry debited to WIP. Test: `ProcessCostingGuardsIT` |
| C8a | Production can consume stock that is not on hand, at a stale cost | **Option added (ACR-19).** Setting `costing.production.requireMaterialsOnHand` (default off). Applies to single-step orders and multi-stage backflush issues; manual stage issues were already strict. Tests: `ProductionMaterialsOnHandIT`, `MultiStageProductionIT.stageMaterialsMustBeOnHand` |
| C8b | Standard cost roll-up ignores BOM yield | **Fixed (ACR-18).** The standard is per good unit. Test: `StandardUnitsYieldIT` |
| C8c | Quarry amortisation (and unwinding) can be run twice for one period | **Fixed (ACR-15).** 409 when the period overlaps a posted one. Test: `QuarryRehabMovementsIT` |
| C8d | Three cost-close gates pass when any one item is done | **Fixed (ACR-16).** Absorption needs every work centre with overhead to settle. NRV needs every item still written down to be re-assessed. Quarry needs every provision and stripping asset that has something to post. Each gate lists what is missing. Tests: `AbsorptionSettlementCausesIT.everyCentreMustSettle`, `CostCloseIT.nrvGateNeedsEveryWrittenDownItem`, `QuarryRehabMovementsIT` |
| — | Found while fixing: a cancelled order's absorption blocked the work centre's settlement for ever | **Fixed (ACR-12).** Test: `AbsorptionSettlementCausesIT.cancelledOrderDoesNotBlock` |
| — | Found while fixing: reversing a standard-costed order left its variance rows, so the material ledger pooled them | **Fixed (ACR-13).** The rows are offset on reversal. Test: `StandardProcessCostingIT` |
| — | Found while fixing: profitability facts were stored at 2 decimals, so a busy ledger showed a false 0.01 difference | **Fixed (V532).** Stored at 6 decimals. Test: `ProfitabilityReconciliationIT.demoBooks` |

### Costing techniques added

| Technique | What was built | Tests |
|---|---|---|
| Mix and yield variances | Material usage split into **mix** and **input yield**, per material and per order. Applies when two or more materials share one unit; otherwise a note says why | `ManufacturingCostingIT.mixAndYield` |
| Labour, machine and overhead variances | Labour **rate** and **efficiency**, machine **spending** and **efficiency**, overhead **spending** and **efficiency**, and **energy** | `ManufacturingCostingIT` |
| Variance reconciliation | Every order shows actual cost − standard cost (good output, or the planned run while open) = variances, with what is left unexplained. The variance `lines` carry a label, a parent and a plain explanation. **The order total now includes labour rate, machine and energy**, and the standard includes machine time | `ManufacturingCostingIT` |
| Joint cost by net realisable value | Allocation method `net_realisable_value`: quantity × (sales value − costs after the split-off, floored at 0). Costs after the split-off are kept on the BOM's co-products and the main output's rule (V533) | `JointCostAllocatorTest`, `MultiStageProductionIT.jointCostByNrv` |
| Rework costing | `POST /manufacturing/production-orders/{id}/rework` takes `treatment`: `normal` (default; the cost stays in the units) or `abnormal`. Under `abnormal`, the units keep the cost they carried and the extra cost is expensed (IAS 2.16(a)) as `reworkLoss`, taken back if the order is reversed (V534) | `ShopFloorIT.abnormalRework` |
| Cost explanation | `GET /inventory/cost-explanation?productId&locationId&limit`: why an item costs what it does. It shows the method, the value per location, the price-difference and NRV adjuncts, value-only changes, and every movement with running quantity, value and unit cost | `CostExplanationIT` |

**Still missing (not built):**

- target costing;
- throughput accounting;
- transfer pricing;
- the retail method;
- drop-ship, consignment, and kits outside POS;
- FIFO for items that are not lot-tracked;
- periodic actual cost for non-standard items;
- actual subcontracting cost.

LIFO stays blocked (IFRS).

### Ease of use

New web screens:

- **Why this cost?** (`/inventory/cost-explanation`, also from the item's menu).
- **Cost close** (`/costing/close`): checklist with remedies, reconciliations, adjustment runs, locked periods, price differences and NRV.
- **Profitability** (`/costing/profitability`): margin ladder, reconciliation and simulation.
- **GRNI reconciliation** (`/procurement/grni`).

New controls on existing pages:

- Posting Monitor: abandon a failed job.
- Quarry: unwind and amortise for a period.
- Process costing: a journal-entry reference.
- Order cost page: the full variance breakdown with explanations and the reconciliation.
- BOM stage set-up and the default allocation rule: the NRV method and costs after split-off.
- Order page: the rework treatment and the expensed loss.
- Inventory–GL tie-out: the remedy for unposted stock movements told users to run a Nest script
  (`pnpm --filter @erp/api backfill:inventory-gl-gaps`). That script has no Spring equivalent. The page now describes
  the correcting journal entry instead. A catch-up tool would need a new admin route and permission.

Not done:

- Journal drill-down to documents.
- The product form still sends `AVCO` (`ProductEditPage`, owned by `phase/acc-erp-controls`).
- A wider jargon clean-up.
- Screens that still ask for raw ids.

## Not addressed

### Production-costing reviewer findings

Numbers are the production reviewer's own. All were confirmed by reading the code.

| # | Finding |
|---|---|
| 7 | Finished-goods value depends on how scrap is reported: normal loss is tested per posting (stage / processing yield) |
| 8 | Overhead added while an order is completing is recorded on the order but never posted (narrow race) |
| 9 | A fully failed single-step batch cannot be recorded through the API (`qtyProduced` must be positive), so the scrap branch is unreachable |
| 11 | Cost reported after a plain order posts is dropped (documented in ADR-S018) |
| 12 | Legacy STANDARD products without an active standard leave the production variance in the stock account |
| 14 | Subcontract and resource labour are credited to the overhead-absorbed pool at an estimate and never matched to the supplier bill |
| 15 | Rate changes ratchet absorbed cost upward (rates have no effective dates) |
| 16 | Account 5970 carries both abnormal process loss and unabsorbed overhead |
| 17 | The variance register double-counts an output shortfall (report only; the GL is right) |

### Known limitations of the fixes

- **Period close (ACR-17):** draft journals dated in the period still do not block the close; they stay advisory.
- **Accrual clearing (ACR-14):** a release made by assigning a lineage exception is not recorded as a release, so a
  later accrual clearing takes that part back from cost of sales.
- **Not re-checked:** goods-receipt reversal and the PPV variance rows.

## Verification

- Targeted `fastTest` runs on a private Postgres; see `docs/PORT_STATUS.md` (REV-2) for the classes.
- Web: `pnpm web:typecheck` clean, eslint with no new errors, i18n check passed.
- The screens were **not** exercised in a browser.
- The full `./gradlew build` was **not** run (CI runs it on push).
