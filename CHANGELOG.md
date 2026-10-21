# Changelog

All notable changes. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow SemVer.
The wire contract (paths, JSON, status codes, error messages) is the NestJS POS-CAFE API — see `docs/CONTRACT.md`.

## [Unreleased] — 1.0.0 release candidate

### Added
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
