-- V29: strategic sourcing — RFQs, supplier quotations, awards, purchase agreements (ADR-PROCUREMENT-001, wave 4).
-- Additive extension: new tables, plus a nullable agreement link on purchase-order lines.

CREATE TABLE "Rfq" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqNumber"        TEXT         NOT NULL,
    title              TEXT         NOT NULL,
    status             TEXT         NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'sent', 'closed', 'awarded', 'cancelled')),
    deadline           TIMESTAMP(3),
    "branchId"         TEXT,
    "deliveryLocationId" TEXT,
    "deliveryTerms"    TEXT,
    notes              TEXT,
    weights            JSONB        NOT NULL DEFAULT '{"price": 40, "quality": 25, "delivery": 20, "terms": 10, "performance": 5}',
    "createdBy"        TEXT,
    "sentAt"           TIMESTAMP(3),
    "closedAt"         TIMESTAMP(3),
    "awardedAt"        TIMESTAMP(3),
    "awardedBy"        TEXT,
    version            INTEGER      NOT NULL DEFAULT 1,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "Rfq_number_key" ON "Rfq" ("organizationId", "rfqNumber");
CREATE INDEX "Rfq_status_idx" ON "Rfq" ("organizationId", status);

CREATE TABLE "RfqLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqId"          TEXT           NOT NULL REFERENCES "Rfq" (id) ON DELETE CASCADE,
    "lineNumber"     INTEGER        NOT NULL,
    "productId"      TEXT,
    description      TEXT           NOT NULL,
    quantity         NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "uomId"          TEXT,
    "requiredDate"   DATE,
    "requestLineId"  TEXT,
    notes            TEXT
);
CREATE INDEX "RfqLine_rfq_idx" ON "RfqLine" ("rfqId");
CREATE INDEX "RfqLine_requestLine_idx" ON "RfqLine" ("requestLineId") WHERE "requestLineId" IS NOT NULL;

CREATE TABLE "RfqSupplier" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqId"          TEXT         NOT NULL REFERENCES "Rfq" (id) ON DELETE CASCADE,
    "partnerId"      TEXT         NOT NULL REFERENCES "Partner" (id),
    status           TEXT         NOT NULL DEFAULT 'invited' CHECK (status IN ('invited', 'responded', 'declined')),
    "invitedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "respondedAt"    TIMESTAMP(3),
    "declineReason"  TEXT
);
CREATE UNIQUE INDEX "RfqSupplier_key" ON "RfqSupplier" ("rfqId", "partnerId");

CREATE TABLE "SupplierQuotation" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqId"             TEXT           NOT NULL REFERENCES "Rfq" (id) ON DELETE CASCADE,
    "partnerId"         TEXT           NOT NULL REFERENCES "Partner" (id),
    revision            INTEGER        NOT NULL DEFAULT 1,
    status              TEXT           NOT NULL DEFAULT 'received'
        CHECK (status IN ('received', 'superseded', 'accepted', 'rejected', 'expired')),
    "supplierReference" TEXT,
    "validUntil"        DATE,
    "currencyCode"      TEXT           NOT NULL,
    "exchangeRate"      NUMERIC(20, 6) NOT NULL DEFAULT 1 CHECK ("exchangeRate" > 0),
    "leadTimeDays"      INTEGER        CHECK ("leadTimeDays" IS NULL OR "leadTimeDays" >= 0),
    "paymentTermsDays"  INTEGER        CHECK ("paymentTermsDays" IS NULL OR "paymentTermsDays" >= 0),
    "freightAmount"     NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("freightAmount" >= 0),
    "qualityScore"      NUMERIC(6, 2)  CHECK ("qualityScore" IS NULL OR ("qualityScore" >= 0 AND "qualityScore" <= 100)),
    notes               TEXT,
    "submittedVia"      TEXT           NOT NULL DEFAULT 'manual' CHECK ("submittedVia" IN ('manual', 'portal')),
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SupplierQuotation_current_key" ON "SupplierQuotation" ("rfqId", "partnerId")
    WHERE status = 'received';
CREATE INDEX "SupplierQuotation_rfq_idx" ON "SupplierQuotation" ("rfqId", status);

CREATE TABLE "SupplierQuotationLine" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "quotationId"    TEXT           NOT NULL REFERENCES "SupplierQuotation" (id) ON DELETE CASCADE,
    "rfqLineId"      TEXT           NOT NULL REFERENCES "RfqLine" (id) ON DELETE CASCADE,
    "unitPrice"      NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    quantity         NUMERIC(20, 6) CHECK (quantity IS NULL OR quantity > 0),
    "leadTimeDays"   INTEGER        CHECK ("leadTimeDays" IS NULL OR "leadTimeDays" >= 0),
    "taxRate"        NUMERIC(10, 4) NOT NULL DEFAULT 0,
    alternative      TEXT,
    notes            TEXT
);
CREATE UNIQUE INDEX "SupplierQuotationLine_key" ON "SupplierQuotationLine" ("quotationId", "rfqLineId");

CREATE TABLE "RfqAward" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "rfqId"             TEXT           NOT NULL REFERENCES "Rfq" (id) ON DELETE CASCADE,
    "rfqLineId"         TEXT           NOT NULL REFERENCES "RfqLine" (id) ON DELETE CASCADE,
    "quotationLineId"   TEXT           NOT NULL REFERENCES "SupplierQuotationLine" (id),
    "partnerId"         TEXT           NOT NULL,
    quantity            NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "unitPrice"         NUMERIC(20, 6) NOT NULL,
    "lowestPrice"       BOOLEAN        NOT NULL,
    justification       TEXT,
    "purchaseOrderId"   TEXT,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "RfqAward_rfq_idx" ON "RfqAward" ("rfqId");

CREATE TABLE "PurchaseAgreement" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "agreementNumber"  TEXT           NOT NULL,
    "partnerId"        TEXT           NOT NULL REFERENCES "Partner" (id),
    type               TEXT           NOT NULL DEFAULT 'blanket' CHECK (type IN ('blanket', 'rate')),
    title              TEXT           NOT NULL,
    status             TEXT           NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'active', 'on_hold', 'exhausted', 'expired', 'terminated')),
    "validFrom"        DATE           NOT NULL,
    "validTo"          DATE           NOT NULL,
    "currencyCode"     TEXT           NOT NULL,
    "committedValue"   NUMERIC(20, 6) CHECK ("committedValue" IS NULL OR "committedValue" > 0),
    "releasedValue"    NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("releasedValue" >= 0),
    "branchId"         TEXT,
    "rfqId"            TEXT,
    terms              TEXT,
    notes              TEXT,
    "createdBy"        TEXT,
    "approvedBy"       TEXT,
    "approvedAt"       TIMESTAMP(3),
    "statusReason"     TEXT,
    version            INTEGER        NOT NULL DEFAULT 1,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("validTo" >= "validFrom")
);
CREATE UNIQUE INDEX "PurchaseAgreement_number_key" ON "PurchaseAgreement" ("organizationId", "agreementNumber");
CREATE INDEX "PurchaseAgreement_status_idx" ON "PurchaseAgreement" ("organizationId", status, "validTo");

CREATE TABLE "PurchaseAgreementLine" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "agreementId"       TEXT           NOT NULL REFERENCES "PurchaseAgreement" (id) ON DELETE CASCADE,
    "lineNumber"        INTEGER        NOT NULL,
    "productId"         TEXT,
    description         TEXT           NOT NULL,
    "uomId"             TEXT,
    "unitPrice"         NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    "taxRate"           NUMERIC(10, 4) NOT NULL DEFAULT 0,
    "committedQuantity" NUMERIC(20, 6) CHECK ("committedQuantity" IS NULL OR "committedQuantity" > 0),
    "releasedQuantity"  NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("releasedQuantity" >= 0)
);
CREATE INDEX "PurchaseAgreementLine_agreement_idx" ON "PurchaseAgreementLine" ("agreementId");

ALTER TABLE "PurchaseOrderLine" ADD COLUMN "agreementLineId" TEXT;
CREATE INDEX "PurchaseOrderLine_agreementLine_idx" ON "PurchaseOrderLine" ("agreementLineId")
    WHERE "agreementLineId" IS NOT NULL;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['rfq:read', 'rfq:manage', 'rfq:award', 'purchase_agreement:read', 'purchase_agreement:manage',
                  'purchase_agreement:approve']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role" SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['rfq:read', 'rfq:manage', 'rfq:award',
        'purchase_agreement:read', 'purchase_agreement:manage', 'purchase_agreement:approve'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

ALTER TABLE "Rfq" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "Rfq" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "RfqLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "RfqLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "RfqSupplier" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "RfqSupplier" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierQuotation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierQuotation" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierQuotationLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierQuotationLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "RfqAward" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "RfqAward" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PurchaseAgreement" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PurchaseAgreement" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PurchaseAgreementLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PurchaseAgreementLine" USING (("organizationId" = current_setting('app.org_id', true)));
