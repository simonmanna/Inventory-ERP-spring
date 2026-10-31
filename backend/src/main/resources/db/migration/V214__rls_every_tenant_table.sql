-- PR-1: row-level security as a tenancy backstop on every organization-scoped table.
--
-- V1 had policies on its 219 tables only, keyed on app.org_id with no fallback: a role subject to RLS saw nothing in
-- background jobs, logins and seeds (no tenant), so production ran as a superuser and RLS did nothing. This migration
-- covers every table with an "organizationId" column (the ~300 added since V1 too) with one policy:
--
--   * a transaction opened for a tenant (TenantAwareTransactionManager sets app.org_id) sees and writes only that
--     tenant's rows, plus global rows where the column is nullable and NULL;
--   * a transaction without a tenant (jobs that span organizations, login, bootstrap, seeds) is not restricted.
--
-- RLS is enabled, not forced: the table owner (Flyway, backups) is not subject to it. The API connects as a separate
-- non-owner role (DB_USER), created and granted by db/callback/afterMigrate.sql; see docs/DATABASE.md section 6.
DO $$
DECLARE
    t record;
    expr text;
BEGIN
    FOR t IN
        SELECT c.table_name, c.is_nullable
        FROM information_schema.columns c
        JOIN information_schema.tables tb ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
        WHERE c.table_schema = 'public' AND c.column_name = 'organizationId' AND tb.table_type = 'BASE TABLE'
    LOOP
        expr := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                || 'current_setting(''app.org_id'', true)';
        IF t.is_nullable = 'YES' THEN
            expr := expr || ' OR "organizationId" IS NULL';
        END IF;
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.table_name);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t.table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t.table_name, expr,
                expr);
    END LOOP;
END $$;

-- The organization table itself (V1 keyed it on id, with no fallback for work without a tenant).
DROP POLICY IF EXISTS tenant_isolation ON public."Organization";
CREATE POLICY tenant_isolation ON public."Organization"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR id = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR id = current_setting('app.org_id', true));
