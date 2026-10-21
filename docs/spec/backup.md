# Backup — `backup`

| | |
|---|---|
| Phase | P6 |
| Nest source | `reference/nest-api/src/modules/backup` |
| Routes | 13 — full table: [generated/backup.md](generated/backup.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.backup` |

## Purpose

Full/incremental/files/config backups, encryption, checksums, secondary + offsite copies, retention, restore, restore verify, restore test into a scratch DB, health status, schedules, notifications.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| admin | `/admin/backups`: status, health, list, settings (GET/PUT), full, incremental, files, config, cleanup, restore, restore/verify, restore/test |

## Business rules to reproduce exactly

1. Shell out to `pg_dump`/`psql`/`pg_restore` via `ProcessBuilder` (PG_BIN), never through a shell; stream output, timeouts, exit-code checks.
2. Encryption AES-256-CBC with PBKDF2-SHA256 600,000 iterations, `Salted__` header — byte-compatible with `openssl enc -aes-256-cbc -pbkdf2 -iter 600000` (port exactly; existing backups must decrypt).
3. Verify: archive entry count + sha256; restore test into scratch DB and compare row counts.
4. Retention cleanup, disk-space check, healthchecks.io ping, email on failure, dynamic cron from settings.
5. Backup health feeds `/health/ready` advisory checks.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

—

## Scheduled jobs

dynamic backup schedule.

## Notes and pitfalls

- Recent hardening (Oct 2026) in the Nest module — port the latest code in `reference/`, not older docs.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/backup-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [x] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Deviations (pending owner approval)

1. Restore checks count applied migrations from `flyway_schema_history` (successful rows) instead of
   `_prisma_migrations` — the Spring schema is managed by Flyway; the table does not exist.
2. The database to back up comes from the Spring datasource (`JDBC_DATABASE_URL` / `DB_USER` / `DB_PASSWORD`, or the
   Testcontainers connection) instead of `DATABASE_URL`; `PGUSER` / `PGPASSWORD` still override the role.
3. Config backup looks for `.env` in the working directory, `backend/.env`, and `../.env` when started from `backend/`
   (Nest: `apps/api/.env`).

## Notes

- `PG_BIN` must point at client tools of the **same major version as the server**: `pg_restore` 17+ emits
  `SET transaction_timeout`, which a 16 server rejects (Nest has the same limitation). The API image installs
  `postgresql16-client`.
- Cron expressions from the settings page are 5-field (node-cron); they are run through Spring `CronExpression` with a
  leading `0` seconds field. Scheduled runs take a ShedLock lock (`backup:<job>`) so only one instance backs up.

## Status

13/13 routes. `BackupController` + `BackupService`, `BackupCrypto`, `BackupHealthCheck`
(`com.inventoryerp.backup.application`). `/health/ready` now carries `advisory.backup` through the kernel
`AdvisoryHealthCheck` SPI (status `degraded` when backups need attention, never 503).
Tests: `BackupServiceTest` (9 — `backup.service.spec.ts`: defaults, merge + persist, validation, secret masking,
invalid persisted schedule, connection resolution, retention incl. smart/GFS, encryption incl. decrypting a file
written by the Nest `backup-crypto.ts`), `BackupRestoreIT` (6 — `backup-restore.spec.ts`: real `pg_dump` of the
Testcontainers DB, encryption, sidecar, verified offsite copy, stock `openssl` decrypt, verify by name, tamper
detection, restore test into a scratch DB, live-DB and path-traversal guards, HTTP surface + 13-route authz matrix).
The restore-test case runs only when the local `pg_restore` major equals the server major (see Notes).
