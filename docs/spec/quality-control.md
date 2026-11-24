# Quality control (module `qualitycontrol`, ADR-S087)

Odoo-style operational quality control: control points create quality checks on receipts, manufacturing orders, work
orders and deliveries; operators execute them (instructions, pass / fail, measure, picture, worksheet); checks can
block the operation; failed checks raise alerts handled by teams; measure checks feed SPC charts.

There is no NestJS counterpart: this is a post-parity owner request (2026-11-24), and every route is new. It is a
separate module beside `quality` (ADR-S013), which keeps specifications, inspection lots, NCR, CAPA and certificates
of analysis. The full design, including lifecycle, blocking and quarantine rules, is ADR-S087.

Status: QC1 (foundation) implemented. QC2 (triggers, blocking, quarantine), QC3 (alerts) and QC4 (SPC) follow.

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
| GET | `/quality-control/checks` | read | list envelope; `search`, `status`, `origin`, `checkType`, `sourceType`, `sourceId`, `controlPointId`, `productId`, `teamId` |
| GET | `/quality-control/checks/{id}` | read | with `executions` (newest first, each with `samples`, `superseded`), `events`, `corrections` |
| POST | `/quality-control/checks` | check | 201; a manual check, number `QCC-000001` |
| POST | `/quality-control/checks/{id}/evaluate` | check | dry run: `{result, evaluation, failureMessage}`, nothing saved |
| POST | `/quality-control/checks/{id}/execute` | check | writes the next execution revision |
| POST | `/quality-control/checks/{id}/waive` | manage | `{reason, disposition, excludedQty?}` |
| POST | `/quality-control/checks/{id}/reset` | manage | `{reason}`; opens revision n+1 |
| POST | `/quality-control/checks/{id}/corrections` | manage | 201; `{reason, notes?}`; a new check with `origin = correction` |
| POST | `/quality-control/checks/{id}/cancel` | manage | `{reason}`; manual or correction checks only |

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

## DMS record types

| `resType` | Read | Write |
|---|---|---|
| `qc_control_point` | `quality_control:read` | `quality_control:configure` |
| `qc_check` | `quality_control:read` | `quality_control:check` |
| `qc_alert` (QC3) | `quality_control:read` | `quality_control:alert` |

## Web

Nav group "Quality Control" (Manufacturing & Operations): Overview (`/quality-control`), Quality Checks, and under
Configuration: Control Points, Worksheet Templates, Quality Teams, Failure Classes.

## Deviations

None from Nest (no Nest counterpart). Choices that differ from the plan are recorded in ADR-S087 under
"Implementation notes".
