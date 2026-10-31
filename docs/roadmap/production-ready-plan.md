# Production-ready plan (owner instruction 2026-10-31)

Build every remaining roadmap item **except** 1.1 localization, 1.2 tax returns, 1.4 bank feeds / payment runs and
3.2–3.7 (IFRS 15/16, scale, GRC, treasury, AI, ecosystem incl. the e-commerce connector); the owner does those later.
Each phase ends with a green full build (`./gradlew build`, `pnpm web:i18n`, `pnpm web:build`), then a commit and a
push to `main`. Release tags (`v*`) publish Docker images and are created only at PR-15.

| Phase | Scope | Size | Status |
|---|---|---|---|
| PR-0 | Green CI: order-dependent test failures, critical permission gap, web i18n gate, web bundle over the precache limit | S | **done 2026-10-31** |
| PR-1 | Release hardening: outbox retry limit / dead letter, RLS with a non-owner role, JaCoCo 80 %, dependency/CVE review, 401/403 matrix run, POS p95, known functional gaps, browser click-through, parity run, Nest retirement | M | **part 1 done 2026-10-31** (see below); part 2 open |
| PR-2 | 1.7 Year-end close | S | **done 2026-10-31** (ADR-S023) |
| PR-3 | 2.4 Analytic dimensions | M | **done 2026-10-31** (ADR-S025) |
| PR-4 | 2.1 Warehouse management | L | todo |
| PR-5 | 2.3 Replenishment & MRP completion | L | todo |
| PR-6 | 2.8 Subscriptions & deferrals | M | todo |
| PR-7 | 2.9 Projects & service billing | M | todo |
| PR-8 | 2.7 Intercompany & multi-company | L | todo |
| PR-9 | 2.10 Portals & payment gateways | M | todo |
| PR-10 | 2.6 BI builder | M | todo |
| PR-11 | 2.5 Customization platform (ADR first) | XL | todo |
| PR-12 | 1.3 E-invoicing (URA EFRIS first, sandbox adapter) | L | todo |
| PR-13 | 3.1 Advanced costing remainder | M | todo |
| PR-14 | 2.2 Barcode / mobile warehouse app | M | todo |
| PR-15 | Release: regression, tags, deploy | S | todo |

## PR-0 — as built

**Order-dependent integration failures (8 on CI run 41207741998).** Two causes, both test isolation:

- `ManufacturingDemoSeedIT` leaves a few hundred outbox rows. Suites that drained with five `tick()`s raced the
  scheduled poller and asserted before their own event shipped. New `ApiTestSupport.drainOutboxFully()` waits until no
  first-attempt row is pending or claimed (60 s cap); seven suites use it.
- `PlanningIT` inserted an 08:00–17:00 shift into the shared `DEMO` organization, so `ShopFloorExecutionIT` never
  created its 24-hour default pattern and found no shift outside those hours. `PlanningIT` now deactivates its shift.

**Critical permission gap (Phase 6 finding).** Deviations from Nest, owner-approved:

| Route | Now requires |
|---|---|
| `PATCH /organizations/me/settings` | `organization:update` |
| `POST /organizations/users/invite` | `user:create`; `roleId` must belong to the organization (400 `Role not found in this organization`) and may only hold permissions the inviter holds (403 `You cannot assign a role with permissions you do not hold`) |
| `PATCH /organizations/users/:id/deactivate` | `user:update` |
| `POST`/`DELETE /feature-flags`, module enable / disable | `setting:update` |
| `/webhooks/endpoints*`, `/webhooks/deliveries` | `webhooks:read` / `webhooks:write` / `webhooks:delete` |
| `/reports/export/trial-balance.csv`, `ar-aging.csv`, `invoice/:id.pdf` | `report:accounting`, `report:ar`, `invoice:read` |

Webhook targets must be `http(s)` and resolve only to public addresses (`WebhookUrlGuard`), checked on create and
before every delivery; a blocked delivery is dead-lettered. `WEBHOOKS_ALLOW_PRIVATE_TARGETS=true` lifts it for
development. Read routes (`GET /organizations/me`, `/organizations/users`, `/feature-flags`, `/me/branch`) stay open.
Tests: `AdminRouteSecurityIT`, `WebhookUrlGuardTest`; `ArchitectureTests` no longer lists `ExportController` and
`WebhooksController` as signed-in-only.

**Web.** `pnpm web:i18n`: 318 phrases translated into ti / lg / sw, 66 stale phrases pruned (no existing translation
changed). `pnpm web:build` failed after that gate (CI never reached it): the main chunk was 6.79 MB, over the 6 MB
precache limit. Every page is now a `React.lazy` chunk (codemod over `App.tsx` and the 14 `pages/**/routes.tsx`),
with `PageSuspense` boundaries in the app, staff, driver and portal shells; the main chunk is 2.79 MB and all 487
chunks stay precached, so the offline POS still works.

**Result:** backend 1,379 tests, 0 failed (JaCoCo, Modulith, ArchUnit gates pass); i18n check passes; web build passes.

**Finding for PR-1:** `OutboxWorker` keeps a failing row `pending` forever and always picks the oldest rows first, so
50 permanently failing rows stall every subscriber (same as Nest).

## PR-1 part 1 — as built

- **Outbox retry limit** (`V213`): a failed `EventOutbox` row waits `nextAttemptAt` (5 s doubling to 15 min) and turns
  `dead` after `OUTBOX_MAX_ATTEMPTS` (10); it no longer holds back newer rows. Gauges `erp.outbox.pending` /
  `erp.outbox.dead`, alerts `EventOutboxDeadRows` / `EventOutboxBacklog`, routes `GET /admin/outbox/dead`,
  `POST /admin/outbox/{id}/retry`, `POST /admin/outbox/dead/retry` (organization-scoped, audited).
  Tests: `OutboxDeadLetterIT`, `OutboxBackoffTest`.
- **Row-level security enforced** (`V214`, `db/callback/afterMigrate.sql`): every table with `organizationId` (≈ 520)
  plus `Organization` has the tenant policy; work without a tenant is not restricted. Flyway and backups run as the
  owner (`FLYWAY_USER`), the API as a non-owner role created by the callback (compose: `erp_app`). The whole IT suite
  now runs as a non-owner role. That run found and fixed three real defects: BI views not readable by the app role,
  licence seat caps counting one tenant instead of the installation (`InstallWide` helper; also licence module
  reconciliation), and per-org document sequences needing ownership (`setval` instead of `ALTER SEQUENCE`).
  Tests: `TenantRowSecurityIT` (role restricted, every tenant table covered, cross-tenant read / insert / update refused).
- **Dependency scan**: `follow-redirects` ≥ 1.16.2 and `image-size` ≥ 2.0.3 (pnpm overrides; `image-size` is not in
  the browser build). Trivy HIGH findings: 0.
- **Whole-app 401/403 matrix**: `AuthorizationMatrixIT` checks every route (> 2,000) in one run.
- **Functional gaps**: replenishment (INV-3) now counts open purchase orders and requests for the location
  (`IncomingSupplyProvider`, implemented by procurement; `V215` `incoming` on the alert). Commission clawback on credit
  notes was already done by the S9 engine (the gap was in the removed SALES-4). Portal write actions move to PR-9.

## PR-1 part 2 — open

| Item | Why it is not done yet |
|---|---|
| JaCoCo 80 % per `application` package | measured per package after the build; needs targeted tests package by package |
| POS checkout p95 < 150 ms | needs profiling of the ~400 statements per checkout on a production-like box |
| Browser click-through of every screen | needs the full stack running with Playwright |
| Nest parity run, then Nest retirement | needs a live Nest + Postgres next to Spring |

## PR-2 — as built (ADR-S023)

`V216`: `FiscalYear`, `FiscalPeriod.fiscalYearId`, `FiscalYearSignOff`, `FiscalYearBalance`. Routes (`fiscal_period:*`):
`GET|POST /fiscal-years`, `GET /fiscal-years/:id`, `GET /fiscal-years/:id/close-checklist`,
`PUT /fiscal-years/:id/close-checklist/:key`, `GET /fiscal-years/:id/closing-balances`, `POST /fiscal-years/:id/close`,
`POST /fiscal-years/:id/reopen`. Web: `/fiscal-years`, `/fiscal-years/:id`. Tests: `FiscalYearIT`,
`FiscalYearPeriodsTest`.

## PR-3 — as built (ADR-S025)

`V217`: `AnalyticDimension`, `AnalyticDimensionValue`, `AccountDimensionRule` opened to any dimension code,
`DocumentLine.dimensions`, GIN index on `JournalLine.dimensions`. Posting validates defined dimensions (active value,
validity dates) and account rules (`required` / `forbidden`; closing journals exempt). Cost centre addressed as the
reserved dimension `costCenter`. Invoice / credit note / vendor bill lines carry dimensions to their revenue / expense
GL lines (split per value). Routes (`analytic_dimension:read|manage`, reports `report:accounting`):
`GET|POST /analytic-dimensions`, `GET|PATCH|DELETE /analytic-dimensions/:id`, `GET|POST /analytic-dimensions/:id/values`,
`PATCH|DELETE /analytic-dimensions/:id/values/:valueId`, `GET /analytic-dimensions/account-rules`,
`PUT /analytic-dimensions/account-rules/:accountId`, `GET /analytic-dimensions/reports/summary|trial-balance|lines`,
`GET|PUT /analytic-dimensions/documents/:documentId/lines`. Web: `/analytic-dimensions` (dimensions, values, account
rules, P&L by value). Tests: `AnalyticDimensionIT`.
