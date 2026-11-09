# Projects — job costing, contracts and project management

Package `com.inventoryerp.projects`. Spring-native (no NestJS counterpart). Designs:
[ADR-S038](../adr/ADR-S038-project-core.md) (project core and job costing, V231),
[ADR-S039](../adr/ADR-S039-contract-commercials.md) (contract commercials, IFRS 15 / IAS 37),
[ADR-S062](../adr/ADR-S062-project-management.md) (project management workspace, V430).

## Purpose

One `Project` row is planned, executed, costed and recognised as revenue:

```
 plan & run (ADR-S062)            cost (ADR-S038)                    revenue (ADR-S039)
 stages · tasks · milestones  →   budgets · forecast · WIP 1460  →   claims · retentions · IFRS 15 runs
 timesheets ─mirror─▶ HR ─approved─▶ /costs/timesheets               onerous assessments · position
```

## Data model

All tables carry `organizationId` and the tenant row-level-security policy.

| Area | Tables |
|---|---|
| Core (V231) | `Project`, `ProjectSite`, `ProjectWbsElement`, `ProjectBudget`, `ProjectBudgetLine`, `ProjectForecastLine`, `ProjectCostLine` |
| Contracts (CM-8b) | variations, revenue criteria assessments, advances, claims, subcontracts and valuations, retentions, revenue runs, onerous assessments — see ADR-S039 |
| Workspace (V430) | `Project` workspace columns, `ProjectMember`, `ProjectFavorite`, `ProjectTaskStage`, `ProjectTag`, `ProjectMilestone`, `ProjectCounter` |
| Tasks (V430) | `ProjectTask`, `ProjectTaskAssignee`, `ProjectTaskTag`, `ProjectTaskDependency`, `ProjectTaskChecklistItem`, `ProjectTaskFollower`, `ProjectMessage` |
| Time (V430) | `ProjectTimesheetLine` (mirrored to `HrTimesheetEntry`), `ProjectTimer`, `ProjectUpdate` |

Enumerations: project status `draft, active, on_hold, completed, closed, cancelled`; privacy `internal, members`;
member role `manager, member, viewer`; stage kind `open, done, cancelled`; task priority `low, normal, high, urgent`;
kanban state `normal, blocked, ready`; recurrence `daily, weekly, monthly, yearly` (interval 1–366); update status
`on_track, at_risk, off_track, on_hold, done`.

## Permissions

| Permission | Allows |
|---|---|
| `project:read` | job-costing reads: projects, budgets, forecast, costs, cost report; cost figures on the overview |
| `project:manage` | create projects (incl. workspace, from sales order, duplicate), sites, WBS, budgets, forecast, status; sees every project |
| `project:post` | cost capture and reversal (journal entries) |
| `contract:read` / `contract:manage` / `contract:post` / `contract:approve` | ADR-S039 contract routes; approvals are never your own |
| `project_task:read` | boards, tasks, milestones, updates, chatter of visible projects; dashboard and gantt |
| `project_task:write` | create and edit tasks, chatter, checklist, dependencies; settings / stages / milestones / members / updates also need the project manager role or `project:manage` |
| `project_task:manage` | see every project (members-only included), delete any task |
| `project_timesheet:log` | own timesheet lines, weekly grid, timer |
| `project_timesheet:manage` | everyone's time |
| `project_report:read` | task, timesheet and workload analysis |

## Routes

116 routes. All responses are plain JSON (no envelope); `GET /project-tasks` is `{ data, total }`. Posting routes
require an `Idempotency-Key` and replay it.

### ProjectController

| Verb | Path | Permission | Idempotency-Key | Status | Response |
|---|---|---|---|---|---|
| GET | `/projects` | `project:read` |  | 200 | array |
| POST | `/projects` | `project:manage` |  | 201 | object |
| GET | `/projects/:id` | `project:read` |  | 200 | object |
| PATCH | `/projects/:id` | `project:manage` |  | 200 | object |
| POST | `/projects/:id/status` | `project:manage` |  | 200 | object |
| POST | `/projects/:id/sites` | `project:manage` |  | 201 | object |
| POST | `/projects/:id/wbs` | `project:manage` |  | 201 | object |
| PATCH | `/projects/sites/:siteId` | `project:manage` |  | 200 | object |
| DELETE | `/projects/sites/:siteId` | `project:manage` |  | 200 | `{ id, deleted }` |
| PATCH | `/projects/wbs/:wbsId` | `project:manage` |  | 200 | object |
| DELETE | `/projects/wbs/:wbsId` | `project:manage` |  | 200 | `{ id, deleted }` |
| GET | `/projects/:id/budgets` | `project:read` |  | 200 | array |
| POST | `/projects/:id/budgets` | `project:manage` |  | 201 | object |
| GET | `/projects/budgets/:budgetId` | `project:read` |  | 200 | object |
| PATCH | `/projects/budgets/:budgetId` | `project:manage` |  | 200 | object |
| DELETE | `/projects/budgets/:budgetId` | `project:manage` |  | 200 | `{ id, deleted }` |
| POST | `/projects/budgets/:budgetId/approve` | `project:manage` |  | 200 | object |
| GET | `/projects/:id/forecast` | `project:read` |  | 200 | array |
| PUT | `/projects/:id/forecast` | `project:manage` |  | 200 | array |
| GET | `/projects/:id/costs` | `project:read` |  | 200 | array |
| GET | `/projects/:id/costs/reclassable` | `project:post` |  | 200 | array of `ReclassableLine` |
| POST | `/projects/:id/costs/issue` | `project:post` | yes | 201 | object |
| POST | `/projects/:id/costs/timesheets` | `project:post` | yes | 201 | object |
| POST | `/projects/:id/costs/equipment` | `project:post` | yes | 201 | object |
| POST | `/projects/:id/costs/reclass` | `project:post` | yes | 201 | object |
| POST | `/projects/costs/:costLineId/reverse` | `project:post` | yes | 200 | object |
| GET | `/projects/:id/report` | `project:read` |  | 200 | object |

### ContractController

| Verb | Path | Permission | Idempotency-Key | Status | Response |
|---|---|---|---|---|---|
| GET | `/projects/:id/revenue-criteria` | `contract:read` |  | 200 | array (newest first) |
| POST | `/projects/:id/revenue-criteria` | `contract:manage` |  | 201 | object |
| POST | `/projects/revenue-criteria/:assessmentId/approve` | `contract:approve` |  | 200 | object |
| GET | `/projects/:id/variations` | `contract:read` |  | 200 | array |
| POST | `/projects/:id/variations` | `contract:manage` |  | 201 | object |
| POST | `/projects/variations/:variationId/approve` | `contract:approve` |  | 200 | object |
| POST | `/projects/variations/:variationId/reject` | `contract:approve` |  | 200 | object |
| GET | `/projects/:id/advances` | `contract:read` |  | 200 | array (with `outstanding`) |
| POST | `/projects/:id/advances` | `contract:post` | yes | 201 | object |
| GET | `/projects/:id/claims` | `contract:read` |  | 200 | array |
| POST | `/projects/:id/claims` | `contract:manage` |  | 201 | object |
| GET | `/projects/claims/:claimId` | `contract:read` |  | 200 | object |
| POST | `/projects/claims/:claimId/certify` | `contract:post` | yes | 200 | object |
| POST | `/projects/claims/:claimId/cancel` | `contract:manage` |  | 200 | object |
| GET | `/projects/:id/subcontracts` | `contract:read` |  | 200 | array (with `supplierName`, `valuations[]`) |
| POST | `/projects/:id/subcontracts` | `contract:manage` |  | 201 | object |
| POST | `/projects/subcontracts/:subcontractId/valuations` | `contract:post` | yes | 201 | object |
| POST | `/projects/subcontracts/:subcontractId/complete` | `contract:manage` |  | 200 | object |
| POST | `/projects/subcontracts/:subcontractId/cancel` | `contract:manage` |  | 200 | object |
| GET | `/projects/:id/retentions` | `contract:read` |  | 200 | array |
| POST | `/projects/retentions/:retentionId/back-charge` | `contract:post` | yes | 200 | object |
| POST | `/projects/retentions/:retentionId/release` | `contract:post` | yes | 200 | object |
| GET | `/projects/:id/revenue-runs` | `contract:read` |  | 200 | array |
| GET | `/projects/:id/revenue-runs/preview` | `contract:read` |  | 200 | object |
| POST | `/projects/:id/revenue-runs` | `contract:post` | yes | 201 | object |
| POST | `/projects/revenue-runs/:runId/reverse` | `contract:post` | yes | 200 | object |
| GET | `/projects/:id/onerous-assessments` | `contract:read` |  | 200 | array |
| POST | `/projects/:id/onerous-assessments` | `contract:manage` |  | 201 | object |
| POST | `/projects/onerous-assessments/:assessmentId/approve` | `contract:approve` |  | 200 | object |
| POST | `/projects/onerous-assessments/:assessmentId/reject` | `contract:approve` |  | 200 | object |
| POST | `/projects/onerous-assessments/:assessmentId/post` | `contract:post` | yes | 200 | object |
| GET | `/projects/:id/contract-position` | `contract:read` |  | 200 | object |

### ProjectWorkspaceController

| Verb | Path | Permission | Idempotency-Key | Status | Response |
|---|---|---|---|---|---|
| GET | `/projects/workspace` | `project_task:read` |  | 200 | array |
| POST | `/projects/workspace` | `project:manage` |  | 201 | object |
| POST | `/projects/from-sales-order` | `project:manage` |  | 201 | object |
| GET | `/projects/assignable-users` | `project_task:read` |  | 200 | array |
| GET | `/projects/:id/workspace` | `project_task:read` |  | 200 | object |
| PATCH | `/projects/:id/workspace` | `project_task:write` |  | 200 | object |
| POST | `/projects/:id/favorite` | `project_task:read` |  | 200 | object |
| GET | `/projects/:id/overview` | `project_task:read` |  | 200 | object |
| PUT | `/projects/:id/members` | `project_task:write` |  | 200 | array |
| POST | `/projects/:id/stages` | `project_task:write` |  | 201 | array |
| POST | `/projects/:id/stages/reorder` | `project_task:write` |  | 200 | array |
| PATCH | `/projects/stages/:stageId` | `project_task:write` |  | 200 | array |
| DELETE | `/projects/stages/:stageId` | `project_task:write` |  | 200 | array |
| GET | `/projects/:id/milestones` | `project_task:read` |  | 200 | array |
| POST | `/projects/:id/milestones` | `project_task:write` |  | 201 | array |
| PATCH | `/projects/milestones/:milestoneId` | `project_task:write` |  | 200 | array |
| DELETE | `/projects/milestones/:milestoneId` | `project_task:write` |  | 200 | array |
| GET | `/projects/:id/updates` | `project_task:read` |  | 200 | array |
| POST | `/projects/:id/updates` | `project_task:write` |  | 201 | array |
| GET | `/projects/:id/messages` | `project_task:read` |  | 200 | array |
| POST | `/projects/:id/messages` | `project_task:write` |  | 201 | array |
| POST | `/projects/:id/duplicate` | `project:manage` |  | 201 | object |
| GET | `/project-tags` | `project_task:read` |  | 200 | array |
| POST | `/project-tags` | `project_task:write` |  | 201 | object |
| PATCH | `/project-tags/:id` | `project_task:write` |  | 200 | object |
| DELETE | `/project-tags/:id` | `project:manage` |  | 200 | object |

### ProjectTaskController

| Verb | Path | Permission | Idempotency-Key | Status | Response |
|---|---|---|---|---|---|
| GET | `/project-tasks` | `project_task:read` |  | 200 | object |
| POST | `/project-tasks` | `project_task:write` |  | 201 | object |
| POST | `/project-tasks/bulk` | `project_task:write` |  | 200 | object |
| PATCH | `/project-tasks/checklist/:itemId` | `project_task:write` |  | 200 | object |
| DELETE | `/project-tasks/checklist/:itemId` | `project_task:write` |  | 200 | object |
| GET | `/project-tasks/:id` | `project_task:read` |  | 200 | object |
| PATCH | `/project-tasks/:id` | `project_task:write` |  | 200 | object |
| DELETE | `/project-tasks/:id` | `project_task:write` |  | 200 | object |
| POST | `/project-tasks/:id/move` | `project_task:write` |  | 200 | object |
| POST | `/project-tasks/:id/done` | `project_task:write` |  | 200 | object |
| POST | `/project-tasks/:id/dependencies` | `project_task:write` |  | 201 | object |
| DELETE | `/project-tasks/:id/dependencies/:dependsOnId` | `project_task:write` |  | 200 | object |
| POST | `/project-tasks/:id/checklist` | `project_task:write` |  | 201 | object |
| GET | `/project-tasks/:id/messages` | `project_task:read` |  | 200 | array |
| POST | `/project-tasks/:id/messages` | `project_task:write` |  | 201 | array |
| POST | `/project-tasks/:id/follow` | `project_task:read` |  | 200 | object |

### ProjectTimesheetController

| Verb | Path | Permission | Idempotency-Key | Status | Response |
|---|---|---|---|---|---|
| GET | `/project-timesheets` | `project_timesheet:log` |  | 200 | array |
| POST | `/project-timesheets` | `project_timesheet:log` |  | 201 | object |
| GET | `/project-timesheets/week` | `project_timesheet:log` |  | 200 | object |
| PUT | `/project-timesheets/week` | `project_timesheet:log` |  | 200 | object |
| GET | `/project-timesheets/timer` | `project_timesheet:log` |  | 200 | object |
| POST | `/project-timesheets/timer/start` | `project_timesheet:log` |  | 200 | object |
| POST | `/project-timesheets/timer/stop` | `project_timesheet:log` |  | 200 | object |
| POST | `/project-timesheets/timer/discard` | `project_timesheet:log` |  | 200 | object |
| PATCH | `/project-timesheets/:id` | `project_timesheet:log` |  | 200 | object |
| DELETE | `/project-timesheets/:id` | `project_timesheet:log` |  | 200 | object |
| GET | `/project-reports/dashboard` | `project_task:read` |  | 200 | object |
| GET | `/project-reports/tasks` | `project_report:read` |  | 200 | array |
| GET | `/project-reports/timesheets` | `project_report:read` |  | 200 | array |
| GET | `/project-reports/workload` | `project_report:read` |  | 200 | array |
| GET | `/project-reports/gantt` | `project_task:read` |  | 200 | object |

## Rules

### Job-costing structure (`ProjectService`, `ProjectCostService`)

- Sites: `PATCH` changes code (unique on the project, `409 Site X already exists on this project`), name, address and
  delivery location; a null field stays, `""` clears address / delivery location. `DELETE` is refused once a project
  cost line points at the site (`Site X has project cost lines and cannot be deleted`); tasks linked to it lose the link.
- WBS elements: `PATCH` changes code (unique), name, flags and parent (`""` = top level; never itself or one of its
  descendants: `A WBS element cannot be placed under itself or one of its children`). `DELETE` needs no children
  (`Move or delete the child elements of WBS element X first`) and no cost, budget or forecast line, variation or
  subcontract on it (`WBS element X is used by cost, budget or forecast lines, variations or subcontracts and cannot be
  deleted`); tasks lose the link.
- Budgets: only a draft can be changed (`lines` replace every line, `notes` replace the notes; `Only a draft budget can
  be changed`) or deleted (`Only a draft budget can be deleted`). Approved and superseded versions are frozen.
- Reclass candidates (`GET …/costs/reclassable?search&from&to&limit`): debit lines of posted, non-reversed,
  non-reversal journal entries on expense / cost-of-sales accounts, not project postings, with an open amount — newest
  first, `limit` 1–200 (default 50). Each row: `journalLineId, journalEntryId, entryNumber, postingDate, accountId,
  accountCode, accountName, description, partnerName, amount, reclassed, open`. The reclass dialog picks from it.
- Subcontracts: `complete` an active subcontract (valuations stop, held retention stays until released); `cancel` only
  while it has no valuation (`A subcontract with valuations cannot be cancelled; complete it instead`); a closed one
  answers `This subcontract is already <status>`.

### Visibility (`PmSupport`)

- A project is visible when the caller has `project_task:manage` or `project:manage`, the project is `internal`, or
  the caller is its manager / a member. Otherwise every route answers `404 Project not found` (`Task not found`,
  `Timesheet line not found`, … for child records). Lists and reports filter with the same condition in SQL.
- Personal to-dos (no project) are visible to their creator and assignees only.
- Another organisation always gets 404 and empty lists.

### Workspace

- New project: code `PRJ-0001…` unless given, status `active`, five default stages, creator is manager. `endDate`
  must not be before `startDate` (`endDate must not be before startDate`). Unknown fields: `property X should not exist`.
- Only the manager or `project:manage`: settings, members, stages, milestones (except marking reached), status updates
  (`Only the project manager can change the project settings`).
- Members: `PUT` replaces the list; the manager always stays with role `manager`; unknown users are rejected
  (`User <id> is not a user of this organization`); newly added members are notified.
- Stages: a project keeps at least one open stage (`A project needs at least one open stage`); a stage with tasks
  cannot be deleted (`Move the tasks out of this stage first`); reorder must list every stage once
  (`stageIds must list every stage of the project exactly once`); changing a stage's kind closes / reopens its tasks.
- Milestones: refused when disabled (`Milestones are disabled on this project`); reaching one writes a system
  message, notifies members and publishes `project.milestone.reached`; deleting one unlinks its tasks.
- Status updates: progress 0–100 (defaults to the task progress), stats frozen into the update, project
  `lastUpdateStatus` set, members notified, `project.update.posted`.
- Project status (`POST /projects/:id/status`): any status from a non-terminal one; `closed` and `cancelled` are final
  (`A closed project cannot change status`). Tasks cannot be added to a closed or cancelled project.
- Duplicate / template: copies stages, milestones, (optionally) tasks with checklist and dependencies, shifting dates
  by the difference of start dates; the caller becomes manager.
- From sales order: once per order (`409 Project <code> already exists for sales order <number>`), not from a
  cancelled order; customer, contract value and reference from the order; one task per line unless `taskPerLine: false`.
- Tags: unique per organisation case-insensitively (`409 A tag named X already exists`), at most 60 characters.

### Tasks

- Number `<project code>-<n>` or `TODO-<n>`. Creator, assignees and commenters follow the task.
- Done / move to a `done` stage refused while a dependency is open (`This task is blocked by unfinished tasks: …`).
  Dependencies: not on itself, no cycles (`This dependency would create a cycle`), refused when disabled.
- Subtasks belong to the parent's project; a task cannot become its own (indirect) subtask.
- Recurrence: completing schedules the next occurrence unless past `recurrenceUntil`; refused when disabled.
- Bulk: 1–200 tasks; stage, priority, deadline (blank clears), add an assignee; each task goes through the normal edit.
- Delete: creator, project manager or `project_task:manage`; deletes subtasks too (soft delete) and stops timers on them.
- Tracked changes (stage, priority, deadline, assignees, …) write `system` messages; comments notify followers,
  mentions notify the mentioned users.

### Time

- 0 < hours ≤ 24 per line and ≤ 24 per user and day (`More than 24 hours logged on <date>`); only on project tasks /
  projects with timesheets enabled; viewers cannot log (`Viewers cannot log time on this project`); others' time needs
  `project_timesheet:manage` (`You can only log your own time`, `You can only see your own time`).
- Each line is mirrored into the user's HR timesheet (`HrProjectTimeService`); locked once that timesheet is submitted
  or approved. HR-approved entries are captured to WIP by `POST /projects/:id/costs/timesheets`.
- Weekly grid `PUT` replaces the day's lines for that project / task with one line (0 deletes).
- Timer: one per user; stop logs at least 0.02 h on today; start on another task stops and logs the running one.

## Events

`project.task.created`, `project.task.assigned`, `project.task.stage_changed`, `project.task.completed`,
`project.milestone.reached`, `project.update.posted` (transactional outbox, `DomainEvents`, `packages/shared/src/events.ts`).
Cost and contract postings publish the accounting events of ADR-S038 / S039.

## Jobs

`ProjectReminderJob` — daily at `app.projects.reminder-cron` (default 06:00): one notification per open task and day to
the assignees of tasks due today, tomorrow or overdue (templates excluded). ShedLock `project-deadline-reminders`.

## Screens (web)

| Route | Screen |
|---|---|
| `/projects` | project cards / list, filters (my, favourites, templates, status), create (blank, from template, from sales order — the order picker needs `sales:order:read`) |
| `/projects/dashboard` | my tasks, overdue, hours this week, project health |
| `/projects/my-tasks` | tasks assigned to me across projects, personal to-dos; bulk priority / deadline |
| `/sales/orders/:id` | action menu: **Create project** (`project:manage`, order not cancelled) |
| `/projects/timesheets` | weekly grid, list, running timer |
| `/projects/reports` | task, timesheet and workload analysis |
| `/projects/tags` | tag maintenance |
| `/projects/:id` | header: project status selector (`project:manage`; closed / cancelled are final). Tabs: Board (kanban), List (bulk actions: stage, priority, assignee, deadline), Overview, Timeline (gantt), Milestones, Status updates, Discussion, **Costing** (sites and WBS with edit / delete, budgets with draft edit / delete and approval, forecast, cost capture with a reclass line picker, reversal, cost report — `project:read` / `manage` / `post`), **Contract** (variations, advances, claims, subcontracts with complete / cancel, retentions, revenue runs, onerous assessments, contract position — `contract:*`), Settings |

## Tests

| Class | Covers |
|---|---|
| `ProjectCostScenarioIT` | ADR-S038 capture paths, reversal, report and WIP tie-out, authz matrix of `ProjectController` (27 routes) |
| `ContractRevenueScenarioIT` | ADR-S039 contract flows, authz matrix of `ContractController` (32 routes) |
| `ProjectManagementIT` | PM-1..6: workspace, tasks, privacy, timesheets with HR mirror and cost capture, sales orders and templates, authz matrix of the three PM controllers (57 routes), GET sweep |
| `ProjectStructureIT` | PS-1..6: site, WBS and draft-budget edit / delete rules, reclass candidates, subcontract complete / cancel, 404 for another organisation on every new route |
| `ProjectAuthorizationIT` | PA-1: all 116 routes denied with every *other* project-family permission and allowed past the guard with exactly their own; PA-2: in-service rules (manager vs member, delete, `project_task:manage`, timesheet manager, reports) |
| `ProjectTenantIsolationIT` | PT-1: 40 record routes answer 404 to another organisation, lists and reports never leak, nothing changes; PT-2: members-only privacy across every surface |
| `ProjectTaskBehaviourIT` | PTB-1..6: dependencies, checklist counters, recurrence intervals and end date, board order / bulk / following, personal tasks and subtasks, timer and timesheet edge cases, weekly grid |
| `ProjectWorkspaceBehaviourIT` | PWB-1..5: stage kinds and rules, milestones, members / favourites / chatter / updates / status, duplicate and sales-order projects, tags and report shapes |

## Deviations

None (no Nest reference). Owner-visible choices are recorded in ADR-S062.
