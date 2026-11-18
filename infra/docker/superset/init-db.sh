#!/bin/sh
# One-shot, idempotent database set-up for the embedded Superset (ADR-S078), run by the superset-db-init service with
# the database owner's credentials:
#   - superset_meta: a plain role owning Superset's own metadata database `superset` (dashboards, charts, users),
#   - superset_reader: the read-only login Superset uses for the ERP `bi` views (grants come from V570/afterMigrate).
# Superset itself never gets the owner's password.
set -eu
: "${SUPERSET_META_DB_PASSWORD:?set SUPERSET_META_DB_PASSWORD}"
: "${SUPERSET_READER_PASSWORD:?set SUPERSET_READER_PASSWORD}"
export PGPASSWORD="$POSTGRES_PASSWORD"
run() { psql -h "${PGHOST:-db}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -q "$@"; }

run -v meta="$SUPERSET_META_DB_PASSWORD" -v reader="$SUPERSET_READER_PASSWORD" <<'SQL'
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'superset_meta') AS create_meta \gset
\if :create_meta
  CREATE ROLE superset_meta LOGIN;
\endif
ALTER ROLE superset_meta LOGIN PASSWORD :'meta' NOSUPERUSER NOCREATEDB NOCREATEROLE;

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'superset_reader') AS create_reader \gset
\if :create_reader
  CREATE ROLE superset_reader NOLOGIN;
\endif
ALTER ROLE superset_reader LOGIN PASSWORD :'reader' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
  CONNECTION LIMIT 20;
ALTER ROLE superset_reader SET statement_timeout = '60s';
ALTER ROLE superset_reader SET default_transaction_read_only = on;

SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'superset') AS create_db \gset
\if :create_db
  CREATE DATABASE superset OWNER superset_meta;
\endif
SQL
echo "Superset databases and roles are in place"
