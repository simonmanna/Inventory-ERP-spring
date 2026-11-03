-- ACC-7 (docs/accounting/ACC-EXECUTION-PLAN.md): fiscal positions that act, VAT settlement and the tax-period lock.
--
-- 1. FiscalPositionTax maps a source tax to a target tax (NULL target = exempt) for documents under the position.
--    Partner."fiscalPositionId" is the customer / supplier default, used when a document states none.
-- 2. TaxSettlement records a settled VAT period: the input VAT offset against output VAT and the net due / refundable.
--    Postings to the output / input tax accounts dated on or before the latest settled period end are refused.
CREATE TABLE "FiscalPositionTax" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "fiscalPositionId" TEXT         NOT NULL REFERENCES "FiscalPosition" (id) ON DELETE CASCADE,
    "sourceTaxId"      TEXT         NOT NULL REFERENCES "Tax" (id) ON DELETE CASCADE,
    "targetTaxId"      TEXT REFERENCES "Tax" (id) ON DELETE SET NULL,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FiscalPositionTax_position_source_key" ON "FiscalPositionTax" ("fiscalPositionId", "sourceTaxId");
CREATE INDEX "FiscalPositionTax_org_idx" ON "FiscalPositionTax" ("organizationId");

ALTER TABLE "Partner" ADD COLUMN "fiscalPositionId" TEXT REFERENCES "FiscalPosition" (id) ON DELETE SET NULL;

CREATE TABLE "TaxSettlement" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "periodFrom"     TIMESTAMP(3)   NOT NULL,
    "periodTo"       TIMESTAMP(3)   NOT NULL,
    "outputTax"      NUMERIC(20, 6) NOT NULL,
    "inputTax"       NUMERIC(20, 6) NOT NULL,
    "netPayable"     NUMERIC(20, 6) NOT NULL,
    "journalEntryId" TEXT,
    "settledBy"      TEXT,
    "settledAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "TaxSettlement_org_to_key" ON "TaxSettlement" ("organizationId", "periodTo");

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['FiscalPositionTax', 'TaxSettlement'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (coalesce(current_setting(''app.org_id'', true),'
                || ' '''') = '''' OR "organizationId" = current_setting(''app.org_id'', true)) WITH CHECK'
                || ' (coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" ='
                || ' current_setting(''app.org_id'', true))', t);
    END LOOP;
END $$;
