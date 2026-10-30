# Operations

## 1. Deployment topologies

| Topology | Stack |
|---|---|
| Single café (LAN server / mini-PC) | `docker compose up -d --build` → Postgres 16 + API + nginx web on :8080; terminals use the server's LAN IP (`CORS_ALLOW_LAN=true`) |
| Hosted (VPS) | same images behind Caddy (TLS, zstd/gzip) — see `docs/legacy/DEPLOYMENT.md` and `deployment/demo` in the legacy repo |
| Cloud read replica | logical replication of the LAN DB + API with `READ_ONLY_MODE=true` (`docs/legacy/architecture`) |

Minimum server: 2 vCPU, 4 GB RAM, SSD. Recommended: 4 vCPU, 8 GB. JVM heap = 75 % of container RAM.

## 2. Configuration

All configuration is environment variables (`.env.example` documents every key). Profiles:
`dev` (default in compose), `prod` (JSON logs, strict secrets check, no error detail), `seed`.
Same variable names as POS-CAFE, plus `JDBC_DATABASE_URL`, `DB_USER`, `DB_PASSWORD`,
`APP_ENCRYPTION_KEY`, S3/SMTP/Twilio/VAPID keys.

Set `APP_TIMEZONE` to the shop's zone (e.g. `Africa/Kampala`). It plays the role of the Nest host's `TZ`:
receipt/bill/KOT date and time, report "local" days and date-only filters. The JVM and the database session
stay UTC (stored timestamps do not change). Default `UTC` = a Nest host without `TZ`.

Optional modules load only when their flag is on (`ENABLE_BEVERAGE`, `ENABLE_HR`, ...; mirror with
`VITE_ENABLE_*` for the web build) and are then gated per tenant by its `OrganizationModule` rows.
Beverage control (bar bottle weighing) organization settings, all optional:

| Setting key | Default | Effect |
|---|---|---|
| `beverage.varianceToleranceG` | `5` | Grams of variance per line before it is flagged (product `varianceToleranceG` wins). |
| `beverage.requireAllBottlesWeighed` | `false` | `true`: a count with unweighed bottles that hold stock can only be submitted with a reason and manager approval (ADR-S004). |

Customer portal (ADR-S011 S10, licence `customerPortal`): `APP_PORTAL_BASE_URL` (e.g. `https://erp.example.com`)
is the web address put in e-mailed portal links when staff do not supply one. Never set
`APP_PORTAL_ALLOW_TEST_PAYMENTS=true` in production — it lets customers "pay" with the mock mobile-money gateway.

Grant `beverage:approve` only to supervisors: they approve flagged counts and unweighed exceptions and cannot
approve a count they started.

## 3. First install

```bash
cp .env.example .env            # fill secrets: openssl rand -base64 48
docker compose up -d db
docker compose run --rm api --spring.profiles.active=prod,seed   # fresh DB only
docker compose up -d --build
curl -fsS http://localhost:3001/api/v1/health/ready
```

## 4. Upgrade and rollback

Scripted (Linux/macOS: `scripts/upgrade.sh`, Windows: `scripts/upgrade.ps1`):

1. Preflight, then a **pre-upgrade `pg_dump`** inside the db container (`var/upgrade-backups/pre-upgrade-<ts>.dump`
   + `.sha256`). The script aborts before touching anything if the dump is empty.
2. The running api/web images are tagged `inventory-erp-{api,web}:rollback`.
3. Build from the checkout (default) or pull published images (`IMAGE_TAG=x.y.z scripts/upgrade.sh --pull` with
   `COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"`).
4. Recreate api + web; Flyway applies new `V{n}` migrations at startup.
5. Wait for `/api/v1/health/ready` (default 240 s). **If the new version never becomes ready the previous images are
   restored automatically** (`scripts/rollback.sh`); the database is left as is.
6. Smoke test: login, PIN login, open a shift, one sale, X-report.

Rollback after a bad release: `scripts/rollback.sh` (images only). Migrations are additive by policy, so the previous
image normally runs on the new schema. If a migration must be undone: `scripts/rollback.sh --restore-db <dump>` —
**destructive**: replaces the live database with the pre-upgrade dump and loses every write made since (asks for
confirmation; `ROLLBACK_CONFIRM=yes` for automation).

## 5. Cut-over from POS-CAFE (Nest) to Spring

1. Rehearse on a copy of the production database: start Spring against it — Flyway baselines at 2
   and applies V3+ (additive tables only).
2. Run the parity harness smoke scenarios against the copy; run a Z-report comparison for the
   last 7 business days (Nest vs Spring) — totals must match to the cent.
3. Production: announce downtime (~10 min), close all cash sessions, take a backup.
4. Stop the Nest API container, start the Spring API container on the same port with the same
   `.env` secrets (existing JWTs/refresh tokens/PIN hashes/MFA secrets stay valid).
5. Smoke test: login, PIN login, open session, one sale, KDS ticket, receipt print, X-report.
6. Rollback path: stop Spring, start Nest (schema unchanged except Spring-only V3 tables).
7. Android devices keep their device tokens (`PosDevice`) — no re-registration needed.

## 6. Backups (module `backup`)

- Scheduled full `pg_dump` (custom format) + uploads mirror + `.env` capture, AES-256-CBC/PBKDF2-600k encryption
  (openssl-compatible: `openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -in x.enc -out x`), sha256
  sidecars, verified copies to secondary folders, cloud copies through `aws`/`az`/`rclone`/`scp`/`curl`, retention
  (keep-last / keep-by-age / grandfather-father-son, never below the 3 newest), healthchecks.io ping, email on failure.
  Schedule and settings: **Admin → Backups** (`/admin/backups/settings`), stored server-wide.
- Set `BACKUP_ENCRYPTION_PASSWORD` (kept out of the config backup) and at least one secondary destination
  (USB / NAS / cloud) — `/health/ready` reports `degraded` until both are in place.
- **`PG_BIN` must point at PostgreSQL client tools of the same major version as the server** (16). Newer `pg_restore`
  (17+) emits `SET transaction_timeout`, which a 16 server rejects. The API image ships `postgresql16-client`.
- Every scheduled full backup is followed by a **restore test** into a throwaway database (row/table/migration counts
  compared with live, then dropped). Manual: `POST /api/v1/admin/backups/restore/test`.
- Restores always go into a **new** database (`POST /admin/backups/restore {scope:"database", backupFile,
  targetDatabase}`); switching the café to it is an operator step with the API stopped (`docs/legacy/backup-recovery.md`).
- Several API instances: the scheduled jobs take a ShedLock lock (`backup:<job>`), so only one instance backs up.

## 7. Monitoring

- Scrape `GET /api/v1/metrics` (Prometheus text) with `Authorization: Bearer $METRICS_TOKEN` (required under the
  `prod` profile). Ready-made stack: `infra/monitoring/` — `prometheus.yml`, `alerts.yml` and the Grafana dashboard
  "Inventory ERP — API" (`docker compose -f docker-compose.yml -f infra/monitoring/docker-compose.monitoring.yml up -d`,
  Grafana on :3002).
- Metrics: Micrometer HTTP (`http_server_requests_seconds{uri,status}`), JVM, GC, Hikari, process CPU, plus
  `erp_stock_posting_jobs_pending`, `erp_sync_dead_letters_open`, `erp_event_publications_incomplete`,
  `erp_backup_health` (0/1/2), `erp_backup_last_full_age_seconds`.
- Alerts (`alerts.yml`): API down, 5xx > 2 %, checkout p95 > 1.5 s, Hikari pending, heap > 90 %, GC > 10 %,
  stock-posting backlog, open sync dead letters, stuck outbox, backup critical / older than 36 h.
- `/api/v1/health/ready`: `critical.database` (503 when down) and `advisory.backup` (`degraded`, never 503).
- Logs: JSON to stdout under `prod`; ship with Loki/Promtail or the Docker logging driver. Search by `requestId`.

## 7a. Disaster-recovery drill (release gate)

`ADMIN_EMAIL=… ADMIN_PASSWORD=… node scripts/dr-drill.mjs` against a running stack: encrypted full backup through the API →
restore into a new scratch database → a second API container boots on it → trial balances of live and restored
system must be identical → scratch database dropped. Run it before every release and quarterly.

## 7b. Performance

- Load suite: `tests/load/` (k6: checkout, tab edits, Z-report + trial balance, tables SSE fan-out);
  `tools/perf/checkout-smoke.mjs` without k6.
- JVM: heap = 75 % of the container memory (`MaxRAMPercentage`), `ExitOnOutOfMemoryError`; give the API container
  ≥ 2 GB. `DB_POOL_SIZE` (default 20) must stay below Postgres `max_connections` minus maintenance headroom.
- Index review: enable `pg_stat_statements` (`shared_preload_libraries`) on the server, run the load suite, then
  `SELECT query, calls, mean_exec_time FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20`.
- Commit durability: business transactions always commit synchronously. Two bookkeeping writes commit with
  `synchronous_commit = off` (no WAL flush wait): outbox ship/fail marks (a mark lost in a database crash only
  re-delivers the event — delivery is at-least-once anyway) and fire-and-forget audit rows (`AuditService.record`,
  login-type events; money paths use `recordInTx`). A database crash can lose at most the last ~0.6 s of these.
- Most of the checkout latency under load is COMMIT (WAL flush). On a server with a slow disk, put `pg_wal` on fast
  storage before anything else; `synchronous_commit = off` server-wide is NOT recommended (money rows).
- Measured results: see the table in `docs/releases/v1.0.0.md`.

## 8. Runbooks

| Symptom | Action |
|---|---|
| POS shows "offline" | `curl /api/v1/health`; check API container; offline queue replays automatically when back |
| Stock not deducted after sales | `/pos/posting-monitor` → jobs/exceptions; retry; check `stock-posting-drain` lock |
| Cannot close cash session | open sync dead letters or pending stock postings block close — resolve in `/sync/dead-letters`, posting monitor |
| Printer not printing | `pos.printerHost/Port` setting; `nc -vz host 9100`; reprint from receipts screen |
| Login lockout | `POST /users/:id/unlock` (admin) |
| Disk full | prune old backups (`/admin/backups/cleanup`), check `var/uploads` |
| `/health/ready` says `degraded` | read `advisory.backup.error` — usually no encryption password, no second destination, or a stale/failed backup |
| Backup fails with `transaction_timeout` | `PG_BIN` points at newer client tools than the server — use the server's major version |
| Upgrade did not come up | `scripts/upgrade.sh` already rolled the images back; read `docker compose logs api`, fix, retry |
| Offline device sales missing | `/sync/dead-letters` (open items block shift close); device registry `/sync/devices` |
