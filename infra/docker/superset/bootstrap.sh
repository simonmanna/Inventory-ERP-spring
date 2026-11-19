#!/usr/bin/env bash
# Superset start-up for the ERP stack (ADR-S078): migrate the metadata DB, make sure the admin, the roles and the
# embed service account exist, then hand over to the image's own server script. Safe to run on every start.
set -euo pipefail

# Generated secrets (gen-secrets.sh, compose service bi-secrets) fill in whatever .env left empty.
if [ -r /bi/secrets.env ]; then
  set -a
  # shellcheck disable=SC1091
  . /bi/secrets.env
  set +a
fi
if [ -z "${SUPERSET_METADATA_DB_URI:-}" ]; then
  export SUPERSET_METADATA_DB_URI="postgresql+psycopg2://superset_meta:${SUPERSET_META_DB_PASSWORD}@${SUPERSET_DB_HOST:-db}:5432/superset"
fi

superset db upgrade
if [ -n "${SUPERSET_ADMIN_PASSWORD:-}" ]; then
  superset fab create-admin --username "${SUPERSET_ADMIN_USERNAME:-admin}" --firstname Superset --lastname Admin \
    --email "${SUPERSET_ADMIN_EMAIL:-admin@erp.invalid}" --password "${SUPERSET_ADMIN_PASSWORD}" >/dev/null 2>&1 || true
fi
superset init
python /app/docker/erp/bootstrap_roles.py

exec /usr/bin/run-server.sh
