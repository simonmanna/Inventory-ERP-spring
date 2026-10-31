-- V31: receiving quality and industry controls (ADR-PROCUREMENT-001, wave 6 / roadmap P4).
-- Incoming inspection of goods receipts (accept / reject quantities, evidence files, automatic quality claim) and
-- emergency purchases with after-the-fact review. Additive only.

CREATE TABLE "IncomingInspection" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "inspectionNumber" TEXT        NOT NULL,
    "goodsReceiptId"  TEXT         NOT NULL REFERENCES "GoodsReceiptNote" (id) ON DELETE CASCADE,
    "purchaseOrderId" TEXT,
    "partnerId"       TEXT,
    status            TEXT         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'passed', 'failed', 'partial', 'cancelled')),
    notes             TEXT,
    "fileIds"         JSONB        NOT NULL DEFAULT '[]',
    "claimId"         TEXT,
    "createdBy"       TEXT,
    "completedBy"     TEXT,
    "completedAt"     TIMESTAMP(3),
    version           INTEGER      NOT NULL DEFAULT 1,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "IncomingInspection_number_key" ON "IncomingInspection" ("organizationId", "inspectionNumber");
-- One live inspection per receipt.
CREATE UNIQUE INDEX "IncomingInspection_grn_key" ON "IncomingInspection" ("goodsReceiptId") WHERE status <> 'cancelled';
CREATE INDEX "IncomingInspection_status_idx" ON "IncomingInspection" ("organizationId", status);

CREATE TABLE "IncomingInspectionLine" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "inspectionId"       TEXT           NOT NULL REFERENCES "IncomingInspection" (id) ON DELETE CASCADE,
    "goodsReceiptLineId" TEXT           NOT NULL,
    "productId"          TEXT,
    description          TEXT           NOT NULL,
    "receivedQuantity"   NUMERIC(20, 6) NOT NULL,
    "acceptedQuantity"   NUMERIC(20, 6),
    "rejectedQuantity"   NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("rejectedQuantity" >= 0),
    "unitPrice"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    result               TEXT CHECK (result IS NULL OR result IN ('pass', 'fail', 'partial')),
    "defectNotes"        TEXT,
    CHECK ("acceptedQuantity" IS NULL OR "acceptedQuantity" >= 0)
);
CREATE INDEX "IncomingInspectionLine_inspection_idx" ON "IncomingInspectionLine" ("inspectionId");

ALTER TABLE "PurchaseOrder"
    ADD COLUMN "isEmergency"         BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "emergencyReason"     TEXT,
    ADD COLUMN "emergencyMarkedBy"   TEXT,
    ADD COLUMN "emergencyReviewedBy" TEXT,
    ADD COLUMN "emergencyReviewedAt" TIMESTAMP(3),
    ADD COLUMN "emergencyReviewNote" TEXT;
CREATE INDEX "PurchaseOrder_emergency_idx" ON "PurchaseOrder" ("organizationId", "emergencyReviewedAt")
    WHERE "isEmergency";

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['inspection:read', 'inspection:manage', 'emergency_purchase:review',
                  'receiving:override_evidence']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role" SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['inspection:read', 'inspection:manage',
        'emergency_purchase:review', 'receiving:override_evidence'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

ALTER TABLE "IncomingInspection" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "IncomingInspection" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "IncomingInspectionLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "IncomingInspectionLine" USING (("organizationId" = current_setting('app.org_id', true)));
