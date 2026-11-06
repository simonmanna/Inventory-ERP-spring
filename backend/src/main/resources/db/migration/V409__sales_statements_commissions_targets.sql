-- SG-10: statement delivery log, customer confirmation, disputes.
CREATE TABLE "SalesStatementLog" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL,
    "periodFrom"     DATE,
    "periodTo"       DATE,
    "closingBalance" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "sentTo"         TEXT,
    delivered        BOOLEAN        NOT NULL DEFAULT false,
    error            TEXT,
    "confirmedAt"    TIMESTAMP(3),
    "confirmedBy"    TEXT,
    "confirmNote"    TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesStatementLog_partner_idx" ON "SalesStatementLog" ("organizationId", "partnerId", "createdAt" DESC);
ALTER TABLE "SalesStatementLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesStatementLog" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "SalesStatementDispute" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL,
    "documentNumber" TEXT,
    amount           NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    reason           TEXT           NOT NULL,
    status           TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'rejected')),
    resolution       TEXT,
    "resolvedById"   TEXT,
    "resolvedAt"     TIMESTAMP(3),
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesStatementDispute_partner_idx" ON "SalesStatementDispute" ("organizationId", "partnerId", status);
ALTER TABLE "SalesStatementDispute" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesStatementDispute" USING (("organizationId" = current_setting('app.org_id', true)));

-- SG-11: product and customer rules, tiers, splits, adjustments, disputes, paid status, carry-forward. Existing plans keep
-- working: every new column is empty for them, and only organizations created from now on default to collected sales.
ALTER TABLE "SalesCommissionRule"
    ADD COLUMN "productId" TEXT,
    ADD COLUMN "partnerId" TEXT,
    ADD COLUMN "partnerCategoryId" TEXT,
    ADD COLUMN tiers JSONB,
    ADD COLUMN splits JSONB;
ALTER TABLE "SalesSettings" ADD COLUMN "commissionDefaultBasis" TEXT NOT NULL DEFAULT 'paid'
    CHECK ("commissionDefaultBasis" IN ('invoiced', 'paid'));
UPDATE "SalesSettings" SET "commissionDefaultBasis" = 'invoiced';
ALTER TABLE "CommissionStatement" DROP CONSTRAINT "CommissionStatement_status_check";
ALTER TABLE "CommissionStatement" ADD CONSTRAINT "CommissionStatement_status_check"
    CHECK (status IN ('draft', 'approved', 'paid', 'cancelled'));
ALTER TABLE "CommissionStatement"
    ADD COLUMN "linesTotal" NUMERIC(20, 6),
    ADD COLUMN "adjustmentTotal" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "carriedIn" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "carriedFromIds" TEXT[],
    ADD COLUMN "paidAt" TIMESTAMP(3),
    ADD COLUMN "paidById" TEXT,
    ADD COLUMN "paymentReference" TEXT;

CREATE TABLE "CommissionAdjustment" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "statementId"    TEXT           NOT NULL REFERENCES "CommissionStatement" (id) ON DELETE CASCADE,
    amount           NUMERIC(20, 6) NOT NULL,
    reason           TEXT           NOT NULL,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE "CommissionAdjustment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CommissionAdjustment" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "CommissionDispute" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "statementId"    TEXT         NOT NULL REFERENCES "CommissionStatement" (id) ON DELETE CASCADE,
    reason           TEXT         NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    resolution       TEXT,
    "raisedById"     TEXT,
    "resolvedById"   TEXT,
    "resolvedAt"     TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE "CommissionDispute" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CommissionDispute" USING (("organizationId" = current_setting('app.org_id', true)));

-- SG-12: targets on more than revenue and for any period. month stays the period start, so existing monthly targets read
-- as before; periodEnd NULL means the end of that month.
ALTER TABLE "SalesTarget" DROP CONSTRAINT "SalesTarget_month_check";
ALTER TABLE "SalesTarget"
    ADD COLUMN metric TEXT NOT NULL DEFAULT 'revenue'
        CHECK (metric IN ('revenue', 'gross_profit', 'collection', 'new_customers', 'order_count', 'order_value')),
    ADD COLUMN "periodType" TEXT NOT NULL DEFAULT 'month' CHECK ("periodType" IN ('week', 'month', 'quarter', 'year', 'custom')),
    ADD COLUMN "periodEnd" DATE,
    ADD COLUMN "alertPercents" INTEGER[] NOT NULL DEFAULT ARRAY[80, 100],
    ADD COLUMN "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "SalesTarget" ADD CONSTRAINT "SalesTarget_period_check" CHECK ("periodEnd" IS NULL OR "periodEnd" >= month);

CREATE TABLE "SalesTargetAlert" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "targetId"       TEXT         NOT NULL REFERENCES "SalesTarget" (id) ON DELETE CASCADE,
    kind             TEXT         NOT NULL,
    "actual"         NUMERIC(20, 6),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE ("targetId", kind)
);
ALTER TABLE "SalesTargetAlert" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesTargetAlert" USING (("organizationId" = current_setting('app.org_id', true)));
