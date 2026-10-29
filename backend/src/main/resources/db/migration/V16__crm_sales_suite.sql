-- V16: CRM sales suite (ADR-S010) — configurable pipeline stages, leads vs opportunities, sales teams, tags, sources,
-- lost reasons, stage history, planned activities with reminders, quotations with approval and conversion to an
-- Order or a proforma invoice, customer-specific price rules, saved filters and per-organization CRM settings.
--
-- Additive extension beyond the NestJS reference. The legacy "Deal".stage enum is kept in sync from the configured
-- stage (each stage maps to a legacy DealStage), so the parity routes under /crm/deals keep their contract. The only
-- change to an existing constraint: "Deal"."partnerId" becomes nullable because a lead may not have a customer yet.

-- ---------------------------------------------------------------- sales teams

CREATE TABLE "CrmTeam" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    "leaderId"       TEXT,
    "memberIds"      TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    -- Monthly invoicing / won-revenue target shown on the dashboard.
    "monthlyTarget"  NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("monthlyTarget" >= 0),
    color            TEXT           NOT NULL DEFAULT 'sky',
    "isActive"       BOOLEAN        NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrmTeam_org_name_key" ON "CrmTeam" ("organizationId", lower(name));

-- ---------------------------------------------------------------- configurable pipeline stages

CREATE TABLE "CrmStage" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT          NOT NULL,
    sequence         INTEGER       NOT NULL DEFAULT 0,
    -- Default win probability (%) of an opportunity in this stage (weighted pipeline, forecast).
    probability      NUMERIC(5, 2) NOT NULL DEFAULT 10 CHECK (probability BETWEEN 0 AND 100),
    "isWon"          BOOLEAN       NOT NULL DEFAULT false,
    -- Folded stages render collapsed in the kanban.
    fold             BOOLEAN       NOT NULL DEFAULT false,
    color            TEXT          NOT NULL DEFAULT 'slate',
    -- Shown as a tooltip: what must be true before a deal moves here.
    requirements     TEXT,
    -- Legacy DealStage written to "Deal".stage for the parity routes.
    "legacyStage"    "DealStage"   NOT NULL DEFAULT 'qualified',
    -- Restrict the stage to one sales team (NULL = every team).
    "teamId"         TEXT REFERENCES "CrmTeam" (id) ON DELETE CASCADE,
    "rottingDays"    INTEGER CHECK ("rottingDays" IS NULL OR "rottingDays" > 0),
    "isActive"       BOOLEAN       NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmStage_org_seq_idx" ON "CrmStage" ("organizationId", sequence);

-- ---------------------------------------------------------------- lookups: tags, sources, lost reasons

CREATE TABLE "CrmTag" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    color            TEXT         NOT NULL DEFAULT 'sky',
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrmTag_org_name_key" ON "CrmTag" ("organizationId", lower(name));

CREATE TABLE "CrmSource" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrmSource_org_name_key" ON "CrmSource" ("organizationId", lower(name));

CREATE TABLE "CrmLostReason" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "CrmLostReason_org_name_key" ON "CrmLostReason" ("organizationId", lower(name));

-- ---------------------------------------------------------------- Deal: leads + opportunities

ALTER TABLE "Deal" ALTER COLUMN "partnerId" DROP NOT NULL;
ALTER TABLE "Deal"
    ADD COLUMN kind              TEXT          NOT NULL DEFAULT 'opportunity' CHECK (kind IN ('lead', 'opportunity')),
    ADD COLUMN "stageId"         TEXT REFERENCES "CrmStage" (id) ON DELETE SET NULL,
    ADD COLUMN status            TEXT          NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'won', 'lost')),
    -- NULL = use the stage probability.
    ADD COLUMN probability       NUMERIC(5, 2) CHECK (probability IS NULL OR probability BETWEEN 0 AND 100),
    ADD COLUMN priority          INTEGER       NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 3),
    ADD COLUMN "teamId"          TEXT REFERENCES "CrmTeam" (id) ON DELETE SET NULL,
    ADD COLUMN "contactId"       TEXT,
    ADD COLUMN "contactName"     TEXT,
    ADD COLUMN "companyName"     TEXT,
    ADD COLUMN email             TEXT,
    ADD COLUMN phone             TEXT,
    ADD COLUMN city              TEXT,
    ADD COLUMN country           TEXT,
    ADD COLUMN "sourceId"        TEXT REFERENCES "CrmSource" (id) ON DELETE SET NULL,
    ADD COLUMN "tagIds"          TEXT[]        NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN "lostReasonId"    TEXT REFERENCES "CrmLostReason" (id) ON DELETE SET NULL,
    ADD COLUMN "lostNote"        TEXT,
    ADD COLUMN "wonAt"           TIMESTAMP(3),
    ADD COLUMN "lostAt"          TIMESTAMP(3),
    ADD COLUMN "convertedAt"     TIMESTAMP(3),
    ADD COLUMN "stageChangedAt"  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN "createdById"     TEXT,
    ADD COLUMN "sequenceInStage" INTEGER       NOT NULL DEFAULT 0;
CREATE INDEX "Deal_org_kind_status_idx" ON "Deal" ("organizationId", kind, status) WHERE "deletedAt" IS NULL;
CREATE INDEX "Deal_org_stageId_idx" ON "Deal" ("organizationId", "stageId") WHERE "deletedAt" IS NULL;
CREATE INDEX "Deal_org_createdAt_idx" ON "Deal" ("organizationId", "createdAt");

-- Every legacy row is an opportunity whose outcome follows its legacy stage.
UPDATE "Deal" SET status = CASE stage::text WHEN 'won' THEN 'won' WHEN 'lost' THEN 'lost' ELSE 'open' END,
                  "stageChangedAt" = "updatedAt";

-- ---------------------------------------------------------------- stage history (time-in-stage reports)

CREATE TABLE "CrmStageHistory" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "dealId"          TEXT         NOT NULL REFERENCES "Deal" (id) ON DELETE CASCADE,
    "fromStageId"     TEXT,
    "toStageId"       TEXT,
    "fromStatus"      TEXT,
    "toStatus"        TEXT         NOT NULL,
    -- Seconds the deal spent in the stage it left.
    "secondsInStage"  BIGINT,
    "changedById"     TEXT,
    "changedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmStageHistory_deal_idx" ON "CrmStageHistory" ("dealId", "changedAt");
CREATE INDEX "CrmStageHistory_org_changed_idx" ON "CrmStageHistory" ("organizationId", "changedAt");

-- ---------------------------------------------------------------- Activity: planned activities, assignment, reminders

ALTER TABLE "Activity"
    ADD COLUMN "assignedToId"   TEXT,
    ADD COLUMN "remindAt"       TIMESTAMP(3),
    ADD COLUMN "reminderSentAt" TIMESTAMP(3),
    ADD COLUMN outcome          TEXT;
CREATE INDEX "Activity_org_assignee_status_idx" ON "Activity" ("organizationId", "assignedToId", status)
    WHERE "deletedAt" IS NULL;
CREATE INDEX "Activity_reminder_due_idx" ON "Activity" ("remindAt")
    WHERE "remindAt" IS NOT NULL AND "reminderSentAt" IS NULL AND "deletedAt" IS NULL;

-- ---------------------------------------------------------------- customer-specific pricing

CREATE TABLE "CrmPriceRule" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name                 TEXT           NOT NULL,
    -- Who: one customer, or a customer category, or everyone (both NULL).
    "partnerId"          TEXT,
    "partnerCategoryId"  TEXT,
    -- What: one product, or a product category, or every product (both NULL).
    "productId"          TEXT,
    "productCategoryId"  TEXT,
    "minQuantity"        NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("minQuantity" >= 0),
    kind                 TEXT           NOT NULL DEFAULT 'discount_percent'
        CHECK (kind IN ('fixed_price', 'discount_percent')),
    value                NUMERIC(20, 6) NOT NULL CHECK (value >= 0),
    "currencyCode"       TEXT,
    "validFrom"          DATE,
    "validTo"            DATE,
    priority             INTEGER        NOT NULL DEFAULT 0,
    "isActive"           BOOLEAN        NOT NULL DEFAULT true,
    notes                TEXT,
    "createdById"        TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (kind <> 'discount_percent' OR value <= 100),
    CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);
CREATE INDEX "CrmPriceRule_org_partner_idx" ON "CrmPriceRule" ("organizationId", "partnerId") WHERE "isActive";
CREATE INDEX "CrmPriceRule_org_product_idx" ON "CrmPriceRule" ("organizationId", "productId") WHERE "isActive";

-- ---------------------------------------------------------------- quotations

CREATE TABLE "CrmQuotation" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number              TEXT           NOT NULL,
    revision            INTEGER        NOT NULL DEFAULT 0,
    "dealId"            TEXT REFERENCES "Deal" (id) ON DELETE SET NULL,
    "partnerId"         TEXT           NOT NULL,
    "contactId"         TEXT,
    "ownerId"           TEXT,
    status              TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'pending_approval',
        'approved', 'sent', 'accepted', 'rejected', 'expired', 'cancelled', 'converted')),
    "issueDate"         DATE           NOT NULL DEFAULT CURRENT_DATE,
    "validUntil"        DATE,
    "currencyCode"      TEXT           NOT NULL DEFAULT 'USD',
    subtotal            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "discountTotal"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "taxAmount"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    total               NUMERIC(20, 6) NOT NULL DEFAULT 0,
    -- Highest line discount vs. list price (%), drives the approval threshold.
    "maxDiscountPercent" NUMERIC(9, 4) NOT NULL DEFAULT 0,
    notes               TEXT,
    terms               TEXT,
    "approvalReasons"   TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    "approvalRequestId" TEXT,
    "approvedById"      TEXT,
    "approvedAt"        TIMESTAMP(3),
    "rejectedById"      TEXT,
    "rejectionReason"   TEXT,
    "sentAt"            TIMESTAMP(3),
    "acceptedAt"        TIMESTAMP(3),
    "convertedType"     TEXT CHECK ("convertedType" IS NULL OR "convertedType" IN ('order', 'proforma')),
    "convertedId"       TEXT,
    "convertedNumber"   TEXT,
    "convertedAt"       TIMESTAMP(3),
    "createdById"       TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3)
);
CREATE UNIQUE INDEX "CrmQuotation_org_number_key" ON "CrmQuotation" ("organizationId", number);
CREATE INDEX "CrmQuotation_org_status_idx" ON "CrmQuotation" ("organizationId", status) WHERE "deletedAt" IS NULL;
CREATE INDEX "CrmQuotation_deal_idx" ON "CrmQuotation" ("dealId");

CREATE TABLE "CrmQuotationLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "quotationId"     TEXT           NOT NULL REFERENCES "CrmQuotation" (id) ON DELETE CASCADE,
    "sortOrder"       INTEGER        NOT NULL DEFAULT 0,
    "productId"       TEXT,
    description       TEXT           NOT NULL,
    quantity          NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "listPrice"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitPrice"       NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    "discountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 0 CHECK ("discountPercent" BETWEEN 0 AND 100),
    "priceSource"     TEXT           NOT NULL DEFAULT 'list' CHECK ("priceSource" IN ('list', 'rule', 'customer', 'manual')),
    "priceRuleId"     TEXT,
    "taxId"           TEXT,
    "taxInclusive"    BOOLEAN        NOT NULL DEFAULT false,
    "lineSubtotal"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "lineTax"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "lineTotal"       NUMERIC(20, 6) NOT NULL DEFAULT 0
);
CREATE INDEX "CrmQuotationLine_quote_idx" ON "CrmQuotationLine" ("quotationId", "sortOrder");

-- ---------------------------------------------------------------- saved filters (favorites) and settings

CREATE TABLE "CrmSavedFilter" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL,
    scope            TEXT         NOT NULL CHECK (scope IN ('pipeline', 'leads', 'quotations', 'activities', 'report')),
    name             TEXT         NOT NULL,
    filters          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    shared           BOOLEAN      NOT NULL DEFAULT false,
    "isDefault"      BOOLEAN      NOT NULL DEFAULT false,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmSavedFilter_org_scope_idx" ON "CrmSavedFilter" ("organizationId", scope, "userId");

CREATE TABLE "CrmSettings" (
    "organizationId"         TEXT           NOT NULL PRIMARY KEY REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- Quotations above either threshold need approval (0 = no amount threshold).
    "quoteMaxDiscountPercent" NUMERIC(9, 4) NOT NULL DEFAULT 10,
    "quoteApprovalAmount"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    -- Block / route to approval when the customer is on credit hold or the quote exceeds the credit limit.
    "creditCheck"            BOOLEAN        NOT NULL DEFAULT true,
    "quoteValidityDays"      INTEGER        NOT NULL DEFAULT 30 CHECK ("quoteValidityDays" > 0),
    "defaultQuoteTerms"      TEXT,
    -- Mark the opportunity won when its quotation is converted.
    "autoWinOnConversion"    BOOLEAN        NOT NULL DEFAULT true,
    "assignmentMode"         TEXT           NOT NULL DEFAULT 'manual'
        CHECK ("assignmentMode" IN ('manual', 'round_robin', 'least_loaded')),
    "assignmentPool"         TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    "roundRobinCursor"       INTEGER        NOT NULL DEFAULT 0,
    -- Default "rotting" age (days without stage change) flagged red on kanban cards.
    "rottingDays"            INTEGER        NOT NULL DEFAULT 14 CHECK ("rottingDays" > 0),
    "useLeads"               BOOLEAN        NOT NULL DEFAULT true,
    "updatedAt"              TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "CrmTeam" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmTeam" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmStage" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmStage" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmTag" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmTag" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmSource" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmSource" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmLostReason" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmLostReason" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmStageHistory" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmStageHistory" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmPriceRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmPriceRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmQuotation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmQuotation" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmQuotationLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmQuotationLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmSavedFilter" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmSavedFilter" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CrmSettings" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmSettings" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions (ADR-S010 §6)

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY[
    'crm:deal:view_all', 'crm:lead:read', 'crm:lead:write', 'crm:quotation:read', 'crm:quotation:write',
    'crm:quotation:approve', 'crm:quotation:convert', 'crm:pricing:read', 'crm:pricing:manage', 'crm:report:read',
    'crm:configure']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY[
        'crm:deal:view_all', 'crm:lead:read', 'crm:lead:write', 'crm:quotation:read', 'crm:quotation:write',
        'crm:quotation:approve', 'crm:quotation:convert', 'crm:pricing:read', 'crm:pricing:manage', 'crm:report:read',
        'crm:configure'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

-- Existing CRM users keep working: deal writers can work leads and quotations and read prices / reports.
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY[
        'crm:lead:read', 'crm:lead:write', 'crm:quotation:read', 'crm:quotation:write', 'crm:pricing:read',
        'crm:report:read'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE 'crm:deal:write' = ANY(permissions) AND name <> 'Administrator' AND "deletedAt" IS NULL;
