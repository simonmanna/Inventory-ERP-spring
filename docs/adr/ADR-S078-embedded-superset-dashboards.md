# ADR-S078 — Embedded Apache Superset dashboards, one per module

- Status: accepted by the owner (2026-11-18). B0 (Sales, Procurement, Inventory, Finance) and B1 (POS, HR,
  Manufacturing) are built; B2–B3 follow.
- Related: ADR-S020 (built-in analytics dashboards), docs/integrations.md (BI export, the `reporting` views of V111).
- Extension: no NestJS counterpart. Two new routes under `/bi/dashboards`; nothing existing changes shape.
- Migrations: `V570__bi_superset_views.sql` (B0), `V571__bi_superset_views_pos_hr_manufacturing.sql` (B1). LP work in other worktrees holds V563–V567.
- New runtime component outside the pinned stack (AGENTS.md §3): Apache Superset 6.1.0 in its own container, with
  Redis. It is optional (compose profile `bi`) and the API runs unchanged without it.

## Context

The owner wants a dashboard for every module, embedded in the ERP. The requirements are: free, flexible, supported
over the long term, safe on a shared multi-tenant database as well as on a one-customer stack, and able to use AI (a
local model or the customer's own subscription).

Five engines were compared: Apache Superset, Metabase, Lightdash, JasperReports and Evidence.

| | Why not / why |
|---|---|
| Metabase OSS | Free embedding is static and read-only. Per-tenant row filtering on the server ("sandboxing") is paid. On a shared database, every card's SQL would have to filter by organization by hand |
| Lightdash | Embedding is in the paid tiers, and it needs a dbt project |
| JasperReports | Builds page-exact reports, not dashboards. OpenPDF and the reporting engine already cover printed reports |
| Evidence | Builds static sites, which don't fit per-user, per-organization views inside an app |
| **Superset** | Apache-governed, so no vendor can relicense it. Free embedded SDK with guest tokens. The token carries row-level-security (RLS) clauses that Superset applies to every query. Dashboards can live in the repo |

## Decision

1. **Superset 6.1.0**, pinned (`infra/docker/Dockerfile.superset`: upstream image + `psycopg2-binary`). It has its
   own metadata database `superset` (role `superset_meta`) and Redis.
2. **A `bi` schema (V570)** of views over the operational tables. The views take no `app.org_id`: a pooled BI
   connection can't set it safely, and a session can change its own setting (docs/integrations.md). Every view
   exposes `"organizationId"`. The `reporting` views stay as they are.
3. **Tenant isolation is the guest token.** `SupersetEmbedService` asks Superset for a guest token for one dashboard
   with RLS clause `"organizationId" = '<org>'`. The org comes from `TenantContext` (the signed JWT), never from the
   request, and must match `[A-Za-z0-9_-]{1,64}`. A dataset without that column fails its query, so a mistake fails
   closed.
4. **Least privilege.**
   - `superset_reader` can read only `bi` views (read-only transactions, 60 s statement timeout, 20 connections).
   - The API role `erp` gets nothing on `bi`, because those views aren't filtered by RLS.
   - The API's Superset account (`EmbedIssuer`) can only mint guest tokens.
   - Guest viewers get `EmbedViewer` (Gamma's read permissions).
   - Superset can be framed only by `SUPERSET_FRAME_ANCESTORS`, and each embedded dashboard by its allowed domains.
5. **Permissions reuse ADR-S020:** `analytics:view` for the routes, plus the module's own `analytics:<module>` for
   each dashboard. No new permission keys. Module keys equal the analytics screen paths. In the web app, the Superset
   dashboard is an "Explore" view on `/analytics/<module>`, shown only when the module has a dashboard.
6. **Dashboards as code.** `tools/superset-provision/dashboards/<module>.json` define the charts. `provision.mjs`
   creates or updates the database connection, datasets, charts, layout and embedded config through the REST API
   (idempotent), and prints the dashboard ids for `.env`.
7. **Isolation is tested against the running Superset, not assumed.** `provision.mjs --check-isolation`:
   - fetches a hidden probe chart (organization + row count) per dataset with a guest token for each of two orgs,
   - fails if any foreign organization appears,
   - and runs an unfiltered control token to prove the probe would see a leak.

   It must pass before a Superset version change ships: guest-token RLS stopped being applied in 5.0.0 (upstream
   discussion), and this check is what would catch a regression like that.
8. **AI comes later, on the ERP side (B3, its own ADR).** It will use a provider interface (OpenAI-compatible for local
   Ollama/vLLM, or the customer's own key) over the `bi` views, with the same organization pinning. Keeping it out of
   the BI engine lets either be replaced on its own.

## Consequences

- Every new `bi` view must expose `"organizationId"` (`BiDashboardIT` enforces this). `afterMigrate.sql` re-grants
  `superset_reader` on each migrate, so new views need no grant of their own.
- A `bi` view is a contract like a `reporting` view: a migration that drops or retypes a column it uses recreates it.
- Customers never log into Superset on a shared database. Only the platform operator builds dashboards.
- Superset costs about 1–2 GB of RAM. It is off unless the `bi` profile is started and `SUPERSET_URL` is set.
- The CI image scan does not gate the Superset image; its CVEs belong to upstream. Watch Superset releases instead.

## Verified (2026-11-18)

- Against a live `apache/superset:6.1.0` with the test database:
  - four dashboards were provisioned, and a second run was idempotent;
  - the isolation check passed: `stock_on_hand` 43 rows for one org and 1 for the other, each token seeing only its own,
    and the control seeing both;
  - the `EmbedIssuer` login minted tokens but got 403 on `/api/v1/dashboard/` and `/api/v1/database/`;
  - the embedded page loaded with the ERP origin as referer and returned 403 for another site;
  - CSP `frame-ancestors` was set, with no `X-Frame-Options`.
- B1, same setup: seven dashboards provisioned; all 34 POS, HR and Manufacturing charts ran their queries without
  error; the isolation check passed on `hr_employees` (2 rows for one org, 1 for the other, control seeing both).
  POS and Manufacturing views had no test rows, so isolation there rests on the same clause, not on a row count.
- B1 modules with no built-in analytics screen use their own reports permission (`pos:reports`, `hr:report`,
  `production_order:read`) plus `analytics:view`. HR views hold no pay per employee or personal details.
- `SupersetEmbedServiceTest` (stubbed Superset) and `BiDashboardIT` pass.
- Not yet verified: the dashboards rendered in a browser through the web app.
