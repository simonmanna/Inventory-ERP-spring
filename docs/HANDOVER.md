# Handover — state after P4 (2026-10-20)

Read `AGENTS.md` and `CLAUDE.md` first. This file only records where the port stands and the conventions
and pitfalls learned so far.

## Where things are

| Phase | Modules | Routes | Branch / commit |
|---|---|---|---|
| P1 | kernel | 79/79 | merged history |
| P2 | core, accounting | 81/81, 110/110 | `phase/P2-core-accounting` (last commit `feat(accounting): complete P2 reporting surface`) |
| P3 | inventory, invoicing, expenses, crm, task, documents | 69, 30, 18, 21, 16, 28 — all done | `phase/P3-inventory-invoicing-crm` (branched from P2) |
| P4 | procurement | 36/36 | `phase/P4-procurement` (branched from P3) |
| P5 | pos | 0/219 | next — brief `docs/phases/P5-pos.md` |

Nothing is pushed or merged. `main` does not contain P2/P3/P4 yet; the owner decides on merge / PRs.
Run `cd backend && ./gradlew build --offline` (Docker required for Testcontainers) before claiming green.

Totals and per-route ticks: `docs/PORT_STATUS.md` (488/1118). Per-module status, deviations and
"same as Nest, kept on purpose" notes: `docs/spec/<module>.md`.

## Open items (not done, do not claim them)

1. **Parity harness not run** against a live Nest for any P2/P3 scenario (`contract-tests/scenarios/*.json`
   exist; `node contract-tests/run.mjs` needs both servers + same seed). Spec checklists leave the
   "Parity scenarios" and "Web screens" boxes unticked for this reason.
2. **Web screens** for P3 modules not exercised against Spring.
3. **JaCoCo ≥ 80 %** on `application` packages not measured.
4. **Deviations awaiting owner approval**: inventory `lowStock` filter, task date-only `dueDate`, DMS A4 PDF
   via OpenPDF (see each spec's "Deviations"). Procurement has none, but lists Nest bugs kept on purpose
   (PO search → 500, inbound non-RTV debit note never balances) — the owner may want those fixed after parity.
5. Deferred Jest suites still open: POS / sync driven (`pos-*`, `sync-*`, the remaining `inv-*` POS cases) and the
   Lakeview simulations (`test/business-simulation/*`, need POS) — port with P5–P6. The procurement ones
   (`procurement-flow`, `purchase-bill-matching`, `supplier-payment-withholding`, purchasing cases of `inv-*`) are
   in `ProcurementIT`.

## How the code is written (follow it)

- **Services are JdbcClient SQL**, not JPA, for Prisma parity. Render rows with
  `SqlRows.as(Entity.class, row)` / `SqlRows.list(Entity.class, spec)` / `SqlRows.one(...)` (Prisma column
  order, Decimal/Instant/jsonb normalization). Build maps with `Obj.of(k, v, ...)` or `LinkedHashMap`.
- **Tenancy emulation**: every org-scoped query has explicit `"organizationId" = ?`; soft-delete models also
  `"deletedAt" IS NULL`. Prisma `include`s are NOT filtered — don't filter related rows either.
  `prisma.raw` reads skip the soft-delete filter.
- **Timestamps**: inserts use `NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)"`; JDBC params
  via `SqlRows.ts(Instant)`; ISO strings → `EntityPatch.toInstant`. JS `toISOString` → `DmsSnapshot.jsIso` /
  `JsonConfig.toJsIso`.
- **Numbers**: `BigDecimal` + `Money.div` (decimal.js precision 20 HALF_UP); JSON-visible JS numbers via
  `JsNumbers.js(double|BigDecimal)`; decimal.js strings via `JsNumbers.dec`; `String(n)` via
  `JsNumbers.toJsString`.
- **Errors**: `ApiException.badRequest/notFound/forbidden/conflict("<verbatim Nest message>")`,
  `new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ...)`. Prisma "unhandled" errors (P2025 on update, bad enum
  cast, unknown data key) stay 500 — throw `IllegalStateException` or let the DB error surface.
- **Status codes**: POST → `@ResponseStatus(CREATED)` unless Nest `@HttpCode`; `@Idempotent` routes answer 200;
  money routes `@Idempotent(required = true)`.
- **Validation**: Bean Validation records with class-validator messages (`must be a string`,
  `must be a valid ISO 8601 date string`, `must be one of the following values: a, b` via `@OneOf`,
  `must be a UUID` via `@Pattern`). Untyped Nest bodies (`@Body() body: {…}` interfaces) are **not** validated —
  take `Map<String, Object>`. Query DTO whitelists: `QueryParams.only(...)` / `QueryParams.validation(errors)`.
- **Transactions**: Nest calls outside `$transaction` that must survive a later failure (approval gates) run in a
  `REQUIRES_NEW` `TransactionTemplate`; best-effort side calls in `PROPAGATION_NESTED`. Don't self-invoke
  `@Transactional` methods — use a `TransactionTemplate`.
- **Events**: `EventBus.publish` (own tx, fire-and-forget like Nest `events.publish`); in-tx outbox facts via
  `EventOutboxService.publish`. Subscribers: `events.subscribe(name, JsonNode -> …)` in `@PostConstruct`; they run
  without tenant context — use the payload's `organizationId`.
- **Audit**: `audit.record(AuditInput.of(...))` fire-and-forget, `audit.recordInTx` inside the business tx.
- **Modularity**: each module's `application/package-info.java` is `@NamedInterface("application")`; other
  modules may call those services and read `domain` entities; never write another module's tables except where
  Nest does (invoicing writes `Document*` like Nest).
- **Feature flags**: optional modules' controllers use `@ConditionalOnProperty(name = "app.modules.<x>",
  havingValue = "true")` + `@RequiresModule("<name>")` (see `task/web/TaskController`). The test profile
  (`src/test/resources/config/application.properties`) enables `app.modules.tasks`.
- **Express route shadowing**: Nest matches routes in registration order; when a `:param` route is declared
  before a literal one, the literal is unreachable in Nest. Keep parity by mapping the literal to the shadowing
  handler (see `DocumentTypesController#categories`, `DocumentsController#listTemplates`). Check this for every
  controller you port.

## Tests (patterns)

- ITs extend `ApiTestSupport`: `ok(status, method, path, body)`, `as(...)`, `call(method, path, body, headers|token)`,
  `adminToken`, `otherToken` (other org), `demoOrg`, `jdbc`, `assertAuthorizationMatrix("com.inventoryerp.<m>")`
  (returns route count — assert it equals the generated spec's count).
- Money routes: pass `Idempotency-Key` header via the `call(..., Map headers)` overload.
- DEMO may be seeded by a suite that skipped module contributors; make an IT self-sufficient (see
  `DocumentsIT#fixtures` re-running `DmsRegistrySeeder.seed`). `PermissionService` caches grants — call
  `evictAll()` after changing role permissions.
- Run a subset: `cd backend && timeout 1500 ./gradlew test --offline --tests '*XxxIT*' -q`, then parse
  `build/test-results/test/TEST-*.xml` for failure messages.
- Expected values for tricky JS behaviour: run the Nest TypeScript directly with Node 22.18 type stripping
  (copy the `.ts` to a temp `.mts`, import, print) — done for the DMS seal in `DmsRulesTest`.

## Tooling notes (Windows, Git Bash)

- The test JVM runs with `-Duser.timezone=UTC` (`build.gradle.kts`). `Application.main` pins UTC in production, but
  `@SpringBootTest` never calls it; on a non-UTC host the PgJDBC session time zone then followed the host and every
  `clock_timestamp()::timestamp(3)` was stored in local time (found in P4: GRNI `asOf` filtered out fresh receipts).

- Multiple heredocs in one Bash call break the tool's quoting — write files with the Write tool or one heredoc
  per call / a Python script.
- `tools/dms-registry/export.mjs` regenerates `backend/src/main/resources/dms/registry.json` from the Nest seed.

## Next step

Start P5 (POS) on a new branch from `phase/P4-procurement`: read `docs/phases/P5-pos.md`, `docs/spec/pos.md` and
`docs/spec/generated/pos.md`. Procurement services other modules may call: `procurement.application.*`
(`PurchaseOrdersService`, `GoodsReceiptsService`, ...). Port the Lakeview simulations with P5.
