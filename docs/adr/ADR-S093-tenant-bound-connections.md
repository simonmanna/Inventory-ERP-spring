# ADR-S093: Tenant-bound connections (row-level security outside transactions)

- Status: Accepted (IPR Phase 4, stream F). Owner sign-off item: the remaining gap below.
- Date: 2026-11-27
- Finding: S-6 (P2, promoted for every organisation that keeps stock by R6).

## Context

V214 gives every table with an `organizationId` column the policy

```
coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true)
```

`TenantAwareTransactionManager.doBegin` sets `app.org_id` with `SET LOCAL` when a transaction begins inside a tenant
context. Nothing set it for statements that run **outside** a transaction. Most read services (for example
`InventoryQueryService`, `InventoryReportsService`, many `JdbcClient` reads) are not `@Transactional`, so in a normal
signed-in request they ran with an empty `app.org_id` and the policy let every row through. Code filters by
`organizationId` everywhere we checked, so this was a latent risk, not a live leak; but RLS was no backstop for those
reads.

What passed, exactly (verified on the owner-split test database, app role `erp`, `NOBYPASSRLS`):

| Context | Before | After |
|---|---|---|
| Tenant, inside a transaction | own organisation only | own organisation only (unchanged) |
| Tenant, outside a transaction | **every row** | own organisation only |
| No tenant (jobs, login, bootstrap, seeds, public portal links, `InstallWide`, `TenantContext.unscoped`) | every row | every row (system mode, unchanged) |
| A session that never ran through the API's pool (psql as `DB_USER`) | every row | every row |

## Decision

1. **Bind each pooled connection to the caller's tenant on checkout.** `TenantBindingDataSource` wraps the
   application `dataSource` bean (a `BeanPostProcessor` in `TenancyConfig`). On every `getConnection()` it runs
   `set_config('app.org_id', <tenant id or ''>, false)`. The value is set on **every** checkout, also to `''` for
   work without a tenant, so it can never leak from one borrower of a connection to the next. JPA, the transaction
   manager and every `JdbcClient` share the wrapped pool, so a transaction's connection is the same object Spring
   binds (no second pool). Transactions still apply their own `SET LOCAL`.
2. **System mode stays explicit by absence of a tenant.** Jobs that span organisations keep working without change.
   Cross-organisation work inside a request already had to use `InstallWide` or `TenantContext.unscoped(...)` with a
   new transaction (transactions were restricted before); those paths are unchanged.
3. **Escape hatch:** `app.tenancy.bind-connections=false` (default `true`) restores the old behaviour, for an
   emergency only.

## Remaining gap (not fixed here; owner sign-off)

A statement run with **no tenant** is unrestricted by design (system mode). A default-deny policy (the empty setting
denies; named jobs set an explicit system marker such as `app.org_id = '*'`) was considered and rejected for this
stream because:

- every code path without a tenant would have to be found and marked: about 57 classes with `@Scheduled` jobs,
  module listeners, login and token refresh, bootstrap, invite acceptance, public portal and quote links, webhooks,
  licensing, seeds. A miss fails silently (a job sees 0 rows, or an insert fails `WITH CHECK`), not loudly;
- it needs a migration that rewrites the policy on about 400 tables, and every later migration (other streams add
  tables) must copy the new expression.

Detector instead: `TenantRlsBackstopIT` pins the matrix above (a tenant's statement outside a transaction is denied
other organisations' rows; the binding never leaks across pooled connections; no-tenant work and `InstallWide` still
span organisations), and `TenantRowSecurityIT` keeps checking that the API role cannot bypass RLS and that every
tenant table has the policy. `DatabaseRoleValidator` refuses a production start under a role that bypasses RLS.

A follow-up (default deny with named system scopes) is possible later as its own ADR, after an inventory of every
no-tenant entry point.

## Consequences

- One extra round trip (`SELECT set_config(...)`) per connection checkout. Non-transactional reads take a connection
  per statement, so a read-heavy non-transactional service pays it per statement. Measured cost is the local round
  trip only; no change to pool size.
- Transaction pooling in front of Postgres (PgBouncer in transaction mode) would break session settings; we run
  without a pooler (native deploy and compose), and the setting is re-applied on every checkout anyway.
- A future read that must span organisations inside a request must use `InstallWide` or `TenantContext.unscoped`,
  as transactions already required.
