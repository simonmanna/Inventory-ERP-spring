-- SG-5: customer type, home branch, default currency, second phone, district, suspension and non-destructive merge.
-- A merge never rewrites posted documents: the old record points at the survivor and accepts no new documents.
ALTER TABLE "Partner"
    ADD COLUMN "customerType" TEXT CHECK ("customerType" IS NULL OR "customerType" IN
        ('retail', 'wholesale', 'contractor', 'distributor', 'government', 'corporate', 'other')),
    ADD COLUMN "homeBranchId" TEXT,
    ADD COLUMN "defaultCurrencyCode" TEXT,
    ADD COLUMN "altPhone" TEXT,
    ADD COLUMN district TEXT,
    ADD COLUMN "suspendedAt" TIMESTAMP(3),
    ADD COLUMN "suspendedReason" TEXT,
    ADD COLUMN "mergedIntoPartnerId" TEXT REFERENCES "Partner" (id),
    ADD COLUMN "mergedAt" TIMESTAMP(3),
    ADD COLUMN "mergedById" TEXT,
    ADD COLUMN "mergeReason" TEXT,
    ADD CONSTRAINT "Partner_not_merged_into_itself" CHECK ("mergedIntoPartnerId" IS NULL OR "mergedIntoPartnerId" <> id);
CREATE INDEX "Partner_mergedInto_idx" ON "Partner" ("mergedIntoPartnerId") WHERE "mergedIntoPartnerId" IS NOT NULL;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:customer:read', 'sales:customer:manage', 'sales:customer:merge']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:customer:read', 'sales:customer:manage',
        'sales:customer:merge'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name = 'Administrator';
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:customer:read'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name <> 'Administrator' AND permissions && ARRAY['partners.view'];
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:customer:manage'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name <> 'Administrator' AND permissions && ARRAY['partners.edit'];
