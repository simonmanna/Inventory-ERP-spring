# Build Guide — Inventory-ERP-spring

Master plan for rebuilding the POS-CAFE ERP on Spring Boot. Every agent and engineer works from
this file. Read `AGENTS.md` first.

## 1. Goal

A production-ready ERP for cafés/restaurants (POS, tables, kitchen, cash, inventory, purchasing,
accounting, invoicing, expenses, CRM, tasks, HR/payroll, manufacturing, fixed assets, rental,
repair, messaging, offline sync, backups) that:

1. Is **wire-compatible** with the POS-CAFE NestJS API, so the copied React client (`web/`) and the
   Android POS run unchanged against it (`docs/CONTRACT.md`).
2. Implements **identical business behaviour** (the NestJS code in `reference/nest-api` is the spec).
3. Can **take over an existing POS-CAFE database in place** (same schema; Flyway baselines at V2).
4. Meets the production bar in section 6 (security, reliability, observability, operability).

## 2. Size of the job

| Metric | Count | Where |
|---|---|---|
| HTTP routes | 1,118 | `docs/spec/routes.json`, `docs/spec/generated/*.md` |
| Controllers | ~142 | `reference/nest-api/src/**/*.controller.ts` |
| Tables / enums | 298 / 151 | `V1__baseline.sql`; entities in `*/domain` (generated) |
| Permissions | 364 | `packages/shared/src/permissions.ts` → `Permissions.java` |
| Domain events | 136 | `packages/shared/src/events.ts` → `DomainEvents.java` |
| Jest specs to port | 118 | listed per module in `docs/spec/generated/*.md` |
| API source | ~103,600 LOC TS | `reference/nest-api/src` |

Routes per module: pos 219 · hr 140 · accounting 110 · kernel 82 · core 81 · inventory 69 ·
repair 64 · fixedasset 54 · rental 47 · manufacturing 42 · procurement 33 · invoicing 30 ·
documents 28 · communication 22 · crm 21 · expenses 18 · task 16 · backup 13 · orders 11 ·
beverage 10 · sync 8.

## 3. What is already done (phase P0)

- Repository, workspace, CI (`.github/workflows/ci.yml`), Docker images, compose stack.
- `web/` + `packages/shared/` copied unchanged; `pnpm web:build` works.
- `V1__baseline.sql` = exact `pg_dump --schema-only` of the POS-CAFE schema after all 84 migrations
  (tables, PG enums, indexes, partial uniques, 237 RLS policies, 13 triggers, 14 functions).
  `V2` = reference rows inserted by migrations. `V3` = ShedLock + Modulith event tables.
- 296 JPA entities, 151 enums (+ converters), 296 repositories generated and **validated against
  the real schema** at test time (`ApplicationIT`, `ddl-auto=validate`).
- `Permissions.java` (364) and `DomainEvents.java` (136) generated from `packages/shared`.
- Kernel baseline: `/api/v1` prefix, Nest-shaped errors, request id, JSON parity (Decimal as
  string), CORS, stateless JWT decoder, public `/health*`, Prometheus at `/api/v1/metrics`,
  Swagger at `/api/docs`, `ModularityTests`, `ArchitectureTests`.
- Route/DTO/error inventory per module (`docs/spec/generated/`).

## 4. Phases

Each phase = one or more PRs on branches `phase/Pn-<area>`. A phase is done only when its exit
criteria pass in CI. Phases P3a/P3b/P3c can run in parallel; P7 sub-modules can run in parallel.
Detailed self-contained briefs: `docs/phases/`.

| Phase | Scope | Depends on | Exit criteria |
|---|---|---|---|
| P0 Foundation | (done) | — | build green, app boots, `/api/v1/health` 200 |
| **P1 Kernel** | auth, staff, tenancy, idempotency, permissions, settings, audit, events/outbox, sequences, files, notifications, approvals, feature flags/modules, recurring, webhooks, search, reports access, rate limit, read-only mode, seed | P0 | web login + dashboard shell work; kernel parity diff = 0 |
| **P2 Core + Accounting** | master data; GL engine; accounts/journals/periods/FX; bank rec; treasury; cash registers/sessions; money operations; financial reports | P1 | trial balance balances on simulation; cash-session rules tests ported |
| **P3a Inventory** | locations, stock service (AVCO/FIFO/STANDARD/SPECIFIC), ledger, stock documents, counts, reservations, posting rules, reports | P2 | inventory specs ported; valuation = GL tie-out |
| **P3b Invoicing + Expenses** | invoices, credit notes, vendor bills, payments/allocations, AR/AP, expenses | P2 | AR/AP aging parity |
| **P3c CRM + Tasks + Documents** | deals/activities/analytics; tasks; DMS documents/templates/relations/PDF | P1 (+P2 partners) | parity = 0 |
| **P4 Procurement** | PR, PO, GRN, landed cost, debit notes, three-way match, GRNI | P3a, P3b | landed-cost AVCO tests ported |
| **P5 POS** | everything under `/pos/**`, `/menu/public/**`, SSE streams, printing, KDS, reports | P3a, P3b | `reference/e2e` POS sell loop green vs Spring; offline replay test; business simulations green |
| **P6 Sync + Backup** | `/sync/**` (Android + offline protocol), `/admin/backups/**` | P5 | sync protocol tests; backup → restore-test drill passes |
| **P7 Optional modules** | beverage, fixedasset, hr, manufacturing, rental, repair, orders, communication (each behind `ENABLE_*`) | P2–P5 | each: parity = 0, specs ported |
| **P8 Hardening + release** | full parity run, load test, security review, SBOM/dep scan, DR drill, perf, docs, v1.0.0 | all | release gate passes (section 7) |

### Recommended order inside a phase
Follow the route table in `docs/spec/generated/<m>.md`: reads before writes, simple CRUD before
workflows, workflows before reports. Port the module's Jest specs as you go, not at the end.

## 5. Working method

1. One module/sub-area per PR. PR title `feat(<module>): <area> parity`.
2. Before coding a route, open its Nest handler + service method. Copy validation, rules, error
   messages, transaction boundaries, locking, events, and response mapping.
3. Write the test first when a Jest spec exists (port it), then the code.
4. Run the parity harness for the module (`contract-tests/README.md`).
5. Update `docs/PORT_STATUS.md` and the module spec's "Status/Deviations".

## 6. Production bar (applies to all phases)

- **Correctness**: parity with Nest; money in `BigDecimal`; no lost updates (row locks / `@Version`);
  idempotent money routes; append-only ledgers (inventory, GL, cash, loyalty, store credit).
- **Security**: `docs/SECURITY.md` checklist (stateless JWT, refresh rotation + reuse detection,
  bcrypt, lockout, MFA, permission check on every route, tenant isolation + RLS, input limits,
  no secrets in logs, secure headers).
- **Reliability**: graceful shutdown, DB-backed job queues with `SKIP LOCKED`, ShedLock for crons,
  transactional outbox for events and webhooks, retries with backoff, health/readiness probes.
- **Observability**: JSON logs with requestId/orgId/userId, Prometheus metrics (HTTP, JVM, Hikari,
  job backlogs: stock posting, outbox, webhooks, comm dispatch), Grafana dashboard in `infra/`.
- **Performance**: p95 < 150 ms for POS checkout at 50 concurrent terminals on 4 vCPU / 8 GB;
  no N+1 (Hibernate statistics test on hot paths); indexes from V1 kept.
- **Operability**: one `.env`, Docker images, upgrade script (backup → migrate → health → rollback),
  backups encrypted + offsite + restore-tested, runbooks in `docs/OPERATIONS.md`.

## 7. Release gate (P8)

- CI green on `main` (backend, contracts, web, docker).
- Parity harness: all scenarios, zero unexplained diffs.
- `reference/e2e` POS sell loop + offline recovery suite green against Spring.
- Load test (k6, `tests/load/`) meets the performance bar.
- OWASP dependency-check / Trivy: no High/Critical without a waiver; CycloneDX SBOM attached.
- DR drill: restore yesterday's encrypted backup into a scratch DB, app boots, trial balance equal.
- Cut-over rehearsal on a copy of a production POS-CAFE DB (`docs/OPERATIONS.md` §Cut-over).
- Tag `v1.0.0`, release notes, images pushed.
