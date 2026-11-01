-- V220: PR-8 (roadmap 2.7, ADR-S028) — intercompany & multi-company.
--
-- 1. Company groups (install-wide): a parent organization and its subsidiaries with an ownership percentage.
-- 2. Intercompany partners: in each company, the partner that stands for another member company.
-- 3. Per-company intercompany settings (mirroring of sales invoices into vendor bills).
-- 4. Mirrored documents log and intercompany journals (two entries, one per company, one transaction).

CREATE TABLE "CompanyGroup" (
    id                      TEXT PRIMARY KEY,
    name                    TEXT NOT NULL,
    "parentOrganizationId"  TEXT NOT NULL UNIQUE REFERENCES "Organization" (id),
    "currencyCode"          TEXT NOT NULL,
    "createdBy"             TEXT,
    "createdAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- An organization belongs to at most one group (unique memberOrganizationId).
CREATE TABLE "CompanyGroupMember" (
    id                      TEXT PRIMARY KEY,
    "groupId"               TEXT NOT NULL REFERENCES "CompanyGroup" (id) ON DELETE CASCADE,
    "memberOrganizationId"  TEXT NOT NULL UNIQUE REFERENCES "Organization" (id),
    "ownershipPercent"      NUMERIC(9, 4) NOT NULL DEFAULT 100,
    "joinedAt"              TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "addedBy"               TEXT,
    CONSTRAINT "CompanyGroupMember_pct" CHECK ("ownershipPercent" > 0 AND "ownershipPercent" <= 100)
);
CREATE INDEX "CompanyGroupMember_group" ON "CompanyGroupMember" ("groupId");

CREATE TABLE "IntercompanyPartner" (
    id                          TEXT PRIMARY KEY,
    "organizationId"            TEXT NOT NULL REFERENCES "Organization" (id),
    "counterpartOrganizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "partnerId"                 TEXT NOT NULL REFERENCES "Partner" (id),
    "createdAt"                 TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"                 TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "IntercompanyPartner_pair" UNIQUE ("organizationId", "counterpartOrganizationId"),
    CONSTRAINT "IntercompanyPartner_partner" UNIQUE ("organizationId", "partnerId"),
    CONSTRAINT "IntercompanyPartner_self" CHECK ("organizationId" <> "counterpartOrganizationId")
);

CREATE TABLE "IntercompanySettings" (
    "organizationId"        TEXT PRIMARY KEY REFERENCES "Organization" (id),
    "mirrorMode"            TEXT NOT NULL DEFAULT 'draft',
    "billExpenseAccountId"  TEXT REFERENCES "Account" (id),
    "updatedAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedBy"             TEXT,
    CONSTRAINT "IntercompanySettings_mode" CHECK ("mirrorMode" IN ('off', 'draft', 'post'))
);

-- One row per mirrored source document; organizationId = the source company.
CREATE TABLE "IntercompanyTransaction" (
    id                          TEXT PRIMARY KEY,
    "organizationId"            TEXT NOT NULL REFERENCES "Organization" (id),
    "sourceDocumentId"          TEXT NOT NULL,
    "sourceDocumentType"        TEXT NOT NULL,
    "sourceDocumentNumber"      TEXT,
    "targetOrganizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "targetDocumentId"          TEXT,
    "targetDocumentType"        TEXT,
    "targetDocumentNumber"      TEXT,
    amount                      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "currencyCode"              TEXT,
    status                      TEXT NOT NULL,
    error                       TEXT,
    attempts                    INTEGER NOT NULL DEFAULT 0,
    "createdAt"                 TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"                 TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "IntercompanyTransaction_source" UNIQUE ("organizationId", "sourceDocumentId"),
    CONSTRAINT "IntercompanyTransaction_status" CHECK (status IN ('mirrored', 'failed', 'skipped'))
);
CREATE INDEX "IntercompanyTransaction_target" ON "IntercompanyTransaction" ("targetOrganizationId");

-- Both halves of an intercompany journal; the row lives in the source company.
CREATE TABLE "IntercompanyJournal" (
    id                          TEXT PRIMARY KEY,
    "organizationId"            TEXT NOT NULL REFERENCES "Organization" (id),
    "number"                    TEXT NOT NULL,
    "targetOrganizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "postingDate"               TIMESTAMP(3) NOT NULL,
    description                 TEXT NOT NULL,
    amount                      NUMERIC(20, 6) NOT NULL,
    "currencyCode"              TEXT NOT NULL,
    "targetAmount"              NUMERIC(20, 6) NOT NULL,
    "targetCurrencyCode"        TEXT NOT NULL,
    "exchangeRate"              NUMERIC(20, 10) NOT NULL DEFAULT 1,
    "sourceAccountId"           TEXT NOT NULL,
    "targetAccountId"           TEXT NOT NULL,
    "sourceEntryId"             TEXT NOT NULL,
    "targetEntryId"             TEXT NOT NULL,
    "createdBy"                 TEXT,
    "createdAt"                 TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "IntercompanyJournal_number" UNIQUE ("organizationId", "number")
);
CREATE INDEX "IntercompanyJournal_target" ON "IntercompanyJournal" ("targetOrganizationId");

-- Row-level security like every tenant table (V214). The group tables are install-wide: the service checks
-- membership on every read and write.
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['IntercompanyPartner', 'IntercompanySettings', 'IntercompanyTransaction',
            'IntercompanyJournal'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
