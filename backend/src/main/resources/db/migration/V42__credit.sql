-- V27: Credit management (ADR-S011 §3, phase S3; docs/spec/credit.md) — customer credit limits (per organization ×
-- optional branch × currency), the organization credit policy, immutable credit decisions taken at check points, and
-- the hold kind on sales orders. Partner."creditLimit" / "creditHold" (baseline) stay the default limit and the hard
-- hold flag.

CREATE TABLE "CreditLimit" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"       TEXT           NOT NULL,
    "branchId"        TEXT,
    "currencyCode"    TEXT,
    amount            NUMERIC(20, 6) NOT NULL CHECK (amount >= 0),
    "riskCategory"    TEXT CHECK ("riskCategory" IS NULL OR "riskCategory" IN ('low', 'medium', 'high', 'blocked')),
    "reviewDate"      DATE,
    "periodDays"      INTEGER CHECK ("periodDays" IS NULL OR "periodDays" >= 0),
    "validFrom"       DATE,
    "validTo"         DATE,
    notes             TEXT,
    "createdById"     TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);
CREATE UNIQUE INDEX "CreditLimit_scope_key" ON "CreditLimit" ("organizationId", "partnerId", COALESCE("branchId", ''),
    COALESCE("currencyCode", ''));

CREATE TABLE "CreditPolicy" (
    "organizationId"          TEXT         NOT NULL PRIMARY KEY REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- Invoices overdue more than this many days trigger the overdue action.
    "overdueDays"             INTEGER      NOT NULL DEFAULT 30 CHECK ("overdueDays" >= 0),
    "overdueAction"           TEXT         NOT NULL DEFAULT 'approval' CHECK ("overdueAction" IN ('none', 'approval', 'hard')),
    "depositsReduceExposure"  BOOLEAN      NOT NULL DEFAULT true,
    "undeliveredBasis"        TEXT         NOT NULL DEFAULT 'gross' CHECK ("undeliveredBasis" IN ('gross', 'net')),
    "groupLimits"             BOOLEAN      NOT NULL DEFAULT true,
    -- Check points that are enforced (order_confirm cannot be switched off).
    checkpoints               TEXT[]       NOT NULL DEFAULT ARRAY['quote_submit', 'order_confirm', 'amendment',
        'trip_dispatch']::TEXT[],
    -- Daily job: set Partner.creditHold for customers overdue beyond overdueDays when overdueAction = 'hard'.
    "autoHold"                BOOLEAN      NOT NULL DEFAULT false,
    "updatedById"             TEXT,
    "updatedAt"               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ('order_confirm' = ANY (checkpoints))
);

-- Immutable record of every credit check (and override).
CREATE TABLE "CreditDecision" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"         TEXT           NOT NULL,
    checkpoint          TEXT           NOT NULL,
    "documentType"      TEXT,
    "documentId"        TEXT,
    "documentNumber"    TEXT,
    amount              NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "limitAmount"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    result              TEXT           NOT NULL CHECK (result IN ('pass', 'hold', 'blocked', 'override')),
    reasons             TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    exposure            JSONB          NOT NULL,
    "overridesId"       TEXT REFERENCES "CreditDecision" (id),
    "decidedById"       TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CreditDecision_org_partner_idx" ON "CreditDecision" ("organizationId", "partnerId", "createdAt" DESC);
CREATE INDEX "CreditDecision_document_idx" ON "CreditDecision" ("documentType", "documentId");

CREATE FUNCTION credit_decision_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Credit decisions are immutable';
END;
$$;
CREATE TRIGGER "CreditDecision_immutable" BEFORE UPDATE OR DELETE ON "CreditDecision"
    FOR EACH ROW EXECUTE FUNCTION credit_decision_immutable();

-- Customer groups for group credit limits: a child customer shares its parent's limit.
ALTER TABLE "Partner" ADD COLUMN "parentId" TEXT REFERENCES "Partner" (id) ON DELETE SET NULL;
CREATE INDEX "Partner_parent_idx" ON "Partner" ("parentId") WHERE "parentId" IS NOT NULL;

-- Why an order is held: manual, credit (needs credit:override to release) or customer (Partner on hard hold).
ALTER TABLE "SalesOrder" ADD COLUMN "holdKind" TEXT CHECK ("holdKind" IS NULL
    OR "holdKind" IN ('manual', 'credit', 'customer'));
UPDATE "SalesOrder" SET "holdKind" = CASE WHEN "holdReason" LIKE 'Customer is on credit hold%' THEN 'customer'
    ELSE 'manual' END WHERE "onHold";

ALTER TABLE "CreditLimit" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CreditLimit" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CreditPolicy" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CreditPolicy" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CreditDecision" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CreditDecision" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['credit:read', 'credit:manage', 'credit:override']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['credit:read', 'credit:manage', 'credit:override'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['credit:read'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE name <> 'Administrator' AND "deletedAt" IS NULL AND 'sales:order:read' = ANY (permissions);
