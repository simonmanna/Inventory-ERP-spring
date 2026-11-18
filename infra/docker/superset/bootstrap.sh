#!/usr/bin/env bash
# Superset start-up for the ERP stack (ADR-S078): migrate the metadata DB, make sure the admin, the roles and the
# embed service account exist, then hand over to the image's own server script. Safe to run on every start.
set -euo pipefail

superset db upgrade
if [ -n "${SUPERSET_ADMIN_PASSWORD:-}" ]; then
  superset fab create-admin --username "${SUPERSET_ADMIN_USERNAME:-admin}" --firstname Superset --lastname Admin \
    --email "${SUPERSET_ADMIN_EMAIL:-admin@erp.invalid}" --password "${SUPERSET_ADMIN_PASSWORD}" >/dev/null 2>&1 || true
fi
superset init
python /app/docker/erp/bootstrap_roles.py

exec /usr/bin/run-server.sh
