# ADR-S038 — Project core and job costing (Cost Management CM-8a)

- Status: accepted (CM-8a built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), G10, AD-6, AD-9
- Migration: `V231__cm8a_project_core.sql`
- Extension: no NestJS counterpart. New module `com.inventoryerp.projects`.

## Context

- Procurement carried a free-text `projectId` on purchase-request lines (V152) and on commitments (V154). Nothing
  checked it and no project entity existed.
- Cost could not be collected by project, WBS element or site. Budgets, commitments and forecasts could not be
  compared.

## Decision

### 1. Master data

| Table | Holds |
|---|---|
| `Project` | code, name, customer, contract reference and value, branch, cost centre, status `draft → active → on_hold → completed → closed` (or `cancelled`), `needsReview` |
| `ProjectSite` | several sites per project, each with an address and a delivery location |
| `ProjectWbsElement` | hierarchy (`parentId`), `isBillable`, `isCostable` |
| `ProjectBudget` / `ProjectBudgetLine` | versioned: the first approved is `original`, later ones `revised`. Approving a version supersedes the previous approved one. Lines by WBS element × cost element (`material, labour, equipment, subcontract, other, overhead`) with an optional revenue amount |
| `ProjectForecastLine` | cost to complete per WBS element × cost element. Append-only; the latest row is in force. Each line carries a `fulfilmentClass` (`incremental / directly_related / excluded`) for the IAS 37.68A onerous-contract test (CM-8b) |

### 2. Project cost sub-ledger and capture paths

Every capture writes `ProjectCostLine` rows and **one journal entry** that debits **1460 Project Work in Progress**
with `projectId`, `wbsId` and `siteId` dimensions, the project's branch and cost centre. Cost is captured only on an
`active` project and only on a costable WBS element.

| Path | Route | Credit |
|---|---|---|
| Stock issue to a WBS element; delivery to a site (the site's delivery location is used) | `POST /projects/:id/costs/issue` | the product's stock account, at the carrying amount the stock engine computed (`skipGl` on the issue) |
| Approved timesheet entries linked to the project (`linkedEntityType = 'project'`) × rate | `POST /projects/:id/costs/timesheets` | 5390 Labour Absorbed to Projects. Each entry is captured once (unique index) |
| Equipment hours × rate | `POST /projects/:id/costs/equipment` | 5391 Equipment Absorbed to Projects |
| Reclass of a posted cost line: purchase or subcontract bill, fleet trip, overhead allocation | `POST /projects/:id/costs/reclass` | the source line's own account, never more than its unreclassed net debit; project postings cannot be reclassed again |

- `excludeFromProgress` marks cost that does not depict progress (uninstalled materials, inefficiencies) for the
  IFRS 15 input method (CM-8b).
- **Reversal** (`POST /projects/costs/:id/reverse`) reverses the journal entry with every cost line it carries and,
  for an issue, receives the stock back at its issue cost. A line cannot be reversed twice.
- All posting routes require an `Idempotency-Key`.

### 3. Commitments, forecast, EAC

- Committed = open `Commitment` amounts (purchase requests and orders) that carry the project.
- EAC = actual + committed + cost to complete.
- `GET /projects/:id/report` shows budget (approved version) vs actual vs cost to complete vs EAC per WBS element and
  cost element, the totals with committed cost, and a **WIP tie-out**: the GL balance of 1460 with the project
  dimension equals the sub-ledger, less the cost released to cost of revenue (CM-8b).

### 4. Free-text references become foreign keys

- V231 creates one placeholder project (`LEGACY-…`, `needsReview = true`) per organisation and distinct free-text
  value, repoints the rows and adds foreign keys on `PurchaseRequestLine.projectId` and `Commitment.projectId`.
- Purchase-request creation refuses an unknown project with `400 Project not found`. Updating a placeholder clears
  `needsReview`.

## Accounts

| Code | Name | Category | Mapping key |
|---|---|---|---|
| 1460 | Project Work in Progress | current_asset | `project_wip` |
| 5390 | Labour Absorbed to Projects | operating_expense | `project_labour_absorbed` |
| 5391 | Equipment Absorbed to Projects | operating_expense | `project_equipment_absorbed` |

Created on demand (`ensureByCode`), like earlier phases.

## Permissions

`project:read`, `project:manage`, `project:post`.

## Deferred

- `wbsId` on commitments (commitments are reported at project level, unassigned to a WBS element).
- Fleet trips and timesheets carrying `projectId` natively; today fleet cost reaches a project through a reclass of
  the posted cost and timesheets through `linkedEntityType = 'project'`.
- Overhead to projects through allocation cycles (segment receiver type `project`); use a reclass of the allocated
  line until then.
- Web screens for projects (APIs only in this phase).

## Tests

- `ProjectCostScenarioIT` (2): two sites, hierarchical WBS, original → revised budget, every capture path, draft and
  non-costable guards, timesheet entries captured once, reclass cap, open commitments, EAC, WIP tie-out = 0, reversal
  restores stock and cost, idempotency, tenant isolation, authorization matrix (20 routes); purchase-request project
  foreign-key guard.
