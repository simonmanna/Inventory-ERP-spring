# ADR-S019: Advanced planning & scheduling (manufacturing 2.5)

- Status: Accepted (autonomous, 2026-10-30). Built without compiling or running anything (Phase 4 lane rule).
- Brief: [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](../AUTONOMOUS_BUILD_CONSTRUCTION.md) Phase 2.5; item MFG-1 in
  [`GAP_ANALYSIS.md`](../requirements/GAP_ANALYSIS.md)
- Spec: [`docs/spec/manufacturing.md`](../spec/manufacturing.md) "ADR-S019"
- Migration: `V40__manufacturing_planning.sql`
- Related: ADR-S006 (MRP, forward scheduler), ADR-S011 (stages, order pegs), ADR-S014 (curing), ADR-S015 (shifts,
  work-centre profile), ADR-S016 (crushing units and grading profiles)

## Decision

1. **No change to existing tables.** Due date, priority and the schedule result live in `ProductionOrderPlanning`, not
   on `ProductionOrder`, because `SELECT *` rows are rendered as the entity and a new column would change the JSON of
   existing routes. Routes live under `/manufacturing/planning/**`.
2. **Demand** is collected from the fleet delivery schedule, confirmed sales orders with a customer, approved
   proformas, a sales run-rate forecast and stock policy. A sales order already on the delivery schedule counts once,
   as its delivery order. Other modules' tables are read by SQL, as MRP already does, and never written.
3. **Netting by rank.** Firm demand, then forecast, then stock policy; inside a class priority, then due date. Supply
   (on hand less reservations plus open production and co-products) goes to the top, so the shortfall lands on the
   least important demand. A forecast is consumed by the firm demand of the same product.
4. **Pegging.** `ProductionDemandPeg` ties plan line and order to the customer demand; it complements
   `ProductionOrderPeg` (parent to child order). Confirming a plan copies the earliest due date and highest firm
   priority to the order. `ProductionPlanService.confirm` gained that one call.
5. **Backward scheduling** walks the last operation back from the due date through a pure `PlanningCalendar` with the
   same rules as the forward scheduler (UTC, shifts, closures, parallel slots). If the first operation would start
   before now, the order is placed forward from now and its lateness stored, so the plan on the board is always real.
6. **Curing is a calendar lag, not machine time.** A stage's minimum curing days sit between the end of the previous
   operation and the start of its own, because blocks cure between the press and cubing. On a first operation the lag
   follows it. Weekends count.
7. **Run time from a rate.** With a crushing unit t/h or a shop-floor ideal rate, an operation takes quantity ÷ rate,
   else the routing's fixed minutes; both ÷ the centre's efficiency.
8. **Gantt drag** snaps forward to the first free working minute. Calendar and capacity are hard limits; a clash with
   the neighbouring operation or a curing lag is a warning, since a planner moves a chain one bar at a time.
9. **Capacity** is calendar minutes × parallel slots, or the resources' minutes per day when the centre has no shifts.
   Load counts working minutes of scheduled work orders only; unscheduled work is shown apart. Efficiency is already
   in the operation's run time and is not applied twice.
10. **Crushing circuit bottleneck** is the stage with the lowest t/h, parallel units adding up, capped by the design
    capacity. The aggregate plan sets the feed by the tightest fraction, because a crusher makes every fraction at once.
11. **MRP** now grosses make quantities up for BOM yield, adds curing days to the release offset and counts stocked
    co-products of open and planned orders as receipts. All three are no-ops for a BOM at 100 % yield with no curing
    and no co-products.
12. **Permissions.** No new ones: `production_plan:*`, `work_order:*`, `production:report`.

## Consequences

- Scheduling is per order, not a global optimiser: `schedule-open-orders` gives slots in priority order.
- Moving an order does not cascade to its pegged parent or child orders.
