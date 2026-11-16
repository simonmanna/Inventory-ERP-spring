-- V493: subcontractor compliance (ADR-S074 §2) — compliance documents per supplier (optionally per subcontract):
-- insurance certificates, tax clearance, social security (NSSF) clearance, trade licence and lien waivers
-- (conditional / unconditional, per valuation or per payment). Gating per document type (off / warn / block) is
-- applied when a subcontract valuation is certified and when a vendor bill of a subcontract is paid; a block can be
-- passed once by a recorded, audited override. Every gate decision that was not "clear" is logged.

CREATE TABLE "ComplianceDocument" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "partnerId"        TEXT NOT NULL REFERENCES "Partner" (id),
    "subcontractId"    TEXT REFERENCES "Subcontract" (id),
    "documentType"     TEXT NOT NULL,
    "documentNumber"   TEXT,
    issuer             TEXT,
    "validFrom"        DATE,
    "validTo"          DATE,
    amount             NUMERIC(20, 2),
    "waiverType"       TEXT,
    "waiverScope"      TEXT,
    "valuationId"      TEXT REFERENCES "SubcontractValuation" (id),
    "paymentId"        TEXT REFERENCES "Payment" (id),
    "throughDate"      DATE,
    "documentId"       TEXT,
    status             TEXT NOT NULL DEFAULT 'pending',
    "verifiedBy"       TEXT,
    "verifiedAt"       TIMESTAMP(3),
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ComplianceDocument_type" CHECK ("documentType" IN ('insurance_certificate', 'tax_clearance',
        'social_security_clearance', 'trade_licence', 'lien_waiver')),
    CONSTRAINT "ComplianceDocument_status" CHECK (status IN ('pending', 'verified', 'rejected')),
    CONSTRAINT "ComplianceDocument_waiverType" CHECK ("waiverType" IS NULL OR "waiverType" IN ('conditional',
        'unconditional')),
    CONSTRAINT "ComplianceDocument_waiverScope" CHECK ("waiverScope" IS NULL OR "waiverScope" IN ('valuation',
        'payment')),
    CONSTRAINT "ComplianceDocument_dates" CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);
CREATE INDEX "ComplianceDocument_partner_idx" ON "ComplianceDocument" ("organizationId", "partnerId", "documentType");
CREATE INDEX "ComplianceDocument_subcontract_idx" ON "ComplianceDocument" ("subcontractId");

-- One row per document type an organisation gates on; a type without a row is "off".
CREATE TABLE "ComplianceRequirement" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "documentType"     TEXT NOT NULL,
    mode               TEXT NOT NULL DEFAULT 'off',
    "updatedBy"        TEXT,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ComplianceRequirement_type" UNIQUE ("organizationId", "documentType"),
    CONSTRAINT "ComplianceRequirement_mode" CHECK (mode IN ('off', 'warn', 'block'))
);

-- A one-time pass through a blocking gate: for a valuation of the subcontract, or for paying one vendor bill.
CREATE TABLE "ComplianceOverride" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "subcontractId"    TEXT NOT NULL REFERENCES "Subcontract" (id),
    gate               TEXT NOT NULL,
    "billId"           TEXT,
    reason             TEXT NOT NULL,
    "consumedAt"       TIMESTAMP(3),
    "consumedBy"       TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ComplianceOverride_gate" CHECK (gate IN ('valuation', 'payment'))
);
CREATE INDEX "ComplianceOverride_open_idx" ON "ComplianceOverride" ("subcontractId", gate) WHERE "consumedAt" IS NULL;

CREATE TABLE "ComplianceGateLog" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "subcontractId"    TEXT NOT NULL REFERENCES "Subcontract" (id),
    gate               TEXT NOT NULL,
    "billId"           TEXT,
    outcome            TEXT NOT NULL,
    issues             JSONB NOT NULL DEFAULT '[]'::jsonb,
    "overrideId"       TEXT REFERENCES "ComplianceOverride" (id),
    "userId"           TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ComplianceGateLog_gate" CHECK (gate IN ('valuation', 'payment')),
    CONSTRAINT "ComplianceGateLog_outcome" CHECK (outcome IN ('warned', 'blocked', 'overridden'))
);
CREATE INDEX "ComplianceGateLog_subcontract_idx" ON "ComplianceGateLog" ("subcontractId", "createdAt");

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['ComplianceDocument', 'ComplianceRequirement', 'ComplianceOverride',
        'ComplianceGateLog'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['compliance:read', 'compliance:manage',
        'compliance:override'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
