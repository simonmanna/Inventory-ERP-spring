-- V31: Sales returns / RMA, customer complaints and quality certificates (ADR-S011, phase S7; sales-postings.md
-- P12, P13). Stock moves through inventory (return receipt Dr inventory / Cr COGS, scrap as waste), credit notes and
-- refunds through invoicing; this migration only adds the commercial documents and their links.

CREATE TABLE "SalesReturn" (
    id                   TEXT         NOT NULL PRIMARY KEY,
    "organizationId"     TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number               TEXT         NOT NULL,
    "orderId"            TEXT         NOT NULL REFERENCES "SalesOrder" (id),
    "partnerId"          TEXT         NOT NULL,
    status               TEXT         NOT NULL DEFAULT 'requested' CHECK (status IN ('requested', 'approved', 'received',
        'inspected', 'closed', 'rejected', 'cancelled')),
    reason               TEXT         NOT NULL CHECK (reason IN ('wrong_grade', 'damaged', 'short_quantity',
        'excess_quantity', 'quality_rejection', 'other')),
    -- Goods come back physically (receive / inspect) or only a claim is settled (e.g. short delivery).
    "physicalReturn"     BOOLEAN      NOT NULL DEFAULT true,
    outcome              TEXT CHECK (outcome IS NULL OR outcome IN ('credit_note', 'refund', 'replacement', 'none')),
    "receiveLocationId"  TEXT,
    "complaintId"        TEXT,
    "discrepancyId"      TEXT,
    "creditNoteId"       TEXT,
    "refundPaymentId"    TEXT,
    "replacementOrderId" TEXT,
    notes                TEXT,
    "rejectionReason"    TEXT,
    "requestedById"      TEXT,
    "approvedById"       TEXT,
    "approvedAt"         TIMESTAMP(3),
    "receivedAt"         TIMESTAMP(3),
    "inspectedAt"        TIMESTAMP(3),
    "closedAt"           TIMESTAMP(3),
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SalesReturn_org_number_key" ON "SalesReturn" ("organizationId", number);
CREATE INDEX "SalesReturn_order_idx" ON "SalesReturn" ("orderId");

CREATE TABLE "SalesReturnLine" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "returnId"         TEXT           NOT NULL REFERENCES "SalesReturn" (id) ON DELETE CASCADE,
    "orderLineId"      TEXT           NOT NULL REFERENCES "SalesOrderLine" (id),
    "productId"        TEXT,
    description        TEXT           NOT NULL,
    quantity           NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "receivedQty"      NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("receivedQty" >= 0),
    -- restock: usable stock; quarantine: held at a quarantine location; scrap: written off (waste).
    disposition        TEXT CHECK (disposition IS NULL OR disposition IN ('restock', 'quarantine', 'scrap')),
    "dispositionLocationId" TEXT,
    "creditQty"        NUMERIC(20, 6),
    "inspectionNotes"  TEXT
);
CREATE INDEX "SalesReturnLine_return_idx" ON "SalesReturnLine" ("returnId");
CREATE INDEX "SalesReturnLine_order_line_idx" ON "SalesReturnLine" ("orderLineId");

CREATE TABLE "CustomerComplaint" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number           TEXT         NOT NULL,
    "partnerId"      TEXT         NOT NULL,
    "orderId"        TEXT,
    "deliveryOrderId" TEXT,
    "discrepancyId"  TEXT,
    category         TEXT         NOT NULL CHECK (category IN ('quality', 'quantity', 'delivery', 'billing', 'other')),
    description      TEXT         NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'investigating', 'resolved', 'closed')),
    "returnId"       TEXT,
    resolution       TEXT,
    "assignedToId"   TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolvedAt"     TIMESTAMP(3)
);
CREATE UNIQUE INDEX "CustomerComplaint_org_number_key" ON "CustomerComplaint" ("organizationId", number);
CREATE UNIQUE INDEX "CustomerComplaint_discrepancy_key" ON "CustomerComplaint" ("discrepancyId")
    WHERE "discrepancyId" IS NOT NULL;

-- Test / quality certificate of a delivery or batch (grade, size, moisture …); the file lives in the file store.
CREATE TABLE "SalesQualityCertificate" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"           TEXT,
    "deliveryOrderId"   TEXT,
    "productId"         TEXT         NOT NULL,
    "batchNumber"       TEXT,
    "certificateNumber" TEXT         NOT NULL,
    results             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    "fileId"            TEXT,
    "issuedAt"          DATE         NOT NULL DEFAULT CURRENT_DATE,
    "createdById"       TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesQualityCertificate_order_idx" ON "SalesQualityCertificate" ("orderId");

-- Product specification shown on certificates and checked on returns (grade, size, moisture …).
ALTER TABLE "Product" ADD COLUMN "qualitySpec" JSONB;

ALTER TABLE "SalesReturn" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesReturn" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesReturnLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesReturnLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "CustomerComplaint" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CustomerComplaint" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesQualityCertificate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesQualityCertificate"
    USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:return:read', 'sales:return:write', 'sales:return:approve', 'sales:complaint:read',
    'sales:complaint:write']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:return:read', 'sales:return:write',
        'sales:return:approve', 'sales:complaint:read', 'sales:complaint:write'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:return:read', 'sales:complaint:read',
        'sales:complaint:write'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name <> 'Administrator' AND "deletedAt" IS NULL AND 'sales:order:read' = ANY (permissions);
