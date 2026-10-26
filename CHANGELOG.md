# Changelog

All notable changes. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow SemVer.
The wire contract (paths, JSON, status codes, error messages) is the NestJS POS-CAFE API — see `docs/CONTRACT.md`.

## [Unreleased] — 1.0.0 release candidate

### Added
- **Per-product units of measure** (ADR-S003, `V5__product_units.sql`): products declare their own pack
  sizes (`units[]`: Box = 24 for drinks, Box = 12 for soap) with buy/sell flags and optional unit prices.
  Purchase orders, goods receipts, vendor bills, sales invoices, credit notes and supplier returns take a
  unit per line; the conversion is frozen on the line (`uomId` + `uomFactor`) so later edits to a product's
  units never change an existing document. Stock stays in the base unit, which is locked once stock has
  moved. Bills match receipts in base units. POS invoice items freeze the sales unit. Web: units table on
  the product page, unit picker on PO / GRN / invoice / bill / credit-note / debit-note lines, units shown
  on PO and invoice details. Pre-V5 lines post exactly as before.
- **Licensing** (ADR-S002, `docs/spec/licensing.md`): daily / monthly / yearly / trial / perpetual
  subscriptions with per-feature activation and caps on POS devices, users and organizations.
  - `license-server/` — a separate Spring Boot service with its own database, the only place a
    signing private key exists. Issues Ed25519-signed licences (compact JWS, rotatable keys served
    as a JWKS), manages customers, plans, licences and activations, mints offline `.lic` files for
    air-gapped installs, sends renewal reminders, and reports what is live, what lapses soon, which
    installs went quiet and which are over their limits.
  - `kernel/licensing` on the API — the installation's licence, **verified offline** against pinned
    public keys, so a shop keeps trading with no network. A lapsed licence gives a grace window and
    then goes read-only: reads succeed, writes answer **402**, and login, `/license/**`, health,
    `/sync/push|pull` and finishing an open order or shift stay available. The licence drives the
    `OrganizationModule` / `FeatureFlag` gating that already existed rather than adding another, so
    effective access is `licensed ∩ enabled`.
  - Web: `Settings → Licence` (state, usage against each cap, activation by key or file, the
    installation code to send a supplier), a banner that stays quiet until it matters, one dialog for
    every 402, and the licence as a ceiling over the sidebar and Developer Settings.
  - Android: the licence travels on `/sync/pull`, is kept on the device so a terminal restarted
    offline still knows where it stands, shows a banner on the till, and blocks new sales when
    read-only — while the offline queue still drains, so a day's takings are never held hostage.

- **Android POS** (`android/`): the offline-first Kotlin terminal migrated from POS-CAFE and retargeted at the
  Spring API (`/sync/pull`, `/sync/push`, `X-Device-Token`; default URL `:8080/api/v1`). Pricing parity is pinned
  on both sides by one golden file — backend `CartVectorParityTest` runs `TaxCalculationService` over
  `android/app/src/test/resources/cart-vectors.json`.
- **HR & payroll** (`ENABLE_HR`, 140 routes): departments, positions, employees with compensation redaction, the
  employee ↔ login identity spine (link / provision / PIN), employment lifecycle (suspend, terminate, rehire,
  transfer) with an immutable ledger, shifts, attendance in the organisation time zone (POS PIN sign-in/off becomes
  clock-in/out), timesheets, leave with balances and attendance stamping, holidays, payroll runs (allowances,
  progressive PAYE, pension/SSF, loan and advance instalments) posted to the GL, payslips, bank payment files,
  documents, training, reviews, self-service and manager views, reports.
- **Back-office orders** (`ENABLE_ORDERS`, 11 routes) over the shared Order aggregate, billed through the POS spine.
- **Offline sync** (8 routes): device registry with one-time tokens, incremental pull (21 scopes, tombstones for
  revoked staff), batched push through the online services with per-op idempotency, client-id mapping, 424 for broken
  dependencies, dead letters that block shift close until a manager resolves them.
- **Backups** (13 routes): pg_dump/pg_restore runner, openssl-compatible AES-256 encryption (reads Nest-written
  backups), checksums, verified secondary copies, cloud CLIs, retention incl. GFS, restore into a new database only,
  scheduled restore tests, ShedLock-guarded schedule, backup health in `/health/ready`.
- Release engineering: CI with Spotless, JaCoCo gate, CycloneDX SBOM, Trivy scans of the SBOM, lockfile and images,
  e2e sell loop against Spring; nightly PIT mutation testing; tag-driven release workflow to GHCR.
- Operations: `scripts/upgrade.sh|.ps1` (pre-upgrade dump, automatic image rollback), `scripts/rollback.sh`,
  `node scripts/dr-drill.mjs`; Prometheus alert rules + Grafana dashboard (`infra/monitoring`); backlog and backup gauges;
  k6 load suite (`tests/load`); `docker-compose.prod.yml` for published images; `.dockerignore`.
- Security checks: JWT algorithm confusion / forged signatures, active-content uploads served as attachments, no
  credentials in logs (`SecurityChecklistIT`).

### Changed
- Patched dependencies over the Spring Boot BOM: Tomcat 11.0.25, Jackson 3.1.7 / 2.21.7, jose4j 0.9.6; the unused
  AsyncHttpClient (via web-push) is excluded.

### Earlier phases (P1–P5)
Kernel, core, accounting, inventory, invoicing, expenses, CRM, tasks, documents, procurement and the full POS
(707 routes) — see `docs/PORT_STATUS.md` and `docs/HANDOVER.md`.
