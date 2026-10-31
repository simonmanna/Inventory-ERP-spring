-- V29: Sales fulfilment (ADR-S011, phase S5; sales.md §4, §10) — physical quantity projections on order lines (from
-- fleet trips / POD / discrepancies), immutable billable-quantity decisions, the fulfilment event inbox and the
-- nightly reconciliation log. Authoritative records stay in fleet ("FleetLoadLine", POD, discrepancies).

ALTER TABLE "SalesOrderLine"
    ADD COLUMN "qtyLoaded"     NUMERIC(20, 6) NOT NULL DEFAULT 0,
    -- Net quantity that left the yard (dispatched − returned) — also feeds "qtyDelivered".
    ADD COLUMN "qtyDispatched" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    -- Received per proof of delivery.
    ADD COLUMN "qtyReceived"   NUMERIC(20, 6) NOT NULL DEFAULT 0,
    -- Received − approved discrepancies (fleet billable quantity).
    ADD COLUMN "qtyAccepted"   NUMERIC(20, 6) NOT NULL DEFAULT 0;

-- Every change of a line's billable quantity: automatic (by billing basis) or a manual override. Never edited.
CREATE TABLE "SalesFulfilmentDecision" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT           NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    "orderLineId"    TEXT           NOT NULL REFERENCES "SalesOrderLine" (id) ON DELETE CASCADE,
    basis            TEXT           NOT NULL CHECK (basis IN ('ordered', 'weighed', 'accepted', 'manual')),
    quantity         NUMERIC(20, 6) NOT NULL CHECK (quantity >= 0),
    "previousQty"    NUMERIC(20, 6) NOT NULL,
    reason           TEXT,
    "decidedById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesFulfilmentDecision_line_idx" ON "SalesFulfilmentDecision" ("orderLineId", "createdAt" DESC);
CREATE TRIGGER "SalesFulfilmentDecision_immutable" BEFORE UPDATE OR DELETE ON "SalesFulfilmentDecision"
    FOR EACH ROW EXECUTE FUNCTION credit_decision_immutable();

-- Inbox of fulfilment events applied to sales projections (duplicates are harmless: projections are recomputed
-- absolutely from the source records; the inbox gives traceability and lets the reconciliation spot gaps).
CREATE TABLE "SalesProcessedEvent" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "eventName"      TEXT         NOT NULL,
    "sourceType"     TEXT         NOT NULL,
    "sourceId"       TEXT         NOT NULL,
    "orderId"        TEXT,
    payload          JSONB        NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'applied' CHECK (status IN ('applied', 'ignored', 'failed')),
    error            TEXT,
    "processedAt"    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesProcessedEvent_order_idx" ON "SalesProcessedEvent" ("orderId", "processedAt" DESC);

-- Nightly comparison of stored projections with a rebuild from source records.
CREATE TABLE "SalesReconciliation" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "ordersChecked"  INTEGER      NOT NULL,
    "linesDrifted"   INTEGER      NOT NULL,
    details          JSONB        NOT NULL,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE "SalesFulfilmentDecision" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesFulfilmentDecision"
    USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesProcessedEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesProcessedEvent"
    USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesReconciliation" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesReconciliation"
    USING (("organizationId" = current_setting('app.org_id', true)));
