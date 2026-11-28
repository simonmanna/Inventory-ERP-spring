-- IPR Phase 2, X-9 (promoted for DEMO and GQDEMO by R6): partial output of a simple (single-stage) production order.
--
-- One row per partial receipt of the main output while the order is in progress. The order's open WIP is
--   materialCost + Σ "overheadAbsorbed" − Σ "wipCredit"
-- (docs/spec/manufacturing-wip-reconciliation.md §5.3), the same basis as the journals: each partial receipt absorbs
-- its share of the order's overhead into WIP and credits WIP with the value it takes out. The final completion takes
-- the rest of the pool. New table only: no existing row is changed, the ledger trigger stays enabled (R1).

CREATE TABLE "ProductionPartialOutput" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "orderId"          TEXT NOT NULL REFERENCES "ProductionOrder" (id),
    "productId"        TEXT NOT NULL,
    "variantId"        TEXT,
    "locationId"       TEXT NOT NULL,
    quantity           NUMERIC(20, 6) NOT NULL,
    "uomId"            TEXT,
    "quantityBase"     NUMERIC(20, 6) NOT NULL,
    "unitCost"         NUMERIC(20, 6) NOT NULL,
    "stockValue"       NUMERIC(20, 6) NOT NULL,
    "wipCredit"        NUMERIC(20, 6) NOT NULL,
    "overheadAbsorbed" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "batchNumber"      TEXT,
    "ledgerCode"       TEXT,
    "postedAt"         TIMESTAMP(3) NOT NULL,
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ProductionPartialOutput_positive" CHECK (quantity > 0 AND "quantityBase" > 0 AND "wipCredit" >= 0
        AND "overheadAbsorbed" >= 0)
);
CREATE INDEX "ProductionPartialOutput_order_idx" ON "ProductionPartialOutput" ("organizationId", "orderId");

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    ALTER TABLE public."ProductionPartialOutput" ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation ON public."ProductionPartialOutput";
    EXECUTE format('CREATE POLICY tenant_isolation ON public."ProductionPartialOutput" USING (%s) WITH CHECK (%s)',
        expr, expr);
END $$;
