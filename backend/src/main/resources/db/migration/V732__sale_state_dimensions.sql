-- ADR-S094 C1 (§4): state dimensions of the Sale and an explicit approval flow.
--
-- * Commercial status is draft | confirmed | done | cancelled. "pending_approval" becomes draft with
--   approvalStatus = pending; "in_fulfilment" becomes confirmed (delivery and invoice progress are in deliveryStatus and
--   invoiceStatus; API responses carry the old value as a derived "stage" for one release).
-- * approvalStatus is its own dimension: not_required | required | pending | approved | rejected. A sale is submitted
--   for approval explicitly; the approval is bound to a hash of the approvable content and to the reasons approved.
-- * saleType: order | cash (cash sales arrive in C3; every existing row is an order).
-- * Settings: confirmOnApproval (an approved order confirms at once, as before), allowSelfApproval (OD-8: off).
-- * TA-6: the default sales quarantine location exists for every organization that sends rejected goods there.

ALTER TABLE "SalesOrder"
    ADD COLUMN "saleType" TEXT NOT NULL DEFAULT 'order',
    ADD COLUMN "approvalStatus" TEXT NOT NULL DEFAULT 'not_required',
    ADD COLUMN "approvalContentHash" TEXT,
    ADD COLUMN "approvalContentEditedById" TEXT,
    ADD COLUMN "approvalSubmittedHash" TEXT,
    ADD COLUMN "approvalSubmittedReasons" TEXT[],
    ADD COLUMN "approvalRuleVersion" TEXT,
    ADD COLUMN "approvalSubmittedById" TEXT,
    ADD COLUMN "approvalSubmittedAt" TIMESTAMP(3),
    ADD COLUMN "approvalJustification" TEXT,
    ADD COLUMN "approvedHash" TEXT,
    ADD COLUMN "approvedReasons" TEXT[],
    ADD COLUMN "approvedRuleVersion" TEXT,
    ADD COLUMN "approvedById" TEXT,
    ADD COLUMN "approvedAt" TIMESTAMP(3);

ALTER TABLE "SalesSettings"
    ADD COLUMN "confirmOnApproval" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "allowSelfApproval" BOOLEAN NOT NULL DEFAULT false;

-- Backfill, with a proof that every row lands in exactly one new combination.
DO $$
DECLARE
    b_draft BIGINT; b_pending BIGINT; b_confirmed BIGINT; b_fulfilment BIGINT; b_done BIGINT; b_cancelled BIGINT;
    b_total BIGINT;
BEGIN
    SELECT count(*) FILTER (WHERE status = 'draft'), count(*) FILTER (WHERE status = 'pending_approval'),
           count(*) FILTER (WHERE status = 'confirmed'), count(*) FILTER (WHERE status = 'in_fulfilment'),
           count(*) FILTER (WHERE status = 'done'), count(*) FILTER (WHERE status = 'cancelled'), count(*)
      INTO b_draft, b_pending, b_confirmed, b_fulfilment, b_done, b_cancelled, b_total
      FROM "SalesOrder";
    IF b_draft + b_pending + b_confirmed + b_fulfilment + b_done + b_cancelled <> b_total THEN
        RAISE EXCEPTION 'SalesOrder has statuses outside the known lifecycle: %', (SELECT string_agg(DISTINCT status, ', ')
            FROM "SalesOrder" WHERE status NOT IN ('draft', 'pending_approval', 'confirmed', 'in_fulfilment', 'done',
            'cancelled'));
    END IF;

    UPDATE "SalesOrder"
       SET status = 'draft', "approvalStatus" = 'pending', "approvalSubmittedReasons" = "approvalReasons",
           "approvalSubmittedById" = "createdById", "approvalSubmittedAt" = "updatedAt"
     WHERE status = 'pending_approval';
    UPDATE "SalesOrder" SET status = 'confirmed' WHERE status = 'in_fulfilment';
    -- a draft whose last approval was rejected keeps that outcome
    UPDATE "SalesOrder" SET "approvalStatus" = 'rejected'
     WHERE status = 'draft' AND "approvalStatus" = 'not_required'
       AND EXISTS (SELECT 1 FROM unnest("approvalReasons") r WHERE r LIKE 'Rejected:%');
    -- orders confirmed through an approval
    UPDATE "SalesOrder" o SET "approvalStatus" = 'approved'
     WHERE o.status IN ('confirmed', 'done', 'cancelled') AND EXISTS (SELECT 1 FROM "ApprovalRequest" r
           WHERE r."organizationId" = o."organizationId" AND r."entityType" = 'sales_order' AND r."entityId" = o.id
             AND r.status = 'approved');

    IF (SELECT count(*) FROM "SalesOrder" WHERE status = 'draft' AND "approvalStatus" = 'pending') <> b_pending
       OR (SELECT count(*) FROM "SalesOrder" WHERE status = 'draft') <> b_draft + b_pending
       OR (SELECT count(*) FROM "SalesOrder" WHERE status = 'confirmed') <> b_confirmed + b_fulfilment
       OR (SELECT count(*) FROM "SalesOrder" WHERE status = 'done') <> b_done
       OR (SELECT count(*) FROM "SalesOrder" WHERE status = 'cancelled') <> b_cancelled
       OR (SELECT count(*) FROM "SalesOrder") <> b_total THEN
        RAISE EXCEPTION 'SalesOrder state backfill does not reconcile (draft %, pending %, confirmed %, in fulfilment %,'
            ' done %, cancelled %)', b_draft, b_pending, b_confirmed, b_fulfilment, b_done, b_cancelled;
    END IF;
    RAISE NOTICE 'SalesOrder backfill: % draft + % pending approval → draft; % confirmed + % in fulfilment → confirmed;'
        ' % done; % cancelled', b_draft, b_pending, b_confirmed, b_fulfilment, b_done, b_cancelled;
END $$;

ALTER TABLE "SalesOrder" DROP CONSTRAINT "SalesOrder_status_check";
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_status_check"
    CHECK (status IN ('draft', 'confirmed', 'done', 'cancelled'));
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_saleType_check" CHECK ("saleType" IN ('order', 'cash'));
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_approvalStatus_check"
    CHECK ("approvalStatus" IN ('not_required', 'required', 'pending', 'approved', 'rejected'));
-- I1: a confirmed (or done) sale needed no approval or had it
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_approval_state_check"
    CHECK (status NOT IN ('confirmed', 'done') OR "approvalStatus" IN ('not_required', 'approved'));
-- I7 (paymentMode part; branch and profile follow in C3): a cash sale is paid immediately
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_cash_check"
    CHECK ("saleType" <> 'cash' OR "paymentMode" = 'cash');
CREATE INDEX "SalesOrder_approval_pending_idx" ON "SalesOrder" ("organizationId")
    WHERE "approvalStatus" = 'pending' AND "deletedAt" IS NULL;

-- ADR-S094 TA-6: the default quarantine location, created here rather than inside the first proof of delivery that
-- rejects goods (a second transaction inside a keyed money request). Same row as VirtualLocationService.ensure.
INSERT INTO "InventoryLocation" (id, "organizationId", code, name, type, "isActive", "createdAt", "updatedAt")
SELECT gen_random_uuid()::text, org.id, 'SALES-QUARANTINE', 'Sales rejections quarantine', 'virtual'::"LocationType",
       true, now(), now()
  FROM "Organization" org
  LEFT JOIN "SalesSettings" s ON s."organizationId" = org.id
 WHERE (s."organizationId" IS NULL OR (s."rejectedToQuarantine" AND s."rejectedQuarantineLocationId" IS NULL))
   AND NOT EXISTS (SELECT 1 FROM "InventoryLocation" l WHERE l."organizationId" = org.id AND l.code = 'SALES-QUARANTINE'
                   AND l."deletedAt" IS NULL);
