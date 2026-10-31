-- V50: approval conditions, hierarchies, limits, separation of duties and controlled config changes (CTRL-1).
--
-- Every addition is inert until configured: a workflow or step with no branch, no department and the default
-- 'permission' scope behaves exactly as before; no ApprovalLimit row means no limit; no active SodRule row means the
-- pair is not enforced (weighbridge_exception is enforced in code regardless); no active 'config_change' workflow
-- means configuration changes apply at once. Branch, department and user ids are plain text like the rest of the
-- schema: kernel never reads hr tables, it asks ApprovalOrgDirectory.

ALTER TABLE "ApprovalWorkflow"
    ADD COLUMN "branchId"     TEXT,
    ADD COLUMN "departmentId" TEXT;

ALTER TABLE "ApprovalStep"
    ADD COLUMN "branchId"      TEXT,
    ADD COLUMN "departmentId"  TEXT,
    ADD COLUMN "approverScope" TEXT NOT NULL DEFAULT 'permission'
        CHECK ("approverScope" IN ('permission', 'department_manager'));

-- ---------------------------------------------------------------- transaction limits

-- The most a user, or a holder of a permission, may approve. A user's own rows win over permission rows; several
-- applicable rows of the same kind give the highest limit. entityType NULL = every document type.
CREATE TABLE "ApprovalLimit" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    "entityType"     TEXT,
    "userId"         TEXT,
    permission       TEXT,
    "branchId"       TEXT,
    "departmentId"   TEXT,
    "maxAmount"      NUMERIC(20, 6) NOT NULL CHECK ("maxAmount" >= 0),
    "isActive"       BOOLEAN        NOT NULL DEFAULT TRUE,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ApprovalLimit_subject_check" CHECK (("userId" IS NULL) <> (permission IS NULL))
);
CREATE INDEX "ApprovalLimit_org_active_idx" ON "ApprovalLimit" ("organizationId", "isActive");

-- ---------------------------------------------------------------- separation of duties

-- One row per organization and pair of the catalogue in SegregationOfDutiesService.
CREATE TABLE "SodRule" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "pairKey"        TEXT         NOT NULL,
    "isActive"       BOOLEAN      NOT NULL DEFAULT TRUE,
    notes            TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SodRule_org_pair_key" ON "SodRule" ("organizationId", "pairKey");

-- ---------------------------------------------------------------- controlled configuration changes

-- A change held for approval (entity type 'config_change'), applied by the approval.decided subscriber.
CREATE TABLE "ControlledConfigChange" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "changeType"        TEXT         NOT NULL,
    "targetId"          TEXT,
    payload             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status              TEXT         NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'applied', 'rejected')),
    "approvalRequestId" TEXT         NOT NULL,
    "requestedBy"       TEXT,
    "decidedBy"         TEXT,
    "decidedAt"         TIMESTAMP(3),
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "ControlledConfigChange_org_status_idx" ON "ControlledConfigChange" ("organizationId", status);

-- ---------------------------------------------------------------- tenancy

ALTER TABLE "ApprovalLimit" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ApprovalLimit" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SodRule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SodRule" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "ControlledConfigChange" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ControlledConfigChange" USING (("organizationId" = current_setting('app.org_id', true)));
