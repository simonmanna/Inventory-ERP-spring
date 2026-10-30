-- V33: Sales agreements, rebates, commissions and targets (ADR-S011, phase S9; sales-postings.md P15).
-- Agreements (blanket / contract) price their releases (sales orders with "agreementId") ahead of every pricelist and
-- track the released balance. Rebate accruals post Dr Sales Rebates (contra revenue) / Cr Rebate Accrual and settle by
-- credit note (Dr Rebate Accrual / Cr AR). Commission statements turn into HR payroll earnings when approved.

CREATE TABLE "SalesAgreement" (
    id                TEXT          NOT NULL PRIMARY KEY,
    "organizationId"  TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number            TEXT          NOT NULL,
    "partnerId"       TEXT          NOT NULL,
    name              TEXT          NOT NULL,
    kind              TEXT          NOT NULL DEFAULT 'blanket' CHECK (kind IN ('blanket', 'contract')),
    status            TEXT          NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'expired', 'closed',
        'cancelled')),
    "startDate"       DATE          NOT NULL,
    "endDate"         DATE          NOT NULL,
    "currencyCode"    TEXT,
    "paymentTermId"   TEXT,
    "siteIds"         TEXT[]        NOT NULL DEFAULT ARRAY[]::TEXT[],
    -- Allow releases beyond a line's contract quantity (else they go to approval).
    "allowOverRelease" BOOLEAN      NOT NULL DEFAULT false,
    notes             TEXT,
    "approvedById"    TEXT,
    "approvedAt"      TIMESTAMP(3),
    "createdById"     TEXT,
    "createdAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("endDate" >= "startDate")
);
CREATE UNIQUE INDEX "SalesAgreement_org_number_key" ON "SalesAgreement" ("organizationId", number);
CREATE INDEX "SalesAgreement_org_partner_idx" ON "SalesAgreement" ("organizationId", "partnerId", status);

CREATE TABLE "SalesAgreementLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "agreementId"     TEXT           NOT NULL REFERENCES "SalesAgreement" (id) ON DELETE CASCADE,
    "productId"       TEXT           NOT NULL,
    "uomId"           TEXT,
    "contractQty"     NUMERIC(20, 6) NOT NULL CHECK ("contractQty" > 0),
    "unitPrice"       NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    "discountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 0 CHECK ("discountPercent" BETWEEN 0 AND 100),
    "minReleaseQty"   NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minReleaseQty" >= 0),
    UNIQUE ("agreementId", "productId")
);

ALTER TABLE "SalesOrder" ADD COLUMN "agreementId" TEXT REFERENCES "SalesAgreement" (id) ON DELETE SET NULL;
CREATE INDEX "SalesOrder_agreement_idx" ON "SalesOrder" ("agreementId") WHERE "agreementId" IS NOT NULL;

CREATE TABLE "RebateProgram" (
    id                   TEXT          NOT NULL PRIMARY KEY,
    "organizationId"     TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name                 TEXT          NOT NULL,
    "partnerId"          TEXT,
    "partnerCategoryId"  TEXT,
    "productId"          TEXT,
    "productCategoryId"  TEXT,
    "periodType"         TEXT          NOT NULL DEFAULT 'quarter' CHECK ("periodType" IN ('month', 'quarter', 'year')),
    "startDate"          DATE          NOT NULL,
    "endDate"            DATE,
    basis                TEXT          NOT NULL DEFAULT 'quantity' CHECK (basis IN ('quantity', 'value')),
    -- [{"from": 5000, "rate": 1500, "rateType": "per_unit" | "percent"}], highest reached tier applies to all.
    tiers                JSONB         NOT NULL,
    status               TEXT          NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed')),
    "createdAt"          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("partnerId" IS NULL OR "partnerCategoryId" IS NULL),
    CHECK ("productId" IS NULL OR "productCategoryId" IS NULL)
);

CREATE TABLE "RebateAccrual" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "programId"        TEXT           NOT NULL REFERENCES "RebateProgram" (id),
    "partnerId"        TEXT           NOT NULL,
    "periodStart"      DATE           NOT NULL,
    "periodEnd"        DATE           NOT NULL,
    "qualifyingQty"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "qualifyingValue"  NUMERIC(20, 6) NOT NULL DEFAULT 0,
    -- Amount accrued so far (sum of the accrual postings) and the latest tier rate.
    amount             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    rate               NUMERIC(20, 6) NOT NULL DEFAULT 0,
    status             TEXT           NOT NULL DEFAULT 'accrued' CHECK (status IN ('accrued', 'settled')),
    "creditNoteId"     TEXT,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE ("programId", "partnerId", "periodStart")
);

CREATE TABLE "CommissionRule" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name                TEXT           NOT NULL,
    "salespersonId"     TEXT,
    "teamId"            TEXT,
    "productCategoryId" TEXT,
    basis               TEXT           NOT NULL DEFAULT 'invoiced' CHECK (basis IN ('invoiced', 'paid')),
    method              TEXT           NOT NULL DEFAULT 'percent_revenue'
        CHECK (method IN ('percent_revenue', 'percent_margin', 'per_unit')),
    rate                NUMERIC(20, 6) NOT NULL CHECK (rate >= 0),
    priority            INTEGER        NOT NULL DEFAULT 0,
    "validFrom"         DATE,
    "validTo"           DATE,
    "isActive"          BOOLEAN        NOT NULL DEFAULT true,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE "CommissionStatement" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number           TEXT           NOT NULL,
    "salespersonId"  TEXT           NOT NULL,
    "periodStart"    DATE           NOT NULL,
    "periodEnd"      DATE           NOT NULL,
    status           TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'approved', 'cancelled')),
    total            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    lines            JSONB          NOT NULL DEFAULT '[]'::jsonb,
    "payrollInputId" TEXT,
    "approvedById"   TEXT,
    "approvedAt"     TIMESTAMP(3),
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CommissionStatement_org_number_key" ON "CommissionStatement" ("organizationId", number);
CREATE UNIQUE INDEX "CommissionStatement_period_key" ON "CommissionStatement" ("organizationId", "salespersonId",
    "periodStart") WHERE status <> 'cancelled';

CREATE TABLE "SalesTarget" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- First day of the month.
    month               DATE           NOT NULL,
    "salespersonId"     TEXT,
    "teamId"            TEXT,
    "branchId"          TEXT,
    "productCategoryId" TEXT,
    amount              NUMERIC(20, 6) NOT NULL CHECK (amount >= 0),
    quantity            NUMERIC(20, 6),
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (extract(day FROM month) = 1)
);

ALTER TABLE "SalesAgreement" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesAgreement" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesAgreementLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesAgreementLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "RebateProgram" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "RebateProgram" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "RebateAccrual" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "RebateAccrual" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CommissionRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CommissionRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CommissionStatement" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CommissionStatement"
    USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesTarget" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesTarget" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:agreement:read', 'sales:agreement:manage', 'sales:rebate:manage', 'sales:commission:read',
    'sales:commission:manage', 'sales:target:manage']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:agreement:read', 'sales:agreement:manage',
        'sales:rebate:manage', 'sales:commission:read', 'sales:commission:manage', 'sales:target:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
