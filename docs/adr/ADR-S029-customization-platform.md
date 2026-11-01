# ADR-S029 — Customization platform (custom fields & objects, print templates, automation rules, public API clients)

Status: **accepted** (owner instruction "build PR-8, PR-11, PR-13", 2026-11-01; roadmap 2.5, PR-11). The roadmap asks
for an ADR before code; this is it. Implemented in `V221`, the new module `com.inventoryerp.customization`
(`CustomFieldService`, `CustomObjectService`, `PrintTemplateService` + `TemplateEngine` + `PrintContextProviders`,
`AutomationRuleService` + `AutomationEngine`), `kernel.apiclients` (`ApiClientService`, `OAuthTokenController`) with
small hooks in `AuthenticationFilter`, `AccessGuardInterceptor` and `JwtTokenService`, and the web pages
`/settings/custom-fields`, `/custom-objects/:key`, `/settings/print-templates`, `/settings/automation`,
`/settings/api-clients`.

## Context

Customers ask for the same four things in every ERP rollout: "add a field", "track a list we invented", "make the
invoice look like ours", "when X happens, do Y" — plus an API for their own integrations. Today:

- a handful of tables carry an opaque `customFields` JSON column (Partner, PurchaseOrder, Deal, …) with no definition,
  validation or UI;
- the DMS `TemplateDefinition` is Nest-parity, install-wide (no tenant) and keyed to DMS document types — it cannot be
  a per-customer designer without breaking parity;
- automation exists only as hard-coded subscribers, task auto-rules and approval workflows;
- there is no machine-to-machine authentication: integrations log in as a human user.

Constraints: the frozen wire contract (ADR-S001: nothing existing changes), tenancy + RLS on every table, no new
frameworks outside the pinned stack (the template engine and the OAuth endpoint are written here, not imported),
money routes idempotent.

## Decision

### 1. Custom fields

- `CustomFieldDefinition` per organization: `entity`, `key` (`^[a-z][a-zA-Z0-9_]{0,39}$`), `label`, `type`
  (`text | textarea | number | decimal | date | boolean | select | multiselect`), `options` (select values),
  `required`, `defaultValue`, `helpText`, `sortOrder`, `showInList`, `isActive`. Unique (`entity`, `key`).
- **Entities** (registry in code, each with its table and an optional document-type filter): `partner`, `product`,
  `sales_invoice`, `credit_note`, `vendor_bill`, `purchase_order`, `sales_order`, `sales_quotation`, `deal`, `asset`,
  `task`, plus every custom object (`x_<key>`).
- Values live in a side table `CustomFieldValue` (`entity`, `recordId`, `values` JSONB, unique per record) — never in
  the core tables, so no existing payload changes. The legacy `customFields` columns are left alone.
- `PUT /custom-fields/values/:entity/:recordId` validates against the active definitions: unknown keys refused, type
  coercion (`decimal` kept as a string with `BigDecimal` checks, `date` as `YYYY-MM-DD`), `select` membership,
  `required` on the merged result. The record must exist in the caller's organization.
- A definition with values cannot change type; deleting a definition deactivates it (values are kept, hidden).

### 2. Custom objects

- `CustomObjectDefinition`: `key` (`^[a-z][a-z0-9_]{1,39}$`, entity name `x_<key>`), `label`, `pluralLabel`, `icon`,
  `displayField` (the field shown as the record title), `isActive`. Its fields are custom-field definitions with
  `entity = x_<key>`.
- `CustomObjectRecord`: `objectId`, `values` JSONB (validated exactly like §1), `title` (copied from the display field
  for search and sorting), audit columns. Routes list (search on title, filter on any field `?f.<key>=value`, paging),
  get, create, patch, delete (soft).
- Records may reference core records through a field of type `reference` (`options.entity` = registry entity); the
  reference is checked on write.

### 3. Print templates (designer)

- `PrintTemplate` per organization: `documentKind` (`sales_invoice | credit_note | vendor_bill | purchase_order |
  sales_order | sales_quotation`), `name`, `content` (HTML), `css`, `paperSize` (`A4 | A5 | Letter`), `orientation`,
  `isDefault` (one per kind), `isActive`, `version` (incremented on every content change; the previous content is kept
  in `PrintTemplateVersion`).
- **Engine**: a logic-less Mustache subset written here (`TemplateEngine`): `{{a.b}}` (HTML-escaped), `{{{raw}}}`,
  sections `{{#lines}}…{{/lines}}` over lists and truthy values, inverted `{{^x}}…{{/x}}`, `{{.}}`. No code execution,
  no file / network access, no partials; output capped at 2 MB and nesting at 10 levels.
- **Context** per kind (`PrintContextProvider`): `company` (organization name, code, currency, tax number, address
  from settings), `document` (number, dates, status, reference, currency, totals pre-formatted and raw), `partner`,
  `lines[]` (description, quantity, unit, unit price, discount, tax, totals), `custom` (the record's custom-field
  values) and `printedAt`. `GET /print-templates/fields/:kind` lists every variable for the designer's field palette.
- `POST /print-templates/preview` renders unsaved content against a real record or built-in sample data;
  `GET /print-templates/render/:kind/:recordId?templateId&format=html|pdf` renders the default (or given) template.
  PDF uses OpenPDF's HTML worker (tables, headings, bold/italics, simple CSS); the HTML output is what the browser
  prints.
- Templates are additive: existing print and PDF routes keep their current output.

### 4. Automation rules

- `AutomationRule`: `name`, `trigger` (one of a catalogue of domain events, each bound to an entity and the payload
  field holding the record id), `conditions` (all must hold: `{field, op, value}` with ops `eq | ne | gt | gte | lt |
  lte | contains | in | empty | not_empty`; `field` addresses the print context — `document.totalAmount`,
  `partner.name`, `custom.priority`), `actions` (in order), `isActive`.
- **Actions**: `notify` (in-app notification to the users of a role or to listed users), `email` (to a fixed address
  or `partner.email`, subject and body are templates of §3's engine), `webhook` (POST JSON to an HTTPS URL, through
  the existing `WebhookUrlGuard` SSRF checks, 5 s timeout), `set_custom_field` (write one custom-field value of the
  triggering record), `create_task` (a task through the task module).
- **Engine**: subscribes to each catalogued event (outbox, after commit, at-least-once). One `AutomationRun` row per
  (rule, event id) — unique, so a redelivered event never runs a rule twice. Each action's outcome is logged; a failing
  action does not stop the next one. Rules run as the organization with no user. `set_custom_field` and the other
  actions publish no catalogued event, so a rule cannot trigger itself. At most 50 active rules per organization.
- `POST /automation-rules/:id/test` evaluates the conditions against a chosen record and returns what *would* run,
  without running it.

### 5. Public API: OAuth 2.0 client credentials

- `ApiClient` per organization: `name`, `clientId` (`ierp_…`, public), secret (`ierps_…`, 32 random bytes, shown
  once; stored as SHA-256), `scopes` (a subset of permission keys), `isActive`, `expiresAt`, `lastUsedAt`,
  `revokedAt`. Rotating the secret invalidates the old one at once.
- `POST /oauth/token` (public, RFC 6749 §4.4): `grant_type=client_credentials`, `client_id`, `client_secret`, optional
  `scope` (must be a subset; default all). Form-encoded or JSON; HTTP Basic client authentication accepted. Answers
  `{access_token, token_type: "Bearer", expires_in: 3600, scope}`; errors use the RFC shape
  `{error: invalid_request | invalid_client | invalid_scope | unsupported_grant_type, error_description}`. Throttled.
- The token is a JWT signed with the access secret and `type: client` — so it can never pass for a user token
  (`verifyAccess` refuses it) — carrying the client row id and the granted scopes.
- **Request rules**: the filter builds a client principal (no user id). The guard grants exactly the token's scopes,
  re-checked against the live client row (cached ≤ 60 s; revoking evicts the cache), and **refuses every route that
  declares no permission** ("API clients can only call permission-protected routes") — sign-in, profile, switching
  and other user routes are unreachable with a client token. Actions are audited with the client id.
- `GET|POST /api-clients`, `PATCH /api-clients/:id`, `POST /api-clients/:id/rotate-secret`,
  `DELETE /api-clients/:id` (revoke), `GET /api-clients/scopes`. Permission `api_client:manage` (+ `api_client:read`).
  A client may only be given scopes its creator holds.

## Permissions

`customization:read|manage` (field and object definitions), `custom_record:read|write` (custom-object records and
custom-field values), `print_template:read|manage`, `automation:read|manage`, `api_client:read|manage`.
Administrator holds them all (startup permission sync); no other role changes.

## Consequences

- Additive only: new tables (all tenant tables with the V214 RLS policy), new routes, no existing payload changes.
- Custom-field values are not yet shown inside the existing list screens' columns or filters; the web adds a custom
  fields panel to the partner, product and invoice detail pages and a generic custom-object screen.
- The template engine is deliberately small; anything beyond Mustache (formatting helpers, loops with indexes) is
  pre-computed in the context.
- Known limits: no visual drag-and-drop layout (the designer is a code editor with a field palette and live preview),
  no scheduled (time-based) automation triggers, no OAuth authorization-code flow (machine clients only), no per-client
  rate limits beyond the global throttle.
