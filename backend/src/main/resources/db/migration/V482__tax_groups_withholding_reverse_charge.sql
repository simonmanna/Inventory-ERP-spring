-- ADR-S071: tax groups, several taxes per document line, withholding tax on vendor bills (with certificates) and
-- reverse-charge VAT. Documents that use none of these keep their exact rows: no breakdown, withholding 0.
--
-- 1. Tax."isGroup": a group tax carries no rate of its own; its ordered children (TaxGroupMember) apply instead.
--    Tax."reverseCharge": on a vendor bill the tax is self-assessed (input and output VAT of equal amount), nothing is
--    added to the payable.
-- 2. DocumentLine."taxIds": the taxes requested for the line (groups unexpanded), NULL on single-tax lines.
--    DocumentLineTax: the per-tax breakdown of such a line (kind tax | withholding | reverse_charge).
-- 3. Document."withholdingAmount": withholding deducted on the bill; the residual is net of it.
-- 4. WithholdingCertificate: issued (we withheld from a supplier) or received (a customer withheld from us).
ALTER TABLE "Tax" ADD COLUMN "isGroup" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Tax" ADD COLUMN "reverseCharge" BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE "TaxGroupMember" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "groupTaxId"     TEXT         NOT NULL REFERENCES "Tax" (id) ON DELETE CASCADE,
    "childTaxId"     TEXT         NOT NULL REFERENCES "Tax" (id) ON DELETE CASCADE,
    sequence         INTEGER      NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "TaxGroupMember_group_child_key" ON "TaxGroupMember" ("groupTaxId", "childTaxId");
CREATE INDEX "TaxGroupMember_org_idx" ON "TaxGroupMember" ("organizationId");

ALTER TABLE "DocumentLine" ADD COLUMN "taxIds" TEXT[];

CREATE TABLE "DocumentLineTax" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "documentId"     TEXT           NOT NULL REFERENCES "Document" (id) ON DELETE CASCADE,
    "documentLineId" TEXT           NOT NULL REFERENCES "DocumentLine" (id) ON DELETE CASCADE,
    "taxId"          TEXT           NOT NULL REFERENCES "Tax" (id),
    sequence         INTEGER        NOT NULL DEFAULT 0,
    kind             TEXT           NOT NULL CHECK (kind IN ('tax', 'withholding', 'reverse_charge')),
    rate             NUMERIC(9, 6)  NOT NULL,
    base             NUMERIC(20, 6) NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "DocumentLineTax_document_idx" ON "DocumentLineTax" ("documentId");
CREATE INDEX "DocumentLineTax_line_idx" ON "DocumentLineTax" ("documentLineId");
CREATE INDEX "DocumentLineTax_org_tax_idx" ON "DocumentLineTax" ("organizationId", "taxId");

ALTER TABLE "Document" ADD COLUMN "withholdingAmount" NUMERIC(20, 6) NOT NULL DEFAULT 0;

CREATE TABLE "WithholdingCertificate" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number            TEXT           NOT NULL,
    direction         TEXT           NOT NULL CHECK (direction IN ('issued', 'received')),
    "partnerId"       TEXT           NOT NULL REFERENCES "Partner" (id),
    "documentId"      TEXT REFERENCES "Document" (id) ON DELETE SET NULL,
    "paymentId"       TEXT REFERENCES "Payment" (id) ON DELETE SET NULL,
    "taxId"           TEXT REFERENCES "Tax" (id) ON DELETE SET NULL,
    "certificateDate" TIMESTAMP(3)   NOT NULL,
    "baseAmount"      NUMERIC(20, 6) NOT NULL,
    rate              NUMERIC(9, 6),
    amount            NUMERIC(20, 6) NOT NULL,
    "currencyCode"    TEXT,
    status            TEXT           NOT NULL DEFAULT 'issued' CHECK (status IN ('issued', 'cancelled')),
    "journalEntryId"  TEXT,
    notes             TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "cancelledAt"     TIMESTAMP(3)
);
CREATE UNIQUE INDEX "WithholdingCertificate_org_direction_number_key"
    ON "WithholdingCertificate" ("organizationId", direction, number);
CREATE INDEX "WithholdingCertificate_org_date_idx" ON "WithholdingCertificate" ("organizationId", "certificateDate");
CREATE INDEX "WithholdingCertificate_document_idx" ON "WithholdingCertificate" ("documentId");
CREATE INDEX "WithholdingCertificate_payment_idx" ON "WithholdingCertificate" ("paymentId");

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['TaxGroupMember', 'DocumentLineTax', 'WithholdingCertificate'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (coalesce(current_setting(''app.org_id'', true),'
                || ' '''') = '''' OR "organizationId" = current_setting(''app.org_id'', true)) WITH CHECK'
                || ' (coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" ='
                || ' current_setting(''app.org_id'', true))', t);
    END LOOP;
END $$;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['withholding:read', 'withholding:update'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['withholding:read'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE 'report:accounting' = ANY (permissions) AND NOT ('withholding:read' = ANY (permissions)) AND "deletedAt" IS NULL;
