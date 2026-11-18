# LP5 — Retail checkout and store room: implementation brief

- Status: **coded on the branch at the owner's request (2026-11-18)**, with LP6b and LP7. The owner builds and tests
  it later. The loss-prevention test classes (125 tests) pass on `fastTest`; the full build has not been run.
- **Nothing here has been measured at a real checkout or store room.** Counts from a camera are trusted as sent;
  every threshold is a design value. Section 6 lists what a pilot must measure.
- Needs LP0 and LP1 (cameras, zones, the edge agent). The store-room rule reads the stock ledger, read-only.
- Contract: [`docs/spec/loss-prevention.md`](../spec/loss-prevention.md), section "Retail checkout and store room
  (LP5)", the event type `stock.movement` and the projection source `stock_movements`.
- Branch: `phase/LP0-exception-intelligence`. Extension: no NestJS counterpart. Migration `V576` (shared with LP6b and
  LP7).

## 1. Goal

The phase table's "item count against invoice quantity": the items a camera sees pass over a checkout's scanner,
against what the sale records; and goods carried out of a store room, against the stock ledger.

| Code | Engine | Finding | Needs | Default |
|---|---|---|---|---|
| `V-R1` | `retail_vision` | More items passed the scanner than the sale records | A `scan_area` zone linked to a till | **off** |
| `V-R2` | `retail_vision` | Items carried past the scanner that the sale does not explain | A `scan_area` zone linked to a till | **off** |
| `V-S1` | `retail_vision` | Goods carried out of a store room with no stock movement recorded | A `storage` zone linked to a stock location | on |

## 2. Out of scope

- Knowing *which* product an item is (product recognition). The camera counts items; the money at stake is an
  estimate at the sale's average item price.
- Weighing. A weighed line (a fractional quantity) counts as one item.
- Identifying customers or staff on video. Subjects are the cashier recorded on the sale, the till or the stock
  location.
- Changing POS or inventory code. The stock ledger is read like the other projection sources.

## 3. What changed outside the engine

- Zone type `scan_area`: a polygon over the scanner, linked to a till (required). Parameters `classes` (`["item"]`),
  `flowDirection` (`left_to_right`, `right_to_left`, `top_to_bottom`, `bottom_to_top`), `laneMargin` (0.15),
  `preRollSeconds` and `postRollSeconds` (10). `vision.handling` gains the action `bypassed`.
- Event type `stock.movement` (`item`, source `stock`): a stock ledger line at one location, direction `in` or
  `out`, a quantity above 0 (a number, or a decimal string as the projection writes it).
- Projection source `stock_movements`: reads `InventoryLedger` only at the locations linked to a live `storage` zone,
  skipping cost adjustments and zero changes. When nothing is found, its cursor moves to the present, so the large
  ledger is not read again from the start.
- Edge agent: profile `scan_area` (`lp_edge/profiles/scan.py`). Along the flow direction the picture has an input
  side, the scanner's band and an output side; across it, the lane is the scanner's extent widened by `laneMargin`.
  An item seen on the input side, then on the output side:
  - having been inside the polygon on the way: `vision.line_crossed` `out`, count 1;
  - never inside the polygon: `vision.handling` `bypassed`, with a clip of the passage.

  An item taken back over the scanner gives direction `in`. Taken back around the scanner, it is not reported.
- Evaluation harness: labels can name transaction `batches` of a `scan_area` zone (items, bypassed). The retail
  verdict scores the count error per batch. Without labelled batches it stays "not measured" and blocks eligibility.
- Web: the zone editor offers the scanner zone and its flow direction. The rules list names the `retail_vision`
  area, and V-R1 and V-R2 carry "Ships off until a pilot measures it".

## 4. How each rule decides

- **Batches** (V-R1, V-R2; calendar day, settles with `visionSettleMinutes`).
  - Per `scan_area` zone, each `sale.completed` or `order.cancelled` at the zone's till closes a batch: the zone's
    observations after the till's previous closing event, and at most `maxBatchMinutes` (20; 1 to 30) before this
    one.
  - Only batches closed by a sale of the day are judged. A batch is skipped, never found, when:
    - nothing was seen;
    - the zone was not live for the whole batch;
    - the batch is after the camera's watermark (the run is then pending);
    - a `vision.gap` overlaps it;
    - a refund at the till falls in it;
    - the sale has no lines.
  - Expected items = items on the sale (a whole quantity is that many items; a fraction is one) + items voided from
    its order (each line once, at its largest voided quantity). Voided items were passed over the scanner too.
- **V-R1.** Passed = `out` crossings − `in` crossings of anything but people, at `minConfidence` (0.5), never below 0.
  A finding when passed − expected ≥ `minExcess` (2) and passed > expected × (1 + `tolerancePercent` (10)/100).
- **V-R2.** Bypassed = `bypassed` handlings of anything but people, one item each. Explained = max(0, expected −
  passed): items on the sale the camera did not see pass, such as a heavy item keyed in by hand. A finding when
  bypassed − explained ≥ `minCount` (1).
- Subject of V-R1 and V-R2: the sale's cashier (`actorId`), else the till. Exposure, an estimate: the excess × the
  sale's total ÷ its items.
- **V-S1** (calendar day). Per `storage` zone linked to a location, per period of `bucketMinutes` (60; 5 to 240):
  - carried = `out` crossings of anything but people. Draw the line with `inSide` on the store-room side.
  - A finding when carried ≥ `minCount` (2) and no `stock.movement` out of that location falls in the period widened
    by `slackMinutes` (30; at most 30) each side.
  - Skipped on a gap or after the watermark. Subject: the location. No exposure: what was carried is not known.
- The engine reads events from 30 minutes either side of the day, the longest batch and the widest slack.

## 5. Settings

No new setting. `retail_vision` settles with `visionSettleMinutes`. The rule parameters are in the spec's table.

## 6. What a pilot must measure

- The item count error per transaction batch, against reviewed video. The harness scores it from labelled `batches`;
  the design's target is at most 5 %. V-R1 and V-R2 stay off until that target is met at the site.
- How often items legitimately go around the scanner (keyed in, too heavy, already bagged): it sets V-R2's
  `minCount`.
- In the store room, how late legitimate removals reach the stock ledger (it sets `slackMinutes`), and which object
  classes stand for goods.

## 7. Tests

- `LpRetailVisionEngineTest`:
  - V-R1 found, within tolerance, voids and weighed items, what counts and what does not, the subject.
  - Batches skipped: cancellation, refund, gap, no lines, watermark.
  - V-R2 explained and unexplained. V-S1 found, explained by a movement within the slack, skipped on a gap, the
    watermark or an unlinked zone. V-R1 and V-R2 ship off.
- `LpEventValidatorTest`: `stock.movement` and its messages.
- `LpRetailResearchIT`:
  - the zone checks (`scan_area` link, polygon, flow direction, lane margin) and the agent configuration;
  - a V-R1 case (switched on for the test) and a V-S1 case through a replay;
  - a ledger line at a watched store room projected as `stock.movement`.
- Edge: `tests/test_scan_queue.py`, and retail batch scoring in `tests/test_evaluation.py`.
- Not run: the full build.

## 8. Open items (owner decision)

1. V-R1 and V-R2 ship off and V-S1 ships on. A wrong count at a checkout points at a cashier; a store-room finding
   points at a location.
2. No new licence key: the zones need `lpVision`, as for LP2.
3. The store-room rule reads only the ledgers of locations linked to a store-room zone.
