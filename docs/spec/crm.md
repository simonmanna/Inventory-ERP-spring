
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

- **CRM production-readiness fixes (2026-11-22, owner approved "P0 + P1"; see the section of that name)** — all on
  Spring-native suite routes except where noted:
  - Converting a quotation marks its open opportunity won even when the converter cannot see the deal (system side
    effect). Before, the conversion rolled back with 404 "Opportunity not found".
  - A non-numeric number in a CRM suite body or filter is a 400 naming the field, not a 500.
  - A public quotation link is revoked by a revision or cancellation of the quotation. A repeated identical answer is
    a 200 replay (a different answer is still 409).
  - Website forms need the signed submit token `_t` from `GET /public/crm/forms/{key}`. Without it the answer is 400
    "This form has expired; reload the page and try again". A token sent back within 2 s is dropped like the
    honeypot. A rolling 24-hour cap per form and per organization answers 429.
  - `POST /crm/{opportunities|leads}/{id}/email` returns `status` (`sent`, `retrying`, `failed`, `not_configured`)
    and `activityId` next to `sentTo` and `delivered`. It never logs "Queued for" an email that was dropped.
  - The quotation-email timeline entry says "Not sent to … (SMTP)" instead of "Queued for … (mail delivery not
    confirmed)" when no SMTP is set up.
  - Error bodies keep the Nest contract `{statusCode, message, error}` (docs/CONTRACT.md). A `code`/`details`
    envelope, as the external review suggested, would change every client's error handling, so it was not added.
  - PR B (P1):
    - Lead and opportunity rows carry `version`. A suite `PATCH`/`assign` that sends a stale `version` is 409 "This
      record was changed by someone else; reload it and try again". Without `version` it saves as before.
    - `POST /crm/customers/{id}/merge` also needs `partner:delete`. Rejected, expired and cancelled quotations stay
      with the archived customer, and the preview reports them as `closedQuotationsKept`.
    - Customer 360 (`/crm/customers/{id}`), the customers list and `GET /crm/analytics/partner/{id}` hide invoices,
      receivables, credit and spend without `invoice:read` (owner decision): same keys, `[]`/`null`, plus
      `financeHidden` on the 360. This applies to the legacy analytics route too.
    - `POST /sales/quotations/{id}/convert` and `/crm/quotations/{id}/convert` require an `Idempotency-Key`
      (400 without one).
    - A lost lead cannot be converted (400 "Restore the lost lead before converting it"), nor converted into an
      inactive or won stage (400 "Convert to an active, open stage; mark it won afterwards").

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
  Closed by the production-readiness fixes (P0-5): `native` is the default and public counters are per form / token.

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

## CRM enhancements T3c (2026-11-22, post-parity): CRM on the Android till

These are online-only screens in the Android app. There is no server change, no sync scope and no Room table. Sidebar: **Customers → CRM**.
- It is hidden on a standalone till.
- It is shown to staff whose synced roles hold `crm:activity:read`, `crm:lead:write` or `crm:deal:read`.

- **Sign-in:**
  - The salesperson signs in with their own ERP account through the existing `POST /auth/login`, then `/auth/mfa-login`
    when MFA is enrolled.
  - The account must be the same user as the PIN signed in on the till; otherwise the app refuses it. Everything done
    from the till is then attributed to the person holding it.
  - The access and refresh tokens are held in memory only, never on disk. The session ends when the till locks, idles
    out or another PIN signs in.
  - A 401 triggers one `POST /auth/refresh` and a retry; if that fails, the sign-in form returns.
  - Only the organization code is remembered, in the encrypted device preferences.
  - Requests use the device's server URL and its rule that plain HTTP is allowed only on the local network. The bearer
    token takes precedence over the device token on the server.
- **My activities:** `GET /crm/planner?state=overdue|today|upcoming` (the caller's own, with counts) and
  `POST /crm/planner/{id}/done` with an optional outcome.
- **New lead:**
  - `POST /crm/leads` with name, contact, company, phone, email and notes. The server's auto-assignment applies.
  - Possible duplicates are shown as typed, from `GET /crm/leads/duplicates`. They are advisory and never block the
    save.
- **Customers:** `GET /crm/customers?q=` and Customer 360 `GET /crm/customers/{id}`, which shows:
  - receivables, overdue amount and credit;
  - contacts, deals, invoices and recent activity.
- Each tab is shown only with its permission. The server enforces every route as for the web.
- Tests (Android unit, run by the Android CI job):
  - `CrmParsersTest`
  - `CrmRepositoryTest` (Robolectric): same-user rule, MFA, session ends on lock, refresh once, sign-out when refresh fails, lead body
  - `SidebarAccessTest` (CRM entry)

### RLS fix for the enhancement tables (V605, 2026-11-22)
V602–V604 created their tables with a strict RLS policy (`"organizationId" = app.org_id`). Under the
non-owner API role used in CI and on live, that hid the rows from the public, token-authorized lookups. Those lookups
run before the organization is known: website lead forms, customer quote links and calendar feeds, which answered 404.

V605 gives those seven tables the standard V214 policy instead: with no organization set every row is visible, otherwise
only the organization's rows. That policy now applies to both reads (`USING`) and writes (`WITH CHECK`).

The CRM ITs were re-run with `DB_USER` set to a non-owner role, as CI does.

## Sales teams ↔ HR (2026-11-23, post-parity, owner request)
The owner asked for sales teams to be configured in CRM and linked to HR. Owner decision: team leaders and members
must be HR employees with a login.

- **Schema (V620):** `CrmTeam.departmentId` (FK `HrDepartment`) and `CrmTeam.branchId` (FK `Branch`). Both are
  optional and are set to null when the department or branch is deleted. Members and the leader stay user ids in
  `leaderId` / `memberIds`, so every existing reader keeps working: auto-assign `teamOf`, leader visibility, team
  reports, team-leader notifications.
- **Rule:** `POST/PATCH /crm/config/teams` admits a user as leader or member only if they have an `HrEmployee` row in
  the organization that is not deleted, has `isActive`, and is ACTIVE, PROBATION or ON_LEAVE. Otherwise the request
  gets 400 `Sales team members must be active HR employees linked to a user account`. Only newly added users are
  checked. Someone who was on the team before this change (or who left HR since) stays until removed. Once removed,
  they cannot be added back.
- **Unknown department or branch:** 404 `Department not found` / `Branch not found`. An empty string clears the field.
- **Reads:**
  - The teams in `GET /crm/config/lookups` gain `departmentName`, `branchName`, `leader` and `members`. `leader` and
    each entry of `members` carry `{userId, name, employee, eligible}`. They also gain `notEligibleCount`.
  - New route `GET /crm/config/team-candidates` (`crm:configure`) returns `{employees, departments, branches}` for the
    editor.
  - `GET /hr/employees/{id}` gains `salesTeams` (`role` is leader or member).
- **Web:**
  - CRM → Settings → Sales teams picks people from HR employees and can filter them by the team's department. It flags
    members who are not active employees.
  - The HR employee profile (Employment tab) shows a "Sales teams" card.
- **Not changed:** the auto-assign pool stays organization-wide. Team targets and commission rules still have no UI.
  `SalesQuotation.teamId` and `Partner.salesTeamId` still have no writer. These are possible follow-ups.
- **Tests:** CrmTeamsHrIT (candidates, rules, legacy members, employee profile, 403). CrmEditsIT now uses an employee.
  The CRM authorization matrix goes from 137 to 138 routes.
- **Existing data:** run the read-only check below before deploying. It lists team members who are not active
  employees. Nothing is changed automatically.

```sql
SELECT t.name AS team, u.email, CASE WHEN t."leaderId" = u.id THEN 'leader' ELSE 'member' END AS role,
       e."employeeCode", e."employmentStatus"
FROM "CrmTeam" t
CROSS JOIN LATERAL unnest(array_append(t."memberIds", t."leaderId")) AS m(uid)
JOIN "User" u ON u.id = m.uid
LEFT JOIN "HrEmployee" e ON e."organizationId" = t."organizationId" AND e."userId" = u.id AND e."deletedAt" IS NULL
WHERE m.uid IS NOT NULL
  AND (e.id IS NULL OR NOT e."isActive" OR e."employmentStatus"::text NOT IN ('ACTIVE', 'PROBATION', 'ON_LEAVE'))
ORDER BY t.name, u.email;
```

## CRM production-readiness fixes (2026-11-22)

An independent audit found the CRM was not production-ready. The owner approved fixing P0 + P1 before more features,
and an external review added safeguards. This section covers PR A (P0, branch `fix/crm-prod-readiness`, V622; first numbered V606, renumbered when rebased after V621).
Tests: `CrmProdReadinessIT`, plus the cases added to `CrmLeadFormQuoteLinkIT`, `CrmDealExtrasIT` and
`RateLimitInterceptorTest`.

- **P0-1, no organization-wide lock on reads.** `CrmConfigService.ensureDefaults` used to take
  `SELECT … FOR UPDATE` on the org's single `CrmSettings` row on every list, kanban, report, dashboard and lookup.
  - Now an unlocked `EXISTS` check returns at once for a seeded organization.
  - Only first use seeds, under the lock with `ON CONFLICT DO NOTHING`. It runs inline in a write transaction, or
    in its own transaction for read-only callers.
  - `list`, `kanban`, `grouped`, `report` and `dashboard` are `readOnly`.
  - Test: the routes answer while another transaction holds the row lock.
- **P0-2, quote → order never rolls back on CRM visibility.** `QuotationOpportunityPort.markWonIfOpen(dealId,
  number)` calls `CrmPipelineService.wonByQuotation`, which locks the deal inside the organization without the
  salesperson visibility rule.
  - It only marks an open opportunity won and returns nothing.
  - The audit entry names the converter and the quotation (`via: quotation_conversion`).
  - The convert route keeps its own permission, quotation visibility, row lock and idempotency.
- **P0-3, bad numbers are 400.** `CrmDb.dec(v, field)` and `CrmDb.intOf(v, field)` turn non-numbers into 400
  "<field> must be a number conforming to the specified constraints" / "<field> must be an integer number". They
  are used for the filters `priority`/`priorityExact`, deal `amount`/`probability`/`priority`, stage `sequence`,
  settings integers, targets and price rules. Legacy `/crm/deals` DTOs are typed, so Jackson already answered 400.
- **P0-4, the quotation link is a commercial and legal boundary.**
  - `SalesQuoteLink.revision` is set when the link is issued.
  - `revise()` and `cancel()` revoke live links (audited).
  - View, PDF and answer refuse a link whose revision is no longer current: 404 "This quotation is being updated;
    ask us for a new link".
  - Accept and decline lock the quotation, then the link (same order as revise), and store `decidedRevision`, an
    immutable `decisionSnapshot` (header + lines as answered), `signerIp` (proxy-resolved) and `signerUserAgent`.
  - A repeated identical answer is an idempotent 200. A different answer is 409.
- **P0-5, public endpoint abuse.**
  - `server.forward-headers-strategy=native` by default: only trusted proxies' `X-Forwarded-For` counts.
  - `@Throttle(perPathVariable)` keys anonymous counters per client address and form key or token. This applies
    to the lead form, quote link and ICS feed.
  - Lead forms add a signed submit token: HMAC-SHA256 with a key derived from the server secret, bound to the
    form, valid 24 h, minimum 2 s fill time.
  - Lead forms have a rolling 24-hour cap per form (`CrmLeadForm.dailyLimit`, default 200, editable) and per
    organization (`APP_CRM_WEB_LEAD_ORG_DAILY_LIMIT`, default 1000). Over the cap the answer is 429 and nothing is
    stored.
  - The honeypot and the origin allow-list are unchanged.
- **P0-6, email outbox.** `CrmEmailService.send` writes the email activity (outcome `Sending`) and a
  `CrmEmailOutbox` row in the request transaction.
  - After commit `CrmEmailOutbox.deliver` makes one immediate attempt, and the answer reports the real status.
  - The job `crm-email-outbox` (30 s, ShedLock) retries transport errors at 1 min, 5 min, 30 min and 2 h. After the
    fifth failure the row is `failed`, the activity outcome becomes "Not sent: …" and the sender gets an in-app
    notification (`crm.email.failed`).
  - Without SMTP the row fails at once with "Outgoing email is not set up on this server (SMTP)".
  - Rows survive restarts. Delivery is at-least-once.

### PR B: P1 (branch `fix/crm-p1`, V623; first numbered V607)

Tests: `CrmP1IT`. `CrmEnhancementsIT.overdueDigest` clears its own digest marker so it can rerun.

- **Optimistic concurrency.**
  - The V623 trigger `Deal_bump_version` raises `Deal.version` on every real change of the row, whoever writes it
    (suite, legacy `/crm/deals`, merges, jobs).
  - Card re-ordering (`sequenceInStage`) and a bare `updatedAt` touch are ignored, so logging an activity never
    makes an open edit dialog stale.
  - `update` and `assign` lock the row and compare `version`. The web edit dialog sends it; one-click star ratings
    don't.
- **Deal merge leaves nothing behind.** `SalesOrder`, `Project` and `CrmEmailOutbox` rows follow the kept deal, next
  to activities, quotations, lines and contacts. Stage history stays with the archived source, which describes its own
  journey. Legacy `CrmQuotation` rows are write-blocked since V24 and stay on the archived, still readable, source.
- **Customer merge.**
  - Only open quotations move: draft, pending approval, approved, sent, accepted.
  - The route needs `crm:configure` and `partner:delete`. The web hides the button without both.
- **Overdue digest once per day.** `CrmDigestLog (organizationId, userId, day)` is inserted `ON CONFLICT DO NOTHING`
  in the same transaction as the notification, with the business-zone day. The ShedLock lock adds
  `lockAtLeastFor = PT5M`, so node clock skew cannot run it twice.
- **Sales orders in CRM.** Customer 360 lists B2B `salesOrders`, next to counter `orders`. The opportunity page lists
  the sales orders that came out of the deal, and its quotations carry `convertedType/Id/Number`.
- **Finance gating.** Owner decision: see the deviation above. The web hides the receivables and credit tiles and the
  invoices tab, and shows "—" for receivables in the list.
- **Bulk selection.** The pipeline list and the leads list clear the selection when filters or page change, so a bulk
  action only touches rows on screen.
- **Quote expiry on the business date.** `expireOverdue`/`expireAndNotify` compare `validUntil` with the business
  date (`today()`), not the database's UTC `CURRENT_DATE`.
- **Idempotent conversion.** The key is required on both convert routes. The web keeps one key per quotation and
  choice, so a retry after a lost response replays the first conversion instead of failing on "already converted".
- **Pipeline guards.**
  - A lost lead cannot be converted. The conversion stage must be active and not won.
  - Dragging a won deal onto an open stage is a reopen: it is audited (`via: move`) and publishes
    `crm.deal.reopened`, like `POST …/reopen`, which now publishes the event too.
- **Indexes.** `SalesQuoteLink (organizationId, quotationId)`, `CrmDealLine (organizationId)`,
  `CrmDealLine (productId)`, `CrmDealContact (organizationId)`, `CrmDealContact (contactId)`, `Deal (competitorId)`,
  and `CrmLeadForm (sourceId)` / `(teamId)`.
  - `CrmLeadForm.ownerId` gets a foreign key to `User`, `NOT VALID`: existing rows are not re-checked.
- **Not changed (P2, needs owner approval):** legacy `/crm/activities` PATCH/DELETE keep the Nest rule (no
  own-activities check).
