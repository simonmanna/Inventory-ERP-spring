# Database

PostgreSQL 16. The schema is **byte-for-byte the POS-CAFE schema**, so the Spring API can run on
an existing POS-CAFE database and the parity harness can compare both APIs on identical data.

## 1. Migrations (Flyway)

| Version | Content | Rule |
|---|---|---|
| `V1__baseline.sql` | `pg_dump --schema-only` after POS-CAFE migration `20260922090000_pos_table_no_cleaning` (298 tables, 151 PG enums, indexes, partial uniques, 237 RLS policies, 13 triggers, 14 functions) | **never edit** |
| `V2__reference_data.sql` | rows inserted by POS-CAFE migrations (`DocumentTypeDef`) | **never edit** |
| `V3__platform_tables.sql` | `shedlock`, `event_publication(_archive)` (Spring-only) | append-only |
| `V4+` | every new schema change | one concern per file |

- New database: Flyway runs V1 → Vn.
- Existing POS-CAFE database: `baseline-on-migrate=true`, `baseline-version=2` → Flyway records a
  baseline at 2 and applies V3+ only. `_prisma_migrations` stays untouched (Nest can still run on
  it as long as V3+ are additive — keep them additive until cut-over is final).
- Regenerating the baseline (only if the reference schema changes): start Postgres 16, run
  `prisma migrate deploy` from `reference/nest-api`, `pg_dump --schema-only --no-owner --no-privileges
  --exclude-table=_prisma_migrations`, strip `\restrict`/`\unrestrict` lines and the
  `set_config('search_path', '', false)` call, write a **new** `V{n}` diff — never rewrite V1.

## 2. Naming

Prisma did not use `@map`, so tables and columns are the exact Prisma names in **quoted mixed case**
(`"CashSession"."organizationId"`). Hibernate is configured with
`PhysicalNamingStrategyStandardImpl` + `globally_quoted_identifiers=true`. In native SQL always quote:
`SELECT "id" FROM "Order" WHERE "organizationId" = ?`.

## 3. Generated persistence model

`node tools/prisma2jpa/generate.mjs` writes into `<module>/domain`:
- one `@Entity` per model (`@DynamicInsert`/`@DynamicUpdate` so column defaults apply),
- one `DbEnum` per Prisma enum with an auto-applied `AttributeConverter` (enums used by several
  modules go to `shared/domain`),
- one `JpaRepository` per entity.

Type mapping:

| Prisma | Postgres | Java |
|---|---|---|
| `String @id @default(uuid())` | `text` | `String`, assigned in `@PrePersist` |
| `BigInt @id @default(autoincrement())` | `bigserial` | `Long` + `IDENTITY` |
| `String` / `Int` / `BigInt` / `Float` / `Boolean` | text / int4 / int8 / float8 / bool | `String` / `Integer` / `Long` / `Double` / `Boolean` |
| `Decimal @db.Decimal(p,s)` (default 65,30) | `numeric(p,s)` | `BigDecimal` |
| `DateTime` | `timestamp(3) without time zone` (UTC values) | `Instant` (`hibernate.jdbc.time_zone=UTC`) |
| `DateTime @db.Date` | `date` | `LocalDate` |
| `Json` | `jsonb` | `String` with `@JdbcTypeCode(SqlTypes.JSON)` |
| `String[]` / enum[] | `text[]` / enum[] | `List<String>` with `@JdbcTypeCode(SqlTypes.ARRAY)` |
| enum | PG enum type | generated enum; JDBC URL needs **`stringtype=unspecified`** |
| `@updatedAt` / `createdAt @default(now())` | — | `@UpdateTimestamp` / `@CreationTimestamp` |
| `version` on Order, Invoice, PurchaseOrder | int | `@Version` |

Relations: only **same-module** many-to-one relations are generated as read-only `@ManyToOne(LAZY)`
navigations next to the FK column; cross-module references stay plain `String` ids
(aggregate-by-id). Add collections (`@OneToMany(mappedBy=...)`) by hand where a use case needs them.

Taking ownership: delete the first line (`// @generated ...`) of a file; the generator then skips it.
`ApplicationIT` validates every entity against the migrated schema on each build.

## 4. Money and quantities

- Stored as `numeric(20,6)` mostly (see column definitions). Compute with `BigDecimal` only.
- Port `kernel/common/money.ts`: `round(v, dp=2)` = `setScale(dp, HALF_UP)`; `approxEqual` epsilon
  `0.0001`; `qtyMul` = exact multiply then scale 6. Currency display decimals per `Currency`
  (UGX = 0).
- Never compare `BigDecimal` with `equals` (scale-sensitive) — use `compareTo`.

## 5. Soft delete and audit columns

Most tables have `deletedAt`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`. Deletes on such
tables are **soft** (set `deletedAt`, `updatedBy`) unless the Nest service hard-deletes (e.g.
`DELETE /pos/menu/items/:id/hard`). `createdBy/updatedBy` = acting user id (POS user if present).

## 6. Row-Level Security

Tenancy is enforced twice: every org-scoped query filters on `organizationId` in code, and Postgres row-level
security (RLS) backs it up.

- **Policy** (`V214`): every table with an `organizationId` column (plus `Organization` on `id`) has
  `tenant_isolation`. A transaction opened for a tenant (`TenantAwareTransactionManager` runs
  `set_config('app.org_id', …, true)`) reads and writes only that tenant's rows (and global rows where the column is
  nullable and NULL). A transaction without a tenant (jobs spanning organizations, login, bootstrap, seeds) is not
  restricted. A new migration that adds an org-scoped table must enable RLS and add the same policy;
  `TenantRowSecurityIT` fails otherwise.
- **Roles**: RLS is enabled, not forced, so the table owner bypasses it. Flyway and backups connect as the owner
  (`FLYWAY_USER` / `FLYWAY_PASSWORD`, default `DB_USER`); the API connects as `DB_USER`. When the two differ, the
  `db/callback/afterMigrate.sql` callback creates `DB_USER` (`NOSUPERUSER NOBYPASSRLS`, password `DB_PASSWORD`) if
  missing and grants it table, sequence and function rights plus `CREATE` on the schema (per-organization document
  sequences are created at run time). It warns if the role is a superuser or bypasses RLS.
- `docker-compose.yml` runs the API as `erp_app` (`DB_APP_USER` / `DB_APP_PASSWORD`); the integration tests run the
  API as a non-owner role too, so the whole suite is an RLS acceptance run.
- Backups run `pg_dump` as the owner (`BackupService` uses the Flyway credentials unless `PGUSER` is set): `pg_dump`
  refuses tables whose RLS would apply to it.

Existing database (one-time, as the owner), if you prefer to create the role yourself:

```sql
CREATE ROLE erp_app LOGIN PASSWORD '...' NOSUPERUSER NOBYPASSRLS;
-- then start the API with FLYWAY_USER=<owner> DB_USER=erp_app; the callback grants the rest.
```

## 7. Seed

`SeedRunner` (profile `seed`) ports `reference/nest-api/prisma/seed.ts` exactly: currencies (USD,
EUR, UGX), all permissions, organization `DEMO`, Administrator role, `admin@demo.test` /
`SEED_ADMIN_PASSWORD` (default `Admin@123` in dev only), taxes, partners `CUST-001`/`SUPP-001`,
fiscal year, branch + warehouse `MAIN`, table zones, Sunrise Cafe catalog (~32 products, opening
stock + ledger rows), register `MAIN-REG`, menu items/variants/recipes, roles Cashier/Waiter/
Manager/Kitchen with demo staff + PINs, kitchen stations, DMS registry. It refuses to run when the
`Organization` table is not empty. The parity harness seeds both databases with the **Nest** seed
so ids line up; the Java seed is for fresh Spring-only installs.
