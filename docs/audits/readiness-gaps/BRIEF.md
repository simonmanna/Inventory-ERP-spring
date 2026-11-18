<!-- The brief the Phase B stream agents worked from on 2026-11-17. The Gradle lock only matters when several agents share one small machine. -->

# Phase B brief: fix the inventory and procurement readiness gaps (shared by every stream)

## Where things stand
- On 2026-11-17 a read-only audit found inventory + procurement NOT production-ready (7 auditor reports).
- Its 8 blockers are fixed and merged to `main` at `0772acb2` (read `docs/audits/2026-11-17-inventory-procurement-readiness.md`
  and `git show fe0793f4 --stat` to see what changed: PurchaseOrdersService pay/create, GoodsReceiptsService supplier,
  RfqService rates, WarehouseOutboundService manual-ref guard, ProductService tracking guard, staff-options, web Select).
- The owner has now asked to "fix and enhance the gaps": every remaining P1, and the P2s where reasonable. The owner's
  request approves the Nest-parity deviations these fixes need ("approved by the owner 2026-11-17").
- Several streams run in parallel, each on its own branch and worktree. A coordinator merges them afterwards.

## Read first
1. `CLAUDE.md` and `AGENTS.md` in your worktree. The hard rules apply: never edit `reference/`, `V1__baseline.sql`,
   `V2__reference_data.sql`; BigDecimal for money and quantities; `ApiException` with clear English messages; every
   query org-scoped; records not entities from controllers; no business logic in controllers.
2. The auditor report for your stream (see HANDOFF.md). Line numbers refer to `46281ee2`; `main` moved a little,
   so locate code with grep before editing.
3. `docs/spec/<module>.md` "Deviations" sections for your module, so you know what is already approved behaviour.

## Rules for this phase
- Work only on your stream's branch (see HANDOFF.md). Never touch `/home/Inventory-ERP-spring-1` (another session's
  uncommitted work) or any other worktree. Do not create new worktrees or branches; your branch already exists.
- Verify each finding in the code before fixing it. If it is not real, or already fixed, say so in your notes with the
  evidence. Do not "fix" what is not broken.
- Fix the root cause, not the symptom. Keep fixes proportionate: no new frameworks, no rewrites of working subsystems.
  If a P2 needs a large redesign, defer it with a one-paragraph reason and what the fix would be.
- Stay inside your file ownership (HANDOFF.md). Shared files (`SegregationOfDutiesService`, `SettingRegistry`,
  `ProcurementPolicyRegistry`, `GlobalExceptionHandler`, test support classes) may get small ADDITIVE changes only.
  Never reformat or reorder code you do not change, because other streams edit the same files.
- Do NOT add permissions (`Permissions.java` and `permission-tree.json` are generated from `packages/shared`). Reuse
  existing permission strings; segregation of duties (`SegregationOfDutiesService` pairs) and the approvals engine are
  the tools for maker-checker. If a fix truly needs a new permission, do not add it: note it for the coordinator.
- Migrations: only the version range in HANDOFF.md, `V{n}__snake_case.sql`. Never edit an existing migration.
- New error messages: short, specific, English, in the style of the existing messages ("X cannot be Y while Z; do W").
- Keep API responses backward compatible: no envelope changes, no renamed or removed fields, no changed status codes
  for success paths. Adding fields or optional query parameters is fine.
- Money routes: `@Idempotent(required = true)` where a retry could move stock or money twice.
- Do not edit `docs/spec/*.md`, `docs/PORT_STATUS.md` or ADRs. Instead write your notes file (see below); the
  coordinator consolidates them.

## Tests
- Add ONE new integration test class for your stream (see HANDOFF.md), extending `ApiTestSupport`; look at
  `backend/src/test/java/com/inventoryerp/procurement/ReadinessBlockersIT.java` for helpers and patterns
  (partners, products, POs, receipts, `money()` / `keyed()` for Idempotency-Key, `tokenWith(...)` for a user with
  specific permissions). Pure logic → plain JUnit test, no Spring context. No new `@MockitoBean` or profile combos.
- Every fixed P1 gets at least one test that fails without the fix. P2s: test where practical.
- Run your new class plus the existing test classes that cover the code you changed (find them with grep).

## Gradle — the machine is small (6 cores, ~4 GB free): ALWAYS go through the shared lock
```bash
LOCK=/tmp/erp-gradle.lock   # any shared path; serializes Gradle when several agents share one machine
cd <worktree>/backend
flock "$LOCK" timeout 1800 env JAVA_HOME=/opt/inventory-erp/jdk ./gradlew compileJava compileTestJava -q --no-configuration-cache
flock "$LOCK" timeout 2400 env JAVA_HOME=/opt/inventory-erp/jdk IT_EXTERNAL_DB=true \
  JDBC_DATABASE_URL='jdbc:postgresql://127.0.0.1:<port>/erp?stringtype=unspecified' \
  SPRING_FLYWAY_URL='jdbc:postgresql://127.0.0.1:<port>/erp?stringtype=unspecified' \
  DB_USER=erp DB_PASSWORD=erp FLYWAY_USER=owner FLYWAY_PASSWORD=owner \
  ./gradlew fastTest --no-configuration-cache --tests '*.YourGapsIT' --tests '*.OtherIT' > <scratch log> 2>&1
```
- NEVER run `./gradlew build`, `clean`, `check`, a bare `test`, `--stop`, or `docker compose`. Never run Gradle
  without the lock. Batch your edits: compile once, then test; do not compile after every file.
- Redirect test output to a log in the scratchpad and grep it (`FAILED|tests completed|BUILD`); the console has
  the test stdout. Never read `build/`, `.gradle/`, `node_modules/`, `var/`. For SQL errors:
  `docker logs <your container> --since 15m 2>&1 | grep -E "ERROR|STATEMENT"`.
- Your own Postgres (start it once; `owner` is a superuser, the app connects as `erp`, which Flyway's afterMigrate
  creates, so RLS is live in tests):
  ```bash
  docker run -d --name <container> -e POSTGRES_USER=owner -e POSTGRES_PASSWORD=owner -e POSTGRES_DB=erp \
    -p 127.0.0.1:<port>:5432 postgres:16-alpine -c max_locks_per_transaction=1024
  ```
  If a rerun fails on data an earlier run left behind (unique keys, counts), `docker rm -f <container>` and start it
  again. Remove your container when you are done.

## Web (only if your stream owns web files)
- `cd <worktree> && pnpm install --frozen-lockfile && pnpm --filter @erp/shared build` once, then
  `cd web && pnpm exec tsc --noEmit -p tsconfig.json` and `pnpm exec eslint <changed files>`.
- Money / stock actions use `idempotentPost` / `idempotentPatch` from `web/src/lib/idempotent-request.ts` (stable key
  per payload, survives retries). Do not touch `web/src/components/ui/select.tsx` (just fixed).

## Finish
1. Write `docs/audits/readiness-gaps/<stream>.md` in your worktree: for each finding of your scope, one entry with
   - ID (auditor report id, e.g. `h03-F4`) and title;
   - status: fixed / partly fixed / not reproduced / deferred (with the reason);
   - behaviour change: what the API or screen now does, new or changed error messages verbatim, new routes or
     parameters, migrations;
   - whether it deviates from Nest (`reference/nest-api`), so the coordinator can record it;
   - tests that cover it.
   End with "Cross-stream notes": anything another stream or the coordinator must know (a web change you need, a new
   permission you wanted, a shared file you touched).
2. `flock "$LOCK" env JAVA_HOME=/opt/inventory-erp/jdk ./gradlew spotlessApply -q --no-configuration-cache` (backend).
3. Commit everything on your branch (Conventional Commits, e.g. `fix(inventory): ...`; body lists the findings fixed).
   End the message with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do NOT merge or push.
4. Final reply (under 400 words): branch, commit SHA, a table of finding → status, the tests you ran with pass/fail
   counts, anything you deferred, and cross-stream notes. Say plainly if anything failed or was not run.
