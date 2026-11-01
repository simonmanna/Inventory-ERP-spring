#!/usr/bin/env bash
# Every parity scenario in one go, in batches that stay under Nest's login throttle (10 logins / 5 min / IP):
# the Nest reference is restarted before each batch, which clears its in-memory throttler.
#
#   NEST_DIR=<built copy of reference/nest-api> NEST_DB=postgresql://erp:erp@localhost:5434/parity_nest \
#   SPRING_URL=http://127.0.0.1:3031 contract-tests/run-all.sh > parity.log
#
# Spring must already run (THROTTLE_ENABLED=false) on a database migrated and seeded like NEST_DB, with the same
# JWT_* secrets and ENABLE_* flags exported here. Nest runs with TZ=UTC (as in production) on 127.0.0.1:3000.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
: "${NEST_DIR:?built copy of reference/nest-api}" "${NEST_DB:?Nest DATABASE_URL}"
SPRING_URL="${SPRING_URL:-http://127.0.0.1:3031}"
NEST_PORT="${NEST_PORT:-3000}"
NEST_LOG="${NEST_LOG:-${TMPDIR:-/tmp}/parity-nest.log}"
BATCH_LOGINS="${BATCH_LOGINS:-8}"
export THROTTLE_STAFF_MULTIPLIER="${THROTTLE_STAFF_MULTIPLIER:-1000}"

stop_nest() {
  if command -v taskkill > /dev/null; then
    pid=$(netstat -ano | grep -E ":$NEST_PORT .*LISTEN" | awk '{print $5}' | head -1)
    [ -n "$pid" ] && taskkill //PID "$pid" //F > /dev/null 2>&1
  else
    pkill -f "node .*dist/main.js" > /dev/null 2>&1
  fi
  return 0
}

start_nest() {
  stop_nest
  (cd "$NEST_DIR" && DATABASE_URL="$NEST_DB" PORT="$NEST_PORT" TZ=UTC NODE_ENV=development RLS_ALLOW_SUPERUSER=true \
    nohup node --max-http-header-size=65536 dist/main.js > "$NEST_LOG" 2>&1 &)
  for _ in $(seq 1 60); do
    grep -aq "ERP API listening" "$NEST_LOG" 2> /dev/null && return 0
    sleep 2
  done
  echo "Nest did not start, see $NEST_LOG" >&2
  exit 2
}

logins=0
start_nest
for file in "$here"/scenarios/*.json; do
  name="$(basename "$file" .json)"
  n=$(grep -c 'auth/login\|auth/pin' "$file")
  if [ $((logins + n)) -gt "$BATCH_LOGINS" ]; then
    start_nest
    logins=0
  fi
  logins=$((logins + n))
  NEST_URL="http://127.0.0.1:$NEST_PORT" SPRING_URL="$SPRING_URL" node "$here/run.mjs" --module "$name"
done
stop_nest
