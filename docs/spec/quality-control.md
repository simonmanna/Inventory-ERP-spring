# Quality control (module `qualitycontrol`, ADR-S087)

Odoo-style operational quality control: control points create quality checks on receipts, manufacturing orders, work
orders and deliveries; operators execute them (instructions, pass / fail, measure, picture, worksheet); checks can
block the operation; failed checks raise alerts handled by teams; measure checks feed SPC charts.

There is no NestJS counterpart: this is a post-parity owner request (2026-11-24), and every route is new. It is a
separate module beside `quality` (ADR-S013), which keeps specifications, inspection lots, NCR, CAPA and certificates
of analysis. The full design, including lifecycle, blocking and quarantine rules, is ADR-S087.

Status: implemented (QC1–QC4): foundation, triggers / blocking / quarantine, alerts, statistics and SPC.

## Permissions

| Permission | Allows |
|---|---|
| `quality_control:read` | View points, checks, teams, templates, failure classes, attachments, statistics |
| `quality_control:configure` | Teams, failure classes, worksheet templates, control points; control-point documents |
| `quality_control:check` | Create manual checks; evaluate and execute pending checks; attach check photos |
| `quality_control:alert` | Alerts (QC3); alert evidence |
| `quality_control:manage` | Waive, reset, open a correction check, cancel a manual check; quarantine retry (QC2) |

Administrator gets all five (V660). The Manager role defaults to `read`, `check` and `alert`.

## Routes (`/api/v1`, QC1)

| Verb | Path | Permission | Notes |
|---|---|---|---|
| GET | `/quality-control/overview` | read | counts by status, blocking, failed (30 days), active points, quarantine exceptions, recent failures, checks to do |
| GET | `/quality-control/teams` | read | plain array, each with `members` |
| GET | `/quality-control/teams/{id}` | read | |
| POST | `/quality-control/teams` | configure | 201; `{name, description?, memberUserIds?}`; 409 `Quality team X already exists.` |
| PATCH | `/quality-control/teams/{id}` | configure | `memberUserIds` replaces the members |
| GET | `/quality-control/failure-classes?active=` | read | plain array; the seven defaults are created for an org that has none |
| POST | `/quality-control/failure-classes` | configure | 201; code is upper-cased; 409 on a duplicate code |
| PATCH | `/quality-control/failure-classes/{id}` | configure | name, sortOrder, isActive |
| GET | `/quality-control/worksheet-templates?active=` | read | latest version of each template, with `checkCount`, `versionCount` |
| GET | `/quality-control/worksheet-templates/{id}` | read | one version, with `versions` |
| POST | `/quality-control/worksheet-templates` | configure | 201; the definition is validated |
| PATCH | `/quality-control/worksheet-templates/{id}` | configure | latest version only; if checks use it, a new version is written and returned |
| GET | `/quality-control/control-points` | read | list envelope; `search`, `operation`, `checkType`, `active`, `page`, `pageSize` |
| GET | `/quality-control/control-points/{id}` | read | with `versions` (newest first) and, when inactive, `note` |
| POST | `/quality-control/control-points` | configure | 201; reference `QCP-00001`; version 1 |
| PUT | `/quality-control/control-points/{id}` | configure | full body; writes version n+1; optional `reason` |
| POST | `/quality-control/control-points/{id}/activate` | configure | optional `{reason}`; writes a version |
| POST | `/quality-control/control-points/{id}/deactivate` | configure | optional `{reason}`; writes a version |
| GET | `/quality-control/checks` | read | list envelope; `search`, `status`, `origin`, `checkType`, `sourceType`, `sourceId`, `controlPointId`, `productId`, `teamId`, `quarantineStatus` (QC2) |
| GET | `/quality-control/checks/{id}` | read | with `executions` (newest first, each with `samples`, `superseded`), `events`, `corrections`, `alerts` (QC3) |
| POST | `/quality-control/checks` | check | 201; a manual check, number `QCC-000001` |
| POST | `/quality-control/checks/{id}/evaluate` | check | dry run: `{result, evaluation, failureMessage}`, nothing saved |
| POST | `/quality-control/checks/{id}/execute` | check | writes the next execution revision |
| POST | `/quality-control/checks/{id}/waive` | manage | `{reason, disposition, excludedQty?}` |
| POST | `/quality-control/checks/{id}/reset` | manage | `{reason}`; opens revision n+1 |
| POST | `/quality-control/checks/{id}/corrections` | manage | 201; `{reason, notes?}`; a new check with `origin = correction` |
| POST | `/quality-control/checks/{id}/cancel` | manage | `{reason}`; manual or correction checks only |
| POST | `/quality-control/checks/{id}/quarantine/retry` | manage | QC2; retries a failed quarantine move; returns the check |
| GET | `/quality-control/alerts` | read | QC3; list envelope; `search`, `stageId`, `teamId`, `responsibleUserId`, `priority`, `productId`, `checkId`, `ncrId`, `tagId`, `open`, `origin` |
| GET | `/quality-control/alerts/kanban` | read | `{columns: [{stage, count, alerts}]}`, active stages in order, highest priority first |
| GET | `/quality-control/alerts/{id}` | read | with `tags`, `stageHistory`, `assignmentHistory` |
| POST | `/quality-control/alerts` | alert | 201; number `QCA-00001`; from a check (`checkId` copies product, batch, source, team), a document (`sourceType` + `sourceId`) or free |
| PATCH | `/quality-control/alerts/{id}` | alert | fields, `tagIds` (replaces), `correctionCheckId`; a team / responsible change writes assignment history |
| POST | `/quality-control/alerts/{id}/move` | alert | `{stageId, comment?}`; writes stage history |
| POST | `/quality-control/alerts/{id}/escalate` | alert | `{severity?, ownerUserId?}`; `{created, ncrId, ncrNumber?, alert}`; idempotent |
| GET | `/quality-control/alert-stages` | read | `active`; with `alertCount` |
| POST | `/quality-control/alert-stages` | configure | 201 |
| PATCH | `/quality-control/alert-stages/{id}` | configure | |
| GET | `/quality-control/tags` | read | |
| POST | `/quality-control/tags` | configure | 201 |
| PATCH | `/quality-control/tags/{id}` | configure | |
| GET | `/quality-control/root-causes` | read | `active` |
| POST | `/quality-control/root-causes` | configure | 201 |
| PATCH | `/quality-control/root-causes/{id}` | configure | |

| GET | `/quality-control/statistics/summary` | read | QC4; `from`, `to` (ISO dates, default last 90 days), `controlPointId`, `productId`, `workCenterId`, `teamId`, `interval` (day / week / month); totals, trend, top-10 failures by point / product / work centre / team / failure class |
| GET | `/quality-control/statistics/pareto` | read | same filters + `dimension` (failure_class, control_point, product, work_center, team); rows with `percent`, `cumulativePercent` |
| GET | `/quality-control/statistics/spc` | read | `controlPointId` (measure), `from`, `to`, `productId`, `workCenterId`, `baseline` (≥ 2), `rules` (`1,4` default), `segment`; chart, capability, segments, excluded counts, dataset ids |

47 routes. The overview gains `openAlerts`.

## Generated checks and blocking (QC2)

| Document | Checks created / reconciled | Completion refused while blocked | Checks cancelled |
|---|---|---|---|
| Production order | confirm, start | complete (also by its work orders' checks) | cancel (with its work orders') |
| Work order | generate, start | complete (also the shop-floor complete) | its production order's cancel |
| Goods receipt | draft created (also the PO approval draft) | post; a one-shot PO receive is refused if a pre-receipt point matches | draft cancel |
| Sales delivery | create, re-plan | issue | cancel |

Only generated pre-completion checks block (`QcBlockingPolicy`). Quarantine-on-receipt points create post-receipt
checks when the receipt posts and hold their quantity in quarantine; the decision releases stock in the same
transaction (pass: all; fail: all but `failedQty`; waive: all but `excludedQty`). Cash sales and plain invoice posts
issue stock without a delivery and are not covered (ADR-S087, implementation notes QC2).

Every check response carries `blocksSource` and `canBlock`, both computed by `QcBlockingPolicy`.

## Check execution

| Type | Body | Result |
|---|---|---|
| `instructions` | — | always `passed` (acknowledged); `result: failed` is a 400 |
| `pass_fail` | `result`, or `samples[]` that all have a `result` | a failed sample cannot give a passed check |
| `measure` | `measureValue`, or `samples[]` with `measureValue` (not both) | inclusive limits; too many decimals is a 400, not a failure |
| `picture` | `pictureDocumentId` (a DMS document attached to `qc_check/{id}`), optional `result` | |
| `worksheet` | `worksheetAnswers` | `WorksheetEvaluator` on the pinned template version |

Samples: `{sampleRef?, batchNumber?, sampleQty?, measureValue?, result?, notes?}`, up to 200. A failed execution
takes `failureClassId` (required when the point sets `failureClassRequired`) and `failedQty` (≤ `coveredQty`).

## Error messages

| Situation | Status | Message |
|---|---|---|
| Execute a completed check | 409 | `Quality check QCC-000012 is already passed.` |
| Execute / reset / waive after source closure | 400 | `Quality check QCC-000012 is closed: its source document is completed. Open a correction check instead.` |
| Waive a passed check | 400 | `A passed check cannot be waived.` |
| Missing failure class | 400 | `A failure class is required when this check fails.` |
| Failed quantity too high | 400 | `The failed quantity exceeds the quantity the check covers.` |
| Picture not attached | 400 | `The photo must be attached to the check first.` |
| Cancel a generated check | 400 | `A generated check is cancelled by its source document only.` |
| Quantity-excluded waiver without quantity | 400 | `excludedQty is required for disposition quantity_excluded.` |
| Measure point without limits | 400 | `A measure control point needs a minimum or a maximum.` |
| Quantity point without percentage | 400 | `A quantity control point needs a test percentage.` |
| Receipt mode on a non-receipt point | 400 | `A receipt mode applies to receipt control points only.` |
| Edit an old template version | 400 | `Only the latest version of a worksheet template can be edited.` |
| Complete with pending blocking checks | 400 | `Quality checks are pending: QCC-000012, QCC-000013.` |
| Complete with failed blocking checks | 400 | `Quality checks failed: QCC-000012. A quality manager can waive them.` |
| One-shot receive under a pre-receipt point | 400 | `Quality checks are required before this receipt is posted. Save it as a draft goods receipt, complete the checks, then post.` |
| Pass a generated quantity check without enough samples | 400 | `Record samples covering the test quantity (2) before passing this check; sampled so far: 1.` |
| Reset a check that moved stock | 400 | `Quality check QCC-000012 has moved stock, so it cannot be reset. Open a correction check instead.` |
| Release cannot move | 400 | `Quality check QCC-000012: only 3 is left in quarantine; 10 should be released. The decision is not saved.` |
| Receipt quarantine cannot move | 400 | `Quality check QCC-000012: only 3 of 10 can be moved into quarantine, so the receipt is not posted.` |
| Retry without a failed move | 400 | `Quality check QCC-000012 has no failed quarantine move.` |
| Quarantine on receipt per operation | 400 | `Quarantine on receipt needs a check per product or per quantity.` |
| Deactivate a stage with alerts | 400 | `Move the 1 alert(s) out of stage On hold before deactivating it.` |
| Move to an inactive stage | 400 | `Alert stage On hold is inactive.` |
| Duplicate stage / tag / root cause name | 409 | `An alert stage named X already exists.` (tag / root cause alike) |
| Link a correction check of another check | 400 | `Link a correction check of this alert's check.` |
| `sourceType` without `sourceId` | 400 | `sourceType and sourceId go together.` |
| SPC on a non-measure point | 400 | `SPC needs a measure control point.` |
| SPC segment out of range | 400 | `Segment 5 does not exist; this point has 2 specification segment(s).` |

## DMS record types

| `resType` | Read | Write |
|---|---|---|
| `qc_control_point` | `quality_control:read` | `quality_control:configure` |
| `qc_check` | `quality_control:read` | `quality_control:check` |
| `qc_alert` (QC3) | `quality_control:read` | `quality_control:alert` |

## Web

Nav group "Quality Control" (Manufacturing & Operations): Overview (`/quality-control`), Quality Checks, and under
Configuration: Control Points, Worksheet Templates, Quality Teams, Failure Classes. QC2 adds Quarantine Exceptions
(`/quality-control/quarantine-exceptions`, retry for managers), a "Quality checks" panel on the production order, goods
receipt and delivery detail views, and the check list on the shop-floor terminal's Finish dialog (Finish stays disabled
while a check blocks). QC3 adds Quality Alerts (`/quality-control/alerts`: kanban with drag-and-drop between stages,
or a list; detail at `/quality-control/alerts/:id` with investigation fields, NCR escalation, evidence and history),
Alert Settings (stages, tags, root causes) under Configuration, a "Quality alert" button on the check detail and on
the document quality-checks panel, and an "Open alerts" tile on the overview. QC4 adds Quality Statistics (`/quality-control/statistics`, Reporting):
pass-rate trend, Pareto by dimension, failure breakdowns, and a control chart per measure point (CL / UCL / LCL from
the data, USL / LSL drawn separately, flagged points, provisional badge, range chart, capability tiles or the reason
they are withheld).

## Deviations

None from Nest (no Nest counterpart). Choices that differ from the plan are recorded in ADR-S087 under
"Implementation notes".
