-- Track C Phase 5 (ADR-S020): management dashboards. The analytics module owns no table; this adds its permissions and
-- the indexes of its query paths only. Additive: no data is rewritten except the Administrator role's permission list.

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY[
    'analytics:view', 'analytics:quarry', 'analytics:aggregates', 'analytics:blocks', 'analytics:inventory',
    'analytics:procurement', 'analytics:sales', 'analytics:fleet', 'analytics:maintenance', 'analytics:finance',
    'analytics:branch']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY[
        'analytics:view', 'analytics:quarry', 'analytics:aggregates', 'analytics:blocks', 'analytics:inventory',
        'analytics:procurement', 'analytics:sales', 'analytics:fleet', 'analytics:maintenance', 'analytics:finance',
        'analytics:branch'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

-- Branch filter over sites and plants.
CREATE INDEX IF NOT EXISTS "QuarrySite_org_branch_idx" ON "QuarrySite" ("organizationId", "branchId");
CREATE INDEX IF NOT EXISTS "CrushingPlant_org_site_idx" ON "CrushingPlant" ("organizationId", "siteId");
-- Period reads: crushing runs without a plant filter, block orders by creation day.
CREATE INDEX IF NOT EXISTS "CrushingRun_org_date_posted_idx" ON "CrushingRun" ("organizationId", "runDate")
    WHERE status = 'posted';
CREATE INDEX IF NOT EXISTS "ProductionOrder_org_created_idx" ON "ProductionOrder" ("organizationId", "createdAt");
-- Purchase price trend and spend by product.
CREATE INDEX IF NOT EXISTS "PurchaseOrderLine_org_product_idx" ON "PurchaseOrderLine" ("organizationId", "productId");
-- Sales documents and open balances by date.
CREATE INDEX IF NOT EXISTS "Document_org_type_issue_idx" ON "Document" ("organizationId", "documentType", "issueDate");
CREATE INDEX IF NOT EXISTS "Document_org_open_balance_idx" ON "Document" ("organizationId", "documentType")
    WHERE "amountResidual" > 0;
-- Finished trips by delivery time.
CREATE INDEX IF NOT EXISTS "FleetLoad_org_delivered_idx" ON "FleetLoad" ("organizationId", "deliveredAt")
    WHERE status IN ('delivered', 'closed');
-- Maintenance spend by completion day.
CREATE INDEX IF NOT EXISTS "FleetWorkOrder_org_completed_idx" ON "FleetWorkOrder" ("organizationId", "completedAt");
-- Count variances by submission day.
CREATE INDEX IF NOT EXISTS "InventoryCountSession_org_submitted_idx" ON "InventoryCountSession"
    ("organizationId", "submittedAt");
