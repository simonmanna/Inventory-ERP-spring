-- Sales readiness SO-10: approving / rejecting a sales order has its own permission (it reused sales:quotation:approve).
-- Every role that could approve orders through the quotation permission keeps that ability.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, 'sales:order:approve', 'sales', 'order', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM "Permission" WHERE key = 'sales:order:approve');

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:order:approve']))
WHERE 'sales:quotation:approve' = ANY (permissions) AND NOT ('sales:order:approve' = ANY (permissions));
