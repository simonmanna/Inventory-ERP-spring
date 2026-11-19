#!/bin/sh
# One-shot (superset-provision service, ADR-S078): once Superset is healthy, create or update the dashboards and write
# their ids to /bi/dashboards.json, which the API reads (SUPERSET_DASHBOARDS_FILE). Idempotent; runs on every
# `docker compose up`, so dashboard changes in tools/superset-provision/dashboards ship with the stack.
set -eu
set -a
. /bi/secrets.env
set +a
export BI_DB_URI="${BI_DB_URI:-postgresql+psycopg2://superset_reader:${SUPERSET_READER_PASSWORD}@${BI_DB_HOST:-db}:5432/${BI_DB_NAME:-erp}}"
export SUPERSET_EMBED_ALLOWED_DOMAINS="${SUPERSET_EMBED_ALLOWED_DOMAINS:-$SUPERSET_FRAME_ANCESTORS}"
exec node /provision/provision.mjs --write-config /bi/dashboards.json
