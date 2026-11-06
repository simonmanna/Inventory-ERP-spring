-- SG-1: cost and margin need sales:cost:read; sales:branch:override lifts a user's data scope within Sales.
-- Whoever already reads sales reports or manages pricing keeps seeing cost. Order and quotation clerks do not.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:cost:read', 'sales:branch:override']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:cost:read'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL
  AND (name = 'Administrator' OR permissions && ARRAY['sales:report:read', 'sales:pricing:manage']);

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:branch:override'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name = 'Administrator';
