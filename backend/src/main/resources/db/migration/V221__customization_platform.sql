-- V221: PR-11 (roadmap 2.5, ADR-S029) — customization platform.
--
-- 1. Custom fields: definitions per entity and a side table of values per record (core tables are untouched).
-- 2. Custom objects: definitions and their records (fields are custom-field definitions with entity x_<key>).
-- 3. Print templates with a version history.
-- 4. Automation rules and their runs (one run per rule and event).
-- 5. API clients for the OAuth 2.0 client-credentials grant.

CREATE TABLE "CustomFieldDefinition" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    entity           TEXT NOT NULL,
    key              TEXT NOT NULL,
    label            TEXT NOT NULL,
    type             TEXT NOT NULL,
    options          JSONB NOT NULL DEFAULT '{}'::jsonb,
    required         BOOLEAN NOT NULL DEFAULT false,
    "defaultValue"   JSONB,
    "helpText"       TEXT,
    "sortOrder"      INTEGER NOT NULL DEFAULT 0,
    "showInList"     BOOLEAN NOT NULL DEFAULT false,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CustomFieldDefinition_key" UNIQUE ("organizationId", entity, key),
    CONSTRAINT "CustomFieldDefinition_type" CHECK (type IN ('text', 'textarea', 'number', 'decimal', 'date', 'boolean',
        'select', 'multiselect', 'reference'))
);

CREATE TABLE "CustomFieldValue" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    entity           TEXT NOT NULL,
    "recordId"       TEXT NOT NULL,
    "values"         JSONB NOT NULL DEFAULT '{}'::jsonb,
    "updatedBy"      TEXT,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CustomFieldValue_record" UNIQUE ("organizationId", entity, "recordId")
);
CREATE INDEX "CustomFieldValue_values" ON "CustomFieldValue" USING gin ("values");

CREATE TABLE "CustomObjectDefinition" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    key              TEXT NOT NULL,
    label            TEXT NOT NULL,
    "pluralLabel"    TEXT NOT NULL,
    icon             TEXT,
    "displayField"   TEXT,
    description      TEXT,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CustomObjectDefinition_key" UNIQUE ("organizationId", key)
);

CREATE TABLE "CustomObjectRecord" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "objectId"       TEXT NOT NULL REFERENCES "CustomObjectDefinition" (id),
    title            TEXT,
    "values"         JSONB NOT NULL DEFAULT '{}'::jsonb,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "CustomObjectRecord_object" ON "CustomObjectRecord" ("organizationId", "objectId", "createdAt");
CREATE INDEX "CustomObjectRecord_values" ON "CustomObjectRecord" USING gin ("values");

CREATE TABLE "PrintTemplate" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "documentKind"   TEXT NOT NULL,
    name             TEXT NOT NULL,
    content          TEXT NOT NULL,
    css              TEXT NOT NULL DEFAULT '',
    "paperSize"      TEXT NOT NULL DEFAULT 'A4',
    orientation      TEXT NOT NULL DEFAULT 'portrait',
    "isDefault"      BOOLEAN NOT NULL DEFAULT false,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    version          INTEGER NOT NULL DEFAULT 1,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PrintTemplate_kind" CHECK ("documentKind" IN ('sales_invoice', 'credit_note', 'vendor_bill',
        'purchase_order', 'sales_order', 'sales_quotation')),
    CONSTRAINT "PrintTemplate_paper" CHECK ("paperSize" IN ('A4', 'A5', 'Letter')),
    CONSTRAINT "PrintTemplate_orientation" CHECK (orientation IN ('portrait', 'landscape'))
);
CREATE UNIQUE INDEX "PrintTemplate_default" ON "PrintTemplate" ("organizationId", "documentKind") WHERE "isDefault";

CREATE TABLE "PrintTemplateVersion" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "templateId"     TEXT NOT NULL REFERENCES "PrintTemplate" (id) ON DELETE CASCADE,
    version          INTEGER NOT NULL,
    content          TEXT NOT NULL,
    css              TEXT NOT NULL DEFAULT '',
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PrintTemplateVersion_version" UNIQUE ("templateId", version)
);

CREATE TABLE "AutomationRule" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    description      TEXT,
    trigger          TEXT NOT NULL,
    conditions       JSONB NOT NULL DEFAULT '[]'::jsonb,
    actions          JSONB NOT NULL DEFAULT '[]'::jsonb,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "runCount"       INTEGER NOT NULL DEFAULT 0,
    "lastRunAt"      TIMESTAMP(3),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "AutomationRule_trigger" ON "AutomationRule" ("organizationId", trigger) WHERE "isActive";

CREATE TABLE "AutomationRun" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "ruleId"         TEXT NOT NULL REFERENCES "AutomationRule" (id) ON DELETE CASCADE,
    "eventKey"       TEXT NOT NULL,
    entity           TEXT,
    "recordId"       TEXT,
    status           TEXT NOT NULL,
    matched          BOOLEAN NOT NULL DEFAULT false,
    results          JSONB NOT NULL DEFAULT '[]'::jsonb,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AutomationRun_once" UNIQUE ("ruleId", "eventKey"),
    CONSTRAINT "AutomationRun_status" CHECK (status IN ('succeeded', 'partial', 'failed', 'skipped'))
);
CREATE INDEX "AutomationRun_rule" ON "AutomationRun" ("ruleId", "createdAt");

CREATE TABLE "ApiClient" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    "clientId"       TEXT NOT NULL UNIQUE,
    "secretHash"     TEXT NOT NULL,
    "secretHint"     TEXT NOT NULL,
    scopes           TEXT[] NOT NULL DEFAULT '{}',
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "expiresAt"      TIMESTAMP(3),
    "lastUsedAt"     TIMESTAMP(3),
    "revokedAt"      TIMESTAMP(3),
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Row-level security like every tenant table (V214).
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['CustomFieldDefinition', 'CustomFieldValue', 'CustomObjectDefinition',
            'CustomObjectRecord', 'PrintTemplate', 'PrintTemplateVersion', 'AutomationRule', 'AutomationRun',
            'ApiClient'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
