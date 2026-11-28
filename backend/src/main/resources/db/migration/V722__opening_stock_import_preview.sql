-- IPR review 1 (finding 5): who previewed an opening-stock journal, so the inventory valuation separation-of-duties
-- pair can keep the previewer from also approving its import. New table only; no existing row changed; the ledger
-- trigger inventory_ledger_append_only is untouched.
CREATE TABLE "OpeningStockImportPreview" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "previewToken"   TEXT NOT NULL,
    "previewedById"  TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "OpeningStockImportPreview_org_token_idx" ON "OpeningStockImportPreview" ("organizationId", "previewToken");

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    ALTER TABLE public."OpeningStockImportPreview" ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation ON public."OpeningStockImportPreview";
    EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)',
                   'OpeningStockImportPreview', expr, expr);
END $$;
