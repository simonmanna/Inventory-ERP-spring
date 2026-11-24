-- V643: PLM settings with the lifecycle policy (ADR-S086 §6) and first-article inspection (§7).
--   * PlmSettings     — one row per organization: the lifecycle policy matrix (what purchasing, sales and
--                       replenishment may do with a product in each lifecycle state; overrides on the defaults), the
--                       default cost policy of BOM changes and whether BOM / routing changes ask for a first article.
--   * firstArticle    — the first production order on the new version is inspected; faiStatus follows it:
--                       pending (no order completed yet) → open (inspection lot faiLotId) | no_plan (no final
--                       inspection plan: record the result on the change) → passed | failed | cancelled.

CREATE TABLE "PlmSettings" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "lifecyclePolicy"     JSONB NOT NULL DEFAULT '{}'::jsonb,
    "defaultCostPolicy"   TEXT NOT NULL DEFAULT 'none',
    "firstArticleDefault" BOOLEAN NOT NULL DEFAULT false,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmSettings_cost_policy" CHECK ("defaultCostPolicy" IN ('none', 'propose', 'activate'))
);
CREATE UNIQUE INDEX "PlmSettings_org_key" ON "PlmSettings" ("organizationId");

ALTER TABLE "EngineeringChange"
    ADD COLUMN "firstArticle" BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "faiStatus"    TEXT,
    ADD COLUMN "faiLotId"     TEXT,
    ADD COLUMN "faiOrderId"   TEXT,
    ADD COLUMN "faiNote"      TEXT;
ALTER TABLE "EngineeringChange" ADD CONSTRAINT "EngineeringChange_fai_status" CHECK ("faiStatus" IS NULL OR
    "faiStatus" IN ('pending', 'open', 'no_plan', 'passed', 'failed', 'cancelled'));
CREATE INDEX "EngineeringChange_fai_idx" ON "EngineeringChange" ("organizationId", "faiStatus")
    WHERE "faiStatus" IN ('pending', 'open', 'no_plan');

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    ALTER TABLE public."PlmSettings" ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation ON public."PlmSettings";
    EXECUTE format('CREATE POLICY tenant_isolation ON public."PlmSettings" USING (%s) WITH CHECK (%s)', expr, expr);
END $$;
