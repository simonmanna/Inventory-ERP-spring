# IPR recovery rehearsal scripts (R7)

Inventory Production Readiness, requirement R7: recovery and migration safety. The scripts are written in Phase 0
and rehearsed in Phase 5 (gate G5) on a **restored copy of the latest live backup**. None of them connects to the
live database, the live API or the live files.

| Script | What it does | Writes to |
|---|---|---|
| `restore-copy.sh <dump> <port>` | Restores a `.dump` (or `.dump.enc` with `BACKUP_ENCRYPTION_PASSWORD`) into a new throwaway `postgres:16` container on `127.0.0.1:<port>`. Checks the `.sha256` sidecar. Uses `pg_restore --no-owner --no-privileges --exit-on-error` through stdin. Prints the table count, the Flyway head and the exact row count of every table. Refuses a port that is in use. | the new container only |
| `run-data-checks.sh <port> <sql-file>` | Runs a SELECT-only file (for example `docs/audits/2026-11-27-inventory-data-checks.sql`) in one `BEGIN READ ONLY` transaction, with `default_transaction_read_only = on` and `ON_ERROR_STOP`. Refuses files with write statements and refuses ports 5432 and 5434 unless `ALLOW_PORT=yes` (live needs the owner's OK each time). | `var/ipr-data-checks/<file>-p<port>-<ts>.txt` |
| `rehearse-migration.sh <dump>` | `CANDIDATE_JAR` and `PREVIOUS_JAR` on a copy. Steps: restore, pre-upgrade dump of the copy, static additivity scan of the new migrations, candidate `--migrate-only`, candidate API ready, previous jar on the migrated schema, `rollback.sh --restore-db` semantics on the copy, previous jar on the restored copy. | a throwaway container, `var/ipr-rehearsal/<ts>/` |
| `rehearse-failed-job.md` | Step-by-step recovery of a failed `StockPostingJob`: a forced failing line, an API killed mid-drain, retry and abandon from the Posting Monitor, and the exactly-once checks through the ledger and the integrity report. The automated proof is `PosStockPostingRecoveryIT`. | the copy only |

They reuse the existing tools:

- `scripts/rollback.sh --restore-db` is the live rollback (dropdb, createdb, pg_restore). `rehearse-migration.sh`
  step 7 runs the same commands against the copy's container instead of the compose `db`.
- `scripts/dr-drill.mjs` (OPERATIONS §7a) proves backup → restore → second API → identical trial balance through
  the API on a compose stack. Run it as well in Phase 5. These scripts add the native-layout path (jars, systemd
  host) and the migration and rollback rehearsal.
- The backup module's restore test (`POST /admin/backups/restore/test`) restores into a scratch database of the live
  cluster. These scripts restore into a separate container, so nothing heavy runs inside the live cluster.

## Phase 5 order

```bash
# 1. backup restore (any recent full backup; read it only)
scripts/ipr/restore-copy.sh /opt/inventory-erp/backups/<latest>.dump 5711 | tee var/ipr-restore.txt
# 2. data checks on that copy (per org results are in the SQL itself)
scripts/ipr/run-data-checks.sh 5711 docs/audits/2026-11-27-inventory-data-checks.sql
docker rm -f ierp-ipr-restore-5711
# 3. migration apply + rollback (makes its own copy)
CANDIDATE_JAR=/opt/inventory-erp/dist/inventory-erp.jar PREVIOUS_JAR=/opt/inventory-erp/app.jar \
  ENV_FILE=/etc/inventory-erp/api.env ADMIN_EMAIL=… ADMIN_PASSWORD=… \
  scripts/ipr/rehearse-migration.sh /opt/inventory-erp/backups/<latest>.dump
# 4. failed-job recovery: follow scripts/ipr/rehearse-failed-job.md on a fresh copy
# 5. DR drill through the API: node scripts/dr-drill.mjs (OPERATIONS §7a)
```

## Notes

- Use spare ports. 5432 and 5434 are database ports on this host, and 3001 is the live API. The defaults
  are 5711 (copy) and 3911 (rehearsal API).
- A copy holds business data. Remove it when done (`docker rm -f <container>`). Keep the evidence files, not the
  copy.
- Startup of a jar takes about 100 s on the shared host. Each API start in `rehearse-migration.sh` waits up to
  `READY_TIMEOUT` (400 s).
- `rehearse-migration.sh` overrides the database, storage, backup, mail, SMS and Superset settings of `ENV_FILE` and
  turns every scheduled job off (`APP_JOBS_ENABLED=false`). It runs as the throwaway container's superuser `owner`.
  `afterMigrate` creates the API role `erp_app` there, so RLS applies to the rehearsal API as it does on live.
- Validated in Phase 0 with `bash -n`. `restore-copy.sh` and `run-data-checks.sh` were also run for real against a
  dump of a test database. `rehearse-migration.sh` was checked with `bash -n` only in Phase 0.
- Phase 5 (2026-11-28): both rehearsals ran on restored copies of the live backup and passed
  (`docs/audits/ipr-evidence/5-r7-rehearsals.md`). The first real runs found three defects in
  `rehearse-migration.sh`, all fixed: the heap was too small for a copy of live (`JAVA_HEAP`, default 1536m), a
  stopped API kept running so a later step could pass against the wrong jar (the jar is stopped and the port waited
  for), and the additivity scan flagged `countType` and `NOT NULL DEFAULT` columns.
- `ENV_FILE` is sourced by bash: quote values with spaces (`JAVA_TOOL_OPTIONS="…"`). The Phase 5 runs used an env file
  with the live `ENABLE_*` flags and fresh random secrets, not the live secrets.
