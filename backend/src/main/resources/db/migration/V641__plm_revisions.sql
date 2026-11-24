-- V641: PLM versions (ADR-S086 §3).
--   * PlmRevision — one row per product revision: current / superseded, effective from / to, the ECO that made it and
--                   a snapshot of what the revision was (default active BOM with its lines, routing operations,
--                   released documents, product data, unit cost). Revision codes are unique per product.
--   * PlmAsBuilt  — the revision a completed production order produced (matched by the BOM it used, else by date),
--                   with its batch: stock by revision is reported through batches.
-- The current revision of an item registered before V641 is captured from the live data the first time it is read;
-- revisions it went through before are recorded here without a snapshot.

CREATE TABLE "PlmRevision" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "itemId"         TEXT NOT NULL REFERENCES "PlmItem" (id) ON DELETE CASCADE,
    "productId"      TEXT NOT NULL,
    revision         TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'current',
    "effectiveFrom"  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "effectiveTo"    TIMESTAMP(3),
    "changeId"       TEXT,
    "bomId"          TEXT,
    "bomVersion"     INTEGER,
    "routingId"      TEXT,
    "unitCost"       NUMERIC(20, 6),
    "costSource"     TEXT,
    snapshot         JSONB NOT NULL DEFAULT '{}'::jsonb,
    note             TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmRevision_status" CHECK (status IN ('current', 'superseded')),
    CONSTRAINT "PlmRevision_cost_source" CHECK ("costSource" IS NULL OR "costSource" IN ('standard', 'cost_price'))
);
CREATE UNIQUE INDEX "PlmRevision_key" ON "PlmRevision" ("organizationId", "productId", revision);
CREATE UNIQUE INDEX "PlmRevision_current_key" ON "PlmRevision" ("organizationId", "productId")
    WHERE status = 'current';
CREATE INDEX "PlmRevision_item_idx" ON "PlmRevision" ("itemId", "effectiveFrom");
CREATE INDEX "PlmRevision_bom_idx" ON "PlmRevision" ("organizationId", "bomId");

CREATE TABLE "PlmAsBuilt" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "orderId"        TEXT NOT NULL,
    "orderCode"      TEXT NOT NULL,
    "productId"      TEXT NOT NULL,
    "revisionId"     TEXT REFERENCES "PlmRevision" (id) ON DELETE SET NULL,
    revision         TEXT,
    "matchedBy"      TEXT NOT NULL,
    "bomId"          TEXT,
    "bomVersion"     INTEGER,
    "batchNumber"    TEXT,
    quantity         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "completedAt"    TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "PlmAsBuilt_matched_by" CHECK ("matchedBy" IN ('bom', 'date', 'none'))
);
CREATE UNIQUE INDEX "PlmAsBuilt_order_key" ON "PlmAsBuilt" ("organizationId", "orderId");
CREATE INDEX "PlmAsBuilt_product_idx" ON "PlmAsBuilt" ("organizationId", "productId", revision);
CREATE INDEX "PlmAsBuilt_batch_idx" ON "PlmAsBuilt" ("organizationId", "productId", "batchNumber");

-- Revisions an item went through before snapshots were kept (from its lifecycle history).
INSERT INTO "PlmRevision" (id, "organizationId", "itemId", "productId", revision, status, "effectiveFrom",
                           "effectiveTo", note)
SELECT gen_random_uuid()::text, e."organizationId", e."itemId", i."productId", e."fromRevision", 'superseded',
       coalesce((SELECT max(p."createdAt") FROM "PlmLifecycleEvent" p
                 WHERE p."itemId" = e."itemId" AND p.kind = 'revision' AND p."createdAt" < e."createdAt"),
                i."createdAt"),
       e."createdAt", 'Recorded before PLM kept revision snapshots.'
FROM "PlmLifecycleEvent" e
JOIN "PlmItem" i ON i.id = e."itemId"
WHERE e.kind = 'revision' AND e."fromRevision" IS NOT NULL AND e."fromRevision" <> i.revision
ON CONFLICT DO NOTHING;

-- The standard tenant policy (V214 / V605) on the new tables.
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['PlmRevision', 'PlmAsBuilt'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
