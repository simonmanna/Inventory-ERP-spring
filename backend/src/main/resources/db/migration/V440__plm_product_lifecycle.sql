-- V440: Product lifecycle management (ADR-S063; Spring-native, no Nest reference). Additive on top of the BOM
-- versions + engineering changes of ADR-S006 package D:
--   * PlmItem / PlmLifecycleEvent   — a product's lifecycle state (concept → … → obsolete), revision and history
--   * PlmChangeStage(+Approver)     — configurable ECO stages with named approvers
--   * PlmChangeRequest              — engineering change requests (ECR) that feed ECOs
--   * EngineeringChange (extended)  — typed ECOs (bom / routing / product / document), stage, effectivity,
--                                     dispositions, revision
--   * PlmChangeApproval / Activity  — per-stage approval decisions and the ECO activity log
--   * PlmDocument                   — versioned engineering documents (drawings, specifications, …)

CREATE TABLE "PlmItem" (
    id                     TEXT PRIMARY KEY,
    "organizationId"       TEXT NOT NULL REFERENCES "Organization" (id),
    "productId"            TEXT NOT NULL,
    revision               TEXT NOT NULL DEFAULT 'A',
    "lifecycleState"       TEXT NOT NULL DEFAULT 'design',
    "ownerId"              TEXT,
    description            TEXT,
    notes                  TEXT,
    "replacementProductId" TEXT,
    "releasedAt"           TIMESTAMP(3),
    "obsoleteAt"           TIMESTAMP(3),
    "createdBy"            TEXT,
    "createdAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmItem_state" CHECK ("lifecycleState" IN ('concept', 'design', 'prototype', 'released',
        'phase_out', 'obsolete'))
);
CREATE UNIQUE INDEX "PlmItem_product_key" ON "PlmItem" ("organizationId", "productId");
CREATE INDEX "PlmItem_state_idx" ON "PlmItem" ("organizationId", "lifecycleState");
ALTER TABLE "PlmItem" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmItem" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "PlmLifecycleEvent" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "itemId"         TEXT NOT NULL REFERENCES "PlmItem" (id) ON DELETE CASCADE,
    kind             TEXT NOT NULL,
    "fromState"      TEXT,
    "toState"        TEXT,
    "fromRevision"   TEXT,
    "toRevision"     TEXT,
    "changeId"       TEXT,
    note             TEXT,
    "userId"         TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmLifecycleEvent_kind" CHECK (kind IN ('registered', 'transition', 'revision'))
);
CREATE INDEX "PlmLifecycleEvent_item_idx" ON "PlmLifecycleEvent" ("itemId", "createdAt");
ALTER TABLE "PlmLifecycleEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmLifecycleEvent" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "PlmChangeStage" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    name               TEXT NOT NULL,
    sequence           INTEGER NOT NULL DEFAULT 10,
    "requiresApproval" BOOLEAN NOT NULL DEFAULT false,
    description        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3)
);
CREATE UNIQUE INDEX "PlmChangeStage_name_key" ON "PlmChangeStage" ("organizationId", lower(name))
    WHERE "deletedAt" IS NULL;
ALTER TABLE "PlmChangeStage" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmChangeStage" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "PlmChangeStageApprover" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "stageId"        TEXT NOT NULL REFERENCES "PlmChangeStage" (id) ON DELETE CASCADE,
    "userId"         TEXT NOT NULL,
    kind             TEXT NOT NULL DEFAULT 'required',
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmChangeStageApprover_kind" CHECK (kind IN ('required', 'optional'))
);
CREATE UNIQUE INDEX "PlmChangeStageApprover_key" ON "PlmChangeStageApprover" ("stageId", "userId");
ALTER TABLE "PlmChangeStageApprover" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmChangeStageApprover"
    USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "PlmChangeRequest" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    number           TEXT NOT NULL,
    title            TEXT NOT NULL,
    description      TEXT,
    "productId"      TEXT,
    "bomId"          TEXT,
    "reasonCode"     TEXT NOT NULL DEFAULT 'other',
    priority         TEXT NOT NULL DEFAULT 'normal',
    status           TEXT NOT NULL DEFAULT 'new',
    "dueDate"        DATE,
    "requestedById"  TEXT,
    "reviewedById"   TEXT,
    "reviewedAt"     TIMESTAMP(3),
    "decisionNote"   TEXT,
    "changeId"       TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmChangeRequest_reason" CHECK ("reasonCode" IN ('cost_reduction', 'quality', 'customer',
        'supplier', 'regulatory', 'safety', 'manufacturability', 'other')),
    CONSTRAINT "PlmChangeRequest_priority" CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    CONSTRAINT "PlmChangeRequest_status" CHECK (status IN ('new', 'accepted', 'rejected', 'converted'))
);
CREATE UNIQUE INDEX "PlmChangeRequest_number_key" ON "PlmChangeRequest" ("organizationId", number);
CREATE INDEX "PlmChangeRequest_status_idx" ON "PlmChangeRequest" ("organizationId", status);
ALTER TABLE "PlmChangeRequest" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmChangeRequest" USING (("organizationId" = current_setting('app.org_id', true)));

-- Typed ECOs: a routing / product / document change has no BOM pair.
ALTER TABLE "EngineeringChange"
    ALTER COLUMN "bomId" DROP NOT NULL,
    ALTER COLUMN "newBomId" DROP NOT NULL,
    ADD COLUMN type                   TEXT NOT NULL DEFAULT 'bom',
    ADD COLUMN "stageId"              TEXT,
    ADD COLUMN priority               TEXT NOT NULL DEFAULT 'normal',
    ADD COLUMN description            TEXT,
    ADD COLUMN "productId"            TEXT,
    ADD COLUMN "requestId"            TEXT,
    ADD COLUMN "routingId"            TEXT,
    ADD COLUMN "newRoutingId"         TEXT,
    ADD COLUMN "targetLifecycleState" TEXT,
    ADD COLUMN "newRevision"          TEXT,
    ADD COLUMN effectivity            TEXT NOT NULL DEFAULT 'asap',
    ADD COLUMN "effectiveDate"        DATE,
    ADD COLUMN "stockDisposition"     TEXT NOT NULL DEFAULT 'use_up',
    ADD COLUMN "wipDisposition"       TEXT NOT NULL DEFAULT 'continue',
    ADD COLUMN "ownerId"              TEXT,
    ADD COLUMN "rejectedReason"       TEXT,
    ADD CONSTRAINT "EngineeringChange_type" CHECK (type IN ('bom', 'routing', 'product', 'document')),
    ADD CONSTRAINT "EngineeringChange_priority" CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    ADD CONSTRAINT "EngineeringChange_effectivity" CHECK (effectivity IN ('asap', 'date')),
    ADD CONSTRAINT "EngineeringChange_stock_disposition" CHECK ("stockDisposition" IN ('use_up', 'scrap', 'rework',
        'return_to_supplier', 'none')),
    ADD CONSTRAINT "EngineeringChange_wip_disposition" CHECK ("wipDisposition" IN ('continue', 'update', 'rework')),
    ADD CONSTRAINT "EngineeringChange_target_state" CHECK ("targetLifecycleState" IS NULL OR "targetLifecycleState"
        IN ('concept', 'design', 'prototype', 'released', 'phase_out', 'obsolete'));
-- Existing ECOs predate typing: their product is the BOM's output.
UPDATE "EngineeringChange" e SET "productId" = b."outputProductId" FROM "Bom" b WHERE b.id = e."bomId";
CREATE INDEX "EngineeringChange_product_idx" ON "EngineeringChange" ("organizationId", "productId");
CREATE INDEX "EngineeringChange_status_idx" ON "EngineeringChange" ("organizationId", status);

CREATE TABLE "PlmChangeApproval" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "changeId"       TEXT NOT NULL REFERENCES "EngineeringChange" (id) ON DELETE CASCADE,
    "stageId"        TEXT NOT NULL,
    "userId"         TEXT NOT NULL,
    decision         TEXT NOT NULL,
    comment          TEXT,
    voided           BOOLEAN NOT NULL DEFAULT false,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmChangeApproval_decision" CHECK (decision IN ('approved', 'rejected'))
);
CREATE INDEX "PlmChangeApproval_change_idx" ON "PlmChangeApproval" ("changeId", "stageId");
ALTER TABLE "PlmChangeApproval" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmChangeApproval" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "PlmChangeActivity" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "changeId"       TEXT NOT NULL REFERENCES "EngineeringChange" (id) ON DELETE CASCADE,
    kind             TEXT NOT NULL,
    message          TEXT NOT NULL,
    "userId"         TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmChangeActivity_kind" CHECK (kind IN ('created', 'comment', 'stage', 'approval', 'status',
        'applied', 'edited'))
);
CREATE INDEX "PlmChangeActivity_change_idx" ON "PlmChangeActivity" ("changeId", "createdAt");
ALTER TABLE "PlmChangeActivity" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmChangeActivity" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "PlmDocument" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    number           TEXT NOT NULL,
    version          INTEGER NOT NULL DEFAULT 1,
    revision         TEXT NOT NULL DEFAULT 'A',
    title            TEXT NOT NULL,
    "docType"        TEXT NOT NULL DEFAULT 'other',
    status           TEXT NOT NULL DEFAULT 'draft',
    "productId"      TEXT,
    "bomId"          TEXT,
    "changeId"       TEXT,
    "fileId"         TEXT,
    url              TEXT,
    "fileName"       TEXT,
    description      TEXT,
    "releasedAt"     TIMESTAMP(3),
    "releasedById"   TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3),
    CONSTRAINT "PlmDocument_type" CHECK ("docType" IN ('drawing', 'specification', 'cad', 'work_instruction',
        'test_procedure', 'datasheet', 'certificate', 'other')),
    CONSTRAINT "PlmDocument_status" CHECK (status IN ('draft', 'released', 'superseded', 'obsolete'))
);
CREATE UNIQUE INDEX "PlmDocument_version_key" ON "PlmDocument" ("organizationId", number, version)
    WHERE "deletedAt" IS NULL;
CREATE INDEX "PlmDocument_product_idx" ON "PlmDocument" ("organizationId", "productId");
CREATE INDEX "PlmDocument_change_idx" ON "PlmDocument" ("organizationId", "changeId");
ALTER TABLE "PlmDocument" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PlmDocument" USING (("organizationId" = current_setting('app.org_id', true)));

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['plm:read', 'plm:manage', 'plm:request',
        'plm:change', 'plm:approve', 'plm:apply'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
