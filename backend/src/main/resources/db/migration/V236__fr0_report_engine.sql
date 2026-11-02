-- V236: Financial reporting FR-0 (ADR-S043) — report engine: configurable financial statement layouts, report run
-- log (reproducibility), saved views and scheduled delivery. Reports read the ledger; nothing here posts.

CREATE TABLE "FinancialStatementLayout" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    kind             TEXT NOT NULL CHECK (kind IN ('PL', 'BS')),
    "isSystem"       BOOLEAN NOT NULL DEFAULT false,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FinancialStatementLayout_code" UNIQUE ("organizationId", code)
);

CREATE TABLE "FinancialStatementLine" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "layoutId"       TEXT NOT NULL REFERENCES "FinancialStatementLayout" (id) ON DELETE CASCADE,
    "sortOrder"      INTEGER NOT NULL,
    code             TEXT NOT NULL,
    label            TEXT NOT NULL,
    "lineType"       TEXT NOT NULL CHECK ("lineType" IN ('HEADER', 'ACCOUNTS', 'FORMULA')),
    -- ACCOUNTS: {"categories":[...],"sections":[...],"accountCodes":[...],"excludeAccountCodes":[...]}
    selector         JSONB,
    formula          TEXT,
    -- DEBIT: amount = debit - credit; CREDIT: amount = credit - debit
    sign             TEXT NOT NULL DEFAULT 'CREDIT' CHECK (sign IN ('DEBIT', 'CREDIT')),
    indent           INTEGER NOT NULL DEFAULT 0,
    bold             BOOLEAN NOT NULL DEFAULT false,
    -- management measure (EBITDA, margins): shown apart from the IFRS lines
    "isKpi"          BOOLEAN NOT NULL DEFAULT false,
    percent          BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT "FinancialStatementLine_code" UNIQUE ("layoutId", code)
);
CREATE INDEX "FinancialStatementLine_layout_idx" ON "FinancialStatementLine" ("layoutId", "sortOrder");

CREATE TABLE "ReportRun" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "reportId"       TEXT NOT NULL,
    params           JSONB NOT NULL,
    "resultHash"     TEXT NOT NULL,
    "rowCount"       INTEGER NOT NULL,
    "durationMs"     INTEGER NOT NULL,
    trigger          TEXT NOT NULL DEFAULT 'USER' CHECK (trigger IN ('USER', 'EXPORT', 'SCHEDULE', 'VERIFY')),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ReportRun_org_idx" ON "ReportRun" ("organizationId", "createdAt" DESC);

CREATE TABLE "ReportSavedView" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "reportId"       TEXT NOT NULL,
    name             TEXT NOT NULL,
    params           JSONB NOT NULL,
    "ownerId"        TEXT NOT NULL,
    shared           BOOLEAN NOT NULL DEFAULT false,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ReportSavedView_report_idx" ON "ReportSavedView" ("organizationId", "reportId");

CREATE TABLE "ReportSchedule" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "reportId"       TEXT NOT NULL,
    name             TEXT NOT NULL,
    params           JSONB NOT NULL,
    format           TEXT NOT NULL CHECK (format IN ('csv', 'xlsx', 'pdf')),
    frequency        TEXT NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    "dayOfWeek"      INTEGER CHECK ("dayOfWeek" BETWEEN 1 AND 7),
    "dayOfMonth"     INTEGER CHECK ("dayOfMonth" BETWEEN 1 AND 28),
    "hourOfDay"      INTEGER NOT NULL DEFAULT 7 CHECK ("hourOfDay" BETWEEN 0 AND 23),
    recipients       TEXT[] NOT NULL DEFAULT '{}',
    "ownerId"        TEXT NOT NULL,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "nextRunAt"      TIMESTAMP(3) NOT NULL,
    "lastRunAt"      TIMESTAMP(3),
    "lastStatus"     TEXT,
    "lastError"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ReportSchedule_due_idx" ON "ReportSchedule" ("isActive", "nextRunAt");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['FinancialStatementLayout', 'FinancialStatementLine', 'ReportRun', 'ReportSavedView',
                             'ReportSchedule'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
