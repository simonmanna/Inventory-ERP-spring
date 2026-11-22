
> **ADR-S011 (phase S0):** quotations and the quote policy settings are owned by the `sales` module since V24
> (`SalesQuotation*`, `SalesSettings`). `/crm/quotations*` keeps its routes and JSON and delegates to
> `SalesQuotationService`; CRM price rules serve quotation pricing through `QuotationPricingPort` until phase S2.
# CRM — `crm`

| | |
|---|---|
| Phase | P3c |
| Nest source | `reference/nest-api/src/modules/crm` |
| Routes | 21 — full table: [generated/crm.md](generated/crm.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.crm` |

## Purpose

Deals pipeline, activities, analytics.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| deals | CRUD, deleted, stage, restore |
| activities | CRUD, upcoming, deleted, complete, restore |
| analytics | pipeline, win-rate, forecast, top-customers, partner/:id |

## Business rules to reproduce exactly

1. Stages lead → qualified → proposal → negotiation → won | lost.
2. Moving to won marks the partner as customer; a won deal cannot be deleted.
3. Listener on `invoice.created` links invoices to open deals of the partner.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

consumes `invoice.created`.

## Scheduled jobs

—

## Notes and pitfalls

- Kanban UI: `web/src/features/crm/kanban.tsx`.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/crm-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

- **Sales suite (ADR-S010, pending owner approval)** — additive: `Deal` gains suite columns (`kind`, `stageId`,
  `status`, …; `partnerId` nullable for leads) that the parity JSON does not expose; legacy stage writes keep
  `status` / `stageId` in sync. No parity route, verb, status code or response field changed.
- **Legacy `/crm/deals` follows the pipeline (CRM review, 2026-11-22; owner approved "discard the old if not
  needed")** — the legacy routes stay (same paths, verbs and JSON) but no longer bypass the suite rules:
  - `PATCH /crm/deals/:id/stage` and a `stage` in `PATCH /crm/deals/:id` go through `CrmPipelineService`
    (`won` / `lost` / `reopen` / `move` to the first active stage mapped to that legacy stage). So a lead cannot be
    marked won ("Convert the lead to an opportunity before marking it won"), a won deal cannot go straight to lost
    ("A won opportunity cannot be marked lost; reopen it first"), and stage history / `wonAt` / `CRM_DEAL_WON` are
    written as on the kanban. An unknown or missing stage is a 400 ("stage must be one of the following values: …")
    instead of the 500 Nest gives.
  - `GET/PATCH/DELETE /crm/deals[/:id]`, `/deleted`, `/restore` and `/crm/analytics/*` apply the
    `crm:deal:view_all` visibility rule (own + unassigned + teams led); a hidden deal is 404 "Deal not found".
  - `POST /crm/deals` and `PATCH /crm/deals/:id` validate `ownerId` ("User not found") and the create writes the first
    `CrmStageHistory` row. Analytics sums use `BigDecimal`.
  - The web client no longer calls the legacy routes (old `features/crm/api.ts` and `deal-form.tsx` removed; the
    partner page's CRM tab uses `/crm/customers/:id`). Android has no CRM screens.

Same as Nest, kept on purpose:

- `PATCH /crm/deals/:id/stage` takes an untyped body (no DTO validation) — but see the deviation above: an unknown
  stage is now a 400, not a 500.
- `restore` audit rows use an action outside the `AuditAction` enum; the fire-and-forget audit write fails
  and is only logged, as in Nest.
- `GET /crm/analytics/forecast` builds prefilled month keys as `month + i + 1` without year roll-over
  (`YYYY-13` past December); deals closing next year land in their own real key after them.

## Status

P3c: 21/21 routes. `invoice.created` listener links the invoice to the partner's open deal (replay-safe).
Tests: `CrmIT` (ports `crm.service.spec.ts`: create / validation / audit + outbox event, stage timeline, won →
customer + delete guard, soft delete / restore / archived lists, `{data, meta}` paging, activities lifecycle,
invoice link replay, analytics, tenant isolation, authorization matrix = 21 routes). Parity scenario:
`contract-tests/scenarios/crm-pipeline.json`.

## Sales suite (ADR-S010)

67 routes under `/crm/config`, `/crm/saved-filters`, `/crm/opportunities`, `/crm/leads`, `/crm/planner`,
`/crm/pricing`, `/crm/quotations`, `/crm/reports`, `/crm/dashboard`, `/crm/customers`
(`crm/web/CrmSuiteController.java`). Design and rules: [ADR-S010](../adr/ADR-S010-crm-sales-suite.md).
Tests: `CrmSuiteIT` (stages config, lead conversion, kanban / grouped / list, won / lost / reopen + legacy sync,
planned activities + reminders, price rules, quotation approval / credit / conversion to Order and proforma, reports,
visibility, tenant isolation). Web: `web/src/pages/crm/*` (pipeline kanban, opportunity form, leads, quotations,
activities, customers, price rules, reports, configuration).

## Commissions (SALES-4) — superseded

Removed when the sales O2C module merged: commissions, targets and payroll statements live in sales
(ADR-S011 S9, `/sales/commissions`, `SalesCommissionService`, table `SalesCommissionRule`). Two engines feeding
payroll would pay a salesperson twice.

## CRM-1 complaints and service requests (built, unverified)

`CrmSuiteController.CaseController` (`/crm/cases`, 8 routes: list, get, create, `from-discrepancy/{id}`, patch,
`{id}/assign`, `{id}/notes`, `{id}/status`), `CrmCaseService`, `CrmCaseRules`, `CrmCaseDiscrepancySubscriber`, migration V94
(`CrmCase`, `CrmCaseEvent`), permissions `crm:case:read`, `crm:case:write`. Status: open → in_progress → resolved → closed,
cancelled with a reason, resolved can reopen. Screens: `/crm/cases`, `/crm/cases/:id`; "Open case" on
`/fleet/delivery-discrepancies`.

## CRM-2 tests and segments (built, unverified)

Tests in `CrmCustomerDataIT`. Segments are `CrmSavedFilter` rows with scope `customers` (V95); `/crm/customers` already
filters by category. `CrmReminderJob` is exercised directly, since scheduled jobs may be off in tests.

## CRM review fixes (2026-11-22)

Spring-native suite behaviour only; no parity route, JSON field or error message changed.

- **Stale approvals:** `SalesQuotationService.applyDecision` / `SalesOrderService.applyDecision` lock the row and ignore a
  decision whose `requestId` is not the record's current `approvalRequestId`. Revise, cancel and a direct
  approve / reject withdraw the pending kernel request (`ApprovalsService.withdraw`). Engine approvals now publish
  `crm.quotation.approved` like the manual route.
- **Expired quotes:** `convert` refuses a quote past `validUntil` ("Quotation has expired; revise it first", as `accept`).
  Quote dates (issue date, revise validity, expiry checks) use the business time zone (`APP_TIMEZONE`), not UTC.
- **Owner on create:** `POST /crm/opportunities` and `/crm/leads` validate `ownerId` like update / assign ("User not found").
- **Price rules:** PATCH checks the resulting rule (stored values + changes) for the customer/category and
  product/category pairs, the 100 % discount cap after a `kind` flip, and `validTo` ≥ `validFrom` — 400 instead of a
  stored conflicting rule or a 500 from the DB check.
- **Lookups:** `GET /crm/config/lookups` accepts any CRM read permission (or `sales:quotation:read`) via the new
  `@RequirePermissions(anyOf = true)`; the 403 message is unchanged.
- **New routes:** `GET /crm/quotations/{id}/pdf` (`crm:quotation:read`) and `POST /crm/quotations/{id}/email`
  (`crm:quotation:write`), delegating to the sales quotation PDF / email.
- **Lead-scoped record routes:** `GET /crm/leads/{id}`, `GET /crm/leads/{id}/timeline` (`crm:lead:read`),
  `PATCH /crm/leads/{id}`, `POST /crm/leads/{id}/lost`, `POST /crm/leads/{id}/reopen`, `DELETE /crm/leads/{id}`
  (`crm:lead:write`) — same JSON as the `/crm/opportunities/{id}` routes, refused with 404 "Lead not found" for an
  opportunity id; a `stageId` in the PATCH is ignored. A user with only lead permissions can now open and work a
  lead (web route `/crm/leads/:id`). Authorization matrix: 104 routes.
- **Events after commit:** CRM pipeline, legacy deal and quotation events use `publishOnCommit`, so a rolled-back
  request no longer announces a change that never happened.
- **Concurrency:** case update / assign / status lock the case row (`FOR UPDATE`); creating a case from a delivery
  discrepancy takes a per-discrepancy advisory lock so a double click cannot open two cases.
- **Report totals:** CRM report / analytics money sums use `BigDecimal`; the customer list's deal totals honour
  deal visibility and skip archived deals.
- **Stages:** deactivating the last active stage is refused ("The pipeline needs at least one stage"), as deleting it is.
- **Pipeline filters:** `priorityExact` and `wonFrom` / `wonTo` so the "group by priority / won month" rows open.
- Tests: `CrmReviewFixesIT`.
