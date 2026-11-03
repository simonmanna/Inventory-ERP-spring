-- ACC-4 (B9, docs/accounting/ACC-0-verification.md): allocation history of credits against open documents.
--
-- A customer credit note settled its invoice by editing "amountResidual" directly, leaving no allocation record (the
-- PaymentAllocation table needs a payment). CreditAllocation records every application of a credit document (credit
-- note) to an open document (invoice), at posting or later; the credit's unapplied remainder stays on its residual.
-- No GL effect: both documents sit on the same receivable control account.
CREATE TABLE "CreditAllocation" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "creditDocumentId" TEXT           NOT NULL REFERENCES "Document" (id) ON DELETE CASCADE,
    "documentId"       TEXT           NOT NULL REFERENCES "Document" (id) ON DELETE CASCADE,
    amount             NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CreditAllocation_org_idx" ON "CreditAllocation" ("organizationId");
CREATE INDEX "CreditAllocation_document_idx" ON "CreditAllocation" ("documentId");
CREATE INDEX "CreditAllocation_credit_idx" ON "CreditAllocation" ("creditDocumentId");

ALTER TABLE "CreditAllocation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CreditAllocation"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));
