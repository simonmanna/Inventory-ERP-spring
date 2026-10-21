# AGENTS.md — instructions for any AI agent building Inventory-ERP-spring

You are building **Inventory-ERP-spring**: a production-ready rebuild of the POS-CAFE ERP
(cafe POS + full ERP) with a **Spring Boot 4 / Java 25** backend. The existing React web app
(`web/`) and the Android POS app must work against your backend **without any client change**.

This file is the entry point. Read it fully, then read the docs it links before writing code.

---

## 1. The golden rule

> **Behaviour must be identical to the NestJS reference implementation in `reference/nest-api/`.**
> Same routes, same permissions, same request validation, same response JSON, same status codes,
> same error messages (verbatim), same database rows written, same GL postings, same events.
> When anything is unclear, **read the Nest source** — it is the specification. Do not invent,
> simplify away, or "improve" business rules. Improvements go in a separate, explicitly labelled
> change after parity is reached for that module.

`reference/` is read-only. Never edit it. It is a snapshot of the original POS-CAFE repo:

| Path | What it is |
|---|---|
| `reference/nest-api/src/` | NestJS 11 API source (~103k LOC TypeScript). **The spec.** |
| `reference/nest-api/src/**/*.spec.ts`, `reference/nest-api/test/` | 118 Jest specs. Port them. |
| `reference/nest-api/prisma/schema.prisma` | 296 models, 151 enums. Already turned into JPA entities. |
| `reference/nest-api/prisma/migrations/` | 84 migrations. Already turned into Flyway `V1__baseline.sql`. |
| `reference/nest-api/prisma/seed.ts` | Demo seed. Port to the Java `SeedRunner`. |
| `reference/e2e/` | Vitest end-to-end POS sell loop. Must pass against Spring. |
| `docs/legacy/` | Original architecture docs, ADRs, sync protocol, runbooks, audits. |

## 2. Read these before coding (in order)

1. `docs/BUILD_GUIDE.md` — phases, order of work, definition of done.
2. `docs/CONTRACT.md` — **non-negotiable wire contract** (paths, JSON, errors, auth, idempotency, SSE).
3. `docs/ARCHITECTURE.md` — module layout, layering, transactions, events, concurrency.
4. `docs/DATABASE.md` — schema baseline, entity generator, naming, money, migrations.
5. `docs/SECURITY.md` — tokens, permissions, tenancy, hardening.
6. `docs/TESTING.md` — test pyramid, Testcontainers, parity harness.
7. `docs/OPERATIONS.md` — deployment, env vars, backups, monitoring, cut-over from Nest.
8. `docs/spec/<module>.md` — what to build for the module you are working on, plus
   `docs/spec/generated/<module>.md` (every route, DTO rule, error message, source file, test file).
9. `docs/PORT_STATUS.md` — what is done; update it in every PR.
10. `docs/phases/Pn-*.md` — the self-contained brief for the phase you were given.

## 3. Stack (pinned in `backend/gradle/libs.versions.toml`)

Java 25 · Spring Boot 4.1 · Spring MVC (virtual threads) · Spring Security 7 (stateless JWT) ·
Spring Data JPA / Hibernate 7 · Flyway · PostgreSQL 16 · Spring Modulith 2.1 (module boundaries
+ JDBC event publication registry) · Jackson 3 · Bean Validation · MapStruct · ShedLock ·
Bucket4j · Caffeine · springdoc-openapi · Micrometer/Prometheus · OpenPDF · ZXing ·
Testcontainers · ArchUnit · RestAssured. Do not add frameworks outside this list without an ADR
in `docs/adr/`.

## 4. Repository map

```
backend/                       Spring Boot app (Gradle). Package root com.inventoryerp
  .../shared/                  OPEN module: DbEnum, PageQuery/PageResult, ApiException, cross-module enums, DomainEvents
  .../kernel/                  auth, security, tenancy, idempotency, web (errors, prefix, JSON), health, ...
  .../<module>/domain/         GENERATED entities + enums + repositories (tools/prisma2jpa)
  .../<module>/application/    services (use cases, @Transactional), policies, calculators   <- you write
  .../<module>/web/            @RestController + request/response records + mappers         <- you write
  src/main/resources/db/migration/   V1 baseline (never edit), V2 reference rows, V3 platform, V4+ yours
web/                           React 18 + Vite client (copied unchanged from POS-CAFE)
android/                       Kotlin offline-first POS (from POS-CAFE apps/android), syncs via /sync/pull|push
packages/shared/               TS contract constants: permissions, events, enums, pagination (source of truth)
tools/prisma2jpa/              schema.prisma -> JPA entities/enums/repositories
tools/contracts2java/          packages/shared -> Permissions.java, DomainEvents.java
tools/extract-routes/          Nest controllers -> docs/spec/routes.json + docs/spec/generated/*.md
contract-tests/                parity harness: same requests to Nest and Spring, JSON diff
infra/docker/                  Dockerfile.api, Dockerfile.web, nginx.conf
reference/                     READ-ONLY NestJS source of truth
```

Module list (package = `com.inventoryerp.<name>`): `kernel, core, accounting, inventory,
procurement, invoicing, expenses, pos, sync, documents, crm, task, backup, beverage, fixedasset,
hr, manufacturing, rental, repair, orders, communication`. The Nest `school` module is dead code
(never imported) — do not port it.

## 5. Commands

```bash
docker compose up -d db                      # Postgres 16 on localhost:5434 (erp/erp)
cd backend && ./gradlew build                # compile + all tests (needs Docker for Testcontainers)
cd backend && ./gradlew bootRun              # API on http://localhost:3001/api/v1
cd backend && ./gradlew test --tests '*Pos*' # subset
pnpm install && pnpm web:dev                 # web on http://localhost:5173, proxies /api/v1 -> :3001
node tools/extract-routes/extract.mjs        # regenerate route inventory
node tools/contracts2java/generate.mjs       # regenerate Permissions/DomainEvents
node tools/prisma2jpa/generate.mjs           # regenerate entities (skips files you took ownership of)
docker compose up -d --build                 # full stack, web on http://localhost:8080
```

Gradle provisions JDK 25 automatically (foojay). Any JDK 17+ can launch Gradle.

## 6. How to port a module (do this every time)

1. **Read** `docs/spec/<m>.md`, then `docs/spec/generated/<m>.md`, then every Nest file listed under
   "Source files to port" — controllers, services, DTOs, policies, subscribers, and the specs.
2. **Entities**: they already exist in `<m>/domain` (generated, validated against the real schema by
   `ApplicationIT`). Add `@OneToMany` collections, `@Version`, or query methods by hand — first
   delete the `@generated` marker line so the generator stops overwriting that file.
3. **DTOs**: Java `record`s in `<m>/web/dto`. Mirror every class-validator rule with Bean Validation
   (`@IsOptional` = nullable, `@IsString` = `String`, `@Min` = `@Min`/`@DecimalMin`, `@IsIn` = custom
   `@OneOf`, nested = `@Valid`). Unknown JSON fields are rejected globally (forbidNonWhitelisted).
4. **Service** in `<m>/application`: one public method per use case, `@Transactional`, business rules
   copied line by line from the Nest service, **error messages copied verbatim** via `ApiException`.
   Use `BigDecimal` for all money/qty math with the same scale and `RoundingMode.HALF_UP` as
   `kernel/common/money.ts`. Lock rows exactly where Nest uses `FOR UPDATE` / serializable retries.
5. **Controller** in `<m>/web`: same path, verb, status code (`@ResponseStatus(CREATED)` for POST
   unless Nest has `@HttpCode`), `@RequirePermissions(...)` with the same permission strings, same
   query parameter names, same response shape (list envelope vs plain array — check per route).
6. **Events**: publish the same domain events (`DomainEvents.*`) at the same points; port subscribers
   as `@ApplicationModuleListener`s (after-commit, transactional outbox).
7. **Tests**: port each listed Jest spec to JUnit 5 (unit) or `@SpringBootTest` + Testcontainers
   (integration). Add an authorization test per route (403 without permission). Add parity scenarios
   in `contract-tests/scenarios/<m>-*.json`.
8. **Verify**: `./gradlew build` green, parity diff empty for the module, web screens for the module
   work against Spring with zero console errors.
9. **Record**: tick routes in `docs/PORT_STATUS.md`, note any deliberate deviation in
   `docs/spec/<m>.md` under "Deviations" (must be approved by the owner).

## 7. Definition of done (per module / per PR)

- Every route in `docs/spec/generated/<m>.md` implemented with identical contract.
- All listed Jest specs ported and passing; integration tests on Testcontainers Postgres.
- Authorization matrix test passes; tenant isolation test passes (org A cannot see org B).
- Money routes reject a missing `Idempotency-Key` and replay correctly.
- Parity harness: zero unexplained diffs for the module's scenarios.
- No new warnings from `ModularityTests` / `ArchitectureTests`; JaCoCo line coverage ≥ 80 % on
  `application` packages of the module.
- OpenAPI renders; `docs/PORT_STATUS.md` updated; PR description lists routes + tests.

## 8. Hard rules (never break)

- Never edit `reference/`, `V1__baseline.sql`, or `V2__reference_data.sql`. Schema changes = new `V{n}__*.sql`.
- Never set `ddl-auto` to anything but `validate`.
- Never wrap responses in an envelope; never rename JSON fields; never change a path or verb.
- Never return JPA entities from controllers — records only (ArchUnit enforces).
- No business logic in controllers; no repository access from controllers.
- Never skip tenancy: every query on an org-scoped table is filtered by `organizationId`.
- Never skip idempotency on money routes; never let a money operation half-commit.
- Never use `double`/`float` for money or quantities.
- Never log secrets, tokens, PINs, passwords, or full card/phone data.
- Never commit `.env`, real data dumps, or keys.
- Keep modules independent: talk to another module through its `application` API or events,
  not its repositories (reading another module's entities through its exposed `domain` named
  interface is allowed for queries; writes go through that module's services).

## 9. Conventions

- Package per module: `web` → `application` → `domain`. Cross-cutting helpers live in `shared` or `kernel`.
- Names: `XxxController`, `XxxService`, `XxxPolicy`, `CreateXxxRequest`, `XxxResponse`, `XxxMapper`.
- IDs: `String` UUIDs (Prisma `uuid()`); assigned in `@PrePersist` when null.
- Time: `Instant` (UTC) for timestamps; `LocalDate` for `@db.Date`; business date per org timezone.
- Enums: generated `DbEnum`s; JSON and DB value = `value()` (e.g. `not_paid`, `AVCO`).
- Errors: `throw ApiException.badRequest("<verbatim Nest message>")` (or `conflict`, `forbidden`, ...).
- Formatting: 4-space Java, 120 columns, no wildcard imports, Javadoc on public services.
- Commits: Conventional Commits (`feat(pos): ...`), one module or concern per PR.
