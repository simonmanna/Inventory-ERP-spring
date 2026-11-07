# Processing — sell at any stage

Manufacturing capability, package `com.inventoryerp.manufacturing`, flag `ENABLE_PROCESSING` (follows `ENABLE_MANUFACTURING` unless set). Design: [ADR-S060](../adr/ADR-S060-multi-stage-processing.md). Migration: V420. User guide: [`docs/guides/processing.md`](../guides/processing.md).

## Purpose

Record any processing step as a job: issue input, capture cost, record outputs and loss. Every output is ordinary stock that can be sold or used as the input of another job.

```
Purchase (GRN) or Extraction or Crushing run
              |  lot GR-001
              v
   stock ---> sell, or Job (issue, costs, outputs, residual, loss)
                          |  lot GR-001-P1
                          v
              stock ---> sell, or next Job ...
```

## Data model

All tables carry `organizationId` and the tenant row-level security policy.

| Table | Key fields |
|---|---|
| `ProcessingTemplate` | code, version, status (`draft`, `active`, `archived`), system BOM, one-operation routing, work centre, cost rates, allocation method, QC plan, yield basis unit, expected yield |
| `ProcessingTemplateInput` | product, role (`traceable`, `consumable`), genealogy basis, basis factor |
| `ProcessingTemplateOutput` | product, role (`main`, `co_product`, `by_product`, `residual`, `waste`), expected %, valuation, basis factor, required measures |
| `ProcessingJob` | code `PJ-000001`, template version, `snapshot` (JSON), order link, reprocess-of job, draft and cancel fields. No execution status |
| `ProcessingJobInput` | product, lot, location, unit, planned and issued quantity, cost, basis quantity, basis source |
| `ProcessingJobOutput` | product, lot, quantity, unit, value, unit cost, grade, basis quantity, basis source, optional weight, length, width, thickness, area, volume |
| `ProcessingJobCost` | category, description, amount, currency, FX rate, base amount, cost date, source type and id, allocation method, posted flag, posting id, credit account, audit fields |
| `ProcessingJobLoss` | kind (`normal`, `abnormal`, `write_off`), basis quantity, value, reason, approver, posting link |
| `LotGenealogyLink` | job, output posting, source product and lot, source quantity and unit, output product and lot, attributed quantity and unit, allocation method, share %, attributed cost, reversed flag |
| `ProcessingRoute`, `ProcessingRouteStep` | optional ordered chain of templates |

`Product` gains `canBeSold`, default true. `Product.manufacturingRole` supplies the stage label; no stage field is added.

## Job lifecycle

Status is derived from the linked order on read:

| Status | Rule |
|---|---|
| `draft` | No order. Input reserved. |
| `cancelled` | No order. Reservation released. |
| `in_progress` | Order open, stage output zero. |
| `partially_output` | Order open, stage output above zero, remaining WIP kept at its cost. |
| `qc_hold` | Order status. |
| `completed` | Order status. |
| `reversed` | Order cancelled with `reversedAt`. |

| Action | Effect |
|---|---|
| Start | Creates and stages the order, then issues named lots |
| Add cost | Adds cost to WIP |
| Record output | Partial postings allowed; each output gets a lot |
| Complete | Requires full reconciliation (below) |
| Post in one step | All of the above in one transaction, for jobs entered after the fact |
| Reverse | Last-in-first-out, after the lot pre-check |

## Rules

### Stock
- A job cannot issue more than the available unreserved quantity. Issues serialise on the `StockItem` lock.
- A named lot short of quantity is refused, never overflowed.
- Each transaction is all-or-nothing, with `Idempotency-Key` on every stock route.
- A job takes input from one location; other locations need a transfer first.
- STANDARD-costed and serial-tracked products are refused on templates.
- Lot-tracked outputs must use `stockPolicy = block`, checked at template activation.

### Lot numbers
Default `<source lot>-P<n>`, editable. Unique per organization and product, and refused if already on `InventoryBatch`.

### Reconciliation at Complete
Every remaining basis quantity must be declared as additional output, recoverable residual (a stocked output with its own lot, which can be the input product), normal loss up to expected yield, or abnormal loss or write-off with a reason and `processing:approve_loss`. Tolerance is 0.001 of the basis unit. After posting, stage WIP must be exactly zero and the engine's abnormal quantity must match what was declared, otherwise the transaction rolls back.

### Units and yield
- Output quantity stays in the product's own unit.
- Basis precedence: measured actual weight, then dimensions with a density the job states, then the template factor (flagged estimated).
- No global tonnes-to-m² or tonnes-to-m³ conversion.
- Output basis above input basis is refused unless a reason is given.
- Quantities at scale 6, money at 2, `HALF_UP`.
- Example: 10 t in, 8 t main (85 m²), 1 t residual, 1 t loss gives 90% mass yield and 8.5 m²/t commercial yield.

### Cost
- **Rate-based:** Dr WIP / Cr overhead absorbed, at `CostAbsorptionService` rates; existing settlement handles actual vs absorbed.
- **Sourced from a record** (expense, payroll, fuel, subcontract): Dr WIP / Cr the account originally debited. The same record cannot be captured twice.
- A work centre uses one route per job. Foreign currency follows ADR-S054.
- Allocation: market values frozen at job start. Mixed-unit templates use only `weight` or `market_value`.

### Genealogy
Many-to-many, written per output posting. Each template input sets its basis: `mass` (default for traceable inputs), `value`, `fixed_pct`, or `manual`. Consumables carry cost but no lot link unless the template says otherwise. Attributions sum exactly, using the largest-remainder method. Reversal flags rows; it does not delete them.

### Reversal
The output lot's full quantity must still be on hand where it was received. Otherwise the call is refused, listing the blocking documents in the order to undo them.

### Selling
Any product with `canBeSold` true can be sold, with no stage restriction. Enforced on quotation, order, invoice and delivery. Delivery keeps the existing automatic FIFO or FEFO pick; the sale line resolves to exact lot and cost through `ledgerCode`. POS is out of scope for this capability.

### Reprocessing
A rejected or downgraded lot can be a job input, linked by `reprocessOfJobId`. Quality's existing downgrade is reused.

## Postings

| Event | Entry |
|---|---|
| Issue input | Dr WIP / Cr stock |
| Cost, rate-based | Dr WIP / Cr overhead absorbed |
| Cost, sourced | Dr WIP / Cr original account |
| Output, residual | Dr stock / Cr WIP |
| Normal loss | No entry; absorbed by good output |
| Abnormal loss, write-off | Dr stock adjustment expense / Cr WIP |
| Sale | Dr COGS / Cr stock at the lot's actual cost |
| Late cost | Follows lineage: stock adjunct 1396, WIP, COGS or scrap |

## Routes

Base `/api/v1/manufacturing/processing`. Routes marked key require `Idempotency-Key`.

| Verb and path | Permission | Key |
|---|---|---|
| GET `/templates`, `/templates/:id` | `processing:read` | |
| POST `/templates`, PUT `/templates/:id` | `processing:create` | |
| POST `/templates/:id/new-version`, `/activate`, `/archive` | `processing:create` | |
| GET `/jobs`, `/jobs/:id` | `processing:read` | |
| POST `/jobs` (draft and reserve), `/jobs/post` (all in one) | `processing:post` | key |
| POST `/jobs/:id/cancel` | `processing:post` | |
| POST `/jobs/:id/start`, `/costs`, `/output` | `processing:post` | key |
| POST `/jobs/:id/reverse` | `processing:reverse` | key |
| GET `/routes`; POST `/routes`, PUT and DELETE `/routes/:id` | read; `processing:create` | |
| GET `/lots`, `/wip`, `/genealogy`, `/stage-margin`, `/dashboard` | `processing:read` | |

A loss above expected yield also needs `processing:approve_loss`, checked in the service.

## Reports

- **WIP now:** per job, tying to the GL `wip` balance.
- **Lot trace:** source lot to customer, and back.
- **Stage margin and dashboard:** production stage, product category and sales stage kept as separate dimensions. Profit from actual posted COGS, never `costPrice` or standard cost. Shows revenue, COGS, gross profit, stock value, WIP, mass and commercial yield, with `measured` vs `estimated` marked.

## Screens

Under Manufacturing → Processing: templates, job list, job entry (inputs, costs, outputs, measures, reconciliation), WIP now, lot trace, route setup, dashboard. Output lots have a **Process Further** action that opens a job with the suggested template, lot and available quantity prefilled.

## Phases

| Release | Phase | Scope |
|---|---|---|
| 1A | PS-0 | Prerequisite costing, lot and trace fixes |
| 1A | PS-1 | Templates and jobs |
| 1A | PS-2 | Mixed units, genealogy, late cost |
| 1B | PS-3 | Sell at any stage |
| 1B | PS-4 | Web screens |
| 1B | PS-5 | Process Further, simple routes |
| 1C | PS-6 | Stage margin and dashboard |
| 1C | PS-7 | Demo and hand-over |

Each phase has its own doc, branch, tests and report, then stops for the owner.

## Acceptance examples

**Chain, same units:**

| Step | Expected |
|---|---|
| 100 t raw at $20 | Stock $2,000 |
| Job 1: 60 t → 48 t, 12 t waste at zero value, $500 cost, expected yield 80% | $35.416667/t; raw 40 t left |
| Sell 20 t at $55 | COGS $708.33, profit $391.67 |
| Job 2: 20 t → 18 t, $400 cost, expected yield 90% | About $61.57/t |
| Job 3: 10 t → 9 t, $450 cost, expected yield 90% | $118.42/t; 8 t polished left |

**Late cost:** buy 100 t for $10,000, process 60 t into 48 t, sell 20 t, then a $2,000 invoice arrives ($20/t):

| Target | Amount |
|---|---|
| Raw on hand (40 t) | $800 |
| Blocks on hand (28 of 48 t) | $700 |
| Blocks sold (20 of 48 t) | $500 to COGS |

The sold share going to current-period COGS is a proposed policy, pending approval from the client's accountant. Single-step orders behave differently: [issue #8](https://github.com/simonmanna/Inventory-ERP-spring/issues/8).

## Deferred

Lot reservation on sales orders; lot picking on non-fleet deliveries; per-slab records; capacity planning and machine utilisation for jobs; alternative route comparison; auto-feed from extraction to crushing.

## Deviations

See "As built" in the ADR.
