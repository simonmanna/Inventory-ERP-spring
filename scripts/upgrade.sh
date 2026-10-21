#!/usr/bin/env bash
# Upgrade a docker-compose deployment of Inventory-ERP-spring, with automatic rollback.
#
#   scripts/upgrade.sh                 # rebuild from the current checkout and roll forward
#   IMAGE_TAG=v1.0.1 scripts/upgrade.sh --pull   # use published images (docker-compose.prod.yml)
#
# Steps: preflight → pre-upgrade database dump (pg_dump inside the db container) → tag the running images as
# :rollback → build/pull → recreate api + web (Flyway migrates on boot) → wait for /api/v1/health/ready.
# If the new version does not become ready, the previous images are restored automatically. The database is NOT
# rolled back automatically (Flyway migrations are forward-only); run scripts/rollback.sh --restore-db <dump> after
# reading docs/OPERATIONS.md §6 if a migration must be undone.
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=${COMPOSE:-"docker compose"}
READY_URL=${READY_URL:-"http://127.0.0.1:3001/api/v1/health/ready"}
READY_TIMEOUT=${READY_TIMEOUT:-240}
BACKUP_DIR=${UPGRADE_BACKUP_DIR:-"./var/upgrade-backups"}
PULL=0
[[ "${1:-}" == "--pull" ]] && PULL=1

log() { printf '[upgrade %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

command -v docker >/dev/null || die "docker is not installed"
$COMPOSE ps db --format '{{.State}}' 2>/dev/null | grep -q running || die "database container 'db' is not running"

mkdir -p "$BACKUP_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP="$BACKUP_DIR/pre-upgrade-$STAMP.dump"
log "Dumping the database to $DUMP"
$COMPOSE exec -T db sh -c 'pg_dump --format=custom --no-owner -U "$POSTGRES_USER" "$POSTGRES_DB"' > "$DUMP"
[[ -s "$DUMP" ]] || die "pre-upgrade dump is empty — aborting before touching anything"
sha256sum "$DUMP" > "$DUMP.sha256" 2>/dev/null || shasum -a 256 "$DUMP" > "$DUMP.sha256"

for svc in api web; do
  img=$($COMPOSE images -q "$svc" 2>/dev/null | head -n1 || true)
  if [[ -n "$img" ]]; then
    docker tag "$img" "inventory-erp-$svc:rollback"
    log "Tagged current $svc image $img as inventory-erp-$svc:rollback"
  fi
done
echo "$DUMP" > "$BACKUP_DIR/last-upgrade-dump"

if [[ $PULL -eq 1 ]]; then
  log "Pulling images (IMAGE_TAG=${IMAGE_TAG:-latest})"
  $COMPOSE pull api web
else
  log "Building images from this checkout"
  $COMPOSE build api web
fi

log "Recreating api and web"
$COMPOSE up -d --no-deps api web

log "Waiting up to ${READY_TIMEOUT}s for $READY_URL"
deadline=$(( $(date +%s) + READY_TIMEOUT ))
until curl -fsS "$READY_URL" >/dev/null 2>&1; do
  if (( $(date +%s) > deadline )); then
    log "New version did not become ready — rolling back the images"
    $COMPOSE logs --tail=200 api || true
    scripts/rollback.sh
    die "upgrade failed; previous version restored (database untouched; dump at $DUMP)"
  fi
  sleep 5
done
log "Upgrade complete. Pre-upgrade dump: $DUMP"
