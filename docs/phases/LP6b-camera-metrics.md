# LP6b — Operations insights from cameras: implementation brief

- Status: **coded on the branch at the owner's request (2026-11-18)**, with LP5 and LP7. The owner builds and tests
  it later. The loss-prevention test classes (125 tests) pass on `fastTest`; the full build has not been run.
- **Camera figures count people and tables; they never identify anyone.** How accurately a camera counts has not been
  measured at any site, and every response says so (`cameraNote`, `coverage.camera.measured` false).
- Needs LP6a (the summaries and their job) and LP1 (zones, the edge agent).
- Contract: [`docs/spec/loss-prevention.md`](../spec/loss-prevention.md), section "Operations insights from cameras
  (LP6b)".
- Branch: `phase/LP0-exception-intelligence`. Extension: no NestJS counterpart. Migration `V576` (shared with LP5 and
  LP7).

## 1. Goal

The operations metrics of ADR-S077 §10 that need a camera, kept like the LP6a ones: summaries computed by the
insights job, read by routes, never a finding or a case.

| Figure | From | Zone type |
|---|---|---|
| Footfall in and out, per hour | People crossing a counting line | `entrance` |
| Conversion | Sales ÷ people in, over the days with footfall | `entrance` |
| Queue wait (average, worst day P90) | One wait per person who stayed `minWaitSeconds` (5) or more | `queue` |
| Queue length | Person-seconds ÷ seconds observed | `queue` |
| Table occupancy | Seconds with a person ÷ seconds observed | `dining` |
| Seatings, wait to the first order | A seating starts where an empty period ended; the wait runs to the table's first kitchen ticket | `dining` linked to a `table` |
| Camera gaps | Seconds without a usable picture | the three above |

## 2. Out of scope

- Identifying anyone, following one person across cameras, age or gender, heat maps of people.
- Live queue alerts: these are daily summaries.
- Counting accuracy. It is measured at a pilot (section 6), not here.

## 3. What changed

- Zone type `queue`: a polygon over the waiting line, optionally linked to a till. Parameters `classes`
  (`["person"]`), `minWaitSeconds` (5), `occupancyEverySeconds` (60). A `dining` zone can link to one POS `table`
  (message `Table not found`).
- The projection writes `payload.tableId` on `kitchen.ticket_created`. Tickets projected before LP6b carry none.
- Insights job: `vision.line_crossed`, `vision.dwell`, `vision.occupancy` and `vision.gap` mark their day dirty.
  An occupancy received in the first hour of a day also marks the day before, because a period belongs to the day it
  started. `V576` marks dirty the days that already hold camera observations.
- Ten metrics (`footfall_in`, `footfall_out`, `queue_wait`, `queue_seconds`, `queue_length`, `table_seconds`,
  `table_occupied`, `table_seatings`, `table_wait_order`, `camera_gap`) per day, hour and zone. The routes
  `GET /insights/footfall`, `/queues` and `/occupancy` serve them. The summary gains `metrics.camera`,
  `coverage.camera` and `cameraNote`.
- **Fix to LP6a** (pending approval, open item 4): every grouping query now writes nothing for an empty day.
  - Before, `GROUPING SETS` returned a total row even with no source rows (count 0, sum NULL). That broke the day's
    computation on the NOT NULL `total`; in the shared test database 11 of 19 summary days held that error.
  - It also wrote zero rows that made "days with data" count days with no pour or no ticket.
- Edge agent: profile `queue` (`lp_edge/profiles/queue.py`).
  - One `vision.dwell` per wait, at its end: `class`, `startedAt`, `endedAt`, `durationSeconds`, `maxCount` 1,
    `method` `track`.
  - `vision.occupancy` every `occupancyEverySeconds`, with `count` (the most people in the zone during the period),
    `class` and `periodSeconds`.
  - Dining zones keep the LP1 counting profile.
- Web: a "Counted by cameras" section in the Insights tab (licence with cameras only). The camera note is shown
  first, then coverage, footfall, queues and tables.

## 4. How each figure is computed

- **Footfall.** Σ `count` of `in` and of `out` crossings with class `person` (or none), per hour of the crossing.
- **Conversion.** Sales ÷ people in, counting sales only on the days the branch counted footfall. A sale on a day
  the door camera was off would otherwise inflate it.
- **Queue wait.** Seconds = `durationSeconds`, else `endedAt − startedAt`. Counted at the hour the wait ended. P50
  and P90 per day; the routes give the worst day's P90.
- **Queue length.** Σ `count` × `periodSeconds` ÷ Σ `periodSeconds`. The count of a period is its peak (open item 3).
- **Occupancy.** A period with at least one person counts as occupied for its whole length.
- **Seatings and wait to the first order.**
  - A seating is a period with people that directly follows a period with none (the two adjoining within 1 s).
    After a gap in the counts, nothing is a seating.
  - The wait runs from the seating to the first `kitchen.ticket_created` with the zone's table, before the table is
    next empty and within 4 hours.
- **Gaps.** Seconds from `from` to `to` of the zone's `vision.gap` events, all on the day the gap is stored.
- **Days and hours.** Periods count on the day and hour they started. A period ending just after midnight is stored
  under the next day, and the job reads it from there.
- **Rounding.** Rates have 4 decimals, seconds 1, lengths 2. A ratio with a zero denominator is `null`.

## 5. Settings

No new setting. The insights job and its delay are those of LP6a.

## 6. What a pilot must measure

- People in and out per hour at each door, against a manual count over several days. Conversion rests on it.
- Waits against a stopwatch sample, and the 10 s rule that ends a wait when someone is lost.
- Occupancy against observation, at the 5-minute period of `dining` zones.

## 7. Tests

- `LpInsightsKeysTest`: camera types mark their day; an occupancy in the first hour also marks the day before.
- `LpRetailResearchIT`: a day of door, queue and table counts (and a gap), computed, then checked figure by figure
  against values worked out by hand in footfall, queues, occupancy and the summary.
- `LpInsightsIT`: the insights routes and their permissions, 10 routes now.
- Edge: `tests/test_scan_queue.py`.
- Not run: the full build.

## 8. Open items (owner decision)

1. No new licence key. The routes are `lpInsights`, the zones `lpVision`, so camera figures need both.
2. "First order" is the first kitchen ticket of the table. The POS records no separate order time, and a table
   whose first order is drinks only (no ticket) has no wait.
3. A period's count is its peak. Queue length reads high when a line changes within the minute, and occupancy when a
   table is used for part of a period.
4. The LP6a fix in section 3 changes LP6a output: days without a source no longer appear in "days with data".
