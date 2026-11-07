# Processing — architecture and migration impact assessment

For [ADR-S060](../adr/ADR-S060-multi-stage-processing.md). Prepared 2026-11-07 from the code at `f6c0da9a`, updated as built.

## Reused without change

| Concern | Service |
|---|---|
| Stock in | `StockService.receive` |
| Journals | `StockPostingService` (`postProductionConsume`, `postProductionOutput`, `postOverheadAbsorption`, `postProductionScrap`), `PostingService` |
| Joint cost | `JointCostAllocator` |
| Account rules | `InventoryPostingRuleService` |
| Reversal | `StockReversalService.reverseLedgerSource` |
| Reservations | `StockReservationService` |
| Rate-based cost | `CostAbsorptionService` |
| Quality | `QualityDecisionService`, stage gate bridge |
| Units | `UomConversionService` |
| Period lock | `FiscalPeriodControl` |
| Audit | `AuditService` |

## Existing code that changes

Each change is additive and must be tested for no change when unused.

| File | Change | Affects | Phase |
|---|---|---|---|
| `inventory/StockService` | Named-lot refusal | Callers using `requireAvailable` with a named lot: silent overflow becomes an error. **Intended fix.** | PS-0 |
| `inventory/StockService` | Reservation guard | All issue paths; inert without job reservations | PS-1 |
| `costing/CostingService` | FIFO roll-up cost | BOM roll-ups with FIFO materials | PS-0 |
| `costing/CostAdjustmentService`, `LineageCapitalisation` | Stage output layers; multi-level late cost | Cost adjustment runs on stage and multi-level orders | PS-0 |
| `quality/QualityTraceabilityService` | Stage references, genealogy | Trace output gains nodes | PS-0, PS-2 |
| `manufacturing/StageExecutionService`, `StageYieldCalculator`, `StageCommands` | Optional basis, frozen market values | Stage orders, crushing | PS-2 |
| `core/Product` | `canBeSold`; remove `@generated` marker | Product JSON gains a field | PS-3 |
| Sales, invoicing, fleet delivery | `canBeSold` check; ledger link where missing | New error only if a product is switched off | PS-3 |
| `packages/shared` | Permissions, regenerated with `tools/contracts2java` | Role setup | PS-1 |
| `web/.../app-shell.tsx`, product form | Nav, toggle | Web | PS-4 |

## Migrations

| Migration content | Phase | Nature |
|---|---|---|
| Template, job, input, output, cost, loss tables with RLS | PS-1 | New tables |
| Cost source partial unique index; output lot unique index | PS-1 | New indexes on new tables |
| Permission grants | PS-1 | Additive rows |
| `LotGenealogyLink` with RLS; measurement columns if not in PS-1 | PS-2 | New |
| `Product.canBeSold` default true; any missing sale-line ledger link column | PS-3 | Additive columns on existing tables |
| Route tables with RLS | PS-5 | New |

No data rewrites and nothing destructive. V1 and V2 untouched. `ddl-auto` stays `validate`. Numbers are chosen at each phase, above V400. Rollback is leaving the flag off; columns and tables can stay.

**PS-0 data impact:** fixes apply to new postings only. Past FIFO roll-ups and past late-cost entries that went to COGS are not rewritten. PS-0 produces a read-only list of affected entries for the owner to decide on.

## Confirmed while building

| Assumption | Result |
|---|---|
| Stage order's late cost goes to COGS via the stage reference | Confirmed; fixed |
| Batch-tracked products keep per-lot cost | Yes |
| Stage issue can name a lot | Yes, with the material line on MANUAL |
| BOM rules allow a residual that is the input product | Yes, tested |
| POS sale-line ledger link | Not needed; POS is out of scope |
| Sales returns restore the original lot | Not tested |
| `/boms` and stage-setup routes refuse changes to a step's recipe | Yes: BOM edit is limited to drafts, stage setup now refuses |

## Existing entries the fixes do not rewrite

Read-only query for late costs on stage orders that went to cost of sales before this fix:

```sql
SELECT a."sourceType", a."sourceId", l.amount, l."createdAt", g."referenceId" AS "stagePosting"
FROM "CostValueAdjustmentLine" l
JOIN "CostValueAdjustment" a ON a.id = l."adjustmentId"
JOIN "InventoryLedger" g ON g.id = l."consumptionLedgerId"
WHERE l."organizationId" = :org AND l.target = 'cogs' AND g."referenceType" = 'production_stage';
```

The lot path did not write these lines before this fix, so also compare landed cost vouchers on lot-tracked goods consumed by stage orders.

## Unresolved risks

| # | Risk | Mitigation or decision needed |
|---|---|---|
| R1 | Single-step orders still send late cost to COGS on some paths, so engines behave differently. | Use jobs for chains until fixed. Shared classifier: [issue #8](https://github.com/simonmanna/Inventory-ERP-spring/issues/8). |
| R2 | Bulk stockpiles physically blend, so a non-lot crushing fraction cannot be traced to one source. | Trace stops at run and job level. Document for the client. |
| R3 | Late cost touches shared costing. | Exact-amount test, `BooksGuard`, full suite. |
| R4 | Late cost on a sold lot goes to current-period COGS. | Proposed policy, **pending explicit approval from the client's accountant**. |
| R5 | One job takes input from one location. | Stated limit; confirm against real shop-floor use. |
| R6 | STANDARD-costed and serial-tracked products are refused on templates. | Revisit if a client needs it. |
| R7 | Late cost on on-hand output sits in adjunct 1396, split by pool quantity where a pool has several lots. | Correct in total; per-lot split is approximate. |
| R8 | Client-specific density and yield factors can make yield reports wrong (not stock). | Marked `estimated`; get real figures before go-live. |
| R9 | `phase/SG-1-12-sales-gap` is unmerged. It merges cleanly today. | **Owner to decide** after its own review and release gate. |
