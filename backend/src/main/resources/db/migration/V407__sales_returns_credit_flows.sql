-- SG-7: damaged disposition, per-line reject, sales returns and bad debt accounts. The accounts are opt-in, so existing
-- postings and any org that sets nothing keep today's behaviour.
ALTER TABLE "SalesReturnLine" DROP CONSTRAINT "SalesReturnLine_disposition_check";
ALTER TABLE "SalesReturnLine" ADD CONSTRAINT "SalesReturnLine_disposition_check"
    CHECK (disposition IS NULL OR disposition IN ('restock', 'quarantine', 'damaged', 'scrap'));
ALTER TABLE "SalesReturnLine"
    ADD COLUMN "rejectedQty" NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("rejectedQty" >= 0),
    ADD COLUMN "rejectReason" TEXT,
    ADD COLUMN "issueUnitCost" NUMERIC(20, 6);
ALTER TABLE "SalesSettings"
    ADD COLUMN "salesReturnsAccountId" TEXT,
    ADD COLUMN "badDebtAccountId" TEXT;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:credit_note:write', 'sales:write_off:post']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:credit_note:write', 'sales:write_off:post'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name = 'Administrator';
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:credit_note:write'])), "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND name <> 'Administrator' AND permissions && ARRAY['sales:return:approve'];
