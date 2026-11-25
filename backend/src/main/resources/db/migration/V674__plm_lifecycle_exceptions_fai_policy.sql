-- V674: owner decisions of 2026-11-24 on the PLM application (ADR-S086 §6, §7, §12).
--   * PlmLifecycleException — an authorized, time-boxed exception to the lifecycle policy: one product, one
--                             activity (purchase, produce, sell, pos, replenish), a reason, a last valid day; revoked
--                             rows stay as the audit trail.
--   * firstArticleRequired  — mandatory first-article inspection: on PlmSettings for every BOM / routing change of the
--                             organization, on PlmChangeType for the changes of one ECO type.
--   * defaultCostPolicy     — new organizations propose the rolled-up cost (approved default), never activate it.
--   * plm:exception         — granted to the Administrator roles, as V440 did for the other PLM permissions.

CREATE TABLE "PlmLifecycleException" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "productId"      TEXT NOT NULL REFERENCES "Product" (id),
    activity         TEXT NOT NULL,
    reason           TEXT NOT NULL,
    "validUntil"     DATE NOT NULL,
    "createdBy"      TEXT,
    "revokedAt"      TIMESTAMP(3),
    "revokedBy"      TEXT,
    "revokeReason"   TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmLifecycleException_activity" CHECK (activity IN ('purchase', 'produce', 'sell', 'pos',
        'replenish'))
);
CREATE INDEX "PlmLifecycleException_product_idx" ON "PlmLifecycleException" ("organizationId", "productId", activity)
    WHERE "revokedAt" IS NULL;

ALTER TABLE "PlmSettings" ALTER COLUMN "defaultCostPolicy" SET DEFAULT 'propose';
ALTER TABLE "PlmSettings" ADD COLUMN "firstArticleRequired" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "PlmChangeType" ADD COLUMN "firstArticleRequired" BOOLEAN NOT NULL DEFAULT false;

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    ALTER TABLE public."PlmLifecycleException" ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation ON public."PlmLifecycleException";
    EXECUTE format('CREATE POLICY tenant_isolation ON public."PlmLifecycleException" USING (%s) WITH CHECK (%s)',
        expr, expr);
END $$;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['plm:exception'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
