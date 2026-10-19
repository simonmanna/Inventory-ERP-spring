# Phase P6 — Sync + Backup

---

Prerequisite: P5 merged. Read `AGENTS.md`, `docs/CONTRACT.md` §10, `docs/legacy/sync-protocol.md`,
`docs/legacy/backup-recovery.md`, `docs/spec/sync.md`, `docs/spec/backup.md` and their generated
tables. Nest source: `reference/nest-api/src/modules/sync`, `.../backup`.

## Sync deliverables
1. Device registration (token returned once, sha256 at rest), list, revoke; device auth filter.
2. `GET /sync/pull`: cursor codec, per-scope watermarks (server clock, −5 s overlap), every scope in
   `docs/spec/sync.md`, tombstones, aggregates (menuItems), full sets (paymentMethods, ledgerAccounts
   with roles/balances, expenseCategories, stockLocations).
3. `POST /sync/push`: batch idempotency (`Idempotency-Key = batchId`), per-op `executeWithKey(opId)`,
   strict `deviceSeq`, every `SYNC_OP_TYPES` handler delegating to the same services as online routes,
   client-id mapping, 424 on failed dependency, provisional → final numbers, dead letters,
   `lastPushSeq`; dead letters block cash-session close.
4. Dead-letter endpoints. Port the 3 sync specs; build a protocol test suite that replays recorded
   Android batches.

## Backup deliverables
1. `pg_dump`/`pg_restore`/`psql` runner (ProcessBuilder, PG_BIN, timeouts, no shell).
2. Full, incremental (WAL status), files, config backups; checksum; AES-256-CBC/PBKDF2-600k
   openssl-compatible encryption (test: decrypt a Nest-produced backup); secondary copy; rclone
   offsite; retention cleanup; disk check; healthchecks ping; email.
3. Restore, restore verify, restore test into scratch DB; settings + dynamic schedule (ShedLock);
   backup health advisory in `/health/ready`. Port the backup specs (3 + test/backup 2).

## Exit criteria
- Android app (legacy repo `apps/android`) syncs against Spring: pull, offline sales, push, dead
  letter resolution.
- DR drill: encrypted backup → restore-test → app boots on restored DB → trial balance equal.
- Parity 0; PORT_STATUS updated.
