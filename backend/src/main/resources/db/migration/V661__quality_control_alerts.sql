-- V661: quality alerts (module qualitycontrol, ADR-S087 §8, QC3). Kanban stages with history, assignment history, tags,
-- root causes; at most one automatic alert per failed check and at most one NCR per alert. Closing an alert never
-- changes its check, an NCR or a CAPA.

CREATE TABLE "QcAlertStage" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    sequence         INTEGER      NOT NULL DEFAULT 0,
    "isClosed"       BOOLEAN      NOT NULL DEFAULT false,
    folded           BOOLEAN      NOT NULL DEFAULT false,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcAlertStage_org_idx" ON "QcAlertStage" ("organizationId");
CREATE UNIQUE INDEX "QcAlertStage_org_name_key" ON "QcAlertStage" ("organizationId", lower(name));

CREATE TABLE "QcTag" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    color            TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcTag_org_idx" ON "QcTag" ("organizationId");
CREATE UNIQUE INDEX "QcTag_org_name_key" ON "QcTag" ("organizationId", lower(name));

CREATE TABLE "QcRootCause" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    description      TEXT,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcRootCause_org_idx" ON "QcRootCause" ("organizationId");
CREATE UNIQUE INDEX "QcRootCause_org_name_key" ON "QcRootCause" ("organizationId", lower(name));

CREATE TABLE "QcAlert" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "alertNumber"       TEXT           NOT NULL,
    title               TEXT           NOT NULL,
    description         TEXT,
    origin              TEXT           NOT NULL CHECK (origin IN ('manual', 'auto')),
    "stageId"           TEXT           NOT NULL REFERENCES "QcAlertStage" (id),
    priority            INTEGER        NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 3),
    "teamId"            TEXT           REFERENCES "QcTeam" (id),
    "responsibleUserId" TEXT           REFERENCES "User" (id),
    "checkId"           TEXT           REFERENCES "QcCheck" (id),
    "correctionCheckId" TEXT           REFERENCES "QcCheck" (id),
    "controlPointId"    TEXT           REFERENCES "QcControlPoint" (id),
    "productId"         TEXT           REFERENCES "Product" (id),
    "variantId"         TEXT,
    "batchNumber"       TEXT,
    "workCenterId"      TEXT,
    "sourceType"        TEXT           CHECK ("sourceType" IS NULL OR "sourceType" IN ('production_order', 'work_order',
                                           'goods_receipt', 'sales_delivery')),
    "sourceId"          TEXT,
    "sourceRef"         TEXT,
    "supplierPartnerId" TEXT           REFERENCES "Partner" (id),
    "failureClassId"    TEXT           REFERENCES "QcFailureClass" (id),
    "rootCauseId"       TEXT           REFERENCES "QcRootCause" (id),
    "correctiveAction"  TEXT,
    "preventiveAction"  TEXT,
    "ncrId"             TEXT,
    "closedAt"          TIMESTAMP(3),
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcAlert_org_idx" ON "QcAlert" ("organizationId");
CREATE UNIQUE INDEX "QcAlert_org_number_key" ON "QcAlert" ("organizationId", "alertNumber");
CREATE INDEX "QcAlert_stage_idx" ON "QcAlert" ("organizationId", "stageId");
CREATE INDEX "QcAlert_check_idx" ON "QcAlert" ("checkId");
-- At most one automatic alert per failed check (written with ON CONFLICT DO NOTHING).
CREATE UNIQUE INDEX "QcAlert_auto_check_key" ON "QcAlert" ("organizationId", "checkId") WHERE origin = 'auto';
-- At most one NCR per alert, and an NCR belongs to one alert.
CREATE UNIQUE INDEX "QcAlert_ncr_key" ON "QcAlert" ("organizationId", "ncrId") WHERE "ncrId" IS NOT NULL;

CREATE TABLE "QcAlertTag" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "alertId"        TEXT         NOT NULL REFERENCES "QcAlert" (id) ON DELETE CASCADE,
    "tagId"          TEXT         NOT NULL REFERENCES "QcTag" (id) ON DELETE CASCADE,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcAlertTag_org_idx" ON "QcAlertTag" ("organizationId");
CREATE UNIQUE INDEX "QcAlertTag_alert_tag_key" ON "QcAlertTag" ("alertId", "tagId");

CREATE TABLE "QcAlertStageHistory" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "alertId"        TEXT         NOT NULL REFERENCES "QcAlert" (id) ON DELETE CASCADE,
    "fromStageId"    TEXT,
    "toStageId"      TEXT         NOT NULL,
    comment          TEXT,
    "userId"         TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcAlertStageHistory_org_idx" ON "QcAlertStageHistory" ("organizationId");
CREATE INDEX "QcAlertStageHistory_alert_idx" ON "QcAlertStageHistory" ("alertId", "createdAt");

CREATE TABLE "QcAlertAssignmentHistory" (
    id                      TEXT         NOT NULL PRIMARY KEY,
    "organizationId"        TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "alertId"               TEXT         NOT NULL REFERENCES "QcAlert" (id) ON DELETE CASCADE,
    "fromTeamId"            TEXT,
    "toTeamId"              TEXT,
    "fromResponsibleUserId" TEXT,
    "toResponsibleUserId"   TEXT,
    "userId"                TEXT,
    "createdAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QcAlertAssignmentHistory_org_idx" ON "QcAlertAssignmentHistory" ("organizationId");
CREATE INDEX "QcAlertAssignmentHistory_alert_idx" ON "QcAlertAssignmentHistory" ("alertId", "createdAt");

CREATE TRIGGER "QcAlertStageHistory_append_only" BEFORE UPDATE ON "QcAlertStageHistory"
    FOR EACH ROW EXECUTE FUNCTION qc_refuse_update();
CREATE TRIGGER "QcAlertAssignmentHistory_append_only" BEFORE UPDATE ON "QcAlertAssignmentHistory"
    FOR EACH ROW EXECUTE FUNCTION qc_refuse_update();

-- ---------------------------------------------------------------- tenancy: standard policy (V214 form)
DO $$
DECLARE
    t    TEXT;
    expr TEXT := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['QcAlertStage', 'QcTag', 'QcRootCause', 'QcAlert', 'QcAlertTag', 'QcAlertStageHistory',
            'QcAlertAssignmentHistory']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END
$$;
