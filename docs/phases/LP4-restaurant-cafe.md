# LP4 — Restaurant and cafe: implementation brief

- Status: **coded on the branch at the owner's request (2026-11-18)**, with LP3. The owner builds and tests it later.
  The affected test classes pass on `fastTest`; the full build has not been run.
- **Nothing here has been measured at a real kitchen pass or counter.** Counts from a camera are trusted as sent;
  every threshold is a design value. Section 6 lists what a pilot must measure.
- Needs LP0. The camera rules need LP1 (`kitchen_pass` and `cafe_counter` zones, the counting profile).
- Contract: [`docs/spec/loss-prevention.md`](../spec/loss-prevention.md), section "Restaurant and cafe (LP4)".
- Branch: `phase/LP0-exception-intelligence`. Extension: no NestJS counterpart. Migration `V575` (shared with LP3).

## 1. Goal

The staged flow of ADR-S077 §3 — ticket, preparation, dispatch, sale — compared stage against stage, and against
what a camera counts leaving the kitchen or crossing the counter.

| Code | Engine | Finding | Needs |
|---|---|---|---|
| `K01` | `kitchen_flow` | Food served from the kitchen with no sale recorded for its order | Kitchen tickets |
| `K02` | `kitchen_flow` | Remakes at one station above the threshold | Kitchen tickets |
| `V-K1` | `service_vision` | More plates left the kitchen pass than the tickets account for | A `kitchen_pass` zone |
| `V-F1` | `service_vision` | More items handed over at the cafe counter than were sold | A `cafe_counter` zone |

## 2. Out of scope

- Per-item kitchen times: the kitchen display records stages per ticket (ADR-S077), so stage evidence stays
  ticket-level and `inferred`.
- `delivered` (to the guest): this ERP records none. A sender that does is read by K01 like `dispatched`.
- Identifying staff on video. Subjects are the ticket, the station, the till or the zone.
- New edge profiles: the LP1 counting profile already counts `plate` over a `kitchen_pass` line and `cup` over a
  `cafe_counter` line. LP4 is the backend that reads those counts.
- Changing POS or KDS code.

## 3. What changed outside the engines

- Projection: `kitchen.ticket_created` and every `kitchen.stage` carry `itemCount` (the quantities on the ticket not
  cancelled, when read) and `orderType`. Tickets projected before LP4 have none.
- `LpEngine.lookaheadDays()`: an engine may read some types from the days after its window (K01 reads sales of the
  next business day: a tab paid after midnight). Such an event marks the earlier reconciled windows dirty again.

## 4. How each rule decides

- **K01** (`kitchen_flow`, business day, final day only). For each ticket with a `dispatched` or `delivered` stage
  that day and no `cancelled` stage: a finding when no `sale.completed` that day or the next has the ticket's
  `orderId` or `transactionId`, and the order was not cancelled. A ticket with neither id is skipped. Severity
  warning. If the sale arrives later, the window runs again and the finding is withdrawn.
- **K02** (`kitchen_flow`, final day only). Per station: tickets with a `remade` stage ÷ tickets created; a finding
  when at least `minCount` (3) and above `maxRatePercent` (10). Info.
- **V-K1** (`service_vision`, calendar day, settles with `visionSettleMinutes`). Per `kitchen_pass` zone and period of
  `bucketMinutes` (60): plates = Σ `count` of `vision.line_crossed` with direction `out` (draw the line with
  `inSide` on the kitchen side) of any class but `person`, observed with `minConfidence` (0.5). Which classes the
  agent counts is the zone's own `classes` parameter (`["plate"]`); expected = Σ `itemCount` of
  the tickets of the zone's station (all stations when the zone has no link) whose first `prepared` or `dispatched`
  falls in the period widened by `slackMinutes` (10) each side. A finding when plates − expected ≥ `minExcess` (3)
  and plates > expected × (1 + `tolerancePercent` (25)/100). A period is skipped, never found, when a ticket in it
  has no `itemCount`, when the zone has a `vision.gap` in it, or when it is after the camera's watermark.
- **V-F1** (`service_vision`). Per `cafe_counter` zone and period, as V-K1, with items = `out` crossings (the zone's `classes`,
  default `["cup"]`; `inSide` on the staff side) and sold = Σ line quantities of the sales at the zone's till (a zone linked to a `counter`, or not
  linked: every sale of the branch), `slackMinutes` 5, `tolerancePercent` 20. Exposure: the excess × the average
  price of the items sold in the period.

## 5. Settings

No new setting. `kitchen_flow` settles with `cashierSettleMinutes`; `service_vision` with `visionSettleMinutes`.

## 6. What a pilot must measure

- Plates and cups counted against a reviewed sample of video, per camera (V-K1 and V-F1 rest on that count).
- How many plates a ticket item really is (sides, shared plates): it sets `tolerancePercent`.
- K01 on real tabs: orders settled on a later day, staff meals, and tickets without an order.

## 7. Tests

- `LpRestaurantEnginesTest`: K01 found, paid that day, paid the next day, cancelled, no order; K02 per station; V-K1
  found, within tolerance, gap, watermark, ticket without `itemCount`; V-F1 found at its till only, with the
  estimated amount; determinism.
- `LpBarRestaurantIT`: a dispatched ticket with no sale becomes a K01 case through a replay.
- Not run: the full build.
