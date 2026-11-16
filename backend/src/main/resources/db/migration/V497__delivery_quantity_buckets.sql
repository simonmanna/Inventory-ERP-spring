-- Phase A A3 (plan §2, §3, §9, §10, §11): claim model 2, quantity history per delivery line and operational
-- reconciliation findings. No UPDATE of business rows: existing claims keep resolution model 1 (decided claims keep
-- their old meaning: rejected = quantity billed); new claims are model 2.

-- Damaged goods get a claim kind of their own; a POD correction closes a shortage claim as reconciled.
ALTER TABLE "FleetDeliveryDiscrepancy" DROP CONSTRAINT "FleetDeliveryDiscrepancy_kind_check";
ALTER TABLE "FleetDeliveryDiscrepancy" ADD CONSTRAINT "FleetDeliveryDiscrepancy_kind_check"
    CHECK (kind IN ('shortage', 'excess', 'returned', 'weight', 'damaged'));
ALTER TABLE "FleetDeliveryDiscrepancy" DROP CONSTRAINT "FleetDeliveryDiscrepancy_status_check";
ALTER TABLE "FleetDeliveryDiscrepancy" ADD CONSTRAINT "FleetDeliveryDiscrepancy_status_check"
    CHECK (status IN ('pending', 'approved', 'rejected', 'reconciled'));

ALTER TABLE "FleetDeliveryDiscrepancy"
    -- Part of the claim closed by POD corrections; the open quantity is differenceQty − reconciledQty.
    ADD COLUMN "reconciledQty"   NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK ("reconciledQty" >= 0),
    -- 1 = before A3 (decided claims keep their old billing meaning), 2 = the A3 claim model.
    ADD COLUMN "resolutionModel" SMALLINT       NOT NULL DEFAULT 1 CHECK ("resolutionModel" IN (1, 2)),
    ADD COLUMN "dismissReason"   TEXT,
    ADD COLUMN "evidenceFileIds" TEXT[]         NOT NULL DEFAULT '{}';
-- Existing rows got 1 from the default above (no UPDATE); every new claim is model 2.
ALTER TABLE "FleetDeliveryDiscrepancy" ALTER COLUMN "resolutionModel" SET DEFAULT 2;
CREATE INDEX "FleetDeliveryDiscrepancy_line_idx" ON "FleetDeliveryDiscrepancy" ("loadLineId");

-- Quantity history of a delivery line (plan §9): one immutable row per change, written by the owning service in the
-- same transaction as the change.
CREATE TABLE "FleetLoadLineQuantityEvent" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "loadId"          TEXT           NOT NULL REFERENCES "FleetLoad" (id),
    "loadLineId"      TEXT           NOT NULL REFERENCES "FleetLoadLine" (id),
    kind              TEXT           NOT NULL CHECK (kind IN ('dispatched', 'pod_recorded', 'returned',
        'damaged_recorded', 'pod_corrected', 'customer_weight_recorded', 'claim_opened', 'claim_quantity_changed',
        'claim_confirmed', 'claim_dismissed', 'claim_reconciled', 'claim_withdrawn', 'excess_recorded')),
    bucket            TEXT,
    "previousQty"     NUMERIC(14, 3),
    "newQty"          NUMERIC(14, 3),
    "deltaQty"        NUMERIC(14, 3),
    "uomId"           TEXT,
    reason            TEXT,
    "userId"          TEXT,
    at                TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "evidenceFileIds" TEXT[]         NOT NULL DEFAULT '{}',
    "refType"         TEXT,
    "refId"           TEXT
);
CREATE INDEX "FleetLoadLineQuantityEvent_line_idx" ON "FleetLoadLineQuantityEvent" ("loadLineId", at);
CREATE INDEX "FleetLoadLineQuantityEvent_org_idx" ON "FleetLoadLineQuantityEvent" ("organizationId");

CREATE OR REPLACE FUNCTION fleet_quantity_event_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'FleetLoadLineQuantityEvent rows are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER "FleetLoadLineQuantityEvent_immutable" BEFORE UPDATE OR DELETE ON "FleetLoadLineQuantityEvent"
    FOR EACH ROW EXECUTE FUNCTION fleet_quantity_event_immutable();

-- Reconciliation findings (plan §10): operational (A3) and financial (A4). A finding stays open while a run still
-- detects it; a run that no longer detects it resolves it. Findings never change the records they point at.
CREATE TABLE "FleetReconciliationFinding" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"               TEXT           NOT NULL,
    scope                 TEXT           NOT NULL CHECK (scope IN ('operational', 'financial')),
    rule                  TEXT           NOT NULL,
    fingerprint           TEXT           NOT NULL,
    "deliveryCode"        TEXT,
    "loadId"              TEXT,
    "loadLineId"          TEXT,
    "deliveryOrderId"     TEXT,
    "deliveryOrderLineId" TEXT,
    "orderId"             TEXT,
    "orderLineId"         TEXT,
    "documentId"          TEXT,
    expected              NUMERIC(20, 6),
    actual                NUMERIC(20, 6),
    message               TEXT           NOT NULL,
    details               JSONB,
    status                TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    "detectedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolvedAt"          TIMESTAMP(3),
    resolution            TEXT
);
CREATE UNIQUE INDEX "FleetReconciliationFinding_open_key" ON "FleetReconciliationFinding" ("organizationId", fingerprint)
    WHERE status = 'open';
CREATE INDEX "FleetReconciliationFinding_scope_idx" ON "FleetReconciliationFinding" ("organizationId", scope, status);

-- Same policy as V214: a tenant transaction sees its own rows; work without a tenant (jobs, seeds) is not restricted.
ALTER TABLE "FleetLoadLineQuantityEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetLoadLineQuantityEvent"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
ALTER TABLE "FleetReconciliationFinding" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetReconciliationFinding"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

-- POD correction (plan §2): its own permission, held by administrators and by the roles that decide discrepancies.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
VALUES (gen_random_uuid()::text, 'sales:delivery:pod_correct', 'sales', 'delivery', CURRENT_TIMESTAMP)
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:delivery:pod_correct'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL AND (name = 'Administrator' OR permissions && ARRAY['fleet_dispatch:approve_discrepancy']);
