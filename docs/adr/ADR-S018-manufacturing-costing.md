# ADR-S018: Manufacturing costing & analytics (manufacturing 2.8)

- Status: Accepted (autonomous, 2026-10-30). Built without compiling or running anything (Phase 4 lane rule).
- Brief: [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](../AUTONOMOUS_BUILD_CONSTRUCTION.md) Phase 2.8; item MFG-2 in
  [`GAP_ANALYSIS.md`](../requirements/GAP_ANALYSIS.md)
- Spec: [`docs/spec/manufacturing.md`](../spec/manufacturing.md) "ADR-S018"
- Migration: `V41__manufacturing_costing.sql`
- Related: ADR-S008 (standard costing, untouched), ADR-S011 (stage conversion), ADR-S015 (clock, meters), ADR-S016

## Decision

1. **Rates per work centre** (`WorkCenterCostRate`): labour, machine and overhead per hour, energy per meter unit,
   overhead basis (machine or labour hours), optional credit account.
2. **Actual basis.** Labour hours are clocked crew minutes of the work order, else its run time. Machine hours are the
   run time. Energy is the meter delta at the centre while the work order ran.
3. **Equipment from fleet.** A rate may name a fleet vehicle; its fleet cost per hour over the last `fleetCostDays`
   replaces the manual machine rate. `manufacturing` calls `FleetCostingService.report` through an `ObjectProvider`.
   Nothing depends on `manufacturing`, so this adds no module cycle.
4. **Through the posting engine.** On a multi-stage order an amount is an ordinary stage conversion (ADR-S011): Dr WIP,
   Cr the rate's account or overhead absorbed, on the operation's stage or the next open one. On a plain order it joins
   the order's overhead and component list, and the complete leg moves it to WIP and finished goods.
5. **No double costing.** `ProductionCostAbsorption` records what was posted per work order and kind; an absorption posts
   only the difference. Reversing the stage posting frees the amount. A work order whose labour or machine time was
   already costed through its resource skips that kind, and subcontracted work orders are skipped.
6. **Variances** reconcile: actual cost − standard cost of the good output = price + usage + labour efficiency +
   overhead + yield. Usage is measured against the quantity planned for the run, so an output shortfall appears once,
   as yield. Standard prices come from the BOM's current roll-up, else the product cost price, else the actual cost.
   `ProductionVariance` and `/reports/variances` are untouched; these are computed on read.
7. **Margin** values sold units at the period's production cost per unit, else average stock cost, else cost price, and
   each product row states its basis. It is an analytic estimate, not the booked COGS.

## Consequences

- Absorption after the last stage closes, or after a plain order posts, is reported as skipped and stays out of cost.
- Payroll actuals are not linked: the labour rate is configured. Linking needs HR-5.
