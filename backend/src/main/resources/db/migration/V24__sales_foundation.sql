-- V24: Sales / order-to-cash foundation (ADR-S011, phase S0).
--
-- * New sales-owned quotation tables; CRM quotations (ADR-S010, V17) are copied with the SAME ids, numbers,
--   statuses and links so approvals, audit rows, attachments and converted-order references stay valid.
-- * The CRM quotation tables are kept for one release and write-blocked; a later migration drops them after the
--   owner has verified the copy (docs/runbooks/sales-quote-migration.md).
-- * Per-organization sales settings (quote policy moved from CrmSettings; fulfilment / recognition policies stored
--   only, executed from phase S5).
-- * Commercial master-data fields on Partner and Product.
-- * sales:* permissions.
-- Additive extension beyond the NestJS reference: no parity table or route changes.

-- ---------------------------------------------------------------- settings

CREATE TABLE "SalesSettings" (
    "organizationId"          TEXT           NOT NULL PRIMARY KEY REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- Quotation policy (moved from CrmSettings, same semantics).
    "quoteMaxDiscountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 10 CHECK ("quoteMaxDiscountPercent" BETWEEN 0 AND 100),
    "quoteApprovalAmount"     NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("quoteApprovalAmount" >= 0),
    "creditCheck"             BOOLEAN        NOT NULL DEFAULT true,
    "quoteValidityDays"       INTEGER        NOT NULL DEFAULT 30 CHECK ("quoteValidityDays" > 0),
    "defaultQuoteTerms"       TEXT,
    "autoWinOnConversion"     BOOLEAN        NOT NULL DEFAULT true,
    -- Flows enabled (sales.md §2); disabling a flow never disables a control.
    "flows"                   TEXT[]         NOT NULL DEFAULT ARRAY['wholesale', 'direct_order', 'advance',
        'collect']::TEXT[],
    -- When stock is reserved for a sales order (executed from S4).
    "reservationTrigger"      TEXT           NOT NULL DEFAULT 'confirm'
        CHECK ("reservationTrigger" IN ('none', 'confirm')),
    -- Fulfilment and recognition policy defaults (sales.md §5, sales-postings.md §3); executed from S5.
    "physicalMovement"        TEXT           NOT NULL DEFAULT 'direct'
        CHECK ("physicalMovement" IN ('direct', 'via_transit')),
    "ownershipTransfer"       TEXT           NOT NULL DEFAULT 'at_dispatch'
        CHECK ("ownershipTransfer" IN ('at_dispatch', 'at_delivery', 'at_acceptance')),
    "cogsRecognition"         TEXT           NOT NULL DEFAULT 'at_ownership_transfer'
        CHECK ("cogsRecognition" IN ('at_ownership_transfer', 'at_revenue_recognition')),
    "revenueRecognition"      TEXT           NOT NULL DEFAULT 'at_invoice'
        CHECK ("revenueRecognition" IN ('at_ownership_transfer', 'at_invoice')),
    "billingPolicy"           TEXT           NOT NULL DEFAULT 'delivered'
        CHECK ("billingPolicy" IN ('ordered', 'delivered')),
    "policyEffectiveFrom"     DATE           NOT NULL DEFAULT CURRENT_DATE,
    "updatedById"             TEXT,
    "updatedAt"               TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Ownership after the gate needs a transit location (policy matrix row F is invalid).
    CHECK ("physicalMovement" = 'via_transit' OR "ownershipTransfer" = 'at_dispatch')
);

INSERT INTO "SalesSettings" ("organizationId", "quoteMaxDiscountPercent", "quoteApprovalAmount", "creditCheck",
                             "quoteValidityDays", "defaultQuoteTerms", "autoWinOnConversion")
SELECT "organizationId", "quoteMaxDiscountPercent", "quoteApprovalAmount", "creditCheck", "quoteValidityDays",
       "defaultQuoteTerms", "autoWinOnConversion"
FROM "CrmSettings";

-- ---------------------------------------------------------------- quotations

CREATE TABLE "SalesQuotation" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number               TEXT           NOT NULL,
    revision             INTEGER        NOT NULL DEFAULT 0,
    -- CRM opportunity (optional; quoting does not need the CRM licence).
    "dealId"             TEXT REFERENCES "Deal" (id) ON DELETE SET NULL,
    "partnerId"          TEXT           NOT NULL,
    "contactId"          TEXT,
    "ownerId"            TEXT,
    -- Sales team (CrmTeam id until teams move to sales).
    "teamId"             TEXT,
    "branchId"           TEXT,
    "paymentTermId"      TEXT,
    "pricelistId"        TEXT,
    -- Delivery site (fleet FleetCustomerSite id) or free-text address.
    "siteId"             TEXT,
    "deliveryAddress"    TEXT,
    status               TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'pending_approval',
        'approved', 'sent', 'accepted', 'rejected', 'expired', 'cancelled', 'converted')),
    "issueDate"          DATE           NOT NULL DEFAULT CURRENT_DATE,
    "validUntil"         DATE,
    "currencyCode"       TEXT           NOT NULL DEFAULT 'USD',
    subtotal             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "discountTotal"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "taxAmount"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    total                NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "maxDiscountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    notes                TEXT,
    terms                TEXT,
    "approvalReasons"    TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    "approvalRequestId"  TEXT,
    "approvedById"       TEXT,
    "approvedAt"         TIMESTAMP(3),
    "rejectedById"       TEXT,
    "rejectionReason"    TEXT,
    "sentAt"             TIMESTAMP(3),
    "acceptedAt"         TIMESTAMP(3),
    "convertedType"      TEXT CHECK ("convertedType" IS NULL OR "convertedType" IN ('order', 'proforma',
        'sales_order')),
    "convertedId"        TEXT,
    "convertedNumber"    TEXT,
    "convertedAt"        TIMESTAMP(3),
    -- Where the row came from: 'crm' = copied from CrmQuotation by this migration.
    "migratedFrom"       TEXT,
    "createdById"        TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"          TIMESTAMP(3)
);
CREATE UNIQUE INDEX "SalesQuotation_org_number_key" ON "SalesQuotation" ("organizationId", number);
CREATE INDEX "SalesQuotation_org_status_idx" ON "SalesQuotation" ("organizationId", status) WHERE "deletedAt" IS NULL;
CREATE INDEX "SalesQuotation_deal_idx" ON "SalesQuotation" ("dealId");
CREATE INDEX "SalesQuotation_org_partner_idx" ON "SalesQuotation" ("organizationId", "partnerId");

CREATE TABLE "SalesQuotationLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "quotationId"     TEXT           NOT NULL REFERENCES "SalesQuotation" (id) ON DELETE CASCADE,
    "sortOrder"       INTEGER        NOT NULL DEFAULT 0,
    "productId"       TEXT,
    description       TEXT           NOT NULL,
    quantity          NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    -- Unit snapshot (ADR-S003): null = product base unit, factor 1.
    "uomId"           TEXT,
    "uomFactor"       NUMERIC(20, 10) NOT NULL DEFAULT 1 CHECK ("uomFactor" > 0),
    "listPrice"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "unitPrice"       NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    "discountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 0 CHECK ("discountPercent" BETWEEN 0 AND 100),
    "priceSource"     TEXT           NOT NULL DEFAULT 'list'
        CHECK ("priceSource" IN ('list', 'rule', 'customer', 'manual', 'pricelist', 'agreement')),
    "priceRuleId"     TEXT,
    "taxId"           TEXT,
    "taxInclusive"    BOOLEAN        NOT NULL DEFAULT false,
    "isOptional"      BOOLEAN        NOT NULL DEFAULT false,
    "lineSubtotal"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "lineTax"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "lineTotal"       NUMERIC(20, 6) NOT NULL DEFAULT 0
);
CREATE INDEX "SalesQuotationLine_quote_idx" ON "SalesQuotationLine" ("quotationId", "sortOrder");

-- Immutable snapshot of a quotation (header + lines) taken whenever a new revision is started.
CREATE TABLE "SalesQuotationRevision" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "quotationId"    TEXT         NOT NULL REFERENCES "SalesQuotation" (id) ON DELETE CASCADE,
    revision         INTEGER      NOT NULL,
    status           TEXT         NOT NULL,
    total            NUMERIC(20, 6) NOT NULL,
    snapshot         JSONB        NOT NULL,
    reason           TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE ("quotationId", revision)
);

-- Copy CRM quotations (same ids).
INSERT INTO "SalesQuotation" (id, "organizationId", number, revision, "dealId", "partnerId", "contactId", "ownerId",
                              status, "issueDate", "validUntil", "currencyCode", subtotal, "discountTotal",
                              "taxAmount", total, "maxDiscountPercent", notes, terms, "approvalReasons",
                              "approvalRequestId", "approvedById", "approvedAt", "rejectedById", "rejectionReason",
                              "sentAt", "acceptedAt", "convertedType", "convertedId", "convertedNumber",
                              "convertedAt", "migratedFrom", "createdById", "createdAt", "updatedAt", "deletedAt")
SELECT id, "organizationId", number, revision, "dealId", "partnerId", "contactId", "ownerId", status, "issueDate",
       "validUntil", "currencyCode", subtotal, "discountTotal", "taxAmount", total, "maxDiscountPercent", notes,
       terms, "approvalReasons", "approvalRequestId", "approvedById", "approvedAt", "rejectedById",
       "rejectionReason", "sentAt", "acceptedAt", "convertedType", "convertedId", "convertedNumber", "convertedAt",
       'crm', "createdById", "createdAt", "updatedAt", "deletedAt"
FROM "CrmQuotation";

INSERT INTO "SalesQuotationLine" (id, "organizationId", "quotationId", "sortOrder", "productId", description,
                                  quantity, "listPrice", "unitPrice", "discountPercent", "priceSource",
                                  "priceRuleId", "taxId", "taxInclusive", "lineSubtotal", "lineTax", "lineTotal")
SELECT id, "organizationId", "quotationId", "sortOrder", "productId", description, quantity, "listPrice",
       "unitPrice", "discountPercent", "priceSource", "priceRuleId", "taxId", "taxInclusive", "lineSubtotal",
       "lineTax", "lineTotal"
FROM "CrmQuotationLine";

-- Write-block the CRM copies: any insert / update / delete fails unless a session explicitly allows it
-- (rollback procedure only).
CREATE FUNCTION crm_quotation_write_block() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF coalesce(current_setting('app.allow_crm_quote_write', true), '') <> 'on' THEN
        RAISE EXCEPTION 'CRM quotation tables are read-only since V24; quotations live in "SalesQuotation"';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER "CrmQuotation_write_block" BEFORE INSERT OR UPDATE OR DELETE ON "CrmQuotation"
    FOR EACH STATEMENT EXECUTE FUNCTION crm_quotation_write_block();
CREATE TRIGGER "CrmQuotationLine_write_block" BEFORE INSERT OR UPDATE OR DELETE ON "CrmQuotationLine"
    FOR EACH STATEMENT EXECUTE FUNCTION crm_quotation_write_block();

-- ---------------------------------------------------------------- commercial master data

ALTER TABLE "Partner"
    ADD COLUMN "pricelistId"        TEXT,
    ADD COLUMN "creditRiskCategory" TEXT CHECK ("creditRiskCategory" IS NULL
        OR "creditRiskCategory" IN ('low', 'medium', 'high', 'blocked')),
    ADD COLUMN "creditReviewDate"   DATE,
    ADD COLUMN "creditPeriodDays"   INTEGER CHECK ("creditPeriodDays" IS NULL OR "creditPeriodDays" >= 0),
    ADD COLUMN "salesTeamId"        TEXT,
    ADD COLUMN "salespersonId"      TEXT;

ALTER TABLE "Product"
    ADD COLUMN "invoicePolicy" TEXT NOT NULL DEFAULT 'delivered' CHECK ("invoicePolicy" IN ('ordered', 'delivered')),
    ADD COLUMN "billingBasis"  TEXT NOT NULL DEFAULT 'accepted'
        CHECK ("billingBasis" IN ('ordered', 'weighed', 'accepted'));

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "SalesSettings" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesSettings" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesQuotation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesQuotation" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesQuotationLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesQuotationLine"
    USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesQuotationRevision" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesQuotationRevision"
    USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions (ADR-S011)

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY[
    'sales:quotation:read', 'sales:quotation:write', 'sales:quotation:approve', 'sales:quotation:send',
    'sales:quotation:convert', 'sales:order:read', 'sales:order:read_all', 'sales:order:write',
    'sales:order:confirm', 'sales:order:cancel', 'sales:order:close_short', 'sales:order:hold_release',
    'sales:order:amend', 'sales:order:amend_approve', 'sales:pricing:read', 'sales:pricing:manage',
    'sales:billing:override', 'sales:policy:override', 'sales:report:read', 'sales:configure']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY[
        'sales:quotation:read', 'sales:quotation:write', 'sales:quotation:approve', 'sales:quotation:send',
        'sales:quotation:convert', 'sales:order:read', 'sales:order:read_all', 'sales:order:write',
        'sales:order:confirm', 'sales:order:cancel', 'sales:order:close_short', 'sales:order:hold_release',
        'sales:order:amend', 'sales:order:amend_approve', 'sales:pricing:read', 'sales:pricing:manage',
        'sales:billing:override', 'sales:policy:override', 'sales:report:read', 'sales:configure'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

-- Existing quotation users keep the same rights under the sales keys.
UPDATE "Role" r
SET permissions = ARRAY(SELECT DISTINCT unnest(r.permissions || ARRAY(
        SELECT 'sales:' || substr(p, 5) FROM unnest(r.permissions) p
        WHERE p IN ('crm:quotation:read', 'crm:quotation:write', 'crm:quotation:approve', 'crm:quotation:convert',
                    'crm:pricing:read', 'crm:pricing:manage')))),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE r.name <> 'Administrator' AND r."deletedAt" IS NULL
  AND r.permissions && ARRAY['crm:quotation:read', 'crm:quotation:write', 'crm:quotation:approve',
                             'crm:quotation:convert', 'crm:pricing:read', 'crm:pricing:manage'];
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:quotation:send'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name <> 'Administrator' AND "deletedAt" IS NULL AND 'crm:quotation:write' = ANY (permissions);
