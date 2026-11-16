-- ADR-S073 (2/3): bank matching rules, partial and grouped matches.
--
-- BankMatchRule: per bank (GL) account, matched on description / reference (contains or regex), an absolute amount
-- range and a direction. action 'match_payment' pairs the line with a posted payment candidate (direction-aware,
-- D-B5); action 'post_entry' books the line against counterAccountId (bank charges, interest, fees) with an optional
-- tax included in the line amount, then marks the line matched.
--
-- BankStatementMatch: allocations of statement lines to payments for partial and grouped matches (one line to
-- several payments, several lines to one payment). A line is 'partial' while its allocations do not cover it.
-- The single-payment match (BankStatementLine.matchedPaymentId) is unchanged.
CREATE TABLE "BankMatchRule" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "bankAccountId"     TEXT           NOT NULL,
    name                TEXT           NOT NULL,
    priority            INTEGER        NOT NULL DEFAULT 100,
    "isActive"          BOOLEAN        NOT NULL DEFAULT true,
    "matchField"        TEXT           NOT NULL DEFAULT 'any',
    "matchType"         TEXT           NOT NULL DEFAULT 'contains',
    pattern             TEXT,
    "minAmount"         NUMERIC(20, 6),
    "maxAmount"         NUMERIC(20, 6),
    direction           TEXT           NOT NULL DEFAULT 'any',
    action              TEXT           NOT NULL,
    "counterAccountId"  TEXT,
    "taxId"             TEXT,
    "partnerId"         TEXT,
    "journalCode"       TEXT           NOT NULL DEFAULT 'BANK',
    "dateToleranceDays" INTEGER        NOT NULL DEFAULT 3,
    "entryDescription"  TEXT,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3),
    CONSTRAINT "BankMatchRule_field" CHECK ("matchField" IN ('description', 'reference', 'any')),
    CONSTRAINT "BankMatchRule_type" CHECK ("matchType" IN ('contains', 'regex')),
    CONSTRAINT "BankMatchRule_direction" CHECK (direction IN ('inbound', 'outbound', 'any')),
    CONSTRAINT "BankMatchRule_action" CHECK (action IN ('match_payment', 'post_entry'))
);
CREATE INDEX "BankMatchRule_account_idx" ON "BankMatchRule" ("organizationId", "bankAccountId", priority)
    WHERE "deletedAt" IS NULL;

ALTER TABLE "BankStatementLine" ADD COLUMN "matchedRuleId" TEXT;
ALTER TABLE "BankStatementLine" ADD COLUMN "journalEntryId" TEXT;

CREATE TABLE "BankStatementMatch" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "statementLineId" TEXT           NOT NULL REFERENCES "BankStatementLine" (id) ON DELETE CASCADE,
    "paymentId"       TEXT           NOT NULL,
    amount            NUMERIC(20, 6) NOT NULL,
    "groupId"         TEXT           NOT NULL,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "BankStatementMatch_amount" CHECK (amount > 0)
);
CREATE INDEX "BankStatementMatch_line_idx" ON "BankStatementMatch" ("organizationId", "statementLineId");
CREATE INDEX "BankStatementMatch_payment_idx" ON "BankStatementMatch" ("organizationId", "paymentId");

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['BankMatchRule', 'BankStatementMatch'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (coalesce(current_setting(''app.org_id'', true),'
                || ' '''') = '''' OR "organizationId" = current_setting(''app.org_id'', true)) WITH CHECK'
                || ' (coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" ='
                || ' current_setting(''app.org_id'', true))', t);
    END LOOP;
END $$;
