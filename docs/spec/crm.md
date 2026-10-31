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

Same as Nest, kept on purpose:

- `PATCH /crm/deals/:id/stage` takes an untyped body (no DTO validation): an unknown stage fails in the
  database cast (500).
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

## Commissions (SALES-4)

Status: built, not compiled or run. `/commissions/*` (`commission:read|manage|approve`, `CrmCommissionController`),
tables `CommissionRule`, `CommissionEntry` (V64), screen `/commissions`. Uses `HrPayrollInputService.addEarning`
(source `sales_commission`). Tests: `CrmCommissionTest`, `CrmCommissionIT`.

