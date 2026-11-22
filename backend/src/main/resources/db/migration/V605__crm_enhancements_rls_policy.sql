-- CRM enhancements (V602-V604): give the new tenant tables the standard row-level security policy of V214, so the
-- public, token-authorized lookups that run before the organization is known (website lead forms, customer quote
-- links, calendar feeds) can find their row. With no app.org_id set the policy admits every row, exactly as for every
-- other tenant table; every scoped query still sees only its own organization.
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['CrmCalendarFeed', 'CrmLeadForm', 'SalesQuoteLink', 'CrmCompetitor', 'CrmDealLine',
            'CrmDealContact', 'CrmEmailTemplate'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
