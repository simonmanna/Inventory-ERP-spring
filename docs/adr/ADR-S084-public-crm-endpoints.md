# ADR-S084 — Public CRM endpoints (calendar feed, website lead form, online quotation link)

- Status: accepted (owner-approved 2026-11-27; proposed 2026-11-22). Built in the CRM enhancement plan the owner approved on 2026-11-22: T2b (calendar
  feed, PR #28) and T3a (lead form, quotation link). The owner reviews it when merging.
- Extension: no NestJS counterpart. These routes are new; no existing route or response changed.
- Migrations: `V602__crm_stage_rules_calendar_feed.sql` (`CrmCalendarFeed`) and
  `V603__crm_lead_forms_quote_links.sql` (`CrmLeadForm`, `SalesQuoteLink`).
- Related:
  - ADR-S011 S10 (customer portal, licence `customerPortal`)
  - The DMS share links (`/public/dms/share/{token}`), which follow the same token pattern.

## Context

Three CRM features have to serve someone who cannot sign in:
- a calendar app reading a salesperson's activities;
- a visitor on the company's website sending an enquiry;
- a customer answering one quotation.

The codebase already has two models for this:
- The customer portal: a magic-link token per customer, covering the whole account (quotes, orders, invoices,
  payments), behind the `customerPortal` licence.
- DMS share links: a token per shared folder.

## Decision

All public routes live under `/api/v1/public/...`, are marked `@Public` with a route-level `@Throttle` (per client
address), and resolve their organization from a credential in the URL. They never use a session, a cookie or a bearer
token.

| Route | Credential | Limit | Scope reached |
|---|---|---|---|
| `GET /public/crm/calendar/{token}.ics` | secret token, SHA-256 stored | 30/min | that user's activities, 30 days back to 180 ahead |
| `GET /public/crm/forms/{key}` | public form key | 60/min | form name, company name, thank-you text |
| `POST /public/crm/forms/{key}/submit` | public form key | 10/min | creates one lead |
| `GET /public/sales/quotes/{token}` (+ `/pdf`) | secret token, SHA-256 stored | 60/min, 20/min | that one quotation |
| `POST /public/sales/quotes/{token}/accept`, `/decline` | same | 10/min | that one quotation |

**Secret tokens** (calendar, quotation):
- 32 random bytes, URL-safe Base64. Only the SHA-256 is stored, and the token is shown once.
- One live link per user or per quotation; creating a new one revokes the old.
- Quotation links expire after 60 days. Unknown, revoked and expired tokens all answer the same 404, so the response
  never reveals whether a token once existed.
- The code switches to the organization before opening the transaction (`TenantContext.run` → `TransactionTemplate`),
  so the RLS `app.org_id` is set. Permissions are granted narrowly for the call only:
  - quotation links: read any owner's or branch's quotation;
  - lead forms: read back the lead just created.

**Website form key** (`lf_` + 24 characters):
- Not a secret, since it sits in the website's HTML. Abuse is limited by:
  - the per-address limit (10 a minute);
  - a honeypot field `_hp` (a filled one is answered as success and dropped);
  - field length and format checks;
  - an optional origin allow-list (checked when the browser sends `Origin`; the hosted page on the app's own host is
    always allowed);
  - switching the form off, or rotating the key.
- The response never discloses the lead id.
- CORS: `SecurityConfig` serves an open configuration only for `/public/crm/forms/{key}` and
  `/public/crm/forms/{key}/submit`: any origin, no credentials, `Content-Type` header only. Every other route keeps the
  allow-list.

**Quotation link and the customer portal:**
- The link reuses the portal's rules:
  - the same `SalesQuotationService.accept` / `decline`;
  - the same field stripping (no internal notes, owner, credit, stock or cost).
- It sits behind the same `customerPortal` licence feature, on both the staff routes and the public routes, so it is
  not a free way around a paid feature.
- A customer sees the quotation only while it is approved, sent, accepted, rejected, expired or converted; a draft or
  pending revision answers 404 "This quotation is being updated; ask us for a new link".
- Accept requires a typed name of 2–120 characters. The signer name, note, client address and time are stored on the
  link as evidence.
- A link can be answered once (409 "This quotation has already been answered").
- The quote owner gets an in-app notification on first view and on the answer. The CRM timeline gets a done entry
  through `QuotationOpportunityPort.quotationAnsweredOnline`, so sales still never depends on crm.

## Consequences

- Rate limits are per address and per application instance (in memory, Bucket4j). A distributed attacker can exceed
  them. The honeypot and the form switch are the fallback, and a CAPTCHA can be added later behind the same route.
- **Open point for the owner: client addresses behind the proxy.** The app does not set
  `server.forward-headers-strategy`, so behind nginx `request.getRemoteAddr()` is the proxy's address.
  - Every anonymous caller then shares one throttle bucket. This already applies to login, the portal and DMS share
    links. The lead-form limit is set to 10 a minute for that reason.
  - The signer address recorded on a quotation answer is the proxy's address.
  - Fix: set `server.forward-headers-strategy: native` with nginx sending `X-Forwarded-For`. That is a kernel-wide
    change (it also changes login throttling), so it is left for a separate, owner-approved change.
- A forwarded or leaked quotation link lets anyone holding it accept on the customer's behalf. That is the same risk
  as an emailed PDF with "sign and return". The typed name and recorded address are evidence, not identity proof.
  Staff can turn a link off at any time.
- Tests: `CrmStageRulesCalendarIT` (feed) and `CrmLeadFormQuoteLinkIT` cover:
  - expiry, revocation and replacement;
  - the honeypot, the origin allow-list and CORS;
  - which fields a customer can see;
  - answering a link only once;
  - tenant isolation.
