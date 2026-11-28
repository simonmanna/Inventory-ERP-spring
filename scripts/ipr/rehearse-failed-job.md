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
     set +a; exec /opt/inventory-erp/jdk/bin/java -Xmx1536m -jar /opt/inventory-erp/dist/inventory-erp.jar
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

Give M a catalog price (`UPDATE "MenuItem" SET "basePrice" = 500 WHERE id = '<M>'`); without one the checkout is refused
with "Configure a catalog selling price before selling this item".

To shorten the rehearsal, lower the attempts on the copy, for the rehearsal's own job only:
`UPDATE "StockPostingJob" SET "maxAttempts" = 2 WHERE "invoiceNumber" = '<invoice>' AND status = 'pending';`
Run it right after the sale below (the worker's first attempt comes within 30 s); new jobs default to 5 attempts.

## 2. Sell, and kill the API mid-drain

Sell from a till of the copy: one line of A (qty 5) and one line of M (qty 2). Use the POS screen pointed at port
3911, or `POST $API/pos/checkout` (an `Idempotency-Key` header is required; cash tenders must equal the amount due:
more is refused with "Tenders sum … exceeds amount due …"). Note the order and invoice numbers. The sale enqueues one
`StockPostingJob`.

**Real crash (B).** For a backlog, sell about 30 such tickets, then wait until the worker has started draining:
`$PSQL -c "SELECT status, count(*) FROM \"StockPostingJob\" GROUP BY 1"` shows `processing`. Then
`kill -9 $(cat $WORK/api.pid)`. Restart the API (step 0.2) and keep the new pid.

Expect a pause before the takeover. The drain runs under the ShedLock lock `stock-posting-drain`
(`lockAtMostFor = PT5M`), and the killed process still holds it: no runner drains until it expires, up to **5 minutes**
after the run the process was killed in started (`SELECT * FROM shedlock WHERE name = 'stock-posting-drain'` shows
`lock_until`). Then the claims, older than 60 s by then, are taken over in the next drain. Nobody needs to act. On
2026-11-28 the kill came at 05:08:00 with 15 jobs `processing`; the lock expired at 05:13:00 and all 15 posted at
05:13:03, one issue row each.

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

**Retry does not pick up a recipe added after the sale.** The job carries the recipe as it was at checkout
(`StockPostingJob.recipeSnapshot`, the same as Nest `pos-invoice.service.ts`), and a retry reads that snapshot. A
menu line that failed for "no recipe" has a snapshot with `"ingredients": []`, so adding the recipe and retrying fails
again (rehearsed 2026-11-28: the retried job went back to `failed`, and the A line was still not issued twice). The exit
for that job is **abandon** (step 4) plus a manual stock-out adjustment for the ingredients. Retry is for causes that
are fixed outside the sale: stock that arrived late, a product that was blocked, a closed period that was reopened.

Rehearse retry with such a cause, on a product line:

1. On the copy: turn negative stock off (`PUT $API/costing/settings` `{"allowNegativeStock": false}`), set a product
   B with on-hand `n` to the `block` stock policy (`UPDATE "Product" SET "stockPolicy" = 'block' WHERE id = '<B>'`),
   and sell `n + 50` of B. The sale is kept, and each attempt fails with "Insufficient stock for … on hand n,
   requested n + 50". Let the job reach `failed`.
2. Fix the cause: an adjustment that brings B to at least the sold quantity (`POST $API/inventory/adjustments`, then
   `POST $API/inventory/adjustments/<id>/approve`).
3. `POST $API/pos/posting-monitor/jobs/<id>/retry`. Expected: `201`, `status: pending`, attempts reset.
4. Within 30 s the worker posts **only** the missing line. The job is `done`, and B's exception is `resolved` with
   the note "Posted automatically when the stock posting job retried this line".
5. Retry again → `400 Only failed or pending jobs can be retried (this one is done)`.
6. Turn negative stock back on.

## 4. Abandon the job that cannot post

Abandon the "no recipe" job from step 2: `POST $API/pos/posting-monitor/jobs/<id>/abandon` with
`{"note": "R7 rehearsal: stock corrected by adjustment"}`. Expected: a note under 10 characters → `400`; then `201`,
`status: abandoned`, `exceptionsDiscarded: 1`; a retry of it → `400 … (this one is abandoned)`. The job is never
claimed again, and A's posted line stays posted once.

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
- A: one issue row, qty −5 (and one row per crash ticket). B: one issue row for the retried sale. No issue row for M.
- On-hand of A and B moved by exactly those quantities (plus B's adjustment) from the baseline.
- `GET $API/inventory/reports/integrity` → `integrity-after.json`. No check lists A or B that did not already list
  them in `integrity-before.json`. (Compare with `jq`: for each failing check, the product ids in its rows.)
- `GET /pos/posting-monitor/counts`: no job of the rehearsal is `pending`, `processing` or `failed`.
- `api.log` holds no ERROR line other than the expected "issue failed …" lines for M and B, and the health
  monitor's "stock posting unhealthy … 1 failed" while a job was failed.

Record the outcome, the invoice numbers and the files in `$WORK` in the Phase 5 gate evidence. Then stop the API
(`kill $(cat $WORK/api.pid)`) and remove the copy (`docker rm -f ierp-ipr-restore-5711`).
