-- V227: Cost Management CM-4 (ADR-S034) — governed normal capacity per work centre (IAS 2.13) and the cause-based
-- settlement of absorbed vs actual conversion overhead (AD-11), traceable to lineage targets.

CREATE TABLE "WorkCenterCapacity" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "workCenterId"        TEXT NOT NULL REFERENCES "WorkCenter" (id),
    "costCenterId"        TEXT NOT NULL REFERENCES "CostCenter" (id),
    basis                 TEXT NOT NULL DEFAULT 'machine_hours',
    "normalCapacity"      NUMERIC(20, 4) NOT NULL CHECK ("normalCapacity" > 0),
    "budgetFixedOverhead" NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK ("budgetFixedOverhead" >= 0),
    "fixedOverheadRate"   NUMERIC(20, 6),
    "periodDays"          INTEGER NOT NULL DEFAULT 30 CHECK ("periodDays" BETWEEN 1 AND 366),
    derivation            TEXT NOT NULL,
    "rateSource"          TEXT NOT NULL DEFAULT 'manual',
    "effectiveFrom"       DATE NOT NULL,
    "reviewDue"           DATE NOT NULL,
    status                TEXT NOT NULL DEFAULT 'proposed',
    "proposedBy"          TEXT,
    "approvedBy"          TEXT,
    "approvedAt"          TIMESTAMP(3),
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "WorkCenterCapacity_basis" CHECK (basis IN ('machine_hours', 'labour_hours')),
    CONSTRAINT "WorkCenterCapacity_source" CHECK ("rateSource" IN ('manual', 'fleet_tco', 'payroll_actual')),
    CONSTRAINT "WorkCenterCapacity_status" CHECK (status IN ('proposed', 'approved', 'rejected')),
    CONSTRAINT "WorkCenterCapacity_review" CHECK ("reviewDue" > "effectiveFrom")
);
CREATE INDEX "WorkCenterCapacity_wc_idx" ON "WorkCenterCapacity" ("organizationId", "workCenterId", "effectiveFrom");

CREATE TABLE "AbsorptionSettlement" (
    id                       TEXT PRIMARY KEY,
    "organizationId"         TEXT NOT NULL REFERENCES "Organization" (id),
    number                   TEXT NOT NULL,
    "workCenterId"           TEXT NOT NULL REFERENCES "WorkCenter" (id),
    "capacityId"             TEXT NOT NULL REFERENCES "WorkCenterCapacity" (id),
    "periodFrom"             DATE NOT NULL,
    "periodTo"               DATE NOT NULL,
    status                   TEXT NOT NULL DEFAULT 'posted',
    actual                   NUMERIC(20, 2) NOT NULL,
    absorbed                 NUMERIC(20, 2) NOT NULL,
    "budgetFixed"            NUMERIC(20, 2) NOT NULL,
    "normalActivity"         NUMERIC(20, 4) NOT NULL,
    "actualActivity"         NUMERIC(20, 4) NOT NULL,
    "fixedRate"              NUMERIC(20, 6) NOT NULL,
    spending                 NUMERIC(20, 2) NOT NULL,
    volume                   NUMERIC(20, 2) NOT NULL,
    "rateError"              NUMERIC(20, 2) NOT NULL,
    classification           TEXT,
    justification            TEXT,
    "journalEntryId"         TEXT,
    "reversalEntryId"        TEXT,
    "createdBy"              TEXT,
    "createdAt"              TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"             TIMESTAMP(3),
    CONSTRAINT "AbsorptionSettlement_number" UNIQUE ("organizationId", number),
    CONSTRAINT "AbsorptionSettlement_status" CHECK (status IN ('posted', 'reversed')),
    CONSTRAINT "AbsorptionSettlement_class" CHECK (classification IS NULL OR classification IN ('normal', 'abnormal'))
);
CREATE UNIQUE INDEX "AbsorptionSettlement_posted" ON "AbsorptionSettlement" ("organizationId", "workCenterId",
    "periodFrom", "periodTo") WHERE status = 'posted';

CREATE TABLE "AbsorptionSettlementLine" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT NOT NULL REFERENCES "Organization" (id),
    "settlementId"    TEXT NOT NULL REFERENCES "AbsorptionSettlement" (id) ON DELETE CASCADE,
    component         TEXT NOT NULL,
    cause             TEXT NOT NULL,
    treatment         TEXT NOT NULL,
    target            TEXT NOT NULL,
    "orderId"         TEXT,
    "productId"       TEXT,
    "layerLedgerId"   TEXT,
    "adjustmentId"    TEXT,
    "accountId"       TEXT NOT NULL,
    amount            NUMERIC(20, 2) NOT NULL,
    CONSTRAINT "AbsorptionSettlementLine_component" CHECK (component IN ('spending', 'volume', 'rate_error',
        'total')),
    CONSTRAINT "AbsorptionSettlementLine_treatment" CHECK (treatment IN ('expense', 'capitalise',
        'reduce_inventory', 'cogs'))
);
CREATE INDEX "AbsorptionSettlementLine_settlement_idx" ON "AbsorptionSettlementLine" ("settlementId");

-- Settlement shares that reach inventory are lineage adjustments of their own type.
ALTER TABLE "CostValueAdjustment" DROP CONSTRAINT "CostValueAdjustment_type";
ALTER TABLE "CostValueAdjustment" ADD CONSTRAINT "CostValueAdjustment_type" CHECK ("adjustmentType" IN ('landed_cost',
    'revaluation', 'nrv', 'standard_change', 'std_price_difference', 'ml_price_diff', 'cost_adjustment',
    'policy_migration', 'price_difference_release', 'overhead_settlement'));

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['WorkCenterCapacity', 'AbsorptionSettlement', 'AbsorptionSettlementLine'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
