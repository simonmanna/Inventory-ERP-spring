# Tasks — `task`

| | |
|---|---|
| Phase | P3c |
| Nest source | `reference/nest-api/src/modules/task` |
| Routes | 16 — full table: [generated/task.md](generated/task.md) |
| Feature flag | `ENABLE_TASKS` |
| Java package | `com.inventoryerp.task` |

## Purpose

Kanban tasks with checklists, comments, labels, templates, recurring templates, auto-rules triggered by domain events, verification, dashboard.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| tasks | CRUD, dashboard, reorder, checklist item toggle, comments, verify |
| labels/templates/auto-rules | CRUD |

## Business rules to reproduce exactly

1. Status: DRAFT, PENDING, ASSIGNED, IN_PROGRESS, WAITING, REVIEW, VERIFIED, COMPLETED, CANCELLED, SKIPPED, OVERDUE (uppercase DB values).
2. Verification only when `requiresVerification`; verifier must hold `task:verify` and differ from assignee if Nest enforces.
3. Auto-rules subscribe to events and create tasks from templates.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

consumes many domain events (auto-rules).

## Scheduled jobs

recurring task generation (check service).

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/task-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Pending owner approval:

1. `dueDate` (create / update) accepts a bare `YYYY-MM-DD` and stores UTC midnight; Prisma rejects anything
   but a full ISO date-time (500).

Same as Nest, kept on purpose:

- The controller is registered only with `ENABLE_TASKS=true` (`app.modules.tasks`; Nest imports `TaskModule`
  conditionally) and is also gated per tenant by `@RequiresModule("task")`. Routes declare **no** permissions.
- Unknown enum values (status, priority, …) fail in the database cast (500); `PATCH /:id/checklist/:itemId`
  on an unknown item is Prisma's unhandled P2025 (500).
- The activity-log diff compares JavaScript `String(value)` forms (dates as `Date#toString()`).
- The spec mentions auto-rules reacting to events and recurring generation: Nest has neither a subscriber nor a
  scheduled job for them — templates and rules are stored only.

## Status

P3c: 16/16 routes. Tests: `TaskIT` (CRUD + activity log diff, reorder, checklist progress, threaded comments,
verification, list envelope + query validation, dashboard, labels / templates / auto-rules, tenant isolation,
401 matrix = 16 routes). The test profile sets `app.modules.tasks=true`. Parity scenario:
`contract-tests/scenarios/task-board.json` (both servers need ENABLE_TASKS=true).
