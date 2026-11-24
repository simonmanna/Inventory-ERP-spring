-- PLM (V440, ADR-S063) created its tables with the V1-style tenant policy, keyed on app.org_id with no fallback, after
-- V214 had moved every other tenant table to one standard policy. Under the application role (subject to RLS) a
-- transaction without a tenant therefore saw no PLM rows at all: cross-organization jobs, seeds and the integration
-- tests' direct queries came back empty. Give these tables the standard policy of V214 (as V605 did for CRM): a
-- tenant transaction sees and writes only its own organization's rows; a transaction without a tenant is not
-- restricted, exactly as for every other tenant table.
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['PlmItem', 'PlmLifecycleEvent', 'PlmChangeStage', 'PlmChangeStageApprover',
            'PlmChangeRequest', 'PlmChangeApproval', 'PlmChangeActivity', 'PlmDocument'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
