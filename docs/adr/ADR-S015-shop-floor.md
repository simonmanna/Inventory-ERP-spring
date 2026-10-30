# ADR-S015: Shop-floor execution & data capture (Phase 2.7)

- Status: Accepted (autonomous, 2026-10-30) — owner / production manager to review the defaults under "Open"
- Scope: construction-materials brief §2.7 (operator terminal, shift reports with supervisor approval, downtime with a
  reason taxonomy, meter readings, `MachineDataAdapter` SPI with simulator / REST / push / CSV, OEE)
- Builds on: ADR-S006 (work orders, time logs, work-centre calendars), ADR-S011 §1 (stage engine) and §11 (the
  design this ADR finalises), ADR-S013 (quality gates stay where they are)
- Migration: `V982__manufacturing_shop_floor.sql` (placeholder number, renumbered at merge)

## Context

The plant needs tablets at each crusher, screen and block press: operators start and stop jobs, say why a machine
stopped, report good output and scrap, confirm material issues, read meters, and a supervisor signs the shift off.
PLCs, counters and belt scales should feed counts and stops without anybody typing them, and management wants OEE per
line and shift. ADR-S011 §11 sketched it; this ADR records the choices that last.

## Decision

1. **Inside `manufacturing`, under `/manufacturing/shop-floor/...`** (41 routes, `ShopFloorExecutionController`),
   behind the existing `manufacturing` module flag. No new module: every record hangs off work centres, work orders
   and stages of this module.
2. **No duplicated stock logic.** The terminal drives existing engines:
   - start / pause / complete → `WorkOrderService` (run time logs, labour cost at completion, ADR-S006);
   - output on a **multi-stage** order → `StageExecutionService.output` (backflush, WIP, yield, joint cost, GL); the
     production report keeps the stage posting id;
   - output on a **single-leg** order → adds to the work order's good / scrap counts (stock moves at the order's
     complete leg, unchanged);
   - material issue → the existing `POST /production-stages/{id}/issue` route (manual stages);
   - QC → the 2.1 / 2.6 stage gate (the terminal only shows the checkpoint state).
3. **Pause = stop with a reason.** Pausing a running work order needs a downtime reason and opens an operator
   downtime event on the work centre; resuming (start) or completing closes it. Stops can also be logged directly
   (open or with minutes). One open operator stop and one open machine stop per work centre.
4. **Reason taxonomy**: `ShopFloorDowntimeReason` (code immutable, category, `isPlanned`). Planned stops (breaks,
   planned maintenance, cleaning, no orders) are excluded from planned production time; everything else is an
   availability loss. A standard list of 13 reasons is offered.
5. **Production shifts** are a shop-floor table (`ShopFloorShift`, minutes after local midnight in the organization's
   time zone, break minutes; active shifts must not overlap; a standard Day / Evening / Night pattern is offered).
   Not the HR shift model: HR is optional (`ENABLE_HR`) and models people, not plant time — linking crews to HR
   rosters is Phase 4.
6. **Shift report** = one per work centre × shift × date, created on first use. Every record (production report,
   downtime, clock entry, meter reading) belongs to the report of its time. `open → submitted` (no machine drafts may
   be pending) `→ approved` by a holder of `shop_floor:approve` **who is not the submitter** (four eyes), or rejected
   back to open with a reason. Submitted / approved reports refuse new or changed records in their window. An OEE
   snapshot is stored at submission and approval. Direct permission + audit, not the approval-workflow engine
   (no amount threshold to route on).
7. **Operator clock entries** (`ShopFloorClockEntry`) record who was on which work centre / work order; one open entry
   per person (clocking on elsewhere closes the previous one). They are attendance-by-job and not costed: labour cost
   stays the work order's run time × resource rate (ADR-S006) so nothing is costed twice.
8. **Production data capture SPI** `manufacturing.spi.MachineDataAdapter` (`code`, `configured`, `pull`,
   `poll(PollRequest)` → `MachineEvent`s: `count` (belt-scale `weight` is a count), `downtime`, `downtime_start`,
   `downtime_stop`). Beans: `simulator` (deterministic, for demos / tests) and `rest` (HTTP pull from a gateway,
   `MFG_MACHINE_DATA_REST_*`). Push: `POST .../machine-data/push` (gateway / MQTT bridge, adapter `push[:source]`),
   CSV: `POST .../machine-data/csv`. Events are stored once per `(adapter, externalId)` (at-least-once sources are
   safe) and become **drafts** — a count → draft production report for the single running work order of the machine's
   work centre, a stop → draft downtime. **Nothing is posted until an operator confirms** (counts may be corrected);
   confirmation is idempotent (`Idempotency-Key`) and row-locked so two operators cannot post the same draft.
   Machines are matched by the work-centre profile's machine code, else the work-centre code.
9. **Push authentication** is the normal JWT of a device user that holds only `shop_floor:machine_data` (instead of
   ADR-S011's HMAC idea): the gateway is a first-class, revocable, audited user; no shared secret to rotate.
   An MQTT client is not embedded (no new dependency): an MQTT → HTTP bridge (Node-RED, Telegraf, the PLC gateway)
   posts to the push route.
10. **OEE** (`OeeCalculator`, pure): availability = run ÷ planned (planned = shift window − pro-rated break − planned
    stops; run = planned − unplanned stops, overlapping stops counted once, planned wins — `ShiftClock.stopMinutes`);
    performance = (total count ÷ ideal rate per hour) ÷ run time, capped at 100 % and flagged; quality = good ÷ total.
    Ideal rate per work centre on `ShopFloorWorkCenterProfile`. Aggregates (per work centre, period) sum minutes,
    ideal minutes and counts — never average ratios. A running shift is measured on its elapsed part.
11. **Permissions**: reuse `work_order:read` (views), `work_order:manage` (terminal actions, downtime, clock, meters,
    submit), `production_order:complete` (output / scrap and draft confirmation — they post stock),
    `work_center:read|update` (set-up), `production:report` (OEE). New: `shop_floor:approve` (supervisor) and
    `shop_floor:machine_data` (device accounts / data feeds).

## Consequences

- One code path moves stock for staged orders whether the count came from a person or a PLC.
- OEE is only as good as the ideal rates and the reason discipline; performance is n/a without an ideal rate.
- Shift reports exist only where something was recorded; idle shifts without any record are not in the OEE report.
- The 2.8 costing agent can read actual run time, downtime and output per shift from these tables.

## Open (owner / production manager to confirm)

1. Four-eyes approval (submitter ≠ approver) — relax if one supervisor runs a small plant.
2. Shift times in the organization time zone; standard pattern 06–14 / 14–22 / 22–06 with 30 min breaks.
3. Machine counts are attributed to the single running work order of the work centre (none when several run).
4. Clock entries are not costed (labour cost from work-order run time).
