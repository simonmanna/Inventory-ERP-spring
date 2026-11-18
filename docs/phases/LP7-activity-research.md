# LP7 — Activity recognition research: implementation brief

- Status: **coded on the branch at the owner's request (2026-11-18)**, with LP5 and LP6b. The owner builds and tests
  it later. The loss-prevention test classes (125 tests) pass on `fastTest`; the full build has not been run.
- **Research only, in shadow mode.** No rule reads an activity, so an activity never makes a finding, a case or an
  alert. No face recognition, no pose of a person, no identity: an activity is about what happens to objects in a
  zone.
- Needs LP1 (zones, the edge agent, clips).
- Contract: [`docs/spec/loss-prevention.md`](../spec/loss-prevention.md), section "Activity recognition research
  (LP7)".
- Branch: `phase/LP0-exception-intelligence`. Extension: no NestJS counterpart. Migration `V576` (shared with LP5 and
  LP6b).

## 1. Goal

Find out whether activity recognition can work at a site before any rule depends on it. A recognizer runs beside a
zone's own profile and reports what it thinks happened. People review a sample, and the system computes how often
the recognizer was right, per recognizer and activity.

## 2. Out of scope

- Any rule, case or alert from an activity.
- Pose estimation, faces, gestures, identity.
- Training or shipping a model. The two built-in recognizers are simple rules over object tracks (section 3).

## 3. What changed

- Event type `vision.activity`: `activity` (a lowercase name), `startedAt`, `endedAt`, `recognizer` (`name@version`),
  optional `score` (0 to 1) and `trackIds`.
- Zone parameters, passed to the agent as they are:
  - `activityResearch` (true to run recognizers on the zone);
  - `activityRecognizers` (which ones; all installed by default);
  - `leftBehindSeconds` (60).
- Table `LpActivityReview` (V576), append-only, tenant row-level security. One row per review; the latest review of
  an activity counts.
- Routes under `/loss-prevention/research` (`lp:investigate`, licence `lpVision`):
  - the activity list with filters, including the latest review's verdict;
  - `POST /activities/{eventId}/review`;
  - a summary of precision per recognizer and activity, with what reviewers said the wrong ones really were.
- Edge agent: package `lp_edge/activities`. A zone with `activityResearch` gets a wrapper; the zone's own
  observations are unchanged. Built-in recognizers, over objects that are not people:
  - `object_carried_out`: an object that stood still in the zone for 1 s leaves it;
  - `object_left_behind`: an object stays still for `leftBehindSeconds`.

  Others install through the entry-point group `lp_edge.activity_recognizers`. A failing recognizer is logged and
  does not stop the zone.
- Evaluation harness: on clips whose labels list `activities`, segments are matched per zone and activity at a
  temporal IoU of at least 0.5. Results appear under the candidate's `activityResearch` and never change its overall
  verdict.
- Web: a Research tab (`lp:investigate`, licence with cameras).
  - The list, filtered by date, zone, activity, recognizer and verdict.
  - A review dialog. A user with `lp:evidence` can also request the clip of the activity from the box.
  - The precision table.

## 4. How reviews are counted

- A review is `correct`, `incorrect` or `unsure`. A `label` (what it really was, in the same form as an activity) is
  given only with `incorrect`. A note holds at most 1000 characters.
- Reviews are never changed. A second review is a new row, and the latest one is the activity's verdict.
- Precision = correct ÷ (correct + incorrect), over the activities whose latest review says so; `unsure` counts in
  neither. Recall cannot come from reviews (a missed activity has no row). The harness measures it on labelled
  footage.

## 5. Settings

None.

## 6. What a pilot must measure

- Precision per recognizer and activity over a review sample large enough to mean something (the summary shows the
  count reviewed beside it).
- Recall on labelled footage, with the harness.
- The review time it costs, before anyone proposes a rule from an activity.

## 7. Tests

- `LpEventValidatorTest`: `vision.activity` and its messages.
- `LpRetailResearchIT`:
  - activities listed newest first, each filter, paging;
  - reviews with every message and a 404 for an event that is not an activity;
  - the latest review counting, precision and relabels;
  - reviews append-only, another organization seeing nothing, the permission matrix.
- Edge: `tests/test_activities.py` (wrapper, built-in recognizers, loader, names and scores), and activity scoring
  in `tests/test_evaluation.py`.
- Not run: the full build.

## 8. Open items (owner decision)

1. No new licence key or permission: `lpVision` and `lp:investigate`.
2. Activities are kept in the event ledger like other observations. A retention job for the ledger is still not built
   (ADR-S077).
3. The built-in recognizers and their thresholds are research values, deliberately crude. Their names are not a
   promise of a feature.
