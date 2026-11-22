
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

— (Nest had none. Spring-only jobs: `CrmReminderJob`, `CrmCaseSlaJob`, `CrmActivityDigestJob`; see the sections below.)

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

## CRM enhancements T1 (2026-11-22, post-parity)

Improvements beyond the NestJS reference, approved by the owner ("build all three tiers"). No parity route, JSON field
or error message changed; the additions below are new behaviour.

- **Case response-time alerts:** `CrmCaseSlaJob` runs every five minutes (ShedLock `crm-case-sla`) →
  `CrmCaseService.sendSlaAlerts`.
  - A live case (`open` / `in_progress`) is warned once when 75 % of its response time has passed. The in-app
    notification (`crm.case.sla_warning`) goes to the assignee, or to the creator when the case is unassigned.
  - It is flagged once when overdue: `crm.case.sla_breached` goes to the assignee and the creator, and an `sla` entry
    is added to the timeline.
  - The new columns `CrmCase.slaWarnedAt` / `slaBreachedAt` (V601) keep each alert to one. Changing `dueAt` via PATCH
    clears both.
- **Overdue-activity digest:** `CrmActivityDigestJob` runs daily at 07:00 in `APP_TIMEZONE` (ShedLock
  `crm-activity-overdue-digest`). It sends one in-app notification per person with planned activities due before
  today (`crm.activity.overdue_digest`, link `/crm/activities?state=overdue`; the Activities page opens that tab).
- **Quotation emails on the timeline:** `POST /crm/quotations/{id}/email` (and the sales route) writes a done `email`
  activity on the quote's opportunity and customer, through the new `QuotationOpportunityPort.quotationEmailed`
  (a no-op without CRM).
- **Duplicate check:** `GET /crm/leads/duplicates?email=&phone=&companyName=&excludeId=` (`crm:lead:read` or
  `crm:deal:read`).
  - Returns `{deals, partners}` matching on email (case-insensitive), phone (last 9 digits, at least 7) or company
    name, each with `matchedOn`.
  - Deals the caller may not see come back with `id` / `name` null and `visible: false`.
  - The check is advisory: the New lead dialog and the Convert dialog warn, and a customer match can be linked in one
    click.
  - Authorization matrix: 105 routes.
- **CSV export** of the Leads and Pipeline lists (current filters, all pages up to 5,000 rows; web only, using the
  existing list routes).
- Tests: `CrmEnhancementsIT`.

## CRM enhancements T2a (2026-11-22, post-parity)

- **Bulk actions:** `POST /crm/opportunities/bulk` (`crm:deal:write`; actions assign / move / lost / archive) and
  `POST /crm/leads/bulk` (`crm:lead:write`; assign / lost / archive).
  - Body: `{action, ids (≤ 200), ownerId | stageId | lostReasonId + lostNote}`.
  - `CrmBulkService` runs each record through the normal single-record use case in its own transaction, with the same
    visibility, rules, history, audit and events.
  - Response: `{action, done, failed: [{id, error}]}`. On the leads route an opportunity id fails with "Lead not
    found".
  - Web: tick boxes on the Leads table and the Pipeline list view, plus a sticky action bar.
- **Lead CSV import:** `POST /crm/leads/import` (`crm:lead:write`).
  - Body: `{rows (≤ 1000), dryRun, skipDuplicates}`.
  - Columns: name (required), companyName, contactName, email, phone, city, country, amount, expectedClose, notes,
    source (by name), tags ("a; b"), owner (user email).
  - Unknown sources and tags are warnings; an unknown owner, a bad amount or a bad date is a row error.
  - Rows are checked with the T1 duplicate check, plus repeats within the file.
  - Each created lead goes through `create` (auto-assignment when no owner).
  - Web: the Import dialog on Leads parses the CSV (comma or semicolon) in the browser, previews it with a dry run,
    then imports. A template download is included.
- **Salesperson targets on the dashboard:** `GET /crm/dashboard` adds `salesTargets`. These are the sales-module
  targets (SG-12) whose period covers today, with actual and %; in mine scope (or without view-all) only the caller's
  own.
- Authorization matrix: 108 routes. Tests: `CrmBulkImportIT`.

## CRM enhancements T2b (2026-11-22, post-parity)

- **Stage rules (V602):** `CrmStage.requiredFields` (any of amount, expectedClose, partnerId, contactName, email,
  phone) plus an optional automatic follow-up (`followUpType` call / email / meeting / task, `followUpTitle`,
  `followUpDays` 0–365).
  - Moving a deal into the stage (drag, PATCH `stageId`, bulk move) and the Won button check the target stage's
    fields: 400 "Fill in Expected revenue, Expected closing before moving to Proposal". Amount must be above 0.
  - A won caused by quotation conversion (`autoWinOnConversion`) skips the check, so a stage rule never blocks turning
    a quote into a sales document.
  - Entering the stage creates one planned activity for the deal owner, due in N days at 09:00 business time. It is
    not created again while an open one with the same title exists.
  - Empty by default; set in CRM Settings → Stages.
- **Calendar:** `GET /crm/planner?state=range&from=YYYY-MM-DD&to=YYYY-MM-DD` (at most 62 days; done and planned, not
  cancelled). The Activities page has a List / Calendar toggle (week and month) and `?view=calendar`.
- **Personal ICS feed:** `GET|POST|DELETE /crm/planner/calendar-feed` (`crm:activity:read`) shows the status, creates
  a link (shown once) and turns it off.
  - The public route is `GET /public/crm/calendar/{token}.ics` (`@Public`, throttled 30/min).
  - It serves the user's activities from 30 days back to 180 days ahead as RFC 5545 VEVENTs.
  - Only the token's SHA-256 is stored (`CrmCalendarFeed`), one live link per user. A new link or turning it off
    makes the old one 404, as does an inactive user.
- Authorization matrix: 112 routes (the public ICS route included). Tests: `CrmStageRulesCalendarIT`.

## CRM enhancements T3a (2026-11-22, post-parity, ADR-S084)

- **Website lead forms (V603 `CrmLeadForm`):** CRM Settings → Website forms.
  - Each form has a name, source, team, salesperson (empty = normal auto-assignment), tags, allowed websites,
    thank-you text and an on/off switch.
  - Config routes: `GET|POST /crm/config/lead-forms`, `PATCH|DELETE /crm/config/lead-forms/{id}`,
    `POST /crm/config/lead-forms/{id}/rotate-key` (`crm:configure`).
  - Public routes: `GET /public/crm/forms/{key}` (for the hosted page) and `POST /public/crm/forms/{key}/submit`
    (10/min per address).
  - Submit fields: `name` (required), `email` and/or `phone`, `company`, `subject`, `message`, `pageUrl`, and the
    honeypot `_hp`.
  - Each submission creates a lead named "Website enquiry: {company or name}" (or the `subject`). The message and the
    page go in `notes`. `createdById` is null.
  - The owner gets an in-app notification (`crm.lead.web_form`).
  - Errors: "Please enter your name", "Please enter an email address or a phone number", "Please enter a valid email
    address", 403 "This website is not allowed to use the form", 404 "Form not found".
  - Two ways to put it on a site: the hosted page `/forms/lead/{key}`, or an HTML snippet. CORS is open for these two
    paths only.
- **Online quotation link (V603 `SalesQuoteLink`, licence `customerPortal`):**
  - Staff routes: `GET|POST|DELETE /sales/quotations/{id}/share-link`. Read needs `sales:quotation:read` or
    `crm:quotation:read`; create and revoke need `sales:quotation:send` or `crm:quotation:write`.
  - Only approved or sent quotations can be shared. A link is valid 60 days, and a new one replaces the old.
  - The customer page is `/q/{token}`, backed by `GET /public/sales/quotes/{token}`, `/pdf`, `POST …/accept`
    (`signerName` required, `note`) and `…/decline` (`reason`, `signerName`).
  - Accept and decline use the staff accept/decline rules. A link answers once (409).
  - The owner is notified on first view and on the answer, and the CRM timeline gets a done entry.
  - Editor menu: "Online link for the customer".
- Authorization matrix: 119 CRM routes. The sales routes are counted in `SalesOrderIT`. Tests:
  `CrmLeadFormQuoteLinkIT`.
- Open point (ADR-S084): without `server.forward-headers-strategy`, anonymous throttling behind nginx shares one bucket.

## CRM enhancements T3b (2026-11-22, post-parity)

Migration V604 adds `CrmCompetitor`, `Deal.competitorId`, `Deal.forecastCategory`, `CrmDealLine`, `CrmDealContact` and
`CrmEmailTemplate`.

- **Competitors:**
  - Config routes: `POST /crm/config/competitors`, `PATCH|DELETE /crm/config/competitors/{id}` (`crm:configure`).
    They also appear in `GET /crm/config/lookups` as `competitors`.
  - `POST …/lost` accepts `competitorId`; an unknown one is 404 "Competitor not found".
  - New report section `lostCompetitors`.
- **Forecast categories:** `forecastCategory` on PATCH: pipeline, best_case, commit or omitted. Empty means pipeline;
  any other value is 400.
  - The report's `forecast` rows gain `commit` and `bestCase` amounts.
  - New report section `forecastCategories`.
- **Product lines:** `PUT /crm/opportunities/{id}/lines` with `{lines, updateAmount = true}` replaces all lines (at
  most 200; `crm:deal:write`).
  - With `updateAmount`, the expected revenue becomes the total of qty × price × (1 − disc %), rounded HALF_UP to 2
    decimals.
  - Opportunity detail returns `lines`, each with product name, tax and line total.
  - A new quotation started from the opportunity pre-fills its lines from them (web).
- **Contacts on a deal:** `POST /crm/opportunities/{id}/contacts` with `{contactId, role}` (posting again updates the
  role) and `DELETE /crm/opportunities/{id}/contacts/{contactId}`. Opportunity detail returns `contacts`.
- **Merge deals:** `POST /crm/opportunities/{id}/merge` and `POST /crm/leads/{id}/merge` with `{sourceId}`. The lead
  route only merges leads.
  - Moves activities, quotations, product lines and contacts.
  - Fills the target's empty fields, combines tags, appends notes, logs a note and archives the source.
  - Refused when:
    - the source is won;
    - the target is not open;
    - the two records have different customers: 400 "The two records belong to different customers; merge the
      customers first".
- **Merge customers:** `GET /crm/customers/{id}/merge-preview?sourceId=` and `POST /crm/customers/{id}/merge`
  (`crm:configure`).
  - Only for a customer used in CRM alone. Its deals, activities, cases, contacts and quotations move over, child
    companies are re-parented, and it is archived through `PartnerService.remove`.
  - Any other reference blocks the merge with 409 "This customer also has records in X; only customers used in CRM
    alone can be merged". That means every foreign key to `Partner`, plus partner-like text columns, plus converted
    quotations.
  - Cross-module note: the merge updates `Contact.partnerId` and `SalesQuotation.partnerId` / `dealId` directly, in
    the same transaction, so a merge is all or nothing.
- **Email templates:**
  - `GET /crm/config/email-templates` (deal, lead or configure read) also returns `placeholders`.
  - `POST|PATCH|DELETE /crm/config/email-templates/{id}` (`crm:configure`).
  - Placeholders: `{{contact_name}} {{first_name}} {{company}} {{deal_name}} {{amount}} {{salesperson}} {{my_name}}
    {{organization}}`. An unknown one is 400.
  - `GET /crm/opportunities|leads/{id}/email?templateId=` returns `{to, subject, body}` rendered.
  - `POST /crm/opportunities|leads/{id}/email` with `{to, subject, body}` sends plain text and logs a done email
    activity "Email: {subject}".
- Authorization matrix: 137 routes. Tests: `CrmDealExtrasIT`.
