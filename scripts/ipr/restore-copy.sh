#!/usr/bin/env bash
# IPR R7: restore a backup dump into a THROWAWAY Docker postgres:16 container. Never touches a running cluster.
#
#   scripts/ipr/restore-copy.sh <dump> <port>
#
#   <dump>  a pg_dump custom-format archive (.dump) as written by the backup module or scripts/upgrade.sh, or an
#           encrypted one (.dump.enc): BACKUP_ENCRYPTION_PASSWORD must then be set (decrypted on the fly, never
#           written to disk). A <dump>.sha256 sidecar, when present, is checked first.
#   <port>  a FREE local port; the copy listens on 127.0.0.1:<port>. The script refuses a port that is in use, so
#           it can never restore into the live cluster or another test database.
#
# Env (all optional):
#   CONTAINER     container name                  (default ierp-ipr-restore-<port>)
#   PG_IMAGE      server image, same major as live (default postgres:16)
#   PG_SUPERUSER / PG_SUPERPASSWORD               (default owner / owner: Flyway can migrate the copy as this role)
#   PG_DB         database name                   (default erp)
#   KEEP_ON_FAIL  yes = leave a failed container for inspection (default: removed)
#
# Restore flags follow BackupService.restore (--no-owner --exit-on-error) plus --no-privileges, because the copy has
# none of the live roles; a candidate jar's afterMigrate re-creates and grants the API role (docs/OPERATIONS.md §4b).
# Output: table count, migration head, and the row count of every public table (exact count(*), largest first).
# Remove the copy afterwards: docker rm -f <container>.
set -euo pipefail

log() { printf '[restore-copy %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

[[ $# -eq 2 ]] || die "usage: $0 <dump> <port>"
DUMP=$1
PORT=$2
[[ -f "$DUMP" && -r "$DUMP" ]] || die "dump not found or not readable: $DUMP"
[[ "$PORT" =~ ^[0-9]{4,5}$ ]] || die "port must be a number: $PORT"
CONTAINER=${CONTAINER:-ierp-ipr-restore-$PORT}
PG_IMAGE=${PG_IMAGE:-postgres:16}
PG_SUPERUSER=${PG_SUPERUSER:-owner}
PG_SUPERPASSWORD=${PG_SUPERPASSWORD:-owner}
PG_DB=${PG_DB:-erp}

command -v docker >/dev/null || die "docker is not installed"
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  die "port $PORT is already in use: pick a free port (this script never restores into a running cluster)"
fi
docker container inspect "$CONTAINER" >/dev/null 2>&1 && die "container $CONTAINER already exists (docker rm -f it)"

if [[ -f "$DUMP.sha256" ]]; then
  expected=$(awk '{print $1; exit}' "$DUMP.sha256")
  actual=$(sha256sum "$DUMP" | awk '{print $1}')
  [[ "$expected" == "$actual" ]] || die "checksum mismatch for $DUMP"
  log "checksum verified ($actual)"
else
  log "no $DUMP.sha256 sidecar: checksum not verified"
fi

ENCRYPTED=0
if [[ "$DUMP" == *.enc ]]; then
  [[ -n "${BACKUP_ENCRYPTION_PASSWORD:-}" ]] || die "encrypted dump: set BACKUP_ENCRYPTION_PASSWORD"
  command -v openssl >/dev/null || die "openssl is needed to decrypt $DUMP"
  ENCRYPTED=1
fi

cleanup_on_fail() {
  local rc=$?
  if [[ $rc -ne 0 && "${KEEP_ON_FAIL:-}" != "yes" ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    log "failed (exit $rc): container $CONTAINER removed (KEEP_ON_FAIL=yes keeps it)"
  fi
}
trap cleanup_on_fail EXIT

log "starting $PG_IMAGE as $CONTAINER on 127.0.0.1:$PORT"
docker run -d --name "$CONTAINER" --label ierp.ipr=restore-copy \
  -e POSTGRES_USER="$PG_SUPERUSER" -e POSTGRES_PASSWORD="$PG_SUPERPASSWORD" -e POSTGRES_DB="$PG_DB" \
  -p "127.0.0.1:$PORT:5432" "$PG_IMAGE" -c max_locks_per_transaction=1024 >/dev/null

ready=0
for _ in $(seq 1 60); do
  # pg_isready over TCP: the image's init phase only listens on the socket, so this waits for the real server.
  if docker exec "$CONTAINER" pg_isready -h 127.0.0.1 -U "$PG_SUPERUSER" -d "$PG_DB" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
[[ $ready -eq 1 ]] || die "postgres in $CONTAINER did not become ready"

started=$(date +%s)
log "restoring $(basename "$DUMP") ($(du -h "$DUMP" | cut -f1)) via stdin"
restore() {
  docker exec -i "$CONTAINER" pg_restore --no-owner --no-privileges --exit-on-error -U "$PG_SUPERUSER" -d "$PG_DB"
}
if [[ $ENCRYPTED -eq 1 ]]; then
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -in "$DUMP" -pass env:BACKUP_ENCRYPTION_PASSWORD \
    | restore
else
  restore < "$DUMP"
fi
log "restored in $(( $(date +%s) - started ))s"

psql_copy() { docker exec -i "$CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -U "$PG_SUPERUSER" -d "$PG_DB" "$@"; }

echo "== restored copy: container=$CONTAINER port=$PORT db=$PG_DB dump=$DUMP"
psql_copy -At -c "SELECT 'tables(public): ' || count(*) FROM information_schema.tables
                  WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
psql_copy -At -c "SELECT 'schemas: ' || string_agg(nspname, ', ' ORDER BY nspname) FROM pg_namespace
                  WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema'"
if [[ -n $(psql_copy -At -c "SELECT to_regclass('public.flyway_schema_history')") ]]; then
  psql_copy -At -c "SELECT 'flyway head: V' || version || ' ' || description || ' (' || count(*) OVER () || ' rows, '
                    || (SELECT count(*) FROM flyway_schema_history WHERE NOT success) || ' failed)'
                    FROM flyway_schema_history WHERE version IS NOT NULL
                    ORDER BY installed_rank DESC LIMIT 1"
else
  echo "flyway head: (no flyway_schema_history table)"
fi
echo "== exact row counts (public, non-empty first)"
psql_copy -At -F $'\t' <<'SQL'
CREATE TEMP TABLE ipr_counts (tbl text, n bigint);
DO $$
DECLARE t record;
BEGIN
  FOR t IN SELECT table_name FROM information_schema.tables
           WHERE table_schema = 'public' AND table_type = 'BASE TABLE' LOOP
    EXECUTE format('INSERT INTO ipr_counts SELECT %L, count(*) FROM public.%I', t.table_name, t.table_name);
  END LOOP;
END $$;
SELECT n, tbl FROM ipr_counts ORDER BY n DESC, tbl;
SELECT '-- total rows: ' || sum(n) || ' in ' || count(*) || ' tables (' || count(*) FILTER (WHERE n > 0)
       || ' non-empty)' FROM ipr_counts;
SQL
trap - EXIT
log "done. Connect: psql postgresql://$PG_SUPERUSER:***@127.0.0.1:$PORT/$PG_DB   Remove: docker rm -f $CONTAINER"
