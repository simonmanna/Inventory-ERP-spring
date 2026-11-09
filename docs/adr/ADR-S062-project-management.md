# ADR-S062 — Project management workspace (tasks, timesheets, insights)

- Status: accepted (built 2026-11-09, commit `0c7ef4da`; tests and spec completed 2026-11-09)
- Builds on: [ADR-S038](ADR-S038-project-core.md) (project core and job costing) and
  [ADR-S039](ADR-S039-contract-commercials.md) (contract commercials). Roadmap item 2.9 "Projects & service billing".
- Migration: `V430__project_management.sql`
- Extension: no NestJS counterpart. Same module `com.inventoryerp.projects`; spec [docs/spec/projects.md](../spec/projects.md).

## Context

- ADR-S038 introduced `Project` as a **cost object**: sites, WBS, budgets, forecasts, cost capture to 1460 Project WIP.
  ADR-S039 added the contract side (variations, claims, retentions, IFRS 15 revenue, IAS 37 onerous contracts).
- Nothing let a team *run* the project: no tasks, no board, no members, no deadlines, no time logging against a task.
  The Nest `task` module (ported in P3c) is a flat CRM to-do list with no project, stage or time concept and must keep
  its contract unchanged.
- HR already had timesheets (`HrTimesheet` / `HrTimesheetEntry` with `linkedEntityType`), and ADR-S038 already
  captures **approved** HR entries linked to a project as labour cost. A second, unconnected time store would split the
  truth.
- Users expect the interaction model of mainstream PM tools (Odoo Project, Asana, Jira): per-project kanban stages,
  several assignees, tags, subtasks, dependencies, milestones, checklists, chatter with followers, recurring tasks,
  timesheets with a timer and a weekly grid, status updates, templates.

## Decision

### 1. Data model (V430)

`Project` gains workspace columns instead of a parallel table, so a job-costing project *is* the PM project:
`description, managerId, color, privacy (internal | members), allowTimesheets, allowMilestones, allowDependencies,
allowRecurring, isTemplate, salesOrderId, dealId, plannedHours, lastUpdateStatus, taskSeq`.

| Table | Holds |
|---|---|
| `ProjectMember` | `(projectId, userId)` with role `manager / member / viewer` |
| `ProjectFavorite` | per-user favourite projects |
| `ProjectTaskStage` | per-project kanban columns with `kind` `open / done / cancelled` and `folded`. Default set: To Do, In Progress, Review, Done, Cancelled (backfilled for existing projects) |
| `ProjectTag` | organisation-wide tags, unique by `lower(name)` |
| `ProjectMilestone` | name, deadline, reached flag / by / at |
| `ProjectTask` | number (`<project code>-n` or `TODO-n` for a personal to-do with no project), stage, parent (subtasks), milestone, WBS element and site (job-costing link), priority, kanban state, planned start / deadline, planned and effective hours, recurrence, linked record (`sourceResType/Id`), checklist counters, `version`, soft delete |
| `ProjectTaskAssignee`, `ProjectTaskTag`, `ProjectTaskDependency`, `ProjectTaskFollower`, `ProjectTaskChecklistItem` | the task's many-to-many parts; a dependency cannot point to itself |
| `ProjectMessage` | chatter of a project or task: `comment` (notifies followers / members), `note` (internal), `system` (tracked change) with `mentions` |
| `ProjectTimesheetLine` | user, date, hours (0 < h ≤ 24), billable flag, `hrEntryId` (the HR mirror) |
| `ProjectTimer` | one running timer per user |
| `ProjectUpdate` | status update (`on_track / at_risk / off_track / on_hold / done`), progress, summary, frozen task stats |
| `ProjectCounter` | per-organisation counters (project codes `PRJ-0001`, to-do numbers) as plain rows, so the app role never owns sequences |

Every new table has the tenant row-level-security policy, and every query filters `organizationId`.

### 2. Permissions

Two families, deliberately separate:

| Family | Permissions | Opens |
|---|---|---|
| Job costing / contracts (ADR-S038/S039) | `project:read`, `project:manage`, `project:post`, `contract:*` | money: budgets, forecasts, cost capture, claims, revenue. |
| Project management (this ADR) | `project_task:read`, `project_task:write`, `project_task:manage`, `project_timesheet:log`, `project_timesheet:manage`, `project_report:read` | the workspace: boards, tasks, milestones, chatter, own time, reports |

- `project:manage` creates projects (`POST /projects/workspace`, `/from-sales-order`, `/duplicate`) and also sees every
  project. `project_task:manage` sees every project and may delete any task.
- `project_timesheet:manage` reads and edits everyone's time; `project_timesheet:log` only the caller's own.
- Holding a PM permission never opens a costing or contract route and vice versa (`ProjectAuthorizationIT` PA-1).
- The cost figures on the overview are only included when the caller also has `project:read`.

### 3. Membership and privacy

- `internal` projects are visible to every holder of `project_task:read`; `members` projects only to their members,
  the manager, and holders of `project_task:manage` / `project:manage`. Invisible projects answer **404**
  (`Project not found`), never 403, so existence does not leak.
- Roles on a project: the **manager** (and `project:manage`) changes settings, stages, milestones, members and posts
  status updates; a **member** edits tasks; a **viewer** reads only and cannot log time. On an internal project any
  `project_task:write` holder may edit tasks.
- The manager is always kept as a member with role `manager` whatever `PUT /members` sends.
- Personal to-dos (no project) are visible to their creator and assignees only.

### 4. Tasks

- Stage moves set `isClosed` from the stage kind; moving to a `done` stage is refused while a dependency is open
  (`This task is blocked by unfinished tasks: …`). Changing a stage's kind closes / reopens its tasks. A project
  always keeps at least one open stage.
- Dependencies are cycle-checked with a recursive query. Subtasks belong to their parent's project.
- Completing a recurring task schedules the next occurrence (deadline and planned start advanced by the interval;
  assignees, followers, tags and checklist copied unticked) unless it would fall after `recurrenceUntil`; the completed
  task stops recurring.
- Every tracked change writes a `system` message; assignment, mention, comment and completion send in-app
  notifications.

### 5. Time: one store, mirrored to HR, costed by ADR-S038

```
ProjectTimesheetLine ──mirror──▶ HrTimesheetEntry (linkedEntityType = 'project')
                                      │  HR submits / approves the weekly HrTimesheet
                                      ▼
                     POST /projects/:id/costs/timesheets (ADR-S038) ─▶ ProjectCostLine + JE Dr 1460 / Cr 5390
```

- Each project line is upserted into the user's HR timesheet through `HrProjectTimeService` (the HR module's
  application API — no repository access across modules). The HR entry carries minutes, the task number and the
  billable flag.
- Once the HR timesheet is submitted or approved the project line is **locked** (`This time is on a submitted or
  approved HR timesheet and can no longer be changed`). Deleting a line removes its HR entry.
- Task `effectiveHours` is recomputed from the lines on every change. A user can never log more than 24 h on one day.
- The timer logs the elapsed time (at least 0.02 h) on stop; starting it on another task stops and logs the first.

### 6. Reminders, events, attachments

- `ProjectReminderJob` (daily, `app.projects.reminder-cron`, ShedLock) notifies assignees of open tasks due today,
  tomorrow or overdue, once per task and day (`lastReminderOn`).
- Domain events (outbox): `project.task.created`, `project.task.assigned`, `project.task.stage_changed`,
  `project.task.completed`, `project.milestone.reached`, `project.update.posted`. They are in `packages/shared` and
  `DomainEvents.java` so communication rules can subscribe.
- `ProjectResourceProviders` registers `project` and `project_task` as DMS attachment targets (ADR-S044 DM-2).

### 7. Integrations

- `POST /projects/from-sales-order` creates a project from a confirmed order (customer, contract value and reference)
  with one task per order line, once per order.
- Templates (`isTemplate`) and `POST /projects/:id/duplicate` copy stages, milestones, tasks, checklists and
  dependencies, shifting dates by the new start date.

### 8. Maintaining the costing structure and subcontracts

- Sites, WBS elements and draft budgets can be edited and deleted after creation. A delete is refused once anything
  financial refers to the record (cost lines; for WBS also budget / forecast lines, variations, subcontracts and child
  elements), because the sub-ledger and the journal dimensions keep pointing at it. Tasks are planning data: they just
  lose the link. Approved and superseded budget versions stay frozen; a change is a new revision.
- Reclass needs a journal line id. `GET /projects/:id/costs/reclassable` lists the lines the reclass would accept (with
  what is already reclassed and still open), so the dialog offers a picker instead of a typed id. It needs
  `project:post` (the permission of the reclass itself) because it shows GL lines outside the project.
- A subcontract is completed (valuations stop, held retention stays until released) or, while nothing is valued,
  cancelled. A valued subcontract is never cancelled, so its cost and retention trail stays intact.
- Responses stay Prisma-shaped rows (`SqlRows`), like every other JdbcClient module; the new computed projection
  (`ReclassableLine`) is a record.

## Consequences

- One `Project` row serves planning, execution, costing and revenue; the web project page shows the PM tabs and,
  with the costing / contract permissions, the Costing and Contract tabs over the same project.
- Time is entered once. HR approval stays the control point before labour reaches the ledger.
- The Nest `task` module and its contract are untouched; the PM tasks live under `/project-tasks`.
- The PM routes are not money routes and take no `Idempotency-Key`; the cost and contract posting routes keep
  requiring it.
- Privacy is enforced in SQL (`PmSupport.visibleSql`) for lists and reports, and in `visibleProject` for single
  records; every new query must use one of them.
- Tests: `ProjectManagementIT`, `ProjectStructureIT`, `ProjectAuthorizationIT`, `ProjectTenantIsolationIT`, `ProjectTaskBehaviourIT`,
  `ProjectWorkspaceBehaviourIT` (and `ProjectCostScenarioIT`, `ContractRevenueScenarioIT` for ADR-S038/S039).
