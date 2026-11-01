-- V234: Cost Management CM-10 (ADR-S041) — cost adjustment run (late cost stranded in WIP of completed production
-- orders cascades to the orders' output layers through lineage: on hand → adjunct, sold → cost of sales) and the
-- costing period lock / reopen behind the ordered close gates.

CREATE TABLE "CostAdjustmentRun" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    number            TEXT NOT NULL,
    "periodFrom"      DATE NOT NULL,
    "periodTo"        DATE NOT NULL,
    status            TEXT NOT NULL DEFAULT 'posted',
    total             NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "journalEntryId"  TEXT,
    "reversalEntryId" TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"      TIMESTAMP(3),
    CONSTRAINT "CostAdjustmentRun_number" UNIQUE ("organizationId", number),
    CONSTRAINT "CostAdjustmentRun_status" CHECK (status IN ('posted', 'reversed'))
);

CREATE TABLE "CostAdjustmentRunLine" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    "runId"              TEXT NOT NULL REFERENCES "CostAdjustmentRun" (id) ON DELETE CASCADE,
    "adjustmentLineId"   TEXT NOT NULL REFERENCES "CostValueAdjustmentLine" (id),
    "orderCode"          TEXT NOT NULL,
    amount               NUMERIC(20, 6) NOT NULL,
    status               TEXT NOT NULL DEFAULT 'posted',
    CONSTRAINT "CostAdjustmentRunLine_status" CHECK (status IN ('posted', 'reversed'))
);
-- A WIP share cascades once (AD-2a no double counting).
CREATE UNIQUE INDEX "CostAdjustmentRunLine_once" ON "CostAdjustmentRunLine" ("adjustmentLineId")
    WHERE status = 'posted';

CREATE TABLE "CostingPeriodLock" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "periodFrom"     DATE NOT NULL,
    "periodTo"       DATE NOT NULL,
    status           TEXT NOT NULL DEFAULT 'locked',
    gates            JSONB,
    "lockedBy"       TEXT,
    "lockedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reopenedBy"     TEXT,
    "reopenedAt"     TIMESTAMP(3),
    "reopenReason"   TEXT,
    CONSTRAINT "CostingPeriodLock_status" CHECK (status IN ('locked', 'reopened')),
    CONSTRAINT "CostingPeriodLock_period" CHECK ("periodTo" >= "periodFrom")
);
CREATE INDEX "CostingPeriodLock_org_idx" ON "CostingPeriodLock" ("organizationId", "periodFrom", "periodTo")
    WHERE status = 'locked';

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['CostAdjustmentRun', 'CostAdjustmentRunLine', 'CostingPeriodLock'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
