# IPR Phase 5: R7 recovery rehearsals (2026-11-28)

Requirement R7 (recovery and migration safety), gate G5. Both rehearsals ran on **throwaway restored copies** of the
latest live full backup, `POS-CAFE_FULL_2026-11-28_020001.dump` (6.6 MB, checksum verified by `restore-copy.sh`).
Nothing connected to the live database, the live API (3001) or the live files.

- **Spare ports:** 5711/3911 for the migration rehearsal, 5712/3912 for the failed-job rehearsal.
- **Environment:** the jars ran with an env file that copies only the live `ENABLE_*` flags and the timezone. Every
  secret (JWT, encryption key, seed password) was freshly generated. Mail, SMS, Superset and backups were off.
- **Clean-up:** both copies were removed afterwards. Only the evidence below is kept.

| Jar | Build |
|---|---|
| Candidate | `bootJar` of `fix/ipr-p15` (PR #86) at 17c85478 |
| Previous | the live `/opt/inventory-erp/app.jar` (main 77daa9fb) |

## 1. Migration apply and rollback (`scripts/ipr/rehearse-migration.sh`)

The final run passed all steps (`ALL STEPS PASSED (V696 -> V722 -> V696)`):

| Step | Result |
|---|---|
| 1 restore | PASS: Flyway head V696, 917 tables |
| 2 pre-upgrade dump of the copy | PASS (6.4 MB) |
| 3 static additivity scan | PASS. Every new migration is additive: V700, V701, V702, V715, V722 |
| 4 candidate `--migrate-only` | PASS: V696 → V722, 5 applied, none failed |
| 5 candidate API on the migrated copy | PASS: ready in about 65 s, 0 ERROR log lines |
| 6 previous jar on the migrated schema (Flyway off, Hibernate validate) | PASS: ready in about 60 s, 0 ERROR lines. The old release runs on the new schema |
| 7 `rollback.sh --restore-db` semantics on the copy | PASS: head V696, row counts of all 917 tables identical to step 1 |
| 8 previous jar on the restored copy | PASS: `--migrate-only` "up to date", ready, 0 ERROR lines |

Health is reported as `degraded`, with `criticalOk: true`. That is because the copy's scratch backup folder holds no
full backup. It is expected on a copy and has no bearing on the result.

The smoke login was skipped, because the copy's credentials were not used. Step 6 is therefore proven by Hibernate
`validate` plus readiness. The failed-job rehearsal below exercised the candidate's routes on production data.

**Script defects the first real runs found** (the script had been checked only with `bash -n` in Phase 0). All are
fixed in this PR:
1. **Heap too small.** Every jar ran with `-Xmx700m` and the candidate ran out of heap at startup on a copy of live.
   The heap is now `JAVA_HEAP`, default `1536m`, the live `api.env` value.
2. **A step could pass against the wrong jar.** `stop_api` killed the background wrapper shell, not the jar. The
   candidate kept answering on 3911, so step 6 reported "ready" 0.2 s after step 5, against the candidate. Step 7's
   `dropdb` then failed, because the database was still in use. Now:
   - `stop_api` and the exit trap stop the jar (the wrapper's child) and wait until the port is closed;
   - `start_api` refuses to start when the port already answers.
3. **False positives in the additivity scan.** `ALTER COLUMN "countType" SET DEFAULT` matched `type` inside the column
   name, and `ADD COLUMN … NOT NULL DEFAULT false` was flagged although a default keeps the previous jar's inserts
   working. Now:
   - `TYPE` must be a separate word;
   - `NOT NULL` is flagged only without a `DEFAULT`.

   A drop, a type change, `SET NOT NULL`, an `UPDATE`/`DELETE`/`TRUNCATE` or a rename is still flagged; checked against
   samples.

## 2. Failed stock-posting job recovery (`scripts/ipr/rehearse-failed-job.md`)

Org DEMO on the copy, with the candidate jar and the stock-posting worker on (`APP_JOBS_ENABLED=true`).

- **Login:** the DEMO admin's password was replaced on the copy only. The secret file was deleted afterwards.
- **Baseline:** every blocking integrity check green; report-only `negative_quants` (3 rows, pre-existing) and
  `feature_risk` (7 rows); Posting Monitor `health: ok`; `erp_stock_posting_jobs_failed 0`.

| Scenario | Result |
|---|---|
| Sale INV-2026-000017: cement ×5 (A) + recipe-less menu item ×2 (M) | 1st attempt: the A line posted (one row −5, key recorded); M failed "no recipe (BOM)"; job `pending`, attempts 1 |
| Deterministic crash: job set to `processing` with a dead claim | Left alone while the claim was younger than 60 s. Then taken over: A **not** issued again, M failed, job `failed` (attempts 2/2) |
| Failure visibility | `jobs?status=failed` lists it with `lastError`; **one** open exception (not one per attempt); bell "Stock posting needs review" once each to the 3 holders of `pos:reports`; `erp_stock_posting_jobs_failed 1.0` |
| Retry after adding a recipe to M | **Still fails.** The job carries the recipe as at checkout (`recipeSnapshot`, `"ingredients": []`; Nest parity). A still issued only once. The runbook said this retry would post; corrected (see below) |
| Retry with a cause fixed outside the sale | Sale INV-2026-000018: Generic Widget ×150 (on hand 100, `block` policy, negative stock off) failed 3/3 "Insufficient stock". An adjustment (+100) brought stock to 200, entered at the current cost 6. Retry → `201 pending` → `done` within 11 s: one row −150; exception `resolved` "Posted automatically when the stock posting job retried this line". A second retry → `400 … (this one is done)` |
| Abandon | A 9-character note → `400`. Abandon INV-2026-000017 with a note → `201 abandoned, exceptionsDiscarded: 1`. A retry → `400 … (this one is abandoned)`. Never claimed again |
| **Real crash:** 25 one-line tickets, `kill -9` of the API with 10 jobs `done` and 15 `processing` | 10 rows committed before the kill. After the restart the 15 claims waited for the ShedLock `stock-posting-drain` lock of the killed process (`lockAtMostFor` 5 min: kill 05:08:00, lock expired 05:13:00). All 15 posted at 05:13:03. **25 invoices, 25 rows, −25, at most 1 row per invoice** |

Final checks:
- **Stock:** cement 600 → 570 (−5 −25); Widget 100 → 200 → 50.
- **Blocking integrity checks:** all green.
- **`negative_quants`:** the same 3 products as at baseline (none of the rehearsal's).
- **`feature_risk` 7 → 8:** the new row is S6 "lot products and adjustments". DEMO now has an adjustment, so the dynamic
  feature map (R6) raised the open P2, as designed.
- **Posting Monitor:** no job is pending, processing or failed; `erp_stock_posting_jobs_failed 0.0`.
- **`api.log` ERROR lines:** only the forced ones (3 × Widget "Insufficient stock", 4 × "no recipe"), plus the health
  monitor's "stock posting unhealthy … 1 failed" while a job was failed.

**Runbook corrections** (`scripts/ipr/rehearse-failed-job.md`):
- **Step 3 rewritten.** A failure caused by a missing recipe cannot be cleared by retry, because the job keeps its
  recipe snapshot. Its exit is abandon plus a manual adjustment. Retry is rehearsed with a cause outside the sale
  (stock arrived late).
- **Expect a pause of up to 5 minutes** before the takeover after a real crash (the ShedLock `lockAtMostFor`). No
  action is needed.
- **Setup details:** the menu item needs a catalog price; cash tenders must equal the amount due; lower `maxAttempts`
  only on the rehearsal's own job.

**Owner option (sign-off item 20):** a retry could refresh a snapshot whose recipe is empty from the current recipe.
This is a deviation from Nest, and it would let an operator fix a forgotten recipe without abandoning. It is not
built.

## Not done in Phase 5

- `scripts/dr-drill.mjs` (OPERATIONS §7a): it needs a compose stack, and the host runs the live deploy. The backup
  restore path is covered by `restore-copy.sh` step 1 (checksum, restore, table and row counts).
- The parity scenarios against the Nest reference.
- The DEMO browser test of the new screens.
