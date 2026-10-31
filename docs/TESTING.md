# Testing

## 1. Pyramid

| Layer | Tooling | What | Where |
|---|---|---|---|
| Unit | JUnit 5, AssertJ, Mockito | policies, calculators, mappers (money, AVCO, landed cost, tax, payroll, split bill, mutation policy, line signature, pricing) | `src/test/java/.../<module>/application` |
| Integration | `@SpringBootTest` + Testcontainers Postgres 16 (`TestcontainersConfiguration`), RestAssured | full HTTP → DB per route, transactions, locking, idempotency | `src/test/java/.../<module>/web` |
| Module | Spring Modulith `@ApplicationModuleTest`, `Scenario` | event flows between modules | `.../<module>` |
| Architecture | `ModularityTests`, `ArchitectureTests` (ArchUnit) | boundaries, layering | root |
| Contract parity | `contract-tests/` | Nest vs Spring JSON diff on identical data | repo root |
| E2E | Vitest `reference/e2e/pos-sell-loop.spec.ts` (+ POS recovery suite) | real web flows against Spring | `tests/e2e` (copy when P5 starts) |
| Simulation | port of `reference/nest-api/test/business-simulation` | days of trading, GL ties out, mutants killed | `src/test/java/.../simulation` |
| Load | k6 | checkout at 50 terminals, tables SSE, reports | `tests/load` |

## 2. Rules

- Port every Jest spec listed in `docs/spec/generated/<m>.md` → "Tests to port". Keep the test
  names (as `@DisplayName`) so coverage of the original intent is traceable.
- Integration tests share one Postgres container (reused) and isolate by creating a fresh
  organization per test class (fast) — never truncate shared tables between tests.
- Every money route: test missing `Idempotency-Key` (400), replay (same body → same response,
  one DB effect), conflicting body (409), concurrent duplicate (one 2xx, one 409 `OPERATION_PENDING`).
- Every list route: pagination meta, `X-Total-Count`, search, sort whitelist.
- Every route: authorization (401/403) and tenant isolation.
- Coverage gate: JaCoCo ≥ 80 % lines on `application` packages (P8 enforces in CI).
- Mutation testing (PIT) on `pos`, `accounting`, `inventory` calculators before release.

## 3. Parity harness (`contract-tests/`)

Goal: prove Spring returns what Nest returns for the same request on the same data.

1. Start Postgres A + Nest API (from
   `reference/nest-api`) and Postgres B + Spring API (this repo) as in `contract-tests/README.md`. Both DBs are migrated with Prisma and
   seeded with the Nest seed, so ids match.
2. `node contract-tests/run.mjs --module pos` replays `scenarios/pos-*.json` against both: each step
   has `method`, `path`, `body`, `headers`, optional `capture` (JSONPath → variable for later steps).
3. Responses are normalized (generated ids, timestamps, request ids, document numbers mapped by
   capture order) and diffed: status, headers that matter (`X-Total-Count`, `X-Pos-Session`), body.
4. A module is done when its scenarios produce zero unexplained diffs. Explained diffs are listed in
   `contract-tests/allowed-diffs.json` with a reason (e.g. permissions intentionally not in JWT).

Scenario authoring tip: record real traffic from the web client against Nest (browser HAR → script
in `contract-tests/tools/har2scenario.mjs`), then replay against both.

## 4. Running

```bash
cd backend && ./gradlew test                        # unit + integration (Docker required)
cd backend && ./gradlew test --tests '*CashSession*'
node contract-tests/run.mjs --module accounting     # parity (needs both stacks up)
pnpm vitest run tests/e2e                           # E2E against API_URL (default http://localhost:3001)
```

Without Testcontainers (an empty Postgres 16 you started yourself), keep `stringtype=unspecified` in the URL or enum
inserts fail:

```bash
docker run -d --name erp-itdb -e POSTGRES_USER=erp -e POSTGRES_PASSWORD=erp -e POSTGRES_DB=erp \
  -p 127.0.0.1:5499:5432 postgres:16 -c max_locks_per_transaction=1024
IT_EXTERNAL_DB=true JDBC_DATABASE_URL='jdbc:postgresql://127.0.0.1:5499/erp?stringtype=unspecified' \
  DB_USER=erp DB_PASSWORD=erp ./gradlew test
```

Integration suites share one database and the seeded `DEMO` organization. A suite that changes organization-wide
setup (shifts, policies, approval rules) must undo it, and a suite waiting for event subscribers calls
`drainOutboxFully()` (it waits for every pending outbox row, including rows an earlier suite left behind).
