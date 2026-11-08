# ADR-S061 — Configurable approval rules and the approval hub

- Status: proposed (owner asked for an approval hub and a rules builder like `references/approvals/*`, 2026-11-08).
- Builds on: the Nest approval engine (`approvals.service.ts`, ported in P1) and CTRL-1 (branch / department
  conditions, department-manager steps, limits, separation of duties).
- Extension: no NestJS counterpart. Additive migration V421, additive JSON fields, five new routes. Every existing
  workflow behaves exactly as before: new columns default to "no condition", precedence 0, priority `medium`.

## Context

Approval routing was configurable only by document type, a workflow-wide `minAmount`, per-step amount bands, branch and
department. Deciders were "holders of these permissions" or "the department manager". Users could not:

1. route on any other document attribute (category, vendor, cost centre, budget code, …) or combine tests with AND / OR;
2. assign a stage to a named person or a role;
3. see, in one place, what is waiting on them and where each request sits in its route.

## Decision

### Rules (`ApprovalWorkflow`)

- `conditions` (jsonb): a condition tree `{"logic": "and"|"or", "rules": [leaf | group]}` with leaves
  `{"field", "operator", "value"}`. Fields are dot paths into the request snapshot; the engine also resolves `amount`,
  `branchId`, `departmentId` (the requester's when the snapshot has none, as in CTRL-1) and `requesterId`.
  Operators: `eq neq gt gte lt lte between in not_in contains not_contains starts_with is_empty is_not_empty`.
  Numbers compare as decimals when both sides parse as numbers; everything else compares as case-insensitive text.
  A missing field fails every positive test. Depth ≤ 4, ≤ 40 leaves; malformed trees are a 400 listing every problem.
- `precedence` (int, default 0): rules of one document type are tried highest precedence first, then by specificity
  (branch, department, *has conditions*), then `minAmount` — the CTRL-1 order with two keys in front.
- `priority` (`low|medium|high|critical`, default `medium`): stamped on each request the rule routes.
- `description` (text).

### Stages (`ApprovalStep`)

- `conditions` (jsonb): the stage applies only when its tree holds (in addition to the amount band, branch and
  department). A failing stage is skipped both when requesting and when advancing.
- `approverScope` gains `user` (`approverUserIds`) and `role` (`approverRoleIds`, any active holder).
- Every named scope (`department_manager`, `user`, `role`) removes the requester and **falls back to the stage
  permissions when nobody else resolves**, so no request is ever stranded. This extends the CTRL-1 rule for department
  managers to the new scopes.
- A `user` stage cannot require more approvals than it has assigned users (400).
- New refusal: `You are not assigned to decide on this step` (named user / role stages).

### Requests (`ApprovalRequest`)

- `priority`: the snapshot's own `priority` when it is one of the four values, else the rule's.

### Routes

| Route | Access | Purpose |
|---|---|---|
| `GET /approvals/inbox?scope=mine\|requested\|all&status&priority&entityType&q` | signed in; `all` needs `approvals:read` | Hub cards: reference, title, amount, priority, requester, current stage, `canDecide` |
| `GET /approvals/summary` | signed in | Badge counts |
| `GET /approvals/{id}/timeline` | signed in, tenant scoped | Every stage with state, named assignees, decisions; labelled snapshot details |
| `GET /approval-workflows/meta?entityType` | `approvals:read` | Builder catalogue: fields (engine keys + keys seen on the type's last 50 snapshots), operators, scopes, priorities, users, roles |
| `POST /approval-workflows/simulate` | `approvals:read` | Dry run of the saved rules for a sample snapshot; writes nothing |

`canDecide` mirrors `decide` short of transaction limits and SoD pairs, which are still enforced when deciding.
Notifications for `user` / `role` stages go to exactly the assignees and link to `/approvals?request=<id>`.

### Web

- `/approvals` becomes the **Approval Hub**: inbox (awaiting me / my requests / all, search, priority), detail with a
  stage stepper (initiated → stages, assignee avatars, state), document details grid, activity, approve / reject
  (rejection needs a comment in the UI only — the API is unchanged).
- `/approval-workflows` becomes **Workflow Configuration**: module navigator, rule cards with a plain-language
  "If …" sentence, and the **Approval Rules Builder** (condition rows with field / operator / value and AND/OR, nested
  groups, horizontal stage flow with assignees, per-stage conditions, scope & options, Test dialog using `simulate`).

## Consequences

- Wire contract: existing routes keep paths, verbs, permissions and messages; their JSON gains fields
  (`priority` on requests; `description`, `conditions`, `precedence`, `priority` on workflows; `conditions`,
  `approverUserIds`, `approverRoleIds` on steps). The parity harness must ignore these keys for approval scenarios.
- Snapshot keys become part of a document type's configuration surface. Renaming a snapshot key in a module breaks rules
  that test it; the builder shows fields from recent snapshots so admins see what exists.
- The inbox evaluates eligibility per request (≤ 300 newest per call). Enough for interactive use; a materialized
  assignee table would be the next step if inboxes grow.
