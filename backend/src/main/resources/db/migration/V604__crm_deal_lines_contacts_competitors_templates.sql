-- CRM enhancements T3b (post-parity): deal product lines, several contacts per deal, competitors, forecast categories
-- and email templates.

CREATE TABLE "CrmCompetitor" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmCompetitor_org_idx" ON "CrmCompetitor" ("organizationId");
ALTER TABLE "CrmCompetitor" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmCompetitor" USING (("organizationId" = current_setting('app.org_id', true)));

-- competitorId: who won the deal when it was lost. forecastCategory: the salesperson's call on an open deal
-- (NULL = pipeline).
ALTER TABLE "Deal"
    ADD COLUMN "competitorId"     TEXT REFERENCES "CrmCompetitor" (id) ON DELETE SET NULL,
    ADD COLUMN "forecastCategory" TEXT CHECK ("forecastCategory" IN ('pipeline', 'best_case', 'commit', 'omitted'));

-- Expected products of an opportunity; they pre-fill a new quotation.
CREATE TABLE "CrmDealLine" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "dealId"           TEXT           NOT NULL REFERENCES "Deal" (id) ON DELETE CASCADE,
    "sortOrder"        INTEGER        NOT NULL DEFAULT 0,
    "productId"        TEXT REFERENCES "Product" (id) ON DELETE SET NULL,
    "variantId"        TEXT,
    description        TEXT           NOT NULL,
    quantity           NUMERIC(18, 4) NOT NULL CHECK (quantity > 0),
    "unitPrice"        NUMERIC(18, 4) NOT NULL CHECK ("unitPrice" >= 0),
    "discountPercent"  NUMERIC(5, 2)  NOT NULL DEFAULT 0 CHECK ("discountPercent" BETWEEN 0 AND 100),
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmDealLine_deal_idx" ON "CrmDealLine" ("dealId", "sortOrder");
ALTER TABLE "CrmDealLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmDealLine" USING (("organizationId" = current_setting('app.org_id', true)));

-- More people on a deal than the one main contact (decision maker, finance, technical...).
CREATE TABLE "CrmDealContact" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "dealId"         TEXT         NOT NULL REFERENCES "Deal" (id) ON DELETE CASCADE,
    "contactId"      TEXT         NOT NULL REFERENCES "Contact" (id) ON DELETE CASCADE,
    role             TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CrmDealContact_deal_contact_key" UNIQUE ("dealId", "contactId")
);
ALTER TABLE "CrmDealContact" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmDealContact" USING (("organizationId" = current_setting('app.org_id', true)));

-- Reusable emails with {{placeholders}}, sent from a lead or an opportunity.
CREATE TABLE "CrmEmailTemplate" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    subject          TEXT         NOT NULL,
    body             TEXT         NOT NULL,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrmEmailTemplate_org_name_key" ON "CrmEmailTemplate" ("organizationId", lower(name));
ALTER TABLE "CrmEmailTemplate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmEmailTemplate" USING (("organizationId" = current_setting('app.org_id', true)));
