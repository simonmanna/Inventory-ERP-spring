-- V660: operational quality control (module qualitycontrol, ADR-S087, QC1). Odoo-style control points, operator checks
-- with immutable execution revisions, sample records, failure classes, versioned worksheet templates, quality teams, and
-- the generation-identity tables QC2 uses (counter, persisted random decisions, periodic schedule). Independent of the
-- ADR-S013 quality tables (Quality*) and of manufacturing's QcPlan.

-- ---------------------------------------------------------------- teams
CREATE TABLE "QcTeam" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    description      TEXT,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcTeam_org_idx" ON "QcTeam" ("organizationId");
CREATE UNIQUE INDEX "QcTeam_org_name_key" ON "QcTeam" ("organizationId", lower(name));

CREATE TABLE "QcTeamMember" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "teamId"         TEXT         NOT NULL REFERENCES "QcTeam" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL REFERENCES "User" (id) ON DELETE CASCADE,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcTeamMember_org_idx" ON "QcTeamMember" ("organizationId");
CREATE UNIQUE INDEX "QcTeamMember_team_user_key" ON "QcTeamMember" ("teamId", "userId");

-- ---------------------------------------------------------------- failure classes
CREATE TABLE "QcFailureClass" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    "sortOrder"      INTEGER      NOT NULL DEFAULT 0,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcFailureClass_org_idx" ON "QcFailureClass" ("organizationId");
CREATE UNIQUE INDEX "QcFailureClass_org_code_key" ON "QcFailureClass" ("organizationId", lower(code));

-- ---------------------------------------------------------------- worksheet templates (one row per version)
-- templateKey groups the versions of one template; a version referenced by a check is never edited: the edit writes
-- version n+1 and moves isLatest.
CREATE TABLE "QcWorksheetTemplate" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "templateKey"    TEXT         NOT NULL,
    version          INTEGER      NOT NULL CHECK (version >= 1),
    name             TEXT         NOT NULL,
    description      TEXT,
    fields           JSONB        NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(fields) = 'array'),
    "isLatest"       BOOLEAN      NOT NULL DEFAULT true,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcWorksheetTemplate_org_idx" ON "QcWorksheetTemplate" ("organizationId");
CREATE UNIQUE INDEX "QcWorksheetTemplate_key_version_key" ON "QcWorksheetTemplate" ("organizationId", "templateKey", version);
CREATE UNIQUE INDEX "QcWorksheetTemplate_latest_key" ON "QcWorksheetTemplate" ("organizationId", "templateKey")
    WHERE "isLatest";

-- ---------------------------------------------------------------- control points
CREATE TABLE "QcControlPoint" (
    id                     TEXT           NOT NULL PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    reference              TEXT           NOT NULL,
    title                  TEXT           NOT NULL,
    operation              TEXT           NOT NULL
        CHECK (operation IN ('receipt', 'delivery', 'manufacturing', 'work_order')),
    "productIds"           JSONB          NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof("productIds") = 'array'),
    "categoryIds"          JSONB          NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof("categoryIds") = 'array'),
    "supplierPartnerId"    TEXT,
    "routingOperationId"   TEXT,
    "teamId"               TEXT REFERENCES "QcTeam" (id),
    "responsibleUserId"    TEXT REFERENCES "User" (id),
    "checkType"            TEXT           NOT NULL
        CHECK ("checkType" IN ('instructions', 'pass_fail', 'measure', 'picture', 'worksheet')),
    instructions           TEXT,
    "failureMessage"       TEXT,
    "failureClassRequired" BOOLEAN        NOT NULL DEFAULT false,
    -- measure: norm and the absolute specification limits (LSL = toleranceMin, USL = toleranceMax)
    norm                   NUMERIC(20, 6),
    "toleranceMin"         NUMERIC(20, 6),
    "toleranceMax"         NUMERIC(20, 6),
    unit                   TEXT,
    decimals               INTEGER        CHECK (decimals IS NULL OR decimals BETWEEN 0 AND 6),
    "worksheetTemplateKey" TEXT,
    -- sampling
    "controlPer"           TEXT           NOT NULL DEFAULT 'operation'
        CHECK ("controlPer" IN ('operation', 'product', 'quantity')),
    "testQtyPct"           NUMERIC(9, 4)  CHECK ("testQtyPct" IS NULL OR ("testQtyPct" > 0 AND "testQtyPct" <= 100)),
    "minSampleQty"         NUMERIC(20, 6) CHECK ("minSampleQty" IS NULL OR "minSampleQty" > 0),
    -- frequency
    frequency              TEXT           NOT NULL DEFAULT 'all' CHECK (frequency IN ('all', 'random', 'periodic')),
    "randomPct"            NUMERIC(9, 4)  CHECK ("randomPct" IS NULL OR ("randomPct" > 0 AND "randomPct" <= 100)),
    "periodValue"          INTEGER        CHECK ("periodValue" IS NULL OR "periodValue" >= 1),
    "periodUnit"           TEXT           CHECK ("periodUnit" IS NULL OR "periodUnit" IN ('day', 'week', 'month')),
    -- blocking (interpreted only by QcBlockingPolicy)
    blocking               BOOLEAN        NOT NULL DEFAULT true,
    "blockOnFailure"       BOOLEAN        NOT NULL DEFAULT false,
    "autoAlert"            BOOLEAN        NOT NULL DEFAULT true,
    "receiptMode"          TEXT           CHECK ("receiptMode" IS NULL
        OR "receiptMode" IN ('pre_receipt', 'quarantine_on_receipt')),
    "failureLocationId"    TEXT,
    -- SPC subgrouping (QC4)
    "spcSubgroup"          TEXT           NOT NULL DEFAULT 'individual'
        CHECK ("spcSubgroup" IN ('individual', 'consecutive', 'batch', 'work_order', 'time_window')),
    "spcSubgroupSize"      INTEGER        CHECK ("spcSubgroupSize" IS NULL OR "spcSubgroupSize" BETWEEN 2 AND 10),
    "spcWindowHours"       INTEGER        CHECK ("spcWindowHours" IS NULL OR "spcWindowHours" >= 1),
    "spcSplitByWorkCenter" BOOLEAN        NOT NULL DEFAULT false,
    sequence               INTEGER        NOT NULL DEFAULT 10,
    "isActive"             BOOLEAN        NOT NULL DEFAULT true,
    version                INTEGER        NOT NULL DEFAULT 1,
    "createdBy"            TEXT,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("toleranceMin" IS NULL OR "toleranceMax" IS NULL OR "toleranceMin" <= "toleranceMax"),
    CHECK ("receiptMode" IS NULL OR operation = 'receipt'),
    CHECK ("checkType" <> 'worksheet' OR "worksheetTemplateKey" IS NOT NULL),
    CHECK ("controlPer" <> 'quantity' OR "testQtyPct" IS NOT NULL),
    CHECK (frequency <> 'random' OR "randomPct" IS NOT NULL),
    CHECK (frequency <> 'periodic' OR ("periodValue" IS NOT NULL AND "periodUnit" IS NOT NULL))
);
CREATE INDEX "QcControlPoint_org_idx" ON "QcControlPoint" ("organizationId");
CREATE UNIQUE INDEX "QcControlPoint_org_reference_key" ON "QcControlPoint" ("organizationId", reference);
CREATE INDEX "QcControlPoint_org_operation_idx" ON "QcControlPoint" ("organizationId", operation) WHERE "isActive";

-- Immutable configuration history: one row per create / update.
CREATE TABLE "QcControlPointVersion" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "controlPointId"   TEXT         NOT NULL REFERENCES "QcControlPoint" (id) ON DELETE CASCADE,
    version            INTEGER      NOT NULL CHECK (version >= 1),
    config             JSONB        NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    reason             TEXT,
    "changedBy"        TEXT,
    "changedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcControlPointVersion_org_idx" ON "QcControlPointVersion" ("organizationId");
CREATE UNIQUE INDEX "QcControlPointVersion_point_version_key" ON "QcControlPointVersion" ("controlPointId", version);

-- ---------------------------------------------------------------- checks
CREATE TABLE "QcCheck" (
    id                      TEXT           NOT NULL PRIMARY KEY,
    "organizationId"        TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "checkNumber"           TEXT           NOT NULL,
    title                   TEXT           NOT NULL,
    "controlPointId"        TEXT REFERENCES "QcControlPoint" (id),
    "controlPointVersionId" TEXT REFERENCES "QcControlPointVersion" (id),
    origin                  TEXT           NOT NULL CHECK (origin IN ('generated', 'manual', 'correction')),
    phase                   TEXT           NOT NULL DEFAULT 'pre_completion'
        CHECK (phase IN ('pre_completion', 'post_receipt')),
    -- generation identity (generated checks): baseKey#seq, unique among non-cancelled checks
    "baseKey"               TEXT,
    "generationSeq"         INTEGER        CHECK ("generationSeq" IS NULL OR "generationSeq" >= 0),
    "generationKey"         TEXT,
    operation               TEXT           CHECK (operation IS NULL
        OR operation IN ('receipt', 'delivery', 'manufacturing', 'work_order')),
    "checkType"             TEXT           NOT NULL
        CHECK ("checkType" IN ('instructions', 'pass_fail', 'measure', 'picture', 'worksheet')),
    -- source document (traceability; generated checks are also gated by it)
    "sourceType"            TEXT           NOT NULL DEFAULT 'manual'
        CHECK ("sourceType" IN ('production_order', 'work_order', 'goods_receipt', 'sales_delivery', 'manual')),
    "sourceId"              TEXT,
    "sourceLineId"          TEXT,
    "sourceRef"             TEXT,
    "workOrderId"           TEXT,
    "productId"             TEXT,
    "variantId"             TEXT,
    "batchNumber"           TEXT,
    "locationId"            TEXT,
    "supplierPartnerId"     TEXT,
    "workCenterId"          TEXT,
    "teamId"                TEXT REFERENCES "QcTeam" (id),
    "responsibleUserId"     TEXT REFERENCES "User" (id),
    "coveredQty"            NUMERIC(20, 6) CHECK ("coveredQty" IS NULL OR "coveredQty" >= 0),
    "testQty"               NUMERIC(20, 6) CHECK ("testQty" IS NULL OR "testQty" >= 0),
    -- requirement snapshot taken at creation: norm, limits, unit, decimals, instructions, worksheet fields
    snapshot                JSONB          NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(snapshot) = 'object'),
    "worksheetTemplateId"   TEXT REFERENCES "QcWorksheetTemplate" (id),
    blocking                BOOLEAN        NOT NULL DEFAULT false,
    "blockOnFailure"        BOOLEAN        NOT NULL DEFAULT false,
    "failureClassRequired"  BOOLEAN        NOT NULL DEFAULT false,
    status                  TEXT           NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'passed', 'failed', 'waived', 'cancelled')),
    "currentRevision"       INTEGER        NOT NULL DEFAULT 1 CHECK ("currentRevision" >= 1),
    "failureClassId"        TEXT REFERENCES "QcFailureClass" (id),
    -- disposition of the failed quantity (QC2)
    "failedQty"             NUMERIC(20, 6) CHECK ("failedQty" IS NULL OR "failedQty" >= 0),
    "quarantinedQty"        NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("quarantinedQty" >= 0),
    "quarantineStatus"      TEXT           NOT NULL DEFAULT 'not_required'
        CHECK ("quarantineStatus" IN ('not_required', 'pending', 'completed', 'failed')),
    "quarantineError"       TEXT,
    "quarantineMoveRef"     TEXT,
    "quarantineLocationId"  TEXT,
    "sourceClosed"          BOOLEAN        NOT NULL DEFAULT false,
    "correctsCheckId"       TEXT REFERENCES "QcCheck" (id),
    "includeInSpc"          BOOLEAN        NOT NULL DEFAULT true,
    -- waiver
    "waivedBy"              TEXT,
    "waivedAt"              TIMESTAMP(3),
    "waiveReason"           TEXT,
    "waiveDisposition"      TEXT           CHECK ("waiveDisposition" IS NULL
        OR "waiveDisposition" IN ('accepted_as_is', 'quantity_excluded', 'reworked', 'other')),
    "excludedQty"           NUMERIC(20, 6) CHECK ("excludedQty" IS NULL OR "excludedQty" >= 0),
    "cancelReason"          TEXT,
    notes                   TEXT,
    "doneBy"                TEXT,
    "doneAt"                TIMESTAMP(3),
    "createdBy"             TEXT,
    "createdAt"             TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"             TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (origin <> 'generated' OR ("generationKey" IS NOT NULL AND "controlPointId" IS NOT NULL)),
    CHECK (origin <> 'correction' OR "correctsCheckId" IS NOT NULL),
    CHECK (status <> 'waived' OR ("waiveReason" IS NOT NULL AND "waiveDisposition" IS NOT NULL)),
    CHECK ("waiveDisposition" IS DISTINCT FROM 'quantity_excluded' OR "excludedQty" IS NOT NULL)
);
CREATE INDEX "QcCheck_org_idx" ON "QcCheck" ("organizationId");
CREATE UNIQUE INDEX "QcCheck_org_number_key" ON "QcCheck" ("organizationId", "checkNumber");
-- The identity guarantee: one live check per generation key (a cancelled check frees its key, keeps its history).
CREATE UNIQUE INDEX "QcCheck_org_generation_key" ON "QcCheck" ("organizationId", "generationKey")
    WHERE status <> 'cancelled' AND "generationKey" IS NOT NULL;
CREATE INDEX "QcCheck_org_source_idx" ON "QcCheck" ("organizationId", "sourceType", "sourceId");
CREATE INDEX "QcCheck_org_status_idx" ON "QcCheck" ("organizationId", status, "createdAt" DESC);
CREATE INDEX "QcCheck_org_point_idx" ON "QcCheck" ("organizationId", "controlPointId", "createdAt")
    WHERE "controlPointId" IS NOT NULL;

-- One immutable row per execution attempt (revision). A reset opens revision n+1; earlier rows stay as recorded.
CREATE TABLE "QcCheckExecution" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "checkId"           TEXT           NOT NULL REFERENCES "QcCheck" (id) ON DELETE CASCADE,
    revision            INTEGER        NOT NULL CHECK (revision >= 1),
    result              TEXT           NOT NULL CHECK (result IN ('passed', 'failed')),
    "measureValue"      NUMERIC(20, 6),
    "worksheetAnswers"  JSONB,
    evaluation          JSONB          NOT NULL DEFAULT '{}'::jsonb,
    "failureClassId"    TEXT REFERENCES "QcFailureClass" (id),
    "failedQty"         NUMERIC(20, 6) CHECK ("failedQty" IS NULL OR "failedQty" >= 0),
    "pictureDocumentId" TEXT,
    notes               TEXT,
    "executedBy"        TEXT,
    "executedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcCheckExecution_org_idx" ON "QcCheckExecution" ("organizationId");
CREATE UNIQUE INDEX "QcCheckExecution_check_revision_key" ON "QcCheckExecution" ("checkId", revision);

-- Physical samples tested in an execution (which units were actually inspected).
CREATE TABLE "QcCheckSample" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "checkId"        TEXT           NOT NULL REFERENCES "QcCheck" (id) ON DELETE CASCADE,
    "executionId"    TEXT           NOT NULL REFERENCES "QcCheckExecution" (id) ON DELETE CASCADE,
    revision         INTEGER        NOT NULL,
    "sampleNo"       INTEGER        NOT NULL CHECK ("sampleNo" >= 1),
    "sampleRef"      TEXT,
    "batchNumber"    TEXT,
    "sampleQty"      NUMERIC(20, 6) CHECK ("sampleQty" IS NULL OR "sampleQty" > 0),
    "measureValue"   NUMERIC(20, 6),
    result           TEXT           NOT NULL CHECK (result IN ('passed', 'failed')),
    notes            TEXT,
    "sampledBy"      TEXT,
    "sampledAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcCheckSample_org_idx" ON "QcCheckSample" ("organizationId");
CREATE UNIQUE INDEX "QcCheckSample_execution_no_key" ON "QcCheckSample" ("executionId", "sampleNo");
CREATE INDEX "QcCheckSample_check_idx" ON "QcCheckSample" ("checkId", revision);

-- Append-only lifecycle log of a check.
CREATE TABLE "QcCheckEvent" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "checkId"        TEXT         NOT NULL REFERENCES "QcCheck" (id) ON DELETE CASCADE,
    action           TEXT         NOT NULL,
    "fromStatus"     TEXT,
    "toStatus"       TEXT,
    revision         INTEGER,
    reason           TEXT,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    "userId"         TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcCheckEvent_org_idx" ON "QcCheckEvent" ("organizationId");
CREATE INDEX "QcCheckEvent_check_idx" ON "QcCheckEvent" ("checkId", "createdAt");

-- History is evidence: executions, samples, events and point versions are never changed.
CREATE FUNCTION qc_refuse_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% rows are append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER "QcCheckExecution_append_only" BEFORE UPDATE ON "QcCheckExecution"
    FOR EACH ROW EXECUTE FUNCTION qc_refuse_update();
CREATE TRIGGER "QcCheckSample_append_only" BEFORE UPDATE ON "QcCheckSample"
    FOR EACH ROW EXECUTE FUNCTION qc_refuse_update();
CREATE TRIGGER "QcCheckEvent_append_only" BEFORE UPDATE ON "QcCheckEvent"
    FOR EACH ROW EXECUTE FUNCTION qc_refuse_update();
CREATE TRIGGER "QcControlPointVersion_append_only" BEFORE UPDATE ON "QcControlPointVersion"
    FOR EACH ROW EXECUTE FUNCTION qc_refuse_update();

-- ---------------------------------------------------------------- generation support (used by QC2)
-- Supplementary-check numbering: INSERT ... ON CONFLICT DO UPDATE SET "nextSeq" = "nextSeq" + 1 RETURNING.
CREATE TABLE "QcGenerationCounter" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "baseKey"        TEXT         NOT NULL,
    "nextSeq"        INTEGER      NOT NULL DEFAULT 0 CHECK ("nextSeq" >= 0),
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "QcGenerationCounter_org_key" ON "QcGenerationCounter" ("organizationId", "baseKey");

-- Random frequency: the roll is made once per base key and kept.
CREATE TABLE "QcSamplingDecision" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "controlPointId" TEXT          NOT NULL REFERENCES "QcControlPoint" (id) ON DELETE CASCADE,
    "baseKey"        TEXT          NOT NULL,
    selected         BOOLEAN       NOT NULL,
    roll             NUMERIC(9, 6) NOT NULL,
    pct              NUMERIC(9, 4) NOT NULL,
    "createdAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "QcSamplingDecision_org_key" ON "QcSamplingDecision" ("organizationId", "baseKey");

-- Periodic frequency: last generation per (point, scope); upserted under a row lock in the generating transaction.
CREATE TABLE "QcPointSchedule" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "controlPointId"  TEXT         NOT NULL REFERENCES "QcControlPoint" (id) ON DELETE CASCADE,
    "scopeKey"        TEXT         NOT NULL,
    "lastGeneratedAt" TIMESTAMP(3) NOT NULL
);
CREATE UNIQUE INDEX "QcPointSchedule_org_point_scope_key" ON "QcPointSchedule" ("organizationId", "controlPointId", "scopeKey");

-- ---------------------------------------------------------------- tenancy: standard policy (V214 form)
DO $$
DECLARE
    t    TEXT;
    expr TEXT := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['QcTeam', 'QcTeamMember', 'QcFailureClass', 'QcWorksheetTemplate', 'QcControlPoint',
            'QcControlPointVersion', 'QcCheck', 'QcCheckExecution', 'QcCheckSample', 'QcCheckEvent',
            'QcGenerationCounter', 'QcSamplingDecision', 'QcPointSchedule']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------- permissions
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['quality_control:read', 'quality_control:configure', 'quality_control:check',
        'quality_control:alert', 'quality_control:manage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions
        || ARRAY['quality_control:read', 'quality_control:configure', 'quality_control:check',
                 'quality_control:alert', 'quality_control:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
