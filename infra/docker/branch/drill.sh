#!/bin/sh
# ADR-S083 O7 drill on the compose HQ + branch stack: a till trades through the branch while HQ is up, while HQ is
# down, and after the branch database is restored from a backup taken before the outage; HQ must end with every sale
# posted once. Run from the repository root after:  docker compose --profile branch up -d --build
# Needs curl and jq. Uses DEMO (org code BRANCH_HQ_ORG) and the HQ admin from .env (BRANCH_HQ_ADMIN_*).
# DC overrides the compose command (another project or override file), e.g. DC="docker compose -p x -f a.yml -f b.yml".
set -eu
DC=${DC:-docker compose --profile branch}
HQ=${HQ:-http://127.0.0.1:3001/api/v1}
BR=${BR:-http://127.0.0.1:3003/api/v1}
ORG=${BRANCH_HQ_ORG:-DEMO}
: "${BRANCH_HQ_ADMIN_EMAIL:?}" "${BRANCH_HQ_ADMIN_PASSWORD:?}"
uuid() { cat /proc/sys/kernel/random/uuid; }
now() { date -u +%Y-%m-%dT%H:%M:%S.000Z; }
step() { printf '\n== %s\n' "$*"; }
wait_live() { # wait_live <api base> : an API restart takes minutes
  i=0; until curl -fsS "$1/health/live" >/dev/null 2>&1; do i=$((i + 1)); [ "$i" -ge 120 ] && { echo "$1 not live" >&2; exit 1; }; sleep 5; done
}
wait_drained() { # the branch relayed everything it holds (or 5 minutes)
  i=0; while [ "$(branch_status | jq .pendingHqPosting)" != 0 ]; do i=$((i + 1)); [ "$i" -ge 60 ] && break; sleep 5; done
}
branch_status() { curl -fsS -H "X-Device-Token: $TILL_TOKEN" "$BR/sync/branch/status"; }

TOKEN=$(jq -n --arg o "$ORG" --arg e "$BRANCH_HQ_ADMIN_EMAIL" --arg p "$BRANCH_HQ_ADMIN_PASSWORD" \
  '{organizationCode:$o,email:$e,password:$p}' | curl -fsS -H 'Content-Type: application/json' -d @- "$HQ/auth/login" \
  | jq -r .accessToken)
auth() { curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }
mkdir -p var
BRANCH_ID=$($DC exec -T branch-api sh -c '. /enroll/branch.env && echo "$BRANCH_ID"')
ADMIN=$(auth "$HQ/auth/me" | jq -r .id)

step "enrol a till at HQ for the branch, and a product"
TILL=$(auth -d "$(jq -n --arg b "$BRANCH_ID" '{name:"Drill till",platform:"android",branchId:$b}')" "$HQ/sync/devices/register")
TILL_TOKEN=$(echo "$TILL" | jq -r .deviceToken); TILL_ID=$(echo "$TILL" | jq -r .id)
SFX=$(uuid | cut -c1-6)
REG=$(auth -d "{\"code\":\"DRL-$SFX\",\"name\":\"Drill till $SFX\"}" "$HQ/cash-registers" | jq -r .id)
PROD=$(auth -d "{\"code\":\"DRL-$SFX\",\"name\":\"Drill tea\",\"productType\":\"service\",\"trackInventory\":false,\"salesPrice\":60,\"costPrice\":10}" "$HQ/products" | jq -r .id)
push() { # push <ops-json-array>
  curl -fsS -H "X-Device-Token: $TILL_TOKEN" -H 'X-Sync-Protocol: 2.0' -H 'Content-Type: application/json' \
    -d "{\"deviceId\":\"$TILL_ID\",\"ops\":$1}" "$BR/sync/push" | jq -c '[.results[] | {status, relayState, code}]'
}
SEQ_FILE=var/drill-$TILL_ID.seq
echo 0 > "$SEQ_FILE"
op() { # op <type> <payload-json>   (called in $(...) subshells: the till's sequence lives in a file)
  SEQ=$(($(cat "$SEQ_FILE") + 1)); echo "$SEQ" > "$SEQ_FILE"; echo "{\"opId\":\"$(uuid)\",\"deviceSeq\":$SEQ,\"type\":\"$1\",\"actorUserId\":\"$ADMIN\",\"occurredAt\":\"$(now)\",\"payload\":$2}"
}
sale() { echo "{\"clientId\":\"$(uuid)\",\"cashSessionId\":\"$SESSION\",\"expectedTotal\":60,\"lines\":[{\"productId\":\"$PROD\",\"description\":\"Drill tea\",\"quantity\":1,\"unitPrice\":60}],\"tenders\":[{\"method\":\"cash\",\"amount\":60}]}"; }

echo "waiting for the branch to learn the new till from HQ…"
i=0; until curl -fsS -H "X-Device-Token: $TILL_TOKEN" "$BR/sync/branch/status" >/dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -ge 60 ] && { echo "the branch never learned the till" >&2; exit 1; }; sleep 2; done
SESSION=$(uuid)
step "online: open + sale (HQ answers through the branch)"
push "[$(op cash_session.open "{\"clientId\":\"$SESSION\",\"cashRegisterId\":\"$REG\",\"openingFloat\":0}"),$(op sale.checkout "$(sale)")]"

step "backup of the branch database (before the outage)"
$DC exec -T branch-db pg_dump -U "${BRANCH_DB_USER:-erp}" -Fc "${BRANCH_DB_NAME:-erp_branch}" > var/branch-drill.dump

step "HQ down: two sales are held at the branch"
$DC stop api
HELD1=$(op sale.checkout "$(sale)")
HELD2=$(op sale.checkout "$(sale)")
push "[$HELD1,$HELD2]"
branch_status | jq '{pendingHqPosting, lastError}'

step "HQ back: the relay drains"
$DC start api
wait_live "$HQ"
wait_drained
branch_status | jq '{pendingHqPosting, outbox}'

step "branch crash: restore the pre-outage backup, restart"
$DC stop branch-api
$DC exec -T branch-db pg_restore -U "${BRANCH_DB_USER:-erp}" -d "${BRANCH_DB_NAME:-erp_branch}" --clean --if-exists < var/branch-drill.dump
$DC start branch-api
wait_live "$BR"
step "the till re-pushes a sale the restore lost from the branch (same opId: HQ replays it) and a new one"
push "[$HELD2,$(op sale.checkout "$(sale)")]"
wait_drained
branch_status | jq '{pendingHqPosting, outbox, hqSeqHighWater, relaySeq}'

step "HQ: invoices in the drill shift (expect 4: one per sale, none twice)"
auth "$HQ/sync/reconciliation/run" -d '{}' | jq '{status, issueCount, checks: [.checks[] | {key, ok, count}]}'
$DC exec -T db psql -U "${DB_USER:-erp}" -d "${DB_NAME:-erp}" -tAc \
  "SELECT count(*) FROM \"Invoice\" i JOIN \"CashSession\" s ON s.id = i.\"cashSessionId\" WHERE s.\"cashRegisterId\" = '$REG'"
