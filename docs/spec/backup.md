<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
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

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/backup-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
