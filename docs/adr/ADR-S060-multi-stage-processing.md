# ADR-S060 — Multi-stage processing: sell at any stage

- Status: accepted (owner asked to build all phases, 2026-11-07). Numbered S060 because the unmerged sales branch holds ADR-S059.
- Spec: [`docs/spec/processing.md`](../spec/processing.md). Impact: [`docs/audits/processing-impact-assessment.md`](../audits/processing-impact-assessment.md).
- Builds on: ADR-S011 (multi-stage engine), S016 (crushing), S031 (cost lineage), S041 (cost adjustment), S053 (landed cost), S054 (FX), S058 (variants).
- Extension: no NestJS counterpart. New tables (V420) and routes behind `ENABLE_PROCESSING`, which follows `ENABLE_MANUFACTURING` unless set.

## Context

A client extracts or buys a product, processes it, sells some, processes the rest further, and sells again. This must work for dimension stone, crushed aggregates, bought goods that get modified, and non-stone products. It is a generic manufacturing capability: stone terms belong in templates and demo data, never in code.

The ERP already has the stage engine, joint cost allocation, yield handling, stock valuation, lots, reservations and journals. What it lacks:

1. A general processing job. Only crushing has one.
2. Selling between stages of one order. A completing backflush fails if units were sold (`StageExecutionService:396-408`), and an order cannot stop early.
3. Mixed-unit yield. `StageYieldCalculator` assumes one measure.
4. Reliable genealogy. Stage moves use `production_stage` references, which the trace does not follow, and nothing records input lot to output lot.
5. True margin. `SalesRevenueQuery` uses the product's current `costPrice`.

## Findings that shaped this design

| # | Finding | Where |
|---|---|---|
| F1 | Reservations do not block stock issues; `issue` checks on-hand only. | `StockService:461` |
| F2 | A short named-lot issue passes if other lots cover it, then overflows into a ledger row with no lot, at an averaged cost. | `StockService:461-470`, `:590-600` |
| F3 | Stage reversal checks on-hand by product and location, not by lot. | `StageExecutionService:707-718` |
| F4 | Cost adjustment looks up output layers under `production_order`; stage postings use `production_stage`. An order with no found layer sends the cost to COGS. Confirmed by test. | `CostAdjustmentService:42,120` |
| F5 | Market value uses the current sales price at each output posting. | `StageExecutionService:437,460-463` |
| F6 | One issue writes one `ledgerCode` with one row per lot, so exact lots are recoverable where the code is stored. Fleet and cash sale store it; `InvoicingWorkflowsInitializer:125` discards it. POS not yet checked. | `StockService:482,657` |
| F7 | `InventoryBatch` has no unique rule on lot number, and one number legitimately spans locations after transfers. | V1 baseline |

## Decision

### 1. One job = one step = one production order

A product's journey is a chain of jobs linked through stock and lots, not one long order. Selling, holding or stopping needs no special handling, because a later job takes only what is issued to it. Each job's output cost becomes the next job's input cost through stock, so cost is never double counted.

Rejected: selling intermediates out of one long multi-stage order (gap 2 above), and a separate engine per process type.

### 2. One stock, valuation and posting path

Jobs use the same `StockService` ledger, posting engine and period locks as manufacturing and crushing, following the `CrushingRunService` precedent: a system-managed BOM and routing behind a versioned template. No code updates stock balances directly. Crushing is unchanged.

One `wip` account; per-stage WIP stays in the sub-ledger. Separate "WIP – Stage n" accounts are rejected. Raw, semi-finished and finished stock accounts use existing `InventoryPostingRuleService` rules.

### 3. One source of truth for status

Execution state lives only on `ProductionOrder` and its stage. `ProcessingJob` stores draft and cancel fields, and its status is derived on read. A route holds no state: stopping after any step simply leaves output in stock.

### 4. Immutable history

Template versions follow `CrushingProfileService`: drafts editable, activation builds the system BOM and archives the previous version, active versions never edited. A job pins its version and snapshots yield, rates, factors, allocation method and market values at start.

### 5. No silent write-offs

`Complete` requires every remaining basis quantity to be declared as additional output, recoverable residual, normal loss, or abnormal loss or write-off with a reason and `processing:approve_loss`. The transaction rolls back unless stage WIP ends at exactly zero and the engine's abnormal quantity matches what was declared.

### 6. Lot traceability without new sale tables

Sale line → `ledgerCode` → ledger rows gives exact lot, quantity and actual cost. Paths that drop the code get it added. Lot-tracked job outputs must use `stockPolicy = block`, so a sale cannot create a no-lot row. `LotGenealogyLink` records many-to-many input to output with an explicit allocation basis; proportional allocation is not assumed.

### 7. No universal unit conversion

Yield uses a common basis per template, with measured weight taking precedence over a factor. Mass yield and commercial yield are reported separately. Mixed-unit templates may allocate only by `weight` or `market_value`.

### 8. Additive changes only

Every engine change takes a nullable input, where null means today's behaviour. The one intended behaviour change: a named-lot issue with `requireAvailable` now refuses instead of overflowing (F2).

### 9. Reservation guard scoped to processing

A guard in `StockService.issue` counts only `processing_job_input` reservations, so tenants not using processing see no change. The owning job, POS and reversals are exempt.

## Consequences

- PS-0 must fix existing costing and trace defects before any job can run. These fixes apply to new postings only; past entries are listed read-only for an owner decision.
- A job's cost pool is shared by its inputs. A `manual` genealogy attribution changes the trace, not the valuation. Lots needing separate costs need separate jobs.
- Reservations are per product and location, not per lot. If another issue takes a named lot first, Start refuses with a clear message.
- Bulk stockpile products have job-level trace only.
- Output that has been sold or moved locks that job's reversal until the blocking documents are undone.

## Owner decisions (2026-11-07)

- **POS is out of scope.** The construction ERP does not use it. Issues from POS sources are exempt from the reservation guard, and no POS report was built. This replaces the earlier POS exception reporting.
- **Late cost on sold stock (proposed policy, pending approval):** the share on goods already sold goes to current-period COGS. **The client's accountant has not approved this yet.** Until they do, treat it as a known limitation, not a settled accounting policy. Remaining stock and WIP get their share. Closed-period journals are never rewritten.
- **`phase/SG-1-12-sales-gap`** was inspected, not merged. It merges cleanly with main (checked with `git merge-tree`), but 103 files and its release gate have not been run, so merging it needs its own review. This work uses V420 and ADR-S060 to stay clear of its V401-V409 and ADR-S059.

## As built

- **Late cost path:** a late cost on a lot consumed by a stage order is held in WIP and recorded per consuming ledger row. `CostAdjustmentService` then follows it onto the order's output layers, repeating for further levels (up to 10 per request). Stage postings now resolve to their order in `CostLineageService` and `LineageFollower`.
- **Scope of that fix:** stage orders, which include jobs and crushing. Single-step orders are still inconsistent: a landed cost voucher's first hop reaches WIP, but later hops and other late costs go to COGS. A shared classifier for all engines is tracked in [issue #8](https://github.com/simonmanna/Inventory-ERP-spring/issues/8).
- **On-hand output** receives late cost on the price-difference adjunct (1396) through existing `LineageCapitalisation`, not by raising the lot's unit cost. Where a pool holds several lots, the on-hand and sold split is by pool quantity.
- **Abnormal loss** is computed from expected yield, not declared by the user, so the two cannot disagree.
- **Cost capture:** one source record can be captured once, in full. Partial capture was not built. The rule "one cost route per work centre per job" is not enforced; jobs create no labour work orders of their own to double count.
- **Manual genealogy:** one share per input, applied to all outputs. Not per output.
- **Block means block for lots.** `inventory.allowNegativeStock` defaults to on and used to override the item policy, so a lot-tracked sale could go short with no lot and zero cost. A lot-tracked item set to block now never goes short, whatever that setting says. Other products are unchanged.
- **Invoice location.** An invoice has no location and used to issue from the oldest warehouse. A lot-tracked line now ships from a warehouse that covers it: the default if it can, otherwise the one with the oldest lot. One line takes one warehouse.
- **`canBeSold`** is checked in `SalesPricingEngine`, on invoice create and edit, and at fleet dispatch.
- **Sale line link:** a sale resolves to lots by document and product through ledger rows. Invoice lines are not linked one by one when a document has two lines of one product.
