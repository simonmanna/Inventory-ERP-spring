# LP2 — Cashier vision: implementation brief

- Status: **coded on the branch at the owner's request (2026-11-17)**, straight after LP1 and ahead of LP3 and LP4,
  which changes the order in ADR-S077 §9. The owner builds and tests it later. The loss-prevention test classes pass
  on `fastTest`; the full build has not been run.
- **Detection accuracy has not been measured.** The rules are tested on the observations an agent would send, not on
  video. Section 6 lists what a pilot must measure.
- Needs LP1 (agents, cameras, zones, evidence). Contract, rules and parameters:
  [`docs/spec/loss-prevention.md`](../spec/loss-prevention.md), section "Cashier vision (LP2)".
- Branch: `phase/LP0-exception-intelligence`. Extension: no NestJS counterpart.

## 1. Goal

Cases at the till that the POS records alone cannot raise:

| Code | Finding |
|---|---|
| `V-C1` | The cash drawer opened with no sale, refund, cash movement or session change near it |
| `V-C2` | A refund, void or cancellation with no customer at the till |
| `V-C3` | A customer served at the till with no sale recorded |
| `C11` | One cashier's no-sale drawer openings well above the branch (POS data only) |

## 2. Out of scope

- Changing this ERP's POS in this phase. Drawer openings came later, as LP2b (owner decision 2026-11-19): see §8.
- Identifying people on video. A finding's subject is the cashier logged into the cash session open on the till
  (V-C1, V-C3) or the person who refunded or voided (V-C2); without a session it is the till.
- Bar and kitchen vision (LP3, LP4).

## 3. What was built

- Engine `cashier_vision` (`LpCashierVisionEngine`), with its own reconciliation windows (`V573` adds it to the
  `LpWindow` engine check). A window exists only where the branch has a `cash_drawer` or `checkout` zone; it runs
  `visionSettleMinutes` after it became dirty, and again when an agent's watermark moves past its observations.
- Rules `V-C1` to `V-C3` in the rule catalog, enabled, not final-only; `C11` in the cashier engine (version 2),
  final day only.
- Event type `cash_drawer.opened` (`payload.reason`, `payload.tillId`), checked at ingest.
- Projection: `payload.tillId` (the `CashRegister` id) on sales, voids, cancellations, refunds and cash sessions,
  and cash pay-ins, pay-outs, supplier payments and adjustments projected as `cash.movement` (source
  `cash_movements`).
- Coverage: V-C2 skips, and never finds, a refund or void whose window is not fully observed (inside a `vision.gap`
  of the zone, or after the watermark). A run with such a gap is stored with `observationGap`.
- A case from these rules gets its clips linked as it opens (LP1 brief §6).
- Web: the engine is labelled "Cashier, cameras"; runs show the observation gap.

## 4. How each rule decides

- **V-C1.** A `vision.state_changed` of a `cash_drawer` zone to `open`, observed with at least `minConfidence`, is a
  finding unless a `sale.completed`, `refund.issued`, `cash.movement`, `cash_drawer.opened`, or a cash session
  opening or closing at the same till falls within `windowBeforeSeconds` before or `windowAfterSeconds` after it.
- **V-C2.** For each refund, void or cancellation at a till with a `checkout` zone drawn before the window and not
  retired in it: a finding when no person is seen there from `windowBeforeSeconds` before to `windowAfterSeconds`
  after it, that is no `vision.dwell` of a person lasting at least `minPresenceSeconds` and no `vision.occupancy`
  above 0 overlapping the window, each with at least `minConfidence`. A window that was not fully observed is skipped
  and the run marked.
- **V-C3.** A `vision.dwell` of a person of at least `minDwellSeconds` in the `checkout` zone, with no
  `sale.completed` or `refund.issued` at the till from `windowBeforeSeconds` before its start to
  `windowAfterSeconds` after its end. When the till has cash sessions in the ledger, only a dwell while one is open
  counts: someone at a closed till is not a customer being served.
- **C11.** Per business day: each cashier's `cash_drawer.opened` with reason `no_sale` against everyone who worked a
  till that day; a finding when one cashier has at least `minCount` and more than `multiplier` times the branch's
  rate, like C04 for refunds.

The till of a POS event is `payload.tillId`; else the till of its cash session; else `deviceId`.

## 5. Settings and data

No new table. The rules use the LP1 settings (`visionSettleMinutes`) and the ordinary rule versions, so a change of a
threshold is versioned and a replay shows its effect on past days.

## 6. Accuracy, and what the pilot must measure

The rules are only as good as the observations. Before these cases are used for decisions, a pilot must measure,
per till camera:

- drawer openings detected and missed, against a reviewed sample of video (V-C1's false cases come mostly from
  missed or false openings);
- customer presence detected and missed at the till (V-C2 and V-C3);
- the false-case rate per rule per week, which sets `minConfidence` and the windows.

Until then every case states the observation, its confidence and the POS events around it, and the investigator
confirms on the clip.

## 7. Tests

- `LpVisionEngineTest`: V-C1 found, explained, low confidence and other states; V-C2 found, presence, gap, pending
  watermark, no zone, zone drawn later; V-C3 found and closed till; determinism; C11 on the final day.
- `LpVisionIT`: a drawer opening sent by an agent becomes a V-C1 case through a replay, with its clip linked.
- `LpEventValidatorTest`: `cash_drawer.opened` and the vision payload checks.
- Not run: the full build.

## 8. Open items

- Done as LP2b (owner decision 2026-11-19): this ERP's POS records drawer openings (`CashDrawerOpening`, V578;
  `POST /pos/cash-drawer/openings`; sync op `cash_drawer.open`; "No sale" on the web cash register page and the
  Android More screen), and the projection sends each as `cash_drawer.opened` (source `drawer_openings`), so C11 and
  the POS side of V-C1 now run on this ERP's own data. Tests `PosCashDrawerIT`, `LpReconIT` (11 sources).
- The defaults of every threshold are design values, not measured ones.
