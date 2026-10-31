-- V73 (Phase 4, PROC-1): requests for quotation and supplier quotations.
--
-- draft (editable) → sent (quotes recorded) → awarded (a purchase order was raised) | cancelled. Partner, product,
-- purchase request and purchase order ids are plain text ids like the rest of the schema.

CREATE TABLE "PurchaseRfq" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqNumber"         TEXT         NOT NULL,
    title               TEXT         NOT NULL,
    status              TEXT         NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'sent', 'awarded', 'cancelled')),
    "purchaseRequestId" TEXT,
    "dueDate"           DATE,
    "currencyCode"      TEXT         NOT NULL,
    notes               TEXT,
    "sentAt"            TIMESTAMP(3),
    "awardedAt"         TIMESTAMP(3),
    "awardedBy"         TEXT,
    "awardedQuotationId" TEXT,
    "purchaseOrderId"   TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PurchaseRfq_org_number_key" ON "PurchaseRfq" ("organizationId", "rfqNumber");
CREATE INDEX "PurchaseRfq_org_status_idx" ON "PurchaseRfq" ("organizationId", status);

CREATE TABLE "PurchaseRfqLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqId"           TEXT           NOT NULL REFERENCES "PurchaseRfq" (id) ON DELETE CASCADE,
    "productId"       TEXT,
    description       TEXT           NOT NULL,
    quantity          NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "unitOfMeasureId" TEXT,
    "lineNumber"      INTEGER        NOT NULL DEFAULT 0
);
CREATE INDEX "PurchaseRfqLine_rfq_idx" ON "PurchaseRfqLine" ("rfqId");

CREATE TABLE "PurchaseRfqSupplier" (
    id               TEXT NOT NULL PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqId"          TEXT NOT NULL REFERENCES "PurchaseRfq" (id) ON DELETE CASCADE,
    "partnerId"      TEXT NOT NULL
);
CREATE UNIQUE INDEX "PurchaseRfqSupplier_rfq_partner_key" ON "PurchaseRfqSupplier" ("rfqId", "partnerId");

-- One live quotation per supplier: recording again replaces it.
CREATE TABLE "PurchaseQuotation" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqId"          TEXT           NOT NULL REFERENCES "PurchaseRfq" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL,
    reference        TEXT,
    "currencyCode"   TEXT           NOT NULL,
    "exchangeRate"   NUMERIC(18, 8) NOT NULL DEFAULT 1 CHECK ("exchangeRate" > 0),
    "validUntil"     DATE,
    "leadTimeDays"   INTEGER        CHECK ("leadTimeDays" IS NULL OR "leadTimeDays" >= 0),
    "paymentTerms"   TEXT,
    notes            TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PurchaseQuotation_rfq_partner_key" ON "PurchaseQuotation" ("rfqId", "partnerId");

CREATE TABLE "PurchaseQuotationLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "quotationId"    TEXT           NOT NULL REFERENCES "PurchaseQuotation" (id) ON DELETE CASCADE,
    "rfqLineId"      TEXT           NOT NULL REFERENCES "PurchaseRfqLine" (id) ON DELETE CASCADE,
    "unitPrice"      NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    "taxRate"        NUMERIC(9, 6)  NOT NULL DEFAULT 0 CHECK ("taxRate" >= 0)
);
CREATE UNIQUE INDEX "PurchaseQuotationLine_quote_line_key" ON "PurchaseQuotationLine" ("quotationId", "rfqLineId");

ALTER TABLE "PurchaseRfq" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PurchaseRfq" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PurchaseRfqLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PurchaseRfqLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PurchaseRfqSupplier" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PurchaseRfqSupplier" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PurchaseQuotation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PurchaseQuotation" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PurchaseQuotationLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PurchaseQuotationLine" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['rfq:read', 'rfq:manage', 'rfq:award']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['rfq:read', 'rfq:manage', 'rfq:award'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
