# Rehearsal: failed stock-posting job recovery (IPR R7)

Scripted in Phase 0, rehearsed in Phase 5 (gate G5). Run it on a **restored copy only** (`restore-copy.sh`), never
on live. The automated proof of the same property is `PosStockPostingRecoveryIT` (backend tests); this rehearsal
shows that the real jar, the real worker and the Posting Monitor routes behave the same on production data.

**Property to prove.** A POS sale's stock is issued exactly once per line, whatever happens to the runner. A runner
can crash mid-drain, a line can keep failing, and an operator can retry or abandon the job. Afterwards the integrity
report shows no new finding on the products involved.

How the engine guarantees it (code references are for the reviewer):

| Guard | Where |
|---|---|
| A runner holds the job's claim (`status = processing` + its own `claimToken`). A claim older than 60 s is presumed dead and may be taken over. | `PosInvoiceService.claim`, `STALE_CLAIM_MS` |
| Each line commits in its own transaction together with its key in `postedLineKeys`, checked under the job row lock. A retry skips posted keys. | `PosInvoiceService.postOnce` |
| Completion and failure writes apply only while the claim is still the runner's own. | `processStockPostingJob`, the `FOR UPDATE` re-read |
| After `maxAttempts` the job is `failed`. Retry (`pos:override`) puts it back to `pending` with attempts reset. Abandon (note ≥ 10 characters) is final: never claimed again, and its open exceptions are discarded. | `PosStockPostingService`, `StockPostingController` |
| A line that posts on a retry closes its earlier open exception (`resolved`, with an automatic note). | `postOnce` |

## 0. Setup

1. Restore the latest backup into a copy on a spare port (example 5711):
   `scripts/ipr/restore-copy.sh /path/backup.dump 5711`
2. Start the jar under test on the copy, with the worker ON and everything external OFF. Example for the native
   layout (run from the repo root; use a scratch `WORK` directory):

   ```bash
   WORK=$PWD/var/ipr-failed-job; mkdir -p "$WORK/storage" "$WORK/backups"
   ( set -a; . /etc/inventory-erp/api.env      # feature flags only; everything below overrides it
     PORT=3911 JDBC_DATABASE_URL='jdbc:postgresql://127.0.0.1:5711/erp?stringtype=unspecified'
     DB_USER=erp_app DB_PASSWORD=rehearsal-app FLYWAY_USER=owner FLYWAY_PASSWORD=owner SPRING_FLYWAY_ENABLED=true
     DB_BACKUP_USER= DB_BACKUP_PASSWORD= PGUSER=owner PGPASSWORD=owner PGHOST=127.0.0.1 PGPORT=5711
     APP_JOBS_ENABLED=true BACKUP_CRON_ENABLED=false BACKUP_DIR=$WORK/backups STORAGE_LOCAL_DIR=$WORK/storage
     SMTP_HOST= TWILIO_ACCOUNT_SID= TWILIO_AUTH_TOKEN= SUPERSET_URL= SUPERSET_PUBLIC_URL= METRICS_TOKEN=rehearsal
     set +a; exec /opt/inventory-erp/jdk/bin/java -Xmx700m -jar /opt/inventory-erp/dist/inventory-erp.jar
   ) > "$WORK/api.log" 2>&1 &
   echo $! > "$WORK/api.pid"
   until curl -fsS http://127.0.0.1:3911/api/v1/health/ready; do sleep 5; done
   ```

   `APP_JOBS_ENABLED=true` is needed because the stock-posting worker is a scheduled job. Other jobs then run against
   the copy too. That is acceptable on a copy, and mail, SMS and the integrations are switched off above. If the live
   env file holds other outbound integrations (payment gateway, telematics), blank them as well.
3. Log in to org **DEMO** (owner rule: demo data only) as a user holding `pos:reports`, `pos:override` and
   `inventory_valuation:read`. Keep the token in `$T`. Below, `$API` is `http://127.0.0.1:3911/api/v1` and `$PSQL` is
   `docker exec -i ierp-ipr-restore-5711 psql -X -U owner -d erp`.
4. Baseline, saved to `$WORK`:
   - `GET $API/inventory/reports/integrity` → `integrity-before.json`
   - `GET $API/pos/posting-monitor/counts` → `counts-before.json`
   - `curl -H "Authorization: Bearer rehearsal" http://127.0.0.1:3911/api/v1/metrics | grep -E 'erp_stock_posting|erp_inventory_integrity'`

## 1. Force a failing line

Pick two stockable products of DEMO with stock, A and B, at the POS warehouse. Create a menu item M that is
inventory-tracked but has **no recipe**. A sale of M then fails on every attempt, with the message "Menu item is
inventory-tracked but has no recipe (BOM)". Create M through the back office (Menu → new item, "Track inventory" on,
no ingredients), or on the copy with SQL:

```sql
INSERT INTO "MenuItem" (id, "organizationId", name, "isInventoryTracked", "updatedAt")
VALUES (gen_random_uuid()::text, (SELECT id FROM "Organization" WHERE code = 'DEMO'), 'R7 rehearsal latte', true, now())
RETURNING id;
```

To shorten the rehearsal, lower the attempts on the copy:
`UPDATE "StockPostingJob" SET "maxAttempts" = 2 WHERE status = 'pending';`
Run it after the sale below; new jobs default to 5 attempts.

## 2. Sell, and kill the API mid-drain

Sell from a till of the copy: one line of A (qty 5) and one line of M (qty 2). Use the POS screen pointed at port
3911, or `POST $API/pos/checkout`. Note the order and invoice numbers. The sale enqueues one `StockPostingJob`.

**Real crash (B).** For a backlog, sell about 30 such tickets, then wait until the worker has started draining:
`$PSQL -c "SELECT status, count(*) FROM \"StockPostingJob\" GROUP BY 1"` shows `processing`. Then
`kill -9 $(cat $WORK/api.pid)`. Restart the API (step 0.2) and keep the new pid.

**Deterministic crash (A).** When the timing of a real kill misses, simulate the state a dead runner leaves behind
(its claim and the lines it already committed):

```sql
UPDATE "StockPostingJob" SET status = 'processing', "claimToken" = 'dead-runner',
       "claimedAt" = now() - interval '2 minutes'
WHERE "invoiceNumber" = '<invoice>';
```

Expected:
- While the dead claim is younger than 60 s, nothing touches the job.
- After that, the worker (every 30 s) takes the job over. The A line was already committed, so it is **not** issued
  again. The M line fails again.
- After `maxAttempts` the job is `failed`. `GET $API/pos/posting-monitor/jobs?status=failed` lists it with
  `lastError`, and `GET $API/pos/posting-monitor/exceptions` shows one open exception for line M (not one per
  attempt).
- Users holding `pos:reports` see "Stock posting needs review" in their bell (IPR T08), at most once an hour.
- `erp_stock_posting_jobs_failed` ≥ 1, and alert `StockPostingJobsFailed` fires after 15 min if Prometheus is attached.

## 3. Retry from the Posting Monitor

1. Fix the cause. Give M a recipe: back office, or
   `INSERT INTO "MenuProduct" (id, "organizationId", "menuItemId", "productId", quantity) VALUES (gen_random_uuid()::text, <DEMO>, <M>, <B>, 1);`
2. `POST $API/pos/posting-monitor/jobs/<id>/retry`. Expected: `201`, `status: pending`, attempts reset.
3. Within 30 s the worker posts **only** the missing line. The job is `done`, and M's exception is `resolved` with
   the note "Posted automatically when the stock posting job retried this line".
4. Retry again → `400 Only failed or pending jobs can be retried (this one is done)`.

## 4. Abandon a second job

Repeat steps 1–2 with a new menu item M2 (no recipe) and a product C, until the job is `failed`. Then
`POST $API/pos/posting-monitor/jobs/<id>/abandon` with `{"note": "R7 rehearsal: stock corrected by adjustment"}`.
Expected: `201`, `status: abandoned`, `exceptionsDiscarded: 1`. Add a recipe to M2 afterwards: the job never runs
again, and C's posted line stays posted once.

## 5. Verify exactly once

For each rehearsal invoice (`<ref>` = invoice id, or the order id when the job carries no invoice):

```sql
-- one issue row per posted line, never two
SELECT l."productId", count(*) AS rows, sum(l."quantityChange") AS qty
FROM "InventoryLedger" l
WHERE l."organizationId" = (SELECT id FROM "Organization" WHERE code = 'DEMO')
  AND l."referenceId" IN ('<ref1>', '<ref2>') AND l."quantityChange" < 0
GROUP BY 1 ORDER BY 1;
-- every job finished: done or abandoned, none still processing, keys = posted lines
SELECT "invoiceNumber", status, attempts, "postedLineKeys" FROM "StockPostingJob" WHERE "invoiceNumber" IN ('<inv1>', '<inv2>');
```

Pass criteria:
- A: one issue row, qty −5. B: one issue row, qty −2 (from the retried M line). C: one issue row. No issue row for M2.
- On-hand of A, B and C moved by exactly those quantities from the baseline.
- `GET $API/inventory/reports/integrity` → `integrity-after.json`. No check lists A, B or C that did not already list
  them in `integrity-before.json`. (Compare with `jq`: for each failing check, the product ids in its rows.)
- `GET /pos/posting-monitor/counts`: no job of the rehearsal is `pending`, `processing` or `failed`.
- `api.log` holds no ERROR line other than the expected "issue failed … has no recipe" lines.

Record the outcome, the invoice numbers and the files in `$WORK` in the Phase 5 gate evidence. Then stop the API
(`kill $(cat $WORK/api.pid)`) and remove the copy (`docker rm -f ierp-ipr-restore-5711`).
