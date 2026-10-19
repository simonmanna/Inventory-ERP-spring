# Architecture

Modular monolith on Spring Boot 4 with Spring Modulith boundaries — the same shape as the Nest app
(kernel + business modules + optional verticals), one deployable, one Postgres database.

## 1. Modules

| Module | Nest source | Always on? | Owns (tables) |
|---|---|---|---|
| `shared` | `packages/shared`, `kernel/common` | open module | — (cross-module enums, web contract types) |
| `kernel` | `src/kernel`, `src/health`, `src/observability` | yes | Organization, User, Role, Permission, RefreshToken, IdempotencyRecord, AuditLog, Setting, EventOutbox, Notification, File, Approval*, Webhook*, FeatureFlag, OrganizationModule, ... (30) |
| `core` | `modules/core` | yes | Partner, Product, Branch, Tax, UoM, FiscalPeriod, PaymentTerm, ... (18) |
| `accounting` | `modules/accounting` | yes | Account, Journal, JournalEntry/Line, CashRegister, CashSession, CashMovement, BankAccount, Currency, report snapshots (23) |
| `inventory` | `modules/inventory` | yes | StockItem, InventoryLedger, InventoryLocation, stock documents, counts (18) |
| `procurement` | `modules/procurement` | yes | PurchaseRequest/Order, GoodsReceipt, LandedCost, DebitNote, ThreeWayMatch (15) |
| `invoicing` | `modules/invoicing` | yes | Invoice*, Receipt*, Payment, PaymentAllocation (8) |
| `expenses` | `modules/expenses` | yes | Expense, ExpenseCategory, ExpensePayment |
| `pos` | `modules/pos` | yes | Order*, PosTable*, Menu*, Modifier*, KitchenTicket, SplitBill, Loyalty*, StockPostingJob, ... (39) |
| `sync` | `modules/sync` | yes | PosDevice, SyncOpDeadLetter |
| `documents` | `modules/documents` | yes | Document*, Template*, DMS* (17) |
| `crm` | `modules/crm` | yes | Deal, Activity |
| `backup` | `modules/backup` | yes | (files + settings) |
| `task` | `modules/task` | `ENABLE_TASKS` | Task* (7) |
| `beverage` | `modules/beverage` | `ENABLE_BEVERAGE` | Bottle* (4) |
| `fixedasset` | `modules/fixed-asset` | `ENABLE_ASSETS` | Asset* (15) |
| `hr` | `modules/hr` | `ENABLE_HR` | Hr* (32) |
| `manufacturing` | `modules/manufacturing` | `ENABLE_MANUFACTURING` | Bom*, Production*, WorkCenter, Routing*, WorkOrder (16) |
| `rental` | `modules/rental` | `ENABLE_RENTAL` | Rental* (19) |
| `repair` | `modules/repair` | `ENABLE_REPAIR` | Repair* (15) |
| `orders` | `modules/orders` | `ENABLE_ORDERS` | (uses pos Order tables via services) |
| `communication` | `modules/communication` | `ENABLE_COMMUNICATION` | Conversation, Message, CommunicationChannel, ... (13) |

Table ownership comes from `tools/prisma2jpa/generate.mjs` (`moduleOf`). If you move a table,
change it there and regenerate.

## 2. Package layout inside a module

```
com.inventoryerp.<module>
  package-info.java            @ApplicationModule
  domain/                      @NamedInterface("domain") — entities, enums, repositories (generated + hand-tuned)
  application/                 services (public API of the module), policies, calculators, event listeners
  application/port/            (optional) interfaces other modules call
  web/                         @RestController, dto/ (records), mapper/ (MapStruct)
  infra/                       adapters: printers, mail, S3, external APIs, jobs
```

Dependency direction: `web → application → domain`. Other modules may call `application`
services (Modulith allows the base package API) and read `domain` entities (named interface).
They must **not** write another module's tables directly — call its service or publish an event.
`ModularityTests` (cycles, illegal access) and `ArchitectureTests` enforce this.

## 3. Cross-module interaction

| Need | Mechanism |
|---|---|
| Synchronous business call (e.g. POS checkout posts GL entry, issues stock) | Call the owning module's `application` service inside the same transaction (Nest did the same in-process) |
| Reaction to something that happened (CRM links invoice, tasks auto-rules, notifications, webhooks) | Publish a domain event (`ApplicationEventPublisher`) with a name from `DomainEvents`; listen with `@ApplicationModuleListener` (async, after commit, persisted in `event_publication`) |
| Read-only lookup of another module's data | Read its entities via repository in its `domain` named interface, or a query method on its service |

Nest also writes `EventOutbox` / `DomainEventLog` rows (shipped to webhooks by `outbox.worker.ts`).
Keep writing those rows for parity (webhook consumers, audit timelines) — the Modulith registry
is for in-process listeners.

## 4. Transactions

- One `@Transactional` service method per use case. Controllers are never transactional.
- The GL posting, stock movement, cash movement and idempotency outcome of a money operation are
  in the **same transaction** as the source document (Nest uses `prisma.$transaction`; mirror its
  boundaries — read the service).
- External I/O (printing, email, SMS, push, webhooks, rclone, WhatsApp/Telegram) runs **after
  commit** (`@TransactionalEventListener(AFTER_COMMIT)` or a job table), never inside the tx.
- Serializable/retry: where Nest retries on `P2034` (serialization failure), use
  `@Retryable`-style loop on `CannotAcquireLockException`/`PessimisticLockingFailureException`
  with the same attempt count.

## 5. Concurrency control

| Resource | Nest mechanism | Spring |
|---|---|---|
| Stock quantity + AVCO | `SELECT ... FOR UPDATE` on StockItem | `@Lock(PESSIMISTIC_WRITE)` query or native `FOR UPDATE` |
| Cash drawer balance | row lock on session/account | same |
| Document numbers | per-tenant Postgres sequences (`sequence.service.ts`) | same SQL via `JdbcClient` |
| Order / Invoice / PurchaseOrder edits | `version` column compare-and-set | `@Version` (generated) → 409 with Nest message |
| One open tab per table | DB partial unique index | keep; map violation to Nest message |
| Job queues (stock posting, webhooks, comm dispatch, outbox) | `claimToken` + `FOR UPDATE SKIP LOCKED` | same native SQL, `@Scheduled` + ShedLock |
| Idempotency | unique `(organizationId, key)` pending row | same |

## 6. Tenancy

- Every org-scoped request runs with a `TenantContext` (organizationId, userId, posUserId, branchId,
  permissions) resolved by the security filter from the bearer / POS / device token.
- Reads: Hibernate filter `tenant` (`organizationId = :orgId`) + `softDelete` (`deletedAt IS NULL`)
  enabled per session for org-scoped entities (`@FilterDef` in `shared.persistence`). Services that
  list deleted rows (`GET .../deleted`, restore) disable `softDelete` explicitly.
- Writes: an entity listener sets `organizationId` on persist and refuses cross-org updates.
- Native SQL / `JdbcClient` must add `"organizationId" = ?` by hand — code review rule.
- Database backstop: `SET LOCAL app.org_id = '<org>'` at transaction start (RLS policies in V1 use
  this GUC). Production connects as a non-superuser so RLS is enforced (see SECURITY.md).

## 7. Optional modules

`@ConditionalOnBooleanProperty("app.modules.<x>")` on the module's configuration +
`@RequiresModule` checks on controllers (organization-level enablement in `OrganizationModule`).
When disabled, routes respond exactly as Nest does when the module is not imported (404 `Cannot GET`).

## 8. Scheduled jobs (port of `kernel/workers/*` and module crons)

| Job | Interval | Owner |
|---|---|---|
| outbox-ship | 30 s | kernel |
| webhooks-retry | 1 min | kernel |
| recurring-tick | 10 min | kernel |
| cleanup-notifications | hourly | kernel |
| cleanup-tokens | daily | kernel |
| reservation-no-show | 1 min | pos |
| reservation-refresh | 5 min | pos |
| stock-posting-drain | 30 s | pos |
| stock-posting-health | 5 min | pos |
| report snapshots | per `snapshot-cron.worker.ts` | accounting |
| rental-housekeeping | 1 min | rental |
| repair-housekeeping | 10 min | repair |
| communication dispatch | `COMM_DISPATCH_POLL_MS` | communication |
| backups | dynamic (settings) | backup |

All use ShedLock (`@SchedulerLock`) so multiple instances never double-run.

## 9. Printing, PDF, files

- ESC/POS receipts, bills, KOTs, closing statements: port `pos-receipts.service.ts` byte-for-byte
  (CP437, `GS v 0` raster logo, cut commands) into `pos/infra/escpos`. Transport: raw TCP to
  `pos.printerHost:pos.printerPort` (9100) or the Windows spooler (`javax.print`).
- PDF: OpenPDF; HTML previews via Thymeleaf templates in `src/main/resources/templates`.
- Files: `FileStorage` interface with `LocalFileStorage` (default) and `S3FileStorage` (AWS SDK v2,
  real implementation — Nest's S3 driver was a stub).

## 10. Observability

- Logback JSON (logstash encoder) in `prod` profile; MDC: `requestId`, `organizationId`, `userId`.
- Micrometer: HTTP server metrics, JVM, Hikari, custom gauges for job backlogs.
- Modulith observability for event flows; optional OpenTelemetry exporter via env.
