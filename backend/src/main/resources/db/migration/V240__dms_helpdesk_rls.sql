-- V240: row-level tenant isolation for the DMS (V238) and helpdesk (V239) tables, same policy as every org table.

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['DmsWorkspace', 'DmsFolder', 'DmsDocument', 'DmsDocumentVersion', 'DmsDocumentLink',
                             'DmsTagCategory', 'DmsTag', 'DmsAccessRule', 'DmsDocumentActivity', 'DmsRequest',
                             'DmsShareLink', 'HdTeam', 'HdStage', 'HdTicketType', 'HdTicket', 'HdTicketMessage',
                             'HdLetterTemplate'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
