-- A quotation the customer declined is no longer editable in place: its declined terms stay as sent and a change
-- needs a new revision (which snapshots them first). "declinedAt" tells such a decline from an internal approval
-- rejection; both use status 'rejected'.
ALTER TABLE "SalesQuotation" ADD COLUMN "declinedAt" TIMESTAMP(3);

-- Backfill: a quotation still rejected whose latest rejection was the customer's. Customer declines (staff, portal and
-- online link) are audited as action 'reject' with newValues.byCustomer = true; internal rejections are not.
UPDATE "SalesQuotation" q
   SET "declinedAt" = a."createdAt"
  FROM (SELECT DISTINCT ON ("entityId") "entityId", "createdAt", "newValues"
          FROM "AuditLog"
         WHERE entity = 'SalesQuotation' AND action = 'reject'
         ORDER BY "entityId", "createdAt" DESC) a
 WHERE a."entityId" = q.id
   AND q.status = 'rejected'
   AND q."declinedAt" IS NULL
   AND a."newValues" ->> 'byCustomer' = 'true';
