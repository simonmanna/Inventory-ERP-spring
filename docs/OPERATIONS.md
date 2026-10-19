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

## 3. First install

```bash
cp .env.example .env            # fill secrets: openssl rand -base64 48
docker compose up -d db
docker compose run --rm api --spring.profiles.active=prod,seed   # fresh DB only
docker compose up -d --build
curl -fsS http://localhost:3001/api/v1/health/ready
```

## 4. Upgrade

1. `pg_dump` (or `POST /api/v1/admin/backups/full`) and verify the backup.
2. Pull new images / `git pull && docker compose build`.
3. `docker compose up -d api` — Flyway applies new `V{n}` migrations at startup.
4. Health: `/health/ready` 200, Prometheus scrape OK, smoke test (login, open POS, X-report).
5. Rollback: stop the new API, restore the dump if a migration was destructive (migrations are
   additive by policy, so normally just start the previous image).

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

## 6. Backups (module `backup`, P6)

- Nightly full `pg_dump` (custom format) + files + config, AES-256-CBC/PBKDF2-600k encryption
  (openssl-compatible: `openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in x.enc -out x`),
  sha256 checksum, retention `BACKUP_FULL_RETENTION_DAYS`, secondary folder copy, offsite via
  `rclone`, healthchecks.io ping, email on failure.
- Monthly automated restore test into a scratch database (`POST /admin/backups/restore/test`).
- Restore runbook: `docs/legacy/backup-recovery.md` (commands identical).

## 7. Monitoring

- Scrape `GET /api/v1/metrics` (Prometheus) with `Authorization: Bearer $METRICS_TOKEN` (required under the `prod` profile). Dashboards: HTTP rate/latency/errors, JVM, Hikari,
  stock-posting backlog, outbox lag, webhook failures, comm dispatch queue, backup age.
- Alerts: 5xx rate > 1 % 5 min; p95 checkout > 500 ms; stock-posting backlog > 100 or oldest > 5 min;
  backup older than 26 h; DB connections > 80 %; disk < 15 %.
- Logs: JSON to stdout; ship with Loki/Promtail or Docker logging driver. Search by `requestId`.

## 8. Runbooks

| Symptom | Action |
|---|---|
| POS shows "offline" | `curl /api/v1/health`; check API container; offline queue replays automatically when back |
| Stock not deducted after sales | `/pos/posting-monitor` → jobs/exceptions; retry; check `stock-posting-drain` lock |
| Cannot close cash session | open sync dead letters or pending stock postings block close — resolve in `/sync/dead-letters`, posting monitor |
| Printer not printing | `pos.printerHost/Port` setting; `nc -vz host 9100`; reprint from receipts screen |
| Login lockout | `POST /users/:id/unlock` (admin) |
| Disk full | prune old backups (`/admin/backups/cleanup`), check `var/uploads` |
