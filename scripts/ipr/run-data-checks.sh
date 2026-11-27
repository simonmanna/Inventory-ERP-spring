#!/usr/bin/env bash
# IPR R7 / Phase 0 step 0c: run a SELECT-only SQL file against a RESTORED COPY in a read-only transaction.
#
#   scripts/ipr/run-data-checks.sh <port> <sql-file>
#
#   <port>      the local port of the copy (scripts/ipr/restore-copy.sh prints it); connects to 127.0.0.1:<port>.
#   <sql-file>  plain SELECT statements, e.g. docs/audits/2026-11-27-inventory-data-checks.sql.
#
# Env (all optional): PGUSER / PGPASSWORD (default owner / owner, the copy's superuser), PGDATABASE (default erp),
#   OUT_DIR (default ./var/ipr-data-checks), STATEMENT_TIMEOUT (default 10min), LABEL (added to the file name),
#   ALLOW_PORT=yes to run on a port of this machine's live cluster: refused by default (5432 and 5434), because a
#   data check on live needs the owner's separate OK each time (Phase 0 rule).
#
# Safety: the session sets default_transaction_read_only = on and the whole file runs in ONE transaction opened
# with BEGIN READ ONLY, with ON_ERROR_STOP; any INSERT / UPDATE / DELETE / DDL fails ("cannot execute ... in a
# read-only transaction") and nothing is written. The file is also scanned for write keywords before it is sent.
# Results (psql aligned output, one block per query, echoed queries) go to a timestamped file; exit 0 = every query ran.
set -euo pipefail

log() { printf '[data-checks %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

[[ $# -eq 2 ]] || die "usage: $0 <port> <sql-file>"
PORT=$1
SQL=$2
[[ "$PORT" =~ ^[0-9]{4,5}$ ]] || die "port must be a number: $PORT"
[[ -f "$SQL" && -r "$SQL" ]] || die "sql file not found: $SQL"
if [[ ("$PORT" == 5432 || "$PORT" == 5434) && "${ALLOW_PORT:-}" != "yes" ]]; then
  die "port $PORT is a live cluster port on this machine: run data checks on a restored copy (ALLOW_PORT=yes overrides, only with the owner's OK)"
fi
command -v psql >/dev/null || PSQL_IN_DOCKER=1

# Belt and braces: the read-only transaction is the real guard, this only fails fast with a clear message. It looks
# for write statements at the start of a line (comments stripped) and for attempts to leave read-only mode.
if sed -e 's/--.*$//' "$SQL" | tr '[:upper:]' '[:lower:]' | grep -Eq \
    -e '^[[:space:]]*(insert|update|delete|merge|truncate|drop|alter|create|grant|revoke|copy|vacuum|reindex|cluster|refresh|lock)([[:space:]]|$)' \
    -e 'read[[:space:]]+write|transaction_read_only|session_authorization|set[[:space:]]+role'; then
  die "$SQL contains a write or mode-changing statement; data checks must be SELECT only"
fi

export PGUSER=${PGUSER:-owner}
export PGPASSWORD=${PGPASSWORD:-owner}
export PGDATABASE=${PGDATABASE:-erp}
OUT_DIR=${OUT_DIR:-./var/ipr-data-checks}
mkdir -p "$OUT_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$OUT_DIR/$(basename "$SQL" .sql)${LABEL:+-$LABEL}-p$PORT-$STAMP.txt"

run_psql() {
  local args=(-X -v ON_ERROR_STOP=1 -P pager=off -h 127.0.0.1 -p "$PORT" "$@")
  if [[ -n "${PSQL_IN_DOCKER:-}" ]]; then
    # No local client: a disposable postgres:16 client on the host network (same major version as the copy).
    docker run --rm -i --network host -e PGUSER -e PGPASSWORD -e PGDATABASE postgres:16 psql "${args[@]}"
  else
    psql "${args[@]}"
  fi
}

log "running $SQL on 127.0.0.1:$PORT/$PGDATABASE (read only) -> $OUT"
{
  echo "-- IPR data checks: $SQL"
  echo "-- target: 127.0.0.1:$PORT/$PGDATABASE as $PGUSER, started $STAMP"
  echo "-- sha256(sql): $(sha256sum "$SQL" | awk '{print $1}')"
} > "$OUT"
flyway=$(printf 'SET default_transaction_read_only = on;\nSELECT to_regclass('"'"'public.flyway_schema_history'"'"');\n' \
  | run_psql -At 2>/dev/null | tail -n 1 || true)
started=$(date +%s)
set +e
{
  printf "SET default_transaction_read_only = on;\nSET statement_timeout = '%s';\n" "${STATEMENT_TIMEOUT:-10min}"
  printf "BEGIN READ ONLY;\nSELECT current_database() AS database, now() AS checked_at, version() AS server;\n"
  [[ -n "$flyway" ]] && printf "SELECT max(installed_rank) AS flyway_rows, max(version::int) FILTER (WHERE version ~ '^[0-9]+\$')\n  AS flyway_head FROM flyway_schema_history;\n"
  cat "$SQL"
  printf "\nROLLBACK;\n"
} | run_psql --echo-queries >> "$OUT" 2>&1
rc=$?
set -e
echo "-- finished in $(( $(date +%s) - started ))s, psql exit $rc" >> "$OUT"
if [[ $rc -ne 0 ]]; then
  log "FAILED (psql exit $rc): see the end of $OUT"
  tail -n 5 "$OUT" >&2
  exit $rc
fi
log "ok: $OUT ($(grep -c '^(' "$OUT" || true) result sets)"
echo "$OUT"
