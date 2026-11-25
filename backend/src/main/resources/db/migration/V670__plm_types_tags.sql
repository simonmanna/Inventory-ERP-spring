-- V670: PLM as an application (ADR-S086 §2). Additive on top of V440 / V441:
--   * PlmChangeType             — ECO types, each with its own stage pipeline (Odoo "ECO types")
--   * PlmChangeStage            — typeId (the pipeline it belongs to), folded (collapsed on the kanban board)
--   * EngineeringChange         — changeTypeId (the ECO type; `type` stays the kind: bom / routing / product /
--                                 document), kanbanState (normal / blocked / ready) and blockedReason
--   * PlmTag / PlmChangeTag     — free tags on ECOs
--   * PlmChangeStageApprover    — kind 'comment': a reviewer who is asked for an opinion but never blocks the stage
--   * PlmChangeActivity         — stageId (the stage the ECO was in after the entry, for time-in-stage reporting)
--                                 and the activity kinds of the PLM application (kanban, rebase, cost, first article)
-- Existing organizations keep their pipeline: their stages and ECOs move under a default type "Engineering changes".

CREATE TABLE "PlmChangeType" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    description      TEXT,
    color            TEXT,
    sequence         INTEGER NOT NULL DEFAULT 10,
    "isDefault"      BOOLEAN NOT NULL DEFAULT false,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "PlmChangeType_name_key" ON "PlmChangeType" ("organizationId", lower(name))
    WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "PlmChangeType_default_key" ON "PlmChangeType" ("organizationId")
    WHERE "isDefault" AND "deletedAt" IS NULL;

CREATE TABLE "PlmTag" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    color            TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "PlmTag_name_key" ON "PlmTag" ("organizationId", lower(name)) WHERE "deletedAt" IS NULL;

CREATE TABLE "PlmChangeTag" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "changeId"       TEXT NOT NULL REFERENCES "EngineeringChange" (id) ON DELETE CASCADE,
    "tagId"          TEXT NOT NULL REFERENCES "PlmTag" (id) ON DELETE CASCADE,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PlmChangeTag_key" ON "PlmChangeTag" ("changeId", "tagId");
CREATE INDEX "PlmChangeTag_tag_idx" ON "PlmChangeTag" ("organizationId", "tagId");

-- Stage pipelines per type: names are unique within a type.
ALTER TABLE "PlmChangeStage"
    ADD COLUMN "typeId" TEXT REFERENCES "PlmChangeType" (id),
    ADD COLUMN folded   BOOLEAN NOT NULL DEFAULT false;
DROP INDEX "PlmChangeStage_name_key";
CREATE UNIQUE INDEX "PlmChangeStage_name_key" ON "PlmChangeStage" ("organizationId", coalesce("typeId", ''),
    lower(name)) WHERE "deletedAt" IS NULL;
CREATE INDEX "PlmChangeStage_type_idx" ON "PlmChangeStage" ("organizationId", "typeId", sequence);

ALTER TABLE "EngineeringChange"
    ADD COLUMN "changeTypeId"  TEXT,
    ADD COLUMN "kanbanState"   TEXT NOT NULL DEFAULT 'normal',
    ADD COLUMN "blockedReason" TEXT,
    ADD CONSTRAINT "EngineeringChange_kanban_state" CHECK ("kanbanState" IN ('normal', 'blocked', 'ready'));
CREATE INDEX "EngineeringChange_change_type_idx" ON "EngineeringChange" ("organizationId", "changeTypeId");

ALTER TABLE "PlmChangeStageApprover" DROP CONSTRAINT "PlmChangeStageApprover_kind";
ALTER TABLE "PlmChangeStageApprover" ADD CONSTRAINT "PlmChangeStageApprover_kind"
    CHECK (kind IN ('required', 'optional', 'comment'));

ALTER TABLE "PlmChangeActivity" ADD COLUMN "stageId" TEXT;
ALTER TABLE "PlmChangeActivity" DROP CONSTRAINT "PlmChangeActivity_kind";
ALTER TABLE "PlmChangeActivity" ADD CONSTRAINT "PlmChangeActivity_kind" CHECK (kind IN ('created', 'comment', 'stage',
    'approval', 'status', 'applied', 'edited', 'kanban', 'rebased', 'cost', 'fai'));

-- Backfill: one default type per organization that already uses PLM stages or ECOs.
INSERT INTO "PlmChangeType" (id, "organizationId", name, description, sequence, "isDefault")
SELECT gen_random_uuid()::text, o.org, 'Engineering changes', 'BOM, routing, product and document changes.', 10, true
FROM (SELECT "organizationId" AS org FROM "PlmChangeStage"
      UNION
      SELECT "organizationId" FROM "EngineeringChange") o;
UPDATE "PlmChangeStage" s SET "typeId" = t.id
FROM "PlmChangeType" t
WHERE t."organizationId" = s."organizationId" AND t."isDefault" AND s."typeId" IS NULL;
UPDATE "EngineeringChange" e SET "changeTypeId" = t.id
FROM "PlmChangeType" t
WHERE t."organizationId" = e."organizationId" AND t."isDefault" AND e."stageId" IS NOT NULL;

-- Backfill the stage entered: an ECO is created in the first stage of its pipeline, and a stage move is logged as
-- "Moved from <stage> to <stage>." (the longest matching stage name wins).
UPDATE "PlmChangeActivity" a SET "stageId" = (
    SELECT s.id FROM "PlmChangeStage" s
    WHERE s."organizationId" = a."organizationId" AND s."typeId" IS NOT NULL
    ORDER BY s.sequence, s."createdAt" LIMIT 1)
WHERE a.kind = 'created' AND EXISTS (SELECT 1 FROM "EngineeringChange" e WHERE e.id = a."changeId"
                                                                           AND e."stageId" IS NOT NULL);
UPDATE "PlmChangeActivity" a SET "stageId" = (
    SELECT s.id FROM "PlmChangeStage" s
    WHERE s."organizationId" = a."organizationId" AND a.message LIKE '% to ' || s.name || '.'
    ORDER BY length(s.name) DESC LIMIT 1)
WHERE a.kind = 'stage';
CREATE INDEX "PlmChangeActivity_stage_idx" ON "PlmChangeActivity" ("organizationId", "stageId");

-- The standard tenant policy (V214 / V605) on the new tables.
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['PlmChangeType', 'PlmTag', 'PlmChangeTag'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
