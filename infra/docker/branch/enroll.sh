#!/bin/sh
# ADR-S083 O7: registers this branch server at HQ once and writes its settings to /enroll/branch.env.
# Inputs (environment): HQ_URL (…/api/v1), HQ_ORG_CODE, HQ_ADMIN_EMAIL, HQ_ADMIN_PASSWORD, BRANCH_CODE, BRANCH_NAME.
# An existing /enroll/branch.env is kept (the branch token is a server credential; revoke it at HQ to replace it).
set -eu
OUT=/enroll/branch.env
if [ -s "$OUT" ]; then
  echo "branch-enroll: $OUT exists, nothing to do"
  exit 0
fi
: "${HQ_URL:?HQ_URL is required}" "${HQ_ORG_CODE:?}" "${HQ_ADMIN_EMAIL:?BRANCH_HQ_ADMIN_EMAIL is required}" \
  "${HQ_ADMIN_PASSWORD:?BRANCH_HQ_ADMIN_PASSWORD is required}" "${BRANCH_CODE:?BRANCH_CODE is required}"
apk add --no-cache curl jq >/dev/null

echo "branch-enroll: waiting for HQ at $HQ_URL"
i=0
until curl -fsS "$HQ_URL/health/live" >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -ge 150 ] && { echo "branch-enroll: HQ not reachable after 5 minutes" >&2; exit 1; }
  sleep 2
done

TOKEN=$(jq -n --arg o "$HQ_ORG_CODE" --arg e "$HQ_ADMIN_EMAIL" --arg p "$HQ_ADMIN_PASSWORD" \
    '{organizationCode: $o, email: $e, password: $p}' \
  | curl -fsS -H 'Content-Type: application/json' -d @- "$HQ_URL/auth/login" | jq -r .accessToken)
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || { echo "branch-enroll: HQ login failed" >&2; exit 1; }

BRANCH=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$HQ_URL/branches?pageSize=100" \
  | jq -c --arg c "$BRANCH_CODE" '[.data[] | select(.code == $c)][0]')
[ "$BRANCH" != null ] || { echo "branch-enroll: no branch with code $BRANCH_CODE at HQ" >&2; exit 1; }
BRANCH_ID=$(echo "$BRANCH" | jq -r .id)
ORG_ID=$(echo "$BRANCH" | jq -r .organizationId)

DEVICE=$(jq -n --arg n "${BRANCH_NAME:-Branch server $BRANCH_CODE}" --arg b "$BRANCH_ID" \
    '{name: $n, platform: "branch", branchId: $b}' \
  | curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d @- \
      "$HQ_URL/sync/devices/register")
DEVICE_TOKEN=$(echo "$DEVICE" | jq -r .deviceToken)
[ -n "$DEVICE_TOKEN" ] && [ "$DEVICE_TOKEN" != null ] || { echo "branch-enroll: registration failed: $DEVICE" >&2; exit 1; }

umask 077
cat > "$OUT.tmp" <<ENV
export ERP_SYNC_BRANCH_TOKEN='$DEVICE_TOKEN'
export ERP_SYNC_BRANCH_ORGANIZATION_ID='$ORG_ID'
export BRANCH_ID='$BRANCH_ID'
ENV
chmod 644 "$OUT.tmp" # read by the API container's non-root user
mv "$OUT.tmp" "$OUT"
echo "branch-enroll: registered branch device $(echo "$DEVICE" | jq -r .id) for branch $BRANCH_CODE"
