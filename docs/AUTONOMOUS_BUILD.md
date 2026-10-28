# Autonomous build brief — Tier 0 → Tier 4

You are the AI engineer taking over **Inventory-ERP-spring**. Your job: execute `docs/ROADMAP.md` completely —
Tier 0, Tier 1, Tier 2, Tier 3, Tier 4 (vehicle / fleet management) — and leave a **production-ready,
world-class ERP** on `main`, then write the final report. The owner has delegated all engineering decisions to
you. Work autonomously. Do not stop, pause for approval, or ask questions until every tier is finished.

---

## 0. First thing in every session (resume protocol)

Sessions end (context limit, usage limit, crash). Progress must never depend on memory of a previous session.

1. `git checkout main && git pull --rebase origin main`.
2. Read, in order: `AGENTS.md`, `CLAUDE.md`, this file, `docs/ROADMAP.md`, `docs/BUILD_LOG.md`.
3. If `docs/BUILD_LOG.md` does not exist, create it from the template in §8 and do the **state audit** (§2).
4. Find the first item in `BUILD_LOG.md` whose status is not `done`. Continue it. If the working tree has
   uncommitted changes from a crashed session, inspect them, finish or discard them deliberately, and note
   what you did in the log.
5. Keep going item after item. Never end a session voluntarily while items remain. When your context is
   getting full, commit + push the current green state, update `BUILD_LOG.md` (`next action:` line), and
   let the session end — the next session resumes from the log.

## 1. Authority and decision policy

- **You decide.** Architecture, libraries within the pinned stack, scope details, UX, data models, target
  defaults. Record each significant decision as an ADR `docs/adr/ADR-S0NN-<slug>.md` with status
  `Accepted (autonomous, <date>)` — owner reviews later. Do not wait for review.
- Choose what a strong ERP vendor (Odoo, Business Central, SAP) would ship, adapted to this codebase's
  conventions. Prefer correctness, auditability and simplicity over cleverness.
- Missing external inputs (target country, provider credentials, paid API accounts): do **not** block.
  Build the provider-neutral SPI, a fully tested reference implementation, and config placeholders in
  `.env.example`. Defaults when the roadmap leaves a choice open:
  - Localization / tax / e-invoicing: build the framework + **generic IFRS** package + **3 country packs**:
    one EU (Peppol BIS 3 / EN 16931 UBL), Saudi Arabia (ZATCA phase 2), India (GST + IRN). Others follow the
    same SPI.
  - Bank formats: CAMT.053, MT940, OFX import; SEPA pain.001 export.
  - Payment gateway: Stripe (test mode) + generic SPI.
  - Vertical focus: horizontal SMB ERP while keeping the hospitality/POS strengths intact.
  - Inventory costing (1.8): AVCO **and** FIFO both implemented behind a configurable, effective-dated
    `InventoryCostPolicy`; default AVCO org-wide, FIFO selectable per org / category; method changes only via an
    approved policy version + migration job, never a free toggle after postings.
- Only stop for something truly impossible to resolve alone (e.g. a destructive action on production data).
  Log it under `Blocked` in `BUILD_LOG.md`, skip to the next independent item, and continue.

## 2. Current state (2026-10-28) — verify before trusting

`main` is the only branch (local + `origin`). Everything below is merged on `main`:

| Item | State | Your first action |
|---|---|---|
| 0.4 communication | Done: 22/22 routes ticked, Jest specs ported, IT + parity scenario | Confirm green in the Tier 0 build |
| 0.1 manufacturing | 42/42 routes ticked; ADR-S006 package A (production hardening) done; package B time-phased MRP (`MrpPlanningService`, `MrpPlanningController`, `MfgCommands` changes) committed as **WIP, never compiled** | Compile, test, finish package B per ADR-S006 |
| 0.3 rental | Services + controller merged, **tests pending**, 0 routes ticked | Finish, test, tick |
| 0.2 repair | Routes/services/tests written, **never built**, 0 routes ticked | Build, fix, tick |
| Nest retirement | Not done | After Tier 0 exit criteria |

State audit: run `cd backend && ./gradlew build`, read `docs/PORT_STATUS.md`, `docs/spec/{manufacturing,rental,repair}.md`,
`docs/adr/ADR-S006-*.md`, and write the real status into `BUILD_LOG.md`. The build may be red because of the
unverified merges — fixing that is the first task.

## 3. Execution order

1. **Tier 0** — finish 0.1 → 0.3 → 0.2, re-verify 0.4. Nest parity rules (`AGENTS.md` §1, §6) apply in full.
   Exit: 1,118/1,118 routes ticked, parity harness green, `./gradlew build` green, web screens work.
2. **Tier 1** — **1.8 landed costing & cost centres first** (paying-client requirement, full scope in
   `ROADMAP.md` §1.8), then items 1.1 → 1.7 in roadmap order.
3. **Tier 2** — 2.4 first (dimensions), then 2.1, 2.2, 2.3, 2.5, then 2.6–2.10, then i18n + e-commerce connector.
4. **Tier 3** — 3.3 (scale) and 3.1, 3.2 first, then 3.4–3.7. For 3.7 ship the SPIs + 2 reference connectors
   (EDI X12 850/810, one carrier) — ecosystem breadth is ongoing, not a blocker.
5. **Tier 4** — `fleet` module (vehicle management), scope in `ROADMAP.md` §Tier 4.
6. **Production hardening pass** — `docs/phases/P8-hardening-release.md` + §6 checklist across the whole system.
7. **Final report** — §7.

Items inside a tier may be reordered when a dependency demands it; log why.

## 4. Per-module / per-item loop (repeat for every item)

1. **Plan**: write or update `docs/spec/<module>.md` (purpose, functional areas, routes, data model, events,
   permissions, jobs, GL postings). For anything beyond Nest parity, write the ADR first.
2. **Schema**: new Flyway migration `V{n}__<slug>.sql` (never edit V1/V2 or an applied migration). Entities by
   hand in `<module>/domain`; `ddl-auto=validate` stays.
3. **Code**: `web` → `application` → `domain` layering, records for DTOs, Bean Validation, `ApiException`,
   `BigDecimal` + `HALF_UP` for money, tenancy filter on every query, idempotency on money routes, posting
   engine + account mappings for every GL effect, domain events via the outbox, `@RequirePermissions` on
   every route, new permissions added to `packages/shared` and regenerated (`tools/contracts2java`).
4. **Web UI**: screens in `web/` for every new feature (follow existing patterns, i18n keys, permissions
   gating). New features are not done without a usable UI. Android only when the feature is POS/field work
   (barcode app 2.2, fleet driver check-in optional).
5. **Tests**: unit tests for calculators/policies; `@SpringBootTest` + Testcontainers ITs per use case;
   authorization test per route (403); tenant isolation test; idempotency replay test for money routes;
   GL balance assertions (debits = credits) for every posting; JaCoCo ≥ 80 % lines on `application`;
   `ModularityTests` + `ArchitectureTests` green; web `pnpm` lint/typecheck/tests green.
6. **Verify**: `cd backend && ./gradlew build` green (Docker must be running). Start the stack, exercise the
   feature through the web UI, zero console errors. Fix everything before moving on — never leave red.
7. **Document**: update `docs/PORT_STATUS.md` (Tier 0) or the module spec status section, `docs/ROADMAP.md`
   status column, OpenAPI annotations, seed data (`SeedRunner`) for demo, `.env.example` for new config.
8. **Commit + push** (§5). 9. **Log** in `BUILD_LOG.md` (§8), then start the next item immediately.

## 5. Git protocol (autonomous push is authorized)

- Work directly on `main` — the owner wants a single branch. Small, coherent Conventional Commits
  (`feat(fleet): ...`, `fix(rental): ...`, `docs(roadmap): ...`), each ending with your co-author trailer.
- **Push after every finished module/item and after every tier**: `git pull --rebase origin main` then
  `git push origin main`. Also push a green WIP checkpoint before a session is likely to end.
- **Only push a green build.** If `./gradlew build` is red, fix first. If CI (`.github/workflows/ci.yml`) goes
  red after a push, fixing it is the next task.
- Tag each finished tier: `git tag -a tier-<n>-complete -m "Tier <n> complete" && git push origin tier-<n>-complete`.
- Never: force-push, rewrite pushed history, create long-lived branches, skip hooks (`--no-verify`), commit
  `.env`, keys, tokens, real customer data or dumps, edit `reference/`, `V1__baseline.sql`, `V2__reference_data.sql`.

## 6. Production-ready means (apply to every item; audit everything at step 6 of §3)

- **Correctness**: double-entry always balances; period locks respected; reversals instead of deletes for posted
  documents; concurrency guarded (`@Version`, `FOR UPDATE` where stock/money is contended); no half-commits.
- **Security** (`docs/SECURITY.md`): permission on every route, tenant isolation, input validation, rate limits
  on auth/public routes, secrets only from env, no secrets/PII in logs, OWASP top-10 review, dependency scan
  clean (no high/critical CVEs).
- **Performance**: indexes for every new query path, pagination on every list, N+1 checks, load test
  (`tools/perf`) for hot paths; p95 < 300 ms on list/detail routes with 1M-row seed.
- **Operability** (`docs/OPERATIONS.md`): health/readiness, Prometheus metrics for new jobs/queues, structured
  logs with correlation id, ShedLock on scheduled jobs, backup/restore covers new tables, zero-downtime
  migrations (expand → migrate → contract).
- **UX**: every feature reachable in the web app, empty/error/loading states, permission-aware menus, i18n keys.
- **Docs**: spec, ADR, OpenAPI, runbook entries, `.env.example`, release notes in `docs/releases/`.

## 7. Final report (when Tier 4 and hardening are done)

Write `docs/reports/FINAL_REPORT.md`, commit, push, tag `v2.0.0`:

1. Executive summary — what the ERP now does, positioning vs Odoo / Business Central / SAP.
2. Per tier, per item: what was built (routes, screens, migrations, permissions, jobs), tests added, ADRs, commits.
3. Metrics: route count, test count, coverage per module, build time, perf results, CVE scan result.
4. Decisions made autonomously (ADR list with one-line rationale) — for owner review.
5. Deviations, known limitations, stubs that need real credentials/accounts, `Blocked` items.
6. Deployment / upgrade guide for existing customers (migration order, new env vars, licence modules).
7. Recommended next steps.

Then set the last line of `BUILD_LOG.md` to exactly: `STATUS: ALL TIERS COMPLETE`.

## 8. `docs/BUILD_LOG.md` template (keep it current; it is your memory)

```markdown
# Build log
Current: <tier>.<item> <name> — <status>
Next action: <one concrete next step>

## Items
| Item | Status | Commits | Notes |
|---|---|---|---|
| 0.1 manufacturing | in-progress / done / blocked | abc1234 | ... |
...

## Blocked
- <item>: <what is needed> (skipped on <date>, continued with <next item>)

## Session history
- <date> session N: did X, Y; pushed <sha>
```

## 9. Hard rules (from `AGENTS.md` §8 — never break)

Never edit `reference/`, V1/V2 migrations; `ddl-auto=validate`; no response envelopes, no renamed fields or
changed paths/verbs on existing routes (new features = new routes); no entities returned from controllers;
no business logic or repositories in controllers; tenancy on every org-scoped query; idempotency on money
routes; no `double`/`float` for money or quantity; no secrets in logs; modules talk via application APIs or
events. New frameworks outside `AGENTS.md` §3 need an ADR (you may accept it yourself).

## 10. How to run this brief

Paste into a new AI session:

> Read `docs/AUTONOMOUS_BUILD.md` and follow it exactly. Resume from `docs/BUILD_LOG.md`. Work autonomously
> through Tier 0 → Tier 4 and the hardening pass, committing and pushing `main` after every item and tier.
> Do not stop or ask for approval until `BUILD_LOG.md` ends with `STATUS: ALL TIERS COMPLETE` and
> `docs/reports/FINAL_REPORT.md` is pushed.

Unattended across usage limits (Git Bash, from the repo root; run on an isolated dev machine or VM —
this grants the agent full permissions):

```bash
until grep -q "STATUS: ALL TIERS COMPLETE" docs/BUILD_LOG.md 2>/dev/null; do
  claude -p "Read docs/AUTONOMOUS_BUILD.md and follow it exactly. Resume from docs/BUILD_LOG.md. Do not stop until all tiers are complete." \
    --permission-mode bypassPermissions --max-turns 500 || sleep 1800
  git pull --rebase origin main
done
```
