-- ACC-6 (B5, docs/accounting/ACC-0-verification.md): a completed bank reconciliation is a locked statement.
--
-- Each row proves one statement date: statement closing balance + book items not yet on the statement
-- = ledger balance + statement items not yet in the books (difference must be zero). Once locked, statement lines
-- dated on or before statementDate can no longer be imported, matched, unmatched or excluded.
CREATE TABLE "BankReconciliationStatement" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "bankAccountId"       TEXT           NOT NULL,
    "statementDate"       TIMESTAMP(3)   NOT NULL,
    "closingBalance"      NUMERIC(20, 6) NOT NULL,
    "ledgerBalance"       NUMERIC(20, 6) NOT NULL,
    "outstandingBook"     NUMERIC(20, 6) NOT NULL,
    "unrecordedStatement" NUMERIC(20, 6) NOT NULL,
    difference            NUMERIC(20, 6) NOT NULL,
    notes                 TEXT,
    "lockedBy"            TEXT,
    "lockedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "BankReconciliationStatement_account_date_key"
    ON "BankReconciliationStatement" ("organizationId", "bankAccountId", "statementDate");

ALTER TABLE "BankReconciliationStatement" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "BankReconciliationStatement"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));
