-- Review 2026-11-17 (ACR-8 / ACR-14): lineage bookkeeping for late costs that reach WIP, and for accrual clearings.
--
-- 1) CostValueAdjustmentLine."clearedAmount" / CostValueAdjustmentRelease."clearedAmount": the part of a WIP share that
--    a landed-cost accrual clearing (LC-4) has already taken back out of WIP. The CM-10 cost adjustment run carries only
--    what is still in WIP on to the finished order's output, so a partial clearing before the run no longer leaves WIP
--    short, and a run whose share was cleared since cannot be reversed into WIP again.
-- 2) CostValueAdjustmentRelease: where each release of an open adjunct went (cost of sales, WIP of an order, scrap, a
--    write-off account). A clearing takes the released part of an estimate back out of those accounts pro rata, instead
--    of always out of cost of sales. Adjunct released into the WIP of an order is now carried on to the order's output
--    by the cost adjustment run, like any other late cost in WIP.
-- 3) Row-level security for the new table and for PurchaseOrderBillApplication (V530).

ALTER TABLE "CostValueAdjustmentLine" ADD COLUMN "clearedAmount" NUMERIC(20, 6) NOT NULL DEFAULT 0;

CREATE TABLE "CostValueAdjustmentRelease" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "adjustmentId"   TEXT           NOT NULL REFERENCES "CostValueAdjustment" (id) ON DELETE CASCADE,
    target           TEXT           NOT NULL,
    "targetRef"      TEXT,
    "accountId"      TEXT           NOT NULL REFERENCES "Account" (id),
    amount           NUMERIC(20, 6) NOT NULL,
    "clearedAmount"  NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "sourceType"     TEXT           NOT NULL,
    "sourceId"       TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CostValueAdjustmentRelease_adjustment_idx"
    ON "CostValueAdjustmentRelease" ("organizationId", "adjustmentId");
CREATE INDEX "CostValueAdjustmentRelease_wip_idx"
    ON "CostValueAdjustmentRelease" ("organizationId", "targetRef") WHERE target = 'wip';

-- A cost adjustment run line carries either a lineage line or a release into WIP.
ALTER TABLE "CostAdjustmentRunLine" ALTER COLUMN "adjustmentLineId" DROP NOT NULL;
ALTER TABLE "CostAdjustmentRunLine" ADD COLUMN "releaseId" TEXT REFERENCES "CostValueAdjustmentRelease" (id);
ALTER TABLE "CostAdjustmentRunLine" ADD CONSTRAINT "CostAdjustmentRunLine_one_share"
    CHECK (("adjustmentLineId" IS NULL) <> ("releaseId" IS NULL));
CREATE UNIQUE INDEX "CostAdjustmentRunLine_release_once" ON "CostAdjustmentRunLine" ("releaseId")
    WHERE status = 'posted' AND "releaseId" IS NOT NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['CostValueAdjustmentRelease', 'PurchaseOrderBillApplication'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
