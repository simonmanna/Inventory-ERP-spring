#!/usr/bin/env bash
# Roll the api/web containers back to the images tagged :rollback by scripts/upgrade.sh.
#
#   scripts/rollback.sh                          # images only (database untouched)
#   scripts/rollback.sh --restore-db <dump>      # ALSO replace the database with a pre-upgrade dump
#
# --restore-db is destructive: it drops and recreates the live database from the dump, losing every write made after
# the dump was taken. It asks for confirmation unless ROLLBACK_CONFIRM=yes.
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=${COMPOSE:-"docker compose"}
log() { printf '[rollback %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

for svc in api web; do
  docker image inspect "inventory-erp-$svc:rollback" >/dev/null 2>&1 || die "no inventory-erp-$svc:rollback image"
done

if [[ "${1:-}" == "--restore-db" ]]; then
  DUMP=${2:-$(cat ./var/upgrade-backups/last-upgrade-dump 2>/dev/null || true)}
  [[ -f "$DUMP" ]] || die "dump not found: $DUMP"
  if [[ -f "$DUMP.sha256" ]]; then
    (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256") || die "dump checksum mismatch"
  fi
  if [[ "${ROLLBACK_CONFIRM:-}" != "yes" ]]; then
    read -r -p "Replace the LIVE database with $DUMP? Writes since then are lost. Type 'restore' to continue: " ans
    [[ "$ans" == "restore" ]] || die "aborted"
  fi
  log "Stopping api so nothing writes during the restore"
  $COMPOSE stop api
  $COMPOSE exec -T db sh -c 'dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" -T template0 "$POSTGRES_DB"'
  $COMPOSE exec -T db sh -c 'pg_restore --no-owner --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$DUMP"
  log "Database restored from $DUMP"
fi

log "Starting the previous images"
docker tag inventory-erp-api:rollback "$($COMPOSE config --images | grep -m1 -E -- '-api$' || echo inventory-erp-spring-api)" 2>/dev/null || true
docker tag inventory-erp-web:rollback "$($COMPOSE config --images | grep -m1 -E -- '-web$' || echo inventory-erp-spring-web)" 2>/dev/null || true
$COMPOSE up -d --no-deps --no-build api web
log "Rollback done"
