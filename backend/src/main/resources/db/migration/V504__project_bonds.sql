-- V492: bonds and guarantees register (ADR-S074 §1) — performance bonds, bank guarantees, advance-payment guarantees,
-- retention bonds and bid bonds, issued by us to clients and received by us from subcontractors / suppliers. Each
-- lifecycle step (activate, extend, reduce, release, call, expire) is an event row; the optional GL postings (cash
-- margin, bank commission, a call) carry the event id as their source.

CREATE TABLE "ProjectBond" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    number             TEXT NOT NULL,
    "bondType"         TEXT NOT NULL,
    direction          TEXT NOT NULL,
    "projectId"        TEXT REFERENCES "Project" (id),
    "subcontractId"    TEXT REFERENCES "Subcontract" (id),
    "counterpartyId"   TEXT NOT NULL REFERENCES "Partner" (id),
    issuer             TEXT NOT NULL,
    "issuerType"       TEXT NOT NULL DEFAULT 'bank',
    "bondNumber"       TEXT,
    amount             NUMERIC(20, 2) NOT NULL CHECK (amount > 0),
    "originalAmount"   NUMERIC(20, 2) NOT NULL CHECK ("originalAmount" > 0),
    "currencyCode"     TEXT NOT NULL,
    "issueDate"        DATE NOT NULL,
    "expiryDate"       DATE NOT NULL,
    status             TEXT NOT NULL DEFAULT 'draft',
    "marginHeld"       NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK ("marginHeld" >= 0),
    "commissionPaid"   NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "calledAmount"     NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "documentId"       TEXT,
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectBond_number" UNIQUE ("organizationId", number),
    CONSTRAINT "ProjectBond_type" CHECK ("bondType" IN ('performance', 'bank_guarantee', 'advance_payment',
        'retention', 'bid')),
    CONSTRAINT "ProjectBond_direction" CHECK (direction IN ('issued', 'received')),
    CONSTRAINT "ProjectBond_issuerType" CHECK ("issuerType" IN ('bank', 'insurer')),
    CONSTRAINT "ProjectBond_status" CHECK (status IN ('draft', 'active', 'extended', 'reduced', 'released', 'called',
        'expired')),
    CONSTRAINT "ProjectBond_dates" CHECK ("expiryDate" >= "issueDate")
);
CREATE INDEX "ProjectBond_org_status_idx" ON "ProjectBond" ("organizationId", status, "expiryDate");
CREATE INDEX "ProjectBond_project_idx" ON "ProjectBond" ("projectId");

CREATE TABLE "ProjectBondEvent" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "bondId"           TEXT NOT NULL REFERENCES "ProjectBond" (id),
    action             TEXT NOT NULL,
    "eventDate"        DATE NOT NULL,
    "previousAmount"   NUMERIC(20, 2),
    amount             NUMERIC(20, 2),
    "previousExpiry"   DATE,
    "expiryDate"       DATE,
    margin             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    commission         NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "calledAmount"     NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "recoveryTo"       TEXT,
    "journalEntryId"   TEXT,
    "costLineId"       TEXT,
    reason             TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProjectBondEvent_action" CHECK (action IN ('create', 'activate', 'extend', 'reduce', 'release', 'call',
        'expire'))
);
CREATE INDEX "ProjectBondEvent_bond_idx" ON "ProjectBondEvent" ("bondId", "createdAt");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ProjectBond', 'ProjectBondEvent'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['bond:read', 'bond:manage', 'bond:post'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
