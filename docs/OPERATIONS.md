# Operations

## 1. Deployment topologies

| Topology | Stack |
|---|---|
| Single café (LAN server / mini-PC) | `docker compose up -d --build` → Postgres 16 + API + nginx web on :8080; terminals use the server's LAN IP (`CORS_ALLOW_LAN=true`) |
| Hosted (VPS) | same images behind Caddy (TLS, zstd/gzip) — see `docs/legacy/DEPLOYMENT.md` and `deployment/demo` in the legacy repo |
| Branch relay node (ADR-S083 O7) | same image on a branch mini-PC with its own Postgres, `ERP_SYNC_MODE=branch` (see §1a); the branch's tills sync to it over the LAN; HQ stays the system of record |
| Cloud read replica | logical replication of the LAN DB + API with `READ_ONLY_MODE=true` (`docs/legacy/architecture`) |

Minimum server: 2 vCPU, 4 GB RAM, SSD. Recommended: 4 vCPU, 8 GB. JVM heap = 75 % of container RAM.

## 1a. Branch relay node (ADR-S083 O7)

1. At HQ, register the branch server as a device: Settings → Devices, or `POST /sync/devices/register` with
   `{"name": "...", "platform": "branch", "branchId": "<branch>"}`. Keep the returned token. Register the branch's tills
   at HQ too, bound to the same branch.
2. On the branch machine run the API with its own database and:
   `ERP_SYNC_MODE=branch`, `ERP_SYNC_SERVER_ID=<unique, e.g. branch-arusha>`, `ERP_SYNC_BRANCH_HQ_URL=https://<hq>/api/v1`,
   `ERP_SYNC_BRANCH_TOKEN=<token>`, `ERP_SYNC_BRANCH_ORGANIZATION_ID=<org id>`, `APP_JOBS_ENABLED=false`
   (Spring relaxed binding of `erp.sync.*`). Optional: `ERP_SYNC_BRANCH_INTERVAL` (PT10S), `ERP_SYNC_BRANCH_PULL_INTERVAL`
   (PT30S), `ERP_SYNC_BRANCH_TIMEOUT` (PT10S).
3. Point the tills at the branch server's LAN address. Within a minute the branch has HQ's master data and the tills.
4. Watch `GET /sync/branch/status` (any till token): `pendingHqPosting` should drain whenever the WAN is up;
   `lastError` says why it does not. Rejections appear at HQ under Sync dead letters (device = the branch server).
5. Back up the branch database nightly with WAL (§7c). After a restore just start it: it reads HQ's feed first.

### Compose: HQ and a branch on one machine (demo, drill) or a branch machine

```bash
# .env: BRANCH_HQ_ADMIN_EMAIL, BRANCH_HQ_ADMIN_PASSWORD, BRANCH_CODE (an HQ branch code), optional BRANCH_HQ_ORG (DEMO),
#       BRANCH_SERVER_ID (branch-1), BRANCH_PORT (3003; Grafana uses 3002), BRANCH_HQ_URL (default: this file's api service)
docker compose --profile branch up -d --build          # HQ + branch; Android tills use http://<host>:3003,
                                                        # browser tills open http://<host>:8081 (BRANCH_WEB_PORT)
infra/docker/branch/drill.sh                            # cut the link, restore the branch, compare HQ (needs curl, jq)
# On a branch machine with a remote HQ:
BRANCH_HQ_URL=https://hq.example.com/api/v1 BRANCH_HQ_ORIGIN=https://hq.example.com \
  docker compose --profile branch up -d branch-db branch-enroll branch-api branch-web
```

Browser tills at the branch: open the branch web app (:8081) while HQ is reachable, sign in (sign-in goes to HQ), then
Settings → Offline Devices → This browser, choose the branch, register. With HQ down the app shows its offline banner
and the POS keeps selling through the branch server.

`branch-enroll` registers the branch once and keeps its token in the `branchenroll` volume. To re-enrol (token
revoked at HQ): `docker compose --profile branch run --rm --entrypoint rm branch-enroll /enroll/branch.env`, then start
the profile again.

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

Document management (ADR-S044 / S046): files stay in the kernel vault (`STORAGE_DRIVER`, `UPLOAD_MAX_BYTES`), so
backups already cover them. `APP_PUBLIC_WEB_URL` is the web origin used in share links (`<url>/share/<token>`).
OCR of scanned PDFs / images needs `DMS_OCR_ENABLED=true`, the `tesseract` binary on `PATH` and the language packs
named in `DMS_OCR_LANGUAGES` (e.g. `eng+ara`). Jobs: `dms-text-extraction` (5 min), `dms-expiry` (daily 06:15),
`dms-legacy-index` (10 min); all ShedLock-guarded. Helpdesk replies and e-mail dispatch use the SMTP settings.

Automatic exchange rates (FX-8, ADR-S082) are **off by default**; no outbound call is made unless both are set:

| Variable | Default | Effect |
|---|---|---|
| `FX_RATE_FETCH_ENABLED` | `false` | `true` turns on the daily fetch and `POST /currencies/rates/fetch`. |
| `FX_RATE_PROVIDER_URL` | (empty) | JSON endpoint with `{base}` replaced by the company's functional currency, answering `{"rates": {"USD": 0.00027, ...}}` or `conversion_rates` (units per one functional unit; the stored rate is the inverse). |
| `FX_RATE_FETCH_CRON` | `0 30 6 * * *` | When the daily job runs (server time); ShedLock `fx-rate-fetch`. |
| `FX_RATE_PROVIDER_NAME` | `provider` | Stored as the rate's `source`. |
| `FX_RATE_FETCH_RATE_TYPE` | `market` | Rate type the fetched rates are stored under (`official`, `bank`, `cash`, `market`). |

A rate already on file for the day and type (for example entered by hand) is never replaced. A company policy
that requires approved rates of that type leaves fetched rates `pending` until a second person approves them.

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

### 4a. Migrations V14 to V120 on an existing database

Merge everything first, then migrate once. `out-of-order` is off, so a database that took a higher version cannot accept a
lower one merged later. Reviewed by reading (Phase 6), not yet run:

- **Expand only.** New tables and nullable columns. Nothing dropped, renamed or retyped, and no new `NOT NULL` without a
  default, so the previous image keeps working on the new schema and `scripts/rollback.sh` (images only) stays valid.
- **Existing tables that gain columns:** `Activity`, `ApprovalStep`, `ApprovalWorkflow`, `BankStatementLine`, `Deal`,
  `InventoryLocation`, `ProductionOutput`, `PurchaseOrder`, `PurchaseRequest`. No POS table changes.
- **Short exclusive locks:** each `ADD COLUMN`, and V95 swapping the check on `CrmSavedFilter`. V62 adds a foreign key on
  `InventoryLocation`, which also locks `Branch` for a moment. Run it outside a shift.
- **V120** builds 14 indexes inside the Flyway transaction, blocking writes to each table while it builds. On a large
  copy, run them first with `CREATE INDEX CONCURRENTLY` under the same names: V120 uses `IF NOT EXISTS` and then skips them.
- **Two POS-table indexes are a manual step, on purpose.** Not in a migration because a plain build blocks tills and
  Flyway's default transactional lock can hang on `CONCURRENTLY`. Run them off-peak once volumes justify it:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Order_org_partner_idx" ON "Order" ("organizationId", "partnerId");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "Invoice_org_open_idx" ON "Invoice" ("organizationId") WHERE "amountResidual" > 0;
```

  They serve the customer portal, credit exposure and the receivables aging. Check for an `INVALID` index afterwards
  (`\d "Order"`) and drop and rebuild it if a build was interrupted.

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
- Coverage of new data: `pg_dump` takes the whole database, so every table to V120 and the `reporting` schema are in the
  archive without a table list to maintain. The automatic restore test compares `public` tables only: the restore drill
  in `docs/VERIFY_CHECKLIST.md` checks `reporting` by hand.

## 7. Monitoring

- Scrape `GET /api/v1/metrics` (Prometheus text) with `Authorization: Bearer $METRICS_TOKEN` (required under the
  `prod` profile). Ready-made stack: `infra/monitoring/` — `prometheus.yml`, `alerts.yml` and the Grafana dashboard
  "Inventory ERP — API" (`docker compose -f docker-compose.yml -f infra/monitoring/docker-compose.monitoring.yml up -d`,
  Grafana on :3002).
- Metrics: Micrometer HTTP (`http_server_requests_seconds{uri,status}`), JVM, GC, Hikari, process CPU, plus
  `erp_stock_posting_jobs_pending`, `erp_sync_dead_letters_open`, `erp_sync_push_ops_total{status}`,
  `erp_sync_lag_seconds_max` (devices active in the last 7 days), `erp_sync_devices_stale` (not heard from for 24 h),
  `erp_event_publications_incomplete`,
  `erp_backup_health` (0/1/2), `erp_backup_last_full_age_seconds`, `erp_job_failures_total{task}` (a scheduled job failed
  for one organization: replenishment scan, contract expiry, mobile money poll, alert scans, telematics sync) and
  `erp_provider_failures_total{provider}` (mobile money). These jobs carry on with the next organization, so their
  errors never reach the HTTP metrics.
- Alerts (`alerts.yml`): API down, 5xx > 2 %, checkout p95 > 1.5 s, Hikari pending, heap > 90 %, GC > 10 %,
  stock-posting backlog, open sync dead letters, stale or lagging sync devices, stuck outbox, failing scheduled job, failing external provider, backup
  critical / older than 36 h.
- `/api/v1/health/ready`: `critical.database` (503 when down) and `advisory.backup` (`degraded`, never 503).
- Logs: JSON to stdout under `prod`; ship with Loki/Promtail or the Docker logging driver. Search by `requestId`.

## 7a. Disaster-recovery drill (release gate)

`ADMIN_EMAIL=… ADMIN_PASSWORD=… node scripts/dr-drill.mjs` against a running stack: encrypted full backup through the API →
restore into a new scratch database → a second API container boots on it → trial balances of live and restored
system must be identical → scratch database dropped. Run it before every release and quarterly.

## 7c. Disaster recovery for offline sync (ADR-S083 C2)

Targets. These are owner-adjustable defaults:

| | HQ | Branch server (O7) |
|---|---|---|
| Backup | Continuous WAL archiving + point-in-time recovery | Nightly base backup + WAL |
| RPO | ≤ 5 minutes | ≤ 15 minutes |
| RTO | ≤ 4 hours | ≤ 4 hours |

Rules:

1. **The applied-op ledger (`SyncAppliedOp`) is not a backup.** Lost business records come back only from a database
   restore, never by re-creating them from the ledger.
2. **After any restore, keep devices and branches pointed at the restored server.** They re-push every op they have
   not seen acknowledged. Normal dedup decides apply or replay:
   - an op whose ledger row was lost with the restore is applied again, because its business rows were lost too
   - an op whose ledger row survived is replayed
3. **Run the post-restore reconciliation.** It runs by itself a few minutes after the API starts and nightly; to run it
   now: `POST /sync/reconciliation/run` (needs `organization:update`), then read `GET /sync/reconciliation/runs/{id}`.
   Administrators get an in-app alert when its findings change. Its checks: device and branch watermarks ahead of the
   ledger, stale `committed` ledger rows, unbalanced posted journals, AR/AP subledger vs control accounts, `StockItem`
   vs stock movements, open dead letters. What to do with each finding:
   - `watermarks`: the device acknowledged ops the restore lost; make sure it is online and syncing (it re-pushes them),
     then run again
   - `committedOps`: verify the business rows of each op; it is also an `OPERATION_INCOMPLETE` dead letter
   - `unbalancedJournals`, `receivables`, `payables`: hand to accounting before the period is closed
   - `stock`: rebuild or count the listed cells (Inventory → reconciliation)

   Record what you found in the incident note.
4. **Revoke and re-register a device whose token was in a lost backup window**, if it can no longer pull. Ops it
   pushes after revocation are held as `DEVICE_REVOKED` dead letters; recover the genuine ones (see the runbook row
   "Device revoked or token expired with unsynced sales").

WAL archiving and PITR are infrastructure tasks for the hosting environment. They are not configured by the application.

## 7b. Performance

- Load suite: `tests/load/` (k6: checkout, tab edits, Z-report + trial balance, tables SSE fan-out);
  `tools/perf/checkout-smoke.mjs` without k6.
- Construction routes: `tools/perf/construction-load.mjs` (dispatch board, weighbridge, production runs, stock, reports;
  p95 < 300 ms). Start the API with `THROTTLE_ENABLED=false` or it measures 429s.
- JVM: heap = 75 % of the container memory (`MaxRAMPercentage`), `ExitOnOutOfMemoryError`; give the API container
  ≥ 2 GB. `DB_POOL_SIZE` (default 20) must stay below Postgres `max_connections` minus maintenance headroom.
- Discuss (ADR-S064): presence, typing and live messages travel over the in-process SSE fan-out of
  `/communication/stream`. Running more than one API instance needs sticky sessions for that path (for example
  nginx `ip_hash` or a cookie). Otherwise users on different instances see each other as offline and only get
  messages on the next poll (30 s). Department channels resync hourly (`DiscussDepartmentSyncJob`, ShedLock).
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
| Cannot close cash session | 409 `SYNC_DEAD_LETTERS_OPEN`: a money op of this shift was rejected by sync — resolve it in `/sync/dead-letters` (retry, recover, or discard with a note), or a supervisor with `pos:override` closes with `syncOverrideReason` (audited). Pending stock postings: posting monitor |
| Device revoked or token expired with unsynced sales | its pushes are held as `DEVICE_REVOKED` / `DEVICE_TOKEN_EXPIRED` dead letters, never applied automatically. Check each against paper receipts, then `POST /sync/dead-letters/{id}/recover` with a note, in device-sequence order. A sequence gap (ops that never arrived) needs `acknowledgeGap: true`. Every recovery is audited |
| Printer not printing | `pos.printerHost/Port` setting; `nc -vz host 9100`; reprint from receipts screen |
| Login lockout | `POST /users/:id/unlock` (admin) |
| Disk full | prune old backups (`/admin/backups/cleanup`), check `var/uploads` |
| `/health/ready` says `degraded` | read `advisory.backup.error` — usually no encryption password, no second destination, or a stale/failed backup |
| Backup fails with `transaction_timeout` | `PG_BIN` points at newer client tools than the server — use the server's major version |
| Upgrade did not come up | `scripts/upgrade.sh` already rolled the images back; read `docker compose logs api`, fix, retry |
| Offline device sales missing | `/sync/dead-letters` (open items block shift close); device registry `/sync/devices` |
| Web till must sell through an outage | Register it once while online: Settings → Offline devices → "This browser" (needs `organization:update`); it must sync once before it can work offline (ADR-S083 O2) |
| Offline receipt shows `OFF-XXXX-000123` | Provisional number of a sale recorded offline; the POS sync badge shows the final invoice number once it posts |
| Back office shows "Offline: data as of …" | The server is unreachable; screens show what this browser saved (per user, 24 h–7 days by kind) and writes are refused with "Needs connection". It clears by itself within ~10 s of the server answering (ADR-S083 O3) |
| Offline stock work shows "Held for a supervisor" | The server kept the document but refused its posting (e.g. not enough stock at the source, or the user may not post receipts). Find it in Inventory → Offline stock discrepancies, then complete or reverse the document in the back office and resolve the exception in the Posting Monitor (ADR-S083 O4) |
| A transfer receipt says "Waiting for the dispatch to sync" | The receiving tablet synced before the sending one. It is applied automatically within ~2 minutes of the dispatch reaching the server (`SyncStockJob`); nothing to do unless it stays open |
| Posting Monitor shows an `integrity_*` exception, or period close refused: "open inventory integrity finding(s) on stock value" | The daily inventory integrity monitor (03:10 UTC, `InventoryIntegrityJob`) found a broken invariant. Open Inventory → `GET /inventory/reports/integrity` to see every row of the check (5,000 max). Nothing was corrected: investigate the rows (the read-only queries in `docs/audits/2026-11-22-inventory-data-checks.sql` help for history), fix through documents or an owner-approved repair, then resolve the exception; the next run re-raises it if the check still fails. Only `integrity_valuation_vs_subledger` / `integrity_valuation_vs_gl` hold the period close |
| Period close refused: "stock work recorded offline was refused…" | Open stock dead letters for the period. Correct or discard them in Settings → Offline devices → Rejected, then close (ADR-S083 O4) |
| A field order shows "Price differs — in review" | The server priced the order more than `sync.varianceTolerance.salesOrder` % (default 0.5) away from what the rep's tablet showed — usually a price change or a rule the tablet cannot see. The order is a draft: check it in Sales → Offline sales review, confirm or correct it, then resolve the item (ADR-S083 O5) |
| A field order is "On hold — Offline order cap exceeded" | The rep confirmed more from offline orders within the offline window than `sync.offlineOrderCap` allows. Release the order if it is fine, resolve the review item; raise the cap in Settings if reps legitimately sell more offline |
| A field payment is "Posted — part left on account" | An invoice the rep allocated to was paid or credited meanwhile. The full amount received is posted; the remainder sits unallocated on the customer's account. Allocate it from Customer Receipts and resolve the item; the Offline payments report shows it per rep and day |
| An Android till says "has not synced for more than N hours" and refuses sales | The offline window (`sync.offlineWindowHours`, default 72, or the device policy) has lapsed since its last pull, or its clock reads earlier than the last sync. Connect it and sync (More → Sync); fix the date and time if it was wound back. Queued sales are still pushed (ADR-S083 O6) |
| An Android till says "This device's token expired" | The token passed `sync.deviceTokenTtlDays` without a pull. Nothing on the till was deleted; its queued work is pushed and held as `DEVICE_TOKEN_EXPIRED` dead letters. Re-enroll the device, then recover the held ops under Sync dead letters |
| A dead letter with code `SEQ_REUSED` | A protocol-2 client sent a device sequence it had already used for another op (an old copy of its data, or a bug). Nothing was applied. Check the device's other op with that sequence; discard the duplicate unless it is a genuine, different sale — then re-enter it as a new sale |
| A branch server's `pendingHqPosting` keeps growing | HQ unreachable or refusing the branch token: `GET /sync/branch/status` → `lastError`. Fix the WAN / URL; a 401 means the branch device was revoked at HQ — register a new branch device and update `ERP_SYNC_BRANCH_TOKEN`. Tills keep selling meanwhile; nothing is lost |
| A dead letter with code `ORIGIN_NOT_ALLOWED` | A branch server relayed an op that names no till of its own branch (a till moved to another branch, or a misconfigured branch). Nothing was applied; move the till back or re-enter the sale at the right branch |
| A branch till cannot close its shift: "rejected sync operation(s) involving money" | A money op of that shift was rejected at HQ. Resolve or discard its dead letter at HQ; the branch learns it within one relay cycle and the close goes through. At HQ, any open money rejection from that branch blocks closing that branch's shifts (ADR-S083 O7 deviation 5) |
