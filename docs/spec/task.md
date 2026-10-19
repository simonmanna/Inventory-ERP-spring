<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
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

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/task-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
