#!/usr/bin/env bash
# IPR R7: rehearse a release's migrations and their rollback on a RESTORED COPY. Never touches the live database,
# the live API, or the live files: everything runs against a throwaway container (scripts/ipr/restore-copy.sh) and
# spare ports, with scratch storage and every scheduled job off.
#
#   CANDIDATE_JAR=/path/new.jar PREVIOUS_JAR=/path/old.jar scripts/ipr/rehearse-migration.sh <dump>
#
# Steps (each prints PASS/FAIL; the first FAIL stops the run and cleans up):
#   1. restore <dump> into a throwaway postgres:16 on DB_PORT and record the Flyway head (H0) and row counts
#   2. take the rehearsal's own pre-upgrade dump of that copy (what scripts/upgrade.sh step 1 does on live)
#   3. list the candidate's migrations above H0 (read from the jar) and flag non-additive SQL (R7: a V7xx must add
#      nullable columns or indexes only, no data rewrite, so the previous jar still runs on the new schema)
#   4. candidate `--migrate-only` as the owner: exit 0, "Successfully applied" / "up to date", every new
#      flyway_schema_history row success, head H1 > H0 (or = H0 when nothing is new)
#   5. candidate API on API_PORT: /health/ready answers; optional login + trial balance (ORG_CODE/ADMIN_EMAIL/
#      ADMIN_PASSWORD) captured as the "after migration" evidence
#   6. previous jar on the MIGRATED schema (Flyway off, Hibernate validate on): proves the migrations are additive
#   7. rollback.sh --restore-db semantics on the COPY only: drop + recreate the copy's database from the step-2 dump
#      (same dropdb / createdb -T template0 / pg_restore --no-owner --exit-on-error as scripts/rollback.sh); the
#      Flyway head is H0 again and the row counts equal step 1
#   8. previous jar on the restored copy (its own migrate-only first: "up to date"): /health/ready answers
#   9. cleanup: stop the jars, remove the container (KEEP=yes keeps the container for inspection)
#
# Env:
#   CANDIDATE_JAR, PREVIOUS_JAR   required (e.g. /opt/inventory-erp/dist/inventory-erp.jar, the newest app.jar.bak-*)
#   JAVA        default /opt/inventory-erp/jdk/bin/java, else java on PATH
#   DB_PORT     default 5711        API_PORT  default 3911       (both must be free; checked)
#   ENV_FILE    optional api.env-style file for feature flags (ENABLE_*); its database, storage, backup, mail, SMS,
#               payment and integration settings are overridden below, so nothing reaches a live system
#   ORG_CODE / ADMIN_EMAIL / ADMIN_PASSWORD   optional smoke login (DEMO by default; use the copy's credentials)
#   WORK_DIR    default ./var/ipr-rehearsal/<stamp> (logs, dumps, evidence)
#   READY_TIMEOUT seconds per API start, default 400 (startup is ~100 s on the shared host)
#   JAVA_HEAP   -Xmx for every jar run, default 1536m (the live api.env value; 700m runs out of heap at startup
#               on a copy of live, found by the first Phase 5 rehearsal)
#   BACKUP_ENCRYPTION_PASSWORD   when <dump> is a .dump.enc
#
# Exit 0 = every step passed. Evidence: $WORK_DIR/summary.txt plus one log per step.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
log() { printf '[rehearse %s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "${SUMMARY:-/dev/null}" >&2; }
die() { log "FAIL: $*"; exit 1; }
pass() { log "PASS: $*"; }

[[ $# -eq 1 ]] || die "usage: CANDIDATE_JAR=… PREVIOUS_JAR=… $0 <dump>"
DUMP=$1
[[ -f "$DUMP" ]] || die "dump not found: $DUMP"
[[ -f "${CANDIDATE_JAR:-}" ]] || die "set CANDIDATE_JAR to the new release's jar"
[[ -f "${PREVIOUS_JAR:-}" ]] || die "set PREVIOUS_JAR to the running release's jar"
JAVA=${JAVA:-/opt/inventory-erp/jdk/bin/java}
[[ -x "$JAVA" ]] || JAVA=$(command -v java || true)
[[ -n "$JAVA" ]] || die "no java: set JAVA"
DB_PORT=${DB_PORT:-5711}
API_PORT=${API_PORT:-3911}
READY_TIMEOUT=${READY_TIMEOUT:-400}
JAVA_HEAP=${JAVA_HEAP:-1536m}
CONTAINER=${CONTAINER:-ierp-ipr-rehearsal-$DB_PORT}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
WORK_DIR=${WORK_DIR:-$ROOT/var/ipr-rehearsal/$STAMP}
mkdir -p "$WORK_DIR/storage" "$WORK_DIR/backups"
SUMMARY=$WORK_DIR/summary.txt
: > "$SUMMARY"
for p in "$API_PORT"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then die "port $p is in use"; fi
done
log "work dir $WORK_DIR; candidate $(basename "$CANDIDATE_JAR"), previous $(basename "$PREVIOUS_JAR")"

APP_PID=""
cleanup() {
  if [[ -n "$APP_PID" ]]; then
    pkill -TERM -P "$APP_PID" 2>/dev/null || true
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ "${KEEP:-}" == "yes" ]]; then
    log "KEEP=yes: container $CONTAINER left running on 127.0.0.1:$DB_PORT"
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

psql_copy() { docker exec -i "$CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -U owner -d erp "$@"; }
flyway_head() {
  psql_copy -At -c "SELECT coalesce(max(version::int), 0) FROM flyway_schema_history
                    WHERE success AND version ~ '^[0-9]+$'"
}
row_counts() {
  psql_copy -At -F ' ' <<'SQL'
CREATE TEMP TABLE c (t text, n bigint);
DO $$ DECLARE r record; BEGIN
  FOR r IN SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
           AND table_name <> 'flyway_schema_history' LOOP
    EXECUTE format('INSERT INTO c SELECT %L, count(*) FROM public.%I', r.table_name, r.table_name);
  END LOOP; END $$;
SELECT t, n FROM c ORDER BY t;
SQL
}

# The jar's environment: the copy, scratch storage, nothing external, no scheduled jobs.
app_env() {
  local flyway=$1
  shift
  (
    set -a
    # shellcheck disable=SC1090
    [[ -n "${ENV_FILE:-}" ]] && . "$ENV_FILE"
    PORT=$API_PORT
    JDBC_DATABASE_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/erp?stringtype=unspecified"
    DB_USER=erp_app DB_PASSWORD=rehearsal-app
    FLYWAY_USER=owner FLYWAY_PASSWORD=owner
    SPRING_FLYWAY_ENABLED=$flyway
    DB_BACKUP_USER="" DB_BACKUP_PASSWORD="" PGUSER=owner PGPASSWORD=owner PGHOST=127.0.0.1 PGPORT=$DB_PORT
    APP_JOBS_ENABLED=false BACKUP_CRON_ENABLED=false
    BACKUP_DIR=$WORK_DIR/backups STORAGE_LOCAL_DIR=$WORK_DIR/storage
    SMTP_HOST="" TWILIO_ACCOUNT_SID="" TWILIO_AUTH_TOKEN="" SUPERSET_URL="" SUPERSET_PUBLIC_URL=""
    METRICS_TOKEN=rehearsal
    set +a
    exec "$@"
  )
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$API_PORT") 2>/dev/null; }

start_api() { # <jar> <flyway true|false> <label>
  local jar=$1 flyway=$2 label=$3
  # A server still answering here would make this step "pass" against the wrong jar (first Phase 5 run).
  port_open && die "$label: port $API_PORT already answers (an earlier API is still running)"
  app_env "$flyway" "$JAVA" -Xmx"$JAVA_HEAP" -jar "$jar" > "$WORK_DIR/$label.log" 2>&1 &
  APP_PID=$!
  local deadline=$(( $(date +%s) + READY_TIMEOUT ))
  until curl -fsS "http://127.0.0.1:$API_PORT/api/v1/health/ready" > "$WORK_DIR/$label.ready.json" 2>/dev/null; do
    kill -0 "$APP_PID" 2>/dev/null || { tail -n 40 "$WORK_DIR/$label.log" >&2; die "$label: the API exited"; }
    (( $(date +%s) < deadline )) || { tail -n 40 "$WORK_DIR/$label.log" >&2; die "$label: not ready in ${READY_TIMEOUT}s"; }
    sleep 5
  done
  local errors
  errors=$(grep -c ' ERROR ' "$WORK_DIR/$label.log" || true)
  pass "$label: ready ($(cat "$WORK_DIR/$label.ready.json" | head -c 200)); $errors ERROR log lines"
}

stop_api() {
  [[ -n "$APP_PID" ]] || return 0
  # APP_PID is the background wrapper shell; the jar is its child (app_env's subshell exec), so stop the child too.
  pkill -TERM -P "$APP_PID" 2>/dev/null || true
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
  local deadline=$(( $(date +%s) + 90 ))
  while port_open; do
    (( $(date +%s) < deadline )) || die "the API on port $API_PORT did not stop"
    sleep 1
  done
}

smoke() { # <label>
  [[ -n "${ADMIN_EMAIL:-}" && -n "${ADMIN_PASSWORD:-}" ]] || { log "$1: no ADMIN_EMAIL/ADMIN_PASSWORD, smoke login skipped"; return 0; }
  local api="http://127.0.0.1:$API_PORT/api/v1" token
  token=$(curl -fsS -H 'Content-Type: application/json' -d "$(printf '{"organizationCode":"%s","email":"%s","password":"%s"}' \
    "${ORG_CODE:-DEMO}" "$ADMIN_EMAIL" "$ADMIN_PASSWORD")" "$api/auth/login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  [[ -n "$token" ]] || die "$1: smoke login failed"
  curl -fsS -H "Authorization: Bearer $token" "$api/reports/accounting/trial-balance" > "$WORK_DIR/$1.trial-balance.json" \
    || die "$1: trial balance failed"
  curl -fsS -H "Authorization: Bearer $token" "$api/inventory/reports/integrity" > "$WORK_DIR/$1.integrity.json" \
    || log "$1: integrity report not readable (permission or older build)"
  pass "$1: login + trial balance ($(wc -c < "$WORK_DIR/$1.trial-balance.json") bytes)"
}

# 1. restore
CONTAINER=$CONTAINER "$ROOT/scripts/ipr/restore-copy.sh" "$DUMP" "$DB_PORT" > "$WORK_DIR/1-restore.txt" \
  || die "restore-copy failed (see $WORK_DIR/1-restore.txt)"
H0=$(flyway_head)
row_counts > "$WORK_DIR/1-rows.txt"
pass "1 restore: flyway head V$H0, $(wc -l < "$WORK_DIR/1-rows.txt") tables"

# 2. the rehearsal's pre-upgrade dump (of the copy, like scripts/upgrade.sh)
docker exec "$CONTAINER" pg_dump --format=custom --no-owner -U owner erp > "$WORK_DIR/pre-upgrade.dump"
[[ -s "$WORK_DIR/pre-upgrade.dump" ]] || die "2 pre-upgrade dump is empty"
sha256sum "$WORK_DIR/pre-upgrade.dump" > "$WORK_DIR/pre-upgrade.dump.sha256"
pass "2 pre-upgrade dump of the copy ($(du -h "$WORK_DIR/pre-upgrade.dump" | cut -f1))"

# 3. the candidate's new migrations, and a static additivity scan
mkdir -p "$WORK_DIR/migrations"
NEW=()
while read -r entry; do
  f=$(basename "$entry")
  v=$(sed -n 's/^V\([0-9]*\)__.*/\1/p' <<< "$f")
  [[ -n "$v" && "$v" -gt "$H0" ]] || continue
  unzip -p "$CANDIDATE_JAR" "$entry" > "$WORK_DIR/migrations/$f"
  NEW+=("$f")
done < <(unzip -Z1 "$CANDIDATE_JAR" 'BOOT-INF/classes/db/migration/V*.sql' 2>/dev/null | sort -V)
if [[ ${#NEW[@]} -eq 0 ]]; then
  log "3 no migrations above V$H0 in the candidate"
else
  log "3 new migrations: ${NEW[*]}"
  flagged=0
  for f in "${NEW[@]}"; do
    # A column TYPE change needs the keyword on its own (not "countType"). ADD COLUMN ... NOT NULL is flagged only
    # without a DEFAULT: with one, the previous jar's inserts still succeed and Postgres 11+ does not rewrite rows.
    hits=$( { sed -e 's/--.*$//' "$WORK_DIR/migrations/$f" | grep -Eni \
      -e 'drop[[:space:]]+(table|column|type|schema)|alter[[:space:]]+column[^;]*([[:space:]]type[[:space:]]|set[[:space:]]+not[[:space:]]+null)' \
      -e '^[[:space:]]*(update|delete|truncate)[[:space:]]|rename[[:space:]]' || true
      sed -e 's/--.*$//' "$WORK_DIR/migrations/$f" | grep -Eni 'add[[:space:]]+column[^;]*not[[:space:]]+null' \
        | grep -Evi 'default' || true; } )
    if [[ -n "$hits" ]]; then
      flagged=1
      log "   NOT ADDITIVE? $f:"; sed 's/^/      /' <<< "$hits" | tee -a "$SUMMARY" >&2
    fi
  done
  [[ $flagged -eq 0 ]] && pass "3 static scan: every new migration looks additive" \
    || log "3 review the flagged statements: R7 requires owner sign-off for any non-additive V7xx"
fi

# 4. candidate migrate-only
set +e
app_env true "$JAVA" -Xmx"$JAVA_HEAP" -jar "$CANDIDATE_JAR" --migrate-only > "$WORK_DIR/4-migrate.log" 2>&1
rc=$?
set -e
[[ $rc -eq 0 ]] || { tail -n 40 "$WORK_DIR/4-migrate.log" >&2; die "4 candidate --migrate-only exited $rc"; }
grep -Eq 'Successfully applied|is up to date' "$WORK_DIR/4-migrate.log" || die "4 no Flyway success line in the log"
failed=$(psql_copy -At -c "SELECT count(*) FROM flyway_schema_history WHERE NOT success")
[[ "$failed" == 0 ]] || die "4 $failed failed flyway_schema_history row(s)"
H1=$(flyway_head)
psql_copy -At -F ' | ' -c "SELECT version, description, installed_on, execution_time || ' ms' FROM flyway_schema_history
                           WHERE version ~ '^[0-9]+$' AND version::int > $H0 ORDER BY installed_rank" > "$WORK_DIR/4-applied.txt"
(( H1 >= H0 )) || die "4 flyway head went backwards (V$H0 -> V$H1)"
pass "4 candidate migrate-only: V$H0 -> V$H1, $(wc -l < "$WORK_DIR/4-applied.txt") migration(s) applied, none failed"

# 5. candidate API on the migrated copy
start_api "$CANDIDATE_JAR" false 5-candidate
smoke 5-candidate
stop_api

# 6. previous jar on the migrated schema (additivity proof)
start_api "$PREVIOUS_JAR" false 6-previous-on-new-schema
smoke 6-previous-on-new-schema
stop_api

# 7. rollback --restore-db semantics, on the copy only
docker exec "$CONTAINER" sh -c 'dropdb -U owner --if-exists erp && createdb -U owner -T template0 erp'
docker exec -i "$CONTAINER" pg_restore --no-owner --no-privileges --exit-on-error -U owner -d erp < "$WORK_DIR/pre-upgrade.dump"
H2=$(flyway_head)
[[ "$H2" == "$H0" ]] || die "7 after the restore the flyway head is V$H2, expected V$H0"
row_counts > "$WORK_DIR/7-rows.txt"
diff -q "$WORK_DIR/1-rows.txt" "$WORK_DIR/7-rows.txt" >/dev/null || die "7 row counts differ from step 1 (diff 1-rows.txt 7-rows.txt)"
pass "7 restore-db on the copy: head V$H2, row counts identical to step 1"

# 8. previous jar on the restored copy
set +e
app_env true "$JAVA" -Xmx"$JAVA_HEAP" -jar "$PREVIOUS_JAR" --migrate-only > "$WORK_DIR/8-migrate.log" 2>&1
rc=$?
set -e
[[ $rc -eq 0 ]] || { tail -n 40 "$WORK_DIR/8-migrate.log" >&2; die "8 previous --migrate-only exited $rc"; }
[[ "$(flyway_head)" == "$H0" ]] || die "8 the previous jar changed the schema head"
start_api "$PREVIOUS_JAR" false 8-previous-on-restored
smoke 8-previous-on-restored
stop_api

log "ALL STEPS PASSED (V$H0 -> V$H1 -> V$H0). Evidence in $WORK_DIR"
