#!/bin/sh
# One-shot (bi-secrets service, ADR-S078): makes the embedded Superset work with no configuration. Writes
# /bi/secrets.env once, with every Superset secret the stack needs. A value set in .env wins and is written as is; a
# value already in the file is kept (Superset's SECRET_KEY must never change once dashboards exist); anything else is
# generated. The file lives on the private `bi` volume, mounted only into this stack's containers: superset-db-init,
# superset, superset-provision (read/write) and the API (read-only, for the embed service account).
set -eu
FILE=/bi/secrets.env
touch "$FILE"

existing() { sed -n "s/^$1=//p" "$FILE" | tail -n 1; }
random() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-40}"; }

out=$(mktemp)
for key in SUPERSET_SECRET_KEY SUPERSET_GUEST_TOKEN_SECRET SUPERSET_META_DB_PASSWORD SUPERSET_READER_PASSWORD \
    SUPERSET_ADMIN_PASSWORD SUPERSET_EMBED_USERNAME SUPERSET_EMBED_PASSWORD; do
  eval "given=\${$key:-}"
  value="$given"
  [ -n "$value" ] || value=$(existing "$key")
  if [ -z "$value" ]; then
    case "$key" in
      SUPERSET_EMBED_USERNAME) value=erp_embedder ;;
      SUPERSET_ADMIN_PASSWORD) value=$(random 20) ;;
      *) value=$(random 48) ;;
    esac
  fi
  echo "$key=$value" >>"$out"
done
cat "$out" >"$FILE"
rm -f "$out"
# Readable by the other containers' users (superset, node, the API's app user); the volume itself is private.
chmod 644 "$FILE"
echo "Superset secrets are in place (the admin password is SUPERSET_ADMIN_PASSWORD in the bi volume's secrets.env)"
