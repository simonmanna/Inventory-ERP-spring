-- V30: enterprise purchasing controls (ADR-PROCUREMENT-001, wave 5 / roadmap P3).
-- Budget commitments (memo records owned by accounting, no GL), purchase-order change requests and revisions,
-- supplier claims. Additive only.

CREATE TABLE "Commitment" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "sourceType"     TEXT           NOT NULL CHECK ("sourceType" IN ('purchase_order', 'purchase_request')),
    "sourceId"       TEXT           NOT NULL,
    "sourceLineId"   TEXT           NOT NULL,
    "sourceNumber"   TEXT,
    "accountId"      TEXT           NOT NULL,
    "branchId"       TEXT,
    "costCenterId"   TEXT,
    "projectId"      TEXT,
    period           TEXT           NOT NULL CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    amount           NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    "openAmount"     NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("openAmount" >= 0),
    status           TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "Commitment_line_key" ON "Commitment" ("organizationId", "sourceType", "sourceLineId");
CREATE INDEX "Commitment_budget_idx" ON "Commitment" ("organizationId", "accountId", period) WHERE status = 'open';
CREATE INDEX "Commitment_source_idx" ON "Commitment" ("organizationId", "sourceType", "sourceId");

CREATE TABLE "PoChangeRequest" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "purchaseOrderId" TEXT           NOT NULL REFERENCES "PurchaseOrder" (id) ON DELETE CASCADE,
    "changeNumber"    INTEGER        NOT NULL,
    status            TEXT           NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'applied', 'rejected', 'cancelled')),
    reason            TEXT           NOT NULL,
    changes           JSONB          NOT NULL,
    "oldTotal"        NUMERIC(20, 6) NOT NULL,
    "newTotal"        NUMERIC(20, 6) NOT NULL,
    "needsApproval"   BOOLEAN        NOT NULL DEFAULT false,
    "approvalRequestId" TEXT,
    "requestedBy"     TEXT,
    "decidedBy"       TEXT,
    "decidedAt"       TIMESTAMP(3),
    "decisionNote"    TEXT,
    "appliedAt"       TIMESTAMP(3),
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PoChangeRequest_number_key" ON "PoChangeRequest" ("purchaseOrderId", "changeNumber");
-- One open change at a time per order: two concurrent edits would otherwise overwrite each other.
CREATE UNIQUE INDEX "PoChangeRequest_one_open" ON "PoChangeRequest" ("purchaseOrderId")
    WHERE status IN ('pending', 'approved');

CREATE TABLE "PoRevision" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "purchaseOrderId" TEXT         NOT NULL REFERENCES "PurchaseOrder" (id) ON DELETE CASCADE,
    revision          INTEGER      NOT NULL,
    "changeRequestId" TEXT,
    snapshot          JSONB        NOT NULL,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PoRevision_key" ON "PoRevision" ("purchaseOrderId", revision);

ALTER TABLE "PurchaseOrder" ADD COLUMN "revision" INTEGER NOT NULL DEFAULT 0;

CREATE TABLE "SupplierClaim" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "claimNumber"     TEXT           NOT NULL,
    "partnerId"       TEXT           NOT NULL REFERENCES "Partner" (id),
    "purchaseOrderId" TEXT,
    "goodsReceiptId"  TEXT,
    "vendorBillId"    TEXT,
    type              TEXT           NOT NULL
        CHECK (type IN ('shortage', 'damage', 'quality', 'price', 'late_delivery', 'other')),
    status            TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'submitted', 'accepted', 'rejected', 'settled', 'cancelled')),
    amount            NUMERIC(20, 6) NOT NULL CHECK (amount >= 0),
    "settledAmount"   NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("settledAmount" >= 0),
    description       TEXT           NOT NULL,
    "supplierResponse" TEXT,
    "debitNoteId"     TEXT,
    "createdBy"       TEXT,
    "submittedAt"     TIMESTAMP(3),
    "decidedAt"       TIMESTAMP(3),
    "settledAt"       TIMESTAMP(3),
    version           INTEGER        NOT NULL DEFAULT 1,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SupplierClaim_number_key" ON "SupplierClaim" ("organizationId", "claimNumber");
CREATE INDEX "SupplierClaim_partner_idx" ON "SupplierClaim" ("organizationId", "partnerId", status);

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['budget:override', 'commitment:read', 'po_change:request', 'po_change:approve',
                  'supplier_claim:read', 'supplier_claim:manage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role" SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['budget:override', 'commitment:read',
        'po_change:request', 'po_change:approve', 'supplier_claim:read', 'supplier_claim:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

ALTER TABLE "Commitment" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "Commitment" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PoChangeRequest" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PoChangeRequest" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PoRevision" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PoRevision" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierClaim" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierClaim" USING (("organizationId" = current_setting('app.org_id', true)));
